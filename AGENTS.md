# AGENTS.md

## Snapshot
- App name: `Editora`.
- Stack: Java 25 + JavaFX 25 desktop app, Maven wrapper, JPMS in `src/main/java/module-info.java`.
- Launch flow: `Launcher` -> `EditoraApplication.start(...)` -> `editor-view.fxml` -> `EditorController`.
- Core editor widget: RichTextFX `CodeArea`; keep editor features on that path, not `TextArea`.

## Architecture that matters
- `EditoraApplication` owns stage/bootstrap only: load FXML, apply `editora.css`, restore stage state, and call `EditorController.shutdown()` on close.
- `EditorController` is the application shell. It owns tabs, open/save/dirty flow, search/replace, project explorer, settings overlay, command palette, language analysis, recent files, plugin loading, and workspace/session restore.
- `EditorDocument` is the per-tab mutable model. If state belongs to one tab/editor, add it there instead of new controller maps.
- `editor-view.fxml` defines a single-scene shell with collapsible search bar, collapsible explorer, `TabPane`, status bar, command-palette overlay, and settings overlay.
- `settings-view.fxml` + `SettingsController` implement the non-modal settings surface loaded inside the in-scene overlay.
- `session/SessionManager` + `WorkspaceSession` persist recent files, saved open tabs, selected file, shell visibility, search inputs, palette filter, explorer layout, and window geometry.
- `settings/SettingsManager` persists editor preferences; keep settings persistence there, not inside controllers/widgets.
- `languages/` is the extension point for syntax highlighting + diagnostics. `EditorController.analyzeDocument(...)` consumes `LanguageService` output.
- `plugins/PluginManager` loads JARs from `plugins/` with `ServiceLoader`; plugin failures are intentionally fail-soft.

## Project-specific patterns
- Tabs are document-centric: commands should usually operate on `getActiveDocument()`.
- The line fringe/gutter is built via `CodeArea.setParagraphGraphicFactory(...)` in `EditorController`; extend that for diagnostics, markers, or per-line plugin UI.
- Keyboard-centric editor motion should be editor-local: attach Emacs-style navigation filters per `CodeArea` in `EditorController.attachEditorBehavior(...)`, and keep scene-wide shell shortcuts in `installAccelerators(...)`.
- New command-palette actions should be `CommandAction` instances so they appear in the shared palette/menu flow.
- Palette ranking mixes exact/prefix/substring matches, category/description matches, and simple usage counts; preserve that behavior when expanding metadata.
- Search, explorer, status bar, command palette, and settings are all in-scene UI; prefer toggling existing shell panels over adding modal dialogs.
- Keep FXML/controller contracts exact: every `fx:id`, `fx:controller`, and `onAction="#..."` must match Java fields/methods exactly.
- Keep package/resource symmetry: `org.adriandeleon.editora` <-> `src/main/resources/org/adriandeleon/editora/`.

## Workflows
- Use JDK 25. JDK 11 can appear to “compile” against stale classes but cannot build this project correctly.
- Fast validation:
  - `export JAVA_HOME="$('/usr/libexec/java_home' -v 25)"`
  - `export PATH="$JAVA_HOME/bin:$PATH"`
  - `./mvnw -DskipTests compile`
- Full validation: `./mvnw test`
- Run app: `./mvnw javafx:run`
- IDE: prefer the shared `.run/Editora (Maven).run.xml` configuration. If the IDE shows stale `richtextfx@0.11.4` / `flowless@0.7.3` errors, reload the Maven project instead of debugging the code first.
- Build the example plugin into `plugins/`: `./scripts/build-example-plugin.sh`

## Integration notes
- Key runtime deps in `pom.xml`: JavaFX controls/FXML, AtlantaFX, ControlsFX, Ikonli, RichTextFX, Flowless, ReactFX.
- When adding a dependency, update both `pom.xml` and `module-info.java`.
- Exported plugin contracts live in `org.adriandeleon.editora.plugins` and `org.adriandeleon.editora.commands`; keep those APIs small/stable.
- `EditoraContext` is the plugin surface for opening files, editing active text, inspecting workspace/document state, refreshing the explorer, and registering commands/menu actions.
- The example plugin in `examples/timestamp-plugin/` is the reference for dynamic plugin registration and `EditoraContext` usage.

## Guardrails
- Preserve the close-confirmation flow in `requestCloseDocument(...)` / `requestCloseAllDocuments()` when editing document lifecycle behavior.
- Preserve `EditorController.initialize()` / `shutdown()` ordering so session restore happens before fallback tab creation and session save happens on exit.
- If you add persisted workspace shell state, extend `WorkspaceSession`/`SessionManager` rather than creating new preference keys elsewhere.
- If you add new editor intelligence, route it through `LanguageServiceRegistry` and reuse the existing highlighting/diagnostic update path.
