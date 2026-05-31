# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Release pipeline: pushing a `vX.Y.Z` tag builds native installers for Linux
  (x64/arm64), macOS (x64/arm64), and Windows (x64) on per-platform GitHub runners
  and publishes a GitHub Release via JReleaser (`jreleaser.yml` + `release.yml`).
  Installers are currently unsigned.
- Session restore: on launch, reopens the files that were open at last exit (in tab
  order), reselects the active tab, and restores each file's caret position (scrolled
  into view). Missing files are skipped; an empty session opens a fresh buffer.
- Editor split view: toolbar icons (and `view.splitVertical` / `view.splitHorizontal`
  / `view.unsplit` commands, `C-x 3` / `C-x 2` / `C-x 1`) toggle a second synced view
  of the current file — side by side or stacked. Edits/highlighting stay in sync;
  scroll, caret, and minimap are per-pane. The icons reflect the active split state.
- "Show hidden characters" view option: renders markers for spaces (·), tabs (→),
  and line ends (¶) on a transparent overlay without altering the document.
  Toggle via `view.toggleWhitespace` (`C-c w`) or Settings; off by default.
- The About dialog now shows the build date/time (baked in at build time).
- Editor tab right-click context menu: Close, Close Other Tabs, Close All Tabs,
  Close Unmodified Tabs, Close Tabs to the Left/Right, Copy Path, Pin/Unpin Tab,
  and Rename File…. Pinned tabs are marked with a pin icon, kept grouped at the front, and
  skipped by the bulk-close actions. Each action is also a palette command
  (`buffer.closeOthers`, `buffer.closeAll`, `buffer.copyPath`, `buffer.togglePin`,
  `buffer.rename`, …). Tabs now show a full-path tooltip on hover.
- Status bar segments: live cursor position/selection, language, indentation
  (tab size), line endings, file size, and encoding, shown right of the message area.
  Segments are clickable and dispatch commands — `nav.goToLine` (`M-g g`),
  `buffer.setLanguage` (override the syntax grammar), `buffer.setTabSize`, and
  `buffer.convertLineEndings` (LF/CRLF). Tab size now affects the minimap.
- Syntax highlighting via bundled TextMate grammars (using tm4e) for 21
  languages: Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin,
  Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL.
  Stateful tokenization carries grammar state across lines so multi-line
  constructs (block comments, heredocs, fenced code) highlight correctly.
- Code folding now covers more languages (C, C++, Rust, Go, Kotlin, Groovy, C#,
  CSS) in addition to Java, JSON, XML/HTML, and Markdown.
- `NOTICE` file attributing the bundled TextMate grammars and third-party
  libraries.
- Structure tool window: a collapsible tree of the active file's foldable
  regions with a search filter and Emacs-style keyboard navigation. Selecting
  an entry navigates the editor and anchors the target line at the top of the
  viewport. Bound to `M-7`.
- `C-x o` (Window: Other) to cycle keyboard focus between the editor and open
  tool windows.
- Code/text folding with `view.foldAll` (`C-c f`) and `view.unfoldAll`
  (`C-c u`); fold state persists across sessions.
- Editor view options for line numbers and a minimap.
- Theme switching and a recent-files list.
- IntelliJ-style left/right/bottom tool windows, a buffer Switcher, and a File
  Information panel.
- Toolbar icons, a settings window, and an expanded Emacs keymap.
- Command palette (`M-x`) with fuzzy matching, driven by the command registry.
- GitHub Actions CI workflow with a status badge, plus a Maven wrapper.
- README, MIT license, and `CLAUDE.md` project guide.

### Changed

- User preferences are now stored as TOML in `~/.editora-v2/settings.toml` (was
  `settings.json`). Session state (fold regions, tool-window layout) moved to a
  separate `workspace-state.json`; recent files stay in `recent-files.json`. No
  migration — a pre-existing `settings.json` is ignored.
- Closing a pinned tab now asks for confirmation first (the X, the Close menu
  item, and the close command); bulk-close actions still skip pinned tabs.
- The Structure tool window now labels entries with the symbol name and kind
  (functions, types/classes, namespaces, Markdown headings, XML tags) derived
  from the TextMate grammar, and hides trivial blocks (if/for/while/…), instead
  of showing the raw brace line. Works across all bundled languages; clicking a
  node jumps to the symbol's line.
- Folded regions now shade their header line (in addition to the gutter chevron)
  so collapsed code is easier to spot.
- Opening a file that is already open now switches to its existing tab instead
  of opening a duplicate.
- The recent-files picker is now a toolbar menu button (with a per-entry remove
  action) instead of a combo box.
- The key dispatcher now yields only single-key chords to windows that own
  their keys (e.g. the Structure tool window); multi-key Emacs chords such as
  `C-x o` stay global so window switching and `C-x`/`C-c` commands work from
  anywhere.

### Fixed

- Pinned tabs are now remembered across sessions: a tab's pinned state is saved
  on exit and restored on the next launch (alongside the open files and carets).
- The 80-column ruler now tracks horizontal scroll instead of staying pinned to
  a fixed x-offset when a horizontal scrollbar is present.
- The Structure tool window no longer shows "No structure" for documents whose
  regions were computed before the panel attached.
- The editor context menu now dismisses on left-click.

[Unreleased]: https://github.com/adriandeleon/Editora/commits/master
