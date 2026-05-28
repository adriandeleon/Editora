# Editora

A keyboard-driven, cross-platform programmer's text editor built with **JDK 25**,
**JavaFX 25**, and **Maven**. Every action is a registered command, reachable by an
Emacs-style keymap or a fuzzy command palette.

## Features

- **Command-driven core** — every action is a `Command`; bind it to a chord or run it
  from the M-x command palette.
- **Emacs-style keymap** — multi-key chord sequences (e.g. `C-x C-s`), with user overrides.
- **Syntax highlighting** — regex-based, per file extension, via RichTextFX.
- **Editor view options** — 80-column ruler and current-line highlight.
- **Themes** — switchable AtlantaFX themes (Primer, Nord, Cupertino, Dracula).
- **Recent files** — persistent most-recently-used list.
- **Tool windows** — IntelliJ-style dockable panels (Project, Bookmarks, File Information).

## Requirements

- JDK 25+
- Maven 3.9+

## Build & Run

```bash
# Run the app
mvn javafx:run

# Run tests
mvn test

# Build a native app image / installer (DMG on macOS, MSI on Windows, DEB on Linux)
mvn -Pdist package
```

The `dist` profile produces a platform installer under `target/dist/`.

## Configuration

User settings live in `~/.editora-v2/settings.json` (font, theme, keymap, tab size,
view options, and keybinding overrides). Recent files are stored alongside it in
`recent-files.json`.

## License

[MIT](LICENSE) © 2026 Adrian De Leon
