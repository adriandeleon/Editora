# Window and buffer decomposition

`MainController` owns FXML injection, window composition, tabs and lifecycle. Feature work belongs
in the per-window owners below. Each owner receives a package-private `Host` adapter that resolves
current window state when called; constructors must not read callbacks before window initialization.
Do not pass the controller itself or add inheritance to share its fields.

Most adapters are anonymous classes inside `MainController`. The largest, the command table's, is
`WindowCommandHost`: a file of its own that keeps the controller in a field, moved out only for
`MainController`'s size. The owner (`WindowCommandRegistrar`) still sees just its `Host` interface. Moving
another adapter the same way means its members of `MainController` become package-private, and
`WindowAdapterSweepFxTest` picks it up as long as its name ends in `Host` and it holds the controller.

| Owner | Responsibility |
| --- | --- |
| `WindowCommandRegistrar` | Registers window commands in their established order and applies palette gates |
| `EditingCoordinator` | Mark/kill/yank state, rectangles, query replace, clipboard and editing actions |
| `TemplateCoordinator` | File/project creation and template/archetype dialogs |
| `EditorSettingsCoordinator` | Applies editor settings and settings commands |
| `RunConfigurationCoordinator` | Run configuration selection, toolbar and launches |
| `NavigationCoordinator` | Navigation history, pickers, folding and go-to actions |
| `PreviewCoordinator` | Preview modes, Markdown/CSV editing and lint integration |
| `ExportCoordinator` | Export, copying, print preparation and export service shutdown |
| `GitWindowCoordinator` | Git window actions and navigation. The Git Log owns the repository root it listed: it is cleared and reloaded when `GitCoordinator` reports another active root or branch, and every row action runs in that root, captured before any dialog. It also subscribes to `GitCoordinator.onMutation` (fired by `afterMutation()`), so an open log reloads after every Git command; `GitLogPanel.setLog` leaves the list and selection alone when the same commits come back. The `git.log.*` palette commands go through `withSelectedCommit`, which never acts on a hidden log. Paging, the all-branches view, history search, commit review/compare, tags and merge reverts are described in [git-log.md](git-log.md). The branch dropdown captures its repository root when requested and refuses an action once another root is active |
| `GitCoordinator` | The Git engine facade and repository state for the window, plus stage/unstage/commit/discard. Rules it owns: unsaved buffers of the repository are saved through the normal save path before stage and commit (git only reads the disk); the command of a discard comes from the path's status (`GitDiscardPlan`); a path picked in the Project tree is resolved to *its* repository (`GitPathScope`), which need not be the active one; outside a repository the Commit window stays (placeholder + Clone) and the status bar says "No VCS" |
| `GitCommitCoordinator` | The Commit window's options, owned by `GitCoordinator` (`commits()`); `GitPanel` is its view (`GitPanel.Actions.attached` hands it the panel, so `MainController` wires nothing). Amend (`git.commitAmend`: `GitService.headCommit` names the commit, says whether its upstream already has it, and supplies the message; refused on an unborn branch and during an operation), sign-off (per repository, session memory), commit-and-push (`git.commitAndPush`: the push is `GitCoordinator.gitPush`, so the rejection offer and first-push remote resolution apply, and it runs only after a commit that was made), `git.undoLastCommit` (`reset --soft HEAD~1`; `undoRefusal` refuses a merge or parentless commit; a pushed one gets the stronger confirmation), the `commit.template` (read once per repository, again after a manual refresh), the recent-messages list (`CommitMessages.remember`, 20 per repository, session memory — no store on disk fitted it) and the per-file line counts (`GitService.lineCounts`: two `diff --numstat -z`, only while the panel is on screen, only up to 500 status entries, dropped when a newer status has been applied). `GitCoordinator` itself keeps `gitUnstageAll` and `gitDiscardAll` (`DiscardAll.of` sorts every path through `GitDiscardPlan`) next to the single-path versions |
| `GitAutoFetch` | "Fetch automatically": a `Timeline` at the configured interval that asks `GitService.autoFetch` for a background `fetch --prune` of the active repository — only in a trusted folder or a repository the user ran a network command in this session, never while a user network command is running or queued, not for a window unfocused longer than one interval. Owned by `GitCoordinator` (`autoFetch()`), re-timed by `applySupport()`. The environment that keeps it from prompting, and why the trust gate exists, are in [ADR 0002](../decisions/0002-native-cli-git.md) |
| `GitBranchCoordinator` | Branch, remote and work-tree management, built from `GitWindowCoordinator`'s host: delete / rename / merge / rebase / new-branch-from / upstream / compare / checkout-by-name, the push variants, and the remotes and work-trees managers (`GitManagerOverlay`). It supplies the branch dropdown's per-row secondary actions (`rowActions`, under the same `inRoot` guard) and replaces the body of `GitCoordinator.gitPush` (`pushHandler`), so every push answers a non-fast-forward rejection with pull-then-push / force-with-lease / cancel. Rules it owns: the repository root is captured before any picker or confirmation; a typed name is validated (`GitRefNames`) and every name is placed after `--end-of-options` (`GitService.guarded`; `checkout` uses a closing `--`); a command that may rewrite files runs through `GitCoordinator.aroundWorkingTreeMutation`; the outcome is classified by `GitOutcome`, so a merge, rebase or pull that stops on conflicts says "stopped on conflicts" with git's own message instead of the failure dialog; a forced push is always `--force-with-lease`; removing a dirty work tree needs a second confirmation |
| `GitHunkCoordinator` | The active file's changes worked on in the editor: `git.nextChange` / `previousChange`, the change card (`GitHunkPopup`, opened by `git.peekChange` or a click on a change bar), `git.revertHunk` and `git.stageHunk`. Owned by `DiffCoordinator` (`hunks()`), which is also its way to the index. It installs itself as `GitCoordinator`'s *gutter sink*, so every diff that reaches a buffer brings its hunks (`editor/GitHunk`) along with the bars. Rules it owns: positions come from `GitGutterLines` (`marks()`, `hunkAt`, `locate`), never from raw diff line numbers, so they are right under unsaved edits; Revert is one `replaceText` planned by the pure `HunkRevert` and refuses when `locate` cannot name the lines exactly (a rewrite the `DiskLineMap` gave up on, an edit inside the hunk); Stage saves the buffer, takes a fresh diff, picks the *unstaged* hunks that touch the change (`HunkStaging.touching`) and hands `DiffCoordinator.stageFromEditor` the index text with them applied — the same blob rewrite and index compare-and-swap as the diff viewer's Stage Hunk — and refuses an unmerged file |
| `GitStashCoordinator` | Stash: the list card (`GitStashPopup`, `git.stashes`), the `git.stash` form and its options, apply/pop/drop/branch, and the plain pickers. Owned and constructed by `GitCoordinator`, whose `gitStash*` methods are one-line delegations. Reads name a stash by its commit; mutations use `stash@{N}` through `GitService.runStashMutation`, which first checks the ref still resolves to the listed commit. Apply/pop save the buffers of the stash's files first, run inside `aroundWorkingTreeMutation`, and report through `StashOutcome` — a conflict is "applied, stash kept", not a failed command. |
| `GitBlameCoordinator` | The blame column: fetch, formatting, click-to-commit, the per-window options (`BlameOptions`, held by the window's `GitService` and part of its cache key) and *annotate previous revision*. A revision tab is a path-less, read-only buffer registered with its `(root, commit, path)`; `GitCoordinator.contextPath()` asks for that root so the tab keeps its repository, and blame for it is `git blame <commit> -- <path>`. Owned by `GitCoordinator`. |
| `GitPatchCoordinator` | `git.applyPatch` / `git.createPatch` and the patch review tab's Apply pair. Applies the patch file's own bytes through `GitService.applyPatch` (`--check` first; `--3way` only on request after a rejection) in the repository the review was opened in. Owned by `GitCoordinator`; the three share `GitCoordinator.attachWindow(diff, openTab)` for the two window surfaces they open tabs through. |
| `WindowChromeCoordinator` | Chrome visibility, focus modes and overlays |
| `TabHeaders` | The graphic header of an editor tab — icon, title, pin/dirty/read-only/remote markers, tooltip — for any `TabContent` and for a buffer, plus the drag handle that reorders the strip (it tracks the dragged tab for `EditorArea`'s group drops). `MainController.updateTabMeta` keeps the window-title and preview-tab bookkeeping around its calls |
| `WindowMcpBridge` | Window-facing MCP operations; the controller retains the public facade |
| `FileWorkflowCoordinator` | Loading, saving, autosave and elevated saves |
| `CloseCoordinator` | Exact-state tab/window close prompts and cross-prompt revalidation |
| `WindowSessionCoordinator` | Session restoration/persistence and command-line startup |
| `TestNavigationCoordinator` | Test/stack navigation and test/main-method gutter gates |
| `InstallPromptCoordinator` | Language-support install prompts and server picker |
| `UpdateCoordinator` | The release check: the automatic one (`UpdateCheckScheduler`: once per session and per day, a few seconds after the first painted frame), the manual "Check for Updates", the status-bar notice and the download page; the newest release found is shared across windows |

The existing service coordinators continue to use `CoordinatorHost` and the shared `Services`
adapter. Feature-specific hosts can refer to those owners when they need their capabilities. Keep
callbacks narrow and resolve active buffers, settings and the owning window at invocation time.
`OpenBufferLifecycle` supplies the shared path lookup, pending-Git-write invalidation, and asynchronous
post-Git disk reconciliation used across windows.

Each `FileWorkflowCoordinator` save request captures the identities of preceding unresolved saves from
the same buffer alongside its disk snapshot. A worker compares the actual bytes with the preceding
application commit even if its FX acknowledgment has already retired that request. Looking up only live
requests would mistake that commit for an external edit; differing external bytes must still block autosave.

Commands still run through `CommandRegistry`. Preserve their identifiers and registration order,
since order affects the palette. FXML entry points and public window APIs remain forwarding methods
on `MainController`. Background work, stale-result guards, FX-thread callbacks and service shutdown
stay with the workflows that own them; extraction must not change their scheduling or lifetime.

`editor/BufferCompletion` owns a buffer's completion/code-action/documentation popups, ghost text,
AI completion and asynchronous completion state. `EditorBuffer` retains the public API, document
snapshots, mark ring and indentation decisions. Its host stays in the editor package and has no UI
controller dependency.

Existing JavaFX tests exercise these workflows through real windows and command dispatch. Tests
that inspect private state must target the owning coordinator. `SourceFileSizeTest` enforces a
10,000-line upper bound for production Java files; this is a ceiling, not a desired class size.
Prefer focused responsibilities well below it.

Run requests are also navigation requests: `RunCoordinator` opens and focuses the Run tool window before
launch validation. If a process is already active, another Run request refocuses that existing console and
reports the busy state. `ToolWindowManager.open(window, true)` must therefore refocus an already-open docked
or floating window, not treat the call as a no-op. Run-window availability is "the active buffer is
runnable, or a run has reached the console in this window" (`RunCoordinator.consoleInUse()`: a live process or
before-launch step, or any earlier one), independent of the active buffer's runnability. A run's own exit
refreshes that gate with the process already gone, so a gate that only counted live processes closed the
console on the output it had just streamed; like Test Results, the window stays available once used.

A Java launch in a Maven or Gradle project builds first. jdtls runs with autobuild off, so the classpath it
resolves is right while the class files in it are whatever the last build left: `RunCoordinator.buildBeforeLaunch`
and `DebugCoordinator.buildToolThenLaunch` run the build tool's compile (`compile`/`test-compile` for a class under
`src/test`; Gradle `compileJava`/`compileTestJava`) as a `BeforeLaunchStep.Command` in the Run or Debug console
before the jdtls launch, on the project's JDK, aborting on a non-zero exit — behind `Settings.buildBeforeRun`
("Build before Run and Debug", on). A configuration with its own before-launch step keeps that step instead; with
the setting off, Debug keeps jdtls's workspace build and Run prints a hint to build when the JVM's first lines say
it could not find the main class. The command's executable is the tasks tree's (`BuildCoordinator.trustedExecutable`:
the project's wrapper after the trust gate, the project-scoped command override, else the PATH command), which the
no-jdtls Maven fallback uses too: `MavenClasspathResolver.plan` builds the `dependency:build-classpath` command with
that prefix and the launch's scope (`-Dmdep.includeScope=runtime`, `test` for a test class), and the coordinator runs
it as a before-launch step — its output streams, Stop kills it, a second Run is refused, and a failure's status
quotes Maven's first `[ERROR]` line (`BeforeLaunchStep.failureDetail`). Both commands carry the Maven tasks tree's
active profiles and flags (`BuildCoordinator.activeToggleArgs` → `RunCoordinator.Ops.mavenToggleArgs`; the tree's
reactor switch is dropped, `MavenClasspath.withoutReactorArgs`), and both choose the module's reactor themselves:
`MavenReactor.describe` walks the ancestors' `<modules>` (a `modules/` folder without a pom included), bounded by
the open project, and a module its reactor lists builds from the root with `-pl <module> -am`; when Maven answers
"Could not find the selected project in the reactor", the fallback runs the module alone (`Plan.singleModule`) and
the console says which mode ran. A saved configuration's fallback resolves the module `projectName` (= the
artifactId) or the main class's source file names (`MavenReactor.locateModule`), not the routing file's. With no
JDK selected the program runs on `$JAVA_HOME/bin/java` when the inherited environment names one
(`JdkToolchain.inheritedJavaExecutable`), i.e. the JDK Maven just compiled with. A configuration's before-launch text may be
symbolic — `maven:compile`, `gradle:compileJava` (`run.BuildBeforeLaunch.symbolic`) — resolved through the same
executable at run time; the configuration seeded for a generated Maven project stores that.

The Run and Debug windows' runtime chip comes from `run.RuntimeProbe`, which asks the launched argv's first
word (`RuntimeVersion.probeFor`: `java`, a Python, `node`/npm, `bash`, `zsh`, `fish`, GNU `make`) for its version
in the launch's folder and environment, off the FX thread and uncached, beside the launch rather than before it.
`RunCoordinator.streamRun` probes every run; `DapManager.Listener.onDebuggeeRuntime` reports a debug launch's
`javaExec`, debugpy interpreter or `node` (never an attach). Each coordinator drops an answer that arrives after
a newer run or session began.

Every open of a file runs `MainController.restorePerFileState` (folds, bookmarks, breakpoints, personal
notes, read-only pin) — the ordinary load, session restore, and the background opens used by workspace
edits, diff/merge apply, agent writes and run-configuration restore (`attachBackground`). The mark stores are
replace-on-change, so a tab that skipped the restore would persist its empty lists over the stored ones at
the next mark change, and the background callers' only protection for a pinned file is `isEditable()`.

"Open, then act on a line" must wait for the load: `FileWorkflowCoordinator.openPath` only starts the read
and the tab is an empty read-only shell until it lands. Use `openThen` / `whenLoaded` (or
`WindowSessionCoordinator.gotoInFile`, which defers itself and carries its record decision and origin through
the deferral) rather than `openPath` + `Platform.runLater`. `openAndGoto` records its jump once, after the
load, through `NavigationCoordinator.landJump`.

## Restore is staggered

`WindowManager.launch` builds the primary window (the one that had focus), returns to the event loop, and
then builds each remaining restored window after a painted frame. External open requests that arrive
meanwhile are queued until every window exists, a quit pauses the queue, and windows not yet built keep
their saved sessions. Within a window, only the selected tab (in a split, each group's selected tab) gets an editor
before the first frame; every other saved file is a `RestorePlaceholder` tab — a `TabContent` with the
file's title, icon and pin and an empty node, in its saved group and position, in the same `Tab` object the
editor is later swapped into. After the selected file has painted, `WindowSessionCoordinator` builds and
fills the rest on the `RestoreSchedule` cadence: a batch per pulse within a time budget (at least one), a
painted frame (`afterNextPaint`) between batches, in selected-then-tab order. A background tab is loaded and
has its folds and caret restored as before, but its editor node is attached to the scene only when the tab
is first selected (`DeferredTabContent`), and its first syntax-highlight pass waits for that moment too
(`EditorBuffer.setHighlightDeferred`); the first restored tab the `TabPane` auto-selects is not treated as
active (`EditorArea.holdActiveTab`). A placeholder that something needs earlier is built out of order
(`materialize`): `EditorArea`'s tab materializer runs before a newly selected tab is published, so no
selection listener ever sees a placeholder as the active tab; `MainController.ensureBuffer` serves
`whenLoaded`, crash-recovery records, MCP buffer operations and the test-method jump; `openBackgroundBuffer`
(diff apply, agent writes, LSP workspace edits) builds and loads a placeholder's file rather than opening a
second tab on it. Everything else that iterates buffers sees a placeholder as "no buffer" (`bufferOf` is
null, `tabPath` is the file): a setting pushed to open buffers reaches the editor when it is built, since
the wiring reads the current settings; search in open files, Replace in Files, Problems and the tab-close
paths treat the file as on disk, which is what the placeholder's content is; session capture writes the
placeholder's saved caret and pin; a rename or folder move relocates the placeholder's path. The restore is
complete (`restoreComplete`, the `session-restored` startup mark) once every entry is built and filled, in
or out of order. Tab context menus (`LazyContextMenu`) and the build-tool task trees
(`ToolWindow` content suppliers) are built on first use, and build-file detection runs on one shared worker
that skips unchanged marker files (`BuildDetection`).

Which saved entries can be reopened is decided before the window exists: the launch's config-prefetch thread
scans them (`config/SessionScan` — readable, kept-but-missing, or dropped, the rules `SessionEntries` used
to apply inline) and `WindowSessionCoordinator.openInitialBuffer` takes that scan from its `ConfigManager`
(`takeStartupSessionScan`, only when it is of the session's current list), so the restore stats nothing on
the FX thread; a window built outside a launch scans inline. The focus a window gains by being shown runs
none of the regain fan-out (`MainController.onWindowFocusGained`): Git status and build-tool detection are
computed by the tab-switch path when a session is restored, and by that first focus only when no tab switch
did. The startup probes (login-shell PATH, `rg`, `gh`, the spell dictionary, the release check, the
Paste button's clipboard read) wait behind `perf/AfterFirstPaint` until the first content has painted; see
[performance.md](../performance.md#startup).
