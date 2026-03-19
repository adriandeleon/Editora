# Editora

`Editora` is a modern JavaFX text editor prototype built on Java 25, JavaFX 25, AtlantaFX, and RichTextFX.

## Implemented starter slices
- Tabbed editor workspace
- Project explorer rooted at the current workspace folder
- Collapsible project explorer and collapsible search/replace bar
- File open / save / save as / dirty tab state
- Recent files menu and workspace/session restore for saved documents
- In-scene command palette overlay
- Command palette ranking, category metadata, shortcut hints, and keyboard navigation
- Full settings screen loaded from FXML
- RichTextFX `CodeArea` with line numbers and line fringe
- Keyboard-centric editing with core Emacs-style movement in the active editor tab
- Light / dark theme switching via AtlantaFX
- Search / replace in the active tab
- Dynamic plugin loading from `plugins/`
- Stronger plugin API through `EditoraContext` (open files, inspect workspace, modify active editor)
- Starter language-service scaffolding with Java highlighting + lightweight diagnostics
- Example timestamp plugin

## Quick start
Use a JDK 25 installation.

```zsh
export JAVA_HOME="$('/usr/libexec/java_home' -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -DskipTests compile
```

## Run the app
```zsh
./mvnw javafx:run
```

## Run from the IDE
- Prefer the shared run configuration: `Editora (Maven)`.
- It launches the app through Maven (`javafx:run`), which uses the versions declared in `pom.xml`.

Use the shared Maven run configuration or reload the Maven project in the IDE before running again.

## Build the example plugin
```zsh
./scripts/build-example-plugin.sh
```

That writes `plugins/timestamp-plugin.jar`, which Editora loads on startup.

## Notes on persistence
- Recent files are persisted between launches.
- The current workspace root is persisted between launches.
- Saved files that were open when the app closed are restored on startup.
- Search/replace bar visibility is restored between launches.
- Project explorer visibility is restored between launches.
- Project explorer width/divider position is restored between launches.
- Search text, replace text, and the last command palette filter are restored between launches.
- Status bar visibility is restored between launches.
- Window size, position, and maximized state are restored between launches.

Unsaved untitled tabs are intentionally not restored.

## Project map
- `src/main/java/org/adriandeleon/editora/EditoraApplication.java` — stage/bootstrap
- `src/main/java/org/adriandeleon/editora/EditorController.java` — main editor shell logic
- `src/main/java/org/adriandeleon/editora/documents/EditorDocument.java` — per-tab document state
- `src/main/java/org/adriandeleon/editora/languages/` — syntax highlighting + diagnostics scaffolding
- `src/main/java/org/adriandeleon/editora/session/` — recent files + workspace/session restore
- `src/main/java/org/adriandeleon/editora/plugins/` — plugin API + loader
- `src/main/resources/org/adriandeleon/editora/editor-view.fxml` — main shell
- `src/main/resources/org/adriandeleon/editora/settings-view.fxml` — settings screen

## Plugin API highlights
Plugins currently integrate through `EditoraContext` and can:
- register command-palette actions
- register plugin menu actions
- open files in the editor
- insert/replace text in the active editor
- inspect the active/open document paths
- inspect the current workspace root
- trigger a workspace refresh
- post status-bar messages

