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
| `python` | `pyright-langserver --stdio` | `pyproject.toml`, `setup.py`, `setup.cfg`, `pyrightconfig.json`, then `.git`, then `requirements.txt`/`Pipfile` |
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
A file no editor tab has open can be *lent* to the session that already runs for it (`LspManager.lendDocument`,
for the Project Map's linked reference cards): a `didOpen` under an opener of its own, so the file-keyed requests
(references, call hierarchy, …) reach that server, and a `didClose` when the handle is run and nothing else holds
the document. Overlapping loans of one file (a closed-file outline and a links question) share the first loan's
opener and count: `didClose` goes only when the last of them ends (Map review 4, C13). It starts no server and opens
no editor; `runsFor(file)` says whether one runs. `prepareCallHierarchy` and `callHierarchyChildren` take an optional
`onError`, as `references` does, so a failed or timed-out request is not read as "no callers" (the session's
futures no longer turn a failure into an empty list).

[`lsp/LspManager`](../../src/main/java/com/editora/lsp/LspManager.java) is the UI-facing facade
(mirrors `MermaidService`). It keys sessions by `serverId + " " + root.toUri()` — **not** by
language id — so js/ts/jsx/tsx in one project share a single `tsserver`. It owns:

- `sessionsByRoot` (`computeIfAbsent` starts a server on first open) and `sessionByDocUri` (routing).
- An async, per-server availability probe (`detect(serverId, cb)` → cached in `availableCache`,
  invalidated when that server's command changes or after an in-app install via `invalidateDetection()`).
- The document lifecycle (`openDocument`/`changeDocument`/`saveDocument`/`closeDocument`) and the
  request methods, each returning **neutral** results (`Target(file, line, character)` for
  definition/references) marshaled to the FX thread via `Platform.runLater`.

The session lifecycle stays in `LspManager`; each request family lives in a package-private class it holds and
forwards its public methods to, so callers still see one facade: `LspNavigationRequests` (definition, references,
hierarchies), `LspAssists` (completion, signature help, highlights, hover), `LspCodeActions` (code actions and every
server edit on its way to the editor, planned by the pure `WorkspaceEditDrafts`), `LspFormatting`,
`LspDocumentSymbols` (outline, folding, selection ranges, colours, symbol search), `LspInlineHints` (code lenses,
inlay hints), `SemanticTokenRequests`, `LspRename` (symbol and file renames), `LspOrganizeImports`, `JdtlsCommands`
(jdtls's own requests), `JavaBuilds`, `LspDiagnostics` (published and pulled diagnostics) and `LspFileWatching`
(root watchers and `didChangeWatchedFiles`). What a server starts with is in `ServerInitOptions` (its
`initializationOptions`), `NodeServerPaths` (tsserver, TypeScript SDK, Node.js) and `JdtlsWorkspaces` (the
process-wide `-data` claims); `FxClientUi` posts a server's questions to the FX thread.

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
  `analysis` object without that key exists); `{}` for the CSS server's `css`/`scss`/`less`, whose validators throw on
  `null`; VS Code's `format` defaults for the HTML server's `css`/`javascript` (with `{}` its TypeScript formatter
  dropped the spaces inside braces of every inline script); and `null` for everything else —
  including the HTML server's `js/ts`, where `{}` makes its JavaScript mode throw. A new server's sections
  must be checked against the real server before anything other than `null` is answered.
- **The HTML server's `html` section** (`lsp/HtmlServerSettings`, Settings → LSP, the HTML card) is VS Code's
  defaults — the server has none of its own, and with `{}` its formatter protected only `script`, so Format
  Document re-spaced every `<pre>` and `<textarea>` — with the user's values (wrap column, wrap attributes, content
  kept as written, attribute quotes — whose row is under Settings → Editor beside Auto-close tags, since a typed `=`
  follows it with the server off too — embedded script/style validation), `autoClosingTags` from Editora's own
  auto-close switch whatever the project says (so completion does not offer a closer Editora already typed), and a
  project's `html` object from `.editora/settings.json` (VS Code's key names, nested or dotted, and top-level
  `html.*` keys as `.vscode/settings.json` writes them; known keys of the right type only, the rest named once on
  the status bar and in the log) for servers on the project's root. The answer is per document (`scopeUri` → the
  recorded `FormattingIndent`): `format.wrapLineLength` is the file's EditorConfig `max_line_length` when set
  (`off` = 0, never wrap). `format.endWithNewline` is always `false` (VS Code's value: the formatter hands it to the
  CSS beautifier of every `<style>`, which then adds a blank line before `</style>`); Editora keeps the final
  newline on the edits instead (`LspTextEdits.withFinalNewline`): the file's `insert_final_newline`, else the
  project's `format.endWithNewline`, else what the text has now. The server caches each document's answer until
  `didChangeConfiguration` or `didClose`, so every change is pushed (`LanguageServerSession.setHtmlSettings`) and a
  formatting request repeats the wrap column as a `FormattingOptions` value, which the server lays over its
  settings. Proven against the real server by
  `HtmlServerFormatNodeProbeFxTest` (opt-in, `-Dgroups=node -Dnode.probe=true`).
- **lemminx's settings** (`lsp/XmlServerSettings`, Settings → LSP, the XML card) go to the `xml` and `maven-pom`
  servers through all three channels: `initializationOptions.settings.xml` (so the first validation already
  has them), a push on change (`LspManager.setXmlSettings`, run by every `LspCoordinator.applySupport`, so a
  trust decision is read again), and `workspace/configuration` answers by dotted path. The object is built per
  session root: `xml.downloadExternalResources.enabled` follows `xmlSchemaDownloads` (`trusted` = that root's
  folder trust; the shared loose-files root is never trusted), relative catalogs and grammar paths resolve under
  the root (and are left out for the loose-files root, which is Editora's folder, not one of the user's), and `xml.server.workDir` puts lemminx's cache at `<config>/cache/lemminx` (a GraalVM native lemminx
  takes `user.home` from passwd, so neither `$HOME` nor a test's `user.home` reached it). The pom server's push
  repeats lemminx-maven's own `xml.maven` object, which it reads whole. `xml.format.tabSize`/`insertSpaces`
  are answered from the document's indentation, as jdtls's `java.format.*` are.
- **pom.xml and trust** (`ui/XmlFolderTrust`): a pom goes to `maven-pom` only when its Maven root (the outermost
  contiguous `pom.xml` folder) is trusted; otherwise plain lemminx serves it and the tab offers **Trust Folder**
  (also the palette's **Workspace: Trust This Folder**). Trusting or revoking reroutes that root's poms. lemminx-maven
  resolves parents, BOMs and plugins and class-loads plugin and extension code, so `LspManager` also refuses to start
  `maven-pom` on an untrusted root. The `xml` server must stay free of lemminx-maven, including the Java fallback, whose
  classpath is the plain-lemminx jars only. Both servers get the bundled POM schemas (`lsp/XmlBundledSchemas`:
  `maven-4.0.0.xsd`, `maven-4.1.0.xsd` behind a catalog in `<config>/cache/schemas/`, listed first) so an untrusted
  or offline pom validates; a JVM lemminx gets `-Duser.home=<Editora's home>` like the native one's `workDir`.
  `maven-pom` is also sent `xml.maven.repo.local`, the absolute local repository `MavenPluginGoals.localRepository`
  reads (with `${env.…}` and system properties expanded), so its `.lemminx-maven` store is the one Doctor shows.
- **The pom bundle's jar signatures** (`install/JarSignatures`): Eclipse signs `org.eclipse.lemminx-*.jar`, which adds
  classes to Jing's `com.thaiopensource.relaxng.pattern`, so the JVM refused lemminx's `RelaxNGPlugin` ("signer
  information does not match") and the Java lemminx had no RELAX NG completion. Install strips `META-INF/*.SF|RSA|DSA|EC`
  from the bundle's jars before the smoke test; a bundle installed earlier is rewritten once when a local lemminx is
  started from it (`LspManager`), and `.editora-unsigned` marks the folder as done.
- **A malformed pom and lemminx-maven** (`ui/PomServerSupport`, `maven/MalformedPoms`): lemminx-maven 0.12.0 keeps a
  malformed pom in its workspace with a null project, and every coordinate completion after the first throws on it
  (upstream: `getCurrentWorkspaceProjects` filters null futures, not null projects). On such an answer Editora walks the
  root the way lemminx-maven does, reads each pom from disk (what the server built from), takes each malformed one out
  with a `workspace/didChangeWorkspaceFolders` that removes its own file URI, and asks again. lemminx-maven never
  rebuilds a closed pom, so one cannot be put back in the same session: when a left-out pom parses again (a save, or
  either watcher: the Project panel's or `LspManager`'s root watcher), that root's server is restarted. The proof that
  an answer is the broken one is the server's own log (`xml.logs.client`, counted per session); an empty answer without
  it is an honest no-match and changes nothing. lemminx-maven also keeps a null project for a well-formed pom whose
  effective model fails (an unresolvable parent) and whose strict re-read fails (a duplicated or unknown `<project>`
  child, a DOCTYPE with entities). Such a pom is never left out: the status says once per root that Maven completion
  is off, naming the pom `MalformedPoms.unbuildable` finds, and the root restarts only when one of its poms changed on
  disk since that server started, never twice for the same files. `${env.*}` completion items keep their names but
  not their values.
- **Formatting options** (`lsp/FormattingIndent`): every formatting request describes the document's own indentation;
  Format Document and Format Selection also carry the file's save rules (EditorConfig `trim_trailing_whitespace` /
  `insert_final_newline`, else the project's) as `trimTrailingWhitespace` / `insertFinalNewline`. Tab re-indent and
  on-type formatting leave them out.
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
  rather than being reduced to a Boolean. Diagnostic, semantic-token, inlay-hint, folding and code-lens refresh
  requests are advertised (`workspace.*.refreshSupport` — servers send none without it) and re-request
  data for managed open buffers, coalesced over a 100 ms window; semantic tokens, inlay hints and code lenses are
  re-requested for the active buffer only, the one their replies are applied to. A capability change re-pushes every
  buffer gate, including Go to Implementation / Type Definition.
- **A document opens on its server only with its text.** A tab first appears as an empty loading shell
  (`EditorBuffer.isLoading()`); `syncBuffer` treats it as ineligible and the load's completion
  (`finishAsyncOpen`, `finishSessionBuffer`) syncs it again, so `didOpen` carries the whole file. The shell's
  heavy-file flag alone did not stop it: Python, Java and XML keep their server in the large-source tier, and the
  shell's one line passed their line limits, so those servers got an empty `didOpen` and then a whole-file insert.
  The indent a server's `workspace/configuration` questions are answered with (`LspManager.setDocumentIndent`) is
  re-recorded on every sync and with each debounced `didChange`.
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

[Java interactive editing](java-editing.md) maps the keystroke-to-popup pipeline, completion
sessions, timing, the tests and probes, and the limits that belong to JDT LS.
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
internally while FX remains asynchronous, at some cost in server latency;
[Java interactive editing](java-editing.md) has the remaining same-file import conflict.

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
(`ServerInitOptions.javaExtendedClientCapabilities`): a flag is declared only together with the code that
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

A `jdt://` definition opens as a read-only, path-less buffer (`LspNavigation.openLibraryDefinition`),
remembered in `librarySources` with the workspace file whose session produced it. Position requests from
such a tab — definition, hover, references, implementation, type definition, declaration and both
hierarchy prepares — go through `navOrigin()`: the anchor file's session, asked about the tab's `jdt://`
URI (the `…At(anchor, documentUri, …)` methods of `LspManager`). jdtls resolves the URI itself, so no
`didOpen` is sent. References into class files are dropped (the References window is file-based); a
hierarchy node with a `jdt://` URI opens as library source. Search Everywhere's Symbols group adds
jdtls's `workspace/symbol` types that live in `jdt://` (`LspManager.javaLibraryTypes`), as a second batch
after the index's own (`SearchEverywherePopup.Ops.laterSymbols`).

A library tab's sources (`LibrarySources`, the jdtls side in `lsp/JavaLibrarySources`): jdtls reads a class file
whose library has no sources attached by first looking for its sources jar, for a Maven project in the local
repository and, by the jar's SHA-1, on Maven Central's search, waiting about three seconds (Maven Central artifacts
open with their sources), then decompiles it. A tab opened on such a class asks `java.project.resolveSourceAttachment`;
with no sources, its file notice offers Download Sources (`lsp.downloadLibrarySources`: the class file is read again,
which asks jdtls again, then waited for up to 20 s when the URI names Maven coordinates, not for a `lib/` or system
jar or a JDK module) and, when none is found, Choose Sources… (`lsp.attachLibrarySources`: a `-sources.jar` or zip,
attached with `java.project.updateSourceAttachment`). A Gradle library's resolve answers an error (its container takes
no attachment): it is told its sources come from its build. jdtls's `language/eventNotification` SourceInvalidated
(event type 500, `data.affectedRootPaths`, a JDK's as its `jrt-fs.jar`) says which libraries got sources later; their
open tabs are read again, the caret kept on its line. Settings → LSP's "Download library sources when a Java project is
imported" (off) sends `java.maven.downloadSources` and `java.eclipse.downloadSources` at `initialize` and with every
settings push; m2e applies it at the next import (Clean Java Workspace), and Buildship downloads Gradle's sources
whatever it says. The event reaches the window whose manager started the session; a shared server's other windows do not
reload their tabs.

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
and is merged into `settings.java` at `initialize` (`ServerInitOptions.withProjectSettings`) and in every push
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
(`BufferTyping.closeJavadocOpening`, rule in `CommentContinuation.closesDocOpening`).

### Files written behind the server's back

jdtls tracks a file only through `didOpen` or `workspace/didChangeWatchedFiles`. A workspace edit that
writes a file with no tab (`WorkspaceFileOperations` saves it straight to disk), a file the edit creates,
and a Project-tree rename are each announced with `didChangeWatchedFiles` (`WorkspaceEditStaging.resourceEvents`,
`afterWorkspaceEdit`); without it the next rename skipped the file and left it broken. A Project-tree rename of
a `.java` file, and each file of a drag-move (`ProjectPanel.moveThrough`, also Undo Move), goes through
`workspace/willRenameFiles` first (`LspRefactoringUndo.willRenameFile`); jdtls rewrites a moved file's package only
for a target package it knows: the edit the
server answers is applied and saved, and the move is recorded with it, so Undo Last Refactoring moves the
file back and reverts the edit together. A folder rename is applied but not undoable as one. Saving a Java file
the server does not have open (a deferred tab) sends `didChangeWatchedFiles` CHANGED. A `pom.xml` or Gradle build
file changed on disk is also sent, once the build files have stayed unchanged for two seconds, in a
`java/projectConfigurationsUpdate` (`LspFileWatching.buildFilesChanged`), which updates a project even when its build
file reads as before: jdtls reads a changed pom when it gets to the event and skips one it has seen, so a checkout and
a pull a second apart left modules on the versions in between, every import of a sibling module red. A formatter profile is
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
Go to Super Method (`LspSuperMethods.gotoSuperMethod`) finds the member at the caret in `MemberSpan`s (whole ranges,
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
their erasure (`JavaMembers.typeParameterErasures`); an anonymous class's `<>` takes its field's type, a raw
`new Store() {…}` does not. A jar class without sources has no type parameters in its outline name (`LKeyed`, not
`LKeyed<Key>`): its decompiled text (`java/classFileContents`) is read as a project file's is
(`JavaMembers.withTypeParameters`). Through a JDK class file, whose text the walk does not read, a variable keeps its
binding when the supertype's parameter names are the same (`List<E>` to `Collection<E>`), else matches any. An open
file is read as the server has it (`LspManager.sentText`), which covers a buffer in another window sharing the
server. A method's own type parameters are read from both declarations and paired by position
(`JavaMembers.methodTypeParameters`), so `<Out>` and `<U>` match. The walk drops a
hit whose type another hit's type extends and the method asked about itself, reads `Object` only for its own
methods' names, and caches JDK outlines per session (a dependency's jar can be rebuilt in place under the same URI).
A hierarchy item's line can differ from the outline's for the same type, so a type is also found by name; for
`new pkg.Type<>()` jdtls prepares the hierarchy at the anonymous class itself, which it may give no supertypes, so an
anonymous class is prepared at its name's last segment (`JavaMembers.lastSegment`: `Comparator`), where jdtls answers
with the type itself for most names. In a `<>` anonymous creation it may not: it resolves a JDK type of the same simple
name (`java.util.stream.Sink`) or the enclosing class there, at a package-qualified name it may answer the enclosing
class, and its Go to Definition does the same. So nothing jdtls answers for the instantiated type is used unchecked
(`InstantiatedType`, for the method walk, the class-level routes and Go to Definition on that name alike): an answer
must have the written simple name and a package the file sees it in (its own, an import's, `java.lang`, the one it is
qualified with). A simple name is asked where it is written, then at the variable's declared type when it is written
the same (there jdtls resolves it), then through the type its import names; a qualified one
(`JavaMembers.qualifiedName`) from its first segment's type, found by Go to Definition at the file's import of it, at
the declared type or where it is written, and checked by name, then the rest in that type's outline
(`JavaMembers.nestedType`, which also takes a class file's flat outline of member types nested only by range) or a
supertype's (an inherited member type), the hierarchy prepared there. When nothing checks out, it says none.
Class-file outlines have no anonymous classes or initializers: `JavaMembers.anonymousMethod` finds them in the text,
and their method is named from it (`JavaMembers.signatureAt`) for the same walk. Modifiers and `@Override` are read
from the declaration's own start (`declarationHead`), not its line's, so a one-line class or field initializer does
not lend the method its `static` or `private`. With the caret on such a method's header (its name, modifiers,
annotations, parameter list, Javadoc, a `//` comment right above it) `anonymousMethod` reads ahead to the method's
`{`; on the anonymous class's own header, from its type's name to its `{` (also wrapped), or a field in it, it answers
the class itself, whose super is the type it instantiates (prepared, so not that type's constructor). An interface's
anonymous class's `hashCode()` reaches `Object.hashCode` through the library type search, as the walk from an
interface does not. A target in a file open only in another window, unsaved, is given the same line in the disk copy
that opens here.
`MemberSpan.startColumn` and `endColumn` keep the outline's columns: a member holds the caret from its start column on
its first line to its end column on its last, so a one-line class's later member is itself, and jdtls's habit of
starting a member at a comment ending the line before it (a class line, a wrapped header's second line) does not give
that line to the member (`JavaMembers.enclosing(…, column, text)`). A class's supertypes leave out `java.lang.Object`,
as IntelliJ's do. A method reference or a lambda's parameters go to Go to Definition at
the `::` or the arrow, as a lambda's body does. Every `java/*` request
sent through `rawRequest` must be declared on `JdtLanguageServer`: LSP4J types a response by that registry and
turns an undeclared method's answer into null (`JdtLanguageServerProtocolTest` checks every literal).

A Java save while another Java file of the project is open (or Problems shows the whole project) is followed by a
quiet recheck. Problems over the whole project, or a Maven project, gets an incremental `java/buildWorkspace`; other
projects get each other open Java document re-sent unchanged (`LanguageServerSession.revalidateOtherJavaDocuments`),
which jdtls reconciles at once. A build would compile a Gradle project into Buildship's `bin/` (re-review CN3).
The recheck comes 1.2 s after the last save, or 5 s after the last autosave: autosave "after delay" saves at each
pause in typing, and a recheck per pause would re-send every open Java file while the user is still typing
(`LspCoordinator.recheckDelayMillis`, perf review 2026-11-23 L4).

m2e skips a pom.xml that does not parse at the first import with a `ProjectStatus` of OK, and jdtls then calls each
source file "a non-project file" (syntax only). A file under `src/main/java` or `src/test/java` of a root with a build
file, or of the nearest folder with one under a root that has none (a build beside others), that gets that warning
marks that build's import failed (`LspCoordinator.noteNonProjectFile`): said once with that
reason, then the ready line's "did not import" and each counter's "Not checked". So does "not on the classpath of
project <folder>_<hash>", the invisible project jdtls makes when a loose file was opened first (a real project's
name is not enough: a healthy project keeps a nested sample's, `buildSrc`'s or an excluded file off its classpath), and
a reactor module
whose pom.xml does not parse (checked at the ready line, `JavaImportCheck.mavenModules`); a pom that does not parse
is named with its parse error's line and column (`JavaImportCheck.unreadablePom`). A loose file elsewhere in the
project is a non-project file by rights and is not counted.

"Did the import fail" reads jdtls's last logged Error; the session forgets it at a launch and at `ProjectStatus` OK,
and "Error occured while building workspace" (a compile error a build found) is never one, nor a Java Model Exception
("… does not exist", logged for each request on a file outside the source folders), so a build problem after a good
import is "the build has problems"; only that element's shape is skipped (`[in …] does not exist`), as Gradle's own
"Included build '…' does not exist" is a failed import. A `language/status` Error before jdtls says it is ready is an
import that threw (m2e on a dependency without an artifactId): the ready line then says the project did not import,
with the pom's "Project build error" when Problems has it. That error on a folder's pom.xml (a dependency without a
version, a parent Maven cannot resolve) is the folder's failed import too, at the ready line or when it comes later
(`JavaImportCheck.modelError`): m2e made no Java project of it, and jdtls gives its files no diagnostics at all. So is
a source file of a module whose pom says `<packaging>pom</packaging>`. A Gradle "Unresolved dependency" is a build with
problems, its files checked. A failure is keyed by the folder of the build that failed
unless the root's own
pom.xml does not parse (a Maven and a Gradle build side by side, or a Gradle build in `tools/` of a Maven root), and
counters, the ready line
and Reload look at the folder and the folders under it that the same session serves; the reason is the project
diagnostic's first line that names the cause (`JavaImportCheck.reasonLine`: "Unexpected input", "Unexpected
character", "Unresolved reference", a "@ line N, column M"), with a script's path cut to its file name, not Gradle's
closing "1 error" nor its "Could not compile settings file" wrapper. A failure that is one folder's under the root is
said with that folder's name ("jdtls (tools)").

jdtls updates a project's classpath from a build file only when told it changed, lemminx revalidates against a
grammar only when told it changed, and the Bash server never re-reads a sourced library on its own. The Project panel
reports changes from its expanded folders alone, so every local server root gets a recursive watcher of its own
(`LspFileWatching.ensureRootWatcher`, one `RootWatcher` per root, none for a root inside one already watched, at most eight),
project or not. It skips hidden, dependency and virtual-environment folders and the folders the root's `.gitignore`
files leave out (nested ones too). A watcher registers at most 2,000 folders (300 when polling), level by level so build files near the top come
first, and it reports a file only when its own folder is registered (`RootWatcher.watches`), not anything under its root; the panel's own event for a
file that a watcher reports is not sent again (`LspManager.reportsChangesTo`). A build file saved here that no watcher
covers (an SSH project, or one past the folder cap) is sent as a watched-file change on save.

A server that runs elsewhere (SSH, WSL, a container) has no watcher here: `RemoteRootScanner` lists its root again
(at most 300 folders, 5,000 files, the same folders skipped) when the window comes back to the front and every minute
while it stays there, and reports the difference as watched-file events.

**Python specifics** (Python review 2). In a project window a Python file's server is rooted at the nearest folder
below the project root holding `pyproject.toml`, `setup.py`, `setup.cfg` or `pyrightconfig.json`
(`RootResolver.pythonProjectRoot`; a uv workspace member stays with its workspace), so a `backend/` is analysed with
its own `.venv`, `src/` and `[tool.pyright]`. The interpreter a session is told (`python.pythonPath`) is resolved
from its root; the status-bar item resolves the same way (`PythonInterpreterCoordinator.setServerRoots`), so it shows
what Pyright was told. A watched event that creates or removes an environment folder or a `pyvenv.cfg` re-resolves
it (`LspManager.setOnPythonEnvironmentChanged`, now and twice more within six seconds). Pyright watches nothing itself
when the client does not register its watchers, so `PythonLibraryWatchers` watches the top of each local session's
`site-packages` and tells that session when a package folder appears or goes. Pyright is started with
`disablePullDiagnostics` and publishes; Problems → Whole project sends `python.analysis.diagnosticMode =
"workspace"` (pulled diagnostics would cover the open files only), Open files sends `openFilesOnly`.

bash-language-server has no `didChangeWatchedFiles` handler. For a Bash session, a shell script (by extension or name,
or `.inc`) created or changed under its root and open in no window is opened and closed again with its text from disk
(`BashReanalysis`, gathered for 400 ms, at most 200 files of 1 MB per burst, read off the FX thread and sent from it);
the server parses it afresh and the close clears what it publishes. The other Bash workarounds are in `BashShims`:
after the server's first `workspace/configuration` has been answered (`AfterRequestConsumer`, on the reader thread, so
the answer is already queued) and after every `bashIde` push, each open document is re-sent unchanged, because the
server lints only the document it heard of last; Find References asks with `includeDeclaration: true` and drops the
reference that starts where `textDocument/definition` does, because the server reads `false` as "without the
occurrence under the caret"; ShellCheck's columns, which count code points, are made UTF-16 in its diagnostics and quick
fixes against the text the session sent.

### jdtls preferences

jdtls's defaults assume vscode-java, which sends its own `package.json` defaults; several features stay
off for a client that sends nothing. `LspServerSettings.javaSettings` is the one `java` object, sent at
`initialize` (inside `ServerInitOptions.javaSettings`, with autobuild and the runtimes) and pushed after it:
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

`LspRefactorings.runGeneratePrompt` / `runRefactorCommand` intercept these before `applyCodeAction`;
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
The fork's inlay labels are mouse-transparent, so `BufferInlays.installCodeLensClick` finds the lens
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

Its larger workflows are package-private classes it constructs and forwards to, each reaching back through
a small `Host` of its own: `LspWorkspaceEdits` (applying workspace edits, their outcome reports, interrupted
edits) with `WorkspaceEditStaging` (the filesystem transaction and its journal), `LspRefactoringUndo` (Undo
Last Refactoring and the Project tree's rename participant), `LspRefactorings` (Rename and the jdtls
generate/refactor prompts), `LspNavigation` (definition, references, implementations, hierarchies, workspace
symbols, library-source tabs; it owns the navigation request generation), `LspSuperMethods` (Go to Super
Method) and `LspPopups` (hover card, signature help, Smart Type). The commands and other windows still call
`LspCoordinator`.

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
destination in the tab's spelling (`LspWorkspaceEdits.inSpellingOf`) so the tab stays under the project and
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

**Loading a project's build is under the same gate** (`lsp/UntrustedBuilds`, since 0.27.1, when language
servers became on by default). Some servers run the folder's own code just to load it: jdtls imports Gradle by
running its build scripts and Maven by loading its build extensions, Kotlin's server imports Gradle, Metals runs
sbt/Mill or compiles Scala macros, rust-analyzer builds `build.rs` and proc macros, Ruby LSP runs Bundler on the
`Gemfile`, csharp-ls evaluates MSBuild, and clojure-lsp asks Leiningen, Boot or shadow-cljs for a classpath.
`LspManager` reads the root's trust on the caller's thread and, on the start thread:

- **jdtls** always starts, but on an untrusted root with `java.import.{maven,gradle}.enabled=false`, the Gradle
  wrapper off and `configuration.updateBuildConfiguration=disabled` — in its `initializationOptions` and in every
  later `didChangeConfiguration` push (`LanguageServerSession.buildImportsOff`), so a push cannot turn an import
  on. When the root holds a Maven or Gradle build, `onBuildNotLoaded` fires and the file says so.
- **The others** are withheld (`onStartWithheld`, no crash notice, no retry) on an untrusted root holding their
  build files — searched one folder down for Rust and two for C# (`UntrustedBuilds.hasBuild`). A loose file with
  no build starts as before. `deps.edn` and `bb.edn` are read as data and do not gate clojure-lsp.

`ui/XmlFolderTrust` shows the file notice (`lsp.trust.javaNotice` / `lsp.trust.withheldNotice`) with **Trust
Folder**, and on every trust broadcast calls `LspManager.restartForTrust`: a gated session whose root's trust no
longer matches the one it started with stops (a jdtls started without its build on a root now trusted, any gated
server on a root no longer trusted), and its files sync again, which starts it as the trust now says. Remote and
container roots go through the same check (the file search runs on the start thread). Not gated yet: Pyright runs the
interpreter it is given, which may be a project’s own `.venv`.

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
file whose name says nothing XML when it opens with `<?xml` (not a `.txt`). `.mod .sch .props .targets .fo` are shared
with other formats, so such a file stays XML only while its first non-blank character is `<`
(`LanguageRegistry.sniffedXmlLanguage`); a Modula-2 module or a KiCad schematic is plain text. A DTD opens as language id `dtd` and a
stylesheet as `xsl`, as vscode-xml sends them. lemminx keeps its server in the large-source tier up to
`XmlServer.HEAVY_FILE_LINE_LIMIT` lines; the 5 MB large-file cut still comes first ("up to 100,000 lines and 5 MB").
Its protocol extensions: `initializationOptions.extendedClientCapabilities`
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

**Ctrl/Cmd-hover links** (`editor/DefinitionLinks`). Holding the shortcut key alone over a view underlines what a
Ctrl/Cmd-click there would follow and turns the pointer into a hand, as VS Code and IntelliJ do; the click is still
`EditorBuffer`'s own Ctrl/Cmd-click filter. Each view has its own node-less `DefinitionLinks` (the mouse and key
handling); the underline is `DefinitionLinkOverlay`, a mouse-transparent region with a one-pixel bar, attached on the
first link a buffer shows (the split's second view gets its `SecondaryPane.Followed` twin), so a buffer nobody
Ctrl-hovers builds nothing. Once the mouse
rests on a new character for `SETTLE` (60 ms), `EditorBuffer.probeDefinitionLink` answers with a span: an HTML
`href`/`src` value (found off the FX thread, `HTML_LINK_HOVER_SITE`), else the identifier there when the server
knows its definition (`setDefinitionProbe`, wired in `LspCoordinator` to a plain `definition` request that does not
touch the navigation request generation). The answer is kept for its span, a link or not, so moving within a word
asks nothing more; a press, a drag, an edit, a scroll, a key that is not a modifier, or the key let go drops it, and
a late answer is dropped by a generation counter. The key filters sit on the scene only while the mouse is over the
view, so the half of a split without the focus still hears the key. Markdown links are styled as links already and
are left alone.

**HTML links, markup checks and colours** (`HtmlLspAids`, owned by `LspCoordinator`). The HTML server's definition
answers only for embedded JavaScript, so Go to Definition and Ctrl/Cmd-click fall back to `HtmlLinkFollow` on an
`href`/`src` value (`editops.HtmlLinks`): the server's `documentLink` at the caret when it runs
(`LspManager.documentLinkTarget`; it percent-encodes a fragment as `%23`, split off there), else the value resolved
against the page, root-relative against the project; the file opens at the element whose `id` the fragment names,
a missing one says "File not found", a web address goes to the browser after a confirm. The same fallback runs with
LSP off. The server validates no markup, so `editops.HtmlMarkupCheck` (unclosed and stray tags, missing local link
targets) is added to its pulled or pushed diagnostics off the FX thread (`LspManager.withMarkupChecks`, source
`editora`). A page with template syntax (`HtmlElements.hasTemplateSyntax`: `{% %}`, `{# #}`, `<% %>`, a `{{ }}` block
form) or in a template folder (`templates/`, `_layouts/`, `partials/` …) is not checked for balance, only for links;
a link missing where it points is accepted under the project's `public/`, `static/` or `assets/`
(`HtmlMarkupCheck.servedElsewhere`). A page no server serves shows the same check on the `.http` variable marks'
squiggle overlay (`HttpVariableMarks.MarkupCheck`, gated on `!isLspActive()`; the buffer holds several such checks
and runs the first whose gate is open — JSON Lines' one-value-per-line check, `ui.JsonLinesMarks`, is the other,
since no server reads that format). `textDocument/documentColor` (any server with a
`colorProvider`: html, css) rides `requestFoldingRanges`' pulses; `EditorBuffer.setColorSwatches` draws an inlay per
colour, painted by a per-buffer `data:` stylesheet with one class per colour (`ColorSwatchInlays`), on the text areas, not the buffer root (adding a colour re-styles only them). Between answers
the cached colours move with each edit (`ColorSwatch.shifted`, on the area's `plainTextChanges`), so a swatch stays
before its literal while the user types.

**HTML typing aids.** A completion item whose command is `editor.action.triggerSuggest` (a CSS property, a folder in
`href`) re-opens the list manually after the accept; the accept's own automatic passes at that version and caret
(the queued one and the 90 ms settled-edit one) leave it alone (`BufferCompletion.twoStepVersion`) — they used to
hide it or drop the server's answer in flight. Tab never asks the server to re-indent a line inside a verbatim
element (`IndentWindow.serverReindents`): the server formats a two-line fragment without the element around it.
Inside a page's `<script>`/`<style>` Tab and Enter use the embedded language's brace rules; a `<pre>`/`<textarea>`
line keeps its whitespace. Auto-quote (`TagTyping`) follows `htmlAttributeQuotes` (an application-wide static set
by `EditorSettingsCoordinator`), and `HtmlCaretRegion.before` heads its 20,000-char window with the start tag of
the script or style the window begins inside, read backwards.

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

### Language profiles and transports

Editora debugs Java, Python, JavaScript (Node and Deno), the JVM languages, Go, Rust, C, C++, C#, Ruby, PHP, Bash, Dart (Flutter too) and Lua, off by default
(`Settings.debugSupport`). Each language is a [`dap/DebugLanguage`](../../src/main/java/com/editora/dap/DebugLanguage.java)
profile in [`dap/DebugLanguages`](../../src/main/java/com/editora/dap/DebugLanguages.java): its `Kind` transport, the
DAP `launch` type, the `initialize` adapter id, the default interpreter + adapter args, the fallback transport, what
makes it available (`Availability`: the java-debug plugin, a located adapter, or built in), the exception filters it
offers and its "caught" one, the language server's start command for a hosted adapter, whether a remote file is
debugged on its own host, and its "unavailable" message. [`dap/DapServerRegistry`](../../src/main/java/com/editora/dap/DapServerRegistry.java),
`ExceptionFilterCatalog`, `HostedDebugAdapter`, `DapManager.isLanguageAvailable` / `debuggableOnHost` and
`DebugCoordinator.debugEffectiveFor` read them instead of comparing language names. An adapter's own settings
(its switch, command or path, install id, off and missing messages) are a binding in
[`ui/DebugAdapterSettings`](../../src/main/java/com/editora/ui/DebugAdapterSettings.java), which the Debugging
page, the `debug.toggleAdapter` / `debug.setAdapterPath` pickers and `unavailableMessage` read. `languageIdsForDebug()` =
`{java, python, javascript, groovy, kotlin, clojure, scala, go, rust, cpp, csharp, ruby, php, bash, dart}` (`c` files are served by `cpp`); `typescript`, `javascriptreact` and `typescriptreact` files are served by the
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
| `go` | `EXTERNAL` | Delve: `dlv dap --listen=127.0.0.1:0`, the port read from its `DAP server listening at:` line (`ANNOUNCED_PORT`); see **Go** below. |
| `rust` | `EXTERNAL` | CodeLLDB: `codelldb --port N` on a port Editora picks (`PORT_ARGUMENT`); see **Rust** below. |
| `cpp` | `EXTERNAL` | The same CodeLLDB as Rust (`DebugLanguages.CODELLDB`); see **C and C++** below. |
| `csharp` | `EXTERNAL` | netcoredbg: `netcoredbg --interpreter=vscode` over stdio (`STDIO`); see **C#** below. |
| `ruby` | `EXTERNAL` | rdbg, which is the program itself: `rdbg --command --open --stop-at-load --host=127.0.0.1 --port=0 -- ruby <file>`, the port read from its `Debugger can attach via TCP/IP` line (`ANNOUNCED_PORT`); see **Ruby** below. |
| `php` | `EXTERNAL` | vscode-php-debug: `node phpDebug.js` over stdio, which starts `php` with Xdebug; see **PHP** below. |
| `bash` | `EXTERNAL` | vscode-bash-debug: `node bashDebug.js` over stdio, which runs the script under the bashdb it ships; see **Bash** below. |
| `dart` | `EXTERNAL` | The SDK's own adapter: `dart debug_adapter` over stdio, or `flutter debug_adapter` (`DebugLanguages.FLUTTER`) for a Flutter app's file; see **Dart and Flutter** below. |
| `scala` | `LANGUAGE_SERVER` | An adapter the language server starts on request ([`HostedDebugAdapter`](../../src/main/java/com/editora/dap/HostedDebugAdapter.java)): Metals' `workspace/executeCommand debug-adapter-start` with `{path, runType: "run", args, jvmOptions, env}` compiles the build target and answers `{name, uri: "tcp://host:port"}`; `DapClient.connect(port, "scala")`, then `launch {name, noDebug: false}`. Metals' adapter runs the program and reports its output. Only for a file of an sbt, Mill, Gradle or Maven build (`ScalaRun.ownerOf`); a script or a Scala CLI project falls back (`DapServerRegistry.fallbackKind`) to `JVM`: `scala-cli run --java-opt <agent> <file> -- args`, the agent in place of `JvmScriptRun.AGENT_ARGUMENT`, and breakpoints bound by file name (Scala packages need not follow folders). |

**External adapters.** A `Kind.EXTERNAL` profile carries an [`ExternalAdapter`](../../src/main/java/com/editora/dap/ExternalAdapter.java):
the adapter's command and how DAP reaches it — `STDIO` (lldb-dap, `gdb -i dap`, netcoredbg), `PORT_ARGUMENT` (a free
port Editora picks, in place of `{port}`; CodeLLDB) or `ANNOUNCED_PORT` (the adapter prints the port it listens on,
matched by the profile's pattern; Delve's `DAP server listening at: 127.0.0.1:38697`). `DapManager.configureExternal`
finds the command (a configured path, else on the PATH: `locateExecutable`) and `startExternal` starts it with the
found executable in place of the command's first word, connects (`connectStdio`, or `connect` with the adapter
process set to be killed on dispose), and sends the `launch` the language shaped (`ExternalAdapter.launchRequest`:
`program`, `args`, `cwd`, `env`, `stopOnEntry` plus the language's own fields). Output before an announcement goes to
the console as the command's; after it, as the program's (Delve writes its debuggee's output there). A request whose
`request` is `attach` is sent as `attach`, and Stop then detaches (`setTerminateOnDisconnect(false)`).
`setExternalPathDirs` puts folders first on an adapter's PATH; Go uses it. `startExternal` can take words after the
adapter's command (`adapterTail`): the program, for an adapter that *is* the program (Ruby's rdbg), which then starts
in the request's `cwd` with its `env` (`DapManager.adapterFolder`). An adapter found as a `.js` file runs on the
Node.js Settings names (`adapterNode`; vscode-php-debug). An adapter Editora can install is found as CodeLLDB is
([`dap/InstalledAdapter`](../../src/main/java/com/editora/dap/InstalledAdapter.java): a configured file or folder,
else `<config>/plugins/dap/<language>`, else the newest VS Code extension that ships it, else the PATH). An external profile's
session starts with its own default exception filters when none were chosen (`ExternalAdapterFilters`):
CodeLLDB names `rust_panic` only after `launch`, too late for its `initialize` defaults.
`DebugReviewCWindowFxTest` drives all three transports through `RelayAdapterMain`, a test process standing in for
the adapter in front of the in-process fake.

**Go.** The `go` profile is Delve's DAP server. `DapManager.locateExternal` finds `dlv` as configured
(`Settings.goDebugPath`, or a trusted project's `debug.go`), else on the PATH, else in the folder `go install` fills
(`$GOBIN`, each `$GOPATH/bin`, `~/go/bin`: it may not have existed when Editora read its PATH). `Settings.goDebugEnabled`
switches it, and Settings → Debugging → Go has its Install, which runs `go install
github.com/go-delve/delve/cmd/dlv@latest` (`InstallCatalog.debuggerInstall("dlv")`, needing `go`).

[`dap/GoLaunch`](../../src/main/java/com/editora/dap/GoLaunch.java) shapes the requests the way VS Code's Go extension
does:
- A file of a module (a `go.mod` above it) debugs its package (`mode: debug`, `program` = its folder, as `go run .`).
- A file outside one debugs alone (`go run file.go`).
- In a `_test.go` file, `mode: test` runs the test function the caret is in (`-test.run ^TestX$`; a benchmark
  `-test.run ^$ -test.bench ^BenchmarkX$`), or every test of the package outside one.
- `output` is a file of its own in the temporary folder. Left to Delve, it is `__debug_bin…` in the package, and it
  stays there when Stop kills Delve.

`DebugCoordinator.debugGoFile` takes the `go` the Go build tool runs (`buildExecutable(GO, module)`) and puts its
folder first on Delve's PATH, since Delve builds with it. Before each start and Restart it saves the folder's Go files,
because Delve builds from disk. Restart repeats the same test wherever the caret is now. Debug Test at Caret on a Go
test file (`debugGoTestAtCaret`) is the same launch.

Attach to Process lists Go programs: `LocalProcesses` reads each candidate executable's section names for
`.go.buildinfo` / `__go_buildinfo` ([`dap/ProgramFiles`](../../src/main/java/com/editora/dap/ProgramFiles.java); ELF and
Mach-O, not Windows), once per program file. It attaches with `attach {mode: local, processId}`. On Linux that needs
`kernel.yama.ptrace_scope` 0, as for any debugger attaching to a program it did not start.

Delve reports no exception filters (it stops on a panic by itself), and it lists at most 1024 goroutines as threads.
Comment-only Go lines take no breakpoint (`CodeLessLines`), and a `select` block is control flow to
`InlineValueScope`.

**Rust.** The `rust` profile is CodeLLDB (LLDB with Rust's formatters). `DapManager.locateExternal` finds `codelldb`
through `DebugAdapterLocator.locateCodelldb`, in this order:
- as configured (`Settings.rustDebugPath`: the program, or an unpacked extension's folder);
- Editora's install (`<config>/plugins/dap/rust`);
- VS Code's newest `vadimcn.vscode-lldb-*` extension;
- the PATH.

Settings → Debugging → Rust's Install fetches the pinned per-platform `.vsix` from CodeLLDB's GitHub release
(`InstallCatalog.debuggerInstall("codelldb")`, a `PINNED_SERVER` step, SHA-256 checked). On Linux, whose GNU tar
reads no zip, Editora unpacks it itself (`InstallService.unzipWithPrograms`) and marks the files of every `bin` and
`adapter` folder executable.

[`dap/RustLaunch`](../../src/main/java/com/editora/dap/RustLaunch.java) plans a Rust file of a Cargo package (the
nearest `Cargo.toml`):
- **The test at the caret.** A `#[test]` (or `#[tokio::test]`…) function is debugged in its test binary, filtered
  to its path (`net::http::tests::adds --exact`, the file's module path plus its inline modules).
- **An integration test file outside a test:** all its tests (`--test it`).
- **Otherwise:** the package's program (`cargo build`, or `--example x`).
- **A library without a program:** the file's tests.

`DebugCoordinator.debugRustFile` asks for the package's trust (`buildExecutable(CARGO, root)`), saves its Rust files,
and runs the build as a before-launch step in the Debug console (Stop ends it). It then runs the same command again
with `--message-format=json`, which builds nothing, and launches the executable whose target root is the file
(`RustLaunch.artifacts` / `pick`). Test binaries carry a hash in their names, which is why they're found this way.

The `launch` adds `sourceLanguages: ["rust"]` (Rust's formatters and the panic stop) and `terminal`: `console`, or
`integrated` (`runInTerminal`, the program's input) with "Run the debugged program in the Debug console".
CodeLLDB 1.12 also uses `runInTerminal` in `console` mode: it has the client start its launcher,
`codelldb-launch --connect=…`, which connects back so the adapter can start the program with the launcher's standard
streams, and it ends the launcher with SIGTERM once the program has exited. So that launcher is not the program
(`DapManager.isAdapterLauncher`): its exit (143) is not reported, the adapter's `exited` event gives the program's code,
and the launcher's end ends the session after that event (or after `LAUNCHER_EXIT_GRACE_MILLIS` without it).
CodeLLDB reports a panic's stop with the reason `breakpoint`. Rust's LLDB formatters expand a `Vec`, a `String` and a
`HashMap`, but not a `BTreeMap`, which shows as its raw tree (so `samples/debug/rust` keeps a `HashMap`).
Attaching to a running Rust program isn't offered yet.

`RustDebugProbeFxTest`, opt-in, runs in CI with the pinned CodeLLDB. It drives the real Cargo and CodeLLDB:
- Rust values, Step Over and the program's output;
- the test at the caret running alone;
- a panic;
- in a window, F5 and Debug Test at Caret.

`RustDebugWindowFxTest` drives the window against stand-in `cargo` and `codelldb` scripts.

**C and C++.** The `cpp` profile (`c` and `cpp` files) runs on Rust's CodeLLDB: one `codelldb`, found through
`Settings.rustDebugPath`. `Settings.cppDebugEnabled` switches it, from a check box on Settings → Debugging's
"Rust, C and C++" card. [`dap/NativeBuild`](../../src/main/java/com/editora/dap/NativeBuild.java) builds the file
first:
- **A CMake project** (`cmakeRoot`: the topmost of the folders above the file that each have a
  `CMakeLists.txt`). A Debug build in `build/editora-debug` (`cmake -S … -B … -DCMAKE_BUILD_TYPE=Debug`, then
  `cmake --build`). A `codemodel-v2` File API query is written first, and the reply's executable target whose
  sources hold the file is launched (`cmakeExecutable`), else the only executable target.
- **A Make project** (`makeRoot`: the nearest makefile). `make -C <folder>`, its default goal, then the native
  program in the folder (or `bin/`, `build/`) named after the file, else the only one, else the newest
  (`makeExecutable`; a program is told from a script by its ELF, Mach-O or PE magic).
- **A lone file.** `cc` / `c++ -g -O0 -o <config>/cache/native-debug/<stem>-<hash>`. A lone header says to open
  its source.

CMake and Make run the project's code, so `DebugCoordinator.debugNativeFile` asks the folder's trust first
(`Ops.trustBuild`, the build-trust prompt with one line about the build file). A compiler run asks nothing. The
build steps run one after another as before-launch steps in the Debug console; later steps keep the earlier ones'
output (`beforeLaunchConsole(false)`). Restart builds again.

The `launch` adds `sourceLanguages: ["cpp"]`. A thrown C++ exception stops it (`cpp_throw`, on by default as
CodeLLDB has it, sent as the profile's default: see **External adapters**).

**Run configurations for C, C++, Go and Rust.** The types `c`, `cpp`, `go` and `rust`
(`ScriptRunCommand.isCompiled`) name a source file, resolved like a script's (`RunConfigWorkingDirectory.script`).
What is built for it is decided at launch, by the code F5 uses:
- **Debug.** `DebugCoordinator.debugCompiledConfig` runs the configuration's before-launch step, then calls
  `debugNative`, `debugGo` or `debugRust`, the same methods Debug File reaches. `LaunchExtras` carries what the
  configuration adds: its variables for the program's environment, its working directory (null when blank: the
  folder F5 uses) and whether a step of its own has already written to the Debug console. No caret picks a test,
  so a Go or Rust test file debugs all its tests. A Go launch in another folder sends Delve `dlvCwd`, the package's
  folder, since Delve builds from its own working directory.
- **Run.** There was no Run for these languages. `RunCoordinator.runCompiledConfig` builds in the Run console
  (`NativeBuild.plan`'s steps; `GoLaunch.buildArgv`: `go build -o`, or `go test -c` for a test file; Cargo's
  `RustLaunch.Plan`), finds the program off the FX thread (`NativeBuild.executable`, the Go output, Cargo's JSON
  messages through `RustLaunch.pick`) and streams it. A lone C, C++ or Go file's program goes to
  `<config>/cache/native-run/<stem>-<hash>`. Rerun builds again. Stop during the search starts nothing. A Go file
  whose package clause (`GoLaunch.packageName`) is not `main` is refused before the build: `go build -o` writes such
  a package's archive and exits 0.
- The configuration's variables go to the program, not to the build. The trust questions are F5's: the Go and
  Cargo build tools' (`Ops.buildExecutable`), and the folder's for CMake and Make (`Ops.trustBuild`).
- Both paths refuse a file that runs elsewhere (a remote host, a container, WSL, a deployment): the program is built
  into this machine's cache.
- **Run File.** A C, C++, Go or Rust file with a program's `main` is runnable (`RunScan`, from
  `run.CompiledRunLine`: a C or C++ `main` definition outside a header, `func main` in `package main` or a Go test
  file's first test, a top-level Rust `fn main`), so it gets the gutter ▶, the Run File row (with Debug File beside
  it) and `file.run`. `RunCoordinator.launchCompiledFile` builds a `CompiledRun` with the file's own program
  arguments and no working directory of its own and hands it to the same `runNative`, `runGo` or `runRust` a
  configuration uses; nothing it finds wrong opens a configuration form (`CompiledRun.configured` is false). A file
  without a `main` keeps Debug File of its own (`DebugCoordinator.offersOwnDebugFile`), and Debug: Debug Current File
  accepts either kind.

**Attach to Process: native programs.** On Linux `LocalProcesses` lists a program built with debug information
(`ProgramFiles.kind`: `.debug_info` and no Go section). It's listed as Rust when rustc's `.debug_gdb_scripts` or
`.rustc` is there, else as native. CodeLLDB attaches by `pid` with that language's `sourceLanguages`
(`NativeBuild.attach`). A macOS program keeps its DWARF elsewhere, so it isn't listed. GDB's DAP mode
(`gdb -i dap`) is left for a configurable adapter.

`NativeDebugProbeFxTest`, opt-in, runs in CI with the pinned CodeLLDB. It drives the real tools:
- a lone C file compiled with `cc`;
- a CMake C++ project stopping in another source file, with `std::vector` shown as a value;
- a Make project;
- an attach then detach;
- in a window, F5 on a CMake project.

`NativeDebugWindowFxTest` drives the window against a Make project whose recipe writes a stand-in program, with a
stand-in `codelldb`.

**C#.** The `csharp` profile is Samsung's netcoredbg (MIT), `netcoredbg --interpreter=vscode` over stdio.
`InstalledAdapter.NETCOREDBG` finds it as configured (`Settings.csharpDebugPath`), else in Editora's install
(`plugins/dap/csharp/netcoredbg/netcoredbg`), else in the free C# extension's `.debugger/netcoredbg`
(`muhammad-sammy.csharp-*`; Microsoft's own extension ships vsdbg, licensed for Microsoft's tools only), else on the
PATH. `Settings.csharpDebugEnabled` switches it. Settings → Debugging → C#'s Install fetches the pinned release
(`InstallCatalog.netcoredbg`, 3.2.0-1092, SHA-256 checked; Samsung builds no Intel macOS one).
[`dap/DotnetLaunch`](../../src/main/java/com/editora/dap/DotnetLaunch.java) plans F5:
- **A project**: the nearest `.csproj` in the file's folder or above it (of several, the one named after the folder).
  `dotnet build <project>` runs as a before-launch step, after the folder's trust (MSBuild runs the project's
  targets: `dialog.trust.item.dotnet`).
- **A lone file**: a .NET 10 file-based app, `dotnet build <file>`; no trust is asked (the SDK's own targets).

Then `dotnet build <target> -getProperty:TargetPath`, which only evaluates, names the assembly (`targetPath`: the
last absolute `.dll` line). netcoredbg's `launch` gets it as `program`, run on `dotnet` in the project's folder
(`justMyCode`); the program's output comes as output events. An exception user code does not handle stops it
(`user-unhandled`, on by default as in Visual Studio; `all` stops on every throw). `ProcessRunner`'s PATH includes
`~/.dotnet`, where Microsoft's install script puts the SDK. Not yet: C# in Attach to Process.

**Debug Test at Caret in C#** ([`ui/CsharpTestDebug`](../../src/main/java/com/editora/ui/CsharpTestDebug.java),
[`dap/DotnetTests`](../../src/main/java/com/editora/dap/DotnetTests.java)) is Visual Studio's and C# Dev Kit's way:
the file's project must be a test project (`isTestProject`: `Microsoft.NET.Test.Sdk`, xUnit, NUnit or MSTest in its
`.csproj`, or `<IsTestProject>`), and the caret in a test method (`testAt`: an xUnit `[Fact]`/`[Theory]`, an NUnit
`[Test]`/`[TestCase]`, an MSTest `[TestMethod]`, from its attributes to the next method) or in a class with tests.
After the folder's trust, `dotnet test <project> --filter <filter>` runs in the Debug console with
`VSTEST_HOST_DEBUG=1` (`filter`: `FullyQualifiedName=` the method, `|FullyQualifiedName~` it followed by `(` for a
parameterized test's cases; the class's `FullyQualifiedName~Ns.Class.` outside a test). It builds, starts the test
host, and the host waits for a debugger, printing `Process Id: N, Name: dotnet` (`processId`); netcoredbg then
attaches to N (`DotnetLaunch.attach`), and the test runs under the debugger. The run is the session's
(`DebugSession.testHost`): Stop ends it, Restart runs the same test again, and a run that ends before its host
waited says so (`status.debug.dotnetTestNoHost`: a build error, a filter that matches nothing).
`CsharpTestDebugWindowFxTest` (a stand-in `dotnet` that prints a process id, netcoredbg relaying to
`FakeDebugAdapter`) checks the command, the environment, the attach, Stop, and a project with no test SDK;
`CsharpTestDebugProbeFxTest` stops in an xUnit test on the real SDK and netcoredbg with its locals.

**Ruby.** The `ruby` profile is the debug gem's `rdbg` (Ruby 3.1 and later ship it). It is the program itself: the
command ends with `-- ruby <file> <args>` (`ScriptLaunch.rubyTail`), rdbg prints `DEBUGGER: Debugger can attach via
TCP/IP (127.0.0.1:N)` on standard error (`DebugLanguages.RDBG_ANNOUNCEMENT`; the adapter's streams are merged) and
waits at load; `launch` (`localfs: true`) tells it it runs on this machine's files, and `configurationDone` lets it
go on. The program's output follows the announcement on the same stream. `DapManager.locateExternal` finds `rdbg` as
configured (`Settings.rubyDebugPath`), else on the PATH, else in the user's gem folders (`gemUserBinDirs`:
`~/.local/share/gem/ruby/*/bin`, `~/.gem/ruby/*/bin`, newest first). Settings → Debugging → Ruby's Install runs
`gem install --user-install debug` (a system Ruby's own gem folder is root's). Its exception filters (`any`,
`RuntimeError`) are off by default, as in VS Code.

What a Ruby file runs as is [`run/RubyApps`](../../src/main/java/com/editora/run/RubyApps.java)'s plan, the same for Run
File (`RunCoordinator`, `FilePlan.ruby`) and F5 ([`ui/RubyDebugLaunch`](../../src/main/java/com/editora/ui/RubyDebugLaunch.java),
planned off the FX thread, its command after rdbg's `--` in place of `ruby <file>`), in the project's folder (the nearest
with a `Gemfile`), under `bundle exec` when the bundle is installed (`Gemfile.lock`): an RSpec file under `rspec
file[:line]`; a Minitest file (`*_test.rb`, `test_*.rb` with a test class) under `ruby bin/rails test file[:line]` in a
Rails app (`config/application.rb` and `bin/rails`), else `ruby -Itest file [-n name]`
([`run/RubyTests`](../../src/main/java/com/editora/run/RubyTests.java): `def test_…`, or ActiveSupport's `test "…"`
made `test_` + its words joined by `_`); a Rails app's file under `app/` or `config/`, its `config.ru` or `bin/rails`
under `ruby bin/rails server -p <first free from 3000> -b 127.0.0.1`; anything else `ruby file`.
[`run/RubyRunLines`](../../src/main/java/com/editora/run/RubyRunLines.java) puts the ▶ on specs (their `describe`),
tests (their class), `config.ru`, `bin/rails`, `Rakefile` and a script with a statement at the left margin that is not a
declaration; a file of classes, modules and methods has none. Probed under rdbg 1.11 with Ruby 4.0, RSpec 3.13 and Rails
8.1: a spec's example by `file:line`, `bundle exec` with no `debug` gem in the bundle (rdbg loads it itself), a
controller under `bin/rails test file:line` and in a request to `bin/rails server` all stop; Stop ends the server.
`RubyAppsProbeFxTest` (opt-in) drives each, the Rails cases on a copy of a prepared app (`~/.cache/editora/rails-probe`,
which the full-tests job makes with `rails new --minimal`).

**PHP.** The `php` profile is Xdebug through vscode-php-debug (MIT), `phpDebug.js` on Node.js 20.18 or later over
stdio. `InstalledAdapter.PHP_DEBUG` finds it as configured (`Settings.phpDebugPath`: the file or its unpacked
`.vsix`), else in Editora's install (`plugins/dap/php/extension/out/phpDebug.js`), else in VS Code's
`xdebug.php-debug-*`. Settings → Debugging → PHP's Install fetches the pinned `.vsix` (1.40.2, one for every
platform). PHP needs the Xdebug extension (`php-xdebug`). Its exception filters (`Exception`, `Error`, `Warning`,
`Notice`, `Deprecated`, `*`) are off by default.

What a PHP file runs as is [`run/PhpApps`](../../src/main/java/com/editora/run/PhpApps.java)'s plan, the same for Run
File (`RunCoordinator.launchPhp`) and F5 ([`ui/PhpDebugLaunch`](../../src/main/java/com/editora/ui/PhpDebugLaunch.java),
planned off the FX thread): a script (`php file`); a document root's `index.php`, or a class file under a folder with
`public/index.php`, on PHP's built-in server (`php -S 127.0.0.1:8000 -t public`, the first free port from 8000); a
Laravel app (its `artisan`, `public/index.php` or a class file of it) as `artisan serve` serves it — `php -S` in
`public/` with the app's own `server.php` or the framework's `resources/server.php` router — run directly, because
`artisan serve`'s server is a child process the adapter's Stop does not end and that the `-d` switches do not reach;
a PHPUnit test file (`*Test.php` with a `TestCase` class) under `vendor/bin/phpunit`, else `phpunit` on the PATH, in
the folder of its `phpunit.xml` or `composer.json`. [`run/PhpRunLines`](../../src/main/java/com/editora/run/PhpRunLines.java)
puts the ▶ on those, and on a script with a statement outside its declarations; a file of classes, a configuration
file's `return [...]` and a helpers file's `function_exists` guard have none.

F5 sends `ScriptLaunch.phpCommand`: no `program`; the planned command as `runtimeExecutable` and `runtimeArgs`, with
`port: 0` (the adapter listens on a free port) and Xdebug told to debug and connect there twice — as `-d` switches
after `php` (`-dxdebug.mode=debug`, `-dxdebug.start_with_request=yes`, `-dxdebug.client_port=${port}`), and as
`XDEBUG_MODE`, `XDEBUG_SESSION` and `XDEBUG_CONFIG` in `env` (the adapter fills `${port}` into both), which reach a
PHP the command starts itself: a PHPUnit runner that is a shell wrapper or a `.bat` (a runner whose first line is not
`<?php` or a `php` shebang runs as itself, with the environment alone). Each script or request is an Xdebug connection,
a thread of the one session; the adapter ends the server on Stop. Debug Test at Caret narrows a test file to the test
at the caret ([`run/PhpUnitTests`](../../src/main/java/com/editora/run/PhpUnitTests.java): a `test…` method, or one
marked `#[Test]` or `@test`) with `--filter '/::name(?: .*)?$/'`, which takes its data sets and not `nameMore`.

PHP has no attach: Xdebug connects to a listening adapter. Debug: Listen for Xdebug (`debug.listenXdebug`) starts one
on Xdebug's port 9003 (`ScriptLaunch.phpListen`), beside a running session, as a launch.json `php` configuration
without a `program` ("Listen for Xdebug") does. `PhpWebProbeFxTest` (opt-in) drives each of these with the real PHP,
Xdebug, PHPUnit and vscode-php-debug.

**Bash.** The `bash` profile (a shell file: `editorLanguages` `shell`) is bashdb through vscode-bash-debug (rogalmic,
MIT), `bashDebug.js` on Node.js over stdio; its `.vsix` ships bashdb 5.0 (`extension/bashdb_dir`), which runs on Bash
5.2. `InstalledAdapter.BASH_DEBUG` finds it as configured (`Settings.bashDebugPath`), else in Editora's install
(`plugins/dap/bash/extension/out/bashDebug.js`), else in VS Code's `rogalmic.bash-debug-*`. Settings → Debugging →
Bash's Install fetches the pinned `.vsix` from Open VSX (0.3.9, its last release; SHA-256 checked). Only a Bash script
is debugged (`ScriptLaunch.isBashScript`: a `bash` or `sh` shebang, or none, and no other shell's extension); another
shell's says so (`status.debug.shellNotBash`, pointing at Run with Trace). F5 sends `ScriptLaunch.bashLaunch` (the
script as `program`, its folder as `cwd`, output in the Debug console); `DapManager.runExternal` adds the programs the
adapter checks by absolute path (`withBashTools`: the bashdb beside `bashDebug.js`, and `bash`, `cat`, `mkfifo`,
`pkill` from the PATH), keeping a launch.json's own. Three things are Editora's: bashdb stops at the script's first
command and the adapter reports it as any stop, so a Bash session's first stop on a line with no breakpoint is passed
on (`DapManager.passesBashEntry`); bash-debug's Variables shows only `$PWD` and `$?`, the script's variables being read
by name (a watch, a hover: `total` gives `declare -- total="0"`), so `$total` and `${total}` are sent bare
(`adapterExpression` → `ScriptLaunch.bashName`; with a `$`, bashdb expands it in its own shell, where it is empty); and
a shell run configuration still is not debugged (`status.debug.shellNotDebuggable`). No exception filters, no attach.
`BashDebugWindowFxTest` (a stand-in adapter) covers the launch, the first stop passed, the watch and a zsh script;
`BashDebugProbeFxTest` stops in a loop on the real adapter, reads its variables, steps and runs to the end.

**Elixir.** The `elixir` profile is ElixirLS (elixir-lsp, Apache-2.0, 0.31.1), whose release zip holds the debug
adapter (`debug_adapter.sh`, `.bat` on Windows; `InstalledAdapter.ELIXIR_LS`, a script, `<config>/plugins/dap/elixir`,
`InstallCatalog.ELIXIR_LS_STEP`, settings `elixirDebugEnabled`/`elixirDebugPath`, schema 184) and the language server
beside it (`language_server.sh`, located by `install/ElixirServerInstall`: as configured, else that install, else
`elixir-ls` or `language_server.sh` on the PATH; held back by `UntrustedBuilds` in an untrusted folder with a
`mix.exs`, since it compiles the project). Its first start compiles ElixirLS with the user's Elixir into
`~/.cache/mix/installs` (Mix.install; Debian splits leex's `leexinc.hrl` into `erlang-dev`). A launch is a `mix_task`
([`dap/ElixirLaunch`](../../src/main/java/com/editora/dap/ElixirLaunch.java)) planned by
[`run/ElixirRun`](../../src/main/java/com/editora/run/ElixirRun.java) in the project (the nearest `mix.exs`): a test
file `task: test`, `taskArgs: ["test/x_test.exs:<line>"]` (Debug Test at Caret's line, else the file), `startApps`, and
`requireFiles` of `test/test_helper.exs` and the file, so a test's own module is interpreted too; another `.exs`
`task: run` with the script; a module `task: run`, `--no-halt` when `mix.exs` names an application `mod:`. Without a
Mix project ElixirLS fails the launch (`Mix.NoProjectError`): `ui/ElixirDebugLaunch` says so
(`status.debug.elixirNeedsMix`) and Run File runs `elixir file`. Run File and F5 in a project ask the trust question
first (`dialog.trust.item.mix`: Mix compiles the project and its dependencies). A launch.json `mix_task` configuration is
`LaunchRoute.Kind.ELIXIR_LS`. No exception filters. ElixirLS does not pass ExUnit's report to the console.
`ElixirDebugProbeFxTest` (opt-in) drives Debug Test at Caret (a stop in the module with its values, that test alone),
F5 on a project script, a script with no project, and Run File on a test file, on Elixir 1.18 / OTP 27.

**Lua.** The `lua` profile is local-lua-debugger (tomblind, MIT, `tomblind.local-lua-debugger-vscode` 0.3.3, its last
release; SHA-256 checked), `extension/debugAdapter.js` on Node.js over stdio. `InstalledAdapter.LUA_DEBUG` finds it as
configured (`Settings.luaDebugPath`), else in Editora's install (`plugins/dap/lua/extension/extension/debugAdapter.js`),
else in VS Code's extension. F5 sends `ScriptLaunch.luaLaunch`: `program` `{lua, file, communication: "pipe"}` (the
adapter starts `lua -e "require('lldebugger').runFile(…)"`; over a pipe — FIFOs, named pipes on Windows — the script's
own stdout is its output, not the debugger's channel), its folder as `cwd`. VS Code's extension fills in the rest before
the adapter sees it, so `DapManager.runExternal` does (`withLuaRuntime`): `extensionPath`, the folder above the
adapter's, where `debugger/lldebugger.lua` is (the adapter sets `LUA_PATH`/`LOCAL_LUA_DEBUGGER_FILEPATH` from it);
`workspacePath`; and for the bare `lua`, the first of `lua`, `lua5.4`, `lua5.3`, `luajit`, `lua5.2`, `lua5.1` on the
PATH, keeping a launch.json's own (`lua-local` routes to `LaunchRoute.Kind.LUA_DEBUG`; no attach). Probed with Lua
5.4.9: conditional breakpoints, locals/upvalues/globals scopes, evaluate, set variable; it stops on an uncaught error by
itself (no exception filters) and cannot pause a running script; the script gets no standard input. A custom adapter in
`debug-adapters.json` that lists `lua` no longer gets F5 (built-ins are looked up first), only its launch.json `type`.
`LuaDebugWindowFxTest` (a stand-in adapter) covers F5's launch and a launch.json configuration;
`LuaDebugProbeFxTest` stops in a function on the real adapter, reads its locals, steps, sees the output, and stops on
an error.

**Dart and Flutter.** The `dart` profile is the Dart SDK's own adapter, `dart debug_adapter` over stdio (`STDIO`);
`DebugLanguages.FLUTTER` is `flutter debug_adapter`, the same adapter inside the Flutter tool (VS Code's Dart extension
uses each so). FLUTTER is not a language of its own (no editor languages, not in `all()`, found by `byId` as GDB is):
a Dart file whose package — the nearest folder up with a `pubspec.yaml` (`run/DartRun.packageRoot`) — names Flutter's
SDK among its `dependencies` (`DartRun.isFlutter`: `flutter:` then `sdk: flutter`) is a Flutter app's. Its session is
Dart's (`DapManager.filtersFor` names it `dart`: its exception choices, the stops passed below). `dap/DartLaunch` plans
F5: `{program: <absolute file>, cwd: <package root, else the file's folder>, args, debugSdkLibraries: false,
debugExternalPackageLibraries: false}`, plus `toolArgs: ["-d", <device>]` for Flutter (the `flutterDevice` setting;
blank: this computer's desktop, `linux`, `macos` or `windows`). Without the two `false`s a stop lands in SDK frames.
[`ui/DartDebugLaunch`](../../src/main/java/com/editora/ui/DartDebugLaunch.java) (a part of `DebugCoordinator`) reads
the pubspec off the FX thread, asks the package folder's trust first (pub fetches its packages and runs their build
hooks: `dialog.trust.item.pubspec`), saves the package's other edited Dart files, says `status.debug.flutterMissing`
when `flutter` is not found, and starts `DapManager.startExternal`; a Dart run configuration goes the same way from
`debugCompiledConfig` (its variables as `env`, its folder as `cwd`, except Flutter's, which runs in its app's folder).
Found as configured (`dartDebugPath`, `flutterDebugPath`), else on the PATH; no Install (the SDK ships the adapter).
Settings → Debugging → Dart has the switch (`dartDebugEnabled`, which covers Flutter), both paths and the device;
Doctor has a Dart row and an optional Flutter one.

What the adapter does, probed with Dart 3.13.5 (Flutter 3.47.7): its exception filters are `All` ("All Exceptions",
off) and `Unhandled` ("Uncaught Exceptions", on) — `All` is the caught one, so `DebugLanguage.uncaughtFilter` answers
the profile's other filter when none is called `uncaught`. It offers conditional breakpoints, logpoints,
`configurationDone`, delayed stack loading, evaluate for hovers, `restartFrame`, `terminate`, value formatting and the
clipboard context, but no `restart` (Flutter's announces one later, a `capabilities` event: hot restart, unused).
`setBreakpoints` answers `verified: false, reason: "pending"` and a `breakpoint` `changed` event verifies it once the
isolate has loaded the library. The isolate starts paused: the adapter sends `stopped` with reason `entry` (in an SDK
frame, `_RawReceivePort._handleMessage`), sets the breakpoints, sends `continued` and resumes by itself; at the end a
`stopped` with reason `exit` comes before `exited`/`terminated`. Neither pause is shown (`DapManager.passesDartPause`).
And generally: a stop whose thread had a `continued` event before its frames came back is dropped (`resumesOf`:
counted as the events arrive, on the reader thread) — the `continued` for the entry stop arrives before the answer to
its `stackTrace`, which used to leave a running program shown paused in an SDK file. At a stop frame 0 is `main` at
the file's line; the scopes are Locals and Globals (`args`, `i`, `total`); `evaluate` works (context `watch`). Flutter on the Linux desktop
builds the app first (about ten seconds when the engine is cached), then the same sequence. Stop: Flutter's adapter
ends `flutter run` at once on a `disconnect`, before that has stopped the app, which then runs on as an orphan, so a
Flutter session's Stop sends `terminate` first and waits (up to 5 s) for its answer (`DapClient.setTerminateFirst`):
`flutter run` stops the app (`app.stop`), as VS Code's Stop does. A window's close stops its sessions the same way
(`DapManager.shutdown` → `stop` → `disposeInBackground`), so it ends the app too (probed).

**Debug Test at Caret for Dart.** In a `*_test.dart` file (`dap/DartTests.isTestFile`), `test.debugAtCaret` debugs the
`test(…)`, `testWidgets(…)` or `group(…)` whose call spans the caret's line, innermost first, named with its groups'
names joined by spaces as `dart test` names it (`DartTests.at`; a `)` in a string or comment does not end a call);
outside every call, the whole file. `DartLaunch.testPlan` sends it to the adapter's test mode (`--test` after its
command, `DartLaunch.TEST_MODE`: `dart debug_adapter --test`, or Flutter's for a Flutter app's test, which then runs on
`flutter test`'s own device: no `-d`) with `toolArgs: ["--name", "^<name>$"]`, or `"^<group>( |$)"` for a group: every
test in it (`DartTests.toolArgs`; the name's regex characters escaped). Probed: with breakpoints in two tests, the
caret's alone stops. The test runner's isolate and the test's each start paused at `entry` (two threads), both passed.
The test is found off the FX thread with the package (`DartDebugLaunch.debugTestAtCaret`).

**Run File for Dart.** A Dart file with a top-level `main` at the start of a line (`DartRun.runLine`: `main(`,
`void main(`, `Future<void> main(`) is runnable: the `dart` run configuration type is one of the `isCompiled` types, so
`CompiledRunLine`, `RunScan`, `RunConfigDefaults.typeFor` and the Run configurations form treat it as Go's.
`RunCoordinator.runDart` reads the pubspec off the FX thread, asks the folder's trust as Debug does, saves the
package's edited Dart files, and streams `dart run <file> args…` in the package root (else the file's folder), or for
a Flutter app's file `flutter run -d <device> -t <file relative to the root> --dart-entrypoint-args=<arg>…` in the
app's folder. It runs the `dart` and `flutter` the Debugging page names, else the PATH's. Rerun reads the package
again. Run File on a `_test.dart` file runs it as a program (`dart run`), which runs its tests.
`DartDebugWindowFxTest` (stand-ins for both adapters) covers the launch, the entry stop passed, the breakpoint's
shown, Flutter's device and Debug Test at Caret; `RunCoordinatorFlowsFxTest` covers both Run File command lines; the opt-in
`DartDebugProbeFxTest` drives the real adapters (its Flutter case needs Linux, `flutter` and a display, builds a
`flutter create --platforms=linux --no-pub` app and checks that Stop, and a window's close, end it; its test case needs
`package:test` in the pub cache).

`CsharpDebugProbeFxTest`, `RubyDebugProbeFxTest` and `PhpDebugProbeFxTest`, opt-in, run in CI with the pinned
adapters (`setup-dotnet`, the runner's Ruby with the debug gem, `php-xdebug`). `PresetDebugWindowFxTest` drives the
window against stand-ins for all three adapters and for `dotnet` (`DebugCoordinator.dotnetCommand`).

**launch.json.** *Debug: Start Configuration…* (`DebugCoordinator.startLaunchConfiguration`) lists the configurations
of the project's `.vscode/launch.json`, else the nearest one above the active file (`LaunchJson.find`). The one started
last comes first.

[`dap/LaunchJson`](../../src/main/java/com/editora/dap/LaunchJson.java) reads launch.json and tasks.json as VS Code
does:
- comments and trailing commas;
- whole numbers as `long`;
- each configuration's `linux`, `osx` and `windows` part laid over it (`forPlatform`);
- VS Code's variables (`substitute`): `${workspaceFolder}`, the `${file…}` family, `${relativeFile}`,
  `${lineNumber}`, `${selectedText}`, `${env:X}`, `${userHome}` and `${pathSeparator}`.

`${input:id}` asks a `pickString` (a picker) or a `promptString`, once per start, before anything runs.
`${command:…}` and `${config:…}` are VS Code's own commands and settings, so they're refused with a message.

A `preLaunchTask` runs as a before-launch step when its `tasks.json` task is a plain `shell` (`sh -c` / `cmd /c`) or
`process` command (`LaunchJson.taskArgv`). Any other task type, or one with `dependsOn`, is said rather than guessed.

A configuration's `serverReadyAction` and `postDebugTask` ride on its session as a `ui/LaunchFollowUp`, set just before
`startRoute` and counted from the session's next STARTING (so an end still to be reported for the launch it replaced
doesn't run the task). [`dap/ServerReadyAction`](../../src/main/java/com/editora/dap/ServerReadyAction.java) is VS
Code's matcher: its default pattern, `uriFormat`, ANSI stripped, lines joined across output events, one match per
launch. `DebugCoordinator.serverOutput` feeds it the session's output (not Editora's own command lines);
`openExternally` goes to `openExternalUrl`, `debugWithChrome`/`debugWithEdge` and `startDebugging` run
`resolveAndRun` in a session beside it (`sessionForLaunch`), remembered with its generation for `killOnServerStop`. At
the session's INACTIVE `launchEnded` stops that session and runs the `postDebugTask` as a before-launch step; Restart
takes the follow-up before its stop and runs the task, then the launch again.

[`dap/LaunchRoute`](../../src/main/java/com/editora/dap/LaunchRoute.java) sends each `type` to its debugger. Only
the fields VS Code reads itself are left out (`name`, its tasks, `console`, `presentation` …):

| `type` | Debugger | How |
| --- | --- | --- |
| `lldb` | CodeLLDB | as written, the Rust or C/C++ profile by `sourceLanguages`. Its `cargo` field runs `cargo <args>`, then the executable its JSON messages name, narrowed by `filter`, becomes `program` (`LaunchRoute.cargoExecutable`). |
| `gdb` | GDB's DAP mode | `program`, `args`, `cwd`, `env`, `stopAtBeginningOfMainSubprogram`; attach by `pid`. |
| `cppdbg` | GDB, or CodeLLDB with `MIMode: lldb` | its fields told in the target's own: the `environment` list becomes `env`, `stopAtEntry` the entry stop, `processId` the `pid`. |
| `go` | Delve | as written, `output` in the temporary folder when not given; `mode: remote` is not supported. |
| `debugpy`, `python` | debugpy | laid over the launch Editora plans for the program (`DapManager.startConfigured` → `overlay`, which keeps Editora's console); an attach connects to `connect`. |
| `node`, `pwa-node` | vscode-js-debug | as Python; an attach to `address` / `port`. |
| `chrome`, `pwa-chrome`, `msedge`, `pwa-msedge` | vscode-js-debug, driving the browser | as written with js-debug's type (`BrowserLaunch.configured`): `webRoot` defaults to the workspace folder, a launch's `userDataDir` to Editora's own profile and its `timeout` to 30 s; a launch needs a `url` or a `file`; an attach goes to `port` (9222 by default). |
| `coreclr` | netcoredbg | as written (`program` is the built assembly; a `preLaunchTask` builds it); an attach by `processId`. C# Dev Kit's `dotnet` type is not supported. |
| `rdbg` | rdbg | a launch runs `rdbg … -- <command> <script> <args>` (`LaunchRoute.rdbgTail`: `command` defaults to `ruby`, `useBundler` puts `bundle exec` first) in its `cwd` with its `env`; an attach connects to a local `debugPort` (`rdbgPort`; `DapManager.startPortAttach`). |
| `php` | vscode-php-debug | as written; without a `program` it listens for Xdebug ("Listen for Xdebug"). `${port}` is left for the adapter. |
| `bashdb` | vscode-bash-debug | as written (a launch; the programs it names are filled in where it names none). No attach. |
| `java` | java-debug | a transient run configuration (`LaunchRoute.javaRunConfiguration`: `mainClass`, `projectName`, quoted `args` and `vmArgs`, `cwd`, `env`); a `mainClass` that is a `.java` file debugs that file. |
| a user's adapter | it | as written. |

Anything else is said (`status.debug.launchUnsupported`).

**GDB.** `DebugLanguages.GDB` is GDB's own DAP mode: `gdb -q -i dap`, GDB 14 or later, over stdio. The program's
output comes as output events. It isn't among `all()`, since it's not a language. It's C and C++'s other debugger:
`Settings.cppDebugger` set to `gdb`, with `gdbPath`. F5's native build then launches with `NativeBuild.gdbLaunch`, and
`debugEffectiveFor("cpp")` checks GDB is found.

**The user's own adapters.** [`dap/CustomAdapters`](../../src/main/java/com/editora/dap/CustomAdapters.java) reads
`debug-adapters.json`: the config folder's, then a trusted project's `.editora/debug-adapters.json`, whose adapters
replace those of the same type (`merge`). *Debug: Edit Debug Adapters* opens the user's, written with an example the
first time.

Each entry has:
- a `type`, which may not be one of Editora's own types;
- a `command`;
- a `transport`: `stdio`, `port` (`{port}` anywhere in an argument) or `announce` (an `announce` pattern whose group
  is the port);
- `languages`;
- an optional `name` and `adapterID`;
- a `launch` template for F5.

Each becomes a `DebugLanguage` profile on the external-adapter path (`DebugLanguages.setCustom`), found by id and by
language after the built-in ones. Its program is looked for by `configureExternal`, and one that can't be read is said
in the status bar. `debugEffectiveFor` looks a profile up by adapter id when no language names it.

`LaunchJsonProbeFxTest` drives the real debuggers:
- CodeLLDB with a `preLaunchTask` that compiles the program;
- `gdb` and `cppdbg` on GDB;
- a user's adapter (GDB again);
- F5 on GDB;
- Delve's `exec` mode on a built binary;
- CodeLLDB's `cargo` field;
- debugpy and js-debug configurations with their `args` and `env`.

`LaunchJsonWindowFxTest` drives the picker, a `pickString` input, a task, F5's `launch` template, and each message,
against stand-in adapters.

**Disassembly and Memory views.** The Debug window's third area holds three views, chosen by toggles in its header:
Console, Disassembly and Memory (`DebugPanel.showArea`). They work with adapters that advertise them, which are
CodeLLDB and Delve:
- `supportsDisassembleRequest`;
- `supportsReadMemoryRequest`;
- `supportsSteppingGranularity`.

`initialize` declares `supportsMemoryReferences`, and each stack frame keeps DAP's `instructionPointerReference`
(`StackFrameInfo.instructionPointer`).

[`ui/DebugDisassemblyView`](../../src/main/java/com/editora/ui/DebugDisassemblyView.java) shows the selected frame's
instructions. They come from `DapManager.disassemble`: 40 before the instruction pointer and 120 in all, with
`resolveSymbols`.
- The instruction the frame is at is marked and selected. Addresses are compared as numbers (`parseAddress`).
- Each run of instructions is labelled with the source line it came from.
- Double-clicking a row opens that line.
- While the list has the keyboard, Step Over and Step Into step one instruction (`granularity: instruction`), as
  VS Code's Disassembly view does.
- Debug: Step Over Instruction and Step Into Instruction do it from anywhere. Debug: Show Disassembly opens the view.
- It's filled again at every stop and frame change (`DebugCoordinator.refreshLowLevelViews`).
- GDB (16) answers a negative `instructionOffset` with the end of a listing forward from the address, so for a GDB
  session `DapManager.disassembleBackOnGdb` asks from 160 bytes before the instruction pointer instead. A start
  byte inside an x86 instruction decodes out of step until the listing falls into step; a listing with no
  instruction at the pointer (`aroundAddress` null) is asked again a byte further back, up to 8 times, then from
  the pointer on.

**Instruction breakpoints.** Where the adapter has `supportsInstructionBreakpoints` (CodeLLDB, GDB 14 and later,
Delve), a breakpoint can go on one instruction of the Disassembly view, as in VS Code's Disassembly view and
IntelliJ's ([`ui/InstructionBreakpoints`](../../src/main/java/com/editora/ui/InstructionBreakpoints.java)).
- It goes on and off by a click in the margin before the address, Toggle Breakpoint (F9) while the list has the
  keyboard (`toggleBreakpointAtCaret` hands it to the view), or the row's right-click menu. A red dot marks it.
- Each toggle sends the session's whole set with `setInstructionBreakpoints`, each at its address with offset 0
  (`DapClient.setInstructionBreakpoints`). The adapter's answer marks one it could not set hollow, and the status
  line gives its message.
- They belong to the session (the `DapClient` the request goes to), not to the project: an address is that
  process's, so they aren't saved and the next run starts with none. A child session has its own.
- A stop on one shows the Disassembly view at it. DAP's reason for it is `instruction breakpoint`, but CodeLLDB
  and GDB say `breakpoint`, so a `breakpoint` stop whose top frame is at one of the set's addresses counts too
  (`atInstructionBreakpoint`).
- A condition and a hit count: the row's Edit Breakpoint… (`debug.editBreakpoint`, enabled on a row with one) asks
  for the condition, then the hit count — each only where the adapter says it takes it
  (`supportsConditionalBreakpoints`, `supportsHitConditionalBreakpoints`; neither: the status line says so) — and
  sends them in the breakpoint's `condition`/`hitCondition` (`DapClient.setInstructionBreakpoints(addresses,
  conditions)`, kept per address in `instructionConditions`; an address taken off loses its own). Its dot then takes
  the conditional colour.
- The Breakpoints list (`BreakpointsPicker`) lists the shown session's instruction breakpoints after the rest
  (`Entry.instruction`), with what each asks and "this run only": Enter opens the Disassembly view at it, Alt+D
  takes it off, and Alt+E says it has no off state (DAP has none: remove it). Not in groups, export or triggers,
  which are the project's and an address is not.

[`ui/DebugMemoryView`](../../src/main/java/com/editora/ui/DebugMemoryView.java) shows the program's memory as rows
of 16 bytes: address, hex and text (`rows`). It reads a 256-byte page at a time (`DapManager.readMemory`), with ◀ ▶
to move between pages.
- Its field takes an address (`0x…`), or an expression whose value's memory reference comes from `evaluate`
  (`memoryReferenceOf`).
- A variable's context menu has View Memory, which opens the view at the variable's path expression.
- An expression whose value has no memory reference is asked again as `&(expression)` (`DapManager.addressOf`):
  GDB gives a C array none, only a pointer.
- An address that can't be read is said, as is an expression with no address, or an adapter that doesn't read
  memory.
- **Edit Bytes…** (`debug.memoryEditBytes`: a double-click, F2 or the row's menu) changes a row's bytes where the
  adapter has `supportsWriteMemoryRequest` (CodeLLDB, GDB and Delve; probed 2026-11-22). The work is in
  [`ui/MemoryEdits`](../../src/main/java/com/editora/ui/MemoryEdits.java), out of `DebugCoordinator`. The prompt
  starts from the row's bytes in hex and takes hex (`48 65`, `4865`, `0x48`) or `"quoted text"` (UTF-8)
  (`DebugMemoryView.parseBytes`); typing past the row is allowed up to the page's readable end. Only the span from
  the first changed byte to the last is written (`DebugMemoryView.edit`), at the page's reference + offset as
  `readMemory` reads it. Then the page and the frame's scopes are read again, and a `bytesWritten` short of the
  count is said. A step, another page or another address while the prompt is open writes nothing
  (`status.debug.memoryMovedOn`). `MemoryWriteProbeFxTest` drives CodeLLDB and GDB; Delve's is unprobed (no Go
  toolchain on the dev machine).

`DisassemblyMemoryProbeFxTest` drives the real CodeLLDB; `GoDebugProbeFxTest` checks Delve's answers too.
`InstructionBreakpointProbeFxTest` stops at an instruction breakpoint with a condition (`i == 3`) on CodeLLDB, GDB and
Delve, through the window. `LowLevelViewsFxTest` drives both views and instruction breakpoints against `FakeDebugAdapter.lowLevel`.

`GoDebugProbeFxTest`, opt-in, runs in CI's integration job with Go and a pinned Delve. It drives the real Delve
through `DapManager`: variables, Step Over, goroutines, a test at the caret, and an attach then detach. It also
drives it through a window: F5 and Debug Test at Caret.

[`dap/DapClient`](../../src/main/java/com/editora/dap/DapClient.java) (mirrors `LanguageServerSession`)
runs the handshake over an lsp4j.debug `DSPLauncher`: `initialize` → on the `initialized` event send
`setBreakpoints`/`setExceptionBreakpoints`, then `configurationDone` once they have been answered (at most
`CONFIGURATION_WAIT`, 5 s), chained on their futures **without** `.join()` — that callback runs on the reader
thread, so blocking would deadlock on a response delivered by the same thread. Waiting for the answers is what VS
Code does: vscode-js-debug handles requests concurrently, and a `configurationDone` sent straight after let the
program run before a column breakpoint (which js-debug places with more work) was in place, about one run in three. Events arrive on the launcher thread; the `Host`
(`DapManager`) marshals them to FX. `dispose()` disconnects, closes the socket, and
`ProcessRegistry.killTree`s the adapter subprocess tree (same wrapper-orphan reasoning as LSP). The
standalone adapters' stderr is drained into `AdapterStderr` (an undrained PIPE deadlocks, like the LSP
servers; DAP traffic is on stdin/stdout or the socket) and its last lines are added to a start failure's
message. An adapter that dies while the session starts (state STARTING, or a `launch`/`attach` it never answered:
`gdb -i dap` before GDB 14, a crashing CodeLLDB or debugpy) is reported by `DapManager.onTransportClosed` with that
tail, for every adapter — the connect or launch failure that would say so arrives after the session has ended, and
is dropped. The tail is kept per session (`SessionStderr`), so a session with no drained adapter (java-debug in
jdtls, a remote js-debug) never shows the last one's.

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

**Jest and Vitest.** A JS/TS test file (`JsLaunch.isTestFile`: `.jsx`/`.tsx` too) whose package tests with Jest or
Vitest ([`run/JsTestRunner`](../../src/main/java/com/editora/run/JsTestRunner.java): Vitest when the package depends on
`vitest` or has a `vitest.config.*`, else Jest when it depends on `jest`/`jest-cli`, has a `jest` section or a
`jest.config.*`) and has that runner installed (its entry script, `node_modules/jest/bin/jest.js` or
`node_modules/vitest/vitest.mjs`, at or above the package, not above the project: a workspace hoists it) is planned by
`NodeRunLauncher.runnerReady` instead of `node --test`, on the Node.js Run would pick: Jest as
`jest.js --runInBand [-t <pattern>] <file>` (one process, so the tests run where js-debug is attached), Vitest as
`vitest.mjs run <file> [-t <pattern>]` (its fork workers are child processes js-debug attaches to by itself: a child
session's own `startDebugging`). The plan's target is the runner's script, so js-debug launches `program` = that script
(VS Code's recipes). Run File on such a test file streams the runner (`RunCoordinator.launchNode`'s off-FX check goes
past `node --test`'s Test Results). Debug Test at Caret's pattern is the test's full name
([`run/JsTestNames`](../../src/main/java/com/editora/run/JsTestNames.java): the innermost `test`/`it`/`describe`
around the caret with its `describe`s; a `.each` row or a computed name runs the whole file), each name literal, joined
as the runner joins them — Jest with a space, Vitest 3+ with ` > ` (probed: Vitest 5.1.1 does not match a space), so
Vitest's pattern takes either — whole for a test, prefix for a suite. It rides in the Node arguments as
`JsTestRunner.NAMES_ARG`, dropped with Deno's by `DenoRun.withoutFilter` (every `--editora-` word). The `.jsx`/`.tsx`
refusal (`JsLaunch.launchProblemKey`) now waits for the plan: only plain Node running the file itself is refused
(`NodeDebugConfigs.nodeRuns`), so Jest, Vitest and Deno debug them. `JsTestRunnerProbeFxTest` installs each runner with
npm and debugs the caret's test alone.

**Electron.** [`run/ElectronApps`](../../src/main/java/com/editora/run/ElectronApps.java) (pure) finds the app a
main-process file starts: the nearest `package.json` names `electron` (dependencies, devDependencies or optional), the
file is its `main` (else `index.js`) or loads `electron` (`require`, `import … from`, `import()`), and Electron is
installed (`node_modules/electron/path.txt`, in the package's `node_modules` or one above, names the program under
`dist/`). Electron 45 downloads that program on its first start, not on `npm install`: an app whose package is installed
(`node_modules/electron/package.json`) but whose `path.txt` is not written yet is still the app, with no program, and
F5 and Run File say to run `npx install-electron` (`status.run.electronNotDownloaded`) rather than run Electron's
installer themselves. A page's script, a build script, a test file and a file under `node_modules` are not the app's start. F5
([`ui/ElectronDebugLaunch`](../../src/main/java/com/editora/ui/ElectronDebugLaunch.java), looked for off the FX
thread first in `debugJsFile`; a Node test run is never Electron) builds VS Code's Electron recipe in memory — "Electron:
Main", a `pwa-node` launch with Electron's program as `runtimeExecutable` and `--remote-debugging-port=<free port> .`
(then the file's program arguments) as `runtimeArgs`, `outputCapture: std`; "Electron: Renderer", a `pwa-chrome`
attach to that port with the app's folder as `webRoot` and a 30 s `timeout` — and runs them as a compound with
`stopAll` through `runLaunchCompound`, so each is a session of its own and Debug ▸ Stop ends both and the app. js-debug
treats `electron` as a Node runtime for the main process; Chromium switches (`--ozone-platform=headless --no-sandbox`
in the probe) are read anywhere on the command line. Run File (`RunCoordinator.planNode`) runs `electron .` in the
app's folder. `ElectronDebugProbeFxTest` (opt-in) drives a headless Electron 45: a stop in the main process, then in
the window's script, Stop leaving no Electron, and Run File.

**Deno.** A JS/TS file of a Deno project — [`run/DenoRun`](../../src/main/java/com/editora/run/DenoRun.java):
the nearest folder above it with a `deno.json(c)`, before any with a `package.json`, inside the project (a loose
file: up to eight folders, not past home) — is planned by `NodeRunLauncher.denoReady` instead of `NodeRunPlan`,
unless its shebang or a configuration's Interpreter names a runner: `deno run --allow-all <file> <args>` (a test
file, `{*_,*.,}test.{ts,tsx,mts,js,mjs,jsx}`, `deno test --allow-all <file>`), from that folder, `FORCE_COLOR=1`
as for Node. `--allow-all` because the Run console gives Deno no terminal to ask in (VS Code's Deno launch does the
same). The executable is `deno` on PATH, else `$DENO_INSTALL/bin` or `~/.deno/bin` (its installer leaves PATH
alone). Debug reuses that plan: `NodeDebugConfigs.debuggableRuntime` takes Deno, and `DapManager.startSocket`
rewrites the runtime with `DenoRun.inspected` (`--inspect-wait=127.0.0.1:<free port>` after the subcommand, `run`
added for a bare `#!/usr/bin/env deno`) and sets `attachSimplePort` plus a 60 s `timeout` — NODE_OPTIONS reaches
Node alone, so js-debug attaches to Deno's inspector instead (probed: Deno 2.9.8 maps `.ts` breakpoints itself; its
own `core/…`, `deno_web/…` files are sourceReference sources). Debug Test at Caret narrows `deno test` with
`--filter /^<name>$/`, the **outermost** test around the caret
([`run/DenoTests`](../../src/main/java/com/editora/run/DenoTests.java): `Deno.test("…")`, `{ name: "…" }`,
`function named()`, bdd `describe`/`it`), since Deno's filter matches top-level tests alone (a `t.step` or nested
`it` runs inside its test). That name rides in the Node arguments as `DenoRun.FILTER_ARG`, which every Node route
drops (`withoutFilter`), because which runtime runs is known only once the planner has looked at the disk. Bun stays
out: it speaks WebKit's inspector protocol. `DenoDebugProbeFxTest` drives the real Deno and js-debug.

**Autos.** At a stop the Variables view gets an **Autos** node, last and closed, as Visual Studio's Autos window
shows: the variables and member chains of the stop's line and the nearest line with code before it
([`dap/AutoExpressions`](../../src/main/java/com/editora/dap/AutoExpressions.java), pure: strings and the line comment
of the language skipped; keywords, a call's own name (its receiver stays), a declaration's type, Python keyword
arguments and annotations left out; in their order on the lines; at most 10). `DebugPanel.loadAutos` reads the shown
frame's two lines off the FX thread (PERF-01; a file over 4 MiB, code with no local file, a host's file: none) and
adds the node; only once it is open does it evaluate each through the Watches' own path (`Actions.evaluateWatch`),
keeping those the adapter answers — a type, a module or a name not in scope fails and is left out. Open, it stays open
from stop to stop (remembered as a scope's expansion is). Closed and last on purpose: each expression is an evaluate
request, and a first version that opened after Watches at every stop sent them to every adapter at every stop and moved
the rows other features find by position. Rows are `Kind.AUTO`: values for copy, hover, View as and
Set Value, but not watches (no edit, no Delete). The node is added only while its tree is still shown, and never on an
earlier stop of Debug History. Adapter-agnostic: every debugger that evaluates watches gets it. `DebugAutosFxTest`
covers the node, its order, a refused expression and the next stop's lines.

**Return values after a step.** A step that leaves a function shows what it returned, first in the caller's Locals
as `(return) name`, where the adapter can say it. debugpy does with `showReturnValue: true`, which Editora sends on every
launch and attach (`PythonLaunch.request`, `LaunchConfig.pythonAttach`) and adds to a launch.json debugpy configuration
that does not set it (`LaunchRoute`), as VS Code's Python extension defaults it (debugpy's own default is off).
Editora's JVM adapter (Kotlin, Groovy, Clojure, scripts) does it itself: a Step Over or Step Out puts a
`MethodExitRequest` on the stepping thread, filtered to the stepped-from method's class, and keeps the value of that
method's own exit at the depth it started at (`ReturnWatch`; a recursive call exits deeper and is not it; a `void`
method or constructor has none); the top frame's Locals list it first as `(return) name()` until the next run. Only
the stepped-from method is watched — IntelliJ's "show method return values" for every call on a line slows each step,
and is off by default there. js-debug shows a "Return value" of its own. java-debug captures method exits only for
its stepping and never shows the value, so Java through jdtls has none; CodeLLDB, GDB and Delve are not covered.
`JvmDebugAdapterTest.aStepThatLeavesAMethodShowsWhatItReturned` drives a recursive method on a real JVM;
`ReturnValueProbeFxTest` (opt-in) steps out of a Python function in a window.

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

**Debugging in a browser** ([`dap/BrowserLaunch`](../../src/main/java/com/editora/dap/BrowserLaunch.java)). The same
vscode-js-debug debugs a web page in Chrome, Edge or another Chromium browser (Chromium, Brave, Vivaldi: the
browsers that speak the DevTools protocol; Safari does not; Firefox runs on its own adapter, below).
`DapManager.startBrowser` starts js-debug as
for Node.js (`startJsRequest`, shared with `startJsAttach`) and sends it a `pwa-chrome` or `pwa-msedge` `launch` or
`attach`; each page is a child session, as a Node.js worker is. A launch names the browser's own program
(`Browsers.executable`: the binary on PATH, the `.app`'s `Contents/MacOS` program, the Windows `.exe`; `stable`, which
js-debug resolves itself, when none is found, as for a Flatpak) and a profile of Editora's own under
`<config>/browser-debug/<browser>`, kept between sessions and apart from the everyday profile a running browser holds
locked. Stop closes a launched browser (`terminateDebuggee`) and leaves an attached one open.

A launch gives the browser `BrowserLaunch.LAUNCH_TIMEOUT_MILLIS` (30 s, as long as Editora waits for any launch to
be answered) to start and be ready, as js-debug's `timeout`: its own 10 s are too few for a browser's first start on
a machine. js-debug 1.117 does not answer a launch it gives up on (the browser exited, or was not ready in time): it
writes the error to its standard error and closes the connection. A session is RUNNING from the moment the adapter
is connected, so `DapManager` keeps the launch it has not been answered (`UnansweredLaunch`), and a connection that
closes before the answer is reported (`debug.error.browserNotReady`, with the adapter's stderr in the Debug console)
instead of ending as a session whose program ran and finished, with nothing said. This is what made the first browser
launch of a CI job fail now and then with no message; `BrowserDebugProbeFxTest` starts the browser once before its
tests and prints how long that took.

**Firefox** ([`dap/FirefoxLaunch`](../../src/main/java/com/editora/dap/FirefoxLaunch.java)) is debugged on
vscode-firefox-debug (firefox-devtools, MIT; `dist/adapter.bundle.js` on Node.js over stdio), the profile
`DebugLanguages.FIREFOX`: not in `all()` (a browser, not a language), with the JavaScript profile's editor languages so a
page's files' breakpoints go to it, found as `InstalledAdapter.FIREFOX_DEBUG` (`Settings.firefoxDebugPath`, schema
183; Editora's install `plugins/dap/firefox`, `InstallCatalog.FIREFOX_DEBUG_STEP`, the 2.15.0 `.vsix` from Open VSX;
VS Code's `firefox-devtools.vscode-firefox-debug-*`). It is in `BrowserLaunch.BROWSER_IDS` last: `pick` takes it when
Settings names it or it is the only browser found, never because the HTML preview shows pages in it (its debugger is a
separate install). Its requests differ from js-debug's: `file` for a `file:` page (the adapter maps those itself),
`url` and `webRoot` otherwise — the adapter refuses a `webRoot` without a `url` — `pathMappings` of whole URLs
(`http://127.0.0.1:<port>/<token>/` → the site), `firefoxExecutable`, and Editora's profile as `profileDir` with
`keepProfileChanges` (made on first use, kept), a free `port` for Firefox's debugger server (the adapter's own 6000 can be
held by another Firefox under a debugger, which the adapter then connects to instead: its pages never load the files
asked for), `timeout` 30 s for the adapter to connect (its own 5 s were too few for a CI runner's first start, and the
failed launch left Firefox running on the port) and `reAttach: false`. Its exception filters are the adapter's: `all`, `uncaught`, and
`debugger` statements, on by default as `debugger;` stops in Chrome. Attach to Browser attaches to Firefox's debugger
server (`-start-debugger-server`, port 6000) when Firefox is the chosen browser; a launch.json `firefox` configuration
is `LaunchRoute.Kind.FIREFOX` (`FirefoxLaunch.configured`). Probed with Firefox 160: a stop by `file:` and through a
served site, the profile kept, no Firefox left after Stop; `FirefoxDebugProbeFxTest` drives F5, launch.json and attach.

The entry points are [`ui/BrowserDebugLaunch`](../../src/main/java/com/editora/ui/BrowserDebugLaunch.java), a part of
`DebugCoordinator`:
- *Debug: Debug in Browser…* (`debug.browser`): a picker of the active HTML file, the URLs debugged before in this
  project (`WorkspaceState.debugBrowserUrls`, schema 36, most recent first) and *Another page…*, which asks for a URL
  (`BrowserLaunch.url` adds `http://` to `localhost:5173`); a URL maps to files through `webRoot`, the project.
- F5 on an HTML file (`launchBuffer` → `debugHtmlPage`): the page is served as the live preview serves it
  (`HtmlPreviewCoordinator.serveForDebug` → `HtmlPreviewService.serve`, no browser opened), so modules and fetches work
  and edits reload it; the served URL `http://127.0.0.1:<port>/<token>/…` maps back through `pathMapping`
  `{"/<token>/": site}` with `webRoot` the site folder (`BrowserLaunch.Served`). With the preview off it opens the
  file's `file:` URL. HTML is one of the JavaScript profile's editor languages, so an HTML tab takes breakpoints (its
  inline scripts), and Debug ▸ Attach on one goes to the browser.
- *Debug: Attach to Browser…* (`debug.attachBrowser`): a browser started with `--remote-debugging-port`, 9222 by default.
- launch.json `chrome` / `msedge` configurations (`LaunchRoute.Kind.BROWSER`).

The browser is the Debugging setting `debugBrowser` (settings schema 168; "" = automatic): `BrowserLaunch.pick` takes
it when found, else the HTML preview's browser when it is a Chromium one, else the first of Chrome, Edge, Chromium,
Brave, Vivaldi found; a chosen browser that is gone falls back and the status line says so. `BrowserLaunchTest`,
`BrowsersTest`, `LaunchRouteTest` and `BrowserDebugWindowFxTest` (a stand-in js-debug relaying to `FakeDebugAdapter`)
cover it; the opt-in `BrowserDebugProbeFxTest` drives the real js-debug and a headless Chrome: F5 on an HTML file
stops in its inline script and in a script it loads, and a launch.json `chrome` configuration opens a `file:` URL.

### DapManager & the java-debug bundle

[`dap/DapManager`](../../src/main/java/com/editora/dap/DapManager.java) is the UI facade (mirrors
`LspManager`+`RunService`). One manager debugs one session at a time and dispatches
`startLaunch(file, language, picker)`. What a window's sessions share — the Debugging settings, where detection
found each adapter, the exception filters chosen per language, the breakpoints supplier, member breakpoints, hex
values — lives in [`dap/DapSettings`](../../src/main/java/com/editora/dap/DapSettings.java), not in the manager:
`newSession()` makes another manager for the same window on the same `DapSettings`, so a setting changed through
any of them reaches the next session of every one. Launch settings (program and JVM arguments, environment, the
Python plan, the JavaScript program) and the listener stay the manager's own. Adapter `output` events reach the console through
[`dap/DapOutputPump`](../../src/main/java/com/editora/dap/DapOutputPump.java), which mirrors the Run
console's pump: a bounded queue, one scheduled drain at a time, a bounded slice per drain with neighbouring
events joined into one append, and a "truncated" notice when the debuggee outruns the UI; the queue is
flushed before a session ends so its last lines are not lost. A program java-debug launched itself (the
program console off) has output that trails its `exited`/`terminated` events by seconds when it prints fast,
so the end of such a session and its exit line wait until the output has been quiet for 300 ms, at most 30 s
([`dap/OutputTail`](../../src/main/java/com/editora/dap/OutputTail.java), review E104). The Debug console
keeps its last 200,000 characters and its state line says when older output was dropped (E107). Step Over/Into/Out put the state back to
RUNNING until the next stop, exactly like Resume — but only once the adapter *acknowledges* the step: a
refused step (java-debug: "the thread is not suspended") leaves the session SUSPENDED and reports the
adapter's message. `isStepping()` is true from the request to the next stop; `debug.start` treats that as
the paused session it is (Continue), never as a running one to retarget. The manager also tracks *which*
threads are stopped (`stopped.allThreadsStopped`, `continue`'s `allThreadsContinued`, `continued`
events): java-debug suspends and resumes per thread, so after Continue on one of several stopped threads
the next still-stopped thread is brought forward instead of reporting a running session. Pause in a Java session
sends java-debug's own `pauseAll` (`DapClient.pauseAll`), which suspends the whole program and reports the stop with
`allThreadsStopped`; until the program resumes, Continue then sends `continueAll` (`DapManager.allSuspended`), because
a plain `continue` naming one thread would leave every other thread suspended — steps in between resume only the
stepped thread. The "Suspend all threads when one stops" setting is java-debug's `suspendAllThreads`
(`JavaDebugSettings`, read when a session starts): breakpoints suspend with `SUSPEND_ALL`, and java-debug's own
`continue` then resumes the whole program. *Force Run to Cursor* (`DapManager.forceRunToCursor`) sends every
other file an empty breakpoint set and the caret's file only the cursor line, then resumes; `clearTempBreakpoint`
sends the real sets again (`forceRunMuted`) when the trip ends. A Run to Cursor trip ends at a stop on the cursor
line, at a stop elsewhere the program is not resumed from (a step, a new Run to Cursor, a stop reached by anything but
a resume), at a stop the window shows the user (`DapManager.stopShown`, as VS Code and IntelliJ end it), and with
the session (`DapManager.settleTempBreakpoint`): the window resumes some stops without showing them (a logpoint it
writes, a recording breakpoint, filters or a hit count that did not match, Thread Dump's pause), and clearing the
temporary breakpoint at every stop left Run to Cursor never arriving. *Show Numbers as Hexadecimal*
(`DapManager.setHexValues`) puts `format: {hex: true}` on every `variables` and `evaluate` request of the session and
the next ones (java-debug, debugpy and GDB honour it; js-debug, CodeLLDB and Delve ignore it), and forgets the stop's cached answers. The opt-in `DebugQuickWinsProbeFxTest` checks this against the
installed adapters in a real window (a worker thread's output stops while paused), along with temporary breakpoints
and changed-value marks: `./mvnw test -Dtest=DebugQuickWinsProbeFxTest -Dgroups=probe -Dlsp.probe=true`.
**Show Value As** (IntelliJ's and CLion's *View as*, Visual Studio's format specifiers; the Variables view's
right-click *Show Value As ▸ As the Debugger Shows It / Decimal / Hexadecimal / Binary / Octal / Character*, and the
row commands `debug.showSelectedAs{Default,Decimal,Hex,Binary,Octal,Character}` in `DebugRowCommands`) shows one whole
number in a radix of its own, where *Show Numbers as Hexadecimal* is all or nothing (and is honoured by java-debug,
debugpy and GDB only: CodeLLDB, Delve and js-debug ignore `format.hex`). It is Editora's: the debugger's text is read
and written again by [`dap/NumberViews`](../../src/main/java/com/editora/dap/NumberViews.java) (pure), so it works the
same with every adapter. Each writes a number its own way, and `ValueFormatsProbeFxTest` saw each: a plain number
(js-debug's BigInt `42n`), hexadecimal from the global switch (debugpy `-0x2a`, java-debug `0xffffffffffffffd6` for an
`int`, GDB `0xffffffd6`), Delve's `255 = 0xff`, GDB's character `65 'A'`, CodeLLDB's `'A'` and `'\xff'`, java-debug's
bare `A` for a `char`. The type, in the session's language, gives the width and sign (`NumberViews.width`: a JVM
`char` is 16 bits unsigned, Go's `byte` a `uint8`, Rust's `char` 32 bits, C's `char` 8 bits signed; Python's `int` and
JavaScript's `number` have none), so a negative number is its two's complement in hexadecimal, binary and octal, and a
hexadecimal value is read back in that width. GDB and Delve name no type: there a negative number keeps its sign
(`-0x2a`) and GDB's hexadecimal reads as unsigned. Booleans, floats, strings and objects are not numbers; the submenu
is grayed for them, saying why (KBD-04), and offered on an array or object, whose numbers it then shows that way until
one has its own. The choice is kept by the session's language and the value's expression (`DebugPanel.formatKey`: the
adapter's `evaluateName`, the path of names, a watch's text) in the window's map (`DebugCoordinator.valueFormats`,
every session's panel; not saved), so it holds at the next stop and in the next session; the cell draws
`DebugPanel.shown`, and Copy Value copies what is shown. `ShowValueAsFxTest` (a scripted panel) covers the menu, an
array's elements, a watch, Copy Value, the grayed row and the commands; `ValueFormatsProbeFxTest` runs the commands
in a window on java-debug, debugpy, Delve, GDB, CodeLLDB and js-debug.
**Go's goroutines in the Threads view** (GoLand's and VS Code's goroutine options) are Delve's own: launch and
attach arguments that are also settings of a running session (its console's `dlv config`). Settings → Debugging → *Go
goroutines* keeps *Hide runtime goroutines* (`debugGoHideRuntimeGoroutines`, on: Delve's `hideSystemGoroutines`, which
leaves out the runtime's garbage-collector workers, finalizer and scavenger), *pprof labels in names*
(`debugGoPprofLabels`, `*`: `showPprofLabels`, so a goroutine started under `pprof.Do` reads `[Go 20 job:resize-0]
main.worker`; one key shows its value alone) and *Goroutine filter* (`debugGoGoroutineFilter`, "": `goroutineFilters`,
the filter of Delve's `goroutines` command, `-with label job=*`, `-without go runtime.*`, `-with user`); schema 175.
[`dap/GoGoroutines`](../../src/main/java/com/editora/dap/GoGoroutines.java) (pure) adds them to a `type: go` launch or
attach request (`decorate`, chained after the evaluation time limit in `publishClient`'s decorator; a launch.json's own
are kept) and turns them into the console commands a running session is sent (`liveCommands`: `dlv config
hideSystemGoroutines …`, `goroutineFilters …` — empty clears it — and the labels cleared then added). `DapManager
.setGoGoroutines` sends them, one after another, only when a Go session's options changed (`liveGoGoroutines`), and
[`ui/GoroutineCommands`](../../src/main/java/com/editora/ui/GoroutineCommands.java) lists the threads again after.
The Threads view's right-click menu, in a Go session, has *Hide Runtime Goroutines* (`debug.toggleRuntimeGoroutines`)
and *Filter Goroutines…* (`debug.filterGoroutines`, an overlay prompt); both save the choice in Settings and tell every
running Go session at once. A filter can leave out the goroutine the program stopped on. Delve 1.27.2 probed:
`GoroutinesWindowFxTest` (a stand-in `dlv` relaying to `FakeDebugAdapter`) checks the launch arguments, the console
commands and that nothing is sent again when nothing changed; `GoGoroutinesProbeFxTest` stops a program with three
labelled workers and checks the list Delve gives with the defaults, with runtime goroutines shown, and filtered.
**Breakpoints in Django and Jinja templates** (PyCharm's template debugging, VS Code's `"django"` and `"jinja"`) are
debugpy's: its `django` and `jinja` launch and attach switches. A breakpoint in a template file already reaches
debugpy (every file's breakpoints are sent), but without the switches a Django template's is never hit and a Jinja
one stops at the wrong place — the line of that number in the Python Jinja compiles the template to, with that
module's variables. With them, the call stack's top frame is the template (`Django Template`, `template`) on the
breakpoint's line, and Variables shows its context (`name`, `items`, a loop's `i` and `forloop`).
[`dap/PythonTemplates`](../../src/main/java/com/editora/dap/PythonTemplates.java) (pure) puts them in a `type: python`
or `debugpy` request, last in `publishClient`'s launch decorator, as Settings → Debugging → Python → *Template
breakpoints* (`debugPythonTemplates`, schema 176) says: *Automatic* (default) looks at the launch's folder (its `cwd`,
else its program's) and up to four above it (`detect`, reading through `DapManager.templatesIn`): a `manage.py`, or
Django named in `requirements*.txt`, `pyproject.toml`, `Pipfile`, `setup.cfg`, `setup.py` or `environment.yml`, turns
`django` on; Jinja2, Flask or Quart named there turns `jinja` on. *Django and Jinja* turns both on, *Off* neither; a
launch.json's own values are kept, and a remote folder (not on this machine) finds nothing. `PythonTemplatesTest`
covers detection and the switches; `PythonTemplatesWindowFxTest` sends a launch through the window's manager to
`FakeDebugAdapter` with Automatic and Off; `PythonTemplatesProbeFxTest` stops on a Jinja and a Django template's loop
line in a real program (CI installs both into `~/.cache/editora/templates-venv`).
**Method breakpoints and field watchpoints** ([`config/MemberBreakpoint`](../../src/main/java/com/editora/config/MemberBreakpoint.java),
[`ui/MemberBreakpointCommands`](../../src/main/java/com/editora/ui/MemberBreakpointCommands.java)) are java-debug's
DAP function and data breakpoints, both named `pkg.Class#member` (the binary class name, `Outer$Inner`). A method
breakpoint stops on entering every overload of that name in that exact class (java-debug matches the method entry's
declaring type, not subclasses), before its body runs; a field watchpoint stops on a write (or read) of that field in
any instance. They are kept per project in `breakpoints.json` (`memberBreakpoints`, schema 4) beside the line
breakpoints, listed after them in Debug: Breakpoints (Alt+E / Alt+D work; Enter jumps through the Java server's
workspace symbols), and handed to every session: `DapManager.setMemberBreakpoints` keeps the enabled ones (none while
breakpoints are muted), `publishClient` gives them to a new `DapClient`, whose `initialized` sends
`setFunctionBreakpoints`/`setDataBreakpoints` only when the adapter's capabilities offer them, and a change after
that is sent at once. *Toggle Method Breakpoint* / *Toggle Field Watchpoint* read the member at the caret from the
Java server's document outline (`memberAt`; the package from the source's `package` line) and ask for
`Class#member` when there is none. While paused, Variables → **Break When Value Changes** asks the adapter
(`dataBreakpointInfo`) which field a row is; a local variable has no `dataId` and cannot be watched. The stop's
reason (`function breakpoint` / `data breakpoint`) is named on the state line. The opt-in
`MemberBreakpointsProbeFxTest` sets both from the caret against the installed jdtls and java-debug.
Each is marked in the gutter on its member's declaration line ([`ui/MemberBreakpointMarks`](../../src/main/java/com/editora/ui/MemberBreakpointMarks.java)):
`declarationLines` walks the file's document outline as `memberAt` does (binary class names, `<init>` for a
constructor) and puts every breakpoint on each declaration of its member — all overloads of a method. The marks live
on the `BreakpointManager` (`setMemberMarks`, view lines) beside the line breakpoints; the gutter draws a line
breakpoint first, else the mark (`hasGlyph`, `glyphClasses` → `method`/`field` with `conditional`, `disabled`,
`muted`; `glyphTooltip`). They are placed again when the member breakpoints change (at once from the marks already
shown, `current`, then from a fresh outline), when a file opens, when a server's capabilities arrive
(`onServerCapabilitiesReady`) and on each typing pause (`LspCoordinator.Ops.onTypingPause`); an answer for an older
document version is dropped. A click on a mark removes its breakpoints; its right-click menu disables or removes them.
**Pinned values** ([`editor/debug/PinManager`](../../src/main/java/com/editora/editor/debug/PinManager.java),
[`ui/PinnedValues`](../../src/main/java/com/editora/ui/PinnedValues.java)) are expressions pinned to a line
(`config/PinnedLine`: line, expressions, line-text snapshot), at most four per line. The manager is one more
`LineMarks` carrier beside bookmarks and breakpoints (it follows edits through `LineMarkTracker`, rides the
narrowing swap, and re-anchors to its line text on reopen), and the pins are kept in `breakpoints.json`
(`pins`, project → file → lines; schema 5, merged by line). On every frame selection `PinnedValues.refresh` sends
each open file's pins — at most 40 a stop — to `evaluateFull` with context `watch` in that frame and paints
`expr = value` (or `unavailable: …`) after the line's inline values (`InlineValuesOverlay.setPinned`, the palette's
info colour, `◆ `); `clearDebugEditorSurfaces` clears them when the program runs. Commands: `debug.pinValue`
(selection or `DebugExpressions.atCaret`, else a prompt; again unpins) and `debug.unpinLine`; the value popup's
Pin to Line pins its expression to the line it opened for (`DebugValuePopup.lineAbove`). The opt-in
`PinnedValuesProbeFxTest` checks two pins against java-debug across two stops.
**Evaluating expressions** follows IntelliJ's Evaluate Expression. Three surfaces evaluate with DAP context `repl`
(a method call runs; `DebugPanel.Actions.evaluateCode` → `DapManager.evaluateFull`, which counts a `repl` evaluation
as a console one, so a Step meanwhile is refused rather than queued) and keep the result's `variablesReference`:
- the **evaluate field at the top of Variables** (`DebugPanel.evaluateBar`): its result is the tree's first row
  (`Kind.RESULT`, built by `lazyItem`, so its fields get Set Value, Go to Index and Add to Watches), kept across the
  B205 refresh that follows a successful evaluation and dropped on any state change. Its fields are not noted for
  changed-value marks or Debug History (`underResult`);
- the **Evaluate window** ([`ui/DebugEvaluateWindow`](../../src/main/java/com/editora/ui/DebugEvaluateWindow.java),
  `debug.evaluate`): non-modal, opened with the selection or `DebugExpressions.atCaret`, its result a tree loaded by
  [`ui/DebugValueTree`](../../src/main/java/com/editora/ui/DebugValueTree.java) (shared with the hover popup). The
  coordinator keeps every window it opened and reuses a closed one; a state change leaves their results to read but not
  to expand (`sessionChanged`);
- **Quick Evaluate** (Alt+click, `DebugHover.quickEvaluate`): the selection clicked in (as it was at the press) or
  `DebugIdentifiers.quickExpressionAt` — the name chain plus a call's argument list — shown by the value popup's
  `showEvaluated`, which also shows a failure.

Both fields are a [`ui/DebugExpressionField`](../../src/main/java/com/editora/ui/DebugExpressionField.java): a
RichTextFX area highlighted with the stopped frame's grammar (`setLanguageFile`, loaded off the FX thread), the
configured keymap's motion and editing through [`ui/CodeFieldKeymap`](../../src/main/java/com/editora/ui/CodeFieldKeymap.java)
(the `CodeArea` counterpart of `TextInputKeymap`), completion through `DebugCompletion` (now over a `TextField` or a
`CodeArea`), one line where Enter evaluates or several (Shift+Enter, or text with a line break) where Ctrl+Enter does.
Every field walks one history (`DebugPanelText.History(shared)`: one list, a place of its own), persisted as the
workspace's `debugEvaluateHistory`. Watches can be paused (`debugPausedWatches`, `ValueNote.PAUSED`): listed, not
evaluated at each stop, with Evaluate Once for the stop shown. `DebugPanelEvaluateFxTest`, `DebugEvaluateWindowFxTest`,
`DebugExpressionFieldFxTest` and `QuickEvaluateFxTest` cover them with fake adapters.
**Set Value on a watch** (its menu, or `debug.setValue` with the watch selected; IntelliJ's and Visual Studio's) assigns
a value to the watch's expression while the program is paused and the watch has a value at this stop
(`DebugPanel.settableWatch`: not paused, not failed). `DapManager.setExpression` asks the debugger's `setExpression`
where it has one (`DapClient.supportsSetExpression`: debugpy, js-debug); else, when the watch names a variable of the
scopes shown (`DebugPanel.scopeHolding` gives that scope's reference), it sets that variable (`setVariable`: java-debug,
Delve, CodeLLDB — with the existing fallback to an assignment for a type java-debug cannot set); else it evaluates
`expression = value` in the frame shown (java-debug's `o.n`). Then every watch and value shown is evaluated again.
`DebugPanelWatchesFxTest` covers the panel's side; `WatchSetValueProbeFxTest` sets `x` and `o.n` and reads them back on
debugpy, java-debug and js-debug, and `x` on Delve.
**Debug History** ([`ui/DebugHistory`](../../src/main/java/com/editora/ui/DebugHistory.java), "time travel lite") keeps
each stop as it was shown, so the Debug window can go back to it after the program has run on. Nothing is asked of
the adapter for it: no DAP adapter Editora drives supports `stepBack`, and an earlier stop's values cannot be fetched
again. A stop (`DebugHistory.Stop`) is recorded when the call stack of a new suspension arrives
(`DebugPanel.setCallStack` after `setState(SUSPENDED)`) and holds:
- the frames, the frame selected, the stop reason, thread and time;
- per frame, the rows the Variables tree showed (`noteValue`, the hook that also feeds `DebugValueChanges`): each
  row's path of names, value, type and whether it had children (at most `MAX_ROWS` a frame);
- the inline values painted for a frame (`DebugCoordinator.applyInlineValues` → `recordInlineValues`), the pinned
  values painted per file (`PinnedValues.setOnPainted` → `recordPins`) and the exception summary.

The ring keeps `Settings.debugHistoryStops` stops (200 by default, 0 = none; Settings → Debugging → History), in
memory only. A new session clears it, so after Stop the last session's stops can still be read. Back (`debug.historyBack`,
the toolbar's ‹), Forward (`debug.historyForward`, ›) and Show History (`debug.showHistory`, a list of the stops) put the
panel in history mode: the stop's own call stack, a read-only tree rebuilt from the rows
(`DebugPanel.historyTree`: a row with children nobody opened says so), a banner with Back to the live stop, and
changed-value marks against the stop before (`DebugHistory.changed`). The editor shows the stop's line grey
(`ExecutionLine.HISTORY_STYLE`), its inline values and pinned values (`PinnedValues.showRecorded`), and hovering
evaluates nothing. Forward from the last kept stop, a step or Continue, or a new stop return to the live state
(`leaveHistory`). Show Value History on a Variables row lists the row's value at each kept stop of the same frame
(`DebugHistory.valueHistory`, the frame told apart by `DebugValueChanges.frameKey`); picking one goes back to that stop.
`DebugHistoryPanelFxTest` and `DebugHistoryWindowFxTest` (a fake adapter) cover the panel and editor;
`DebugHistoryProbeFxTest`, opt-in, drives debugpy, js-debug and java-debug.
**Recording breakpoints** keep a stop in Debug History and let the program go on, as Visual Studio's tracepoints and
IntelliJ's non-suspending breakpoints log. `Breakpoint.recording` (`breakpoints.json` schema 7; `records()` is false for a
logpoint, which never stops) is sent as an ordinary breakpoint: the adapter is not told. `BreakpointTargets` and
`ClosedFileBreakpoints` carry it on `DapModels.LineBreakpoint.recording`, so a stop is matched against what was sent, in
the numbering of the code the program runs (`DebugCoordinator.recordingBreakpointAt`). On a `breakpoint` stop there,
`recordingHit` marks the stop quiet (`selectFrame` paints nothing in the editor), and `recordAndContinue` reads the top
frame's first cheap scope and the watches into the stop's history entry (its inline values too), writes `Recorded stop
n: …` to the console and resumes. A session records at most `MAX_RECORDED_STOPS` (500); past that, or with Debug History
off, the breakpoint stops and says why. *Debug: Toggle Recording Breakpoint* and the breakpoint form's *Record and
continue* set it; the gutter draws it purple with a ring. `RecordingBreakpointsWindowFxTest` (a fake adapter) and the
`…Recording` tests of `DebugHistoryProbeFxTest` cover it.
**Don't step into** (IntelliJ's stepping filters, VS Code's `skipFiles`, debugpy's `rules`; Settings → Debugging's
list, and the call stack's Don't Step Into This…, `debug.dontStepIntoFrame`) names code Step Into goes over and a step
leaves at once: a class or package (`com.shop.util.*`), a module (`requests`), a function (`fmt.*`, `std::*`) or a file
(`**/vendor/**`), for one language or every one. They are kept in Settings (`debugStepFilterList` of
[`dap/StepFilter`](../../src/main/java/com/editora/dap/StepFilter.java), schema 173) and shared by every session
(`DapManager.setStepFilters`). [`dap/StepFilters`](../../src/main/java/com/editora/dap/StepFilters.java) (pure) decides
how each debugger is told. A pattern is a file when it has a `/` (not in Go, whose functions are named by import path),
a leading `**` or a source extension (`pathLike`); otherwise it names code. The debuggers that skip code themselves are
told: java-debug through `vscode.java.updateDebugSettings`' `skipClasses`, beside the JDK's (`JavaDebugSettings.toJson`,
sent before each session and again at once when the list changes during one — java-debug reads it at each step);
Editora's own JVM adapter through the launch's `skipClasses`; debugpy through `rules` (`{"module": …}` or `{"path": …}`,
`include` false) and js-debug through `skipFiles`, added to the launch or attach request by a decorator `publishClient`
gives each `DapClient` (`decorate`; a child session's request is its parent adapter's). Delve, CodeLLDB, GDB, netcoredbg,
rdbg and Xdebug have no such option, and a filter added during a session reaches debugpy and js-debug only at the next
launch, so [`ui/StepFilterCommands`](../../src/main/java/com/editora/ui/StepFilterCommands.java) checks every `step`
stop: when its top frame is in filtered code (`matches`: the function's name as a glob, a dotted name as a package or
module found in the frame's path, a file glob against the path) and there is a caller, the stop is held quiet and the
thread steps out — again while it lands in such code, at most `MAX_STEPS_OUT` (8) times. A breakpoint in filtered code
still stops. Don't Step Into This… offers the frame's code as its language names it (`suggest`: a JVM class with the
package its source declares, a Python or JavaScript file — its package under `node_modules` — a Go or C# package, a C++
or Rust outer namespace) in [`ui/StepFilterDialog`](../../src/main/java/com/editora/ui/StepFilterDialog.java), and adds
it; Settings' list is [`ui/StepFiltersEditor`](../../src/main/java/com/editora/ui/StepFiltersEditor.java).
`StepFiltersWindowFxTest` covers the step out and the command against `FakeDebugAdapter` (as rdbg);
`StepFiltersProbeFxTest` steps into a filtered call on debugpy (a module), java-debug (a class), js-debug (a file) and
Delve (`strings.*`, stepped out of by Editora) and stops back in the caller.
**The evaluation time limit** (Settings → Debugging → Evaluating expressions → Time limit, `debugEvaluationTimeLimit`,
10 s by default, schema 174) keeps an expression that runs the program — a watch, the Debug console, a hover or Quick
Evaluate that calls a function — from holding the stopped thread for good: the debugger stops the call and puts the
frame back, so the program stays paused where it was and the next expression is evaluated there.
[`dap/EvaluationLimits`](../../src/main/java/com/editora/dap/EvaluationLimits.java) tells each debugger as a session
starts (`DapManager.setEvaluationTimeLimit`): GDB on its command line, before `-i dap` (`set direct-call-timeout`,
`set indirect-call-timeout` for a breakpoint's condition, and `set unwind-on-timeout on`), and debugpy's launch through
pydevd's `PYDEVD_INTERRUPT_THREAD_TIMEOUT` in the program's environment, which interrupts the evaluating thread with
`KeyboardInterrupt` (a busy loop, a `sleep` and a long comprehension alike; an attach is not told, the program having
started without it; a launch.json's own value is kept). Editora waits for an answer past the limit, never under 30 s
(`EvaluationLimits.wait`, `DapClient.setEvaluationWait`), and an answer that does not come says the program is still
running the expression after that long. CodeLLDB stops a call in a watch or a hover by itself, in under a second,
whatever its `evaluationTimeout` says, and its Debug console takes LLDB commands, whose `expr` waits for good unless
given `-t`. java-debug, js-debug and Delve have no limit: a call there that never returns holds the session until it is
stopped. DAP's `cancel` was probed as the way out and is none: of the debuggers here only GDB and CodeLLDB advertise
`supportsCancelRequest`; GDB ignores it during an inferior call (and Pause did not bring it back), and CodeLLDB
interrupts the call but its frame then evaluates nothing. `EvaluationTimeLimitProbeFxTest` (limit 3 s) calls a function
that takes a second (answered) and one that never returns (stopped within the limit, the program still paused on the
same line, `x` read after it) on GDB and debugpy, and on CodeLLDB checks its own stop; a run with the limit off fell
back to Editora's 30 s wait on both. `SettingsEvaluationTimeLimitFxTest` covers the Settings row.
**Exception breakpoints** (IntelliJ's Java Exception Breakpoints, Visual Studio's Exception Settings; Debug: Add
Exception Breakpoint…, and rows of Debug: Exception Breakpoints… after the adapter's filters) stop on an exception
of a named type, when a condition holds. A [`config/ExceptionBreakpoint`](../../src/main/java/com/editora/config/ExceptionBreakpoint.java)
is a debug language, a type pattern (comma-separated globs with `!` exclusions, as breakpoint filters'), a condition
and an enabled flag, kept per project in `breakpoints.json` (schema 11, `exceptionBreakpoints`). Editora checks them
itself, so they work with every adapter that stops on exceptions. For a language whose adapter has an "every thrown
exception" filter (`ExceptionFilterCatalog.caughtFilter`: java-debug `caught`, debugpy `raised`, js-debug `all`),
`ExceptionBreakpoints.extras` turns that filter on besides the user's choice (`DapManager.setExceptionExtras` →
`DapClient.setExtraExceptionFilters`, merged in the adapter's order by `withExtras`; none while breakpoints are muted);
other adapters' exception stops (C++ on throw, Go panics) are narrowed as they come. At an `exception` stop
(`ExceptionBreakpointCommands.stop`, called from `DebugCoordinator.onStopped` after breakpoint filters), unless the user's Caught choice already stops on every
thrown one, the stop is held quiet and `DapManager.exceptionInfo` asked which exception it is: an uncaught one
(`breakMode` `unhandled`, as debugpy and java-debug mark the uncaught stop) is the user's choice and shown; otherwise
the enabled breakpoints of the session's language whose pattern names the type — whole or by its last part after `.`,
`::` or `/` (`ExceptionBreakpoints.typeNames`) — are taken, their conditions evaluated in the frame that threw
(`watch` context; a value that is not false, zero or nothing holds; one that cannot be evaluated stops and says so),
and the stop is shown (re-entering `onStopped`, then `showException`) or the program resumed. debugpy stops a raised
exception again in each frame it passes up through: the stop after a shown one, same type, its stack the shown one's
outer frames, is passed over (`propagating`). Subclasses are not matched (no adapter says a type's ancestors); a
pattern such as `*IOException` reaches them by name. Measured (`ExceptionBreakpointsProbeFxTest`, three caught
throws, one breakpoint on the first type with a condition true only the third time): debugpy and java-debug stop once,
at the third throw, and run to the end. `ExceptionBreakpointsWindowFxTest` covers the decisions against
`FakeDebugAdapter` reached as rdbg (no thrown-exception filter), and Add's offer of the type of the exception shown.
**Breakpoint filters** (IntelliJ's thread, caller and instance filters; the Edit Breakpoint form's *Only in threads*,
*Only when called from* and *Only on the object marked*) say where a breakpoint's hits count. They are a
[`config/StopFilters`](../../src/main/java/com/editora/config/StopFilters.java) on the breakpoint (`Breakpoint.filters`,
breakpoints.json 10) and on what is sent (`LineBreakpoint.filters`). No adapter knows them, so they are checked
client-side and work with every adapter: `DapClient` sends a filtered breakpoint without its hit count and log message
(the adapter would count and log hits the filters drop), and `DebugCoordinator.filteredStop`, at the top of
`onStopped`, holds a stop on one quiet (`quietStop`) while it checks the caller (frame 1's name, as shown, without its
arguments, or its function alone — `StopFilterCheck.callerNames`), then the thread's name (`threads`, java-debug's
`Thread [x]` read as `x`), then the instance — `this` (`self` in Python, Ruby and Rust, `$this` in PHP) evaluated in the
top frame and its Mark Object identity compared with the label's. A stop that passes counts toward the hit count
(`StopFilterCheck.hitMatches`: `N`, `== N`, `>= N`, `> N`, `<= N`, `< N`, `% N`, per session in
`DebugSession.filteredHits`) and is handled anew, shown (`filtersPassed`); one that fails resumes unseen. A filtered
logpoint is written by Editora (`logpointToWrite`). Patterns are comma-separated globs, case ignored, `!` excluding.
An instance filter whose label no object carries in the session passes, so the first stop can be marked and the next
ones are that object's. `StopFilterCheckTest`, `BreakpointFiltersWindowFxTest` (the fake's scripted frames and
`evaluateResults`) and the opt-in `BreakpointFiltersProbeFxTest` (debugpy and java-debug: a worker-thread filter, then a
caller filter with an instance filter on an order marked at the first stop) cover it.
**Triggered breakpoints** ([`ui/TriggeredBreakpoints`](../../src/main/java/com/editora/ui/TriggeredBreakpoints.java))
wait, in each debug session, until another breakpoint is hit — VS Code's triggered breakpoints, IntelliJ's "Disable
until hitting the following breakpoint", Visual Studio's "Only enable when the following breakpoint is hit". A
breakpoint names the one it waits for by id (`Breakpoint.triggeredBy` → that one's `Breakpoint.id`, `breakpoints.json`
schema 8): line numbers move with edits, ids do not. A breakpoint gets an id (`Breakpoint.newId`, eight hex digits)
only when another is first made to wait for it (`TriggeredBreakpoints.link`, in its tab or in a closed file's saved
list). The waiting is Editora's, not the adapter's, so it works with every debugger: `fileBreakpoints` and
`ClosedFileBreakpoints.of` (its `sent` predicate) leave out a breakpoint that `TriggeredBreakpoints.waits` — its trigger
still exists among the window's breakpoints and is not in the session's `DebugSession.hitTriggers`. On a `breakpoint`
stop (`armTriggered`, before a recording breakpoint resumes, so a recording one can be a trigger), the top frame's
breakpoint id joins `hitTriggers` and the files of the breakpoints waiting for it are sent again — open tabs at once, a
closed file re-anchored off the FX thread — with the status bar and console saying so. `hitTriggers` is cleared when a
session starts or ends, so each session waits again. A breakpoint whose trigger is removed waits for none: it is sent
at once (`triggersChanged` re-sends the other tabs' triggered breakpoints after any breakpoint change) and drawn as a
plain one. While waiting, the gutter draws it hollow with "Waiting: … has not been hit in this session"; otherwise an
hourglass over the dot (`FoldManager.TRIGGERED_MARK_PATH`) and "Waits until … is hit" on hover
(`BreakpointManager.setTriggerLabels`, `BreakpointDescription.lines`). *Debug: Add Triggered Breakpoint…*
(`debug.addTriggeredBreakpoint`; editor menu, gutter menu, Run menu) picks the breakpoint to wait for from a list, and
the breakpoint form has a *Wait for* choice; neither offers a breakpoint that waits, directly or through others, for
this one (`TriggeredBreakpoints.choices`), so two never wait for each other. `TriggeredBreakpointsTest`,
`TriggeredBreakpointsWindowFxTest` (a fake adapter) and the opt-in `TriggeredBreakpointsProbeFxTest` (debugpy, js-debug,
java-debug) cover it.
**Breakpoint groups and breakpoint files** ([`ui/BreakpointGroups`](../../src/main/java/com/editora/ui/BreakpointGroups.java),
[`config/BreakpointExport`](../../src/main/java/com/editora/config/BreakpointExport.java)). Any breakpoint — on a line,
a method or a field — can be in a named group (`Breakpoint.group`, `MemberBreakpoint.group`, `breakpoints.json` schema
9; `Breakpoint.groupName` keeps a name on one line, at most 60 characters), as IntelliJ's groups are. *Debug: Move
Breakpoint to Group…* (editor and gutter menus, Run menu) and the breakpoint form's *Group* field (an editable list of
the groups there are; blank for none) set it. *Debug: Enable / Disable Breakpoint Group…* lists the groups — ✓ when
all of a group's breakpoints are on, – when some are — and choosing one turns them all on, or all off when they all
are (`BreakpointsPicker.toggleGroup`, through the same per-entry `toggleEnabled` as Alt+E, so tabs, closed files and
member breakpoints all follow and a live session is told). In the Breakpoints list a row's detail names its group,
typing a group's name lists its breakpoints, and Alt+G turns the highlighted row's group on or off. *Debug: Export
Breakpoints…* writes every breakpoint, or one group's (asked when there are groups), to a JSON file
(`{"format": "editora-breakpoints", "version": 1, "breakpoints": [{"file", "breakpoint"}], "memberBreakpoints"}`), each
file relative to the project with `/` when under it, else its stored form; an encrypted file's line text is left out.
*Debug: Import Breakpoints…* reads one back (`BreakpointExport.read` refuses another format or a later version):
breakpoints of an open tab are re-anchored to the line their text is on now and added where the line has none; a
file with no tab gets them in its saved list, and a live session is sent them (`DebugCoordinator.sendClosed`, off the
FX thread); files that are not there are counted, not set. An imported id that another breakpoint already has is
replaced, and what waits for it follows (`BreakpointExport.freshIds`). The file choosers are `chooseSave` /
`chooseOpen`, which tests answer. `BreakpointGroupsTest`, `BreakpointExportTest` and `BreakpointGroupsWindowFxTest` (a
fake adapter) cover it.
**Loaded Sources and Modules** ([`ui/LoadedSourcesPicker`](../../src/main/java/com/editora/ui/LoadedSourcesPicker.java)):
*Debug: Loaded Sources…* (VS Code's Loaded Scripts) and *Debug: Modules…* (Visual Studio's Modules window), palette
lists of what the live session's debuggee has loaded (Run menu; greyed in the palette with no session). Each
`DapClient` keeps what its adapter's `loadedSource` and `module` events say (`sourcesSeen`, `modulesSeen`; a
`removed` reason takes one out), and `loadedSources()` / `modules()` merge that with the `loadedSources` / `modules`
requests' answers where the adapter offers them, over the root and every live child (a js-debug program's workers),
remembering which session listed each `sourceReference` (`sourceOwners`) so the `source` request goes to it. What the
adapters do (measured): js-debug offers `loadedSources` and sends the events — the program's files by path, Node's
internals as `<node_internals>/…` with a reference only; CodeLLDB (Rust, C/C++) offers `modules` and sends events with
the symbol status; netcoredbg sends module events without offering the request (so `listsModules` is also true once
an event came); debugpy answers `modules` with the modules whose code has run under it (the script as `__main__`),
not every import; java-debug and Delve list neither, and the commands say so. The sources list puts the project's
files first, then other files, then references; Enter opens a file in its tab (breakpoints are set there as anywhere)
and a reference read-only (`Ops.openReadOnlyDoc`, highlighted by name; Node's internals as JavaScript) with the
`source` request's text. The modules list puts the user's code first, shows symbol status, version, user code /
optimized, address range and path, and Enter or Alt+C copies the path. `DapLoadedSourcesTest`,
`LoadedSourcesPickerTest`, `LoadedSourcesWindowFxTest` (a fake adapter) and the opt-in `LoadedSourcesProbeFxTest`
(js-debug, CodeLLDB, debugpy) cover it.
**Code without a file.** A frame whose source the adapter has only by reference (DAP `sourceReference`: Node's
internals, code run through `eval`) carries it as `StackFrameInfo.reference` (`DapClient.referenceOf`;
the frame's session is remembered in `sourceOwners`, so the `source` request about it goes to a js-debug worker when
the frame is a worker's). `highlightFrame` opens it read-only through `openReferenceSource` — the `source` request's
text in a path-less tab (`Ops.openReadOnlyDoc`), once per session (`DebugSession.sourceDocs`; asked again, the tab
is brought to the front with `Ops.selectBufferTab`) — and marks the execution line there, as VS Code does. Which
frame a stop shows: a step whose top frame is such code shows it (`frameToShow`); any other stop takes the topmost
frame that is a file or such code the adapter does not de-emphasize (`showsOnStop`), so a pause in Node's skipped
internals still lands on the user's line; the call stack draws such frames as having source (`canShow`).
Loaded Sources opens a reference through the same tab. A breakpoint set in that tab goes to the session naming the
reference (`DapClient.setReferenceBreakpoints`; `DebugCoordinator.referenceBreakpointsChanged`, the gutter drawn from
the answer); it is never saved, and the session's end removes it (`forgetReferenceBreakpoints`): a reference means
nothing to the next session. debugpy, with its default `justMyCode`, leaves `exec`'d code out of the stack, so this
is js-debug's in practice. `DebugFrameSourceTest`, `SourcelessFramesWindowFxTest` (a fake adapter) and the opt-in
`SourcelessFramesProbeFxTest` (js-debug: a `debugger` statement in `eval` code, a step into it, a breakpoint set in it)
cover it.
**Mark Object and View As** ([`ui/ObjectMarks`](../../src/main/java/com/editora/ui/ObjectMarks.java),
[`ui/StringViews`](../../src/main/java/com/editora/ui/StringViews.java)). *Mark Object…* on a Variables, watch or
result row gives the object it shows a label, drawn as `[label]` before its value wherever that object appears —
Variables, watches, evaluation results, the hover popup (`DebugValuePopup.setMarks`) and Debug History — as
IntelliJ's Mark Object does. An object is told by what its adapter shows of its identity (measured):
java-debug's object id (`ArrayList@10 size=2`), else an address (debugpy's `<Box object at 0x7f…>`, a native pointer);
js-debug shows none (`Box`), so a JavaScript object, a number or a string cannot be marked and the item is not
offered (`ObjectMarks.identity`). Marks are the session's (`DebugSession.marks`), cleared when it starts again — an id
means nothing to another run — and kept after Stop so Debug History still shows them; the label can be used only to
recognize the object, not in expressions (java-debug offers no way to name an object). IntelliJ's Referring Objects
is not offered: it needs JDI's `referringObjects`, which java-debug does not expose and DAP has no request for.
*View As ▸ JSON / XML / Text* on a string row opens its text in a read-only tab (`Actions.openText` →
`Ops.openReadOnlyDoc`), laid out and highlighted as JSON (Jackson) or XML (no DTDs or external entities: the value is
the debuggee's), or as it is; text that is not what it was asked to be opens as text and the status line says why.
The whole text is evaluated again (`repl`: js-debug cuts a watch or hover at ~1,000 characters) while paused; an
earlier stop shows what it kept. Each adapter quotes a string its own way (measured): java-debug as `"…"` with the
text as it is, debugpy and js-debug as a literal of their language, escapes and all (`StringViews.content`).
`ObjectMarksTest`, `StringViewsTest`, `MarkObjectWindowFxTest` (a fake adapter) and the opt-in
`MarkObjectProbeFxTest` (java-debug, debugpy) cover it.
**Add Logpoint** (`DebugCoordinator.addLogpoint`): *Debug: Add Logpoint…* and a Shift+click on the breakpoint strip
(`FoldManager.setBreakpointShiftClick` → `EditorBuffer.setGutterLogpointClick`; unset, a Shift+click toggles like a
plain click) open a one-field form for the log message, with the `{expression}` completion of the full form. OK is
disabled while the message is blank, so it never leaves a breakpoint that stops; the breakpoint is created only on OK,
and on a line that has one only its message changes. A method breakpoint's mark ignores the Shift+click.
`DebugReviewCWindowFxTest.shiftClickingTheGutterOrAddLogpointMakesTheLineALogpoint` covers it.
**Logpoint tokens** ([`dap/LogpointTokens`](../../src/main/java/com/editora/dap/LogpointTokens.java)). A log message
may use Visual Studio's tracepoint tokens `$FUNCTION`, `$CALLER`, `$CALLSTACK`, `$TID` and `$TNAME`. No adapter knows
them, so `DapClient.sourceBreakpoints` sends such a logpoint without its `logMessage` (its condition and hit count
kept): the adapter stops there, `DebugCoordinator.logpointToWrite` finds the logpoint at the top frame's line, and
`logAndContinue` evaluates its `{expression}` parts one after another in that frame (`watch` context; a refusal shows
the adapter's message), fills the tokens from the stop's frames (`DebugPanelText.frameText`, one per line after the
message for `$CALLSTACK`) and the `threads` answer, writes the line to the console and resumes. The stop is quiet like
a recording breakpoint's (`DebugSession.quietStop`): not shown, not in Debug History, and a step or Stop meanwhile wins.
The program pauses briefly at each hit, which the form says; a message without tokens is the adapter's, as before.
As with a recording breakpoint, the stop does not end a Run to Cursor under way: the resume carries it on
(`DapManager.settleTempBreakpoint`).
An adapter without `supportsLogPoints` (netcoredbg 3.2.0) ignores every `logMessage` and stops on the breakpoint after
checking its condition, so `logpointToWrite` takes any logpoint of such a session the same way, tokens or not
(`DapClient.supportsLogPoints`; capabilities not known yet count as supporting). A hit count the adapter does not
support is not emulated. `LogpointWithoutAdapterSupportFxTest` covers it.
**Copy as Expression** (VS Code's). `VariableInfo.evaluateName` and `VarRow.evaluateName` carry the adapter's own
expression for a value (`items.get(0)` from java-debug, `d['k']` from debugpy, `obj.x` from js-debug), kept through
lazy rows (`LazyValues`), Java's text tidying and string repair. `DebugPanel.expression` uses it, else the path of names
(`pathExpression`, which reads `items[0]` — not Java for a list): Copy as Expression, Add to Watches, Set Value's
assignment fallback, View As and the selected row of the evaluate window and hover popup all go through it.
**Jump to Type Source** (IntelliJ's; [`ui/DebugTypeSource`](../../src/main/java/com/editora/ui/DebugTypeSource.java)).
On a Variables, watch or result row whose type names a class, the menu opens that class's declaration — the value's
own type, which may be a subclass of the declared one. `DebugTypeSource.parse` reads each adapter's way of naming a
type (`java.util.ArrayList<String>`, `Main$Square`, `*main.Circle`, `main.Stack[int]`, `app::Point`, `const Shape &`;
js-debug types every object `object`, so its value's leading class name is used) and refuses built-ins, arrays,
slices, maps, tuples and anonymous classes. The language server of the shown frame's file answers `workspace/symbol`
for the simple name; `best` takes a class, interface, enum or struct of that name whose container agrees with the
qualifier, then a project file, then a library's top-level class before a nested or shaded namesake (java-debug names
`ArrayList` without its package, and jdtls also has the private `Arrays.ArrayList`), then Search Everywhere's tie
order. Unless the project declares it, jdtls's JDK and dependency classes are asked too (`LspCoordinator.libraryTypes`),
and a library class opens in its read-only tab. The item is offered only while that server answers workspace symbols.
**Run to Here in the gutter** (IntelliJ's). While paused, hovering a line number turns it into an arrow
(`FoldManager.runToHere`: the number stays under it, transparent, so the gutter keeps its width; one shared tooltip);
a click runs to that line and Shift+click ignores the breakpoints on the way (`DebugCoordinator.runToLine`, shared
with Run to Cursor and Force Run to Cursor). Only an armed number keeps the press from the text. Not on the line it
stopped on, not while an earlier stop is shown, and only in a file that takes breakpoints
(`EditorBuffer.GutterRunToHere`).
**Step timing** (Visual Studio's PerfTips; setting *Show how long each step took*, `debugStepTiming`, settings 169).
`DebugCoordinator.timeRun` starts the clock when the program goes on from a stop the user saw (a step, Continue, Run to
Cursor) and keeps it through a quiet stop; the next stop shows `≤ 1 ms elapsed`, `12 ms elapsed`, `1.5 s elapsed`
(`DebugPanelText.elapsed`) faintly after the line it stopped on (`InlineValuesOverlay.setElapsed`, painted last on the
line), in the frame the stop showed first. The first stop after a launch, a Pause's and the program's entry are not
timed. The time is measured by Editora, so it includes the adapter's round trips: a hint, not a profiler.
`DebugTypeSourceTest`, `LogpointTokensTest`, `DebugPanelTextTest`, `DebugSmallWinsWindowFxTest` (a fake adapter) and
the opt-in `DebugSmallWinsProbeFxTest` (debugpy; java-debug with JDT LS) cover these.
**Lambda breakpoints** ([`ui/LambdaBreakpoints`](../../src/main/java/com/editora/ui/LambdaBreakpoints.java),
[`editor/debug/JavaLambdas`](../../src/main/java/com/editora/editor/debug/JavaLambdas.java)) are line breakpoints
that also, or only, stop inside lambdas of their line: `Breakpoint.lambdas` holds their numbers on the line (from 1,
left to right) and `lambdasOnly` says the line itself is not a target (`breakpoints.json` schema 6). java-debug
installs a line breakpoint at the line's first location only, so it never stops in a lambda body; a breakpoint with a
`column` is matched by `LambdaExpressionLocator` to the lambda whose start position (its parameter, or the `(` of its
parameter list) is exactly that column, and installed on the synthetic `lambda$…` method. `JavaLambdas.on` finds
those starts in the line's text (each `->` outside strings and comments, traced back to its parameters, skipping a
switch rule's `case …` / `default` arrow), and `BreakpointTargets.of` turns one breakpoint into the line's
`setBreakpoints` entries at send time — the line unless `lambdasOnly`, then one per lambda still on the line, each
with the breakpoint's condition, hit count and log message. Keeping numbers rather than columns lets an edit before
a lambda move it without losing it; a number the line no longer has falls back to the line. `DapClient.perLine`
folds the answers into one status per line (bound when all are, refused when any is). Commands:
`debug.toggleLambdaBreakpoint` (the lambda at or before the caret, else the first; again removes it, and the
breakpoint with it when it stopped nowhere else); the gutter menu's Stop on the Line / Stop in Lambda n check items.
The opt-in `LambdaBreakpointsProbeFxTest` checks both kinds against java-debug.
In **JavaScript and TypeScript** the same breakpoints stop inside the arrow functions and function expressions of the
line ([`editor/debug/JsFunctions`](../../src/main/java/com/editora/editor/debug/JsFunctions.java)): each `=>` outside
strings, template literals and comments, traced back to its parameter or parameter list (past a TypeScript return
type and an `async`), and each `function` that does not begin the line. V8 puts a column breakpoint on the first place
it can stop at or after the column, so where the function stops is the start of its body — the expression after `=>`,
else the first statement after its `{`, or this line's end when the body goes on below — while its start is what the
caret and its number go by. [`editor/debug/LineFunctions`](../../src/main/java/com/editora/editor/debug/LineFunctions.java)
dispatches by language (and by file extension for a file with no tab, `languageOf`); `BreakpointTargets.of` sends each
function's stop column. The opt-in `JsColumnBreakpointsProbeFxTest` checks both kinds against js-debug.
The **Threads view** (the Call Stack area's Threads switch, `debug.threads`) lists `DapManager.threadStatuses`: the
adapter's `threads`, each marked suspended when it is in `stoppedThreads` (with its stop reason), is the thread shown,
or — after a whole-program suspend (`allSuspended`) — was not resumed on its own since (`resumedSinceSuspendAll`).
**Pause Thread** sends a plain `pause` for that thread (java-debug suspends it alone; debugpy suspends everything) and
its `stopped` event shows it like any stop. **Resume Thread** sends a plain `continue` for it; when it is the thread
shown, the next still-stopped thread is brought forward as after Continue, and an answer of `allThreadsContinued`
(debugpy) runs the whole session. **Show Frames** on a suspended thread selects it as the dropdown does, or — no thread
shown because the shown one was resumed alone — brings it forward (`showSuspendedThread`). The adapter's `thread`
events (start/exit) reach the panel as `onThreadsChanged`, coalesced into one listing per 250 ms while the view is
shown. The opt-in `ThreadsViewProbeFxTest` checks Pause Thread and Resume Thread act on one thread against java-debug.
**Thread Dump** (`debug.threadDump`, IntelliJ's Get Thread Dump; also in the Threads view's menu and the call stack's)
is every thread's stack at one moment. `DapManager.threadStacks` asks `threadStatuses`, then sends `stackTrace` for
every thread at once — a running one too, since an adapter that stops the whole program without `allThreadsStopped`
still walks it — and a refused one has no frames. Suspended, the dump is of the stop. Running, the coordinator sets
`DebugSession.dumpPause` and pauses (java-debug's `pauseAll`): the `pause` stop that comes is the dump's, made a quiet
stop as a recording breakpoint's is (`quietStop`), the stacks are taken and the program resumed (`resumeAll` for Java),
the editor never taken to it (`dumpAndContinue`); another stop the user sees coming first is shown and dumped as usual.
[`ui/ThreadDump`](../../src/main/java/com/editora/ui/ThreadDump.java) (pure) holds the dump: the shown thread first and
the JVM's housekeeping threads last, java-debug's `Thread [main]` named `main`; `groups(merge)` merges threads whose
stacks match frame for frame in one state (Merge Identical Stacks; a thread without frames is never merged); `matches`
filters on a thread's name or a frame's text; `text` prints it as `jstack` does, one quoted name, `#id` and state per
row and an `at` line per frame. [`ui/DebugThreadDumpWindow`](../../src/main/java/com/editora/ui/DebugThreadDumpWindow.java)
lists the rows beside the selected one's stack; Enter or a double-click opens a frame's file at its line, a library
class through `openLibraryFrame`, or a `sourceReference` frame's text while the session lasts — the frames are a
snapshot, valid after the program went on. Copy, Open as Text (a read-only tab) and Take Again sit below it. A dump with
running threads that have no stack (java-debug with *Suspend all threads* off stops the breakpoint's thread alone)
says so and suggests Pause, then Take Again. Measured (`ThreadDumpProbeFxTest`, three workers blocked in one place and
a breakpoint in the main thread): debugpy and Delve walk every thread at a stop and the workers merge into one row
(Delve's goroutines are named `[Go 33] main.work`; the runtime's own parked goroutines stay apart, their stacks
differ); java-debug at a stop gives the breakpoint's thread alone, and paused for the dump gives every thread, the
workers merged. `DebugThreadDumpWindowFxTest` covers the window against `FakeDebugAdapter`'s `idleThreads`.
**Step Back and Reverse Continue** (`debug.stepBack`, `debug.reverseContinue`; the Debug window's B and Shift+C;
Run → Stepping) run the program backward where its debugger can, and `debug.toggleRecording` (**Record Execution**)
makes GDB able to. `DapClient.supportsStepBack` is the adapter's `supportsStepBack` — from `initialize`, or from a
later `capabilities` event (`DapClient.capabilities`), which is how Delve says it once its launch is on rr — and
`stepBack` / `reverseContinue` send those requests. GDB's DAP mode (`DebugLanguages.GDB`, GDB 16 here) has neither
request nor capability, but its own recorder works through its console: `DapManager.toggleRecording` sends `record
full` (again: `record stop`) with `DapClient.consoleCommand` — `evaluate` in the `repl` context with no frame — and,
while it records (`isRecording`), Step Back and Reverse Continue send `reverse-next` and `reverse-continue` the same
way. GDB answers them with ordinary `stopped` events carrying the values as they were, so the stop is shown like any
other. Both go through `DapManager.step`, so a backward step is refused while an evaluation runs, and a core dump's
session refuses them (`DEBUG_RUNS_PROGRAM`). `canRunBackward` (the adapter steps back, or GDB records) decides the
Debug window's two buttons (`DebugPanel.setBackward`, shown in a session that can have them: GDB's, or an adapter that
steps back) and the palette (`PaletteContext.debugBackward`, `palette.disabled.needsBackward`); Record Execution's
toggle shows in a GDB session only (`debugRecordable`), and the state line says *recording* while GDB records.
Measured on GDB 16.3: recording a loop, three Step Overs and a Step Back put the line and the total back as they were,
and Reverse Continue went back to where recording began; GDB's recorder handles glibc's AVX `memset`/`memcpy` and keeps
200,000 instructions by default. Recording slows the program a great deal, so it is turned on at a stop, not at launch.
rr (Delve's `backend: "rr"`, CodeLLDB's `reverseDebugging`) is not wired yet: it needs `rr` installed and
`kernel.perf_event_paranoid` at 1 or less. `StepBackWindowFxTest` covers it against `FakeDebugAdapter` (`stepBack`,
`announceStepBack`, a stand-in `gdb`); `StepBackProbeFxTest` drives the real GDB.
**Parallel Stacks** (`debug.parallelStacks`, beside Thread Dump in Run → Debugger and the Threads view's and call
stack's menus; Visual Studio's Parallel Stacks window) draws every thread's stack as one tree. The stacks are taken as
Thread Dump takes them (`DebugCoordinator.takeStacks`; `DebugSession.dumpForParallel` says which window they are for):
at a stop they are that stop's, and a running program is paused for them and goes on. [`ui/ParallelStacks`](../../src/main/java/com/editora/ui/ParallelStacks.java)
(pure) merges them into a trie from the outermost frame up, frames being the same when they are the same function in
the same file (`key`: name and file, not line), then cuts it into boxes: a box goes on up while one way leads on and
every thread in it takes it, so it ends where the threads part or where a thread's stack ends. A box frame whose
threads are on different lines says *several lines*; a click opens the shown thread's own line there, else the first
thread's. `layout` (pure) places each box over the middle of those above it, the boxes above side by side and one gap
up, the roots side by side at the bottom. [`ui/DebugParallelStacksWindow`](../../src/main/java/com/editora/ui/DebugParallelStacksWindow.java)
draws them on a pannable canvas with a line from each box to the box below; the boxes the shown thread passes through,
their lines, and its own top frame are drawn in the accent. Frames are focusable (Enter or a click opens their code
through `openDumpFrame`, as Thread Dump's do); Copy gives the tree as indented text; Take Again takes it anew; threads
without a stack (running ones at a stop) are counted in a note. `DebugParallelStacksWindowFxTest` covers it against
`FakeDebugAdapter`'s new per-thread `threadFrames`; `ParallelStacksProbeFxTest` pauses a running program on debugpy,
java-debug and Delve whose three workers block in one place: they are one box (java-debug's `3 threads: worker-0, …`
from `Thread.run` up to `CountDownLatch.await`), and the program goes on.
**View as Table** (the Variables menu on an expandable value — a variable, a watch, the evaluate result; IntelliJ's
and Visual Studio's table view of a collection) shows a list, array, slice, set or map as a table: a row per element,
a column per field. [`ui/ValueTable`](../../src/main/java/com/editora/ui/ValueTable.java) (pure) decides what is a row
and what a column. A debugger lists more than the elements under a collection — debugpy its `special variables`,
`function variables` and `len()`, Delve a map's `len()`, js-debug an array's `length` and `[[Prototype]]` — so
`elements` drops those and, when some children are indexes (`0`, `[0]`), keeps only them; `fields` drops the same rows
under an element; a pointer's fields are a level down, under its one unnamed child (Delve's `*o`), which `pointee`
finds and the window follows once. `of` lays the columns out: `#` (the index, or a map's key), the element's own value
when one has no fields, then each field name in the order first seen, at most `MAX_COLUMNS` (60). `compare` sorts
numbers by value (`[10]` after `[9]`) before text; `csv` and `tsv` write what is shown.
[`ui/DebugTableWindow`](../../src/main/java/com/editora/ui/DebugTableWindow.java) loads the collection through the
coordinator's `renderedVariables` (type renderers apply): a collection that reported its size (`indexedVariables`) is
asked for one page of at most `MAX_ROWS` (1000) — plus its named children, where a map's keys may be — and one that did
not is asked for everything and cut there; then each element's fields, all at once, the rows filling in as they come
(the columns are laid out again every 50 rows and at the end, keeping the sort). The note says how many elements there
are, or which first ones are shown. A filter keeps the rows with the text in a cell; Copy (Ctrl+C) puts the selected
rows, or all shown, on the clipboard as tab-separated text; Open as CSV opens them in a CSV tab; Refresh evaluates the
expression again in the shown frame (`watch` context) and loads what it is now, and needs a paused program. The table
is the stop's: it keeps its values after the program goes on. One window per collection asked for
(`DebugCoordinator.tableWindows`). `DebugTableWindowFxTest` covers it against `FakeDebugAdapter`;
`DebugTableProbeFxTest` opens a list of two objects on debugpy (a Python list), java-debug (a `List<Order>`) and Delve
(a `[]*Order`): two rows, the fields as columns, none of the debuggers' own rows.
**View as DataFrame / View as Array** (the same menu row, named for the value, in a Python session; PyCharm's data
viewer) shows a pandas `DataFrame` or `Series`, or a numpy array, as a table of its rows. debugpy lists a DataFrame's
attributes under it, not its rows, so they cannot come from its children: [`ui/PythonDataTable`](../../src/main/java/com/editora/ui/PythonDataTable.java)
(pure) builds one expression that defines a small helper (`HELPER`) in a dictionary of its own — nothing of the
program's is touched — and calls it on the value for a page of `MAX_ROWS` rows from a start, at most `MAX_COLUMNS`
columns. The helper finds pandas and numpy in `sys.modules` (it imports neither), writes each cell as `str()` does
(cut at 1,000 characters), and answers JSON: the kind, the shape, the column names and dtypes, the index labels, the
rows, and the rows and columns in all. A Series, a 0-d or 1-D array is a column of values (`leaf`); a structured
array's fields are its columns; an array of more dimensions is rows of its last one, each named by the indexes before
it (`(1, 2)`). The window evaluates it in debugpy's `clipboard` context, where a string comes back whole (the others
cut it at 1,000 characters), written as a Python string literal: `unrepr` reads it back and `parse` the JSON;
`columnNames` gives each column one name the table can key on (a second `a` is `a (2)`, never `#` or blank). The
columns' headers are labels whose tooltip is the dtype; the heading says the kind and the shape; Load More asks for the
next page and Refresh for the first again, both while paused. Which rows offer it is `PythonDataTable.viewable` (a
type ending in `DataFrame` or `Series`, `ndarray`, `matrix`, `MaskedArray`, `memmap`), asked through the panel's
`viewsAsData` so only a Python session's values do. `DebugTableWindowFxTest` covers it against `FakeDebugAdapter`
(scripted helper answers); `PythonDataProbeFxTest` runs a program with pandas and numpy from its project's `.venv`
(a link to `-Dpandas.venv`, by default `~/.cache/editora/pandas-venv`; CI makes one) on debugpy: a DataFrame of 1,500
rows, its Series and a 3-D array.
**Core dumps** (`debug.openCoreDump`, Run → Launch after Reattach; GoLand's and CLion's Open Core Dump) open the
memory of a program that crashed, or that `gcore` dumped, to look at its threads, stacks and variables.
[`dap/CoreDumps`](../../src/main/java/com/editora/dap/CoreDumps.java) reads a Linux (ELF) core's notes: `NT_FILE`
lists the files the process had mapped, the program among them, and `NT_PRPSINFO` the process's name (cut to 15
characters), which picks the program when it is not the first mapped (`programOf`). Another core — a macOS Mach-O one,
a Windows minidump — names none, nor does a core whose program has since moved: the program is then asked for
(`DebugCoordinator.chooseFile`, a test's hook). `languageOf` reads the program's bytes: Go's build information
(`\xff Go buildinf:`, in every Go program since 1.13) is Go, opened on Delve (`"mode": "core"`, `coreFilePath`);
Rust's library path (`/rustc/`) is Rust, and anything else C/C++, both on CodeLLDB, whose launch has
`targetCreateCommands: ["target create "<program>" --core "<core>""]` and an empty `processCreateCommands` (nothing
starts). A session whose launch opens a core — those, or a launch.json configuration with Delve's `mode: core`, an LLDB
`target create -c`/`--core`, or cppdbg's `coreDumpPath` (`CoreDumps.isCore`) — is post-mortem
(`DapManager.isPostMortem`): Continue, the steps, Run to Cursor, Jump to Line, Restart Frame and Resume Thread are
refused with `status.debug.coreCannotRun`, the Debug window disables their buttons and its state line reads *Core dump:
SIGSEGV…*, and the palette and menus grey them with that reason (`PaletteContext.debugPostMortem`). Evaluate, watches,
the threads and Thread Dump work as at any stop. The stop selects the first frame in the program's own code — under the
project or the program's folder (`DebugCoordinator.ownFrame`) — rather than the runtime's frames that raised the signal
(Go's `runtime.raise`, libc's `raise`); that frame is the one drawn as the crash. Measured: CodeLLDB loads an ELF core
and stops with `reason: exception` and the signal (`SIGSEGV: address not mapped to object`), and answers a step with
`elf-core does not support resuming processes`; Delve stops with `can not continue execution of core process`, accepts
`next` and `continue` and stops again, lists every goroutine, and puts a pointer's fields under one unnamed child.
`CoreDumpWindowFxTest` covers the flow against `FakeDebugAdapter` (with `FakeCoreFile`, the smallest ELF core);
`CoreDumpProbeFxTest` has a C and a Go program dump their own core with `gcore` (after `prctl(PR_SET_PTRACER_ANY)`, which
Yama's `ptrace_scope` 1 needs) and opens each on the real debugger.
**Type renderers** (IntelliJ's Java Type Renderers, Visual Studio's natvis; Settings → Debugging → Type renderers,
`debugTypeRenderers`, settings 172, and the Variables menu's **Customize Rendering…**) show a value of a type as the
user's expressions say. A [`dap/TypeRenderer`](../../src/main/java/com/editora/dap/TypeRenderer.java) (a Jackson bean)
holds the type, the debug language it applies to (`""`: all), the value as an expression of `this` (or `self`) and the
children as `name = expression` lines. [`ui/TypeRenderers`](../../src/main/java/com/editora/ui/TypeRenderers.java) is
pure: `match` compares types by `DebugTypeSource.parse` (own name, generics and pointers aside; the qualifiers must agree
when both have one; a subclass is its own type — the adapter names the value's class), `substitute` puts the value's
expression in parentheses for each `this`/`self` outside string literals, `children` reads the lines, `safeToRepeat`
says whether a typed expression can be evaluated again (no call, assignment, increment, `new` or `delete`), and
`unquote` drops a string result's quotes. [`ui/TypeRendering`](../../src/main/java/com/editora/ui/TypeRendering.java),
one per session, works client-side: the coordinator's `renderedVariables` sends every `variables` answer (Variables,
the Evaluate window, the value popup) through `apply`, which evaluates each matching value's renderer with the
adapter's `evaluateName` (context `watch`, the shown frame) — a value with no `evaluateName` is left as the debugger
gives it — and, when it lists children, hands the row a reference of Editora's own (from 1 900 000 000 up, above what
adapters number) answered by `children`: each expression evaluated, rendered in turn, then `[Raw View]` with the
debugger's own reference. A failed value expression keeps the debugger's value; a failed child shows the adapter's
message. `renderedEvaluate` does the same for a watch, a hover or an evaluation, only when `safeToRepeat`. The
references are dropped on resume and on a refresh. Set Value on a renderer's child assigns its expression
(`DapManager.assignValue`), there being no container to send `setVariable` to. **Mute Type Renderers**
(`debug.muteRenderers`, the Variables menu) shows every value as the debugger gives it. Measured
(`TypeRenderersProbeFxTest`, a Money with an Owner and a list of two): java-debug names a nested class `Money`
(`Main$Money` parses the same) and its list element `items.get(0)`; debugpy gives `items[0]`; Delve gives locals no
type — the value leads with it (`main.Money {Amount: 12, …}`), which `DebugTypeSource` now reads, as it read js-debug's
`Point {x: 1}` — and cannot join strings, so its renderer shows one field. `TypeRenderersWindowFxTest` covers the
window against `FakeDebugAdapter` (`evaluateTypes` types an evaluation), `TypeRenderersEditorFxTest` the Settings list.
The **exception card** ([`ui/DebugExceptionCard`](../../src/main/java/com/editora/ui/DebugExceptionCard.java)) opens
under the line that threw once `exceptionInfo` has answered (`onExceptionInfo`) and that frame is the one painted
(`paintedFrame.id() == exceptionFrameId`), once per stop by itself and again through `debug.showException`. It is a
`PopupControl` owned by the window that never takes focus: auto-hide does not consume the click that closes it, an Esc
filter on the editor closes it, and it follows the line on scroll (`estimatedScrollY`) and closes when the line leaves
the screen. A line drawn only a pulse or two after the stop (a file just opened) is waited for, at most ten times
40 ms. `DapModels.ExceptionInfo` now carries the adapter's `breakMode` (java-debug: `userUnhandled` for uncaught,
`always` for caught), `details.stackTrace` and `details.innerException` (as `causes`, at most eight deep). java-debug
sends only `exceptionId` and its `toString()` as a quoted string value, so the card strips the quotes and the type in
front. **Don’t Stop on Caught Exceptions** (a caught exception, with the caught toggle on) turns that toggle off.
The opt-in `ExceptionCardProbeFxTest` checks a caught then an uncaught exception against java-debug.
**Attach to Remote Program** (`debug.attachRemote`; [`ui/RemoteAttachLaunch`](../../src/main/java/com/editora/ui/RemoteAttachLaunch.java),
[`dap/RemoteNativeAttach`](../../src/main/java/com/editora/dap/RemoteNativeAttach.java)) attaches to a native program
under a debug server elsewhere (`gdbserver host:port program`, `lldb-server gdbserver`), with a local copy for its
symbols: GDB's DAP `attach` with `program` and `target` when C/C++ is set to GDB, else CodeLLDB's `launch` with
`targetCreateCommands: ["target create <program>"]` and `processCreateCommands: ["gdb-remote host:port"]` (Rust always
CodeLLDB). It asks for the address (`RemoteNativeAttach.address`: a port alone is `localhost`) and the program (relative
to the project; checked off the FX thread), both kept for the next time in the window, and starts beside a running
session (`sessionForLaunch`). launch.json: a `gdb` attach keeps its `target`, and `cppdbg` with
`miDebuggerServerAddress` is that attach (GDB, or CodeLLDB with `MIMode: lldb`). Probed with gdbserver 16.3: the server
holds the program before its first instruction, which GDB reports as a stop with reason `attach` and CodeLLDB as a
`SIGTRAP` exception (sometimes twice) in the loader; `DapManager.passesRemoteEntry` resumes stops of a remote attach
(`RemoteNativeAttach.isRemote`, set in `startExternal`) until one is at a breakpoint or in code with a source, so the
program runs on to its breakpoints as a launched one does. Stop ends the program, and gdbserver with it.
`RemoteNativeAttachProbeFxTest` drives GDB, CodeLLDB and a `cppdbg` configuration against a real gdbserver. Delve's
headless server (`go` with `mode: remote`) is not covered yet.

**Attach to Process** (`debug.attachToProcess`; [`dap/LocalProcesses`](../../src/main/java/com/editora/dap/LocalProcesses.java))
reads every process's command line (`ProcessHandle.Info`; on Windows, where other processes' arguments are not given
that way, `Win32_Process` through PowerShell) and lists the Java, Node.js and Python ones: a JVM whose
`-agentlib:jdwp` / `-Xrunjdwp` has `server=y` and a fixed `address` (a wildcard host is this machine; `server=n` or
`address=0` cannot be attached to), a Node.js with `--inspect`, `--inspect-brk` or `--inspect-wait` (default 9229;
`--inspect-port` alone does not listen), and a Python under `-m debugpy --listen` (or debugpy run by its folder,
`python …/debugpy --listen`). debugpy's own helpers are left out (`debugpyPart`): `--listen` starts its adapter as
`python …/debugpy/adapter --for-server …`, which would otherwise be listed as a Python not started for debugging, and
an editor's `-m debugpy.adapter` / `debugpy.launcher` are not programs either. A JVM is attached through the
ordinary Java attach (`attachTo`, from the active Java tab or the first open one, and remembered for Reattach), a
Node.js through `startJsAttach`, and a Python through the new `DapManager.startPythonAttach`: `debugpy --listen` runs
debugpy's own adapter on that port, so the session connects to it over the socket (`DapClient.connect`) and sends
`attach` — no local debugpy is needed. Every attach session ends with `terminateDebuggee: false`
(`DapClient.setTerminateOnDisconnect`, inherited by js-debug's child sessions): debugpy and vscode-js-debug (1.117)
both end an attached program on `true`, which made Stop on a Node.js attach kill the program; java-debug ignores it. The opt-in
`AttachToProcessProbeFxTest` attaches to a real JVM, Python and Node.js; each test skips without its tools.
**Attach to Process** (`debug.attachToProcess`; [`dap/LocalProcesses`](../../src/main/java/com/editora/dap/LocalProcesses.java))
reads every process's command line (`ProcessHandle.Info`; on Windows, where other processes' arguments are not given
that way, `Win32_Process` through PowerShell) and lists the Java, Node.js and Python ones: a JVM whose
`-agentlib:jdwp` / `-Xrunjdwp` has `server=y` and a fixed `address` (a wildcard host is this machine; `server=n` or
`address=0` cannot be attached to), a Node.js with `--inspect`, `--inspect-brk` or `--inspect-wait` (default 9229;
`--inspect-port` alone does not listen), and a Python under `-m debugpy --listen` (or debugpy run by its folder,
`python …/debugpy --listen`). debugpy's own helpers are left out (`debugpyPart`): `--listen` starts its adapter as
`python …/debugpy/adapter --for-server …`, which would otherwise be listed as a Python not started for debugging, and
an editor's `-m debugpy.adapter` / `debugpy.launcher` are not programs either. A JVM is attached through the
ordinary Java attach (`attachTo`, from the active Java tab or the first open one, and remembered for Reattach), a
Node.js through `startJsAttach`, and a Python through the new `DapManager.startPythonAttach`: `debugpy --listen` runs
debugpy's own adapter on that port, so the session connects to it over the socket (`DapClient.connect`) and sends
`attach` — no local debugpy is needed. Every attach session ends with `terminateDebuggee: false`
(`DapClient.setTerminateOnDisconnect`, inherited by js-debug's child sessions): debugpy and vscode-js-debug (1.117)
both end an attached program on `true`, which made Stop on a Node.js attach kill the program; java-debug ignores it. The opt-in
`AttachToProcessProbeFxTest` attaches to a real JVM, Python and Node.js; each test skips without its tools.
Restart goes
through `DebugCoordinator.restart()`, which repeats the coordinator-level start (save, before-launch
build, closed-file breakpoints) rather than only the adapter launch. `debug.start` in a JUnit test file that
has no `main` of its own debugs the test at the caret, or the class when the caret is outside a test, through
the gutter's Debug Test path ([`ui/DebugTestRoute`](../../src/main/java/com/editora/ui/DebugTestRoute.java),
review A102). A session `attachToPort` opened on a
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
`debug.toggleProgramConsole`, Settings ▸ Debugging ▸ Debugged programs) a Java **launch** instead says
`console: integratedTerminal` (`LaunchConfig.inClientConsole`) on a `DapClient` that offered
`supportsRunInTerminalRequest` (`setRunsDebuggee`). The adapter then sends the `runInTerminal` reverse
request — an argv array (the `java` executable, `-agentlib:jdwp=…server=n…address=localhost:<port>`, class
path, main class, arguments), the `cwd`, and the launch's own `env` — and `DapManager.startDebuggee` starts
exactly that through [`dap/Debuggee`](../../src/main/java/com/editora/dap/Debuggee.java), a per-session
`RunService`: the user's environment with the augmented PATH and the launch's variables on top (what Run
gives a program; not jdtls's environment), tracked by `ProcessRegistry`, stdout/stderr through the Run
console's bounded pump into `Listener.onOutput` as `stdout`/`stderr`. With `debugProgramInTerminal` on,
`DapManager` holds a `ProgramTerminal` and `Debuggee` starts the same command in a Terminal tab instead: a real
terminal, whose tab owns the output and input (no Debug-console input then; the console's first line names
the tab, review E106), while its exit and Stop behave as
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
`translated_line`) moves the breakpoint there.

A *temporary* breakpoint (`Breakpoint.temporary`, "Remove once hit") reaches the adapter as an ordinary one;
`DebugCoordinator.removeTemporaryBreakpointHit` removes it at a stop whose reason is `breakpoint` on its line, which
re-sends that file's breakpoints without it. A logpoint is never removed (it never stops). A root session that only starts child sessions (js-debug)
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
`lspManager`/`LspCoordinator` split. The coordinator holds it as the first session of
[`ui/DebugSessions`](../../src/main/java/com/editora/ui/DebugSessions.java). Each
[`ui/DebugSession`](../../src/main/java/com/editora/ui/DebugSession.java) has its manager, its own `DebugPanel`
(console, call stack, variables, Debug History) and what the coordinator keeps about it: the launch in progress, its
relaunch, the breakpoints sent to it and its answers, the stop shown, the exception card's stop, the Memory view's
address, recording-breakpoint stops, code replace, the text its files were launched from.

**Several sessions.** F5 or Debug File in another file while a session runs asks *Debug Both*, *Stop and Debug* or
Cancel (`askRetarget`; Debug Both beside a session that has started — one still getting ready is replaced or kept, as
before). Debug Both makes a session on `DapManager.newSession()` — the same `DapSettings` — and focuses it
(`openSession`). The same file asked again is not debugged twice: the session debugging it is shown and says so.
`s()` is the session being handled: `inSession(session, …)` sets it for the duration of a call, and otherwise it is
the focused one. Everything a session's manager hands the FX thread runs as that session's: its listener and its
panel's `Actions` are proxies that wrap each call in `inSession` (`inSessionProxy`), and `DapManager.fx()` runs every
reply through `setCallbackWrapper`. Launch steps that finish later carry the session that started them —
`BeforeLaunchStep.setContext`, `later()`/`laterIn()`, `startOffFx`, `asThisSession` for the jdtls build, and
`planOffFx` for every plan read off the FX thread (launch.json and tasks.json reads included), and `bound`
for what runs after a save (`savedOrThen`), the Run scan (`whenRunScanned`) or a text scan (`scan`): each runs as
the session that asked, and not at all once that session is gone — so a tab clicked
meanwhile, or a compound's next member focused before the first one's plan lands, does not launch into the wrong
session. `DebugFacts` refuses a second look only for the same session, and a look that fails says why (no endless retry).
A launch still saving or planning counts as getting ready (`DebugSession.stepsPending`, at most 15 s): F5 again says
it is starting instead of launching twice. A Debug Test run's own attaches (`attachTestJvm`) go to
the session waiting for them (`attachWaiter`), whichever is focused, and a refused one releases it; each session keeps its own queued JVMs (`queuedAttaches`)
and its own closed-file breakpoints (`DebugSession.closedBreakpoints`), and an edit to a closed file's breakpoints
(Breakpoints list, Map card, import) is sent through `sendClosed` as a session start would: re-anchored, triggered ones
held back. Debug support off, a file opened mid-session and hot code replace on save act on every session; Thread
Dump and Parallel Stacks keep the session their stacks came from (`DebugStacks`). Only the focused session (`shown()`) marks the editor
(execution line, inline values, exception card, breakpoint states, pins) and the status bar's debug state; a session
that stops, other than on a recording breakpoint, is focused (`focusSession`), which takes the last one's marks off
the editor and puts the new one's on. Breakpoint changes, Mute, hex values, exception filters and member breakpoints
go to every session. The tool window's content is `DebugSessionsView`: the focused session's panel under a strip of
tabs, shown with two or more sessions — each its file and state, a × on one that has ended, Stop All while two are
live (`debug.stopAll`). An ended session keeps its tab and output until closed; past `DebugSessions.MAX_SESSIONS` a
new one takes the place of the oldest ended one. `MainController.focusedDap()` is what the palette, Jump to Line and
the test runner ask. `SeveralDebugSessionsWindowFxTest` drives two sessions on the fake adapter;
`SeveralSessionsProbeFxTest` two debugpy sessions.

**Compounds.** A compound starts several configurations together, each in a session of its own, in the order it
lists them: a launch.json `compounds` entry (`LaunchJson.Compound`: `configurations` by name or `{name, folder}`,
`stopAll`, `preLaunchTask`), listed in *Debug: Start Configuration* beside the configurations, or one of the project's
own [`config/RunCompound`](../../src/main/java/com/editora/config/RunCompound.java)s of saved run configurations
(workspace state `runCompounds`, schema 32), made in `DebugCompoundsWindow` (*Debug: Edit Compounds…*). *Debug:
Start Compound…* lists both. `runLaunchCompound` asks the `${input:…}` its configurations use (each once), runs the
compound's `preLaunchTask` in the first session, then starts each configuration in its session
(`sessionForLaunch` for the first: the focused session when it is free; `openSession` for the rest), with its own
`preLaunchTask`; `debugRunCompound` does the same through `debugConfig`. A configuration named that is not there
starts none. Each session joins a `DebugCompound`; with `stopAll`, `stop()` in one stops every session that still runs
that compound's launch (`DebugCompound.holds`: its `runtimeGeneration` has not moved past that launch's end), while
Stop All and a session that ends by itself stop only what they stop. Each session has its own `BeforeLaunchStep` and
`RunService` (`beforeLaunch()`), so the sessions of a compound build at the same time. A launch.json configuration
started while a session runs goes beside it (`sessionForLaunch`), as in VS Code. jdtls's answers to a manager
(`executeCommand`, `buildJavaWorkspace`) come back through its callback wrapper too, so a Java launch that resolves
its main class after another session was focused still starts in its own. `LaunchCompoundWindowFxTest` and
`DebugCoordinatorCommandsFxTest` drive compounds on fake adapters, `SeveralSessionsProbeFxTest` on debugpy.

**Python subprocesses.** A local Python launch sets debugpy's `subProcess` when Settings → Debugging → Python →
*Debug Python subprocesses* is on (`Settings.debugPythonSubprocesses`, schema 167; `DapSettings.pythonSubprocesses`),
and its stdio `DapClient` gets `setChildSessions`, which makes it declare `supportsStartDebuggingRequest`. debugpy
then asks for each subprocess with `startDebugging`, naming the port of its own client listener and the child's
`subProcessId`. The client hands that configuration up instead of opening a child connection itself
(`DapManager.Listener.onChildSession`); the coordinator opens a session for it beside its parent
(`openChildSession`, `DebugSession.parent`, named as debugpy names it, "Subprocess 1234"), whose manager attaches
to that port with the configuration (`DapManager.startPythonChild`; Stop detaches). A subprocess's client hands its
own subprocesses up the same way, so `multiprocessing`'s fork server (Python 3.14's default start method on Linux),
which is itself a subprocess and forks the workers, gets a session, and so does each worker under it: folded into the
fork server's connection, as the first version did, the worker was no session of its own and its breakpoints showed in
none. It is shown when it stops, like
any other session; Restart is refused there (`SUBPROCESS`); stopping its parent, or its parent ending, stops it
(`stopChildren`); its tab goes once it has ended, unless it is the one shown. A launch on a host keeps `subProcess`
off. js-debug's children stay inside their session (`DapClient`'s root owns them), where the Threads view and the
call stack already show each process's threads. `PythonSubprocessSessionsWindowFxTest` drives a child session on
the fake adapter; `SeveralSessionsProbeFxTest#aPythonSubprocess…` a `multiprocessing` child on debugpy.

**Python web apps** (PyCharm's Django Server, Flask Server and FastAPI configurations; VS Code's `django`, `flask` and
`fastapi` launch templates). [`run/PythonWebApps`](../../src/main/java/com/editora/run/PythonWebApps.java) (pure)
knows a web app's file: Django's `manage.py` (one that names Django), and a file that makes a Flask or FastAPI app at
module level (`app = Flask(__name__)`, `api: FastAPI = fastapi.FastAPI(…)`). Run File and Debug File start its
development server: `manage.py runserver` when the file has no program arguments (on its own it prints its help;
with some, such as `migrate`, it runs them as before), `python -m flask --app module:app run --reload` and
`python -m uvicorn module:app --reload` for a Flask or FastAPI app's file that does not start a server itself under
`if __name__ == "__main__"` (on its own it defines the app and exits), its program arguments after the server's own.
The module is named from the first folder up without an `__init__.py`, where the server runs, so a package's relative
imports work. Run plans it as a module run (`RunCoordinator.runPythonServer`, `FilePlan.web`, read off the FX thread);
Debug in `DapManager.webOrScript`, where the launch is shaped.

Each of these servers reloads by running the app in a child process (Django's autoreloader, Werkzeug's, uvicorn's
`--reload`): the root process only watches the files, so a launch that did not follow subprocesses never stopped in a
view. A local launch that serves a web app (`DapManager.servesWeb`: its target `python -m` a server module — `flask`,
`uvicorn`, `fastapi`, `hypercorn`, `gunicorn`, `django`, `daphne`, `granian` — or `manage.py`; a launch.json
configuration's `module` or `program` one; or the file a web app that starts its own server, `app.run(debug=True)`)
sets `subProcess` whatever *Debug Python subprocesses* says, so each server process is a session of its own
("Subprocess 1234", above): a breakpoint in a view stops, and after an edit the reloader's new process stops too. A
launch on a host cannot follow its children, so there the reloader is turned off instead
(`PythonWebApps.withoutReload`: `runserver --noreload`, `run --no-reload`, uvicorn without `--reload`). Probed with
debugpy 1.8, Django 6.1, Flask 3.1 and uvicorn 0.54. Template breakpoints (below) are on by themselves in such a
project.

Python run configurations (a script, or a module) debug too ([`ui/PythonDebugLaunch`](../../src/main/java/com/editora/ui/PythonDebugLaunch.java)),
as Run runs them: the target, program arguments, working directory, the configuration's Interpreter and Interpreter
options and variables (`DapManager.planProgram` keeps a request's `configured`, `interpreterOptions` and `userEnv`),
after the before-launch step; a module's session is anchored in its folder, where its venv is looked for
(`DapManager.startPythonModule`, `startPythonScript`). Add and Save as Run Configuration propose a web app's server
for its file (`RunConfigDefaults.forWebApp`): `manage.py` with `runserver`, or a Python module configuration of
`flask` or `uvicorn` in the folder the app's module is named from. `PythonWebAppsTest` and `PythonWebLaunchTest`
cover detection and the targets; `PythonRunWindowFxTest#aWebAppsFileRunsItsDevelopmentServer` Run File with a
stand-in uvicorn; `PythonWebProbeFxTest` (opt-in, a venv with the four packages: `-Dweb.venv`, by default
`~/.cache/editora/web-venv`) stops in a Django, a Flask and a FastAPI view before and after a reload, and in a FastAPI
view debugged from a saved configuration.

The Variables tree marks a value that changed since the previous stop
([`ui/DebugValueChanges`](../../src/main/java/com/editora/ui/DebugValueChanges.java)). Values are recorded as rows
reach the tree (`DebugPanel.noteValue`: loaded children, evaluated watches, rows loaded on a click — only rows of the tree
shown, so a late answer to the previous stop's request records nothing), keyed by their
path of row names and by frame (thread, method, file, depth from the bottom of the stack), and a new stop moves this
stop's values into the set the next one is compared with — so re-selecting a frame or reloading after Set Value
within a stop keeps the marks. Placeholders, failed watches and rows not loaded yet are never compared; each frame
keeps at most 5,000 rows and 32 frames are remembered.

The Variables filter narrows each scope (not Watches) to the rows whose name contains its text: `DebugPanel.append`
keeps every loaded row of a scope in `scopeRows` and adds only the matching ones, and `applyFilter` rebuilds each
scope's children from that list when the text changes (a trailing "Show more" row stays). *Go to Index…* on an open
container with more than one page reloads its children from the typed index through the same paging (`goToIndex`).

Attach keeps the `host:port` addresses it reached per project, most recent first, at most eight
(`WorkspaceState.debugAttachTargets`, v30); Attach offers them in a picker, *Debug: Reattach* uses the first.

---

## Installed versions: pins, receipts and floors

Editora installs some servers and tools at an **exact release** (jdtls, vscode-js-debug, lemminx-maven, gls,
Metals, JetBrains' Kotlin LSP, clojure-lsp, lemminx, ShellCheck, shfmt): the version constants in `InstallCatalog`,
listed with their steps in `install/PinnedVersions`. Every other installer takes whatever is latest (npm, pip, `go
install …@latest`, rustup, the GitHub `releases/latest` archives).

- **bash-language-server** is the one npm global installed at a version (`npm install -g
  bash-language-server@<BASH_LS_VERSION>`; the step id stays the bare package name). It is not in `PinnedVersions`
  (an npm global has no folder for a receipt): `InstallCatalog.nodeFloor` makes `InstallCoordinator.installServer`
  ask `node --version` first and refuse below `VersionFloor.NODE_BASH_LS` (npm only warns about `engines`, and the
  server then dies at start); Doctor reads the found server's `package.json` (`DoctorRules.npmPackageVersion`, for
  every npm server via `InstallCatalog.npmPackage`) and warns with Update below `VersionFloor.BASH_LANGUAGE_SERVER`;
  `LatestVersions` does not look it up. After the server, the install fetches whichever of ShellCheck and shfmt is
  missing (`InstallCoordinator.toolsAfterServer`); the editor's banner offers just the tools when only they are
  missing (`InstallPromptCoordinator.offerShellTools`). Doctor also warns when a server's launcher is a Node.js script
  whose `node` is not found (`DoctorRules.launcherNode`), and when this window gave up on a server that said nothing
  (a blank `LspCoordinator.serverTrouble` detail); those rows offer Restart Language Servers
  (`DoctorCheck.restartable`).

- **lemminx (XML).** vscode-xml's native build (`LEMMINX_VERSION`, which ships `LEMMINX_SERVER_VERSION`), one zip
  per platform: the zip is checked against GitHub's SHA-256 for the asset and the program inside against the
  release's own `lemminx-<platform>.sha256` (`PinnedAsset.member`/`memberSha256`), installed as plain `lemminx` in
  `plugins/lsp/xml`, and asked to answer `initialize` (`JvmLspSmoke.initializeCheck`) before it is swapped in — it
  needs glibc 2.34. One that cannot run installs nothing; when the pom.xml server's bundle is complete the XML
  command becomes its Java lemminx instead (`InstallService.javaLemminx`): lemminx's own jars from that bundle,
  named one by one (`XmlServerInstall.PLAIN_LEMMINX_ARTIFACTS`), never the folder's `*` — lemminx-maven is a
  ServiceLoader extension and would join the plain server, resolving untrusted poms. `LemminxMavenGuard` is the
  start backstop: an `xml` command whose classpath holds lemminx-maven is refused (`StartRefusal.XML_LOADS_MAVEN`).
  The installer leaves the XML command blank: `XmlServerInstall.effectiveCommand` makes a blank (or bare `lemminx`, or a stale path into that folder)
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
command/enable pair; adding a DAP adapter is one `DebugLanguage` profile in `DebugLanguages` (an `EXTERNAL` one
when the adapter is its own program, started by `DapManager.startExternal`), plus a `DebugAdapterSettings` binding
when it has settings of its own.

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
  while its window is closed (it catches up on show); a row's context menu is built when it is asked for
  (`LazyContextMenu.install`), not on each cell update. The build folder a Java publish's non-project check
  needs is memoized per server root and folder (`LspCoordinator.buildFolders`, a `perf/Looked`).
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

## Commands behind the rows and buttons

The debugger's secondary windows and popups have commands of their own (`PanelCommands.DEBUG`, `DEBUG_WINDOWS`):
`debug.pinShownValue`, `debug.evaluateHistory`, `debug.copyDump`, `debug.openDumpAsText`, `debug.openTableAsCsv`,
`debug.refreshTable`, `debug.copyException`, `debug.toggleThreadsView`, `debug.showConsoleArea`,
`debug.switchSession` and the Application Log's `debugLog.refresh`, `debugLog.copyAll`, `debugLog.clear`,
`debugLog.export`. The Evaluate window's and the value popup's Copy Value, Copy Name and Add to Watches rows are the
Variables view's commands (`debug.copySelectedValue`…), which act on the row selected there while it has the
keyboard. The gutter's Stop on the Line and a method breakpoint's Enable / Disable and Remove rows are
`debug.toggleStopOnLine`, `debug.toggleMarkedMembersEnabled` and `debug.removeMarkedMembers`, on the caret's line.
