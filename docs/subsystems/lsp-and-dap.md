# LSP & DAP integration

How Editora talks to external language servers (LSP) and debug adapters (DAP). Part of [the docs index](../README.md).

Both protocols ride on lsp4j, both spawn external processes, and both route every external
process through one lifecycle owner (`process/ProcessRegistry`) so nothing outlives the app. The
two are also coupled at one point: Java debugging is hosted *inside* the running jdtls language
server, so the DAP layer reaches back through the LSP layer to start it. To add a new server or
adapter, follow the recipes in [extending.md](../extending.md) rather than the internals below.

## Dependency

LSP uses lsp4j (`org.eclipse.lsp4j` + `.jsonrpc`); DAP uses lsp4j.debug (`org.eclipse.lsp4j.debug`,
same version). Both are automatic modules that `jlink` can't link, so the `dist` profile's moditect
step injects descriptors — see [dependencies.md](../dependencies.md). jsonrpc reuses the existing
gson, and `module-info` carries `opens com.editora.lsp;` (unqualified — gson reflectively reads the
JDT vendor-notification DTOs, which under `mvn javafx:run` run in the unnamed module)
and `opens com.editora.dap` (gson parses jdtls's untyped `executeCommand` results).

The neutral value types ([`lsp/LspDiagnostic`](../../src/main/java/com/editora/editor/LspDiagnostic.java)
lives in `editor`; `dap/DapModels`) keep `editor`/`ui` free of any lsp4j wire type.

---

## LSP

### Server-centric registry

[`lsp/LspServerRegistry`](../../src/main/java/com/editora/lsp/LspServerRegistry.java) is the static,
pure source of truth: an editor **language id** (resolved by `editor.LanguageRegistry.forFileName`)
maps to a **server** — its id, default launch command (tokenized via `tokenize`, honoring quotes),
and project-root markers. The registry is server-centric, not language-centric: one server can serve
several language ids, so the `typescript` server's `languageIds` is
`{javascript, javascriptreact, typescript, typescriptreact}` and `clangd` serves `{c, cpp}`.

It ships **twenty-three** general-purpose servers (plus the Maven-aware `pom.xml` route). A few examples:

| Server id | Default command | Root markers (nearest-first) |
| --- | --- | --- |
| `java` | `jdtls` | `pom.xml`, `build.gradle`, …, `.git` |
| `typescript` | `typescript-language-server --stdio` | `tsconfig.json`, `jsconfig.json`, `package.json`, `.git` |
| `python` | `pyright-langserver --stdio` | `pyproject.toml`, `setup.py`, …, `.git` |
| `go` | `gopls` | `go.mod`, `go.work`, `.git` |
| `rust` | `rust-analyzer` | `Cargo.toml`, `.git` |
| `clangd` | `clangd` | `compile_commands.json`, `CMakeLists.txt`, …, `.git` |
| `astro` | `astro-ls --stdio` | `astro.config.mjs`, `astro.config.ts`, `package.json`, `.git` |

The rest cover XML (lemminx), JSON, Bash, YAML, PHP, Ruby, HTML, CSS, Kotlin, Lua, Dockerfile, SQL,
Terraform, TOML, C#, Typst, and Astro. Commands are user-configurable (Settings) and **never bundled** — servers
are auto-detected on the augmented PATH or installed in-app.

Useful pure methods: `serverIdFor(languageId)`, `isSupported(languageId)`, `rootMarkersFor(...)`,
`specFor(languageId, commands)` → a `ServerSpec`. The served language ids are mirrored (kept in sync
by hand) in [`EditorBuffer.LSP_LANGUAGES`](../../src/main/java/com/editora/editor/EditorBuffer.java),
which `isLspLanguage()` checks so `editor` imports nothing from `lsp`.

### One session per `(serverId, root)`

[`lsp/RootResolver`](../../src/main/java/com/editora/lsp/RootResolver.java) computes the workspace
root: the active Editora project folder (only when the file actually lives under it), else the
nearest ancestor containing a root marker, else the file's directory. One root → one server process,
shared by every file beneath it.

[`lsp/LspManager`](../../src/main/java/com/editora/lsp/LspManager.java) is the UI-facing facade
(mirrors `MermaidService`). It keys sessions by `serverId + " " + root.toUri()` — **not** by
language id — so js/ts/jsx/tsx in one project share a single `tsserver`. It owns:

- `sessionsByRoot` (`computeIfAbsent` starts a server on first open) and `sessionByDocUri` (routing).
- An async, per-server availability probe (`detect(serverId, cb)` → cached in `availableCache`,
  invalidated when that server's command changes or after an in-app install via `invalidateDetection()`).
- The document lifecycle (`openDocument`/`changeDocument`/`saveDocument`/`closeDocument`) and the
  request methods, each returning **neutral** results (`Target(file, line, character)` for
  definition/references) marshaled to the FX thread via `Platform.runLater`.

`LspManager` warms the augmented PATH off-thread in its constructor (`ProcessRunner::augmentedPath`),
since both detection and the FX-thread session start read it. Remote (SFTP) files are never managed —
`isManaged` bails on `!Vfs.isLocal` before `toUri()`, which would throw for such paths.

`shutdownAll()` is the restart/disable primitive and leaves the manager usable. A window that is closing
calls `close()` instead: it shuts everything down **and makes the manager terminal**, so a callback that
lands afterwards (a detection probe, a settings apply, a crash-restart) cannot open a document and fork a
server nothing owns. After `close()`, `openDocument`, `configure` and `detect` do nothing.

### `LanguageServerSession`: one process over stdio

[`lsp/LanguageServerSession`](../../src/main/java/com/editora/lsp/LanguageServerSession.java) drives
one external server process for a single root over stdio, via an lsp4j `Launcher` on a daemon
executor. Its remote interface extends `LanguageServer` with the raw JDT request names Editora uses;
this registration gives LSP4J the response types it needs to retain their JSON payloads. The session
implements `LanguageClient`, so it receives `publishDiagnostics`/log/show-message.

- **Launch** reuses `ProcessRunner.resolveExecutable`/`applyUserEnv` (the GUI-launch PATH fix, so
  a Finder-launched `.app` finds `jdtls`; the server keeps the user's locale — see
  [below](#processregistry--processrunner)), then registers the process with `ProcessRegistry.track`.
- **Handshake**: `initialize` (client capabilities + workspace folder + optional
  `initializationOptions`) → on success cache the server `ServerCapabilities`, send `initialized`,
  push that server's own configuration, then flush queued requests.
- **Settings are per server** (`lsp/LspServerSettings`), in both directions. The
  `workspace/didChangeConfiguration` push goes only to a server Editora has settings for (`python`: the
  `python.analysis` object; `java`: signature help, smart semicolon, on-type formatting) and every other
  server gets **no push at all** — vscode-json-language-server reads any pushed object without
  `json.validate.enable`, even `{}`, as "validation off". `workspace/configuration` is answered per server
  and section: the Python server's `python` / `*.analysis` objects (always with `autoSearchPaths: true`
  beside `autoImportCompletions`, because Pyright turns its default-on `src/` search path off as soon as an
  `analysis` object without that key exists); `{}` for the CSS server's `css`/`scss`/`less` and the HTML
  server's `css`/`html`/`javascript`, whose validators throw on `null`; and `null` for everything else —
  including the HTML server's `js/ts`, where `{}` makes its JavaScript mode throw. A new server's sections
  must be checked against the real server before anything other than `null` is answered.
- **Startup preparation** runs on `lsp-start`: per-project JDT workspace directory creation and installed-JDK
  discovery never execute on the JavaFX routing path.
- **One ordered writer.** LSP4J writes a message on the calling thread, and most callers are the FX thread.
  With the process pipe as the output a server that stops reading its stdin would block that `write()` and
  freeze the editor. The launcher is therefore given an [`AsyncPipeWriter`](../../src/main/java/com/editora/lsp/AsyncPipeWriter.java):
  `write` only appends to an in-memory FIFO and one daemon thread per session does the real pipe writes.
  Wire order is unchanged — it is still the order the session issues calls in, because LSP4J emits each
  message under its own lock and the queue has one consumer. The backlog is bounded (64 MB); reaching it
  means the server is wedged, so the session kills it and the ordinary crash-restart path takes over.
- **Queue-until-initialized**: `whenReady(action)` runs an action now if initialized, else parks it in
  `pending` to flush when `initialize` resolves. So a document open issued before the handshake
  completes is held rather than dropped.
- **Document sync** follows the negotiated mode: incremental servers receive a minimal splice against a
  per-URI shadow, with periodic full resynchronization; full-sync servers receive the whole document.
  Open/change/close shadow updates are serialized in wire order across initialization, and `didChange` is
  skipped when the server explicitly negotiates `TextDocumentSyncKind.None`.
- **Requests** cover completion and resolve, hover, signature help, definitions and related navigation,
  references, highlights, rename, code actions, document/range/on-type formatting, symbols, folding and
  selection ranges, pull diagnostics, semantic tokens, inlay hints, workspace symbols, call/type hierarchy,
  `executeCommand`, and the registered JDT extension requests used by Java editing and debugging.
  Ordinary requests use a shared 30-second bound that cancels the JSON-RPC future; initialization retains
  its 60-second budget. `bounded(request, timeout)` takes the budget as a parameter — `java/buildWorkspace`
  passes its ten minutes — and cancels its timer as soon as the request completes (the timer runs on one
  shared `removeOnCancel` scheduler), so a delivered result is not kept reachable for the rest of the
  timeout. Disposing or losing a session completes command/raw-request futures that were still queued for
  initialization.
- **Dynamic registration and refresh**: `client/registerCapability` and unregister update the effective
  capability object used by UI gates, including trigger/options data. Registration options arrive as raw
  JSON and are decoded with **LSP4J's own gson** (`LanguageServerSession.LSP_GSON`) — a plain `new Gson()`
  cannot read the `Either` fields (`SemanticTokensWithRegistrationOptions.range`/`full`) and either drops
  them or throws. Each registration is applied on its own, so one unusable entry cannot abort the batch.
  Rename and code-action registrations keep their options object (`prepareProvider`, `codeActionKinds`)
  rather than being reduced to a Boolean. Diagnostic, semantic-token, inlay-hint, and folding refresh
  requests immediately re-request data for managed open buffers; a capability change re-pushes every
  buffer gate, including Go to Implementation / Type Definition.
- **jdtls on-type formatting** is registered by the server only while `java.format.onType.enabled` is set.
  `Settings.lspOnTypeFormatting` is mirrored into it: in `initializationOptions`, in the configuration pushed
  after `initialized`, and again (to running Java sessions) when the setting flips. The opt-in
  `JdtlsDynamicRegistrationProbeTest` checks this, rename's `prepareProvider`, and semantic tokens against
  a real jdtls.
- **stderr must be drained.** A daemon thread (`drainStderr`) reads the server's stderr to EOF and
  logs the first 200 lines to the Debug Log. An undrained PIPE fills its ~64 KB OS buffer on a chatty
  server (jdtls logs heavily) and the server blocks mid-startup, deadlocking the handshake. Capturing
  it (rather than `Redirect.DISCARD`ing) surfaces *why* a launch failed — missing JDK, a lock, a bad
  command — which would otherwise be invisible in a packaged build.
- **Dispose** returns immediately on every path (it runs on the FX thread during a window close). For a
  live, initialized server a daemon thread then follows the protocol: `shutdown`, up to 2 s for the reply,
  `exit`, up to 1 s for the process to leave by itself, and only then `ProcessRegistry.killTree(process)`.
  Killing in the same call as `shutdown` meant `exit` was never delivered and jdtls's workspace closed
  uncleanly on every quit. A server that never answers is killed when the bound expires, and nothing on
  this path touches the pipe from the calling thread. Killing only the launcher would orphan the real
  server: jdtls is a wrapper script (Homebrew `jdtls` → python → java), and the orphaned JVM keeps its
  Eclipse workspace `.lock`, blocking the next session for that root.

### Per-jdtls workspace

jdtls deadlocks on its single shared default workspace's `.lock` when two roots — or a leaked previous
run — contend for it, so `initialize` never resolves (loading bar spins forever, dead completion).
`LspManager` gives each root a dedicated Eclipse workspace by appending `-data <dir>` to the launch
command. The dir is `jdtlsWorkspaceBase / workspaceDirName(root)`, where `workspaceDirName` is a stable
truncated SHA-256 of the root's absolute path (pure, unit-tested). `withDataDir` is a no-op if the
user's configured command already specifies `-data`. The workspace persists across sessions so jdtls's
index is reused. If a session dies before completing `initialize`, `LspManager` treats that data directory
as suspect: it removes the rebuildable cache when the Eclipse lock is free, or writes a sidecar failure
marker and selects a fresh suffixed directory when another process still owns the lock. Automatic crash
restarts are scoped to the failed `(server, root)` pair so one broken project cannot deactivate or restart
Java buffers belonging to another root. A deliberately disposed session keeps its workspace claim until its
process has actually exited (it is given a moment to shut down cleanly, and still holds the Eclipse lock
meanwhile), and a restart of the same `(server, root)` waits — bounded, on the start thread — for that
exit before launching.

### Diagnostics → overlay, stripe, minimap, Problems

A compact Java file's diagnostics are passed through without a message-based suppression rule. The bundled
JDT LS supports Java 25, including implicit `java.base` imports; the opt-in
`JdtlsCompactSourceProbeTest` checks a loose file and a Maven file for completion, no compiler errors,
and a real unresolved-type error. An older server's compatibility error remains visible rather than
being mistaken for harmless noise.

A server's diagnostics (pushed via `publishDiagnostics`, or *pulled* via `textDocument/diagnostic` for
servers with a `diagnosticProvider` such as vscode-html/css/json) are mapped by
[`lsp/DiagnosticMapper`](../../src/main/java/com/editora/lsp/DiagnosticMapper.java) into flat
`LspDiagnostic` records and routed through one callback. `mapReport` returns `null` for an "unchanged"
pull report (the caller keeps the current diagnostics). They surface in four places, all gated on the
buffer being LSP-active:

- `editor/LspDiagnosticOverlay` — squiggles (the Canvas-overlay idiom, visible paragraphs only).
- `editor/DiagnosticStripe` — marks docked over the scrollbar, click-to-jump.
- the minimap edge stripes.
- `ui/ProblemsPanel` (the `problems` tool window), grouped language → file → diagnostic.

A burst of publishes costs one Problems rebuild: the coordinator defers the rebuild to the end of the FX
queue so every publish already waiting lands first, and the panel skips it when the content is what it
already shows. Raw diagnostics kept for the code-action context are keyed by the **managed document URI**
(the spelling this client opened the document under), so a server that reports `file:///c%3A/…` or a
symlink-resolved path still gets its diagnostics back as `context.diagnostics`.

The Problems window defaults to **Open files** and can switch to **Whole project**. In open-file mode the
controller drops diagnostics for files without a tab; project mode retains project-wide publishes and is
selected automatically by Build Project. The open-tab lookup matches by
**canonical (symlink-resolved) path** (`MainController.canonicalPath` via `Path.toRealPath`) — a server
reports diagnostics under the file's real URI (`/private/tmp/…` for a `/tmp/…` symlink on macOS), so
plain `normalize()` matching silently dropped every diagnostic.

### Completion, symbols, and the loading bar

The [Java editing review](java-editing-review.md) maps the keystroke-to-popup pipeline, protocol
coverage, measured timings, behavioral regressions, and known differences from IDEA.
`CompletionSource` retains list completeness and a cancellation handle. Mapping/ranking and list
filtering run off FX; `CompletionSession` tracks compatible prefix edits and rebases acceptance ranges.
Member triggers run on the next FX turn, Java identifiers use a 90 ms settled milestone, and ordinary
prose/local completion retains its 280 ms milestone. Complete lists filter locally; incomplete lists
re-request with the correct trigger context. Additional edits have an independent bounded transform
history, so continued typing below an import does not silently lose that import. `SnippetSessions`
restores enclosing arguments after nested method snippets. `CompletionUndoManager` groups acceptance
with imports; `CompletionUndoFactory` safely commutes disjoint late imports through later history,
keeping that typing separate. Overlapping or unavailable history retains ordinary undo ordering.
Signature help retains a manually selected overload
across multiline refreshes and sends `activeSignatureHelp` with retriggers.

Java launches enable JDT's `java.lsp.joinOnCompletion` through `JDK_JAVA_OPTIONS`, unless the command
or inherited JVM environment explicitly sets it. Wire ordering alone does not await JDT lifecycle
jobs; the option prevents completion/resolve from reading an older working copy. The server waits
internally while FX remains asynchronous. The [review](java-editing-review.md) records the measured
latency tradeoff and the remaining same-file import conflict.

- **Completion** fires on the server's advertised trigger characters, not just `.` — `triggerCharsOf`
  reads `completionProvider.triggerCharacters` from the cached capabilities (so `<` triggers HTML, `:`
  triggers CSS). [`lsp/CompletionMapper`](../../src/main/java/com/editora/lsp/CompletionMapper.java) is
  the only place that touches lsp4j's `CompletionItemKind`/tags, mapping items into the editor's
  `Completion` popup entries (icon, detail, sort/preselect, deprecated flag, and the raw item as an
  opaque resolve token for the doc popup).
- **Structure outline** comes from `textDocument/documentSymbol`, mapped by
  [`lsp/DocumentSymbolMapper`](../../src/main/java/com/editora/lsp/DocumentSymbolMapper.java) into the
  neutral `SymbolNode` tree (handling both the hierarchical `DocumentSymbol` and legacy flat
  `SymbolInformation` forms). Rows display the symbol's 1-based source line; names, parameter types, and
  source-derived return types reuse the declaration's applied syntax classes (falling back from symbol
  kind), so they follow the active editor color theme. When no LSP outline is available, the fold/TextMate
  fallback only borrows a symbol from a
  preceding line for a standalone Allman-style opening brace; control-flow folds must not reuse nearby
  method or call symbols.
- **Loading bar**: the status bar shows an indeterminate bar while a server starts. It is cleared when
  `initialize` resolves — `LanguageServerSession` emits a synthetic `onStatus("ServiceReady", null)` on
  success / `("Error", null)` on failure. This is universal across every server (and a clean file that
  never publishes a diagnostic). Standard work-done progress and JDT's `language/status` and legacy
  `language/progressReport` notifications drive the same bar. Repeated report updates are coalesced so a
  project import cannot flood the JavaFX queue.

Capability gating throughout reads the cached `ServerCapabilities` through pure, null-safe predicates
(`formattingProvider`, `rangeFormattingProvider`, `documentSymbolProvider`, `semanticTokensProvider`,
`triggerCharsOf`), so a feature is offered only when the server advertises it. Dynamic registrations are
folded into that same effective object, so static and post-initialize providers follow one gating path.

### LspCoordinator

The whole integration lives in [`ui/LspCoordinator`](../../src/main/java/com/editora/ui/LspCoordinator.java)
(the `CoordinatorHost` feature-coordinator pattern). It owns the nav/format flows, the diagnostics
routing, and the configure/detect/gating + per-buffer lifecycle. `applySupport()` (init + every
settings apply) sets the jdtls workspace base, calls `lspManager.configure(...)`, then runs per-server
detection (the package-private `SERVER_IDS` array) and gating. `wireBuffer` installs the per-buffer
hooks (didChange, pull diagnostics, semantic tokens, completion, nav actions). `LspManager` itself
stays a `MainController` field (the DAP layer and the MCP bridge read it) and is passed in.

Workspace edits retain protocol versions and request-time text snapshots. Create, rename, and delete resource
operations are staged with overwrite backups and rolled back as a batch on failure or stale text. Production
application decodes unopened files through the host's background loader and runs filesystem staging and
cleanup on virtual threads; only RichTextFX mutation and tab/session bookkeeping run on the FX thread.
Resource preflight includes dirty deletion targets, overwritten destinations, narrowed buffers, and buffers
in other windows. Path changes retire the old URI before registering the new one.

**Moved and deleted files are followed by buffer identity, not by path.** The open buffer for every rename
source and delete target is resolved, with the buffer's own path, *before* the filesystem transaction —
afterwards the file is gone, so the server's spelling of the old path can no longer be canonicalised, and
when it differs from the tab's (a project opened through a symlink; `/tmp` on macOS) a second lookup finds
nothing. The remap then uses the tab's own old path, closes the LSP document under that path, and writes the
destination in the tab's spelling (`LspCoordinator.inSpellingOf`) so the tab stays under the project and
LSP root it was opened in. A tab that changed or closed while the transaction was staging rolls the edit
back; one that still did not follow its file is reported (`status.lsp.editTabNotRemapped`) and the edit is
**not** reported as applied. `MainController.remapProjectFileLocal` looks the tab up before it clears the
canonical-path cache for the same reason.

**Targets the server did not have open** (a cross-file rename reaching a file with no tab, or a tab that was
restored but never shown). jdtls sends a null version for every document, so these cannot be validated by
version, and reading the file once the response is in would only bless whatever is there now. The preimage
is established instead from time: `LspManager` stamps each request (`EditBasis.sentAtMillis`) and accepts a
closed target only when its modification time **predates the moment the request was sent** — then the
content on disk is what the server computed from (`WorkspaceEditMapper.unchangedSince`; a whole-second
timestamp needs a 2 s margin, since a coarse filesystem can record a later write as earlier). The file edit
is marked `diskPreimageAt`, and the applier re-checks it against the buffer it loaded: no window has unsaved
changes to the file, the buffer's recorded disk snapshot equals the file as it is now, and the file is still
unmodified since the request. A version the server attaches to a document it never had open is dropped
rather than compared. The check runs twice: on the FX thread **before anything is staged**, and again after
staging, where a target the same edit also moves (jdtls's answer to renaming a class from a usage site:
an edit to the declaring file plus a `RenameFile` of it) is examined at the **destination** the staged
rename put it — a move keeps size and modification time, and the old path no longer exists. Anything that
cannot be shown — an edit with no known request time, a changed, missing,
unsaved or not-yet-loaded file — refuses the whole edit, and the blocking files are named in the status
line (`status.lsp.editBlocked`) instead of a bare "Rename failed".

**Project overrides and trust.** A committed `.editora/settings.json` may replace a server's command or
switch a server on/off (`config/ProjectSettings`). `applySupport` hands `LspManager.configure` the
*effective* commands — the global ones with the project's laid over them — so an override is what actually
launches, not only what the status bar and Doctor show. Because that file lets a checkout choose a program
to run with the user's privileges, it is gated on `config/TrustStore`: a project-supplied command, or a
project `lspEnabled: true` for a server the user disabled globally, is honoured only when the project root
is trusted. An untrusted project gets the user's own settings (a project may always switch a server *off*),
a one-time status message, and the `lsp.trustProjectSettings` command, which lists what the file would run
before recording the same per-folder trust the build-wrapper prompt writes. `reloadProjectSettings` (every
settings apply, and a save of the project file) re-runs `applySupport` when the resolved overrides changed;
`syncBuffer` does the same when the window's project changed since the manager was configured, because a
window learns its project after `init` has already run. The `lsp.setServerCommand` prompt is prefilled from
the global value, never the project's, since it writes the global setting.

**The Astro TypeScript SDK is under the same gate.** astro-ls refuses to initialize without
`initializationOptions.typescript.tsdk` and loads — runs — the JavaScript in that directory, so the path
Editora computes is a choice of code to execute, with or without a project settings file.
`LspManager.astroTypeScriptSdk` takes the folder's own `node_modules/typescript/lib` only when the session
root is trusted (`setFolderTrust`, wired to `TrustStore.isTrusted`), and the upward walk for hoisted
workspaces stops at the topmost trusted ancestor (`trustedCeiling`) instead of running to the filesystem
root. Otherwise it uses the SDK installed beside the resolved `astro-ls`, which is the user's own. An
untrusted folder with no SDK beside the server gets **no server**: the session is dropped before the fork
(no crash notice, no auto-restart) and the status line says why and names `lsp.trustProjectSettings`
(`status.lsp.astroSdkUntrusted`). That command's prompt lists each folder SDK as an `astro: <dir>` line next
to the project file's commands, and trusting restarts the Astro server so it picks the SDK up. Revoking
trust does not stop a server that is already running; it applies from the next start.

Save completion first synchronizes the current open document, then sends `didSave` with the exact transformed
text written to disk when the server negotiated `includeText`; explicit and automatic saves share this path.
Pull and push diagnostics carry request generation, document version, and originating session checks through
FX delivery. Hover, signature, hierarchy, and navigation responses similarly validate the originating buffer,
path, version, and latest-request generation before changing UI state.

---

## ProcessRegistry & ProcessRunner

Every long-lived spawned server — LSP **and** DAP — is owned by
[`process/ProcessRegistry`](../../src/main/java/com/editora/process/ProcessRegistry.java) so it never
outlives the app. `LanguageServerSession.start`, `DapClient.connectStdio`, and `DapClient.setAdapterProcess`
all call `ProcessRegistry.track(process)`. Three mechanisms:

1. **`killTree(process)`** — destroy the descendant tree (SIGTERM, children first so a wrapper script
   can't reparent-orphan its real child), then schedule a force-kill of any survivor after `GRACE_MS`
   (1500 ms). **Non-blocking** (a daemon scheduler), so it's safe to call on the FX thread during a
   window close.
2. **JVM shutdown hook** (`installShutdownHook`, from `App.main`) runs `killAll` on exit — covering a
   normal quit *and* SIGTERM/`kill`/OS-quit/most crashes, the paths that bypass the window-close
   teardown. A server that was just sent `shutdown`/`exit` is registered with `expectExit`, and the hook
   first waits up to 3 s for those to leave by themselves. It then sends SIGTERM to every tracked tree, waits up to `GRACE_MS` for them to exit (the
   wait ends as soon as the last one is gone), and only then force-kills survivors — so a server can
   release its workspace lock and a running program is not cut off mid-write.
3. **On-disk ledger** + **`reapOrphans()`** (once from `App.start`, before any window builds): kills any
   server leaked by a previous run that died too hard for the hook (SIGKILL / power loss). Each process
   writes **its own** ledger file, `<configDir>/spawned-servers.<pid>.<startMillis>.txt`, and every row
   carries its **owner** (pid + start instant + executable of the Editora process that spawned it). A
   row is reaped only when its owner is gone (`ownerGone`) **and** the recorded pid, start instant and
   executable all still match (`shouldReap`, so a reused PID is never killed). A second Editora on the
   same config dir therefore neither kills the first one's live servers nor rewrites its rows. The
   pre-owner shared file `spawned-servers.txt` is still read at startup (its rows have no owner and are
   reaped as before) but never written. The `LedgerEntry` parse/format and both decisions are pure and
   unit-tested; `ProcessRegistryLedgerProcessTest` runs them against real processes.

`ownerGone` is deliberately conservative: a live process at the owner's pid whose start instant looks
different but whose executable matches is treated as **alive**. Two JVMs can disagree about one
process's start time (on Linux it is derived from the boot time, which moves when the wall clock is
stepped), and wrongly declaring a running editor dead would kill its servers.

[`process/ProcessRunner`](../../src/main/java/com/editora/process/ProcessRunner.java) is the only
subprocess chokepoint, and it builds **two child environments**:

- **Parse-stable** — `run`/`runBytes`/`applyStandardEnv`: `LC_ALL=C` plus the augmented PATH. Only for
  output Editora parses or where only the exit code matters (git, ripgrep, `gh`, version probes).
- **User locale** — `runInUserLocale`/`applyUserEnv`: the augmented PATH only; the locale is inherited.
  For every long-lived or user-facing child (language servers, debug adapters, Run, Build, the agent
  CLI, the terminal, the browser), every tool whose output is shown or inserted rather than parsed
  (External Tools, before-launch steps, plugin commands, installers, diagram/Typst renderers), and any
  JVM tool handed the user's paths (Maven, Gradle, `javac`). Under `LC_ALL=C` a JVM decodes file names
  as ASCII: `java año/H.java` fails with `invalid path for source file: a??o/H.java`.

`ChildLocalePolicyTest` lists which production files may use which, so a new spawn site has to be
classified deliberately.

The **augmented PATH** is common to both. A Finder-launched `.app` (or a `.desktop`) inherits a stripped PATH without
Homebrew/npm/Node dirs, so `jdtls`/`node`/`pyright` wouldn't be found. `augmentedPath()` =
inherited PATH + the user's **login-shell PATH** (`$SHELL -l -i -c` once, fenced by markers — the
`extractMarked` parse is unit-tested) + the hardcoded `EXTRA_PATH_DIRS`. The login-shell step recovers
version-manager bin dirs that can't be hardcoded (nvm's `~/.nvm/versions/node/<ver>/bin`, fnm, asdf,
volta). `resolveExecutable` then rewrites a bare command name to its absolute path against that PATH,
because Java's Unix `ProcessBuilder` resolves the executable against the JVM's own stripped PATH, not
the child env. The result is cached after the first call.

---

## DAP

### Three transports

Editora debugs Java, Python, and JavaScript (Node), off by default
(`Settings.debugSupport`). [`dap/DapServerRegistry`](../../src/main/java/com/editora/dap/DapServerRegistry.java)
is the pure language → adapter spec (a `Kind` transport, the DAP `launch` type, the `initialize`
adapter id, and the default interpreter + adapter args). `languageIdsForDebug()` =
`{java, python, javascript}`.

| Language | `Kind` | How |
| --- | --- | --- |
| `java` | `JDTLS` | The Microsoft java-debug adapter is started *inside* jdtls via `workspace/executeCommand` (`vscode.java.startDebugSession` returns a port), then `DapClient.connect(port, "java")` opens a socket. |
| `python` | `STDIO` | `<python> -m debugpy.adapter`; `DapClient.connectStdio(proc, "python")` wires DAP to the process's stdin/stdout. |
| `javascript` | `SOCKET` | `node <dapDebugServer.js> <port>`; `DapClient.connect(port, "pwa-node")`. |

[`dap/DapClient`](../../src/main/java/com/editora/dap/DapClient.java) (mirrors `LanguageServerSession`)
runs the handshake over an lsp4j.debug `DSPLauncher`: `initialize` → on the `initialized` event send
`setBreakpoints`/`setExceptionBreakpoints` then `configurationDone`, **without** `.join()` — that
callback runs on the reader thread, and lsp4j serializes outgoing messages, so blocking would deadlock
on a response delivered by the same thread. Events arrive on the launcher thread; the `Host`
(`DapManager`) marshals them to FX. `dispose()` disconnects, closes the socket, and
`ProcessRegistry.killTree`s the adapter subprocess tree (same wrapper-orphan reasoning as LSP). The
standalone adapters' stderr is `Redirect.DISCARD`ed by `DapManager` (an undrained PIPE deadlocks, like
the LSP servers; DAP traffic is on stdin/stdout or the socket).

**Child sessions (vscode-js-debug).** js-debug never debugs the program on the connection that launched
it. That root session only starts the launcher; for every debuggee it sends the reverse request
`startDebugging` and waits for a *second* connection to the same port whose `launch`/`attach` carries the
configuration it supplied, including `__pendingTargetId`. lsp4j's default handler throws, which is why
JavaScript sessions used to sit in RUNNING with breakpoints that could never hit. `DapClient` implements
it: a root client (socket transports only — `initialize` declares `supportsStartDebuggingRequest` there)
opens one child `DapClient` per request, gives it the current breakpoints and exception filters, and
launches it with the supplied configuration; the child's own `initialized` handshake then installs them and
sends `configurationDone`. Children are internal to the root: their stops, output and termination are
forwarded to the single `Host`, inspection and control requests are addressed to the child that last
stopped (`target()`), live breakpoint/filter changes are broadcast to every session, and children are
disposed with the root. The session ends when its last child does. A root `terminated` that arrives while
children are still alive is held back for up to a second, because nothing orders the two sockets: measured
against the real adapter, the root's event overtakes the child's final `output`, and ending there would
drop the program's last lines. The socket connect tries both `127.0.0.1` and `::1` — js-debug binds
`localhost`, which resolves to the IPv6 loopback first on many systems — and `telemetry`-category output
is not shown. Known limits: with several simultaneous targets (a debuggee's subprocesses) only the most
recently stopped one is shown, and there is no UI to switch between them. `FakeDebugAdapter` (test
sources) plays js-debug's handshake over real sockets in the default suite; the opt-in
`JsDebugProbeFxTest` drives the real adapter and Node through `DapManager`
(`./mvnw test -Dtest=JsDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true`).

### DapManager & the java-debug bundle

[`dap/DapManager`](../../src/main/java/com/editora/dap/DapManager.java) is the UI facade (mirrors
`LspManager`+`RunService`). It owns the single active session (one debug session at a time, like Run)
and dispatches `startLaunch(file, language, picker)`. Adapter `output` events reach the console through
[`dap/DapOutputPump`](../../src/main/java/com/editora/dap/DapOutputPump.java), which mirrors the Run
console's pump: a bounded queue, one scheduled drain at a time, a bounded slice per drain with neighbouring
events joined into one append, and a "truncated" notice when the debuggee outruns the UI; the queue is
flushed before a session ends so its last lines are not lost. Step Over/Into/Out put the state back to
RUNNING until the next stop, exactly like Resume. Launch paths:

- **java** → resolve main class (`vscode.java.resolveMainClass`) → `resolveClasspath` →
  `resolveJavaExecutable` → `startDebugSession` → connect the socket → `launch`. A loose file with
  no project falls back to `javac -g` compilation. A compact `.java` file takes that compile path
  directly: its implicit class is named for the file, even if the file declares nested types. The
  compiler, launcher, and debuggee environment all use the selected JDK; compilation runs from the
  file's directory so neighboring source files can resolve.

An extensionless Java shebang (`java --source 25+`) cannot be passed directly to `javac` as a source
filename. Debug writes a temporary `.java` copy with the shebang line blanked, preserving line numbers,
then compiles it with `--release` under the selected JDK. Breakpoints sent to the adapter target that
copy; stack frames are mapped back to the user's original path. The temporary source and compiled
classes are removed when the session ends or compilation fails, including a cancelled startup.

The opt-in `CompactSourceDebugProbeFxTest` drives the installed JDT LS and Java debug adapter through
`DapManager`: it verifies that a breakpoint in a loose compact `.java` file and an extensionless
shebang stops on the original source line, its local variable is readable, and step-over advances both
the line and value. Run it with
`./mvnw test -Dtest=CompactSourceDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true`.
It also starts an extensionless shebang through the window's `debug.start` command and checks that
stopping the session removes the temporary compilation directory. Pull-request CI installs JDT LS
and the Java debug adapter, then runs this probe and `JdtlsCompactSourceProbeTest` in the
`Compact Java integration` job.
- **python/javascript** → `startProgram`: snapshot breakpoints on the FX thread, then off-thread spawn
  the adapter + connect + `launch`.

The Java path is hosted on jdtls: that's why the DAP layer takes the `LspManager`. jdtls is started
with the java-debug plugin jar in `initialize.initializationOptions.bundles`
(`LspManager.setDebugBundles` → the `{"bundles":[…]}` option), which registers the `vscode.java.*`
debug commands. Toggling debug on **restarts jdtls** so it reloads with the bundle.

[`dap/DebugAdapterLocator`](../../src/main/java/com/editora/dap/DebugAdapterLocator.java) finds each
adapter (pure name-match + version comparison + filesystem scan): the java-debug jar
(`com.microsoft.java.debug.plugin-*.jar`, newest wins, from a configured path / VS Code / mason /
Editora's plugin dir), `dapDebugServer.js`, and a `debugpy` package dir for `PYTHONPATH`.
[`dap/LaunchConfig`](../../src/main/java/com/editora/dap/LaunchConfig.java) shapes the DAP `launch`/
`attach`/`program` argument maps (pure, unit-tested; attach stays Java-only). The neutral records
exposed to the UI are in [`dap/DapModels`](../../src/main/java/com/editora/dap/DapModels.java)
(`ThreadInfo`, `StackFrameInfo`, `ScopeInfo`, `VariableInfo`, `EvalResult`, `LineBreakpoint`,
`FileBreakpoints`).

### Breakpoints

Breakpoints are persisted per-project in `breakpoints.json` (`config/Breakpoint` +
`config/BreakpointStore`), tracked through edits by `editor/BreakpointManager` (mirrors
`BookmarkManager`; the pure `shift`/`reanchor` are unit-tested), and shown in a leftmost gutter strip
that toggles a breakpoint on click. They are snapshotted on the FX thread at session start and sent to
the adapter on its `initialized` event (DAP is 1-based; the model is 0-based).

### DebugCoordinator

The integration lives in [`ui/DebugCoordinator`](../../src/main/java/com/editora/ui/DebugCoordinator.java)
(`CoordinatorHost`). It owns the `DebugPanel`, breakpoint persistence + gutter gating, the DAP event
sink, the inline-values/hover/execution-line editor surfaces, the start/step/run-to-cursor/jump flows,
and `wireBuffer`. `applySupport()` configures all three adapters, pushes the java-debug bundle into
LSP (restarting jdtls when it changed), async-detects python/js, and gates each buffer's breakpoint
gutter. `debugEffectiveFor(language)` gates start (java = the jdtls server enabled + available + plugin
found, via `lspCoordinator.isServerAvailable("java")`; python/js = their enable + detected adapter).
`DapManager` stays a `MainController` field (built on `lspManager`) and is passed in, mirroring the
`lspManager`/`LspCoordinator` split.

---

## Adding a server or adapter

See [extending.md](../extending.md) — adding an LSP server is one `ServerDef` entry plus its id in the
coordinator's `SERVER_IDS` and the served language ids in `EditorBuffer.LSP_LANGUAGES`, plus a Settings
command/enable pair; adding a DAP adapter is one `Def` entry in `DapServerRegistry`.
