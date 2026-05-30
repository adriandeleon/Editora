# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The About dialog now shows the build date/time (baked in at build time).
- Editor tab right-click context menu: Close, Close Other Tabs, Close All Tabs,
  Close Unmodified Tabs, Close Tabs to the Left/Right, Copy Path, Pin/Unpin Tab,
  and Rename File…. Pinned tabs are marked (📌), kept grouped at the front, and
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

- The 80-column ruler now tracks horizontal scroll instead of staying pinned to
  a fixed x-offset when a horizontal scrollbar is present.
- The Structure tool window no longer shows "No structure" for documents whose
  regions were computed before the panel attached.
- The editor context menu now dismisses on left-click.

[Unreleased]: https://github.com/adriandeleon/Editora/commits/master
