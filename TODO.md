# Editora — TODO / Roadmap

A backlog of planned features and improvements. Unordered within each section.

## Recently shipped
- [x] Global indent-style preference — Settings → Editor → "Indent style" + **Editor: Set Indent Style…**
      palette command: force Spaces or Tabs for Tab/Enter, or keep Detect (per-file auto-detection).
      A file's `.editorconfig` `indent_style` wins; the global pref is the fallback above per-file detect
- [x] EditorConfig (`.editorconfig`) — resolves the nearest config chain (nearest-dir-wins, up to `root`)
      and applies indent style/size + `tab_width`, `end_of_line`, `charset` (utf-8/utf-8-bom/latin1/
      utf-16le/be, round-tripped on read & save), `max_line_length` (column ruler), and on-save
      `trim_trailing_whitespace` / `insert_final_newline`. Glob sections (`*` `**` `?` `[seq]` `{a,b}`
      `{n1..n2}`). On by default; Settings → Editor + **View: Toggle EditorConfig**. Local files only
- [x] MCP server — a minimal Model Context Protocol server (loopback HTTP + bearer-token auth) embedded in
      the editor, exposing live state + the command registry to an LLM agent (six tools); off by default
      behind a security notice (Settings → MCP Server). No new dependency
- [x] Local file history — IntelliJ-style snapshots of local files on save / auto-save / before an
      external reload, independent of any VCS; a **File History** tool window (`M-g l`) lists revisions
      (date/time, reason, size; latest tagged *Current*), double-click for a read-only diff vs current,
      restore = undoable whole-file replace. Gzip'd content-addressed blobs under `<configDir>/history/`,
      deduped, configurable retention. On by default; local-only; off in Simple UI
- [x] Emacs fill commands — Fill Paragraph (`M-q`), Fill Region, Set Fill Column (`C-x f`): re-wrap to a
      fill column, preserving indentation + an adaptive fill prefix (line comments, `>` quotes, Javadoc `*`)
- [x] LSP Format Document — whole-file reformat via the language server (palette + editor right-click),
      undoable, when the server advertises formatting
- [x] File-type icons — per-type glyphs everywhere a file is listed (tabs, Project tree, pickers, Switcher,
      finders); plus a "Current Folder" Project explorer when no project is open
- [x] Plugin support + a signed plugin registry — extend Editora via a Java SPI or a declarative
      `plugin.json` (commands, keybindings, tool windows, editor menu items, status-bar segments;
      snippets/templates). Off by default, full-trust, loaded via a child `URLClassLoader` so the same jar
      works in dev and the packaged installers. **Browse & install** from a curated GitHub registry or a
      local `.zip`; **19 plugins published** (text/encode/hash/json-xml/slug/box, UUID-timestamp inserters,
      markdown-TOC, formatter, open-on-GitHub, reveal/terminal, scratchpad, regex-tester, color-picker,
      word-count, calculator, task-runner, lorem-ipsum). **Security:** the index is verified against a
      bundled Ed25519 signature (*Require signed plugins*, default on), downloads are SHA-256-verified over
      HTTPS with bounded reads, and a capability-disclosure confirm is shown before enabling. See
      `docs/plugins.md`
- [x] Git history, blame & stash (IntelliJ/VSCode parity) — a **Git Log** tool window (`M-g h` / *Show File
      History*): browse commits, see a commit's files, double-click for a read-only diff, right-click to
      Copy Hash / Checkout / Reset / Revert / Cherry-Pick / New Branch. **Inline blame** (`M-g a`,
      GitLens-style "author, time ago • summary" on the current line; off by default). **Stash**
      push/pop/apply/drop (palette + branch dropdown). All Git-gated (off in Simple UI mode)
- [x] Simple UI mode — a one-toggle minimal layout (toolbar icon, **View: Toggle Simple UI Mode**,
      Settings → Application, or `--simple`): hides the extra toolbar groups (new-from-template, recent,
      find-in-files, split, project selector), the tool-window stripe, breadcrumb, the entire gutter
      (collapsed regions unfolded first), minimap, and most status-bar segments — keeping tabs, the core
      toolbar icons (incl. **Open**), and echo/read-only/zoom/Ln-Col/file-size. Also disables the heavier features
      (LSP, debugging, HTTP client, Git, multiple cursors / column selection). Persisted; `--simple` is a
      session-only override; saved preferences are untouched and restored on exit
- [x] Remote file access (SFTP) — connect over SSH/SFTP and edit a server's files as if local: the remote
      folder mounts in the Project tool window, open/edit/save go straight over SFTP, saved connections
      (metadata only) reconnect via a picker; local-process features (LSP/DAP/Git/Run/HTTP) auto-disable
      for remote files. Off by default; built on Apache MINA SSHD (Remote: Connect / Saved Connections /
      Open File / Disconnect)
- [x] HTTP Client (`.http`/`.rest` files) — a green ▶ on every request runs it with the built-in JDK
      HTTP client; response (status/headers/pretty-JSON/timing) in an HTTP Client tool window (`M-0`) with a
      highlighted viewer, history, Copy/Import as cURL. Near IntelliJ parity: `{{var}}`/`@var` + dynamic vars
      (`$random`/`$datetime` with date math/`$dotenv`), request chaining, multipart + external-file bodies,
      environment files (`http-client.env.json` + `$shared`), Basic/Digest auth, auto URL-encoding,
      response-to-file, per-request directives, run-whole-file
- [x] HTML Live Preview — a floating browser icon on `.html`/`.htm`/`.xhtml` files opens them in a detected
      browser (Safari/Chrome/Firefox/Edge/system default) served over a loopback JDK `HttpServer`, with
      live-as-you-type reload (assets load from disk; the page from the live buffer text); off by default
      (Settings → HTML Preview); `htmlPreview.open` / `htmlPreview.openIn` / `view.toggleHtmlPreview`
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
- [x] Fill paragraph/region (Emacs `M-q` / Fill Region / `C-x f` set fill column) — re-wrap to a fill
      column, preserving indentation + an adaptive fill prefix (line comments, `>` quotes, Javadoc `*`)
- [x] Smart line start (`C-a`) — first press to the first non-whitespace, second toggles to column 0
- [x] Markdown formatting — format bar + smart list/heading/link/table editing (see "Recently shipped")
- [x] Format document — **LSP: Format Document** reformats the whole file via the language server
      (`textDocument/formatting`, when it advertises formatting), undoable; palette + editor right-click.
      (GFM table reflow also exists.)
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
      LSP-backed completion, TS/PHP auto-imports, and **Format Document** (whole-file reformat).
      Server-centric registry, per-server Settings, off by default. Document symbols power the Structure
      tool window. (Next: format-on-save; rename, code actions, quick fixes.)
- [x] Fix structure for the 21 languages we support — the Structure tool window now builds from the
      language server's `textDocument/documentSymbol` (precise hierarchy, real kinds, per-kind icons,
      method signatures), with the fold-region/TextMate heuristic as the fallback for non-LSP files;
      sort (Position/Name/Kind) + kind filter, expanded by default
- [x] Multi language support — UI string translation (en/it/es/fr/pt/de); see "UI localization (i18n)"
      under "Recently shipped"

## Snippets
- [ ] GUI for Snippet management

## Files & version control
- [x] Git support — native CLI (branch/status, gutter change bars, commit workflow, fetch/pull/push)
- [x] Diff viewer + merge-conflict UI — side-by-side / unified diff (vs HEAD / commit / another file),
      word-level highlights, apply-hunk / apply-all, patch export, merge-conflict resolver
- [x] Local file history — IntelliJ-style snapshots on save / auto-save / before an external reload; a
      **File History** tool window (`M-g l`) lists revisions (date/time, reason, size; latest tagged
      *Current*), double-click for a read-only diff vs current, restore = undoable whole-file replace.
      Gzip'd content-addressed blobs + a per-project index under `<configDir>/history/`, deduped, with
      configurable retention (revisions/file, age, size/project). On by default; local-only; off in Simple UI
- [x] Detect external file changes — prompt to reload when a file changes on disk (focus-regain / tab switch)
- [ ] Auto-reload modified files
- [x] Remote file editing support — SSH/SFTP: browse/open/edit/save remote files; saved connections
      (metadata only); local-process features auto-disable for remote (see "Recently shipped")
- [ ] Log mode support

## Keybindings
- [ ] Complete emacs movement/text manipulation keybindings
- [x] Fully configurable shortcuts — keybinding editor in Settings → Keymaps: searchable command list,
      multi-key chord recorder, conflict warnings, per-command + global reset; live (no restart), persisted
      as overrides on top of the active keymap theme
- [x] Keybinding themes — switchable in Settings → Keymaps / `keymap.select`, live (no restart), per-OS
      (Ctrl vs Cmd): **Emacs** (default), **CUA**, **Sublime Text**, **VSCode**, **IntelliJ IDEA**
- [ ] Vim keybindings (modal — needs a mode state machine: normal/insert/visual, operators, counts,
      registers, `:` command line; deferred as its own feature)
- [x] Standard accelerator commands — `edit.selectAll` / `edit.duplicateLine` / `edit.moveLineUp` /
      `edit.moveLineDown`, bound in the CUA/Sublime/VSCode/IntelliJ keymaps

## UI / UX
- [ ] UI final touches (fonts, colors, etc.)
- [x] Pretty up Settings Window — sidebar categories, search, live preview, reset
- [x] File-type icons — a per-type glyph (language logos, image/archive/PDF/table/…, generic fallback)
      everywhere a file is listed: tabs, Project tree, Open-Files/Recent pickers, Switcher, file/folder finders
- [x] "Current Folder" explorer — with no project open, the Project tool window roots at the active file's
      folder and follows the focused tab
- [ ] Upgrade breadcrumbs support — _partial:_ Reveal in File Manager / Open Terminal Here on a crumb
- [ ] Fix Zen mode
- [~] Font ligatures (Fira Code / JetBrains Mono `=>`, `!=`, …) — **not feasible on the current stack.**
      Programming ligatures are OpenType contextual alternates (`calt`), and JavaFX exposes no
      feature-control API (no `-fx-font-feature-settings`, no `Font` method) — it only auto-shapes
      complex scripts, never Latin programming ligatures. Even if it did, RichTextFX's editing model
      maps one char → one glyph cell for caret/selection/hit-testing, which ligature glyph-substitution
      breaks (the caret lands in the wrong column). Would require both JavaFX feature support *and*
      ligature-aware caret math in the fork. Deferred unless JavaFX adds OpenType feature control

## Extensibility & integration
- [x] Plugins/API support — Java SPI (`com.editora.plugin.Plugin`) + declarative `plugin.json`
  (keymap / external commands / snippet & template dirs); contributes commands, keybindings, tool windows,
  editor right-click items, and status-bar segments. The `ActiveEditor` facade does
  `filePath`/`text`/`selectedText`/`caretLine`/`replaceSelection`/`insertAtCaret`/`setText`/`openPath`, and
  `PluginContext` adds `openUrl`/`log`/`setStatus` + path accessors. Off by default (Settings → Plugins).
  Loaded via a child `URLClassLoader` so it works in the sealed jlink installers. **Registry + install:**
  browse a curated GitHub-hosted `index.json`, install (download + SHA-256 verify + zip-slip-guarded unzip)
  or install from a local `.zip`; per-plugin Remove. **19 plugins published** in the
  [adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) registry (which also
  carries each plugin's source), with 18 worked examples under `examples/`. **Security:** the index is
  verified against a bundled **Ed25519 signature** (`Settings.pluginRequireSignature`, default on, blocks an
  unsigned/unverified registry; sign with `scripts/PluginSigningTool.java`); a **capability-disclosure
  confirm** (jar? external commands? keybinding remaps?) runs before enabling at every arming point; reads
  are size-bounded and a non-default registry host is flagged. See `docs/plugins.md`.
  *Deferred: sandboxing, hot reload, gutter-marker contributions, GitHub-API/per-repo discovery,
  per-plugin/TOFU signing, auto-update.*
- [ ] External Tools support
- [x] MCP support — a minimal **Model Context Protocol** server embedded in the editor (loopback HTTP +
      bearer-token auth) so an LLM agent (Claude Code, …) can observe state + drive the command registry.
      Six tools: `list_open_files`, `read_buffer`, `get_diagnostics`, `find_in_files`, `list_commands`,
      `execute_command`; writes `<configDir>/mcp-endpoint.json` for discovery; status-bar indicator
      (click to copy the connection command). Off by default behind a security-notice dialog
      (Settings → MCP Server; `view.toggleMcp` / `mcp.copyEndpoint`). No new dependency (`jdk.httpserver`).
      (Next: more tools, resources/prompts, stdio transport.)
- [ ] Headless support

## Packaging
- [ ] Sign native installers
