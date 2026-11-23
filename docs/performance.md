# Performance

Performance is a first-class constraint in Editora, not an afterthought. The editor must stay
responsive on large files, and the UI thread is sacred. This page is the contract every change
on a hot path must honor.

**Assess and report the cost of every change.** For any implementation or fix, evaluate its
effect on the hot paths (allocation per keystroke/scroll, added FX-thread work, extra
layout/CSS passes, memory) and say so in the PR — even if it's "negligible". If a change risks
a regression, measure it (e.g. temporary `System.nanoTime` instrumentation) rather than guess.

## The hot paths

Treat these as sacred — they run on every keystroke or scroll pulse:

- typing / editing
- scrolling
- syntax highlighting
- the document overlays (whitespace, indent guides, minimap, the column rulers, spell-check, search,
  TODO, lint, diagnostics)
- the line-number gutter

## The rules

### 1. Never block the JavaFX Application Thread

Tokenize/parse/search **off-thread**, then apply results back on the FX thread under a
**generation guard** so a stale result is dropped. The canonical shape — used by
`GitService`, `SearchService`, `MarkdownLintService`, the `highlightExecutor` in
`EditorBuffer`, and every other service:

```java
private final ExecutorService exec = Executors.newSingleThreadExecutor(daemon("my-feature"));
private final AtomicLong gen = new AtomicLong();

void request(String input, Consumer<Result> onResult) {
    long mine = gen.incrementAndGet();
    exec.submit(() -> {
        Result r = computeOffThread(input);          // heavy work, off the FX thread
        if (mine == gen.get()) {                       // superseded? drop it
            Platform.runLater(() -> {
                if (mine == gen.get()) onResult.accept(r);
            });
        }
    });
}
```

Later additions with the same shape: the find bar's search (`LatestOnly`), the status
bar's byte count, the Run-glyph scan (`RunScan`, whatever asks for it; the Run and Debug
commands wait with `EditorBuffer.whenRunScanned` so a command issued before the scan lands still decides
from the real scan), external-change and
recent-file checks, directory listings for Find File and the breadcrumb (`DirectoryListing`), the File
Information panel's word count and disk facts, and the spell checker's code-line scan of a prose buffer.

Console output is a special case of "don't flood FX": `process/OutputPump` delivers each drain inside an
`OutputBatch`, `ui/ConsoleAppender` applies a drain as one append, one style application and one trim, and
drains start at least 16 ms apart. A drain's carriage returns are collapsed before the console is touched: the Run
console and the build tabs write only the last non-empty `\r` segment of a chunk (`RunPanel.Carriage`), so a
progress counter is one line overwrite and one append, not two edits per segment. The Test Results tree redraws
its visible cells in place after a live update; it never calls `TreeView.refresh()`, which rebuilds every cell.
Text that is kept rather than shown is bounded the same way: a test's
captured output, failure message and stack trace keep their head and tail around a note of the cut
(`test/OutputCap`, 200,000 chars), and the agent transcript holds at most 200,000 chars per entry and
1,000,000 in all, oldest entries first (`AgentPanel`). Pipe and socket writes to language servers, debug adapters and the ACP
agent all go through `AsyncPipeWriter`, never the FX thread.

A stream that is not a process's output is paced the same way with `process/PacedDrain`: the receiving
thread adds to a buffer of its own and calls `request()`, and the FX thread drains it at once after a quiet
spell and otherwise no sooner than 16 ms after the drain before. The agent's reply (`AgentCoordinator`'s
inbox) and a Git command's progress lines (`GitConsoleLog`) go through it. An event that must come after the
stream (the end of the agent's turn, a command's result) drains the buffer first, in the same FX task, so the
pacing never reorders what the user reads.

Work started by typing waits for typing to pause when it leaves the FX thread: the debugger's completion
asks the adapter 120 ms after the last character (`DebugCompletion.ASK_PAUSE`), and the file finder reads a
directory 100 ms after the path last named a new one (`FileFinder.LIST_PAUSE`; the folder it opens on is read
at once). Filtering a list already in memory needs no pause.

A picked file is measured before it is read: Import Breakpoints refuses a file over
`BreakpointGroups.MAX_IMPORT_BYTES` by its size.

Every thread is named and a daemon: `Thread.ofPlatform().daemon().name(…)` for a platform thread, and a
virtual thread or a virtual-thread executor is given a name too (`Thread.ofVirtual().name(…)`).

The config and project stores follow the same shape (PERF-01, Pauta 6.2.0). A save made on the FX thread takes a
copy of the store (`config/Snapshots`: field by field, no JSON) and hands it to the `config-writer` thread, which
serializes, hashes, merges with another process's write (`StoreSync`) and writes it, in order per file, a later
save replacing one still queued. That covers `ConfigManager.save()`/`saveAsync()`, `SharedConfig.saveSettings()`,
the eight `save…` stores, `ProjectManager.save()` and `dictionary.txt` (the shared word set changes at once, the
file later). The FX thread never waits for the writer: `flushWrites()`/`awaitWrites()` are for tests, background
tasks (export, import, Settings sync) and the quit, where `Application.stop()` → `SharedConfig.shutdown()` makes the
last save; a wait on the FX thread before `quitting()` logs SEVERE and fails an assertion. A save made on any other
thread still waits for its write, as before. What the writer works out for the FX thread comes back through
`Platform.runLater` under a generation guard: the settings change a save carried (compared on the writer, against
what every window last applied), another process's dictionary words, a re-read `settings.json` (applied with
`Snapshots.assignFields`; a settings save made while one is under way is held until it lands), a backup taken
after a save (`SharedConfig.afterWrites`). Reads that used to happen in place also land later: a window a command
opens is built when its session is read (`WindowManager.openWhenRead`, which waits for the writes queued before it
and joins a second ask for the same window), a project's `.editora/settings.json` (`ProjectScope`, which answers the
last values it knew meanwhile, and the user's for a root never read), the agent session history, the snippet files
(warmed per buffer on `snippet-files`; a cold language answers from what is loaded), the templates
(`template-files`), the Map trails (`map-trails-io`; the menu shows a Loading… row until its listing lands), the
project's `.editora/settings.json` writes (`project-settings-write`) and the shared run configurations.
`StoresOffFxThreadFxTest` drives these paths and checks that none of their `BlockingSites` is heard on the FX thread.

The programs Editora starts follow it too (PERF-01, Pauta 6.2.0). `RunService` and `BuildService` start on a virtual
thread per start (`run-start`, `build-start`): the PATH lookup, `BusyExecutable.start` (and its retry sleep), the
container or WSL target's start and `ProcessRegistry.track`. The press returns at once; `isRunning()` is true from
it, so a second press starts nothing; a Stop, typed input or End Input meanwhile is kept and applied once the
program has started; `onStart` is queued on the pump before the first line (`OutputPump.postStart`, after which a
drop notice comes) and a failure comes back as `onError` on the FX thread. The debugged program's start
(`Debuggee.start`) answers with a future. Lower down, a ledger write asked on the FX thread is queued on the
`editora-process-reaper` thread, and so is `killTree`'s walk of the process table; `augmentedPath()` is never built on
the FX thread (a cold one answers with the inherited PATH and the extra folders, and starts the warm-up, which the
launch's config-prefetch thread also starts); `GitService.gitAvailable()` never probed answers true there and probes
on `git-version-probe`; a local Run in Terminal opens through `Factory.background`; the MCP endpoint file is written
on `mcp-endpoint`. Planning reads moved with them: a build's wrapper, trust summary and JDK environment
(`BuildCoordinator.plan` on `build-plan`, under a `planning` guard), a Java debug launch's main class, package clause,
class-file check and launch config (`java-launch-plan`), a Kotlin program's plan and jar (`RunCoordinator.offFx`,
under `locatingProgram`), Maven Update Versions' pom read, the Git Log's shallow check, a shell tab's close question
and the Project panel's confirmed delete (`project-delete`, `gio trash` included). Each names its `BlockingSites`;
`ProcessesOffFxThreadFxTest` drives Run (a Java file, a script, Stop), Run in Terminal, Maven and Gradle builds, Git,
gpg, the Doctor, Update Versions and a mapped folder's program, and `DebugProgramConsoleFxTest` a Java debug launch.
Every Run and Debug launch reads what it plans by on a thread of its own before it goes on on the FX thread: a
script configuration (`ScriptConfigPlan`, `run-config-plan`), a JVM script, a native, Go or Rust file, a Java
configuration, the build before a launch, the Maven classpath, Run File (`run-file-plan`) and the gutter's main class in
`RunCoordinator` (its `planner`, a test seam); Debug's adapters file, `launch.json` and `tasks.json`, configurations,
Go, Rust, C/C++ and C# roots, Kotlin, Debug File's lookups and Debug via Build Tool through `DebugPlanning` (`planner`,
static, a test seam). Small stats asked again and again on the FX thread answer from `perf/Looked`, a memory a plan's
look refreshes (`JavaProjectRoot.find`, `JdkToolchain.isGone`, `inheritedJavaExecutable`, `BuildBeforeLaunch.toolAt`,
`JavaLaunchPlan.Inputs`, a bare name on PATH, a build wrapper, the trust question's summary; detection warms the
wrapper and the summary for the tasks tree's header and the Settings row). A window close's question about busy
terminals answers from each local shell's watcher and the last answer of a remote host; the MCP server binds on
`mcp-start`. File commands, installs and plugins follow it too (PERF-01, Pauta 6.2.0). `perf/OffFx.then(name, work, then)` runs a
command's disk work on a virtual thread named for it and hands the answer, or the failure, back on the FX thread
(`OffFx.runnerForTest` runs it in place); the command keeps a busy flag so a second press starts nothing. That covers
the Project panel's New Folder, Rename, Move, Undo Move and the delete's look (`MoveProceed`: a rename participant
hears when the move has landed, then the tabs follow), Save As, Revert, the command-line targets, Find File's Enter,
New ▸ type, Create Test, Reopen Closed Tab, Rename File, the dictionaries, bookmarks and notes, Local History's
captures and folder view, a pasted or dropped image, the merge resolution's reopen, the Diff commands, Git's clone,
init, untracked folders and clone follow-up, the exports and Save Output, the HTML preview's site root, the Typst
export's commit (`PendingExport.take`), Run Configurations Browse, New Maven Project's follow-up, Update Versions on
a closed pom, the LSP rename look (`LspRename.RenameLook`), Move to a new package, Undo Refactoring's read of closed
files, Settings' theme and sync folders, the plugin install's commit, uninstall and Reload, and the install checks
(`InstallCoordinator.StepDisk`, `ServerDisk`). A workspace edit's disk view is a `WorkspaceFileSnapshot` taken on the
workspace worker for local paths too, and its journal's COMMIT is written there. Small facts asked again and again
answer from a `Looked` memory a look off the thread fills: whether a file may be written and whether a save needs the
administrator (looked at as the file is read), its repository (`GitPathScope.nearestRepository`,
`GitService.realPath`), Editora's ShellCheck and shfmt, `PinnedVersions.canonical`, a configured tool's path
(`DoctorProbes.resolvedPath`), a Recent Locations row, a history file being gone, the Map's remembered places, a
preview image's stamp and a rooted image path. A file chooser opens on its start folder without a look first
(`ui/ChooserStart`: JavaFX checks it, and a refused one is dropped). The emoji table and the technical dictionary are
read at launch and with the spell dictionary. The safety copy before a bulk edit in a buffer with no undo
(`HistoryCoordinator.recordSafetyCopy`) takes the text in memory and queues its write on the history worker, in
order with every later capture of the file. The edit then waits, bounded (as `SearchCoordinator.recordBeforeWrite`
does), for the durable answer, and is refused when the copy fails or is not on disk in time: a recovery copy the
edit cannot count on is no copy. The quit drains the worker. `FilesOffFxThreadFxTest` drives a representative set and checks that
none of their `BlockingSites` is heard on the FX thread.

The checks made as the user types, per buffer sync, per publish and per command follow it too (PERF-01, Pauta 6.2.0).
A memo filled off the FX thread answers them, and a look that lands later is applied under a guard; until a fact is
known the code acts as it did when the fact was absent, never hiding a row or a button. `ui/DiskFacts` is the
FX-confined memo for a handler's small facts (the New Maven Project form's folder, Find in Files' root, the Map
tooltip's attributes, the Problems window's folders, the Maven menu's pom), looked at again behind an answer older than
2 s; `perf/Looked` and `perf/ProbeCache` take a cold answer for a key nothing looked at yet, look it up behind on a
virtual thread and tell an `onLearned` listener. A buffer's server root (`ui/LspRootMemo`, keyed by project, file,
server and loose root) is looked at off the thread; `syncBuffer` waits for it and syncs the buffer when it lands,
and the other callers of `lspRootFor` treat a root not known yet as none. The Java checks around it run off the thread
too: the build folder of a published file, the module poms and their packaging, the import checks, the project's Java
style (`java-style`), the system watcher's registrations (`watchWork`), a Java script's stand-in file (written on
`java-script-shadow`, `didOpen` sent once it exists), the XML catalogs, the JDK list (`JdkChoice.jdksLater`), library
sources and a missing XML include. `.editorconfig` rules (`EditorConfig`) answer from a per-file memo the file's load
fills, looked at again behind an answer older than 3 s, and a change heard on the FX thread re-applies them. The
Markdown lint config (`markdown-lint-config`, the lint waits for the first look), Markdown and HTTP completion
(`structural-completion`, the latest ask for the same version and caret wins), the spell checker's verdicts (a word
with none yet is not squiggled, `spell-lookup`), links followed (`link-classify`, `link-anchor-line`,
`wiki-link-resolve`), the terminal's path links, console and stack-trace links, Go to Super Method's closed files, the
SSH Host field (`RemoteCoordinator.HOST_LOOK_PAUSE_MS`), a remote project's watcher start, Local History's text of a
closed file, the log follow's start from the end (measured on the follow's poll thread), Load all tasks' trust
question, Python test jumps, Don't Step Into's source, the export staging folders and a configured command with a
space (`ConfiguredCommand`: looked at as settings arrive, `onLearned` rebuilds a command line asked before) follow
the same shape. Bundled resources read after the first window are read once and kept: each keymap is parsed once
(`keymaps-warm` parses the others after start-up), `main.fxml`, the Settings controls table and the archetype catalog
are warmed on `bundled-warm`, the About icon comes from `AppIcons`, and zoomed stylesheets are built on
`ui-zoom-sheets` (a copy not made yet answers the 100% sheet, and windows are re-pointed when it lands). A bundled
font family asked for after the first frame is registered off the thread and `Fonts.whenFamiliesLoaded` re-applies
it. Settings sync writes the config folder's files on the config writer (`SharedConfig.callWithWrites`, which the sync
thread waits on, so no store saves meanwhile). A debugger stop's frame files are looked at on the adapter's reader
thread (`dap/FrameFiles`). The structured previews' print and export parse off the thread (`PreviewSnapshots.parse`),
the `.http` environment files where they are read, the inline results under a per-buffer generation, a binary diff's
blobs on the diff executor, View As JSON/XML and the data table's pages through `OffFx`, and an agent message is
encoded on the pipe writer. `ChecksOffFxThreadFxTest` drives a representative set and checks that none of their
`BlockingSites` is heard on the FX thread.

A command that needs a fact no look has filled waits for the look, never a guess and never a look in place: Run,
Build and Debug check their build wrapper, trust summary, build-before-launch tool, build files and Java project root
with `Looked.allKnown` and, when one is missing, look it up off the thread (`run-*-look`, `debug-*-look`, the command
busy meanwhile) and go on; a server command or java-debug path typed in Settings is probed off the thread on apply
(`lsp-own-copies`, `java-debug-locate`, the row says "checking"); a keymap switch waits for `KeymapManager.whenParsed`
(every bundled keymap is parsed on the config-prefetch thread); New Maven Project's Create stays disabled while its
folder is being looked at and the generation looks again before writing. A save's payload (the `.editorconfig`
transform, resolved on disk on the save worker, and the encoding) is worked out on the save worker. A diagram
preview's content hash is worked out on its render executor (`SourceKeys`); Markdown lint's Fix, Disable for Line/File
and fix-on-save work on a snapshot off the thread and apply only to the same document version; test-output lines are
parsed on the output's reader thread (`OutputPump.Sink.prepare`, `TestLineStage`, `TestParseQueue`); a `.http` buffer
is parsed per document version off the thread (`HttpParsedFiles`, `EditorBuffer.httpScriptMap`); a terminal link
click on a path not looked at yet is acted on when the look lands. Outside start-up's allowed reads and the quit, nothing
on the FX thread touches the disk, starts or waits for a process, or parses, hashes or serializes text of the user's
size.

Nothing searches, hashes or parses a document in place either (PERF-01, Pauta 6.2.0). The dirty check compares
lengths and, while the saved baseline holds its text, the text itself; against a sealed baseline the SHA-256 runs off
the thread (`EditorBuffer.textMatchesSaved`, `dirtyHash`, keyed by document version and baseline): until it lands
the tab keeps the dirty state the cheap check gives, never "clean" for text that differs, and an undo back to the saved
text clears it when the hash lands; the fingerprint a baseline is sealed with is worked out off the thread too
(`SavedBaseline` takes no lock, so the FX thread never waits on a hash). The find bar searches every document on
`find-search` (its regex compiled there), and Replace and Replace All work their edits out there on a snapshot;
Replace All applies them as one undo step only to the version they were found in, and works them out again (up to
three times) when text was typed meanwhile. Replace in Files does the same for an open buffer on its replace worker
(`SearchCoordinator.OpenSnapshot`), as does the Markdown link update after a rename (`MarkdownLinkUpdater.applyOpen`).
Shell completion scans the script once per version and caret (`shell-names` through `structural-completion`); the
Run scan runs off the thread for every caller; brace matching looks for the partner near the caret only (the screen
and one screen each way, at most `NEAR_BRACE_SCAN` = 16,384 chars) and scans the rest of its 50,000-char window off the
thread, the pair shown if text, caret and view are unchanged when it lands. A Debug's main-class parse is made with
its plan (`DebugPlanning.ownMainClass`), an HTTP run's request fingerprints as its result lands, Debug Test's Gradle
init script is waited for (`GradleDebugInit.whenPrepared`, a failed write kept), a Clojure Run's missing-tool message
reads the plan's tool, and the pasted-image check after a save searches the texts it took on the save worker.
Windows after the first are built by `MainView`, the frame `main.fxml` declares written in code (`MainViewFxTest`
keeps them equal), so no later window parses XML. A `perf/Looked` memo never looks on the FX thread: a cold key
answers its cold answer (null, "unknown", for a memo made without one, whose FX askers check `Looked.allKnown`) and is
looked at behind; at its bound it drops keys older than a minute, never one a command just had looked at.
Run's save-before-run (`RunCoordinator.saveThen`, `FileWorkflowCoordinator.saveThen`) and Debug File's save write
off the thread and go on in the save's callback, never in a nested event loop. The start-up fallback, when the
prefetch failed or `main()` was bypassed, runs `loadForFirstWindow` on a `config-prefetch` thread `start()` does not
wait for (`App.fallbackBootstrap`): that thread claims the instance lock first, before the settings are loaded or
anything is written, and the first window is built when it lands. `InPlaceWorkOffFxThreadFxTest` drives these paths
and checks that none of their `BlockingSites` is heard on the FX thread.

No save waits on the FX thread outside the quit. A command that must run on the saved file continues in the save's
callback (`FileWorkflowCoordinator.saveThen`; several files one after another through `SaveChain`): Run and its tests,
deploys and compiled-language builds, every Debug launch (`DebugCoordinator.savedOrThen` asks the command again once
the writes have landed), Git's save before a command, Diff, the language servers' re-saves and refactoring saves, Debug
via Build Tool and the close question. Its Save leaves the tab open and asks for the close again when the write has
landed (`CloseCoordinator.askCloseIfDirty`; a project delete is prepared again the same way); only the quit
(`WindowManager.confirmAndDisposeAllWindows`) still waits with `saveSynchronously`, which PERF-01 allows. A file's
personal notes show at their stored offsets and are found in the text off the thread (`NoteManager.relocateLater`,
applied only to the same edit count, worked out again otherwise); the Markdown preview's find takes its text runs on
the FX thread and searches them off it (`PreviewFind.SEARCH_SITE`); the loose-files root a window did not find made by
the launch is made off the thread (`LspManager.setLooseRoot`).

The handlers follow it too (PERF-01, Pauta 6.2.0): no listener, key or mouse handler, cell factory, timer or
`runLater` body parses, searches, diffs or encodes the user's data on the FX thread, at any size. Each moved place
names a `BlockingSites` site, works on a snapshot (text, list or immutable copy) and applies its answer only to the
same document version, list or caret. A stale answer is worked out again (up to three times) or dropped, and a
command waiting for one is busy, so a second press is refused.

- **Pickers and the index.** A picker's filter runs on `picker-search` through `ui/PickerSearch` (a `LatestOnly` per
  picker, run where `OffFx` runs a command's work). The FX thread copies the rows and their match strings and keeps
  the rows shown until the latest answer lands. An Enter (Tab, Open as Project) pressed meanwhile acts on that
  answer. This covers the command palette, every `QuickOpen` picker (Loaded Sources and Modules too), Find File, the
  Git log, the Settings search and its shortcut list, and the Structure, TODO, Bookmarks, Notes and Build Actions
  filters. Search Everywhere searches a snapshot (`SearchEverywherePopup.Ops.sources`): the commands' enabled flags
  and chords, and `IndexCoordinator.corpus()`, the index's unchanging file lists plus a `SymbolIndex.freeze()` copy
  cached until the index changes. Go to Symbol, the Map's `@name` search, Go to Related File and Go to Definition's
  project lookup search a corpus the same way. Go to Definition without a server scans the buffer through `OffFx`
  and jumps only if the buffer, version and caret are unchanged. A save's declarations are scanned on the
  `symbol-index` thread, in order with the walks. The Structure panel builds its outline off the thread from a copy
  of the text, regions and symbols, with its doc comments and Python decorators; a row is coloured from its own
  line only. The status bar's word count is counted off the thread whatever the size (`StatusWordCount.COUNT_SITE`).
- **Language servers.** A `didChange` is queued on the FX thread with only its bookkeeping done (the shadow, the
  version, the resync count). Its content changes are `lsp/LazyChangeEvents`: the compare with what the server holds
  and the prefix/suffix splice are worked out on the session's writer thread as `DeferredSyncConsumer` encodes the
  message, in wire order, so versions and order are unchanged. A text equal to the last one sent, but not the same
  string, still goes out as an empty splice, and the caller hears `onIdentical` on the FX thread. Every other request
  and notification is encoded on the writer too. The parameters of `workspace/executeCommand` and
  `workspace/didChangeConfiguration` are frozen copies (`LanguageServerSession.frozen`: collections copied, JSON
  deep-copied, nothing encoded). A resolve sends a server's item, which Editora changes only while mapping the
  answer it came in.
- **Folding and popups.** Fold regions are detected off the thread (`FoldManager.DETECT_SITE`) for every recompute
  under a generation guard. A fold command asked while a detection is in flight waits for it (`whenRegionsKnown`).
  Fold Block Comments and Markers scan on `KIND_SCAN_SITE`. A popup's Markdown (hover, signature and completion docs,
  a personal note, the Map's hover docs, the PR review pane) is parsed on `MarkdownRenderer.POPUP_PARSE_SITE`. The
  hover and a note show when the parse lands; the others show plain text first. A Markdown code fence asks the FX
  thread only for a grammar already loaded (`MarkdownRenderer.cachedGrammarForInfo`); a cold one is loaded on the
  fence highlighter's worker and the block is coloured when it lands. Line commands beside a collapsed fold
  (`FoldManager.linesAsUnits`) look only at the hidden runs next to the lines they rewrite.
- **Markdown editing.** Whether a line is code, front matter or raw HTML depends on every line above it.
  `editor/markdown/MarkdownBlockMap` keeps that map per buffer, lazily: an edit only notes its first line, and
  `MarkdownBlocks.scan` (`SCAN_SITE`) runs off the thread when typing pauses (the 150 ms settle), when a key finds the
  map behind or when someone waits for it, one scan out at a time, never once per keystroke. The keys and the list,
  heading, fence and table commands look their lines up through `MarkdownLines.withKinds`, a scoped value bound for
  that very text. Until the current version's scan lands, the map answers from the version it has: lines above every edit exactly,
  the edited lines from the first one, the lines below from their old place. The whole-document commands work on a
  snapshot through `MarkdownEditing.offFx`: the table of contents (and its update before a save), a footnote, the link
  commands, Format Document/Selection and heading numbers (`MarkdownFormatting.FORMAT_SITE`) and a paste of
  structured HTML. A Ctrl/Cmd-click's link (`LINK_AT_SITE`), the heading crumbs, the agent's streamed reply
  (`AgentPanel.PARSE_SITE`), the preview's Copy and the HTML export's check of the source go the same way. Mermaid
  completion asks the line map whether the caret is in a mermaid fence. A Mermaid fence in the preview or an agent
  reply is keyed by its surface and source line (`RenderContext.diagramKey`), so a newer render of it drops the one
  still queued for `mmdc`. While an agent reply streams, a fence not yet closed is plain code: no diagram and no
  highlighting job until it closes.
- **Editor commands.** Commands that search from the caret take the text, caret and anchor on the FX thread and
  search on a virtual thread through `editor/CaretLook`: Select All Occurrences, Add Caret at Next Occurrence, Skip
  Occurrence, Jump/Select to Matching Bracket, the sexp, defun and paragraph motions, Expand Selection, kill-sexp,
  zap-to-char, Split into Lines and the Map card's moves. The answer is acted on only if the text, caret and
  selection are unchanged. Toggle Comment works out its edit, and the reason for a refusal, off the thread
  (`EditorBuffer.COMMENT_SITE`): a selection can be the whole document, and the comment rules read the whole text in
  shell, HTML, PHP and INI. Narrow to Defun finds its bounds off the thread (`EditingCoordinator.DEFUN_SITE`). Query
  Replace finds each match and plans "!" off the thread (`QUERY_REPLACE_SITE`); while a search is out, the session
  holds the keys and acts on none of them except quitting. The JUnit test at the caret is read from a
  `JavaTestScanner.CaretIndex` that the Run scan builds with its targets. Auto-rename tag reads the edited name from
  a local window and finds the pair off the thread (`TagRenameMirror.MIRROR_SITE`). Typing `*` after `/*` looks at
  most `IndentWindow.LOOK_BELOW_CHARS` below, as Enter does. Brace matching reads only the lines on screen in place.
- **Whole-document rewrites and installs.** A rewrite never diffs on the FX thread. `EditorBuffer.PreparedEdit` is
  worked out on the caller's worker (reload, Replace in Files, the Markdown link update, CSV Align/Shrink, Convert
  Indentation, Markdown Format, MCP and ACP writes) and applied only to the version it was made for. Other callers
  use `replaceWholeDocumentLater`/`replaceVisibleTextLater` (`WHOLE_EDIT_SITE`), which drop the rewrite rather than
  overwrite text typed meanwhile. Text given to a new tab is prepared off the thread (`setContentLater`,
  `PREPARE_TEXT_SITE`; jumps wait with `whenTextInstalled`). A note inside a replaced span is followed into the
  replacement off the thread (`NoteManager.REPLACEMENT_SITE`). A window whose session no prefetch read, at launch or
  with `--single-window`, is read off the thread and built when the read lands (`WindowManager.sessionReady`,
  `SESSION_READ_SITE`).
- **Consoles, Settings, grammars and the terminal.**
  - A console's find bar searches a snapshot once typing pauses for 80 ms (`ConsoleFindBar.SEARCH_SITE`). The latest
    ask wins, and text the output changed meanwhile is searched again. While the bar is open, output that arrives
    is searched from the start of the last line not yet searched only: the matches before it are kept current as the
    console appends, trims and overwrites (`ConsoleKnownMatches`). With an empty query nothing is searched.
  - The CSV grid parses, infers its column types and measures its widths off the thread under a per-buffer
    generation (`CsvCoordinator.PARSE_SITE`), and keeps its columns when a re-parse does not change them.
  - A console hover or click reads only the line under the pointer.
  - Settings' modified markers, a row's reset and restore, and a window's re-apply compare and set property values
    through their accessors, with no JSON (`SettingsModified`, `SettingsReapply`).
  - The snippet editor and the theme preview tokenize off the thread.
  - Every bundled grammar is loaded once on `grammars-warm` after the first window and kept. An FX caller only asks
    for one already loaded (`GrammarRegistry.cachedForLanguageName`); otherwise it loads off the thread
    (`whenLanguageLoaded`).
  - The terminal's FX handlers never wait for the screen buffer's lock: they try it, and try again 4 ms later while
    the emulator writes (`TerminalView.underLockSoon`).
- **Debugger, diff and merge.**
  - The line mapping between the launched text and the document (`LaunchedLines.SITE`, a Myers diff) is worked out
    on a snapshot keyed by document version. The breakpoint send after an edit, a stop, Run to Cursor and Jump to
    Line wait for it; other callers use the last mapping, and the file is sent again when it changes.
  - `DebugTextScans` (a breakpoint line with no code, the test at the caret) runs off the thread per version.
  - The merge resolver's Save (`MergeViewerPane.SAVE_SITE`, busy meanwhile; a close-save asks for the close again
    once applied), Resolve Conflicts, Open in Diff Viewer and Apply Patch, and the diff viewer's applies parse and
    compose off the thread.
  - A diff's views are laid out where its model is made, on the diff-service thread (`DiffLayout`): the rows shown
    with context folded, each pane's text, line numbers and changed-word offsets, for both views. A patch's own line
    numbers and a pull request file's sides are worked out there too. The viewer only puts that text into its
    panes. The Result editor counts its edits instead of reading its text on every keystroke, and the merge
    resolver puts its Result together from the choices off the thread (a Save waits for it).
  - The Map Changes card's words (the latest pick wins), the History card's lines and a code card's aids and uses
    are worked out off the thread too.

- **Rendering, logging and small reads.**
  - A LaTeX formula is never parsed or rasterized on the FX thread (`MathImages.SITE`, one at a time under its
    lock). The threads that parse a document for the preview, a popup or the agent panel draw its formulas into
    the cache (`MarkdownRenderer.warmMath`), and print draws them with its other assets (`MarkdownPrintAssets`). A
    formula the FX thread finds missing shows as an empty image of its style, filled when its rendering lands (its
    source when it does not parse). JLaTeXMath's fonts are warmed on `math-warm`.
  - Copy HTML renders the markup off the thread (`PreviewMenu.COPY_HTML_SITE`) and fills the clipboard when it
    lands. A print preview's page images are decoded off the thread (`PrintService.DECODE_SITE`), and a page is
    printed once `Pages.prepare` has its images.
  - The debug expression field tokenizes off the thread once typing pauses for 120 ms
    (`DebugExpressionField.HIGHLIGHT_SITE`). The diff viewers' application mark is read once on `bundled-warm` and
    kept (`Icons.warmEditora`). A diff view whose kept layouts were dropped is laid out off the thread
    (`DiffViewerPane.LAYOUT_SITE`). Saving a Map working set compares the two views property by property, with no
    JSON.
  - The session log is written by its own thread (`DebugLog`, `debug-log-writer`): a record logged on any thread
    goes into the in-memory log and is queued, in order, for the session file and for the console, whose handlers
    the log takes over. The quit waits for it (`DebugLog.flushWrites`, from `Application.stop()` and a shutdown
    hook).
  - The status bar's size is counted off the thread whatever the size (`StatusBar.SIZE_SITE`). Import from curl
    converts the command off the thread (`CURL_IMPORT_SITE`). The start-up fallback reads the first window's fonts,
    catalog and keymap on its own thread before the window is built (`App.FALLBACK_FIRST_WINDOW_SITE`) and builds
    that window from `MainView`.

`Handlers{Index,Lsp,Markdown,Editor,Console,Debug,Render}OffFxThreadFxTest` drive these paths and check that none of their
`BlockingSites` is heard on the FX thread. `LspSyncOverWireFxTest` rebuilds the document from the changes a wired
fake server receives, versions in order.

### 2. Debounce and coalesce

Re-highlighting is debounced; overlay/ruler/minimap redraws coalesce to **one per pulse** with
a `pending` flag. Don't add per-keystroke or per-scroll-pulse work that isn't coalesced. The overlays drawn
over the text paint after the pulse's layout through `AfterLayoutPaint` (see
[Canvas overlays](#canvas-overlays)), and so does the minimap's scroll repaint, or its viewport box trails the
text by a frame on every scroll and every jump. The minimap's other repaints, the stripes and the ruler, which do
not sit on a character, use `Platform.runLater`. The coalescing shape:

```java
private boolean redrawPending;
private void scheduleRedraw() {
    if (!active || redrawPending) return;
    redrawPending = true;
    afterLayout.request(); // or Platform.runLater(() -> { redrawPending = false; redraw(); })
}
```

For `runLater`, "one per pulse" means one per event-loop turn: the `runLater` runs before the next input
event, which is normally but not always the next pulse. The toolbar's edit state (Save/Undo/Redo/Cut/Copy) follows the same
shape: a typed character fires an edit and a selection change, and `MainController.scheduleEditStateRefresh`
turns both into one refresh per turn. That refresh reads the buffer's dirty *property*, not `isDirty()`: the
exact answer can cost an O(document) compare, and the property's own listener re-runs the refresh when it
changes. `EditorBuffer.dirtyAfterEdit` decides most edits by length alone; at the saved length it compares the
text at once for a clean buffer and for a *length-changing* edit that lands there (a deletion or an undo of an
insert — the way a user gets back to the saved text, and the dip to clean is what the tab marker and the Git
gutter's reset listen for), while a *same-length* edit of a dirty buffer (move line, overwrite, transpose)
carries the flag and is compared at the 150 ms settle or when `isDirty()` is asked, so a run of them copies
nothing. Nobody on the per-key path may ask `isDirty()`. A dirty transition (clean → dirty on the first edit)
still refreshes at once through the listener; it is the per-key path that is coalesced. A true once-per-frame scheduler was prototyped and not adopted
(it raised render wait in headless scroll runs); measure on a real GPU pipeline before trying again. For a
leading-edge-plus-max-wait throttle (rather than a trailing debounce) use `ThrottlePolicy` / `FxThrottle`.

For text-driven work inside `EditorBuffer`, register a milestone with its shared
`SettledEditDispatcher` rather than adding another RichTextFX `successionEnds` subscription. One document
subscription resets one JavaFX timer sequence, which preserves each feature's delay while inactive feature
milestones are never armed. Standalone controls without that dispatcher should still debounce on their
RichTextFX stream rather than doing expensive work per change.

A stripe on the minimap follows the same rule one step further: its *data* is not recomputed per repaint
either. The Git change stripe holds an `int[]` of `{line, count, kind}` triples and asks `GitGutterLines`
for a new one only after `gitMarksChanged()` (new bars, or an edit that moved lines); a scroll re-blits the
cached content image and draws from the array it has, allocating nothing.

The minimap's *content* render (the one that ends in `canvas.snapshot()`, a forced full-scene layout) is
paced on top of its 200 ms typing-pause debounce: above 2,000 lines two edit-driven renders are at least
`lines / 10` ms apart, capped at 2 s (`MinimapPacing`), and the render after the last pause always runs.
Before it clears anything, a render plans the runs it would draw (`MinimapRows.RunList`) and compares them
with the plan behind the cached image; a pause that moved no run — a letter typed inside a word, a
style-only change that still ended a succession — skips the draw and the snapshot.

### 3. Work incrementally, and only on what's visible

- Highlighting re-tokenizes only the **edited range**: from the first edited line, on the stored grammar
  state, to the first line past the edit whose end state (and bracket depth, when bracket colours are on)
  equals what was stored for it (`HighlightPass`). A pass that is dispatched but never applied (superseded,
  or the text changed under it) still owes its range: `HighlightDirty` keeps the range, mapped through later
  edits, until a pass lands. Tokenizing, the bracket and semantic overlays and the per-line splices all run
  on the pool thread; the FX apply is one `setStyleSpans` over the restyled range.
- Lines over 20,000 characters are not tokenized and each line has a one-second budget
  (`TextMateHighlighter.MAX_TOKENIZED_LINE` / `LINE_TIMEOUT`). Passes are queued per grammar
  (`HighlightPass.submit`) so a waiting pass holds no pool thread, and `GrammarRegistry` lookups are
  lock-free; only loads are serialized.
- A grammar regex that starts with an identifier and a lookahead is tried from every position of a long
  word, each try running to the word's end: quadratic in the word's length. The TypeScript and
  TypeScriptReact grammars carry a word-start guard on those regexes (`scripts/GuardTsGrammars.java`;
  `BundledGrammarsLongWordTest` times 10,000-letter words). Measure a new grammar the same way before
  bundling it.
- A buffer with no grammar is not restyled at a typing pause; styles are cleared once, on the transition
  from "styled" to "no grammar".
- A semantic-tokens response is a style-only update: identical tokens restyle nothing, and a different
  list re-tokenizes and restyles only the lines whose tokens differ (`SemanticToken.changedLines`), with the
  overlay built off the FX thread.
- Overlays iterate just the **visible paragraphs**
  (`firstVisibleParToAllParIndex … lastVisibleParToAllParIndex`) and skip folded lines.
- The minimap draws one row per **pixel row**, not per line, once lines are less than a pixel tall
  (`MinimapRows`): the longest of the lines sharing a row stands for it, so a render reads one length per
  line and fetches, scans and draws text only per row of the column. Rows a whole pixel or taller are still
  drawn per line, which is at most a column's height of them.
- Avoid O(document) work on an edit or a scroll.
- A list the user or the data can grow is a virtualized control, which builds nodes for the rows on screen
  only (PERF-08, Pauta 6.2.0): the Settings shortcut list (a row per command; a key in its filter builds a
  row for each place on screen whose shortcut changed, not one per match, and selecting a row rebuilds that
  row and the one that was selected), the pull-request checks card and the live crontab preview are
  `ListView`s. A row with wrapping text binds its labels' maximum width to the list's, or the cell cannot
  say how tall it is (`CrontabPreview`, `MessageLogPopup`). The picture an export takes of a preview
  (`CrontabPreview.content`) is off screen and holds every row. The other structured previews (fstab,
  systemd, ssh config, Dockerfile, GitHub Actions, OpenAPI docs) share `editor/structured/PreviewList`: a
  preview is a list of row suppliers, shown as a `ListView` on screen and as a column for Print and Export.
  The HTTP client's empty state and the Check for Newer Versions dialog (`MavenUpgradeList`) are `ListView`s
  too.
- Where a virtualized control would change how a pane looks or works for nothing, the pane shows a number of
  items it names and says how many there are past them (`ui/ListCap`, "Showing the first 50 of 73."): the pom
  preview (a grid that aligns its columns; at most `PomPreview.MAX_ROWS` rows, built when shown, the filter
  finding the others), the merge view (`MergeViewerPane.PAGE_SIZE` conflict cards a page), Parallel Stacks
  (a drawn graph: `MAX_BOXES` boxes, `MAX_BOX_FRAMES` frames a box; Copy has them all), the note card, the
  Welcome page's projects and sites, and the small lists of hover cards, info cards and settings pages.
- Per keystroke, scroll and tick, other views follow the same rule (PERF-08, Pauta 6.2.0): an editable diff's
  Result counts its edits and compares with its baseline once typing pauses, a merge result compares its text
  only when a choice is made, Query Replace copies a piece of the document from its offset and searches it off
  the FX thread, a piece twice as long when the answer lies past it (`QueryReplace.piece`, `nextIn`), a snippet or
  template body is coloured once typing pauses, off the FX thread, the Markdown heading crumbs and a large Python file's indent check scan off the FX thread, and a Project Map paint
  makes and tests the boxes of the rows on screen only (`MapProjectedRows`, whose row arrays live as long as the
  world layout). A listener on a `CodeArea`'s `textProperty()` builds the whole document per change: use
  `plainTextChanges()`.
- The Project Map's world layout is cached in two steps (`MapSurface.worldLayout`): the columns (every row grouped,
  sorted and filtered) are keyed by the rows, filters and folding only, and the geometry by the zoom only through
  whether each card's controls fit, so a zoom pulse or a "Compact rows" hover sorts nothing and usually lays
  nothing out. A row's place among its column's rows (the screen-reader text, a step out of a filtered column) comes
  from `MapColumnOrders`, one column sorted once per listing; never call `ProjectMapModel.columnsById` over every
  row from a keystroke or a selection path. Label widths are kept across listings (bounded, cleared by a font change).
  The Map's problem counts are built only while it shows (diagnostics that arrive while hidden are counted on the
  show), and the dependency overlay is worked out off the FX thread (`LatestOnly`, the latest selection wins, the
  recent ones kept for revisits).
- **Never** call `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event.
- **Never** ask `getCharacterBoundsOnScreen` for an **empty** range — it allocates a blinking
  `CaretNode` whose timer is never stopped, permanently leaking a pulse receiver. Measure a
  **one-character** range instead (`getCaretBounds()` is focus-dependent and not a substitute). See
  [gotchas.md](gotchas.md#never-ask-getcharacterboundsonscreen-for-an-empty-range).
- The box returned for a one-character range is wider than the glyph advance, so take a character advance
  from the difference of two `minX` values, never from a box width (`WhitespaceOverlay`).
- The line-number gutter is rebuilt per cell by Flowless (every edited paragraph, every row scrolled into
  view), so a row allocates as little as the look allows: its padded number comes from a per-gutter
  `LineNumberText` cache (no `String.format`), its clip is sized in the row's own `layoutChildren` rather
  than through two property bindings, and the empty bookmark and chevron slots are bare `Region`s. The
  per-view scroll features (format bar, AI actions bar, sticky scroll, semantic refresh) share one
  `estimatedScrollY` listener (`installFormatBarListeners`; `ScrollListenerPolicyTest` pins it).
- Per-keystroke helpers read a bounded window, not the document: Enter/Tab/closer handling goes through
  `IndentWindow` slices, brace matching checks the two adjacent characters before building its window, and
  the find bar holds a `SearchMatches` page (at most 100,000 matches) that overlays binary-search. None of
  them may call `area.getText()`.
- Whole-document work that runs off the FX thread takes the document, not its text: `DocumentText.of(area)` (or the
  buffer's per-version `EditorBuffer.documentText()`) holds RichTextFX's immutable `ReadOnlyStyledDocument`, taken in
  O(1), and `text()` builds the String on the worker, once per handle. The highlight pass, the Run scan, fold
  detection, the TODO scan, the spell code lines, the Markdown line map, the tag-rename mirror, the caret looks, the
  structural completion offers, Toggle Comment and the HTML link lookups all work this way; a consumer that needs
  the String on the FX thread (`documentTextSnapshot()`: the LSP `didChange`, the Undo History checkpoint) reuses
  the one a worker already built for that version. The fork's lazy caches behind `getText()` are races on
  immutable values, so a worker reading the text beside an FX-thread `getText()` is safe (see `DocumentText`).
- The project watcher re-lists only directories whose entries were created or deleted and reconciles the
  rows (`ChildReconciler`); Editora's own saves are registered through `ProjectPanel.noteLocalWrite` so
  they are not treated as external changes.
- The Markdown preview rebuilds only the **blocks that changed** (`MarkdownPreview`). The document is cut
  into its top-level blocks off the FX thread, each keyed by its exact source text (plus the link
  definitions it could use and the size/mtime of the local images it shows); a render keeps every node
  whose key is unchanged, in place, and builds the rest. Its column (`BlockColumn`) remembers each block's
  height and measures only blocks that asked for layout. A 5,000-line document went from ~0.6 s of frozen
  UI per typing pause to ~10 ms. See [gotchas.md](gotchas.md#applycss-restyles-everything-below-the-node-it-is-called-on).
- The column is **virtualized** (PERF-08, Pauta 6.2.0): `BlockColumn` holds a slot per block and builds and
  puts in the scene only the blocks within `BlockColumn.OVERSCAN` of the viewport; blocks that leave it stay
  built out of the scene up to `BlockColumn.KEEP`. Heights (measured, else lines × the measured height per
  line) sit in a Fenwick tree, so a block's offset and the block at an offset are O(log n) and a scroll pulse
  does nothing in the length of the document; when measuring changes the heights above the viewport, the
  preview scrolls by the difference so the top block stays put (`onReflow`). What reads the whole document
  reads the model: Find reads a block not built from a rendering without pictures
  (`MarkdownRenderer.renderTextOnly`) and marks blocks as they enter the scene; a selection pins the blocks of
  its two ends; Copy of whole blocks copies their Markdown. Print and Export build the whole tree once per
  command in `MarkdownPrintLayout`, which is not on screen.
- Each rendered block records its **source lines**, so the split view's scroll sync, the position kept
  across a re-render and across Editor/Split/Preview switches map a source line to a preview offset
  (`MarkdownPreview.yOfLine` / `lineAtY`, `PreviewScrollSync`), not a scroll fraction.

### 4. Don't defeat the per-node CSS style cache

- Keep token rules as the compound `.text.<class>` selector (see
  [`styles/syntax.css`](../src/main/resources/com/editora/styles/syntax.css)).
- Coalesce adjacent same-style spans (`SpanMerger`) before `setStyleSpans`.
- `applyCss()` is for a small node measured before the pulse styles it (a card, a row, a label), never for
  a list, a page or the editor area, and not on every key or scroll (PERF-10, Pauta 6.2.0). To act on
  geometry the pulse has not laid out yet, wait for it (`Scene.addPostLayoutPulseListener`, once) instead of
  forcing a pass: `ToolWindowManager` finds the tab header that way, not by styling the editor area. A bar or
  a label that follows the selection or a pan is styled when it is put there or shown, not each time it
  moves (`SelectionBars`, `MarkdownFormatBar`, `MapCardLinks`). `MarkdownPreview` styles the whole preview
  in one call only when every block in it is new; otherwise the new blocks, or at the end of a sliced build
  the blocks no layout has reached. See
  [gotchas.md](gotchas.md#applycss-restyles-everything-below-the-node-it-is-called-on).

### 5. Preserve the large/huge-file guards

Highlighting, the minimap and undo are disabled at **≥ 200,000 lines** (or ≥ 16 MB, or a line of 1 MiB); the
file goes read-only with a capped load at **≥ 50 MB**, or when its document would not fit the free heap. Many
overlays check `largeFile`/`hugeFile` and no-op. Keep these guards when touching that code, and bound memory
(undo history is capped; loads are capped).

The thresholds were measured on 2026-11-22 (JavaFX 27, JDK 25, `-Xmx2g`; Editora's own Java, generated JSON,
one-line JS/JSON and log text, each feature forced on and off):

- **Responsiveness does not set the line limit.** With highlighting, the minimap and undo on, typing and scrolling
  stayed smooth at 400,000 lines (17.5 MB): key dispatch p95 under 12 ms, the edit settled in about 250 ms. What
  grows is memory and the first highlighting pass. A document keeps about 1 byte per character plus 365 per line,
  and highlighting about 450 more per line; the first pass takes about 6 s per 100,000 lines of Java (JSON is
  far cheaper), holding the grammar's lock, so every other tab of the language waits for its colours. Lines,
  not bytes, set both: 5 MB was 115,000 lines of Java but 315,000 of pretty-printed JSON.
- **The minimap costs nothing measurable** up to 200,000 lines (three runs each, on and off), so the
  line-count tier (`Settings.largeFileThreshold`, now 50,000) only stops the language server.
- **Memory is checked per line too.** `LoadHeapGuard` counts about 8 bytes per character plus 320 per line at
  the load's peak (450 more per line when highlighted). The old 15 bytes per file byte was right for 70-character
  lines but undercounted short-lined files (JSON needed 24).
- **Long lines are cheap until they wrap.** Unwrapped, a caret jump took 10–15 ms on a 64K-character line,
  about 50 ms at 256K and 100–150 ms at 1M, so the full long-line profile now starts at 1 MiB. Wrapped, opening
  one such line took 0.7 s at 8K (no spaces), 2.8 s at 16K, 11 s at 32K and 36–45 s at 64K, which is what
  `WordWrap`'s own hold (a line of 10,000 characters or an unbroken run of 2,000) already prevents.
- **50 MB stays.** 50 MB of text opened in 2–2.8 s and kept 320 MB; 100 MB took 5–8 s and 644 MB. Both stayed
  editable, but the read-only cap is the margin for a second large file.

The bench was a scratch `Application` around `EditorBuffer` (timings on the FX thread, pulse gaps from an
`AnimationTimer`, heap after GC, and the smallest `-Xmx` each file still opened in). Headless Glass and the
desktop es2 pipeline agreed. A desktop run whose window is not being presented (screen off, hidden workspace)
stalls every pulse for about 3 s, whatever the file: discard those runs.

Every text-file candidate uses a tab shell while `MainController` stats, reads, binary-sniffs, resolves
EditorConfig/the charset, and decodes on a virtual thread. File size is not a safe proxy for latency: a
tiny file can live on a cold network mount or FUSE provider. The only required FX-thread step is the final
RichTextFX insertion. The shell applies no path-dependent settings; the prepared EditorConfig result is
applied once, immediately before insertion, rather than walking parent directories on FX and then doing it
again in the loader. The paragraph split and the file hash are also done by the read worker
(`InitialDocument`, `PreparedLoad.fingerprint`), and the loaded String is shared as the saved baseline and
the first text snapshot (`DocumentSnapshots.seed`), so a freshly loaded tab holds one full-text String. A
load of 5 MB or more that will not fit the free heap takes the capped read-only path instead
(`LoadHeapGuard`: about 15× the file size plus a 128 MB margin). Every load takes this path: a session
restore's fill, and a background open on behalf of a diff apply, a workspace edit or an agent write
(`openBackgroundBufferAsync`), prepare the paragraphs on the worker too and hand the prepared EditorConfig
result to `addBuffer`, so attaching the tab never walks the ancestor directories again. Only a merge pane's
save-before-close and the workspace-edit transaction's fallback, both of which must answer synchronously,
still read on the calling thread.

A reload from disk does not swap the document: `EditorBuffer.reloadContent` rewrites only the span that
differs (as `replaceWholeDocument` does), so the paragraphs, styles and folds outside it survive and nothing
is split again on the FX thread; the worker resolves the line endings (`InitialDocument.ofText`). Like a
load it is not an undo step: the history is forgotten, so undo cannot bring back text the disk no longer
has. A reload that needs the segmented or capped profile still installs whole.

The saved baseline — what the modified flag compares the document with — is a `SavedBaseline`: the saved
text's length and `TextFingerprint` (SHA-256 of its chars, computed by the read worker at load and by the
save worker at save), plus the text itself only while it is still the snapshot. Once a dirty buffer's edits
settle the baseline is sealed and the text let go, so a modified tab holds no second copy of its document;
a later edit back to the saved length is compared by hashing the snapshot. The per-keystroke gate is
unchanged: a different length (or line ending) decides without reading the text, and a dirty buffer at the
saved length is compared at the settle, not per edit. A save acknowledges what the worker wrote by document
version, length and fingerprint (`FileWorkflowCoordinator.CommittedSave` keeps neither the bytes nor the
text); on an unchanged document the current snapshot is shared as the new baseline.

Apply the large/heavy/read-only profile before that insertion so disabled features and undo history never
observe the initial document change. Shape matters as well as byte and line counts: a paragraph at **1 MiB or
wider** enters the long-line safety profile, which forces wrapping off, uses the large-file feature guards, and
installs giant paragraphs as bounded, visually identical style segments. (Wrapping is held off much sooner, at
a line of 10,000 characters or an unbroken run of 2,000, by `WordWrap` itself: a wrapped paragraph costs about
the square of its length.) The segmentation keeps
the text and paragraph model exact while preventing JavaFX Text from laying out hundreds of thousands of
characters as one glyph node. This protects minified/generated files that evade a line-count threshold but
are pathological to lay out as one paragraph. Navigation requested against the shell must remain queued
until loading completes.

Folding's debounced document detection is also generation-guarded background work. Explicit fold
commands remain synchronous, while large-file mode skips heuristic detection entirely; server and
manual regions can still be applied without scanning the document. A consumer that wants the regions
current without a stall asks `FoldManager.recomputeAsync()` (synchronous only when the heuristic is off,
which reads no text): the Structure outline does so when it attaches to the selected tab, after building its
tree from the regions the manager already holds, and skips even that while its tool window is closed —
a tab switch scans nothing; opening the window rebuilds and asks once. A fold *restore* is always deferred to
that background detection: the saved collapsed lines are applied when the regions arrive and are computed
on demand by `collapsedStartLines()`, `unfoldContaining()` and the fold commands. The restore's scan also
stands in for the debounced pass the load's own insertion would otherwise trigger: that pass runs only when
the document's generation has moved past the one last scanned.

Whole-document consumers must use `EditorBuffer`'s versioned text snapshot rather than independently
calling RichTextFX `getText()`. The first consumer of a document version materializes one immutable String
on the FX thread; highlighting, folding, TODO scans, lint, LSP sync, previews, run-target detection, saves,
and Undo History reuse it. Every plain-text edit invalidates the buffer's cached reference synchronously,
while background work keeps its local snapshot and retains the existing generation/version guard. This
bounds the cache to one current String per buffer and avoids repeating the same O(document) copy across
independently debounced consumers after an edit settles.

Undo is bounded by text as well as by count: the queue holds 300 entries and 64 M characters
(`BudgetedChangeQueue`), the queues of all documents together share the same 64 M-character budget
(`UndoQueueBudget`: the least recently edited document gives up its oldest steps first, and no document is
taken below its newest one), and Undo History checkpoints share an app-wide 64 M-character budget
(`UndoHistoryBudget`). A closed tab forgets its queue at once. An undoable whole-document rewrite must go through
`EditorBuffer.replaceWholeDocument` / `replaceVisibleText`, which record only the differing span — never
`area.replaceText(wholeText)`. Every RichTextFX area outside `EditorBuffer` must be built through
`ui/AreaUndo.none(...)` (read-only: no history) or `AreaUndo.bounded(...)`; the default undo manager is
unlimited and records programmatic edits. `RichTextAreaUndoPolicyTest` enforces this.

Large-file mode has **no** undo, so "it is one undo step" is not a safety net there. A programmatic bulk or
whole-document edit (Replace in Files, a line transform, tool/AI/agent/plugin/LSP output) must ask
`ui/NoUndoGuard.allow(buffer, operationName)` immediately before it edits: on a no-undo buffer the guard
first stores the buffer text in Local History as a labelled revision (waiting, bounded, until it is on
disk) and tells the user, or returns false when it could not be stored in time — and then the edit must not happen.

Build `HttpClient`s through `io/LazyHttpClient`, never in a field initializer: each one starts a selector
thread when built.

### 6. Bound retained GPU textures

JavaFX's Prism texture pool has a fixed ceiling (default 512 MB); exhausting it makes the
render thread NPE on a null texture — a black window, seen only in the packaged build. So don't
let GPU-backed resources grow with the number of open files:

- A background (non-selected) tab drops its minimap snapshot and shrinks the minimap canvas to 1×1 via
  `EditorBuffer.setRenderingActive(false)`. The snapshot's *pixels* are copied to the CPU first
  (`MinimapRetention`: at most 16 tabs and `ImageCacheBudget.MINIMAP_BUDGET_BYTES`, least recently
  shown out first), so switching back writes them into a fresh image and re-blits — a texture upload, not
  a render — unless the document, colours, tab size or column size changed while the tab was hidden.
  Nothing GPU-backed is retained for a hidden tab.
- Image caches are count- and byte-bounded through `ImageCacheBudget` (Mermaid and Typst included), not
  unbounded maps — each entry pins an `Image` (a texture). The parsed-SVG cache (`SvgImages`) is heap, not
  texture, and is weighed the same way, by an estimate from its source size. Live-preview renders are kept one per surface
  and released when the buffer is disposed (`PreviewSurfaces`).
- Every per-buffer Canvas overlay implements `TabSurface` — the search-highlight, occurrence-highlight,
  inline-values and AceJump overlays included, so a find, a word highlight or a suspended debug session left
  on a hidden tab holds no viewport texture; `EditorBuffer.setRenderingActive` releases them all for a
  background tab, and an overlay with nothing to draw holds a 1×1 canvas (`CanvasGuards.fit` / `release`).
  A jump in progress ends when its tab is hidden (its labels are drawn for one viewport and its keys must
  not stay owned on a tab the user cannot see).
- A plain buffer builds only what every buffer uses: the note and spell overlays (both on by default), the
  minimap and the column rulers (a `Group` with one `Line` per ruler column, all placed from a single
  column-0 measure). The indent guides are on by default but still attached lazily, when the settings apply
  turns them on; their redraw runs after the pulse's layout and skips every frame whose picture would not
  change (a frame key of where column 0 is, the visible lines' indentation, scroll, document height, size,
  colour and folds), so typing inside a line costs one character-bounds query (column 0; about 25 µs on a
  100k-line file) and no per-line layout query. Column 0 is in the key because the gutter can widen with
  nothing scrolled or edited; the column width is measured from the most indented visible line on a repaint
  only. Every other overlay and stripe — whitespace, TODO (overlay and
  stripe, attached with the first non-empty scan result), Markdown lint (overlay and stripe), inline values,
  the LSP diagnostic stripe, search, occurrences, log, Mermaid lint, LSP squiggles, snippets, AceJump — is
  attached on first activation through `attachLazyOverlay` / `attachLazyStripe` at its `EditorBuffer.Layer`,
  which keeps one stacking order whatever a buffer turned on and in whatever sequence. The right-click menu,
  the note hover tooltip and the fold preview tooltip (each a popup window with a scene of its own) are
  built on first use, and the log viewer subscribes to edits only once something asks for its epoch.
  Measured on 30 open 31 KB Java files this takes 14 scene-graph nodes, their subscriptions and three
  popup scenes off every tab; the live heap per tab (4.3–5.7 MB either way) is the document, its style
  spans and the highlighting state, not the overlays.
- A tab that has stayed hidden for `EditorBuffer.HIDDEN_RELEASE_DELAY` (3 minutes) gives back what it keeps
  only for drawing and can rebuild on demand: the cached whole-document String when it is a second copy (a
  dirty buffer's — a clean buffer's snapshot *is* its saved baseline, so dropping it would free nothing and
  cost a copy on the next read) and the spell overlay's per-line spans and squiggle geometry
  (`EditorBuffer.releaseHiddenCaches`, timed by `HiddenTabTimer`: a `CompletableFuture` delay, no thread and
  no pulse receiver; showing the tab cancels it). The delay exists because the snapshot is rebuilt on the FX
  thread in O(document) when next asked for, and a tab switched away from is often switched back to within
  seconds. Undo entries and Undo History checkpoints are user data and are never released this way.
- A restored background tab's editor is kept out of the scene until the tab is first selected
  (`DeferredTabContent`). A hidden but sized RichTextFX area lays out a screenful of cells on every
  document update, which was most of the cost of restoring a session. The editor itself is not even built
  before the first frame: a session restore creates only the selected tab's `EditorBuffer` (and, in a
  split, each group's selected one); every other saved file is a `RestorePlaceholder` tab — title, icon,
  pin, order and group in place, an empty node as content — whose editor is built and filled after the
  selected file has painted, on the `RestoreSchedule` cadence (a time-boxed batch per pulse, a painted
  frame between batches). A tab built in the background also holds its first highlight pass back until it
  is first shown (`EditorBuffer.setHighlightDeferred`): the fill marks the whole document dirty, and
  tokenising thirty files nobody is looking at is pure cost. Measured on a 30-file session (headless, 20–60
  KB Java files): the window build no longer carries ~18 ms of buffer wiring per saved file before `show()`
  returns. Anything that needs a placeholder's editor before its turn — selecting the tab, a workspace edit,
  a recovery record, an MCP buffer call — gets it built on the spot (`MainController.ensureBuffer`,
  `WindowSessionCoordinator.materialize`); everything else that iterates buffers sees a placeholder as "no
  buffer", like an image tab, and treats the file as on disk, which is what its content is.
- A Canvas overlay releases its backing canvas to **1×1** when it has nothing to draw.

When you add any per-buffer `Canvas`/`Image`, make sure it is released or bounded. The dist
build (and `mvn javafx:run`) also raise the caps as a safety net
(`-Dprism.maxvram=2G -Dprism.maxTextureSize=16384`).

## Canvas overlays

Every document overlay (whitespace, spell-check, search-highlight, TODO, Markdown-lint, LSP
diagnostics, …) follows one discipline:

- a **mouse-transparent** `Canvas` sized to the viewport
- coalesced redraw (one per pulse) on scroll / edit / resize
- draws **only the visible paragraphs**
- `CanvasGuards` for dimension clamping + paintability checks
- released to a 1×1 backing texture while inactive (the common case is a buffer that doesn't
  use the feature)
- **lazily attached** on first activation so an off-feature buffer never builds the
  `Canvas`/subscriptions at all — the rule for everything but the two overlays that are on by default
  (notes, spelling); see rule 6 above
- **inert while it has nothing to paint**: every `scheduleRedraw` returns on its `active` flag before any
  viewport call, and an overlay that is on for every buffer (the Personal-Notes highlight) also asks a cheap
  gate (`NoteManager.hasActiveSpans`) before arming its paint, so a buffer without a note never does
  the visible-paragraph lookups — the same shape as the TODO overlay being off until it has marks
- **painted after the pulse's layout**: every overlay placed from the laid-out text schedules its paint
  through `AfterLayoutPaint`. A `Platform.runLater` paint runs between pulses, and when the next pulse moves
  the text (the line numbers widening on the fold recompute's debounce after the 1,000th line, line numbers
  toggled, the font) that pulse shows the old paint for one frame (on macOS the lint squiggles stayed there).
  `AfterLayoutPaint` paints from a post-layout pulse listener, then looks again at where the first visible
  character is for three pulses — one character-bounds query per pulse while the checks last.
  `FrameWatch` (tests) checks every shown frame rather than where a paint settles
- **no gutter work for what the gutter does not show**: `recreateParagraphGraphic` builds a cell even for a
  line far off screen, so a model that follows edits (Personal Notes, which are painted inline) must not ask
  for gutter repaints per keystroke — notes did, two per note per moved line

See the recipe in [extending.md](extending.md#add-a-canvas-overlay), and `SpellCheckOverlay` /
`MarkdownLintOverlay` as references.

An overlay that marks many spans per line should not ask RichTextFX where each one is on every frame:
`getCharacterBoundsOnScreen` is the expensive call, and scrolling moves a paragraph without changing its
layout. `SpellCheckOverlay` keeps each paragraph's squiggle positions relative to the paragraph's own box
(`getParagraphBoundsOnScreen`, one lookup per paragraph per frame), keyed by the paragraph object — which
RichTextFX replaces on any edit or restyle of that line — and re-measures only when the box size, the wrap
width, the font or the tab size changes. Measured on a viewport with 182 squiggles: 2.5–5.9 ms per repaint
before, 0.14–0.5 ms after. The same overlay bounds its work per line: a line over 16 KiB is not checked,
one line gets at most 250 squiggles, and a line is cut into tokens once (`SpellChecker.checkableWords`)
rather than once per misspelled word.

## Startup

Time-to-first-paint is measured with `scripts/measure-startup.sh` (median of N launches, with the
`com.editora.perf.Startup` phase marks; `EDITORA_PERF_SAMPLE=1` adds FX-thread stack samples). The rules below
keep the show path free of work the user is not waiting for; measure against them when touching `App`,
`WindowManager.buildWindow`, `MainController.init` or the session restore:

- **Tool-window panels are built on first open, never at registration.** Every `ToolWindow` in
  `MainController.setupToolWindows` is registered with the `Supplier` constructor; `ToolWindow.getContent()`
  builds the panel the first time the window opens (`ToolWindowManager.restore()` opens the saved-open
  windows, so exactly those are built before first paint) or the first time a caller needs it. A coordinator
  that pushes state into its panel while the window is closed either drops the push and re-reads at build
  (Bookmarks, Notes, File History, Remote Sites, Problems' diagnostics map, the Git status/operation/refusal
  the Commit panel shows, the Project tree's root, Git status and active file) or forces the build because
  the push *is* the feature being used (a run's console output, search results, a debug session's events,
  a test run). The shared build `Output` console, which every build coordinator and the Git transcript write
  to while it is closed, is held by `BuildOutput`: the panel is built by the window's first open or by the
  first write that needs a tab (`started`, `logCommand`, `commandStarted`), whichever comes first, so nothing
  written is lost and the window shows the panel that was written to; the font and the link handlers set
  before that are kept and handed over at the build. `LazyToolWindowsFxTest` fails when a closed window has
  built its panel, and opens each one to prove it still works. The console fonts follow the same rule: `EditorSettingsCoordinator.applyFontsAndPerBufferView`
  hands the font to the coordinator (`setOutputFont`/`setConsoleFont`), which applies it when the panel exists.
- **Nothing else the first window does not show is built before it** (PERF-14, Pauta 6.2.0). A picker's
  overlay card (`QuickOpen`, `CommandPalette`, `FileFinder`, `Switcher`, `SearchEverywherePopup`,
  `BranchPopup`, `BuildActionsPopup`) is built by its first `show` (`ensureBuilt`); the setters that reach a
  node before then keep the value for the build. The Settings window makes its stage and every control in
  `SettingsWindow.build`, on the first `show`; the Message Log popup, the Doctor page and the Welcome page
  (when the window opens on a file) are built by the accessor that first shows them. `FirstWindowOnlyFxTest`
  builds a window, walks everything the controller and the window manager hold, and fails on a window other
  than the main stage or a node that is not in the scene, apart from the parts of controls the window does
  show that it names with a reason (menu rows, a right-click menu, a tooltip, a toolbar or stripe button
  not placed now). A new picker, dialog or secondary window follows the same shape: nothing in the
  constructor, everything in the method the first show calls.
- **Only the fonts the first frame needs are registered before it.** `App.start` calls
  `Fonts.loadForFirstPaint(editorFamily)` — Inter (the chrome), JetBrains Mono (`app.css`'s monospace
  fallback) and the configured editor family — and arms `Fonts.loadRemainingAfterNextPaint()`, which parses the
  other bundled families on a daemon thread once a frame has painted (`Font.loadFont` is static and safe off
  the FX thread). Anything that lists or applies a family a user may pick waits for that load first:
  `SettingsWindow.fontFamilyChoices` calls `Fonts.ensureAllLoaded()`, and applying a font setting calls
  `Fonts.ensureFamily(family)`, which loads just that family inline when nothing deferred has started.
  `Fonts.load()` remains the eager form for tests and tools.
- **Stylesheets ship precompiled.** The build writes a binary `.bss` beside every bundled JavaFX `.css`
  (`scripts/css2bss.java`, at `process-classes`); JavaFX's `StyleManager` looks for `name.bss` before parsing
  `name.css`, so the 165 KB user-agent theme and the 190 KB `app.css` are read as binary at every window
  instead of being parsed as text (measured 180–340 ms for the text parse of the first window, 12–17 ms
  binary). Nothing in the application names a `.bss`; a user theme under the config directory has none and
  is parsed as before. See [building and packaging](building-and-packaging.md#precompiled-stylesheets-bss).
- **The config prefetch starts before the single-instance probe.** `App.main` starts the `config-prefetch`
  thread and only then probes the endpoint file (a stale one can cost up to the connect timeout). Until the
  probe has decided that this process will run, the thread does only `App.warmConfigLoad` — the JSON mapper,
  the store classes and the main files' bytes, read and dropped — because a load can move files (a migration
  backup, a restored set-aside copy, the TOML conversion) and a launch about to be forwarded must not touch
  the running editor's directory. After the gate it loads the shared config and everything the first window
  used to read on the FX thread: the plugin manifests (`WindowManager.discoverPlugins`), the session of the
  project or untitled window the launch will focus (`WindowManager.primaryWindowStateFile`, adopted through
  `adoptBootstrapConfig(bootstrap, projectWindow)`) and the readability of every saved session entry
  (`config/SessionScan`, handed over by `ConfigManager.setStartupSessionScan` and taken once by
  `WindowSessionCoordinator.openInitialBuffer`, which therefore stats nothing on FX). A window built any other
  way reads and scans inline, with the same rules.
- **`start()` waits once, and touches no disk of the user's** (PERF-01, Pauta 6.2.0). The prefetch thread
  (`App.loadForFirstWindow`) also claims the config dir before loading it, opens the session log (what was
  logged before is in memory and written first), reads the process ledgers and reaps a crashed run's servers,
  removes a stale MCP endpoint (`StartupPrep`), and reads what the first window's set-up used to read
  (`ui/FirstWindowPrep`): the user's themes, the zoomed copies of the window's sheets (`UiZoom.prepare`), the
  recent files and search history, the build information (`AppInfo.load`), the first window's project settings
  (handed over by `ConfigManager.setStartupProjectSettings`), the bundled XML schemas and the loose-files root,
  and the facts the window's `applySupport` calls ask of the disk: Editora's own server copies
  (`LspServerCommands`), the java-debug plugin (`DebugAdapterLocator.locateKept`) and whether a configured tool
  path is a file (`ConfiguredCommand.argv`, which asks only when the answer decides). Those facts are kept in a
  `perf/ProbeCache`, so a later window and every settings apply answer from memory; an install forgets them
  (`InstallOpsHost.reapplyToolSupport`). The fallback in `takeBootstrap` runs the same `loadForFirstWindow`
  inline. Restored windows after the first have their sessions (and project settings) read on a
  `window-session-read` thread while the first one paints; a window's turn waits for its read without blocking.
  The window icons are read once (`AppIcons`); the Welcome page checks its recent files and project folders
  off the thread (`RecentFilesCheck`); `--diff-ui`'s operands, the run configuration's main class and the
  `.gitignore` walk of a window joining a shared Java server are looked at on threads of their own. The places
  name themselves to `perf/BlockingSites`, which `StartupOffFxThreadFxTest` listens to through a real launch.
- **Probes wait for the first painted frame.** `perf/AfterFirstPaint` is held by `App.start` before the first
  window and released by the frame after the first content lands (`FileWorkflowCoordinator.firstPainted`, the
  same two-tick timer as the `FIRST_PAINT` mark) or, for a window that never loads content that way, by
  `WindowManager.FIRST_PAINT_FALLBACK` after show. Behind it: the login-shell PATH warm-up
  (`ProcessRunner.warmAugmentedPathAsync`, at once when LSP is on since a session start reads it on FX; the
  `LspManager` constructor no longer spawns it), the `rg --version` probe (and none at all while the ripgrep
  setting is off), the `gh` probe, the Hunspell dictionary build (`SpellDictionaries.ensureBuilt` keeps the
  waiting callbacks), the clipboard read behind the Paste button, and the release check, which also waits
  `UpdateCheckScheduler.DELAY` after that. Outside a launch (tests, a later window) `AfterFirstPaint.run`
  runs its task at once, so callers need not know whether a hold exists. Keep the queued tasks starters —
  an executor submit, a thread start — since they run on the FX thread at release.
- **The focus a window gains by being shown is not a regain.** `MainController.onWindowFocusGained` skips the
  regain fan-out (external-change check, `git status` + diff, build-tool detection, clipboard, Project tree
  re-listing, open diffs) for that first focus; it only computes the repository state when no tab switch did
  (`StartupFocus`), so a window opening on Welcome still gets its Git status once. Nor is focus that moves
  between Editora's own windows (a file opened from the Project Map raises its editor window, a dialog
  closes): `StartupFocus` follows every stage's focus, and a gain within 250 ms of another stage's loss runs
  nothing, unless Editora came back from another program through one of its other windows meanwhile. A real
  regain asks the Project Map's cached folders for their modification time instead of re-listing them.
- **No clipboard round trip without a buffer.** `refreshPasteState` asks the clipboard owner only when a
  buffer could take the paste; the editability flip of every restored shell goes through the coalesced
  `schedulePasteStateRefresh`.
- **The projects index is not `fsync`ed on FX at launch.** `WindowManager.launch` and `finishRestore` save it
  through `SharedConfig.saveProjectsQueued` (`ProjectManager.saveQueued`: serialized now, written on the config
  writer; a direct `save()` made meanwhile is newer and wins), and the orphan session sweep
  (`gcOrphanWindowSessions`) lists and deletes on a virtual thread.

What stays: every store is still re-serialized once at load for the `StoreSync` baseline. The baseline must be
the canonical form of the store as loaded — the "nothing changed" check and the three-way merge compare
against it — and a decoded-bytes baseline would change what a merge keeps of another writer's keys. That
serialization runs on the prefetch thread for every store a launch reads.

## Packaged-runtime tuning

The dist `<javaOptions>` (mirrored into `javafx:run` so dev == prod) pin heap and GC:

- **`-Xmx2g`** — predictable across the release matrix, and safe for a 50 MB file (the huge-file
  read cap); `LoadHeapGuard` opens a document that would not fit what is left as a read-only slice.
- **`-Xms64m`** — set explicitly, because the default initial heap is 1/64 of physical RAM
  *clamped up to `-Xmx`*: on a big-RAM machine that equals `-Xmx`, so the whole 2 GB heap is
  committed before `main` runs. Measured on Linux with a 4-file session: peak RSS median 908 MB
  (n=4), held ~58 s until the periodic GC uncommits, versus 653 MB (n=6) with `-Xms64m`, and no
  startup cost (5 interleaved pairs, mean −27 ms). The live heap is only 63–75 MB idle.
- **`-XX:+UseG1GC -XX:G1PeriodicGCInterval=30000`** — G1, *not* SerialGC: measured on a real
  session, SerialGC cost 434 MB more RSS and a 186 ms max pause against G1's 35 ms. The periodic
  interval is what returns idle memory to the OS. The full measurement notes live in the pom
  beside the options.

The jlinked runtime is stripped for size. An AOT cache (JDK 25 Leyden) shaves ~300–480 ms off cold
start and costs ~71 MB of resident, file-backed, shared mapping. None of this changes behavior —
but if you touch startup or large-file handling, measure against these settings, since they're
what ships.

When measuring memory yourself, know that **`jcmd GC.run` + `GC.heap_info` does not report the live
set** — `heap_info` prints *used*, which a few seconds after a forced GC can still be twice the live
bytes (measured 216 MB against 103 MB live). Use `GC.class_histogram`'s total, which forces its own
stop-the-world full GC. A `Concurrent Mark Cycle` line in the GC log is likewise **not a pause** — grep
`GC(n) Pause`. And if you take a heap dump to chase a suspected leak, make sure the diagnostic itself
holds no reference to the object: a live local slot makes it a "Java frame" GC root and the dump then
shows a retention that is purely your own doing.

Two more things to know before measuring memory yourself: **settled RSS has a ±90 MB run-to-run noise
floor** (two runs of an identical config landed at 552 MB and 688 MB — the variance is how much of
the heap region stays resident after an uncommit), so judge changes on **peak RSS and NMT category
totals**, which are stable, and never on a single settled reading.
