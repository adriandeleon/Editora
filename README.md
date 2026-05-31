# Editora

[![CI](https://github.com/adriandeleon/Editora/actions/workflows/ci.yml/badge.svg)](https://github.com/adriandeleon/Editora/actions/workflows/ci.yml)

A keyboard-driven, cross-platform programmer's text editor built with **JDK 25**,
**JavaFX 25**, and **Maven**. Every action is a registered command, reachable by an
Emacs-style keymap or a fuzzy command palette.

## Features

- **Command-driven core** — every action is a `Command`; bind it to a chord or run it
  from the M-x command palette.
- **Emacs-style keymap** — multi-key chord sequences (e.g. `C-x C-s`), with user overrides.
- **Syntax highlighting** — TextMate grammars (via [tm4e](https://github.com/eclipse/tm4e))
  for 21 languages: Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin,
  Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL.
- **Editor view options** — 80-column ruler and current-line highlight.
- **Themes** — switchable AtlantaFX themes (Primer, Nord, Cupertino, Dracula), each
  with a matching editor color theme (syntax + surface) that follows the app theme by
  default and is independently selectable in Settings.
- **Recent files** — persistent most-recently-used list.
- **Tool windows** — IntelliJ-style dockable panels (Project, Bookmarks, File Information).

## Requirements

- JDK 25+
- Maven 3.9+

## Build & Run

A Maven wrapper is included, so no local Maven install is required — use `./mvnw`
(or `mvnw.cmd` on Windows). Plain `mvn` works too if you have Maven installed.

```bash
# Run the app
./mvnw javafx:run

# Run tests
./mvnw test

# Build a native app image / installer (DMG on macOS, MSI on Windows, DEB on Linux)
./mvnw -Pdist package
```

The `dist` profile produces a platform installer under `target/dist/`.

## Releases

Tagged releases publish native installers to
[GitHub Releases](https://github.com/adriandeleon/Editora/releases) for Linux (x64 and arm64),
macOS (x64 and arm64), and Windows (x64). A GitHub Actions matrix builds each installer with `-Pdist`
on its own runner and [JReleaser](https://jreleaser.org) assembles the release (config in `jreleaser.yml`).

To cut a release: bump `<version>` in `pom.xml`, commit, then push a matching tag:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

A `-rcN` suffix (e.g. `v1.2.3-rc1`) is published as a pre-release. Installers are currently
**unsigned**, so macOS Gatekeeper / Windows SmartScreen will warn on first launch.

## Configuration

User preferences live in `~/.editora-v2/settings.toml` (font, theme, keymap, tab size,
view options, and keybinding overrides). Session state — collapsed fold regions and
tool-window layout — is stored as JSON in `workspace-state.json`, and recent files in
`recent-files.json`, both alongside it.

## License

[MIT](LICENSE) © 2026 Adrian De Leon

Editora bundles third-party libraries and TextMate grammars under their own
licenses. See [NOTICE](NOTICE) for attributions.
