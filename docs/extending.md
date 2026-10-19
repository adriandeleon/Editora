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
- [Add an in-scene card at a line of text](#add-an-in-scene-card-at-a-line-of-text)
- [Extract a feature coordinator](#extract-a-feature-coordinator)

---

## Add a command

1. **Register** in `WindowCommandRegistrar.registerCommands()`:
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
   live-apply path (each control writes the field then `apply()`). A text field goes through
   `commitOnEnterOrBlur` (Enter, focus loss or closing the window), never a `textProperty` listener: a
   save, a re-apply in every window and a probe per keystroke act on half-typed values. An on/off
   setting is a `checkRow` (a switch row), not a bare `CheckBox`.
3. **Palette command** — a `view.toggle*` (for a flag) or a prompt/picker (for a value) that
   flips the same field, `requestSave()`s, re-applies the feature, and calls
   `SettingsWindow.syncAll()`. Reuse `toggleSetting`/`promptIntSetting`/`chooseSetting`.
4. **i18n** for the command + the Settings label.

## Add an LSP server

The registry is server-centric — one session per `(serverId, root)`. Adding a server is data,
not new logic:

1. A `ServerDef` in [`lsp/LspServerRegistry.java`](../src/main/java/com/editora/lsp/LspServerRegistry.java):
   the command tokenization, the language ids it serves, and its root markers.
2. The server id in `LspCoordinator.SERVER_IDS` and its four per-server switches (enabled, command, set
   enabled, set command); `LspCoordinatorServerIdsTest` checks the list.
3. The served language id(s) in `EditorBuffer.LSP_LANGUAGES` (kept in sync with the registry;
   `isLspLanguage()` reads it).
4. A `Settings.lspSupport`-style per-server `…LspCommand` + `…LspEnabled` pair, and an entry in
   `SettingsWindow.lspServerUis()` (the LSP page is data-driven over that descriptor list), plus the
   `settings-controls.properties` rows, a schema bump with an additive migration, and the i18n keys in
   all six catalogs.
5. An installer, if it has one: `InstallCatalog.serverInstall` (`ARCHIVE` for a per-OS binary,
   `JVM_LSP_JAR` for one pinned jar run with `java -jar`, `JVM_LSP_ZIP` for a jar bundle), and the
   persist case in `InstallCoordinator.applyServerCommand`. A server run by `java` gets the Settings JDK
   through `JvmServerCommand.withJdk`.

`LspManager.configure(enabled, Map<serverId, command>)` is map-based, so it never needs a
signature change. If the language has no bundled grammar, also do
[Add a language](#add-a-language--textmate-grammar).

## Add a DAP adapter

Mirrors the LSP recipe. The language→adapter spec is
[`dap/DapServerRegistry.java`](../src/main/java/com/editora/dap/DapServerRegistry.java)
(`Kind` = `JDTLS` / `STDIO` / `SOCKET` / `JVM`), `languageIdsForDebug()` gates the breakpoint gutter +
debug commands, and the Settings → Debugging page is data-driven over
`SettingsWindow.debugAdapterUis()`. Java rides the jdtls session; Python (debugpy) is stdio;
JavaScript (vscode-js-debug) is a socket. External adapters are never bundled — they're located by
`dap/DebugAdapterLocator` and installed by the `scripts/install-*.sh` helpers.

A JVM language other than Java (Groovy today) uses `Kind.JVM`: Editora's own JDI adapter
([ADR 0017](decisions/0017-jvm-debug-adapter.md)). Adding one is a `run/JvmScriptRun` entry (its tool and
the variable its launcher passes JVM options in), a `run/JvmTools` home variable, a `DapServerRegistry`
`Def`, its `RunScan` branch, and its runtime-internal packages in `JvmSourceMap.stepFilters`.

## Add a language / TextMate grammar

A language is a set of small tables, not one class. Each row below is a place a language's id (the
`LanguageRegistry` name, e.g. `scala`) or extension has to appear; leaving one out silently drops that
feature for the language. The Kotlin, Groovy, Scala and Clojure rows are a worked example of the whole
list.

**Recognition**

1. **Grammar** — drop `<name>.tmLanguage.json` under
   [`resources/com/editora/grammars/`](../src/main/resources/com/editora/grammars/), vendored from a
   permissive source (shiki's `tm-grammars` is the usual one), and attribute it in `NOTICE`: source,
   version, any local change, and the "Languages bundled" list. tm4e reads `applyEndPatternLast` as true
   only when it is the boolean `true` (`"1"` is false), and the Joni backend rejects variable-length
   look-behinds; `BundledGrammarsTokenizeTest` compiles every regex and fails on either.
2. **`GrammarRegistry`** — a `scopeToResource` entry (scope → resource base name) and a
   `mapExtensions(...)` entry (extension → scope). Name-only files (`Dockerfile`, `project.clj`) go in
   `ConfigFileType`, which both registries read.
3. **`LanguageRegistry`** — the same extensions → the language id. `LanguageTablesAgreeTest` fails when an
   extension has a grammar but no language, or the reverse. Shell extensions live in one table,
   `ShellFiles`, that all three read.
4. **Content detection** — a plain-text buffer still gets a language from a shebang (`Shebang.INTERPRETERS`:
   interpreter → representative extension) or a vim/emacs modeline (`Modeline.NAMES`).
5. **Markdown fences** — `MarkdownRenderer.INFO_EXT` maps a fence's info string to an extension for the
   preview; the editor's own fence highlighting is a `fenced_code_block_<lang>` rule in
   `markdown.tmLanguage.json`, which already covers most languages.

**Editing**

6. **`Commenter.styleFor`** — line and block comment tokens (`;;` for Clojure).
7. **`Indenter.styleFor`** — the indent `Style`. A language whose blocks are not braces gets its own helper
   beside `PythonIndent`/`ShellIndent`: `LispIndent` (Clojure: by the innermost open bracket) and
   `ScalaIndent` (Scala 3's optional-brace openers, shared with folding). `IndentWindow.enterEdit` must
   hand a style that reads the lines above the caret a back-scan window.
8. **`FoldRegions`** — a `detect` case (the brace walker, or a scanner of its own like `lisp`/`scala`),
   the `SLASH_STAR_LANGUAGES` block-comment set, and `Syntax.of` for the literal rules (triple quotes, a
   `'` that may not close).
9. **`AutoClose.decide`** — a quote that is not a string delimiter in the language (Clojure's `'` and
   backtick) must not pair. **`StringCase.DASH_OPERATOR_LANGUAGES`** — whether `-` between names is an
   operator (Scala) or part of a name (Clojure, CSS).

**Navigation**

10. **Symbol index** — a rule list in `index/DeclarationRules` (`BY_LANGUAGE`; `braceScoped` when members
    are not nested in braces) and, when its quotes are not C's, a case in `SourceBlanker.blank` (Clojure's
    `'sym` and `\c`, Scala 2's `'symbol`). `DeclarationScannerTest` holds a case per language.
11. **Structure panel** — `StructurePanel.isBraceLanguage` for a brace language. When the grammar emits no
    `entity.name.*` scopes for declarations (Clojure), add a branch to `buildNodes` that builds the outline
    another way (`clojureFormNodes` reads the symbol index).
12. **Stack traces** (JVM languages) — the file extension in `StackTraceLinks.JVM_SOURCE` and the source
    roots in `search/SourceFileFinder.SOURCE_ROOTS`.

**Files and chrome**

13. **Snippets** — `resources/com/editora/snippets/<lang>.json`, the id in
    `SnippetManager.BUNDLED_LANGUAGES` (sorted; `SnippetManagerTest` pins it to the directory), and its
    provenance in `NOTICE`.
14. **New file** — a `NewFileCatalog` row (with a template only when an empty file would be useless).
15. **Icons** — a brand glyph in `FileIcons` (`PATHS` + `keyForLanguage`, Simple Icons are CC0; list it in
    `NOTICE`), and the language in `ProjectMapModel.SOURCE_LANGUAGES` (build files in `CONFIG_NAMES`).
16. **Samples** — a `samples/syntax/` file listed in `samples/README.md`; `SamplesCorpusTest` fails when a
    bundled grammar has no sample.

A grammar that `include`s a `#rule` it never defines loses that construct silently (tm4e skips it, and
`App` silences tm4e's warnings); `BundledGrammarIncludesTest` catches it. Language servers and debug
adapters are separate recipes ([LSP](#add-an-lsp-server), [DAP](#add-a-dap-adapter)).

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
  `scheduleRedraw()` (coalesced with a `redrawPending` flag, painted through an `AfterLayoutPaint` so the
  paint runs after the pulse's layout and follows a gutter that changes width);
- `redraw()` clears, then iterates only `firstVisibleParToAllParIndex … lastVisibleParToAllParIndex`,
  using `CanvasGuards` for dimension/paintability checks;
- `setActive(false)` clears and **releases the canvas to 1×1** so it holds no full-viewport
  texture;
- implement `TabSurface` so `EditorBuffer.setRenderingActive` releases the canvas while the tab is in the
  background, size it with `CanvasGuards.fit` / `release`, keep it at 1×1 whenever there is nothing to
  draw (an active feature with an empty list included), and return early from `scheduleRedraw()` in that
  case;
- the data (diagnostics/marks) is **pushed in** by `EditorBuffer`; the overlay only renders.
- implement `SecondaryPane.Followed`: `follower(view)` returns a twin for a split's second view, and
  every setter hands its argument to that twin first (before any "unchanged" early return). The twin
  is created and stacked by `SecondaryPane.follow` — when the split is built, and from
  `attachLazyOverlay` for a lazily attached overlay — so nothing else needs wiring, and the second
  pane cannot be left without the overlay. Stripes, the minimap and the column ruler follow the same
  rule.
- colours come from `OverlayPalette`, not from a `Color.web("#…")` constant: a Canvas cannot be styled
  from CSS, and one fixed colour is too faint on one editor theme or too loud on the other. Call
  `OverlayPalette.track(area, palette -> { colors = palette; scheduleRedraw(); })` in the constructor —
  it re-resolves when the editor theme repaints the background, never per paint.

Attach it lazily: a nullable field, an accessor that calls `attachLazyOverlay(new MyOverlay(area),
Layer.MY_OVERLAY)` on first use (a stripe docked at the right edge goes through `attachLazyStripe`), and a
new constant in `EditorBuffer.Layer` placed where the overlay belongs in the stack — the accessor hands a
late-built overlay the state it missed (the font, the large-file guard, its on/off state). Every setter
that may run before the feature is on guards with `if (on || myOverlay != null) myOverlay().set…`, so
switching a feature *off* on a buffer that never had it builds nothing (`LazyOverlayFxTest` checks every
lazy field stays null through such a sweep). Only an overlay that is on for every buffer by default (notes,
spelling) is built in `installOverlays()`, tagged with `layered(node, Layer.X)`. Then add an
`EditorBuffer.setXxxEnabled`/`setXxxData` pair injected from `MainController`. If it should also
show on the scrollbar/minimap, feed a stripe (`DiagnosticStripe`-style) + a `Minimap` channel.

## Add an in-scene card at a line of text

A card that takes keys is shown through the window's `OverlayHost` ([ADR 0005](decisions/0005-in-scene-overlays.md)),
never as a `javafx.stage.Popup`. One that belongs to a place in the document rather than to a toolbar button
uses `OverlayHost.showAt(card, sceneX, belowY, aboveY, onShown, onHidden)`: the card goes under the line, or
over it when there is no room below, so it never covers what it is about. `GitHunkPopup` is the example —
the anchor comes from `CodeArea.getParagraphBoundsOnScreen` for the part of the hunk that is on screen.

- Set `editora.ownsKeys` on the card; leave `Esc`/the cancel chord and the click-away to the host.
- Do not close the card from an `estimatedScrollY` (or any layout-driven) listener: that property also
  changes while the viewport settles, and the card would close itself as it opens. The host's key and mouse
  events are the only dismissal.
- Run a button's action *after* `overlayHost.hide()`, so focus is back in the editor when it runs.

## Extract a feature coordinator

When a feature's logic bloats `MainController`, pull it into a coordinator behind
[`ui/CoordinatorHost`](../src/main/java/com/editora/ui/CoordinatorHost.java) (the
`LogViewerCoordinator` / `MermaidCoordinator` / `HtmlPreviewCoordinator` pattern):

- a `final class MyCoordinator` in `ui` that owns the feature's service(s) + state and reaches
  the window only through the shared `CoordinatorHost` interface (settings, active/all buffers,
  status, prompts, the overlay host, …);
- `MainController` constructs it with the shared `Services` adapter and keeps **one-line
  delegations** at each call site;
- a fake `Host` in a test makes the coordinator unit-testable without a real window (see the
  `*CoordinatorFxTest`s).

Add to `CoordinatorHost` only the narrow capabilities your coordinator needs; don't hand it the
whole `MainController`.

Keep command IDs and registration order stable during extraction. Move service shutdown alongside
service ownership and retain the controller's lifecycle call. Pass explicit callbacks or existing
renderer coordinators for dependencies outside `CoordinatorHost`; do not expose controller fields.
`ExportCoordinatorFxTest` shows how to replace a native save dialog while testing real exported HTML,
active-buffer switching, filename defaults, and cancellation through the command registry.

For window-specific workflows, use a focused package-private `Host` interface as described in
[window coordinators](subsystems/window-coordinators.md); preserve the public/FXML facade.
