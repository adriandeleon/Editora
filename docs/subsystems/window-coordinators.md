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
| `GitWindowCoordinator` | Git window actions and navigation |
| `WindowChromeCoordinator` | Chrome visibility, focus modes and overlays |
| `WindowMcpBridge` | Window-facing MCP operations; the controller retains the public facade |
| `FileWorkflowCoordinator` | Loading, saving, autosave and elevated saves |
| `WindowSessionCoordinator` | Session restoration/persistence and command-line startup |
| `TestNavigationCoordinator` | Test/stack navigation and test/main-method gutter gates |
| `InstallPromptCoordinator` | Language-support install prompts and server picker |

The existing service coordinators continue to use `CoordinatorHost` and the shared `Services`
adapter. Feature-specific hosts can refer to those owners when they need their capabilities. Keep
callbacks narrow and resolve active buffers, settings and the owning window at invocation time.

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
