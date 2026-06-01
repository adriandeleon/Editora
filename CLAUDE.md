# Editora

A keyboard-driven, cross-platform programmer's text editor. JDK 25 + JavaFX 25 +
Maven, modular (JPMS, module `com.editora`).

## Commands

- Run the app: `mvn javafx:run`
- Run tests: `mvn test`
- Build app image / native installer: `mvn -Pdist package`
  - Produces `target/dist/Editora.app` (macOS); OS profiles auto-select DMG/MSI/DEB.
  - Quick unpackaged bundle: `mvn -Pdist -DskipTests -Djpackage.type=APP_IMAGE package`
- Build a runnable fat jar: `mvn -Pfatjar package` ⇒ `target/Editora-<version>.jar`, run with
  `java -jar`. It bundles JavaFX (classes + natives) for **the build host's platform only** and runs
  from the classpath via the non-`Application` `com.editora.Launcher` main class. A single
  all-platforms jar is impossible (JavaFX's macOS/Linux x64 and arm64 natives share filenames and
  collide), so the release CI builds one fat jar per runner.
- Cut a release: bump `<version>` in `pom.xml`, push a `vX.Y.Z` tag (`-rcN` ⇒ pre-release).

Run Maven from the project root (`/Users/adriandeleon/src/adl/Editora-V2`).

## Release pipeline

`.github/workflows/release.yml` runs on a `v*` tag (or manual dispatch for a dry run): a 5-way
matrix (linux x64/arm64, macOS x64/arm64, windows x64 — Windows arm64 is omitted, no hosted runner;
each on its own GitHub-hosted runner) builds the native
installer via the existing `-Pdist` profile — there is **no cross-building** (jpackage + JavaFX are
host-specific), so each runner builds for itself. Each runner also builds a per-platform runnable
fat jar via `-Pfatjar` (`Editora-<version>-<target>.jar`). Installers are renamed per target
(jpackage's DMG/MSI names omit the arch) and uploaded as artifacts alongside the fat jar; a final job
hands them to **JReleaser** (`jreleaser.yml`, via `jreleaser/release-action`) which creates the
GitHub release with all installers + fat jars + `checksums.txt` + a changelog. JReleaser only *orchestrates the release* — it does not
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
- `editor/` — `EditorBuffer` wraps a RichTextFX `CodeArea` (line numbers + fold chevrons via `FoldManager`, debounced highlighting). **Bookmarks**: a per-buffer `BookmarkManager` (analogous to `FoldManager`) holds line→`Bookmark` (record `line/note/lineText` in `config`), tracks lines through edits via `plainTextChanges` so bookmarks **follow their content** with forward/"sticky" gravity — inserting at a bookmarked line's start pushes it down (pure, column-aware `BookmarkManager.shift(...)` is unit-tested), and the gutter draws a `.bookmark-marker` glyph (wired through `FoldManager.setBookmarkHooks`, refreshed per-line via `recreateParagraphGraphic`). Persistence mirrors folds exactly: `WorkspaceState.bookmarks: Map<path,List<Bookmark>>`, `MainController.persistBookmarks`/`restoreBookmarks`. The **global** `BookmarksPanel` reads that map (so it includes closed files) and routes edits back through the controller (open buffer's manager, else the map). Commands: `bookmarks.toggle` (`C-c m`), `bookmarks.next`/`previous` (`C-c ]`/`C-c [`, within-file), `bookmarks.jump` (`M-g b`, cross-file picker), `editNote`/`clearFile`. **Markdown preview**: an IntelliJ-style per-buffer 3-mode view (`EditorBuffer.MarkdownViewMode` EDITOR/SPLIT/PREVIEW) driven by a floating `MarkdownViewToggle` overlaid top-right of the editor for Markdown files; `MarkdownRenderer` parses CommonMark+GFM (commonmark-java, all proper JPMS modules — `requires org.commonmark.*`, no moditect) **off-thread** then builds native JavaFX nodes **on the FX thread** under a stale-gen guard (the `highlightExecutor` idiom), debounced via `multiPlainChanges().successionEnds(250ms)`, only while SPLIT/PREVIEW (≥5 MB files render once). The mode is persisted per file in `WorkspaceState.markdownViewModes` (`MainController.persistMarkdownMode`/`restoreMarkdownMode`), mirroring folds. Preview colors use AtlantaFX semantic CSS (`.markdown-preview`/`.md-*` in `app.css`) so they track the theme without per-theme overrides. Syntax highlighting uses **TextMate grammars** via tm4e: `GrammarRegistry` (singleton) maps a file extension to a bundled `.tmLanguage.json` grammar under `resources/com/editora/grammars/`; `TextMateHighlighter` tokenizes the document line-by-line (carrying grammar state across lines) and maps each token's TextMate scope to a CSS class themed in `styles/syntax.css`. Files without a bundled grammar are left unstyled. `LanguageRegistry` is now only an extension→language-name resolver used by `FoldRegions`/`FoldManager` (fold strategy) and the File Information panel — it no longer does highlighting. **Grammar resources live in the `com.editora.grammars` package, which `module-info.java` must `opens ... to org.eclipse.tm4e.core`; without that, JPMS encapsulation makes tm4e's `Class.getResourceAsStream` return null at runtime (module path) and grammars silently fail to load even though classpath tests pass.**
- `config/` — `ConfigManager` loads/merges/saves config in the config dir — the `EDITORA_CONFIG_DIR` env var if set (used verbatim), else `~/.editora/` (`user.home` is the user profile on every OS); resolved by the unit-tested pure `ConfigManager.resolveConfigDir(...)`. Falls back to defaults on malformed input. **Preferences** (`Settings` POJO: font, theme, keymap, tab size, view options, keybindings) are **TOML** in `settings.toml` via a Jackson `TomlMapper` (jackson-dataformat-toml). **Text zoom** is `Settings.fontZoom` (1.0 = 100%) — a persisted Settings field deliberately *not* shown in the Settings window; `MainController.applyViewSettings` applies the effective size `round(fontSize*fontZoom)` to every buffer, `textZoom(±1/0)` adjusts it (status-bar `−/+`, `C-=`/`C--`/`C-0`, and the scene-level `Ctrl`+mouse-wheel filter in `App`). **Session state** (`WorkspaceState` POJO: collapsed fold regions, bookmarks, tool-window layout/visibility/dividers) stays **JSON** in `workspace-state.json`, and `RecentFiles` stays JSON in `recent-files.json`; `config.save()` writes both files. Both POJOs are `@JsonIgnoreProperties(ignoreUnknown = true)`, so `save()` rewrites with only modeled fields. The switch to TOML was a clean cut — a pre-existing `settings.json` is not migrated. (The bundled keymap `emacs.json` and the TextMate grammars stay JSON — they're app resources, not user config.) The About dialog shows the live settings path via `ConfigManager.getSettingsFile()` — don't hardcode it. **Projects** (`ProjectManager`, index in `projects.json`) are named single-folder workspaces; each project's session is a separate `WorkspaceState` JSON under `projects/<id>.json`, and `ConfigManager.setWorkspaceStateFile(...)` swaps which one is the active session (default = `workspace-state.json` when no project). Switching a project reuses `MainController.persistSession()`/`openInitialBuffer()`.
- `ui/` — `MainController` (+ `main.fxml`): toolbar, tabbed `EditorBuffer`s, status bar. `CommandPalette` (M-x fuzzy popup) and `FindReplaceBar` (find/replace with case + regex).

## Conventions

- **Performance is a top priority.** Editora must stay fast and responsive, especially on large files. Treat the UI thread as sacred and these as the hot paths: typing/editing, scrolling, syntax highlighting, the document overlays (whitespace, minimap, 80-column ruler), and the line-number gutter. Rules to uphold:
  - **Never block the JavaFX Application Thread** with non-trivial work — tokenize/parse/search off-thread (see the `highlightExecutor` pattern in `EditorBuffer`) and apply results back on the FX thread with a generation/stale-result guard.
  - **Debounce and coalesce** — re-highlighting is debounced; overlay/ruler/minimap redraws coalesce to one per pulse (a `pending` flag + `Platform.runLater`). Don't add per-keystroke or per-scroll-pulse work that isn't coalesced.
  - **Work incrementally / only on what's visible** — highlighting re-tokenizes only from the changed line; overlays iterate just the visible paragraphs (`firstVisibleParToAllParIndex … last`) and skip folded lines. Avoid O(document) work on edits or scrolls.
  - **Don't defeat the per-node CSS style cache** — keep token rules as the compound `.text.<class>` selector (see `syntax.css`); coalesce adjacent same-style spans (`SpanMerger`) before `setStyleSpans`; never query `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event.
  - **Large/huge files** already disable highlighting + minimap (≥5 MB) and go read-only with a capped load (≥50 MB); preserve those guards. Bound memory (undo history is capped; loads are capped).
  - **Assess and report the performance cost of every change.** For any implementation or bug fix, evaluate its cost on the hot paths (allocation per keystroke/scroll, added FX-thread work, extra layout/CSS passes, memory) and tell the user about it — even if it's "negligible." If a change risks a regression, measure (e.g. the temporary `System.nanoTime` instrumentation used for the startup investigation) rather than guess.
- **Adding a feature = add a `Command`, and surface it in the command palette.** Every time we add a command or an applicable user-facing feature, it must be discoverable in the palette. Register it in `MainController.registerCommands()` and, if it needs a key, add a binding to `emacs.json`. The palette is populated from `CommandRegistry.all()`, so a properly registered command appears automatically — never add a user-facing action that bypasses the registry. Toolbar buttons and the palette both dispatch through commands; don't wire UI handlers to logic directly when a command fits.
- **App icon / branding:** the source logo is `branding/editora-icon.svg`. Window-icon PNGs (16–512) live in `resources/com/editora/icons/` (loaded into `stage.getIcons()` in `App`; also the About-dialog logo). Native-installer icons `branding/editora.{icns,ico,png}` are generated from the SVG (rsvg-convert + iconutil + a small ICO writer) and passed to jpackage via `${jpackage.icon}`, set per-OS in the OS-activated profiles. Regenerate after editing the SVG.
- **Bundled fonts:** five monospace families ship under `resources/com/editora/fonts/<family>/` (Regular/Bold/Italic/BoldItalic TTFs; Fira Code has no italic). `Fonts.load()` registers them via `Font.loadFont` at the very start of `App.start` (before any CSS), so they resolve by family name regardless of the OS. `Fonts.BUNDLED` (JetBrains Mono first = default) is listed ahead of the system monospaced families in the Settings font picker. They're plain resources (no `module-info`/moditect change) and are picked up by the `dist` build automatically. Attributed in `NOTICE` (all OFL-1.1).
- **Theming:** AtlantaFX is the user-agent stylesheet and themes standard controls (`Themes`, set via `Application.setUserAgentStylesheet`). It does **not** style the RichTextFX `CodeArea` — editor surface, line-number gutter, and syntax token colors live in `styles/app.css` + `styles/syntax.css` (the **Primer Light** defaults). **Editor color themes** (`EditorThemes`) layer an override stylesheet from `styles/editor-themes/<name>.css` onto the scene *after* those defaults (Primer Light = no override); `MainController.applyEditorTheme` swaps that sheet on the scene and sets each buffer's current-line highlight color (RichTextFX's line-highlight fill is set in code, not CSS). The editor theme follows the AtlantaFX theme (`EditorThemes.defaultFor`) until the user picks one (`Settings.editorThemeUserSet`). Token rules must stay the compound `.text.<class>` form — see the header comment in `syntax.css`. The **Project tool window file tree** is themed via two looked-up CSS colors `-project-folder-color`/`-project-file-color` (defined on `.project-tree` in `app.css`, defaulting to AtlantaFX accent/muted); each editor theme overrides them, so a new editor theme should add a `.project-tree { -project-folder-color: …; -project-file-color: …; }` rule.
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
