# Extension cookbook

Step-by-step recipes for the things you'll add most often. Each follows the same spirit:
register through the right system, keep the `editor`/`completion` packages free of `ui`, add
i18n in all six catalogs, and don't regress the [hot paths](performance.md).

Identifiers (class names, field names) are accurate at the time of writing — grep to confirm
against the current code.

- [Add a command](#add-a-command)
- [Add a setting](#add-a-setting)
- [Add an LSP server](#add-an-lsp-server)
- [Add a DAP adapter](#add-a-dap-adapter)
- [Add a language / TextMate grammar](#add-a-language--textmate-grammar)
- [Add a tool window](#add-a-tool-window)
- [Add a Canvas overlay](#add-a-canvas-overlay)
- [Extract a feature coordinator](#extract-a-feature-coordinator)

---

## Add a command

1. **Register** in `MainController.registerCommands()`:
   ```java
   registry.register(Command.of("edit.myThing", this::myThing));
   ```
   `Command.of(id, runnable)` resolves its title lazily from `command.<id>` and its palette
   description from `command.<id>.desc`.
2. **i18n** — add `command.edit.myThing` and `command.edit.myThing.desc` to **all six**
   `messages*.properties` catalogs.
3. **Keybinding (optional)** — add a chord → id mapping in the bundled keymap JSON under
   `resources/com/editora/keymaps/`. Emacs is single-file (`emacs.json`); the GUI keymaps ship
   a base `<name>.json` (Ctrl) **and** a `<name>.mac.json` (Cmd). Chord tokens are exactly what
   `KeyDispatcher.chord()` emits, modifiers in canonical order `C- M- Cmd- S-`. `KeymapsTest`
   guards parse, valid command ids, token order, and base↔`.mac` parity.

The palette and the keybinding editor populate from `CommandRegistry.all()`, so a registered
command appears automatically. Never add a user-facing action that bypasses the registry.

## Add a setting

A setting is a `Settings` field **plus** a Settings-window control **plus** a palette command.

1. **Field** in `config/Settings.java` with getter/setter; bump `Settings.SCHEMA_VERSION` and
   add an additive-identity step in `ConfigSchema` (see
   [conventions.md](conventions.md#config-and-schema)).
2. **Settings UI** — a control on the relevant page in `SettingsWindow`, wired through the
   live-apply path (each control writes the field then `apply()`).
3. **Palette command** — a `view.toggle*` (for a flag) or a prompt/picker (for a value) that
   flips the same field, `requestSave()`s, re-applies the feature, and calls
   `SettingsWindow.syncAll()`. Reuse `toggleSetting`/`promptIntSetting`/`chooseSetting`.
4. **i18n** for the command + the Settings label.

## Add an LSP server

The registry is server-centric — one session per `(serverId, root)`. Adding a server is data,
not new logic:

1. A `ServerDef` in [`lsp/LspServerRegistry.java`](../src/main/java/com/editora/lsp/LspServerRegistry.java):
   the command tokenization, the language ids it serves, and its root markers.
2. The server id in `MainController.LSP_SERVER_IDS`.
3. The served language id(s) in `EditorBuffer.LSP_LANGUAGES` (kept in sync with the registry;
   `isLspLanguage()` reads it).
4. A `Settings.lspSupport`-style per-server `…LspCommand` + `…LspEnabled` pair, and an entry in
   `SettingsWindow.lspServerUis()` (the LSP page is data-driven over that descriptor list).

`LspManager.configure(enabled, Map<serverId, command>)` is map-based, so it never needs a
signature change. If the language has no bundled grammar, also do
[Add a language](#add-a-language--textmate-grammar).

## Add a DAP adapter

Mirrors the LSP recipe. The language→adapter spec is
[`dap/DapServerRegistry.java`](../src/main/java/com/editora/dap/DapServerRegistry.java)
(`Kind` = `JDTLS` / `STDIO` / `SOCKET`), `languageIdsForDebug()` gates the breakpoint gutter +
debug commands, and the Settings → Debugging page is data-driven over
`SettingsWindow.debugAdapterUis()`. Java rides the jdtls session; Python (debugpy) is stdio;
JavaScript (vscode-js-debug) is a socket. Adapters are never bundled — they're located by
`dap/DebugAdapterLocator` and installed by the `scripts/install-*.sh` helpers.

## Add a language / TextMate grammar

1. Drop the `<name>.tmLanguage.json` under
   [`resources/com/editora/grammars/`](../src/main/resources/com/editora/grammars/) (vendor
   from a permissive source; attribute it in `NOTICE`).
2. Register it in `GrammarRegistry`: a `scopeToResource` entry (scope → resource base name) and
   a `mapExtensions(...)` entry (file extension → scope). Filename-only matches (like
   `Dockerfile`) get a special case in `scopeForFileName`.
3. Add the extension to `LanguageRegistry` (language-name resolver used by folding + File
   Information), and to `FoldRegions`/`Indenter`/`Commenter` if it needs folding/indent/comment
   support.

> **JPMS gotcha:** grammar resources live in the `com.editora.grammars` package, which
> `module-info.java` must `opens ... to org.eclipse.tm4e.core`. Without that, tm4e's
> `Class.getResourceAsStream` returns null at runtime (module path) and grammars silently fail
> to load even though classpath tests pass.

`TextMateHighlighter` tokenizes line-by-line and maps each scope to a `.text.<class>` CSS class
themed in [`styles/syntax.css`](../src/main/resources/com/editora/styles/syntax.css).

## Add a tool window

1. Build a panel that `extends` a JavaFX `Region` (usually `VBox`) and `implements`
   `editor/TabContent`-style `ToolWindowContent`. Keep it in `ui`; route actions back to the
   controller via a small `Actions` callback interface (see `MarkdownLintPanel`, `SearchPanel`).
2. Construct a `ToolWindow` and register it:
   ```java
   myPanel = new MyPanel(actions);
   myToolWindow = new ToolWindow(
           "myId", tr("toolwindow.myId"), ToolWindow.Side.BOTTOM, Icons::myGlyph, myPanel, "tool.myId");
   toolWindows.register(myToolWindow);
   ```
3. Add the `tool.myId` toggle command + a keybinding, and the `toolwindow.myId` i18n key.
4. Use `ToolWindowManager.setAvailable(tw, condition)` for a **transient** hide (e.g. "only in a
   git repo") — distinct from the persisted `setVisible` show/hide preference.

The default `Side` is a default only; each window restores its persisted
`WorkspaceState.toolWindowSides`.

## Add a Canvas overlay

The discipline that keeps overlays off the hot path (see
[performance.md](performance.md#canvas-overlays)). Mirror `MermaidLintOverlay` /
`SpellCheckOverlay`:

- a `final class MyOverlay extends Region` in `editor`, holding a `Canvas`, mouse-transparent,
  `setVisible(false)` until activated;
- subscribe to `area.viewportDirtyEvents()`, `multiPlainChanges()`, and the scroll properties to
  `scheduleRedraw()` (coalesced with a `redrawPending` flag + `Platform.runLater`);
- `redraw()` clears, then iterates only `firstVisibleParToAllParIndex … lastVisibleParToAllParIndex`,
  using `CanvasGuards` for dimension/paintability checks;
- `setActive(false)` clears and **releases the canvas to 1×1** so it holds no full-viewport
  texture;
- the data (diagnostics/marks) is **pushed in** by `EditorBuffer`; the overlay only renders.

Attach it in `EditorBuffer.installOverlays()` (eagerly for a common feature, or lazily via
`attachLazyOverlay` for a rare one), anchor it inside `Minimap.WIDTH`, and add an
`EditorBuffer.setXxxEnabled`/`setXxxData` pair injected from `MainController`. If it should also
show on the scrollbar/minimap, feed a stripe (`DiagnosticStripe`-style) + a `Minimap` channel.

## Extract a feature coordinator

When a feature's logic bloats `MainController`, pull it into a coordinator behind
[`ui/CoordinatorHost`](../src/main/java/com/editora/ui/CoordinatorHost.java) (the
`LogViewerCoordinator` / `MermaidCoordinator` / `HtmlPreviewCoordinator` pattern):

- a `final class MyCoordinator` in `ui` that owns the feature's service(s) + state and reaches
  the window only through the shared `CoordinatorHost` interface (settings, active/all buffers,
  status, prompts, the overlay host, …);
- `MainController` constructs it with an anonymous `Host` adapter and keeps **one-line
  delegations** at each call site;
- a fake `Host` in a test makes the coordinator unit-testable without a real window (see the
  `*CoordinatorFxTest`s).

Add to `CoordinatorHost` only the narrow capabilities your coordinator needs; don't hand it the
whole `MainController`.
