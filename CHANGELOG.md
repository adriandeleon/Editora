# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Editor froze (render thread crashed) when opening a Markdown file straight into Preview/Split
  mode** — the editor's `Canvas` overlays (minimap, whitespace, spell-check, note-highlight, Mermaid
  lint) were sized directly from the editor pane, which is momentarily zero-width while a Markdown
  buffer opens into Preview/Split. A buffered draw on a zero/NaN-sized canvas made JavaFX allocate a
  null GPU texture and the render thread NPE'd in `NGCanvas` — stalling the whole pipeline so the UI
  looked hung. Canvas dimensions are now clamped to a finite, in-range size (new unit-tested
  `CanvasGuards`) and overlays skip painting into a collapsed surface.
- **External tools not found in the installed app** — a GUI-launched app (a macOS `.app` from Finder,
  a Linux `.desktop`) inherits a stripped `PATH` that omits Homebrew / npm / Node locations, so `mmdc`,
  `npx`/`maid` (and a Homebrew-installed `git`) showed up as "not found" even when installed. Editora now
  augments the subprocess `PATH` with the usual install dirs (e.g. `/opt/homebrew/bin`, `/usr/local/bin`)
  and resolves a bare command name to its absolute path before launching. Setting an absolute path in
  Settings still works as an override.
- **App freeze with several Markdown previews open (macOS)** — rendering SVG badges in the Markdown
  preview pulled in AWT/Java2D, whose native macOS pipeline contended with JavaFX for the AppKit run
  loop, an intermittent deadlock that grew more likely the more previews were open. AWT/Java2D now runs
  headless (software rasterization only), eliminating the conflict; SVG badges still render.
- The **Markdown preview table** columns no longer collapse to one character wide (short headers like
  "Phase"/"Depends on" stacking letter-by-letter); columns now size proportionally to their content.
- **Thread leak on tab close** — each open file's two background worker threads (Markdown preview +
  syntax highlighter) are now shut down when its tab closes, instead of lingering for the rest of the
  session.
- The **message log** opened from the status bar now toggles: clicking the status message again closes
  the popup.
- The **Welcome page** now shows a scrollbar (vertical or horizontal) when its content doesn't fit the
  window, and its tab can be dragged to reorder like any other tab.

### Added

- **Printing** — two new commands show a **print preview** first, then send to the printer.
  **`File: Print…`** (`editor.print`) previews/prints the active buffer's source code — syntax-highlighted
  with an optional line-number gutter, paginated by whole lines, in a clean light theme. **`File: Print
  Preview…`** (`preview.print`) handles the rendered **Markdown** preview (block-aware pagination — a
  paragraph, table, or image is never split across a page boundary) or a standalone **Mermaid** `.mmd`
  diagram scaled to the page. A modal preview window lets you page through the exact output (scaled to
  fit, with page navigation); **Print…** then opens the native dialog (printer, copies, paper) to send,
  **Close** cancels. Reuses the *Include line numbers* / *Syntax highlighting* settings (the Settings →
  Editor section is now **Export & Print**); preparation runs off the UI thread.

- **Export to PDF** — two new commands. **`File: Export to PDF`** (`editor.exportPdf`) writes the active
  buffer's source as a real, **searchable** PDF: embedded JetBrains Mono, syntax highlighting, and a
  right-aligned line-number gutter (both toggleable), in a clean light theme regardless of the app/editor
  theme. **`File: Export Preview to PDF`** (`preview.exportPdf`) exports a previewable buffer: a Markdown
  document becomes **native vector text** (headings, lists, tables, block quotes, code blocks, links and
  images — including embedded Mermaid diagrams and SVG badges as images), and a standalone Mermaid `.mmd`
  file exports via mmdc's native vector PDF. New **Settings → Editor → PDF Export**: *Include line
  numbers*, *Syntax highlighting*, and a *Page size* selector (Letter / A4). PDF generation runs entirely
  off the UI thread. Powered by Apache PDFBox.

- **Mermaid diagram support** (Settings → Mermaid, off by default) — renders Mermaid diagrams in the
  preview: standalone `.mmd` files (syntax-highlighted, with the Editor/Split/Preview toggle) and
  ` ```mermaid ` fenced blocks inside Markdown. Uses the external **mmdc** (mermaid-cli) to render and
  to **export** a diagram to SVG/PNG/PDF (`Mermaid: Export Diagram` command), and **maid**
  (probelabs/maid) to validate — a failed diagram shows the error with its line/column in the preview.
  Configure the `mmdc`/`maid` commands in Settings (blank = `mmdc` on `PATH` and `npx -y @probelabs/maid`).
  Rendered diagrams are cached by content so editing stays responsive. `.mmd` files also get **live maid
  linting** (red squiggly underlines + hover tooltip, debounced while typing) and **autocomplete** of
  Mermaid keywords + diagram snippets — both with their own toggles, automatically disabled when Mermaid
  is off or the tools aren't detected.

- **Export configuration** — a new "Export Configuration…" button (Settings → Advanced) and the
  `Configuration: Export to Zip` command zip your active config folder (`~/.editora`, `~/.editora-dev`,
  or a `--config-dir` override) into a timestamped `.zip` in your home directory, for quick backups.

- **Show/hide the tool stripe** — a new "Show tool stripe" setting (Settings → Tool Windows) and the
  `View: Toggle Tool Stripe` command (command palette) hide the side icon bars. This is UI only — tool
  windows still open via their keyboard shortcuts (e.g. `M-1`) and the command palette. Hiding the
  stripe takes precedence over each tool window's individual visibility toggle.

- **Build commit in dev mode** — when running with `--dev`, the About dialog and the Welcome page now
  show the short git commit the app is running from, so it's easy to tell which build you're testing.
  Not shown in normal (non-dev) runs.

- **Independent Markdown preview theme** — the rendered preview can now be light or dark independently
  of the app/editor theme, so you can read docs in light while coding in a dark theme (or vice versa).
  Toggle it with the floating sun/moon button next to the preview's −/+ zoom controls, or the
  **Markdown: Toggle Preview Light/Dark** command. The choice is remembered; until you first toggle it,
  the preview follows the app theme as before.

### Changed

- **Unsaved-file marker everywhere** — the dot + amber-italic style the tabs use for a file with
  unsaved changes now also appears in the **Switcher**, the **Open Files** picker, and the **Project**
  tool window tree, so every list of open files reads consistently. The Project tool window also
  refreshes when files/folders change outside Editora (on window focus), keeping its expanded folders
  and selection.

- **Markdown preview readability** — the rendered preview now lays its content out as a centered,
  width-capped column (GitHub-style) with more generous margins and vertical spacing (line height,
  space between blocks and list items, heading separation), instead of stretching edge-to-edge across
  a wide window.

### Added

- **SVG images in the Markdown preview** — the preview now renders SVG images, which JavaFX can't
  decode on its own. This makes **shields.io / GitHub status badges** (served as SVG) show up in the
  preview as they do on GitHub. Images load off the UI thread and are cached. (Uses the lightweight
  JSVG library.)

- **Keyboard scrolling in the Markdown preview** — Space / PageDown (page down), Backspace / PageUp
  (page up), and the Emacs `C-v` / `M-v` now scroll the rendered preview.

- **Status-bar message log** — click the status message on the left of the status bar to see every
  message from the current session in a scrollable popup, newest first, each with an `HH:mm:ss` time
  indicator. Messages are selectable (multi-select) and can be copied to the clipboard (Cmd/Ctrl+C or a
  right-click **Copy**). The log is in-memory only (not persisted), capped at the most recent 200
  messages, with a **Clear** action. Also available as the **Message Log** command (`view.messageLog`).

- **Welcome page** — instead of opening an empty "Untitled" buffer, Editora now opens a VSCode-style
  Welcome page in its own **tab** at startup when there's no session to restore. It offers **Start**
  actions — New File, Open File, then Open Folder as a Project and Clone Git Repository (each shown only
  when that feature is enabled), and the Command Palette last — each with its configured keybinding shown
  alongside, plus a **Recent** files list and a footer with the app version, a link to the project home
  page, and the license. Being a real tab, it activates, switches, and closes like any other tab; the
  **Welcome Page** command (`view.welcome`) opens it (or re-selects it if already open) on demand even
  with files open. The **About** dialog now also shows the
  home-page link, copyright, and license. New CLI flag
  **`--new-file[=name]`** bypasses it by opening a fresh buffer instead: `--new-file=notes.md` opens an
  unsaved buffer titled `notes.md` (highlighted by its extension; first save prompts for a location), and
  bare `--new-file` opens a blank untitled buffer.

- **Personal Notes** — private annotations attached to a file *without modifying the file*, for
  read-only / generated / shared code where you want knowledge stored separately. Three **scopes**
  (word / line / range), an optional **body**, **tags**, and a **status** (active / resolved /
  orphaned). Notes follow their content as you edit (live offset tracking) and re-anchor on reopen by
  the captured selection + surrounding context; a note whose anchor can no longer be found is marked
  **orphaned** (kept and recoverable, never silently lost). File identity is **content-hash + path**, so
  a note re-attaches even when the file is renamed/moved outside the app. Indicators: a gutter glyph, a
  soft in-editor highlight behind the anchored span, and a hover tooltip (the body is **rendered as
  Markdown** in the editor's font) — the gutter/highlight is toggleable via *Settings → Editor → Show
  note indicators*. A **Personal Notes** tool window
  (`M-5`) groups notes per file with a filter, edit/resolve/delete, and "delete all in file"; commands
  cover add (`C-c n`), next/previous, cross-file **Jump to Note** (`M-g n`), **Search Notes** (a picker
  that matches the full note body, tags, and file path),
  **delete** (the note on the caret line — also available from the panel and as a Delete button in the
  note editor), and **export** to JSON (resolve/reopen lives in the tool window's context menu). Note bodies are edited in a **multi-line** text box
  (Enter inserts a newline; Ctrl/Cmd+Enter saves). Stored per project in a single versioned `notes.json` (reuses the bookmark
  per-project bucket model + the config migration framework). Bookmarks are unaffected (separate gutter
  slot, no layout shift). The whole feature is **off by default** — enable it via *Settings → Application
  → Enable Personal Notes* (when off, the tool window, commands, and editor menu items are hidden).

- **Config schema versioning + migrations** — every structured config file (`settings.toml`,
  `workspace-state.json`, `projects/<id>.json`, `projects.json`, `bookmarks.json`, `recent-files.json`)
  now carries a per-file integer `schemaVersion` (baseline 1), and reads go through a small migration
  framework (`config/migration/`) so future releases can evolve a file's format with one registered
  `v→v+1` step. Existing unversioned files load unchanged and get stamped on the next save (no data
  loss); `recent-files.json` (previously a bare array) is migrated to a versioned object. If a file is
  **newer** than the running build (e.g. after a downgrade), it's backed up to `<name>.v<n>.bak` and
  defaults are loaded rather than overwriting newer data.

- **Autocomplete** — appears as you type (debounced) and on demand (`C-M-i` / `M-/`, command
  "Edit: Trigger Autocomplete"). **Code** files show a caret-anchored popup of **snippet** completions
  (accepting one expands the snippet with its tab stops); **Enter/Tab** accept, ↑/↓ navigate, Esc
  dismisses. **Prose** files (plain text / Markdown) instead show **inline "ghost text"** — a single
  greyed continuation drawn after the caret, completed from the bundled spell dictionary plus your
  personal dictionary; press **Tab** to accept, Esc (or just keep typing / move the caret) to dismiss.
  In the code popup, ↑/↓ and **C-n/C-p** move the selection. Settings → Editor has a master "Enable
  autocomplete" toggle plus per-source checkboxes — **Words (prose)** and **Snippets (code)** — all on
  by default (more sources can be added later), and there are command-palette toggles for each
  ("View: Toggle Autocomplete", "… Words (Prose)", "… Snippets"). The dictionary word list is parsed
  off-thread and cached; prefix lookup is a binary-search range, so typing/scrolling stay unaffected.

- **Multi-language interface (i18n)** — Editora's UI can now run in **English, Italian, Spanish,
  French, Portuguese, or German**. Command-palette titles, toolbar tooltips, tool-window titles, and
  the full Settings window are translated; pick a language under **Settings → Appearance → Language**
  (default *Automatic* follows the system language, falling back to English). A language change
  applies on the next restart. Strings live in a `messages[_<lang>].properties` catalog loaded by the
  new `com.editora.i18n.Messages` helper; a key-parity unit test keeps every translation complete.
  **Essentially the entire interface is translated** — command palette, toolbar tooltips, tool-window
  titles and panel contents (Project, Structure, Bookmarks, File Information, Commit/Git), the full
  Settings window, status-bar segments, echo-area status messages, every dialog (titles, bodies and
  buttons), context menus, and popups (branch dropdown, command palette, file finder, switcher). The
  chosen language also drives `Locale.setDefault`, so JavaFX's own OK/Cancel buttons match.

### Changed

- **Settings window redesigned** — a scalable left **category sidebar** (Appearance, Editor, Tool
  Windows, Spell Check, Application, Advanced, plus "coming soon" placeholders for Keymaps/Plugins/Git/AI)
  replaces the single stacked list, with a **search** box that filters settings + jumps to matches, a
  **live preview** on Appearance (sample code that recolors/re-fonts as you change theme/font), and a
  **Reset to Defaults** on the Advanced page. Changes still apply live. Tool-window placement moved to
  its own page; the new **Tab size** control lives on the Editor page. **About** moved out of Settings
  (it's on the toolbar + the "About Editora" command); the settings-file link now lives on the Advanced
  page.

- **Markdown preview now renders closer to GitHub** — task lists (`- [x]`/`- [ ]`) show real checkboxes
  instead of bullets, inline `code` gets a rounded gray pill (instead of blue text), `#`/`##` headings
  get an underline rule, and links are no longer permanently underlined. Standalone images now render as
  block images (with the alt text / title as a tooltip), in addition to inline images; relative image
  paths resolve against the file's folder, and `http(s)`/`file`/`data:` URLs are supported.

### Fixed

- **In-app file rename now carries bookmarks and personal notes to the new path** — renaming an open
  file from the tab/right-click menu already moved its folds and recent-files entry; it now also re-keys
  that file's bookmarks and notes, so they no longer get stranded under the old path.

### Added

- **Emacs transpose commands** — transpose characters (`C-t`), words (`M-t`), and lines (`C-x C-t`),
  also in the command palette ("Edit: Transpose …"). At end of line, `C-t` swaps the two preceding
  characters (typo fix); `C-x C-t` swaps the current line with the one above.

- **"Enable Git" setting (Settings → Git), off by default** — Git integration is now opt-in. When off,
  the status-bar VCS segment is disabled, the Commit tool window is hidden, gutter change markers are
  cleared, and all Git commands/keybindings are inactive (and hidden from the palette). Turn it on in
  Settings → Git to use branch/status, change bars, commit, and sync. The Git page detects the `git`
  command: it shows the installed version when found, or "git command not found" and disables the
  checkbox when git isn't on `PATH`.

- **Save / Save As… in the tab right-click menu** — save the right-clicked tab directly (Save is greyed
  out for an unchanged, already-saved file).

- **Git support (native CLI)** — Editora now talks to your installed `git`. The status bar shows the
  current branch with ahead/behind counts (`⎇ main ↑2 ↓1`, click for the branch dropdown — an
  IntelliJ-style searchable list of actions + Local/Remote branches, each local branch showing its
  upstream and incoming/outgoing (`↓`/`↑`) commit counts); the editor gutter
  draws change bars relative to `HEAD` (green added / blue modified / red deleted); and a **Commit** tool
  window (`M-4`) groups Staged / Changes / Untracked files with stage, unstage, discard, **Stage All**,
  a **Push** button, and a commit message box (Ctrl/Cmd+Enter to commit). **Clone** a remote repo with "Git: Clone
  Repository…" (or the button in the empty Commit tool window) — one dialog asks for the repo URL and
  the destination directory (with a Browse button; the directory auto-fills to `<home>/<repo>` from the
  URL), clones, and opens a file from it (its README, if any) so Git lights up; cloning is independent
  of projects (you don't need project support to clone or use Git). Palette commands: "Git: Clone Repository…", "Git: Commit…" (`C-x g`),
  "Git: Stage Current File", "Git: Switch Branch…", "Git: New Branch…", "Git: Fetch", "Git: Pull",
  "Git: Push", and "Git: Refresh Status". Pushing a brand-new branch automatically sets its upstream
  (`--set-upstream origin <branch>`), and Git command failures are shown in a readable dialog rather
  than the one-line status bar. The **Commit** tool window is shown only when the active file is under
  Git; otherwise it's hidden. The status-bar VCS segment is always present: outside a repo it reads
  **No VCS**, and clicking it offers **Clone Git repository…**. All Git calls run off the UI thread;
  everything degrades silently when a file isn't in a repository or `git` isn't installed. (No new dependencies — Editora
  shells out to your real git, so credential helpers, SSH, and signing all work.)

- **Theme commands in the palette** — "Theme: Set App Theme…" and "Theme: Set Editor Theme…" open a
  fuzzy picker. The app-theme picker switches the chrome theme *and* the editor theme to match; the
  editor-theme picker changes only the editor colors (and pins it so it won't follow the app theme).

- **Spell checking** — misspelled words get a red wavy underline; right-click for suggestions (click one
  to replace), **Add to Dictionary**, or **Ignore**. In source files only comments and string literals
  are checked (identifiers aren't flagged); plaintext and Markdown are checked in full. Toggle it with
  "View: Toggle Spell Check" (or the **Settings window**, where you can also choose the default
  dictionary language); pick the dictionary per file with "Spell Check: Set Language…" (ships
  **English (en_US, en_GB)**, **Spanish (es)**, and **French (fr)**; your added words live in
  `dictionary.txt`). Powered by Apache Lucene's pure-Java Hunspell engine.
- **Detect external file changes** — when the file in the active tab is modified by another program,
  Editora notices (on window focus or when you switch to that tab) and offers to reload it, or keep your
  version (also when you have unsaved edits). The prompt only appears for the tab you're looking at.
  Deleted files are left untouched. Editora's own saves never trigger the prompt.
- **Smart backspace** — pressing Backspace while the caret is in a line's leading indentation deletes
  the whole indent in one press instead of one space at a time. On a blank, auto-indented line a single
  Backspace jumps back to the end of the previous line (undoing the Enter); on an indented line that has
  content, it clears the indent. Outside leading whitespace, Backspace behaves normally.

### Changed

- **Bookmarks now live in their own `bookmarks.json`** (in the config directory) instead of inside each
  session's `workspace-state.json`, so they're all in one easy-to-find file. They remain **scoped per
  project** — switching projects shows only that project's bookmarks (the global session has its own),
  and deleting a project deletes its bookmarks. Existing bookmarks are migrated automatically on first
  run (moved into the right per-project bucket; the old session files are cleaned up). This also fixes
  the cross-file "Jump to Bookmark" picker (`M-g b`) showing an empty list when a project was active.
- **Reorder bookmarks in the Bookmarks tool window** — move a bookmark within its file, or a whole file
  group, with **Alt+Up/Down**, the right-click **Move Up/Down** menu, or **drag-and-drop** (with the
  same drag visuals as the editor tab strip: a translucent ghost, the dragged row dimmed, and an accent
  insertion line on the drop target). The order you set is exactly the order the `M-g b` jump picker
  uses, and it persists (and survives edits to the file).

### Fixed

- The About dialog now shows the **live** config/settings path, so `--dev` (`~/.editora-dev/`) and
  `--config-dir` are reflected (it previously always showed the default `~/.editora/settings.toml`).

### Added

- **Zen-mode exit button** — a small floating "Z" button appears at the top-right of the window while
  in Zen mode (same look as the Markdown preview controls); click it (tooltip: "Exit Zen mode") to leave
  Zen. It's hidden whenever Zen is off, and on Markdown files it sits just below the preview controls so
  the two don't overlap.
- **Navigation key hints in the pickers** — the Command Palette, the "Jump to…" pickers (recent files,
  bookmarks, structure, tool windows, snippets), and the file finder now show a footer legend of their
  relevant keys (move / select / cancel, plus Tab-complete in the file finder), matching the Switcher.
- **Focused tool window is highlighted** — the panel that currently holds keyboard focus (Project,
  Structure, Bookmarks, File Information) gets an accent-tinted header, so it's clear where you are when
  moving between panels and the editor.
- **`--dev` command-line flag** — runs Editora against a separate `~/.editora-dev/` config directory so
  a development instance can run alongside your everyday editor without sharing settings or session
  state. Config-dir precedence is now `--config-dir` > `EDITORA_CONFIG_DIR` > `--dev` > `~/.editora/`.
  A light-red "dev mode" badge appears in the toolbar (just left of the About icon) so the dev instance
  is visually distinct.
- **Comment / uncomment** (`M-;` or the palette: "Edit: Toggle Comment"). A single line toggles a line
  comment; a multi-line selection toggles a block/region comment — using whichever the language has
  (e.g. `//` and `/* */` for Java/C-likes, `#` for Python/shell/YAML, `<!-- -->` for XML/HTML/Markdown,
  `/* */` for CSS, `--` for SQL). Falls back gracefully (block-only languages always wrap; line-only
  languages comment each line), preserves indentation, and is a no-op for languages without comments.

- **Auto-close brackets and quotes** — typing `(`, `[`, `{`, `"`, `'`, or `` ` `` inserts the matching
  closer and keeps the caret between; typing the closer (or a quote) when it's already next to the
  caret types over it; typing an opener/quote with a selection wraps the selection; and Backspace
  inside an empty pair deletes both halves. Quotes are not auto-paired next to a word character (so
  apostrophes in `don't` are left alone).
- **Highlight matching brackets** — when the caret is next to a `()`, `[]`, or `{}`, both it and its
  match are highlighted.

- **Auto / smart indentation** for all 21 languages. Enter keeps the current line's indentation; a
  block-opener adds a level (braces `{ ( [`; `:` in Python/YAML; `do`/`then`/`in` in shell;
  `def`/`class`/`do`/… in Ruby; an open tag in XML/HTML); pressing Enter between a matching pair
  (`{}`/`()`/`[]` or `<a>|</a>`) opens an indented stanza with the closer dropped below; and typing a
  closer (a `)]}` bracket alone on the line, or a keyword like `end`/`fi`/`done`) re-aligns the line to
  its opener. The indent unit (tab vs spaces) is inferred from the file.

- **Ctrl + mouse wheel zooms the Markdown preview** while in Preview mode (scroll up/down to zoom
  in/out, driving the preview's `−`/`+`); the editor text zoom is left untouched there. In Editor and
  Split modes Ctrl+wheel still zooms the editor text.

- **Snippets** (VS Code / TextMate-style) — expand templates with interactive tab stops. Type a
  prefix and press **Tab** to expand (Tab still indents when nothing matches), or pick from the
  **"Snippet: Insert…"** fuzzy list (`C-c i`). After expanding, **Tab / Shift-Tab** cycle the fields,
  placeholders are pre-selected to overtype, mirrored fields update live, and `$0` is the final caret.
  Bodies use the standard syntax — `$1`, `${1:default}`, mirrors, `${1|a,b|}` choices (a dropdown
  appears on the field), variables (`$TM_FILENAME`, `$TM_DIRECTORY`, `$CLIPBOARD`, `$CURRENT_YEAR`,
  the selection, …) and `\$` escapes. Snippets ship for **all 21 highlighted languages** — most from
  the MIT-licensed [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) collection
  (attributed in `NOTICE`), the rest written for Editora. Add your own in
  `~/.editora/snippets/<language>.json` (or `global.json`) — "Snippet: Edit User Snippets…" opens the
  file and "Snippet: Reload Snippets" picks up changes. User snippets override bundled ones.

- **Read-only / View mode** — toggle a buffer read-only with `C-x C-q` (or the palette: "View: Toggle
  Read-Only"), so it can't be edited by accident. Typing and the editor's own edit commands are blocked
  (with a status-bar hint), while highlighting, minimap, folding, scrolling, and copy keep working. A
  file opens read-only automatically when it isn't writable on disk, a status-bar toggle flips it
  either way ("Read-Only" ⇄ "Editable") and the tab title is muted, and the per-file state is
  remembered across restarts. A Word-style "View Mode" banner docks above the editor with the
  navigation hint and an **Enable Editing** button (shown only when the file is writable). While
  read-only, **Space pages down and Backspace pages up** (pager-style, like `less`/man).
- **More command-line options** — `--version`/`-V` and `--help`/`-h` (print and exit, no window);
  positional `FILE`, `FILE:LINE`, and `FILE:LINE:COLUMN` to open a file (and jump); `--project[=]<dir>`
  to open a folder as a project (only when Projects are enabled, else ignored); and `--zen` to start in
  Zen mode. File/project args are additive on top of the restored session. Cross-platform.
- **Custom config folder** — point Editora's config directory somewhere other than the default
  `~/.editora/` with the `--config-dir <path>` command-line argument (or `--config-dir=<path>`) or the
  `EDITORA_CONFIG_DIR` environment variable. Precedence: `--config-dir` > `EDITORA_CONFIG_DIR` >
  `~/.editora/`. Works on macOS, Linux, and Windows.
- **Text zoom** — quickly scale the editor text up/down independent of the configured font size (the
  set size is 100%). Use the status-bar `− 100% +` control, `C-=` / `C--` (and `Ctrl`+Plus) / `C-0` to
  reset, **Ctrl + mouse-wheel**, or the palette ("View: Zoom In / Out / Reset Text"). The zoom level
  persists across restarts and is separate from the font-size setting (it's not shown in Settings).
- **Markdown preview** — an IntelliJ-style 3-mode view for Markdown files via a small floating control
  at the top-right of the editor: **Editor**, **Editor + Preview** (side-by-side), and **Preview**. The
  preview is rendered natively (no WebView) from CommonMark + GitHub-flavored Markdown (tables, task
  lists, strikethrough, autolinks), updates live as you type (debounced, off-thread), follows the
  active theme, and remembers its mode per file. The preview has −/+ buttons (top-left) to zoom the
  text in/out. Also available as palette commands ("Markdown: Editor / Editor and Preview / Preview",
  and "Markdown: Zoom In / Out / Reset Preview").
- **Bookmarks** — mark lines and jump back to them, across files. Toggle a bookmark on the caret line
  with `C-c m`, or click the gutter to add one (clicking an existing bookmark asks before removing it);
  a light-orange bookmark glyph appears in the line-number gutter. Each bookmark
  can carry an optional note ("Bookmarks: Edit Note…"). The **Bookmarks** tool window (`M-2`) lists every bookmark across all files (open
  or closed), grouped by file, with a filter box; Enter opens the file and jumps to the line, and
  right-click edits the note or deletes. `C-c ]` / `C-c [` cycle through the current file's bookmarks,
  and `M-g b` ("Bookmarks: Jump…") is a fuzzy cross-file picker. Bookmarks persist with the session
  (per project) and track their line as you edit above them.
- The Project tool window's file tree now colors its icons to match the active theme: folders use the
  theme accent and files a muted tone. The colors are driven by two looked-up CSS variables
  (`-project-folder-color`/`-project-file-color`) that default to the GUI theme (AtlantaFX) and are
  overridden per editor theme, so the tree tracks the chosen code theme. Files open with unsaved
  changes are marked in the tree like dirty tabs — a "• " prefix and amber italic — updating live as
  you edit and save.
- Reorder the tool-window stripe icons: drag an icon up/down within its side, or use the new ▲/▼
  buttons next to each row in Settings → "Tool window placement". The order persists in the session
  state (`workspace-state.json`).

- Projects (VSCode single-folder-workspace style), **off by default** — enable via Settings →
  "Enable projects" (when off, the Project tool window, the toolbar open-folder icon + project
  switcher, and the project commands are all hidden). Each project is a root folder plus its own
  saved session (open files with carets/pins, active file, folds, tool-window layout). Open one via
  the toolbar's open-folder icon (native dialog) or "Project: Open Folder…" (`C-x C-p`, a keyboard
  folder picker like `C-x C-f`); "Project: Switch…" (`C-x p`) saves the current session and restores
  another's; "Project: Close" returns to the global session (with confirmation); "Project: Delete…"
  (also a trash button in the Project tool window) removes a project from your list — its folder and
  files on disk are kept, only the project entry and its saved session are removed. The active project
  is shown in the window title and a project switcher combobox in both the toolbar and the Project
  tool window (each with a "No Project" entry that returns to the global session without closing any
  project). The Project tool window shows the project's files as a lazy tree with Emacs-style
  keyboard navigation (C-n/C-p, C-f/C-b, Enter to open), a filter box that runs a bounded
  project-wide filename search (like the Structure filter), and a right-click menu to rename or
  delete files (syncing open tabs). Opening a recent file that belongs to another project switches to
  that project first (restoring its session/tree) before opening the file. Settings stay global.
- Keyboard file finder (Emacs `find-file` style) on `C-x C-f`: a path popup with a live directory
  listing that prefix-autocompletes as you type. `Tab` completes the common prefix, Enter descends
  into a folder or opens a file (a non-existent path opens a new buffer, written on save),
  `C-n`/`C-p` move, `Esc` cancels — no mouse needed. Also the `file.find` palette command. The
  toolbar Open icon (and `file.open`) still uses the native OS dialog.
- Fold/unfold the single region at the caret: `view.fold` (`C-c C-f`) collapses the innermost
  region around the caret, `view.unfold` (`C-c C-u`) expands it, and `view.toggleFold` (`C-c C-t`)
  toggles it — complementing the existing Fold All (`C-c f`) / Unfold All (`C-c u`).
- Keyboard "Jump to…" pickers — command-palette-style fuzzy popups: recent files (`C-x C-r`),
  the active file's structure/symbols (`M-g i`), open files/tabs (`C-x b`), and tool windows
  (`M-g t`). Type to filter, `C-n`/`C-p` or ↑/↓ to navigate, Enter to act (open the file / jump to
  the symbol / switch tabs / open the tool window), `Esc` to cancel. All are also in the command
  palette ("Recent Files: Jump", "Structure: Jump", "Open Files: Jump", "Tool Windows: Jump").
- More Emacs movement keys: `M-m` (back to indentation), `C-l` (recenter the caret line in the
  viewport), `M-{` / `M-}` (backward/forward paragraph), and `M-a` / `M-e` (backward/forward
  sentence) — all registered commands, so they also appear in the palette. Note: on macOS the
  Option dead keys (`Option`+`e`/`i`/`u`/`n`/`` ` ``) are intercepted by the OS for accent
  composition and never reach the app, so `M-e` (forward sentence) can't be triggered by keyboard
  there — use the palette command "Go: Forward Sentence" instead.
- Auto save (VS Code-style), off by default. Choose a mode in Settings: "After delay" saves a file
  once it's been idle for a configurable number of seconds (default 1), or "On focus change" saves when
  you switch editor tabs or the window loses focus. Only dirty, file-backed, writable buffers are
  saved (untitled/read-only ones are skipped); writes happen off the UI thread. Cycle the mode with
  the `file.toggleAutoSave` palette command or `C-c a`.
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

- Opening a tool window now moves keyboard focus into it and selects its first item (the first
  folder/file in Project, the first bookmark, the first symbol in Structure), so you can navigate it
  with the keyboard immediately. Restoring tool windows on startup/session-switch does not steal focus.
  While a tool window is focused, global commands still work — `M-x`, the tool-window toggles
  (`M-1`/`M-2`/…), the `M-g …` jumps, and `C-x …` prefixes; the panel only intercepts the editor
  navigation chords it reuses (`C-n`/`C-p`, `C-f`/`C-b`, …).
- The **Switcher** (`C-x C-b`) now lists only the open files — the Tool Windows column was removed. It's
  a single column in **tab order**, with a **bold header** and a **fixed width** (sized to the longest
  file path on open) so it no longer resizes while you navigate. Tool windows are still reachable via
  their stripe icons and the "Tool Windows: Jump" picker (`M-g t`).
- Silenced the benign tm4e/Oniguruma grammar warnings that flooded the console
  (`'…]' without escape`, `No grammar source for scope …`) by raising the `org.eclipse.tm4e`
  log level to `SEVERE` at startup. They were harmless bundled-grammar quirks; real errors still
  surface.
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

- **Bookmarks no longer drift to the wrong line when a file is edited outside the editor.** Bookmarks
  followed their content only for edits made inside Editora, so changing a file in another program
  left its markers on stale lines. Each bookmark now re-anchors to its saved line text when the file
  is opened (re-found at the nearest matching line), and the corrected position is saved back so the
  session self-heals.
- **Adding a bookmark no longer shifts that line's text indentation.** The gutter now reserves a
  fixed-width bookmark column on every line, so the marker glyph no longer widens only the bookmarked
  row (which had pushed its text rightward).
- **A bookmark's gutter marker now follows its line when an edit moves it.** Inserting or deleting
  lines above a bookmark already moved the bookmark (and its Bookmarks-panel entry), but the gutter
  glyph wasn't repainted onto the new line; it is now.
- Switching projects (or "No Project"), or closing/deleting a project, while in Zen mode no longer
  leaves the UI permanently mangled. Zen stores its "everything off" view state in the global
  settings while keeping the restore snapshot in the per-session state; swapping sessions orphaned
  that snapshot. The editor now exits Zen (restoring the real view settings) before a session switch
  and lands the incoming session in normal view.
- The `M-<` / `M->` (document start/end) and `M-%` (find & replace) keybindings now work. They were
  stored in Emacs notation (`M-<`) but the dispatcher emits shifted symbols as `M-S-,` etc., so the
  entries never matched; the keymap now uses the dispatcher's token form.
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
