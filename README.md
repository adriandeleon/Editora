# Editora

[![CI](https://github.com/adriandeleon/Editora/actions/workflows/ci.yml/badge.svg)](https://github.com/adriandeleon/Editora/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/github/license/adriandeleon/Editora)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-25-1e90ff)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)
[![Stars](https://img.shields.io/github/stars/adriandeleon/Editora?style=flat)](https://github.com/adriandeleon/Editora/stargazers)

<!-- Uncomment after the first vX.Y.Z release tag:
[![Release](https://img.shields.io/github/v/release/adriandeleon/Editora)](https://github.com/adriandeleon/Editora/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/adriandeleon/Editora/total)](https://github.com/adriandeleon/Editora/releases)
[![Release workflow](https://github.com/adriandeleon/Editora/actions/workflows/release.yml/badge.svg)](https://github.com/adriandeleon/Editora/actions/workflows/release.yml)
-->


A keyboard-driven, cross-platform programmer's text editor built with **JDK 25**,
**JavaFX 25**, [**RichTextFX**](https://github.com/FXMisc/RichTextFX) and **Maven**. Every action is a registered command, reachable by an
Emacs-style keymap or a fuzzy command palette.

## Features

- **Command-driven core** — every action is a `Command`; bind it to a chord or run it
  from the M-x command palette.
- **Keyboard "Jump to…" popups** — fuzzy pickers for recent files (`C-x C-r`), the active file's
  structure/symbols (`M-g i`), open files/tabs (`C-x b`), and tool windows (`M-g t`) — keyboard-first
  alternatives to their list/tool-window UIs.
- **Keyboard file finder** (`C-x C-f`) — Emacs `find-file`-style path popup with prefix
  autocomplete; type/`Tab` to complete, Enter to descend folders or open (or create) a file. The
  Open toolbar icon still uses the native OS dialog.
- **Projects** (off by default; enable in Settings) — VSCode single-folder-workspace style: a root
  folder + its own saved session (open files, layout, folds), shown as a filterable file tree in the
  Project tool window with a project switcher in the toolbar. Open (`C-x C-p`)/switch (`C-x p`)/close
  via the palette or toolbar; switching restores that project's files and layout.
- **Emacs-style keymap** — multi-key chord sequences (e.g. `C-x C-s`), with user overrides.
  (On macOS, the Option dead keys `Option`+`e`/`i`/`u`/`n`/`` ` `` are intercepted by the OS for
  accent composition, so a few `M-`-chords like `M-e` aren't reachable by keyboard there — the
  command palette still works.)
- **Syntax highlighting** — TextMate grammars (via [tm4e](https://github.com/eclipse/tm4e))
  for 21 languages: Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin,
  Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL.
- **Bundled fonts** — JetBrains Mono (default), Cascadia Code, Fira Code, IBM Plex Mono,
  and Source Code Pro ship with the app; no system install required.
- **Editor view options** — 80-column ruler and current-line highlight.
- **Auto / smart indentation** — Enter keeps the indentation and adds a level after a block opener
  (per language: braces, `:` for Python/YAML, `do`/`then` for shell, `def`/`class`/`do` for Ruby, open
  tags for XML/HTML); Enter between a matching pair opens an indented stanza; typing a closing
  bracket/keyword re-aligns the line. Indent unit (tab vs spaces) is inferred from the file.
- **Auto-close & matching brackets** — typing `([{`/quotes inserts the matching closer (type over it to
  skip, wrap a selection by typing a bracket/quote around it, Backspace clears an empty pair); the
  bracket matching the one next to the caret is highlighted.
- **Comment / uncomment** (`M-;`) — toggles a line comment for a single line and a block/region comment
  for a multi-line selection, using the language's comment syntax (`//`, `#`, `<!-- -->`, `/* */`, `--`, …).
- **Spell checking** — red wavy underlines on misspelled words, with right-click suggestions,
  Add-to-Dictionary, and Ignore. Source files only check comments and string literals; plaintext and
  Markdown are checked in full. Toggle via "View: Toggle Spell Check"; choose a dictionary per file
  ("Spell Check: Set Language…", ships English en_US/en_GB, Spanish, and French). Pure-Java (Apache
  Lucene Hunspell).
- **Read-only / View mode** — toggle a buffer read-only (`C-x C-q` or the palette) to view without
  editing; typing and edit commands are blocked while everything else keeps working. Files that aren't
  writable on disk open read-only automatically, and the per-file state is remembered across restarts.
  A Word-style "View Mode" banner docks above the editor with an **Enable Editing** button (when the
  file is writable). While read-only, Space pages down and Backspace pages up (pager-style).
- **Text zoom** — scale the editor text on top of the font size (status-bar `− 100% +`, `C-=`/`C--`/`C-0`,
  Ctrl+mouse-wheel, or the palette); persists across restarts, separate from the font-size setting.
- **Themes** — switchable AtlantaFX themes (Primer, Nord, Cupertino, Dracula), each
  with a matching editor color theme (syntax + surface) that follows the app theme by
  default and is independently selectable in Settings.
- **Markdown preview** — IntelliJ-style 3-mode view (Editor / Editor + Preview / Preview) via a
  floating control top-right of the editor, rendered natively (CommonMark + GFM: tables, task lists,
  strikethrough, autolinks) with **GitHub-style** output — task-list checkboxes, inline-code pills,
  underlined h1/h2, and **images** (local and remote). Live-updating and theme-matched; the mode is
  remembered per file. Zoom the preview text with its `−`/`+` control or, in Preview mode,
  **Ctrl + mouse wheel**.
- **Snippets** — VS Code / TextMate-style templates with interactive tab stops. Type a prefix + Tab to
  expand (or pick via `C-c i` / "Snippet: Insert…"); Tab/Shift-Tab cycle fields, placeholders are
  pre-selected, mirrors update live, `$0` is the final caret. Standard body syntax (`$1`,
  `${1:default}`, mirrors, choices, variables, escapes). Snippets ship for all 21 highlighted languages
  (most from the MIT [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) collection);
  add your own in `~/.editora/snippets/<language>.json` (user snippets override bundled).
- **Autocomplete** — appears as you type (and on demand via `C-M-i` / `M-/`). In **code**, a popup of
  **snippet** completions (accepting expands the snippet with its tab stops; Enter/Tab accept, arrows
  navigate). In **prose** (plain text / Markdown), inline **"ghost text"** — a single greyed
  continuation after the caret from the spell dictionary + your personal dictionary; **Tab** accepts.
  Settings → Editor has a master toggle plus per-source checkboxes (words/prose, snippets).
- **Recent files** — persistent most-recently-used list.
- **Bookmarks** — toggle line bookmarks (`C-c m`) with a gutter marker and optional notes; the
  Bookmarks tool window lists them across all files, `C-c ]`/`C-c [` cycle within a file, and `M-g b`
  is a cross-file jump picker. Reorder bookmarks (and file groups) in the tool window with Alt+Up/Down,
  the right-click menu, or drag-and-drop — the jump picker follows the same order. Saved in
  `bookmarks.json`, scoped per project (switching projects shows that project's bookmarks; deleting a
  project deletes its bookmarks).
- **Git** — uses your installed `git` (no bundled library). The status bar shows the current branch with
  ahead/behind counts (click to switch branches); the gutter draws change bars vs `HEAD` (added /
  modified / deleted); and the **Commit** tool window (`M-4`) lists Staged / Changes / Untracked files with
  stage, unstage, discard, **Stage All**, and a commit box. Palette/keys cover commit (`C-x g`), stage
  current file, switch/new branch, fetch/pull/push, and **clone** ("Git: Clone Repository…" clones a
  repo and opens a file from it — independent of projects). All off the UI thread; hidden when not in a
  repo or when `git` isn't on `PATH`.
- **Tool windows** — IntelliJ-style dockable panels (Project, Commit, Bookmarks, Structure, File Information).
- **Settings** — a category sidebar (Appearance, Editor, Tool Windows, Spell Check, Application, …) with a
  search box, a live font/theme preview, and Reset to Defaults. Changes apply instantly.
- **Multi-language interface** — run Editora in **English, Italian, Spanish, French, Portuguese, or
  German**. Pick a language under Settings → Appearance → Language (default *Automatic* follows your
  system language, falling back to English); the change applies on the next restart.

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

# Build a runnable fat jar, then launch it
./mvnw -Pfatjar package
java -jar target/Editora-<version>.jar
```

The `dist` profile produces a platform installer under `target/dist/`.

The `fatjar` profile produces a self-contained, runnable `target/Editora-<version>.jar` (no separate
JavaFX install needed — `java -jar` is enough, on a JDK 25 runtime). It bundles JavaFX's classes and
native libraries **for the build host's platform only**: a single jar can't be portable because
JavaFX's macOS/Linux x64 and arm64 native libraries share filenames and would collide. To get a jar
for another OS/arch, build the profile on that platform (or grab the per-platform jars from a
[release](#releases)).

On startup the fat jar prints `WARNING: Unsupported JavaFX configuration: classes were loaded from
'unnamed module …'`. This is harmless and expected: JavaFX notes that it's running from the
classpath rather than the module path (which is how a fat jar works). The app runs normally, and the
warning cannot be cleanly suppressed — the native installers launch from the module path and don't
show it.

## Releases

Tagged releases publish native installers **and runnable fat jars** to
[GitHub Releases](https://github.com/adriandeleon/Editora/releases) for Linux (x64 and arm64),
macOS (x64 and arm64), and Windows (x64). A GitHub Actions matrix builds each installer with `-Pdist`
and the matching `Editora-<version>-<platform>.jar` with `-Pfatjar` on its own runner, and
[JReleaser](https://jreleaser.org) assembles the release (config in `jreleaser.yml`). Prefer the
installer for a normal setup; the fat jar is handy if you already have a JDK 25 and just want
`java -jar`.

To cut a release: bump `<version>` in `pom.xml`, commit, then push a matching tag:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

A `-rcN` suffix (e.g. `v1.2.3-rc1`) is published as a pre-release. Installers are currently
**unsigned**, so macOS Gatekeeper / Windows SmartScreen will warn on first launch.

## Command line

```
editora [options] [FILE[:LINE[:COLUMN]] ...]

  --config-dir <path>   Use <path> as the config directory (or set EDITORA_CONFIG_DIR)
  --dev                 Dev mode: use ~/.editora-dev (separate from production config)
  --project[=]<dir>     Open <dir> as a project (only when Projects are enabled; ignored otherwise)
  --zen                 Start in Zen (distraction-free) mode
  --version, -V         Print the version and exit
  --help, -h            Print help and exit

  FILE                  Open FILE (also FILE:LINE and FILE:LINE:COLUMN to jump)
```

File and `--project` arguments are **additive**: your previous session restores as usual, then the
given file(s) open on top (focused) and the editor jumps to any `LINE:COLUMN`. `--version`/`--help`
print and exit without opening a window. Works on macOS, Linux, and Windows.

## Configuration

User preferences live in `~/.editora/settings.toml` (font, theme, keymap, tab size,
view options, auto-save mode, and keybinding overrides). Session state — collapsed fold
regions and tool-window layout — is stored as JSON in `workspace-state.json`, recent
files in `recent-files.json`, and bookmarks (scoped per project) in `bookmarks.json`, all
alongside it.

To use a different config folder, pass `--config-dir <path>` (or `--config-dir=<path>`) on the command
line, or set the `EDITORA_CONFIG_DIR` environment variable. Precedence is **`--config-dir` >
`EDITORA_CONFIG_DIR` > `--dev` (`~/.editora-dev/`) > the default `~/.editora/`**. Works on macOS,
Linux, and Windows.

For running a development instance alongside your everyday editor, pass `--dev` to use a separate
`~/.editora-dev/` config directory, so the two never share settings or session state. (`--config-dir`
and `EDITORA_CONFIG_DIR` still take precedence if you also set them.)

Auto save is off by default; enable it in Settings ("After delay" or "On focus change")
or cycle the mode with `C-c a`. It only saves files that already have a path.

## License

[MIT](LICENSE) © 2026 Adrián Arturo De León Saldivar

Editora bundles third-party libraries and TextMate grammars under their own
licenses. See [NOTICE](NOTICE) for attributions.
