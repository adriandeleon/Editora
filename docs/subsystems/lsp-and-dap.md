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

The neutral value types ([`lsp/LspDiagnostic`](../../src/main/java/com/editora/text/LspDiagnostic.java)
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

The rest cover XML (lemminx), JSON, Bash, YAML, PHP, Ruby, HTML, CSS, Kotlin (JetBrains' Kotlin LSP: a pinned
~370 MB distribution with its own Java runtime, installed by `InstallCatalog.Kind.PINNED_SERVER` and launched
as `bin/intellij-server --stdio`; `kotlin-lsp --stdio` on PATH by default, fwcd's `kotlin-language-server` as a
configured command; a Gradle settings file is the root before any build file, and there is no `.git` fallback),
Groovy (gvy's `gls` jar, pinned, run with `java -jar` on the Settings JDK; CodeNarc style rules off), Scala
(Metals 1.6.9, resolved by a pinned coursier into `plugins/lsp/scala/lib` and run with
`java … -cp "lib/*" scala.meta.metals.Main` on the Settings JDK), Clojure (clojure-lsp, a GraalVM native binary;
the installer pins the 2026.10.15 release per platform by SHA-256, else `clojure-lsp` on PATH; it serves `.clj`,
`.cljs`, `.cljc` and `.edn` and asks `clojure -Spath`, `lein classpath` or `bb print-deps` for a project's
classpath, which the augmented PATH lets it find), Lua, Dockerfile, SQL, Terraform, TOML, C#, Typst, and Astro.
Commands are user-configurable (Settings) and **never bundled** — servers
are auto-detected on the augmented PATH or installed in-app.

Useful pure methods: `serverIdFor(languageId)`, `isSupported(languageId)`, `rootMarkersFor(...)`,
`specFor(languageId, commands)` → a `ServerSpec`. The served language ids are mirrored (kept in sync
by hand) in [`EditorBuffer.LSP_LANGUAGES`](../../src/main/java/com/editora/editor/EditorBuffer.java),
which `isLspLanguage()` checks so `editor` imports nothing from `lsp`.

### One session per `(serverId, root)`

[`lsp/RootResolver`](../../src/main/java/com/editora/lsp/RootResolver.java) computes the workspace
root: this **window's** project folder (only when the file actually lives under it — compared by real
path, so a project opened through a symlink and a location reported under its resolved path are one
workspace), else the nearest ancestor containing a root marker, else the file's directory. A marker found in
the user's home directory or at a filesystem root is ignored: a dotfiles `~/.git` must not hand a server the
whole home directory. One root → one server process, shared by every file beneath it. A session with no open
document is evicted after a grace period unless something holds a lease on it (`LspManager.retain`): a Java
debug session runs inside its jdtls, so `DapManager` retains that session until the debug session ends.

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
since both detection and the FX-thread session start read it. A remote (SFTP) file is served by a server on
its host, and debugged there; how documents are named across the connection (`WireUris`, `DapPaths`), where
jdtls keeps its workspace and how the debuggers are reached is in [remote](remote.md).

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
- **lemminx's settings** (`lsp/XmlServerSettings`, Settings → LSP, the XML card) go to the `xml` and `maven-pom`
  servers through all three channels: `initializationOptions.settings.xml` (so the first validation already
  has them), a push on change (`LspManager.setXmlSettings`, run by every `LspCoordinator.applySupport`, so a
  trust decision is read again), and `workspace/configuration` answers by dotted path. The object is built per
  session root: `xml.downloadExternalResources.enabled` follows `xmlSchemaDownloads` (`trusted` = that root's
  folder trust; the shared loose-files root is never trusted), relative catalogs and grammar paths resolve under
  the root, and `xml.server.workDir` puts lemminx's cache at `<config>/cache/lemminx` (a GraalVM native lemminx
  takes `user.home` from passwd, so neither `$HOME` nor a test's `user.home` reached it). The pom server's push
  repeats lemminx-maven's own `xml.maven` object, which it reads whole. `xml.format.tabSize`/`insertSpaces`
  are answered from the document's indentation, as jdtls's `java.format.*` are.
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
  requests are advertised (`workspace.*.refreshSupport` — servers send none without it) and re-request
  data for managed open buffers, coalesced over a 100 ms window; semantic tokens and inlay hints are
  re-requested for the active buffer only, the one their replies are applied to. A capability change re-pushes every
  buffer gate, including Go to Implementation / Type Definition.
- **jdtls on-type formatting** is registered by the server only while `java.format.onType.enabled` is set.
  `Settings.lspOnTypeFormatting` is mirrored into it: in `initializationOptions`, in the configuration pushed
  after `initialized`, and again (to running Java sessions) when the setting flips. The opt-in
  `JdtlsDynamicRegistrationProbeTest` checks this, rename's `prepareProvider`, and semantic tokens against
  a real jdtls. The on-type request is issued one pulse **after** the keystroke (the `KEY_TYPED` filter runs
  before the character is inserted), and it and Tab's range-format re-indent flush the debounced `didChange`
  first, so the server is asked about the line as it reads on screen.
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
as suspect: once the failed server's process has exited (bounded wait — the kill is non-blocking, and a
dying JVM still holds the lock) it removes the rebuildable cache when the Eclipse lock is free, or writes a
sidecar failure marker and selects a fresh suffixed directory when another process still owns the lock. A
marker is re-tested on every later claim and removed with its cache as soon as the lock is free. Automatic crash
restarts are scoped to the failed `(server, root)` pair so one broken project cannot deactivate or restart
Java buffers belonging to another root. A deliberately disposed session keeps its workspace claim until its
process has actually exited (it is given a moment to shut down cleanly, and still holds the Eclipse lock
meanwhile), and a restart of the same `(server, root)` waits — bounded, on the start thread — for that
exit before launching.

### One jdtls per project across windows

Windows of one process share a project's Java server (Java review C12) instead of each starting a jdtls on a
workspace of its own (`-2`), which cost another ~1.5 GB and a cold import. `LspManager.SHARED_SERVERS` (static;
tests get a private registry from `setSessionStarterForTest`, and `shareServersForTest` joins two) maps a local
root's Java session key to its session, and `sessionForRoot` joins one that is running before it creates
another (`join`). A remote or container root is never shared.

A `LanguageServerSession` serves its windows through `LanguageServerSession.Client`s: the window that started it
holds `primaryClient()` (what the older setters configure), others `attach` their own. Diagnostics, status,
refreshes, request outcomes and death reach every client. `workspace/applyEdit` and the client-UI questions
(`showMessageRequest`, Metals' prompts, `chooseImports`) go to the client whose window has the focus
(`LspManager.setWindowFocused`), else the longest-attached one. Each document is opened per window
(`didOpen(uri, …, opener)`): the server hears the first open and the last close, and a second window's open is
sent as a change, so the server holds the text of the window it last heard from (each window syncs before
it asks).

A window that closes, turns its language servers off, or lets its last document on the server go idle only
leaves (`leaveShared`): its documents are closed on the server and its client detached. The workspace claim
belongs to the session (`jdtlsWorkspaceBySession` is per process), so the window that finally stops the server
releases it, whichever window started it. The last window to leave stops it.
A restart (`restartAll`, `shutdownServer`, a changed command) stops it for every window: `reportEndedForAll`
tells the others at once, and their `dropSession` — the session ended, not by this window (`endedHere`) —
calls `onSharedServerEnded`, which `LspCoordinator.sharedServerEnded` answers by reopening that window's
buffers on the new server, with no crash notice. A crash is a crash in the window whose client is told
first and a quiet reopen in the others, so it is counted once towards the crash-loop cap.

Nothing deletes a workspace on its own: there is one per Java folder ever opened, each ~40 MB before the
project's own classes (jdtls indexes the JDK into every one). `lsp.clearJavaIndexCaches` runs
`LspManager.clearJdtlsWorkspaces` off the FX thread: it claims each directory in the same static claim set the
session start uses (so no session can start on a half-deleted cache), skips it when the claim is taken or
another process holds the Eclipse lock, deletes the rest with their failure markers, and reports the count and
bytes freed. It works with LSP off, and leaves remote hosts' `jdtls-workspaces` alone.

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

Java launches enable JDT's `java.lsp.joinOnCompletion` on the server's command line (`--jvm-arg=` for
the jdtls launcher script, `-D` for a direct `java`; `JDK_JAVA_OPTIONS` only as the fallback for an
unrecognized wrapper, because a debuggee started inside jdtls inherits the environment), unless the command
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

### Client-driven jdtls actions

Some jdtls code actions answer with a command the **client** must carry out — sending it back as
`workspace/executeCommand` fails. Which actions do so is decided by `extendedClientCapabilities`
(`LspManager.javaExtendedClientCapabilities`): a flag is declared only together with the code that
drives its command.

- [`lsp/JdtlsGenerate`](../../src/main/java/com/editora/lsp/JdtlsGenerate.java) — the
  `java.action.*Prompt` commands: toString, hashCode/equals, constructors, override/implement, getters
  and setters, delegate methods. A check request lists candidates, a `MultiSelectPicker` chooses, a
  generate request answers with the edit. Delegate methods are two levels (a field, then its methods);
  the accessor prompt's argument carries the accessor kind and must go back unchanged.
- [`lsp/JdtlsRefactor`](../../src/main/java/com/editora/lsp/JdtlsRefactor.java) —
  `java.action.applyRefactoringCommand` with `[name, CodeActionParams, info?]`. Move asks
  `java/getMoveDestinations` (packages, or an instance method's parameters and fields) or
  `java/searchSymbols` (the project's types) and then `java/move`; Extract Interface asks
  `java/checkExtractInterfaceStatus`; Change Signature asks `java/getChangeSignatureInfo`, shows the
  signature as one editable line and maps the edited parameters back to their `originalIndex` (by name,
  then by type for a renamed one; `= value` marks a new parameter). Both end in `java/getRefactorEdit`,
  which is also all an extract refactoring needs. jdtls offers Extract Interface only when
  `advancedExtractRefactoringSupport` is declared as well, and Change Signature with no flag at all.
  With `inferSelectionSupport` jdtls offers Extract Method, Variable and Field at a bare caret; the
  client then asks `java/inferSelection` for the candidate expressions, picks one (a picker when there
  are several) and sends that `SelectionInfo` as the command argument. Extract to Field and Convert
  Local to Field ask where to initialize the field when the server offers more than one
  `initializedScopes` entry; the scope goes first in `commandArguments`. With
  `moveRefactoringConfirmationSupport` (jdtls 1.61 final and newer) a Move Instance Method that jdtls
  finds problems with answers `{errorMessage, confirmationToken}` and no edit; the user sees the problems
  and the move is asked again with the token only if they continue.
  A `java/getRefactorEdit` answer can carry a `java.action.rename` command with `{uri, offset, length}` of
  the name it picked, offsets in the edited document (`JdtlsRefactor.renameAt`); after an extract Editora
  puts the caret there and starts Rename. Change Signature and Move pass a `preview` to
  `LspManager.jdtlsApplyEdit`: the planned edit goes through the rename preview when it touches other
  files, and only the ticked files are applied.

### Library source tabs

A `jdt://` definition opens as a read-only, path-less buffer (`LspCoordinator.openLibraryDefinition`),
remembered in `librarySources` with the workspace file whose session produced it. Position requests from
such a tab — definition, hover, references, implementation, type definition, declaration and both
hierarchy prepares — go through `navOrigin()`: the anchor file's session, asked about the tab's `jdt://`
URI (the `…At(anchor, documentUri, …)` methods of `LspManager`). jdtls resolves the URI itself, so no
`didOpen` is sent. References into class files are dropped (the References window is file-based); a
hierarchy node with a `jdt://` URI opens as library source. Search Everywhere's Symbols group adds
jdtls's `workspace/symbol` types that live in `jdt://` (`LspManager.javaLibraryTypes`), as a second batch
after the index's own (`SearchEverywherePopup.Ops.laterSymbols`).

Peek Definition goes through `navOrigin()` too, and a `jdt://` target is peeked from
`java/classFileContents` (on the declaration, past its Javadoc) rather than opened. Move into a package that
does not exist yet creates its folder under the current package's source root
(`JdtlsRefactor.currentSourceRoot`), announces it with `workspace/didChangeWatchedFiles`, and sends
`java/move` a destination that is the folder's URI string, which jdtls accepts in place of a package node.
After each `ProjectStatus OK`, `LspCoordinator.buildAfterImport` runs one incremental `java/buildWorkspace` per
Maven or Gradle Java root with an open file (never a loose file's invisible project, which Debug compiles itself); diagnostics jdtls then publishes on a project folder (JDK and compliance
problems) are listed in Problems in either scope and said once in the status line.

**Launch extras.** `JavaLombok` adds `-javaagent:` with the newest Lombok jar from `~/.m2` or Gradle's module
cache when the root's build file, or a module's one level down, declares Lombok (`JdtlsLombokProbeTest` checks
the agent against a real jdtls with `-Dlsp.probe.lombok=PATH`). `LspServerSettings.javaSettings` also sends
vscode-java's `completion.favoriteStaticMembers` and the `compile.nullAnalysis` annotation lists, with mode
`automatic`: vscode-java's `interactive` answers through a command only it implements.

**A project's own preferences.** `JavaProjectSettings.forRoot(root)` reads IntelliJ's `ij_java_imports_layout`
and import-on-demand counts from `.editorconfig` (`EditorConfig.rawFor`, which keeps keys Editora does not
interpret) and the Eclipse formatter profile Spotless names (else `eclipse-formatter.xml` and friends at the root),
and is merged into `settings.java` at `initialize` (`LspManager.withProjectSettings`) and in every push
(`LanguageServerSession.pushConfiguration`). `JdtlsProjectStyleProbeTest` checks the star threshold, the profile
and chain completion (`completion.chain.enabled`) against a real jdtls.

**Lombok on request.** A root whose build declares Lombok, with no jar in the Maven/Gradle caches or in
`<config>/plugins/lsp/lombok`, reports `onLombokMissing`; `LspCoordinator.offerLombok` asks once per root,
downloads `InstallCatalog.LOMBOK` (pinned, SHA-256) with `InstallService.installLombok`, and restarts jdtls.

**Actions on save.** `LspCoordinator.afterUserSave`, called by `FileWorkflowCoordinator` for explicit saves only,
runs `LspManager.organizeImportsQuietly` (the session answers `chooseImports` with no choice while it runs) and/or
Format Document, then saves once more when they changed the text (`Settings.javaOrganizeImportsOnSave`,
`javaFormatOnSave`). Clean-ups go first (`javaCleanupOnSave`): `LspManager.cleanUpJava` sends jdtls's
`java/cleanup` request, which applies the ids in `java.cleanup.actions` and answers a `WorkspaceEdit`. The ids
(`Settings.javaCleanups`, filtered through `JavaCleanups.known`) reach the server in every configuration push.
Editora does not use jdtls's own `java.saveActions.cleanup`, which needs `willSaveWaitUntil`; **Clean Up File**
(`lsp.cleanUp`) sends the same request by hand.

jdtls's "Javadoc comment" completion is offered only on a `/**` line that is already closed. In a Java
buffer with the server on, the `*` that makes a line `/**` therefore inserts `* */` and requests completion
(`EditorBuffer.closeJavadocOpening`, rule in `CommentContinuation.closesDocOpening`).

### Files written behind the server's back

jdtls tracks a file only through `didOpen` or `workspace/didChangeWatchedFiles`. A workspace edit that
writes a file with no tab (`WorkspaceFileOperations` saves it straight to disk), a file the edit creates,
and a Project-tree rename are each announced with `didChangeWatchedFiles` (`LspCoordinator.resourceEvents`,
`afterWorkspaceEdit`); without it the next rename skipped the file and left it broken. A Project-tree rename of
a `.java` file, and each file of a drag-move (`ProjectPanel.moveThrough`, also Undo Move), goes through
`workspace/willRenameFiles` first (`LspCoordinator.willRenameFile`); jdtls rewrites a moved file's package only
for a target package it knows: the edit the
server answers is applied and saved, and the move is recorded with it, so Undo Last Refactoring moves the
file back and reverts the edit together. A folder rename is applied but not undoable as one. Saving a Java file
the server does not have open (a deferred tab) sends `didChangeWatchedFiles` CHANGED. A formatter profile is
pushed as a content-addressed copy (`JavaProjectSettings.contentAddressed`), since jdtls reads
`java.format.settings.url` again only when it changes. `LspManager.holdForInstall` stops a server in every window
while Doctor's Update swaps its folder, and no window starts it until `releaseAfterInstall`. A project with no formatter
profile is always sent an empty one, since jdtls keeps the last profile in its workspace across restarts; a project
window watches `.settings`, `config` and the profile's folder (`JavaProjectSettings.styleFolders`) though the tree
hides them. While jdtls's `chooseImports` question is open, no request on that session times out
(`LanguageServerSession.holdMillis`), and the request's end closes the picker. Restart stops one project's server
(`LspManager.shutdownServerAt`), not every server of that language. A tree rename records its edit before it saves the tabs the edit changed (a save
can run a nested event loop), and the drag's end waits for any rename still in flight (`renamesInFlight`). jdtls is
launched on the Java its installed bundles require (`JavaRuntimes.serverMinMajor`, from their
`Bundle-RequiredExecutionEnvironment`), not on its launcher's own floor. A shared session keeps jdtls's last
`ProjectStatus` and its project-level diagnostics, and replays them to a window that joins; while jdtls has not
said `ServiceReady` itself (it is still importing) a joining window is told "Starting", not the handshake's ready.
Go to Super Method (`LspCoordinator.gotoSuperMethod`) finds the member at the caret in `MemberSpan`s (whole ranges,
every child kept, unlike the Structure outline), reads lambdas and `super(...)` calls with `JavaMembers`, and walks
the type hierarchy (`SuperMethodWalk`) for what `java/findLinks` misses: findLinks answers first and is exact for a class's
method (empty means none: a static, private or overloading method, unless it is marked `@Override`: findLinks misses
an inner class of a generic class), but skips interface methods, a second interface and some anonymous classes. The
walk matches override-equivalent signatures (`JavaMembers.overridden`: parameter types, a type variable matching any
reference type; the type variables are those the type and the types around it declare in their outline names, plus
any one-letter-shaped name for a method's own). A type variable the subtype's header binds (`extends Store<String>`,
read with `JavaMembers.typeArguments` from each project file's text, or an open file's buffer, which jdtls's positions
follow; an anonymous class's `<>` from its field's type) matches that type alone. Only a mention outside every `<…>`
binds (`List<List<Double>>` binds `E` to `List<Double>`), a wildcard binds nothing, and a raw supertype's variables are
their erasure (`JavaMembers.typeParameterErasures`). Through a class file, whose text the walk does not read, a
variable keeps its binding when the supertype's parameter names are the same (`List<E>` to `Collection<E>`), else
matches any. A method's own type parameters are read from both declarations and paired by position
(`JavaMembers.methodTypeParameters`), so `<Out>` and `<U>` match. The walk drops a
hit whose type another hit's type extends and the method asked about itself, reads `Object` only for its own
methods' names, and caches JDK outlines per session (a dependency's jar can be rebuilt in place under the same URI).
A hierarchy item's line can differ from the outline's for the same type, so a type is also found by name; for
`new pkg.Type<>()` jdtls prepares the hierarchy at the anonymous class itself, which it may give no supertypes, so an
anonymous class is prepared at its name's last segment (`JavaMembers.lastSegment`: `Comparator`), where jdtls answers
with the type itself.
Class-file outlines have no anonymous classes or initializers: `JavaMembers.anonymousMethod` finds them in the text,
and their method is named from it (`JavaMembers.signatureAt`) for the same walk. Modifiers and `@Override` are read
from the declaration's own start (`declarationHead`), not its line's, so a one-line class or field initializer does
not lend the method its `static` or `private`. With the caret on such a method's header (its name, modifiers,
annotations) `anonymousMethod` reads ahead to the method's `{`. `MemberSpan.startColumn` keeps the outline's start
column: jdtls starts a class's first member at a comment ending the class line, and `JavaMembers.enclosing(…, column)`
leaves the caret on the class name with the class. A method reference or a lambda's parameters go to Go to Definition at
the `::` or the arrow, as a lambda's body does. Every `java/*` request
sent through `rawRequest` must be declared on `JdtLanguageServer`: LSP4J types a response by that registry and
turns an undeclared method's answer into null (`JdtLanguageServerProtocolTest` checks every literal).

A Java save while another Java file of the project is open (or Problems shows the whole project) is followed by a
quiet recheck. Problems over the whole project, or a Maven project, gets an incremental `java/buildWorkspace`; other
projects get each other open Java document re-sent unchanged (`LanguageServerSession.revalidateOtherJavaDocuments`),
which jdtls reconciles at once. A build would compile a Gradle project into Buildship's `bin/` (re-review CN3).

m2e skips a pom.xml that does not parse at the first import with a `ProjectStatus` of OK, and jdtls then calls each
source file "a non-project file" (syntax only). A file under `src/main/java` or `src/test/java` of a root with a build
file that gets that warning marks the import failed (`LspCoordinator.noteNonProjectFile`): said once with that
reason, then the ready line's "did not import" and each counter's "Not checked". So does "not on the classpath of
project <folder>_<hash>", the invisible project jdtls makes when a loose file was opened first, and a reactor module
whose pom.xml does not parse (checked at the ready line, `JavaImportCheck.mavenModules`); a pom that does not parse
is named with its parse error's line and column (`JavaImportCheck.unreadablePom`). A loose file elsewhere in the
project is a non-project file by rights and is not counted.

"Did the import fail" reads jdtls's last logged Error; the session forgets it at a launch and at `ProjectStatus` OK,
and "Error occured while building workspace" (a compile error a build found) is never one, so a build problem after
a good import is "the build has problems". A failure is keyed by the folder of the build that failed when the
session's root has no build file of its own (a Maven and a Gradle build side by side), and counters, the ready line
and Reload look at the folder and the folders under it that the same session serves; the reason is the project
diagnostic's line that names the cause (`JavaImportCheck.reasonLine`: "Unexpected input", "Unresolved reference"),
not Gradle's closing "1 error".

### jdtls preferences

jdtls's defaults assume vscode-java, which sends its own `package.json` defaults; several features stay
off for a client that sends nothing. `LspServerSettings.javaSettings` is the one `java` object, sent at
`initialize` (inside `LspManager.javaSettings`, with autobuild and the runtimes) and pushed after it:
`signatureHelp.enabled` and `signatureHelp.description.enabled` (the Javadoc in signature help; jdtls
reads dotted keys through the nested maps, so `description: true` resolved to no key at all),
`completion.filteredTypes` (vscode-java's list; jdtls's own default list is not installed unless one is
sent), `inlayHints.parameterNames.enabled = all` with `suppressWhenSameNameNumbered` plus
`variableTypes` and `parameterTypes` (hints are requested only while the setting is on, and
`InlayHintFilter` applies Editora's literals/all mode), and `symbols.includeSourceMethodDeclarations`.
`completion.filteredTypes` is the Settings value `javaExcludedTypes` (vscode-java's list, which includes `java.awt.*`, by
default). jdtls applies it to completion and quick fixes only, so `SymbolRanking` drops the same library types
from Go to Symbol and Search Everywhere, ranks an exact name first and a library class nested in another last.
`java.completion.importOrder`, the star thresholds and the formatter profile come from the project
(`JavaProjectSettings`, `.editorconfig` or the Eclipse profile) over jdtls's own defaults, and are pushed again
when one of those files is saved or changes on disk (`LspManager.projectStyleChanged`). jdtls's
`_java.reloadBundles.command` client command is answered with an empty list; anything else got `null`, which
jdtls logs as an error on every start.
jdtls also asks `workspace/configuration` for `java.format.tabSize` / `insertSpaces` per document before
every code action; the session answers from the document's recorded indent, so quick fixes and generated
members follow the file. The completion `resolveSupport` lists `documentation`: jdtls computes an item's
Javadoc in `completionItem/resolve` only for a client that lists it. The opt-in
`JdtlsClientSettingsProbeTest` checks each of these against a real jdtls
(`./mvnw test -Dtest=JdtlsClientSettingsProbeTest -Dgroups=probe -Dlsp.probe=true`, and
`-Dlsp.probe.jdtls=PATH` for another server).

A package node from `java/getMoveDestinations` carries two paths: `path` is the Eclipse workspace path
(`/geo-demo/src/main/java/com/example`) and `uri` the folder on disk. Only `uri` names a real folder, so
`JdtlsRefactor.folderOf` reads it, and Move ▸ New package… builds its folder under the source root it gives.

`LspCoordinator.runGeneratePrompt` / `runRefactorCommand` intercept these before `applyCodeAction`;
`LspManager.jdtlsRequest` / `jdtlsApplyEdit` send the custom requests, which must be registered on
`JdtLanguageServer` or LSP4J drops their results. The server's own `errorMessage` is shown when it
refuses. The opt-in `JdtlsRefactorProbeTest` runs every flow against a real jdtls
(`./mvnw test -Dtest=JdtlsRefactorProbeTest -Dgroups=probe -Dlsp.probe=true`).

### Code lenses

`Settings.codeLens` (off by default). `LanguageServerSession.codeLens` requests
`textDocument/codeLens` and resolves only the lenses on the lines asked for — jdtls counts references per
lens. `LspManager.codeLensSpans` keeps the lenses that count references or implementations (told apart
by command id; a lens that runs something is the server's own editor integration) and drops "0
implementations". `LspCoordinator.requestCodeLens` follows the inlay-hint cadence with its own 450 ms
settle; `EditorBuffer.setCodeLenses` draws each as an inlay after the end of its line,
`editor/CodeLensShift` moves them with line edits until the next answer, and a click puts the caret on
the declaration and runs Find References or Go to Implementation. A references lens always opens the
References tool window — also for one reference (the command jumps straight there) and for none.
The fork's inlay labels are mouse-transparent, so `EditorBuffer.installCodeLensClick` finds the lens
under the pointer by its bounds and sets the hover pseudo-class (`lens-hover`) and hand cursor itself; a
test that fires a click must pick the area, not the label.

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

**The dirty-target preflight is asked twice.** `resourceTargetsSafe` runs before anything is loaded or
staged and again in `finishWorkspaceEdit`, in the same FX turn that retires the tabs: staging takes several
FX turns, and a keystroke in any window during them must not be closed away with its file. A refusal there
rolls the staged files back and names the unsaved file.

**Text edits are all-or-nothing per transaction.** `EditorBuffer.applyLspEditsAtomically` refuses a batch
with an overlapping, out-of-range, or negative edit (the lenient `applyLspEdits` — Format Document,
completion imports — still skips one); `finishWorkspaceEdit` asks `canPlaceLspEdits` of every buffer before
it changes the first. `WorkspaceEditMapper` refuses an edit with a missing range or a negative position
instead of dropping it. `lspOffset` snaps a column inside a surrogate pair back to the pair's start.

**What an edit destroys is shown, asked about, and kept.** `WorkspaceEditHazards` (pure) lists the deletes
of existing paths and the creates/renames that replace an existing file. The rename preview shows them as
rows that can be unticked (`RenamePreview.filter` drops an unticked one; `excludedButDeleted` refuses a
ticked delete that covers an unticked row); Change Signature and Move use the same preview. An unpreviewed
edit — code action, other refactoring commands, `workspace/applyEdit` — goes through
`applyWorkspaceEditsConfirmed`, which asks before a recursive folder
delete or an overwrite. Before staging, each file about to be deleted or replaced is copied to Local
History (`Ops.captureBeforeDestruction`, at most `MAX_CAPTURED_PER_EDIT` per edit); a failed copy refuses
the edit.

**Staging is journaled.** `WorkspaceEditJournal` appends one forced line to
`<configDir>/lsp-edit-journal/<uuid>.journal` before each move to a hidden `.editora-lsp-*` name, a `COMMIT`
line once the edit is decided, and removes the file after commit cleanup or a complete rollback. A journal
that survives (a crash, or a rollback that could not restore everything) is found by
`offerInterruptedEdits` the next time a window opens that project: the user chooses to restore, leave, or
be asked later, and recovery never overwrites an existing path. Nothing is removed without that answer.

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
changes to the file, the buffer's recorded disk snapshot equals the file as it is now **and carries a content
fingerprint** (only a load or a save records one — "changed on disk → Keep" re-baselines time and size over a
buffer that holds different text, and must not pass), and the file is still unmodified since the request. A version the server attaches to a document it never had open is dropped
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

**typescript-language-server gets the same treatment, and Editora always names its tsserver.** Left to itself
the server runs the first `node_modules/typescript/lib/tsserver.js` it finds walking up from the workspace
root (a downloaded folder's own code), else the `typescript` package beside itself — and TypeScript 7, npm's
`latest` since 2026, is the native compiler with no `tsserver.js`, so an unpinned install crash-looped on every
file. `TypeScriptServer.choose` picks the folder's own tsserver only within its trusted part, else the one beside
the resolved server, and passes it as `initializationOptions.tsserver.path`; with neither, the session is not
started and `LspCoordinator.onStartRefused` says why (`status.lsp.tsSdkWithoutTsserver`, `tsNoSdk`,
`tsSdkUntrusted`). The installer pins `InstallCatalog.TYPESCRIPT_PACKAGE` (`typescript@6`). The same options turn
automatic type acquisition off unless `typescriptTypeAcquisition` is on (it runs `npm install` against the
registry), the session pushes `javascript`/`typescript` inlay-hint and code-lens settings, and answers
`formattingOptions` with the document's indent. Loose JS/TS files (no project, no marker below the home folder)
share one session rooted at `<config>/lsp/loose-files`, with no separate syntax server; the server gives each
file an inferred project of its own because none lies under that root. The server's PATH starts with the bin
folder of the Node.js `NodeRuntimes` resolves.

**The XML server (lemminx) is one process per window.** `XmlServer.sharedRoot` roots every XML file outside the
open project at the same `<config>/lsp/loose-files`, markers or not, since lemminx keeps no project model (a folder
of configs or a Maven reactor opened file by file used to start one native process per folder or module). Files
that only XML reads reach it: `LanguageRegistry` and `GrammarRegistry` map the XML dialects (`.dtd .ent .mod .rng
.xsl .xslt .xsd .wsdl .xlf .xliff .plist .csproj …`, a `go.mod` excepted) to `xml`, and `Modeline.detect` makes a
file whose name says nothing XML when it opens with `<?xml` (not a `.txt`). A DTD opens as language id `dtd` and a
stylesheet as `xsl`, as vscode-xml sends them. lemminx keeps its server in the large-source tier up to
`XmlServer.HEAVY_FILE_LINE_LIMIT` lines. Its protocol extensions: `initializationOptions.extendedClientCapabilities`
(reference code lenses, the binding wizard, actionable notifications), `xml/actionableNotification` (handled like
jdtls's), `xml/executeClientCommand` and the binding wizard's quick fix (`XmlLspActions`: `xml.open.uri`,
`xml.show.references`, `xml.open.binding.wizard` → `xml.associate.grammar.insert`; Bind to Grammar… refuses a
document `XmlGrammarBinding.existing` finds bound). XML: Generate Grammar… sends `XmlServer.noGrammarDiagnostic` at
the root element as a code-action context (`LspManager.codeActionsFor`), so lemminx's "Generate … and bind" fixes
come back even while `xml.validation.noGrammar` is `ignore` and the real diagnostic is never reported. It registers its commands only
for a client with dynamic `workspace/executeCommand` registration, which `clientCapabilities("xml")` alone
declares; registrations add up (`withCommands`). Go to Definition (and so Ctrl/Cmd-click and Peek) on the XML
server goes through `XmlServer.definition`: lemminx's own answer, unless that is only the element's other tag, then
`typeDefinition` (the XSD declaration), then the `documentLink` at the caret (`schemaLocation`, DOCTYPE system id,
`xml-model` href), then, since lemminx links no stylesheet, a relative `href` of an `xsl:include`/`xsl:import` (or
`xi:include`) resolved by Editora itself (`XmlServer.includeHref`). Editora's own auto-close and auto-rename tag stay; lemminx's `xml/closeTag` and
`linkedEditingRange` are not requested.

**A refused `initialize` is not a crash.** A server that answers `initialize` with an error (its message kept as
`LanguageServerSession.initializeRefusal`; the exit is reported only after LSP4J has read the server's last
output) is dropped without the crash path and reported through `onStartRefused` with its own words. Such roots,
and roots whose crash-loop cap was reached, are not restarted by opening or showing a tab
(`LspCoordinator.startBlocked`) until Restart Language Servers, a settings apply or a reconnect.

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
4. **Programs in a container or a WSL distribution.** Ending the `docker exec` / `wsl.exe` client here does
   not end the program there, so a crashed Editora would leave it running. A tracked `RemoteProcess` whose
   target does not end Editora's programs by itself (an SSH session's watchdog does) answers
   `orphanRecord()`: a reaper kind (`container`, `wsl`) and what that reaper needs — for a container its id,
   the user, the pid there and the pid's start time (`/proc/<pid>/stat` field 22, `PosixShell.startTimeArgv`,
   one extra command, asked off the FX thread), then the engine's command. It is written as a seventh field
   with local pid 0 (`LedgerEntry.remote`); earlier versions read only 2, 3 or 6 fields, so they skip it, and
   nothing of this machine's process table ever acts on it. `reapOrphans` hands a dead owner's such rows to
   the reaper `App` registers for the kind (`registerOrphanReaper`: `ContainerExecTarget.reapOrphan`,
   `WslExecTarget.reapOrphan`) on a virtual thread, off the startup path. The reaper runs
   `PosixShell.reapArgv` on the target, which kills the group only while the pid still has the recorded
   start time. On a normal quit the hook's last force-kill of such programs waits until each target has
   been asked (`signalTreeNow`, at most `REMOTE_KILL_WAIT_MS` in all): a request still on its way when the
   JVM exits would never be sent. `ProcessRegistryOrphansTest`, `PosixShellReapProcessTest` and the
   container and WSL probes cover it (the probes kill the client the way a crash does). Docker and Podman
   leave the program running; WSL 1 ends it with `wsl.exe` (the probe prints which), so there the record is
   a safety net, for WSL 2, which the hosted runners cannot run.

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

### Five transports

Editora debugs Java, Python, and JavaScript (Node), off by default
(`Settings.debugSupport`). [`dap/DapServerRegistry`](../../src/main/java/com/editora/dap/DapServerRegistry.java)
is the pure language → adapter spec (a `Kind` transport, the DAP `launch` type, the `initialize`
adapter id, and the default interpreter + adapter args). `languageIdsForDebug()` =
`{java, python, javascript, groovy, kotlin, clojure, scala}`; `typescript`, `javascriptreact` and `typescriptreact` files are served by the
`javascript` adapter (`adapterLanguage`): Node 22.18+/23.6+ runs a `.ts` file itself, and a compiled
project's breakpoints live in its `.ts`/`.tsx` sources.

| Language | `Kind` | How |
| --- | --- | --- |
| `java` | `JDTLS` | The Microsoft java-debug adapter is started *inside* jdtls via `workspace/executeCommand` (`vscode.java.startDebugSession` returns a port), then `DapClient.connect(port, "java")` opens a socket. |
| `python` | `STDIO` | `<python> -m debugpy.adapter`; `DapClient.connectStdio(proc, "python")` wires DAP to the process's stdin/stdout. |
| `javascript` | `SOCKET` | `node <dapDebugServer.js> 0 127.0.0.1`, the port read from its "listening at" line; `DapClient.connect(port, "pwa-node")`. |
| `groovy` | `JVM` | Editora's own JDI adapter (`dap/jvm`, [ADR 0018](../decisions/0018-jvm-debug-adapter.md)), served in-process on a loopback port; `DapClient.connect(port, "editora-jvm")`. The program is `groovy <file>` started through `runInTerminal`, with the JDWP agent (`server=n`, connecting back) in `JAVA_OPTS`. Breakpoints bind by source name. |
| `kotlin` | `JVM` | The same adapter. A script is `kotlinc -script <file>` (agent in `JAVA_OPTS`); a program is compiled first (`kotlinc <file> -include-runtime -d <jar>`, cached by content under `<config>/cache/kotlin-run`) and run as `java <agent> -jar <jar>` (`jvmOptionsInArgv`). A file's declared `package` matches its classes' source path wherever the file sits. Debug via Build Tool and Attach from a Kotlin file attach this adapter (`attach`, `hostName`/`port`) instead of java-debug, which never binds a `.kt` breakpoint. kotlin-lsp's own undocumented DAP (`start_debug_server`) is not used. |
| `clojure` | `JVM` | The same adapter. `run/ClojureRun` picks the tool: the Clojure CLI (`clojure -J<agent> -J-Dclojure.compiler.disable-locals-clearing=true -M -m <ns>`, the agent as a `-J` argument — the launch's `jvmOptionPrefix`) in its `deps.edn` project, Leiningen (`lein run -m <ns>`, the agent in `JVM_OPTS`, which only the project's JVM reads) in a `project.clj` one, and a file outside any project as a script. A function's class (`my_app.core$greet`) records `core.clj` and the package path `my_app/`, so a breakpoint in `src/my_app/core.clj` binds by source name; a script loaded by path records that absolute path (Clojure's SMAP), which `JvmSourceMap.compiledFrom` accepts. Frames read as the function, demunged (`my-app.core/greet`); stepping skips `clojure.lang`, `clojure.core*` and `clojure.main`. Babashka is a native program with no JVM: Debug says so and does not start. Limits: values show as their class (the adapter runs no `toString`), and a function redefined from a REPL is a new class the session's breakpoints were not set in. |
| `scala` | `LANGUAGE_SERVER` | An adapter the language server starts on request ([`HostedDebugAdapter`](../../src/main/java/com/editora/dap/HostedDebugAdapter.java)): Metals' `workspace/executeCommand debug-adapter-start` with `{path, runType: "run", args, jvmOptions, env}` compiles the build target and answers `{name, uri: "tcp://host:port"}`; `DapClient.connect(port, "scala")`, then `launch {name, noDebug: false}`. Metals' adapter runs the program and reports its output. Only for a file of an sbt, Mill, Gradle or Maven build (`ScalaRun.ownerOf`); a script or a Scala CLI project falls back (`DapServerRegistry.fallbackKind`) to `JVM`: `scala-cli run --java-opt <agent> <file> -- args`, the agent in place of `JvmScriptRun.AGENT_ARGUMENT`, and breakpoints bound by file name (Scala packages need not follow folders). |

[`dap/DapClient`](../../src/main/java/com/editora/dap/DapClient.java) (mirrors `LanguageServerSession`)
runs the handshake over an lsp4j.debug `DSPLauncher`: `initialize` → on the `initialized` event send
`setBreakpoints`/`setExceptionBreakpoints` then `configurationDone`, **without** `.join()` — that
callback runs on the reader thread, and lsp4j serializes outgoing messages, so blocking would deadlock
on a response delivered by the same thread. Events arrive on the launcher thread; the `Host`
(`DapManager`) marshals them to FX. `dispose()` disconnects, closes the socket, and
`ProcessRegistry.killTree`s the adapter subprocess tree (same wrapper-orphan reasoning as LSP). The
standalone adapters' stderr is drained into `AdapterStderr` (an undrained PIPE deadlocks, like the LSP
servers; DAP traffic is on stdin/stdout or the socket) and its last lines are added to a start failure's
message.

**The JavaScript launch.** [`dap/JsLaunch`](../../src/main/java/com/editora/dap/JsLaunch.java) builds the
`launch` body: the program runs on the Node.js `run/NodeRuntimes` resolves for the file (`runtimeExecutable`
/ `runtimeArgs`; js-debug itself runs on it too when it is 18+, else on the Settings/PATH one), from
`NodeRuntimes.workingDirectory` (`cwd`, and `__workspaceFolder` = the project when the file is in it — js-debug
resolves `${workspaceFolder}`, i.e. its default `outFiles`/`resolveSourceMapLocations`, from it, so a compiled
file maps back to its `.ts` only with the package root there), with `skipFiles: ["<node_internals>/**"]`.
With "Run the debugged program in the Debug console" on, `console: integratedTerminal` makes js-debug ask
`runInTerminal` and Editora starts the program as a `Debuggee` (stdin, all output, exit code, kill on Stop,
the Terminal-tab option) — js-debug's own `stdout`/`stderr` copies of `console.*` output are then dropped;
off, `outputCapture: "std"` (the default `"console"` misses `process.stdout.write`). Node's inspector lines
("Debugger attached.") are filtered out, and js-debug's "Syntax error setting breakpoint with condition" output
becomes a notice that marks that breakpoint rejected. Debug ▸ Attach on a JS/TS file attaches to a Node.js
inspector (`host:port`, `continueOnAttach: false`); Debug Test at Caret on a JS/TS test file runs it under
`node --test --test-name-pattern <the test at the caret>`. js-debug reports failed evaluations only in
`body.error.format` (`DapErrors.responseText`).

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
is not shown. With several simultaneous targets (a debuggee's subprocesses and workers) the threads of every child are
listed together, and a request about a thread goes to the child that owns it (`target(threadId)`, which also
moves the focus there). `FakeDebugAdapter` (test
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
RUNNING until the next stop, exactly like Resume — but only once the adapter *acknowledges* the step: a
refused step (java-debug: "the thread is not suspended") leaves the session SUSPENDED and reports the
adapter's message. `isStepping()` is true from the request to the next stop; `debug.start` treats that as
the paused session it is (Continue), never as a running one to retarget. The manager also tracks *which*
threads are stopped (`stopped.allThreadsStopped`, `continue`'s `allThreadsContinued`, `continued`
events): java-debug suspends and resumes per thread, so after Continue on one of several stopped threads
the next still-stopped thread is brought forward instead of reporting a running session. Restart goes
through `DebugCoordinator.restart()`, which repeats the coordinator-level start (save, before-launch
build, closed-file breakpoints) rather than only the adapter launch. A session `attachToPort` opened on a
JVM that a test or build run started is the exception: that JVM stops listening once resumed, so Restart
leaves the session alone and reports that the run has to be started again. Launch paths:

- **java** → resolve main class (`vscode.java.resolveMainClass`) → `resolveClasspath` →
  `resolveJavaExecutable` → `checkProjectSettings` → `startDebugSession` → connect the socket → `launch`.
  A loose file with no build project falls back to `javac -g` compilation — both when jdtls lists no
  main class and when it lists the file's own from an *invisible project*, whose output folder holds no
  class file (jdtls runs with autobuild off). A compact `.java` file takes that compile path
  directly: its implicit class is named for the file, even if the file declares nested types. The
  compiler, launcher, and debuggee environment all use the selected JDK; compilation runs from the
  file's directory so neighboring source files can resolve.

The pure decisions of that path live in `dap/JavaLaunchSupport`:

- **Main-class names.** For a class in a named module jdtls answers `<module>/<class>`. The launch keeps
  that form; anything compared with what the user typed or clicked uses `MainClassOption.className()` /
  `JavaMainClass.className()`, and a classpath Run passes the class part.
- **Project name.** java-debug compiles breakpoint conditions, logpoints and evaluated expressions against
  a JDT project, and treats a condition it cannot evaluate as met. An `attach` and a compile-fallback
  launch give it nothing to infer the project from, so `DapManager.projectNameOf` asks jdtls
  (`resolveMainMethod`, then `resolveElementAtSelection` at the file's first type declaration) and sends
  it as `projectName`.
- **Working directory.** A project's main class runs in the project root on every path (Debug, gutter,
  saved configuration, Run); only a loose file runs in its own folder.
- **Preview features.** `checkProjectSettings` (its argument is one JSON *string*) says whether the
  project compiles with `--enable-preview`; the flag is then added to the VM arguments of Debug and Run.
- **Long class paths.** `shortenCommandLine` is sent only when the command could not be started
  (Windows: 32,767 characters in all; elsewhere 128 KiB per argument) — `argfile`, or `jarmanifest` for
  a launcher older than JDK 9.
- **Same file.** jdtls reports real paths, so its `filePath` is compared by real path (a project opened
  through a symlink).

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
`attach`/`program` argument maps (pure, unit-tested; `nodeAttach` for a Node.js inspector). The neutral records
exposed to the UI are in [`dap/DapModels`](../../src/main/java/com/editora/dap/DapModels.java)
(`ThreadInfo`, `StackFrameInfo`, `ScopeInfo`, `VariableInfo`, `EvalResult`, `LineBreakpoint`,
`FileBreakpoints`).

### The debugged Java program is Editora's child (program input)

java-debug's `internalConsole` starts the program inside jdtls with no standard input: anything reading
`System.in` waits forever. With the `debugProgramConsole` setting on (the default; palette
`debug.toggleProgramConsole`, Settings ▸ Debugging ▸ Java) a Java **launch** instead says
`console: integratedTerminal` (`LaunchConfig.inClientConsole`) on a `DapClient` that offered
`supportsRunInTerminalRequest` (`setRunsDebuggee`). The adapter then sends the `runInTerminal` reverse
request — an argv array (the `java` executable, `-agentlib:jdwp=…server=n…address=localhost:<port>`, class
path, main class, arguments), the `cwd`, and the launch's own `env` — and `DapManager.startDebuggee` starts
exactly that through [`dap/Debuggee`](../../src/main/java/com/editora/dap/Debuggee.java), a per-session
`RunService`: the user's environment with the augmented PATH and the launch's variables on top (what Run
gives a program; not jdtls's environment), tracked by `ProcessRegistry`, stdout/stderr through the Run
console's bounded pump into `Listener.onOutput` as `stdout`/`stderr`. With `debugProgramInTerminal` on,
`DapManager` holds a `ProgramTerminal` and `Debuggee` starts the same command in a Terminal tab instead: a real
terminal, whose tab owns the output and input (no Debug-console input then), while its exit and Stop behave as
below ([terminal.md](terminal.md#ide-features)). What follows from owning the process (all measured against
java-debug 0.53.2):

- the adapter sends **no `output` events** for it — the pump above is the only source of program output;
- `disconnect(terminateDebuggee)` does **not** end it — `DapManager` kills it on `stop()` (Stop, Restart,
  a new launch, a failed launch, window close/`shutdown()`), and the registry's shutdown hook covers app exit;
- **its exit ends the session** (`Listener.onProgramExit`, then INACTIVE), after its last output. An adapter
  `terminated` (or a lost transport) that arrives while the process is still there waits for that exit, and
  ends the process after a 3 s grace period.

`DapManager.sendProgramInput` / `closeProgramInput` write to its stdin. The Debug console has one input
field: while the session is RUNNING and the program takes input, Enter sends the line (echoed, an empty line
included); while SUSPENDED it evaluates, as before — a paused program reads nothing, so resume to type to
it. The prompt text says which it is. `debug.endProgramInput` (Ctrl+D in the field, or the console's
context menu) closes stdin. Attach (Debug Test, Debug via build tool, manual attach) launches nothing and is
unchanged; Python and JavaScript keep their adapters' own consoles.

### Breakpoints

Breakpoints are persisted per-project in `breakpoints.json` (`config/Breakpoint` +
`config/BreakpointStore`), tracked through edits by `editor/BreakpointManager` (mirrors
`BookmarkManager`; the pure `shift`/`reanchor` are unit-tested), and shown in a leftmost gutter strip
that toggles a breakpoint on click. They are snapshotted on the FX thread at session start and sent to
the adapter on its `initialized` event (DAP is 1-based; the model is 0-based).

**What the adapter says back.** `DapClient` keeps the `setBreakpoints` answer and later `breakpoint`
events (matched to the requested line by the adapter's breakpoint id — java-debug's event carries nothing
else) and reports them as `DapModels.BreakpointStatus`; `DebugCoordinator` turns them into
`BreakpointManager.Live` states that exist only while a session is live. The gutter draws an unverified
breakpoint hollow, with the adapter's message on hover. *Unverified is not rejected*: java-debug leaves a
breakpoint on a line with no code unverified with no message, and js-debug ("Unbound breakpoint") and
debugpy ("Waiting for code to be loaded…") describe a wait in `message` — so only `reason: "failed"`, an
error answer to the whole request, or an evaluation failure flags a breakpoint as rejected
(`DebugFeedback.live`). java-debug reports a condition or logpoint message it cannot evaluate in a
non-standard `usernotification` event at the first hit, naming only the quoted expression; the breakpoints
carrying that expression are flagged until it is edited. A verified answer naming another line (debugpy's
`translated_line`) moves the breakpoint there. A root session that only starts child sessions (js-debug)
never reports: its "unbound" answers would overwrite the child's.

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

## Installed versions: pins, receipts and floors

Editora installs some servers and tools at an **exact release** (jdtls, vscode-js-debug, lemminx-maven, gls,
Metals, JetBrains' Kotlin LSP, clojure-lsp, lemminx, ShellCheck, shfmt): the version constants in `InstallCatalog`,
listed with their steps in `install/PinnedVersions`. Every other installer takes whatever is latest (npm, pip, `go
install …@latest`, rustup, the GitHub `releases/latest` archives).

- **lemminx (XML).** vscode-xml's native build (`LEMMINX_VERSION`, which ships `LEMMINX_SERVER_VERSION`), one zip
  per platform: the zip is checked against GitHub's SHA-256 for the asset and the program inside against the
  release's own `lemminx-<platform>.sha256` (`PinnedAsset.member`/`memberSha256`), installed as plain `lemminx` in
  `plugins/lsp/xml`, and asked to answer `initialize` (`JvmLspSmoke.initializeCheck`) before it is swapped in — it
  needs glibc 2.34. One that cannot run installs nothing; when the pom.xml server's bundle is complete the XML
  command becomes its Java lemminx instead (`InstallService.javaLemminx`). The installer leaves the XML command
  blank: `XmlServerInstall.effectiveCommand` makes a blank (or bare `lemminx`, or a stale path into that folder)
  command run Editora's copy, including one from before the pin (`lemminx-<platform>`). A server that dies before
  `initialize` at every start reports its first stderr line (`LspManager.crashReason`) in the crash-loop notice,
  Settings and Doctor (`LspCoordinator.serverTrouble`).

- **Receipts.** After a pinned step installs, `InstallService` writes `.editora-install` (`InstallReceipt`: step id,
  version, time) into its folder. A dot file and not a jar, so neither a `<dir>/*` classpath nor `findBinary` sees
  it.
- **Outdated copies.** Doctor's row for a pinned server or tool reads Editora's copy's receipt, else what its
  files show (`PinnedVersions.fingerprint`: jdtls's core bundle, a jar's name, js-debug's bundle constant), else
  what it prints for `--version` (clojure-lsp, ShellCheck, shfmt). Older than the pin, or a copy from before
  receipts whose files say nothing (the Kotlin server), is a warning with **Update**
  (`DoctorCheck.outdated`): the same installer with `update = true`, which reinstalls the pinned step even though
  a server is found and stops the running server first; the settings apply after the install starts it on the new copy. A plain Install also
  replaces Editora's copy that is known to be older (`InstallCoordinator.editoraCopyOutdated`, files only — it
  runs on the FX thread). The user's own older server gets the warning too, and Update switches it to Editora's
  copy; a ShellCheck/shfmt path set in Settings gets only the warning.
- **Bumping a pin** is the version constant (and its hashes) in `InstallCatalog`; the receipt and Doctor follow.
  `PinnedVersionsTest` fails when a new exact-release installer has no `PinnedVersions` entry.
- **Newer releases.** Doctor's **Check for Newer Versions** (header button; nothing is asked otherwise) looks up
  the latest release of each working server or tool that Editora installs at "latest" (`install/LatestVersions`:
  npm, the Go module proxy, GitHub and HashiCorp releases; pinned installs are never looked up) and remembers the
  answers for the window, so a Refresh keeps them. A row whose installed version (the npm `package.json` beside
  the launcher, else what `version`/`--version` prints) is older becomes `DoctorCheck.newer`: still OK, but
  listed under "Updates available" with Update, which runs its installer again — and installers at "latest" take
  the newest release. Update on a language bundle's row reinstalls only that row's step
  (`DoctorCoordinator.updateStepFor`, `InstallCoordinator.installSupport(lang, updateStep, …)`). Not looked up:
  servers a toolchain updates itself (rust-analyzer via rustup, gem, dotnet, composer), the XML server, the typst
  CLI and debugpy.
- **Floors.** The tools Editora finds rather than installs have minimum versions, all in `install/VersionFloor`
  (JDK for Run, `gh`, Node.js for js-debug and for HTTP scripts). Compare with `VersionFloor.metBy` where the
  version is read, and pass `minimum()` to the Doctor tip rather than writing the number into a message.

## Adding a server or adapter

See [extending.md](../extending.md) — adding an LSP server is one `ServerDef` entry plus its id in the
coordinator's `SERVER_IDS` and the served language ids in `EditorBuffer.LSP_LANGUAGES`, plus a Settings
command/enable pair; adding a DAP adapter is one `Def` entry in `DapServerRegistry`.

## Request traffic (2026-10 performance pass)

- **Refresh coalescing.** Capability changes and "ready" statuses go through the same 100 ms coalescer as
  the other refresh kinds and apply only to the originating session's documents. Semantic tokens and inlay
  hints are requested for the tab on screen; a skipped tab catches up in `LspCoordinator.onBufferShown`.
- **One request in flight per document and kind.** Repeated requests (diagnostic, documentSymbol,
  foldingRange, semantic tokens, inlayHint, documentHighlight, signatureHelp) go through `LatestRequests`: a
  newer stamp cancels the older request, the same stamp sends nothing. Session futures propagate `cancel`
  to the JSON-RPC future, so `$/cancelRequest` reaches the server.
- **Outline.** `documentSymbol` is requested only while the Structure window is shown, or when the Jump to
  Structure picker opens.
- **Scroll.** A server without range support is not re-asked for whole-document semantic tokens on scroll
  when its tokens are current.
- **Diagnostics.** In open-files scope, publishes for files not open on the publishing server are dropped
  in `LspManager` on the reader thread. The Problems tree rebuilds at most once per 100 ms and not at all
  while its window is closed (it catches up on show).
- **One ordered writer.** `AsyncPipeWriter` is public and also used by `DapClient` and `AcpClient`, so no
  pipe or socket write happens on the FX thread. `didOpen` / `didChange` / `didSave` are JSON-encoded on the
  writer thread (`DeferredSyncConsumer`); the incremental diff is still computed by the caller.
- **URIs.** `DocumentUris` caches `Path.toUri().toString()` per path (the JDK stats the file on each call).
- **Keys and wire URIs.** Inside `LspManager` a document is identified by its *key* (`DocumentUris.of`). What
  a server is sent, and what it answers with, is a *wire* URI. Each `LanguageServerSession` owns a `WireUris`
  that converts between the two: `toWire` where a request is built (`doc(key)` in the session,
  `LspManager.serverUri(file)` for command arguments), `toKey`/`toPath` on anything read from a server
  (locations, workspace edits via `EditBasis.uris()`, published diagnostics). For a local server both are the
  same `file:` string (`WireUris.LOCAL`), so nothing changes today; the seam exists so a server on another
  machine can be given `file:` URIs of its own filesystem. `LspWireUriFxTest` runs the manager against a fake
  that uses different wire URIs and fails if a key reaches the server unconverted. Objects handed back to a
  server unchanged (hierarchy items, code actions, `jdt:` URIs) stay in wire form.
- **Java scripts and their stand-ins.** jdtls makes a compilation unit only of a readable file named `.java`, so
  a Java document with another name (an extensionless `java --source N` shebang script) is shown to it as a
  stand-in: `LspManager.openDocument` writes `<temp>/editora-java-scripts-*/s*/<name>.java` and registers it with
  `LanguageServerSession.alias`, which sits on top of the session's `WireUris` (`WireUris.aliasing`) — so every
  request names the stand-in and every answer about it reads back as the script — and rewrites the text on the
  wire (`JavaScriptShadow.wireText`): the shebang becomes a block comment of the same length, so positions do
  not move and JDT inserts an import *after* it (above a `//` line it inserts at 0:0, ahead of the shebang). The
  session's own copy of the text stays the editor's, since server edits are checked against it, and an aliased
  document always syncs in full. `closeDocument` deletes the stand-in. java-debug finds a class's source through
  jdtls, so a stack frame can name the stand-in: `DapManager` maps it back with `LspManager.scriptForStandIn`.
  `JavaScriptShadowFxTest` guards the wire; `JdtlsJavaScriptShadowProbeTest` checks the import placement
  against a real jdtls.
- **Consoles.** The Run/Build pump delivers each drain inside a `process/OutputBatch`; `ui/ConsoleAppender`
  applies it as one append, one style application and one trim, and drains start at least 16 ms apart. The
  Java debuggee's console gets this through the Run pump.
- **DAP.** `dispose()` waits up to 250 ms for the queued `disconnect`; `stackTrace` asks for the innermost
  1,000 frames when the adapter advertises `supportsDelayedStackTraceLoading`.
