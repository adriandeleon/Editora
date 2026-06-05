# Editora — TODO / Roadmap

A backlog of planned features and improvements. Unordered within each section.

## Recently shipped
- [x] Export to PDF — code (searchable, embedded font, syntax highlighting + optional line numbers,
      always light theme), Markdown (native vector text), and standalone Mermaid `.mmd` (via mmdc);
      `editor.exportPdf` / `preview.exportPdf`; Settings → Editor (line numbers / highlighting / page size)
- [x] Mermaid diagrams — `.mmd` files + ` ```mermaid ` blocks in the preview (mmdc), export to SVG/PNG/PDF,
      live `maid` linting (squiggles), keyword + snippet autocomplete (Settings → Mermaid, off by default)
- [x] Welcome page — VSCode-style editor-area empty state (New File / Open File / recent) shown when no
      files are open, replacing the empty Untitled buffer; `--new-file[=name]` bypass
- [x] UI localization (i18n) — interface translated to English, Italian, Spanish, French, Portuguese,
      German; language picker in Settings → Appearance (applies on restart); key-parity test
- [x] Settings window redesign — sidebar categories, search, live preview, Reset to Defaults; Tool
      Windows + About moved out
- [x] Git support — native CLI: status-bar branch + ahead/behind, gutter change bars vs HEAD, Git tool
      window (stage/unstage/discard/commit), and fetch/pull/push + branch switch/create commands
- [x] Personal Notes — file-attached annotations (word/line/range/file scope, body/tags/status),
      content-hash + path identity (survive rename/move), gutter + highlight + hover indicators,
      tool window (`M-5`), `M-g n` jump, JSON export, per-project `notes.json`
- [x] Bookmarks — per-project, gutter markers + notes, tool window (filter, reorder via Alt+Up/Down /
      menu / drag-and-drop), `M-g b` cross-file jump picker, stored in `bookmarks.json`
- [x] Markdown preview — IntelliJ-style Editor / Split / Preview, live + off-thread, Ctrl+wheel zoom
- [x] Read-only / View mode (`C-x C-q`) — with "View Mode" banner and Space/Backspace paging
- [x] Projects — single-folder workspaces, per-project session + bookmarks
- [x] Switcher — open-files popup in tab order
- [x] Tool windows (Project, Structure, Bookmarks, File Information) + focused-window highlight
- [x] Zen mode + floating "Z" exit button
- [x] Navigation key hints in the Command Palette, Jump-to pickers, and file finder
- [x] Recent files, editor themes, text zoom
- [x] Snippets — VS Code/TextMate syntax, bundled for all 21 languages + user overrides
- [x] `--dev` mode (isolated `~/.editora-dev`), `--config-dir` / `EDITORA_CONFIG_DIR`, CLI file/project/zen args

## Editing
- [x] Smart backspace — clear the indent in one press / jump back on a blank auto-indented line
- [x] Auto indent
- [x] Smart indentation
- [x] Language indentation aware for the 21 languages we support
- [x] Autoclose `()[]{}` and quotes
- [x] Highlight matching braces
- [x] Comment/uncomment code region
- [ ] Format document
- [x] Column select support — column/block selection (overlay + column-aware edits)
- [ ] Multiple cursors support
- [ ] Advanced Undo/Redo support
- [x] Spell check support — Lucene Hunspell, red squiggles, suggestions, user dictionary, en_US/en_GB
- [x] Private comments/notes — see **Personal Notes** under "Recently shipped"

## Search
- [ ] Incremental Search
- [ ] Regex search
- [ ] Multi-file search
- [ ] Search results panel
- [ ] Highlight all matches
- [ ] AceJump support

## Code intelligence
- [x] Autocomplete support — code: snippet popup (Enter/Tab); prose: inline ghost text (Tab); auto +
      `C-M-i`/`M-/` trigger; Settings toggle. (Next: document-words, LSP, fuzzy matching.)
- [ ] LSP support
- [ ] Fix structure for the 21 languages we support
- [ ] Multi language support

## Snippets
- [ ] GUI for Snippet management

## Files & version control
- [x] Git support — native CLI (branch/status, gutter change bars, commit workflow, fetch/pull/push)
- [ ] Git: history / log view, file blame, side-by-side diff viewer, merge-conflict UI (later phase)
- [ ] Local History support
- [x] Detect external file changes — prompt to reload when a file changes on disk (focus-regain / tab switch)
- [ ] Auto-reload modified files
- [ ] Remote file editing support
- [ ] Log mode support

## Keybindings
- [ ] Complete emacs movement/text manipulation keybindings
- [ ] Fully configurable shortcuts
- [ ] Vim keybindings
- [ ] Emacs keybindings
- [ ] Sublime keybindings
- [ ] CUA keybindings

## UI / UX
- [ ] UI final touches (fonts, colors, etc.)
- [x] Pretty up Settings Window — sidebar categories, search, live preview, reset
- [ ] Upgrade breadcrumbs support
- [ ] Fix Zen mode

## Extensibility & integration
- [ ] Plugins/API support
- [ ] External Tools support
- [ ] MCP support
- [ ] Headless support

## Packaging
- [ ] Sign native installers
