# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- File breadcrumb bar (IntelliJ-style) along the bottom, above the status bar: shows the active
  file's path as clickable segments. Click a segment to drop down that folder's contents
  (sub-folders first, then files); pick a folder to drill in (it becomes the trailing crumb and the
  dropdown reopens) or a file to open it. Long paths scroll, anchored to the file. Off by default;
  toggle via the Settings checkbox, the `view.toggleBreadcrumb` palette command, or `C-c p`.
- Runnable fat jar: `mvn -Pfatjar package` builds `target/Editora-<version>.jar`, launchable
  with `java -jar` (bundles JavaFX classes + natives for the build host's platform via a
  non-`Application` `Launcher` main class). The release pipeline builds one per platform and
  attaches them to the GitHub release alongside the native installers.
- Zen mode (distraction-free): one toggle hides the editor view options (80-column ruler,
  current-line highlight, line numbers, minimap, hidden characters), the toolbar, status bar,
  and tab bar, and all open tool windows — leaving just the editor. While in Zen you can still
  switch individual items back on (e.g. line numbers or the status bar); toggling Zen off
  restores your previous configuration exactly. The mode persists across restarts. Via the
  Settings checkbox, the `view.toggleZen` palette command, or `C-c z`.
- Toggle the toolbar, the status bar, and the tab bar — via Settings checkboxes
  ("Show toolbar", "Show status bar", "Show tab bar"), the `view.toggleToolbar`
  (`C-c t`) / `view.toggleStatusBar` (`C-c s`) / `view.toggleTabBar` (`C-c b`)
  palette commands, and persisted in settings.
- Application icon: the Editora logo now appears as the window/dock/taskbar icon, in
  the About dialog, and as the native installer icon (macOS `.icns`, Windows `.ico`,
  Linux `.png`), all generated from `branding/editora-icon.svg`.
- Emacs-style text selection: `C-SPC` sets the mark, then any caret-movement chord
  (`C-f`/`C-b`/`C-n`/`C-p`/`M-f`/`M-b`/`C-a`/`C-e`/`M-<`/`M->`/`C-v`/`M-v`) extends
  the selection from it. `C-x C-x` exchanges point and mark; `C-g` (and a mouse
  click, cut/copy/paste) clears the mark. The region works with `C-w`/`M-w`/`C-y`.
- Bundled monospace fonts (no install needed): JetBrains Mono (the new default),
  Cascadia Code, Fira Code, IBM Plex Mono, and Source Code Pro. They're loaded at
  startup and listed first in the Settings font picker. (Existing configs keep their
  saved font; the new default applies to fresh installs.)
- Editor color themes (syntax tokens + editor surface) chosen in Settings under
  "Editor theme": Primer Light/Dark, Nord Light/Dark, Cupertino Light/Dark, Dracula
  (these match the AtlantaFX themes), plus JetBrains-style Islands Light and Islands
  Dark. Selecting an AtlantaFX app theme switches to the matching editor theme
  automatically, until you pick an editor theme yourself.
- Editor tabs can be reordered by dragging them with the mouse. Pinned tabs stay
  grouped at the front (a drag is clamped to the dragged tab's group).
- Very large files (50 MB or larger) now open read-only with a capped load — at most
  the first 50 MB is read (so a multi-GB log can't exhaust memory) and editing/undo are
  disabled; the status bar notes the truncation.
- Undo history is now bounded (300 entries per view) instead of unlimited, and is
  disabled entirely in large/huge-file mode, capping undo memory.
- Large-file mode: opening a file 5 MB or larger skips syntax highlighting and the
  minimap (regardless of view settings) to stay responsive, and the status bar
  announces this when it happens.
- The main window now remembers its size, position, and maximized state across
  launches (stored in `workspace-state.json`). A saved position that no longer
  lands on a connected screen falls back to the default centered window.
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

- The Switcher (`C-x C-b`) now lists all open files (labeled "Open Files", most-recently-used
  first) instead of only previously-activated ones. It is also larger with bigger column
  headers; the selection bar now tracks the active column (vivid in the focused column, dimmed
  in the other), and the highlighted file's full path is shown at the bottom of the popup, above
  a legend of the navigation keys. A "Switcher: C-x C-b" hint on the toolbar makes the keybinding
  discoverable.
- Syntax highlighting is now incremental: an edit re-tokenizes only from the changed
  line to the end of the document (reusing stored per-line grammar states for the
  unchanged prefix) instead of the whole file, lowering highlight latency on larger
  files. Still runs off the UI thread with stale-result discarding.
- App chrome (toolbar/tool-window icons, status bar, tool windows, command palette,
  switcher, find bar, File Information) now uses AtlantaFX theme variables instead of
  hardcoded light colors, so it adapts to dark themes (icons and text stay legible).
- Clearing the recent-files list now asks for confirmation; each entry in the
  recent-files menu has an inline ✕ icon to remove just that file (no confirmation).
- Go to Line (`M-g g`) now accepts an optional column as `line:column` (e.g.
  `342:35`); a bare number still goes to the line's start. The dialog notes the
  column is optional and the column is clamped to the target line's length. If the
  target line is inside a collapsed fold, the region is unfolded so the line is shown.
- File Information tool window: values are now selectable/copyable (read-only text
  fields), with tighter padding and without the boxed section borders. Long values
  (e.g. the full path) no longer overflow the panel, which previously truncated the
  key labels to "..." and showed scrollbars.
- The Settings font-family picker now lists only monospaced fonts, with a note
  explaining the filter (a previously saved non-monospaced font stays selectable).
- Quitting now asks for confirmation first (the Quit button, `C-x C-c`, and the
  window close button), in addition to the existing per-buffer unsaved-changes prompts.
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

- No more light-gray flash on startup with a dark theme: the scene is pre-filled with
  the theme's background color, so the first frame paints in the theme color instead of
  JavaFX's default background before the CSS is applied.
- Much faster startup when restoring a session: all tab headers are created first
  (so they appear at once), then each file's content and folds are loaded one per
  pulse — the active file first — keeping the UI responsive. Previously a session
  with a heavily-folded file could freeze the window for several seconds on launch.
- Folding no longer smears the hidden lines' numbers onto the fold-header row in
  the gutter: a collapsed paragraph's gutter cell is laid out at zero height, but
  its line-number label did not clip to it; the gutter is now clipped to its bounds.
- "Show hidden characters" no longer piles stray space/tab/EOL markers onto the
  fold-header row when a region is collapsed: folded (hidden) paragraphs are now
  skipped by the whitespace overlay (and the 80-column ruler's measurement).
- Running a command via a chord that opens a dialog no longer types a stray
  character afterwards (e.g. `M-g g` then OK inserted a `g`): the dispatcher now
  also swallows the character event paired with a key press it handled, which a
  modal dialog (`showAndWait`) would otherwise deliver to the editor after closing.
- `M-` (Meta) chords no longer insert a stray character on macOS. Option is the
  Meta key, and `Option+<key>` also emits a special character (e.g. `M-f` → "ƒ",
  `M-x` → "≈") whose KEY_TYPED was inserted into the editor (or palette) after the
  command ran. Characters typed with Alt held are now swallowed on macOS — globally
  by the key dispatcher and in the command palette popup. (Scoped to macOS so
  AltGr-composed characters keep working on other platforms.)
- Command palette entries can now be run with the mouse — clicking a command
  runs it (previously only Enter on the keyboard-selected row worked).
- Next/previous line (`C-n` / `C-p`) now move the caret like Emacs: they were
  bound to RichTextFX's scroll-based `nextLine`/`prevLine` (which move relative to
  the viewport and no-op when the caret is off-screen). They now move by paragraph
  and preserve a goal column, so passing through short lines keeps the original
  column.
- Syntax highlighting no longer intermittently fails to load when multiple files
  of the same language are open. They share one TextMate grammar instance, and
  tm4e's tokenizer is not thread-safe, so concurrent highlighting on each buffer's
  background thread could throw and silently drop a file's highlighting;
  tokenization is now serialized per grammar.
- The minimap no longer stretches short files across its full height (scattering
  sparse blocks): line height is capped, so short documents fill from the top and
  long documents still compress to fit.
- Pinned tabs are now remembered across sessions: a tab's pinned state is saved
  on exit and restored on the next launch (alongside the open files and carets).
- The 80-column ruler now lands exactly on column 80. It was positioned from a
  font-probe character width and ignored the line-number gutter, so it drifted off
  the text grid; the column is now derived from the editor's live layout (caret
  positions, glyph-independent). It is hidden when column 80 is outside the visible
  text width (window too narrow, or scrolled past it).
- The 80-column ruler now tracks horizontal scroll instead of staying pinned to
  a fixed x-offset when a horizontal scrollbar is present.
- The Structure tool window no longer shows "No structure" for documents whose
  regions were computed before the panel attached.
- The editor context menu now dismisses on left-click.

[Unreleased]: https://github.com/adriandeleon/Editora/commits/master
