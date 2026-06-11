# Editora — TODO / Roadmap

A backlog of planned features and improvements. Unordered within each section.

## Recently shipped
- [x] Simple UI mode — a one-toggle minimal layout (toolbar icon, **View: Toggle Simple UI Mode**,
      Settings → Application, or `--simple`): hides the extra toolbar groups (new-from-template, recent,
      find-in-files, split, project selector), the tool-window stripe, breadcrumb, the entire gutter
      (collapsed regions unfolded first), minimap, and most status-bar segments — keeping tabs, the core
      toolbar icons (incl. **Open**), and echo/read-only/zoom/Ln-Col. Also disables the heavier features
      (LSP, debugging, HTTP client, Git, multiple cursors / column selection). Persisted; `--simple` is a
      session-only override; saved preferences are untouched and restored on exit
- [x] Remote file access (SFTP) — connect over SSH/SFTP and edit a server's files as if local: the remote
      folder mounts in the Project tool window, open/edit/save go straight over SFTP, saved connections
      (metadata only) reconnect via a picker; local-process features (LSP/DAP/Git/Run/HTTP) auto-disable
      for remote files. Off by default; built on Apache MINA SSHD (Remote: Connect / Saved Connections /
      Open File / Disconnect)
- [x] HTTP Client (`.http`/`.rest` files) — a green ▶ on every request runs it with the built-in JDK
      HTTP client; response (status/headers/pretty-JSON/timing) in an HTTP Client tool window (`M-0`);
      `{{var}}`/`@var` substitution, environment files (`http-client.env.json`), run-whole-file
- [x] File templates — "New File From Template" (`C-c C-n`): single- or multi-file templates with a
      `${var}` wizard and `$0`/`${cursor}` placeholders; bundled (Java class, HTML page/bundle, Markdown,
      Python) + user templates in `~/.editora/templates/`
- [x] Debugging (DAP) — full debugger for **Java** (java-debug over jdtls), **Python** (debugpy), and
      **JavaScript/Node** (vscode-js-debug): breakpoints (conditional/logpoints), step/resume/pause/
      run-to-cursor/jump-to-line, call stack + variables + watches + set-value, inline values + hover, an
      IntelliJ-style Debug tool window (`M-g d`); off by default
- [x] Diff viewer & merge — side-by-side / unified diff (vs HEAD, a commit, or another file) with
      word-level highlights, prev/next nav, apply-hunk / apply-all (undoable), live refresh, patch export;
      a merge-conflict resolver (accept ours/theirs/both)
- [x] Multiple cursors & column/box selection — VS Code–style multi-caret editing (add caret at next
      occurrence / above / below) + Alt-drag column selection, via the personal RichTextFX fork
- [x] LSP support — **21 language servers** auto-detected on PATH (per-server Settings command + enable,
      off by default): Java (JDT LS), TypeScript/JavaScript, Python (Pyright), XML (lemminx), JSON,
      Bash/Shell, YAML, Go (gopls), Rust (rust-analyzer), PHP (phpactor), Ruby (ruby-lsp), C/C++ (clangd),
      C# (csharp-ls), HTML, CSS, Kotlin, Lua, Dockerfile, SQL (sqls), Terraform (terraform-ls), TOML (taplo) —
      diagnostics + Problems window (`M-8`) + minimap/scrollbar stripes, go-to-definition (`M-.`),
      find references (`M-?`), hover (`C-c h`), LSP completion, and TS/PHP auto-imports
- [x] Markdown editing — IntelliJ-style floating format bar on selection (bold/italic/strikethrough/code/
      link/list + Normal–H1…H6), `C-c`-prefixed shortcuts + right-click Format menu; smart list/blockquote
      continuation on Enter, heading promote/demote, link helpers (Ctrl/Cmd-click to open), GFM table reflow
- [x] Run a file from a gutter ▶ — Java 25 compact source (`java <file>`), Python (`python3`), and shell
      (`bash`, when the Bash LSP is enabled); streams output into a Run tool window (`M-9`); gated by LSP
- [x] Print — native printing of code or the Markdown preview with a print-preview window (always light),
      reusing the PDF layout core (Settings → Editor → Export & Print); `editor.print` / `preview.print`
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
- [x] Smart line start (`C-a`) — first press to the first non-whitespace, second toggles to column 0
- [x] Markdown formatting — format bar + smart list/heading/link/table editing (see "Recently shipped")
- [ ] Format document — whole-document reformat (will ride LSP formatting; GFM table reflow exists)
- [x] Column select support — column/block selection (overlay + column-aware edits)
- [x] Multiple cursors support — VS Code–style multi-caret (add at next occurrence / above / below) +
      Alt-drag column selection (personal RichTextFX fork); see "Recently shipped"
- [ ] Advanced Undo/Redo support
- [x] Spell check support — Lucene Hunspell, red squiggles, suggestions, user dictionary, en_US/en_GB
- [x] Private comments/notes — see **Personal Notes** under "Recently shipped"

## Search
- [x] Incremental Search — find bar searches as you type (debounced), jumps to the nearest match
- [x] Regex search — regex + case-sensitive + whole-word toggles in the find bar
- [x] Multi-file search — Find in Files (`C-S-f`): project + open buffers, off-thread, with replace-in-files
- [x] Search results panel — Search Results tool window (`M-6`), grouped by file, Enter/double-click to jump
- [x] Highlight all matches — every match highlighted live in the editor (current one accented)
- [x] AceJump support — `M-g j`: type a char, then a label, to jump the caret to any on-screen occurrence

## Code intelligence
- [x] Autocomplete support — code: snippet popup (Enter/Tab); prose: inline ghost text (Tab); auto +
      `C-M-i`/`M-/` trigger; Settings toggle. (Next: document-words, LSP, fuzzy matching.)
- [x] LSP support — **21 servers** (see "Recently shipped"): diagnostics + Problems window (`M-8`) +
      minimap/scrollbar stripes, go-to-definition (`M-.`), find references (`M-?`), hover (`C-c h`),
      LSP-backed completion, and TS/PHP auto-imports. Server-centric registry, per-server Settings, off by
      default. (Next: formatting / format-on-save; rename, code actions, quick fixes; document symbols.)
- [ ] Fix structure for the 21 languages we support
- [x] Multi language support — UI string translation (en/it/es/fr/pt/de); see "UI localization (i18n)"
      under "Recently shipped"

## Snippets
- [ ] GUI for Snippet management

## Files & version control
- [x] Git support — native CLI (branch/status, gutter change bars, commit workflow, fetch/pull/push)
- [x] Diff viewer + merge-conflict UI — side-by-side / unified diff (vs HEAD / commit / another file),
      word-level highlights, apply-hunk / apply-all, patch export, merge-conflict resolver
- [ ] Git: history / log view, file blame (later phase)
- [ ] Local History support
- [x] Detect external file changes — prompt to reload when a file changes on disk (focus-regain / tab switch)
- [ ] Auto-reload modified files
- [x] Remote file editing support — SSH/SFTP: browse/open/edit/save remote files; saved connections
      (metadata only); local-process features auto-disable for remote (see "Recently shipped")
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
