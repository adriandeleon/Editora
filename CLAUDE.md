# Editora

A keyboard-driven, cross-platform programmer's text editor. JDK 25 + JavaFX 25 +
Maven, modular (JPMS, module `com.editora`).

## Commands

- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Build app image / native installer: `mvn -Pdist package`
  - Produces `target/dist/Editora.app` (macOS); OS profiles auto-select DMG/MSI/DEB.
  - Quick unpackaged bundle: `mvn -Pdist -DskipTests -Djpackage.type=APP_IMAGE package`
- Cut a release: bump `<version>` in `pom.xml`, push a `vX.Y.Z` tag (`-rcN` ⇒ pre-release).

Run Maven from the project root (`/Users/adriandeleon/src/adl/Editora-V2`).

## Release pipeline

`.github/workflows/release.yml` runs on a `v*` tag (or manual dispatch for a dry run): a 5-way
matrix (linux x64/arm64, macOS x64/arm64, windows x64 — Windows arm64 is omitted, no hosted runner;
each on its own GitHub-hosted runner) builds the native
installer via the existing `-Pdist` profile — there is **no cross-building** (jpackage + JavaFX are
host-specific), so each runner builds for itself. Installers are renamed per target (jpackage's
DMG/MSI names omit the arch) and uploaded as artifacts; a final job hands them to **JReleaser**
(`jreleaser.yml`, via `jreleaser/release-action`) which creates the GitHub release with all
installers + `checksums.txt` + a changelog. JReleaser only *orchestrates the release* — it does not
build (the `dist` profile is reused as-is) and there is **no `pom.xml`/Maven change**, so the normal
build is unaffected. Installers are currently **unsigned** (signing/notarization is a follow-up).
Uses the BellSoft **Liberica** JDK 25 in CI for full arch coverage (incl. linux aarch64).

## Architecture

Single module `com.editora` (see `src/main/java/module-info.java`). `App` is the
JavaFX entry point: it loads config, builds the command + keymap system, installs
the key dispatcher on the scene, applies the theme, and loads `ui/main.fxml`.

- `command/` — the keyboard-driven core.
  - `Command` / `CommandRegistry`: every action is a registered `Command` (id + title + runnable). The registry feeds both keybindings and the palette.
  - `KeymapManager`: maps chord sequences (e.g. `C-x C-s`) to command ids; loads `resources/com/editora/keymaps/emacs.json`, then overlays user overrides from config.
  - `KeyDispatcher`: a scene-level `KEY_PRESSED` filter that builds chord tokens and resolves them, holding a pending-prefix buffer for multi-key Emacs chords. Lone unbound keys are *not* consumed, so normal typing works.
- `editor/` — `EditorBuffer` wraps a RichTextFX `CodeArea` (line numbers + fold chevrons via `FoldManager`, debounced highlighting). Syntax highlighting uses **TextMate grammars** via tm4e: `GrammarRegistry` (singleton) maps a file extension to a bundled `.tmLanguage.json` grammar under `resources/com/editora/grammars/`; `TextMateHighlighter` tokenizes the document line-by-line (carrying grammar state across lines) and maps each token's TextMate scope to a CSS class themed in `styles/syntax.css`. Files without a bundled grammar are left unstyled. `LanguageRegistry` is now only an extension→language-name resolver used by `FoldRegions`/`FoldManager` (fold strategy) and the File Information panel — it no longer does highlighting. **Grammar resources live in the `com.editora.grammars` package, which `module-info.java` must `opens ... to org.eclipse.tm4e.core`; without that, JPMS encapsulation makes tm4e's `Class.getResourceAsStream` return null at runtime (module path) and grammars silently fail to load even though classpath tests pass.**
- `config/` — `ConfigManager` loads/merges/saves config in `~/.editora-v2/`, falling back to defaults on malformed input (`user.home` is the user profile on every OS). **Preferences** (`Settings` POJO: font, theme, keymap, tab size, view options, keybindings) are **TOML** in `settings.toml` via a Jackson `TomlMapper` (jackson-dataformat-toml). **Session state** (`WorkspaceState` POJO: collapsed fold regions, tool-window layout/visibility/dividers) stays **JSON** in `workspace-state.json`, and `RecentFiles` stays JSON in `recent-files.json`; `config.save()` writes both files. The directory is temporarily `~/.editora-v2/` (instead of `~/.editora/`) to avoid colliding with an existing Editora V1 install. Both POJOs are `@JsonIgnoreProperties(ignoreUnknown = true)`, so `save()` rewrites with only modeled fields. The switch to TOML was a clean cut — a pre-existing `settings.json` is not migrated. (The bundled keymap `emacs.json` and the TextMate grammars stay JSON — they're app resources, not user config.)
- `ui/` — `MainController` (+ `main.fxml`): toolbar, tabbed `EditorBuffer`s, status bar. `CommandPalette` (M-x fuzzy popup) and `FindReplaceBar` (find/replace with case + regex).

## Conventions

- **Adding a feature = add a `Command`, and surface it in the command palette.** Every time we add a command or an applicable user-facing feature, it must be discoverable in the palette. Register it in `MainController.registerCommands()` and, if it needs a key, add a binding to `emacs.json`. The palette is populated from `CommandRegistry.all()`, so a properly registered command appears automatically — never add a user-facing action that bypasses the registry. Toolbar buttons and the palette both dispatch through commands; don't wire UI handlers to logic directly when a command fits.
- **Theming:** AtlantaFX is the user-agent stylesheet and themes standard controls (`Themes`, set via `Application.setUserAgentStylesheet`). It does **not** style the RichTextFX `CodeArea` — editor surface, line-number gutter, and syntax token colors live in `styles/app.css` + `styles/syntax.css` (the **Primer Light** defaults). **Editor color themes** (`EditorThemes`) layer an override stylesheet from `styles/editor-themes/<name>.css` onto the scene *after* those defaults (Primer Light = no override); `MainController.applyEditorTheme` swaps that sheet on the scene and sets each buffer's current-line highlight color (RichTextFX's line-highlight fill is set in code, not CSS). The editor theme follows the AtlantaFX theme (`EditorThemes.defaultFor`) until the user picks one (`Settings.editorThemeUserSet`). Token rules must stay the compound `.text.<class>` form — see the header comment in `syntax.css`.
- **Tests** cover pure logic only (keymap resolution, command registry, config merge, highlighting spans). There is no GUI/toolkit test harness; verify interactive behavior by running the app.

## Packaging note (important)

RichTextFX and its transitive deps (`reactfx`, `flowless`, `undofx`, `wellbehavedfx`)
are **automatic modules**, which `jlink` cannot link. The `moditect-maven-plugin` in
the `dist` profile injects explicit `module-info` descriptors into them. If you bump
RichTextFX or add a dep it uses, you may need to adjust those descriptors' `requires`
(e.g. several of them need `javafx.controls` for `IndexRange`). Use RichTextFX
**0.11.7+** — earlier versions are incompatible with JavaFX 25.

tm4e (the syntax engine) ships as the NetBeans repackaging
`org.netbeans.external:org.eclipse.tm4e.core-0.14.0:RELEASE260` (tm4e is not on
Maven Central). Its Oniguruma backend (`org.jruby.joni:joni`, `org.jruby.jcodings:jcodings`)
and `com.google.code.gson:gson` are already proper modules, but tm4e core is an
automatic module, so `moditect` injects a `module-info` for it too. The NetBeans
jar is also **code-signed**, and `jlink` rejects signed modular jars — the `dist`
profile's antrun step strips `META-INF/*.SF,*.RSA,*.DSA,*.EC` before linking.
