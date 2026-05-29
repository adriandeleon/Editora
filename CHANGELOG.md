# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
- Syntax highlighting with per-language regex rules.
- GitHub Actions CI workflow with a status badge, plus a Maven wrapper.
- README, MIT license, and `CLAUDE.md` project guide.

### Changed

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
