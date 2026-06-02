# Editora — TODO / Roadmap

A backlog of planned features and improvements. Unordered within each section.

## Recently shipped
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
- [ ] Column select support
- [ ] Multiple cursors support
- [ ] Advanced Undo/Redo support
- [x] Spell check support — Lucene Hunspell, red squiggles, suggestions, user dictionary, en_US/en_GB
- [ ] Private comments/notes

## Search
- [ ] Incremental Search
- [ ] Regex search
- [ ] Multi-file search
- [ ] Search results panel
- [ ] Highlight all matches
- [ ] AceJump support

## Code intelligence
- [ ] Autocomplete support
- [ ] LSP support
- [ ] Fix structure for the 21 languages we support
- [ ] Multi language support

## Snippets
- [ ] GUI for Snippet management

## Files & version control
- [ ] Git support
- [ ] Local History support
- [ ] Detect external file changes
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
- [ ] Pretty up Settings Window
- [ ] Upgrade breadcrumbs support
- [ ] Fix Zen mode

## Extensibility & integration
- [ ] Plugins/API support
- [ ] External Tools support
- [ ] MCP support
- [ ] Headless support

## Packaging
- [ ] Sign native installers
