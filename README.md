# Editora Text Editor

[![Java CI with Maven](https://github.com/adriandeleon/Editora/actions/workflows/maven.yml/badge.svg)](https://github.com/adriandeleon/Editora/actions/workflows/maven.yml)

A modern, extensible text editor built with JavaFX 25 and RichTextFX, featuring syntax highlighting, plugin support, and a clean user interface powered by AtlantaFX.

## Features

- **Multi-tab Interface** - Work with multiple files simultaneously
- **Syntax Highlighting** - Built-in support for various programming languages
- **Line Numbers** - Toggle line numbers on/off from the View menu
- **Find & Replace** - Powerful search and replace functionality (Ctrl+F / Ctrl+H)
- **Plugin System** - Extensible architecture for custom plugins
- **Modern UI** - Clean interface using AtlantaFX PrimerLight theme
- **Status Bar** - Real-time display of line/column position, line count, and character count
- **Undo/Redo** - Full undo/redo support
- **Customizable Settings** - Configure font, size, word wrap, and more

## Requirements

- Java 25 or higher
- Maven 3.6+

## Building

```bash
mvn clean compile
```

## Running

```bash
mvn javafx:run
```

Or run the main class directly:

```bash
mvn exec:java -Dexec.mainClass="org.editora.Main"
```

## Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| New File | Ctrl+N |
| Open File | Ctrl+O |
| Save | Ctrl+S |
| Save As | Ctrl+Shift+S |
| Find | Ctrl+F |
| Replace | Ctrl+H |
| Undo | Ctrl+Z |
| Redo | Ctrl+Y |
| Cut | Ctrl+X |
| Copy | Ctrl+C |
| Paste | Ctrl+V |
| Select All | Ctrl+A |
| Settings | Ctrl+, |

## Plugin Development

Editora supports custom plugins. To create a plugin:

1. Implement the `org.editora.plugin.Plugin` interface
2. Package your plugin as a JAR file
3. Place the JAR in the `plugins` directory
4. Restart Editora

Example plugin: See `WordCountPlugin.java` for a reference implementation.

## Technology Stack

- **JavaFX 25** - UI framework
- **RichTextFX 0.11.6** - Advanced text editing capabilities
- **AtlantaFX** - Modern theme library
- **Ikonli Material Design 2** - Icon library
- **SLF4J + Logback** - Logging framework
- **Maven** - Build and dependency management

## Project Structure

```
src/main/java/org/editora/
├── EditorApplication.java     # Main application class
├── Main.java                  # Entry point
├── plugin/                    # Plugin system
│   ├── Plugin.java           # Plugin interface
│   ├── PluginManager.java    # Plugin loader and manager
│   └── examples/             # Example plugins
├── settings/                  # Settings management
│   ├── EditorSettings.java   # Settings model
│   └── SettingsDialog.java   # Settings UI
└── ui/                        # UI components
    ├── CodeEditorArea.java   # Main editor component
    ├── FindReplaceDialog.java # Find/Replace dialog
    └── LineNumberTextArea.java # Line number display (legacy)
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author

Adrian De Leon

## Version

1.0-SNAPSHOT
