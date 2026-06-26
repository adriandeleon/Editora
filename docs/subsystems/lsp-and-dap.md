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
`@JsonNotification("language/status")` DTO, which under `mvn javafx:run` runs in the unnamed module)
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

It ships **twenty-one** servers (the `ServerDef` enum). A few examples:

| Server id | Default command | Root markers (nearest-first) |
| --- | --- | --- |
| `java` | `jdtls` | `pom.xml`, `build.gradle`, …, `.git` |
| `typescript` | `typescript-language-server --stdio` | `tsconfig.json`, `jsconfig.json`, `package.json`, `.git` |
| `python` | `pyright-langserver --stdio` | `pyproject.toml`, `setup.py`, …, `.git` |
| `go` | `gopls` | `go.mod`, `go.work`, `.git` |
| `rust` | `rust-analyzer` | `Cargo.toml`, `.git` |
| `clangd` | `clangd` | `compile_commands.json`, `CMakeLists.txt`, …, `.git` |

The rest cover XML (lemminx), JSON, Bash, YAML, PHP, Ruby, HTML, CSS, Kotlin, Lua, Dockerfile, SQL,
Terraform, TOML, and C#. Commands are user-configurable (Settings) and **never bundled** — servers
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

### `LanguageServerSession`: one process over stdio

[`lsp/LanguageServerSession`](../../src/main/java/com/editora/lsp/LanguageServerSession.java) drives
one external server process for a single root over stdio, via an lsp4j `LSPLauncher` on a daemon
executor. It implements `LanguageClient`, so it receives `publishDiagnostics`/log/show-message.

- **Launch** reuses `ProcessRunner.resolveExecutable`/`applyStandardEnv` (the GUI-launch PATH fix, so
  a Finder-launched `.app` finds `jdtls`), then registers the process with `ProcessRegistry.track`.
- **Handshake**: `initialize` (client capabilities + workspace folder + optional
  `initializationOptions`) → on success cache the server `ServerCapabilities`, send `initialized`,
  push default configuration (enables Pyright auto-imports), then flush queued requests.
- **Queue-until-initialized**: `whenReady(action)` runs an action now if initialized, else parks it in
  `pending` to flush when `initialize` resolves. So a document open issued before the handshake
  completes is held rather than dropped.
- **Document sync** is full-text (`didOpen`/`didChange`/`didSave`/`didClose`), with per-uri versions.
  `didChange` is skipped entirely when the server explicitly negotiated `TextDocumentSyncKind.None`.
- **Requests** are a subset: completion (+ `resolveCompletionItem` for auto-import additional edits),
  hover, definition, references, document/range formatting, document symbols, pull diagnostics,
  semantic tokens (range or full), and `executeCommand` (used by the DAP layer to drive jdtls).
- **stderr must be drained.** A daemon thread (`drainStderr`) reads the server's stderr to EOF and
  logs the first 200 lines to the Debug Log. An undrained PIPE fills its ~64 KB OS buffer on a chatty
  server (jdtls logs heavily) and the server blocks mid-startup, deadlocking the handshake. Capturing
  it (rather than `Redirect.DISCARD`ing) surfaces *why* a launch failed — missing JDK, a lock, a bad
  command — which would otherwise be invisible in a packaged build.
- **Dispose** sends `shutdown`+`exit` (best-effort) then `ProcessRegistry.killTree(process)`. Killing
  only the launcher would orphan the real server: jdtls is a wrapper script (Homebrew `jdtls` → python
  → java), and the orphaned JVM keeps its Eclipse workspace `.lock`, blocking the next session for that
  root.

### Per-jdtls workspace

jdtls deadlocks on its single shared default workspace's `.lock` when two roots — or a leaked previous
run — contend for it, so `initialize` never resolves (loading bar spins forever, dead completion).
`LspManager` gives each root a dedicated Eclipse workspace by appending `-data <dir>` to the launch
command. The dir is `jdtlsWorkspaceBase / workspaceDirName(root)`, where `workspaceDirName` is a stable
truncated SHA-256 of the root's absolute path (pure, unit-tested). `withDataDir` is a no-op if the
user's configured command already specifies `-data`. The workspace persists across sessions so jdtls's
index is reused.

### Diagnostics → overlay, stripe, minimap, Problems

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

The Problems map is **scoped to open files**: a server publishes diagnostics project-wide (jdtls
especially), so the controller drops any file without an open tab. The open-tab lookup matches by
**canonical (symlink-resolved) path** (`MainController.canonicalPath` via `Path.toRealPath`) — a server
reports diagnostics under the file's real URI (`/private/tmp/…` for a `/tmp/…` symlink on macOS), so
plain `normalize()` matching silently dropped every diagnostic.

### Completion, symbols, and the loading bar

- **Completion** fires on the server's advertised trigger characters, not just `.` — `triggerCharsOf`
  reads `completionProvider.triggerCharacters` from the cached capabilities (so `<` triggers HTML, `:`
  triggers CSS). [`lsp/CompletionMapper`](../../src/main/java/com/editora/lsp/CompletionMapper.java) is
  the only place that touches lsp4j's `CompletionItemKind`/tags, mapping items into the editor's
  `Completion` popup entries (icon, detail, sort/preselect, deprecated flag, and the raw item as an
  opaque resolve token for the doc popup).
- **Structure outline** comes from `textDocument/documentSymbol`, mapped by
  [`lsp/DocumentSymbolMapper`](../../src/main/java/com/editora/lsp/DocumentSymbolMapper.java) into the
  neutral `SymbolNode` tree (handling both the hierarchical `DocumentSymbol` and legacy flat
  `SymbolInformation` forms).
- **Loading bar**: the status bar shows an indeterminate bar while a server starts. It is cleared when
  `initialize` resolves — `LanguageServerSession` emits a synthetic `onStatus("ServiceReady", null)` on
  success / `("Error", null)` on failure. This is universal across every server (and a clean file that
  never publishes a diagnostic); earlier the bar only stopped on an incoming diagnostic or jdtls's
  vendor-specific `language/status` notification, leaving non-jdtls servers spinning forever.

Capability gating throughout reads the cached `ServerCapabilities` through pure, null-safe predicates
(`formattingProvider`, `rangeFormattingProvider`, `documentSymbolProvider`, `semanticTokensProvider`,
`triggerCharsOf`), so a feature is offered only when the server advertises it.

### LspCoordinator

The whole integration lives in [`ui/LspCoordinator`](../../src/main/java/com/editora/ui/LspCoordinator.java)
(the `CoordinatorHost` feature-coordinator pattern). It owns the nav/format flows, the diagnostics
routing, and the configure/detect/gating + per-buffer lifecycle. `applySupport()` (init + every
settings apply) sets the jdtls workspace base, calls `lspManager.configure(...)`, then runs per-server
detection (the package-private `SERVER_IDS` array) and gating. `wireBuffer` installs the per-buffer
hooks (didChange, pull diagnostics, semantic tokens, completion, nav actions). `LspManager` itself
stays a `MainController` field (the DAP layer and the MCP bridge read it) and is passed in.

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
2. **JVM shutdown hook** (`installShutdownHook`, from `App.main`) force-kills every tracked tree on
   exit — covering a normal quit *and* SIGTERM/`kill`/OS-quit/most crashes, the paths that bypass the
   window-close teardown.
3. **On-disk ledger** (`<configDir>/spawned-servers.txt`) + **`reapOrphans()`** (once from `App.start`,
   before any window builds): kills any server leaked by a previous run that died too hard for the hook
   (SIGKILL / power loss). The pure `LedgerEntry` parse/format + the `shouldReap` decision (reap only
   when pid **and** start-instant **and** executable all match, so a reused PID is never killed) are
   unit-tested.

[`process/ProcessRunner`](../../src/main/java/com/editora/process/ProcessRunner.java) is the only
subprocess chokepoint. For servers, `applyStandardEnv` is the relevant part: it sets `LC_ALL=C` and the
**augmented PATH**. A Finder-launched `.app` (or a `.desktop`) inherits a stripped PATH without
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

### DapManager & the java-debug bundle

[`dap/DapManager`](../../src/main/java/com/editora/dap/DapManager.java) is the UI facade (mirrors
`LspManager`+`RunService`). It owns the single active session (one debug session at a time, like Run)
and dispatches `startLaunch(file, language, picker)`:

- **java** → resolve main class (`vscode.java.resolveMainClass`, with a `javac -g` `compileAndLaunch`
  fallback for a loose file with no project) → `resolveClasspath` → `resolveJavaExecutable` →
  `startDebugSession` → connect the socket → `launch`.
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
