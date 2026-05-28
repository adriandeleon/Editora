# Editora

A keyboard-driven, cross-platform programmer's text editor. JDK 25 + JavaFX 25 +
Maven, modular (JPMS, module `com.editora`).

## Commands

- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Build app image / native installer: `mvn -Pdist package`
  - Produces `target/dist/Editora.app` (macOS); OS profiles auto-select DMG/MSI/DEB.
  - Quick unpackaged bundle: `mvn -Pdist -DskipTests -Djpackage.type=APP_IMAGE package`

Run Maven from the project root (`/Users/adriandeleon/src/adl/Editora-V2`).

## Architecture

Single module `com.editora` (see `src/main/java/module-info.java`). `App` is the
JavaFX entry point: it loads config, builds the command + keymap system, installs
the key dispatcher on the scene, applies the theme, and loads `ui/main.fxml`.

- `command/` — the keyboard-driven core.
  - `Command` / `CommandRegistry`: every action is a registered `Command` (id + title + runnable). The registry feeds both keybindings and the palette.
  - `KeymapManager`: maps chord sequences (e.g. `C-x C-s`) to command ids; loads `resources/com/editora/keymaps/emacs.json`, then overlays user overrides from config.
  - `KeyDispatcher`: a scene-level `KEY_PRESSED` filter that builds chord tokens and resolves them, holding a pending-prefix buffer for multi-key Emacs chords. Lone unbound keys are *not* consumed, so normal typing works.
- `editor/` — `EditorBuffer` wraps a RichTextFX `CodeArea` (line numbers via `LineNumberFactory`, debounced highlighting). `SyntaxHighlighter` + `LanguageRules` + `LanguageRegistry` do regex-based highlighting per file extension.
- `config/` — `ConfigManager` resolves a platform config dir and loads/merges/saves `config.json` (Jackson), falling back to defaults on malformed input. `Settings` is the POJO.
- `ui/` — `MainController` (+ `main.fxml`): toolbar, tabbed `EditorBuffer`s, status bar. `CommandPalette` (M-x fuzzy popup) and `FindReplaceBar` (find/replace with case + regex).

## Conventions

- **Adding a feature = add a `Command`, and surface it in the command palette.** Every time we add a command or an applicable user-facing feature, it must be discoverable in the palette. Register it in `MainController.registerCommands()` and, if it needs a key, add a binding to `emacs.json`. The palette is populated from `CommandRegistry.all()`, so a properly registered command appears automatically — never add a user-facing action that bypasses the registry. Toolbar buttons and the palette both dispatch through commands; don't wire UI handlers to logic directly when a command fits.
- **Theming:** AtlantaFX (Primer Light) is the user-agent stylesheet and themes standard controls. It does **not** style the RichTextFX `CodeArea` — editor surface, line-number gutter, and syntax token colors live in `styles/app.css` and `styles/syntax.css`.
- **Tests** cover pure logic only (keymap resolution, command registry, config merge, highlighting spans). There is no GUI/toolkit test harness; verify interactive behavior by running the app.

## Packaging note (important)

RichTextFX and its transitive deps (`reactfx`, `flowless`, `undofx`, `wellbehavedfx`)
are **automatic modules**, which `jlink` cannot link. The `moditect-maven-plugin` in
the `dist` profile injects explicit `module-info` descriptors into them. If you bump
RichTextFX or add a dep it uses, you may need to adjust those descriptors' `requires`
(e.g. several of them need `javafx.controls` for `IndexRange`). Use RichTextFX
**0.11.7+** — earlier versions are incompatible with JavaFX 25.
