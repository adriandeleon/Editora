# Window and buffer decomposition

`MainController` owns FXML injection, window composition, tabs and lifecycle. Feature work belongs
in the per-window owners below. Each owner receives a package-private `Host` adapter that resolves
current window state when called; constructors must not read callbacks before window initialization.
Do not pass the controller itself or add inheritance to share its fields.

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
| `GitWindowCoordinator` | Git window actions and navigation. The Git Log owns the repository root it listed: it is cleared and reloaded when `GitCoordinator` reports another active root or branch, and every row action runs in that root, captured before any dialog. It also subscribes to `GitCoordinator.onMutation` (fired by `afterMutation()`), so an open log reloads after every Git command; `GitLogPanel.setLog` leaves the list and selection alone when the same commits come back. The `git.log.*` palette commands go through `withSelectedCommit`, which never acts on a hidden log. The branch dropdown captures its repository root when requested and refuses an action once another root is active |
| `WindowChromeCoordinator` | Chrome visibility, focus modes and overlays |
| `WindowMcpBridge` | Window-facing MCP operations; the controller retains the public facade |
| `FileWorkflowCoordinator` | Loading, saving, autosave and elevated saves |
| `CloseCoordinator` | Exact-state tab/window close prompts and cross-prompt revalidation |
| `WindowSessionCoordinator` | Session restoration/persistence and command-line startup |
| `TestNavigationCoordinator` | Test/stack navigation and test/main-method gutter gates |
| `InstallPromptCoordinator` | Language-support install prompts and server picker |

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
their saved sessions. Within a window, a restored background tab is loaded, highlighted and has its folds
and caret restored as before, but its editor node is attached to the scene only when the tab is first
selected (`DeferredTabContent`); the first restored tab the `TabPane` auto-selects is not treated as active
(`EditorArea.holdActiveTab`). Tab context menus (`LazyContextMenu`) and the build-tool task trees
(`ToolWindow` content suppliers) are built on first use, and build-file detection runs on one shared worker
that skips unchanged marker files (`BuildDetection`).
