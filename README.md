![Editora logo](src/main/resources/com/editora/icons/icon-128.png)

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
**JavaFX 26**, [**RichTextFX**](https://github.com/FXMisc/RichTextFX) and **Maven**. Every action is a registered command, reachable by an
Emacs-style keymap or a fuzzy command palette.

Editora is built with the help of AI coding tools.

🌐 **Website:** [editora-project.dev](https://editora-project.dev) — features, docs, blog, and downloads
(source: [adriandeleon/editora-website](https://github.com/adriandeleon/editora-website)).

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Build & Run](#build--run)
- [Contributing](#contributing)
- [Releases](#releases)
- [Command line](#command-line)
- [Configuration](#configuration)
- [License](#license)

## Features

- **Command-driven core** — every action is a `Command`; bind it to a chord or run it
  from the M-x command palette, which shows each command's one-line description and opens its online
  docs with `C-h`.
- **Keyboard "Jump to…" popups** — fuzzy pickers for recent files (`C-x C-r`), the active file's
  structure/symbols (`M-g i`), open files/tabs (`C-x b`), and tool windows (`M-g t`) — keyboard-first
  alternatives to their list/tool-window UIs.
- **Keyboard file finder** (`C-x C-f`) — Emacs `find-file`-style path popup with prefix
  autocomplete; type/`Tab` to complete, Enter to descend folders or open (or create) a file. The
  Open toolbar icon still uses the native OS dialog.
- **Projects** (off by default; enable in Settings) — VSCode single-folder-workspace style: a root
  folder + its own saved session (open files, layout, folds), shown as a filterable file tree in the
  Project tool window with a project switcher in the toolbar. Open (`C-x C-p`)/switch (`C-x p`)/close
  via the palette or toolbar; switching restores that project's files and layout. With no project open, the
  Project tool window becomes a **"Current Folder"** explorer rooted at the active file's directory, tracking
  the focused tab.
- **Keybinding themes** — choose **Emacs** (default), **CUA**, **Sublime Text**, **VSCode**, or
  **IntelliJ IDEA** in Settings → Keymaps (or the `Keymap: Select…` command); switching is live, no
  restart, and each theme adapts to macOS (Cmd) vs Windows/Linux (Ctrl). Emacs uses multi-key chord
  sequences (e.g. `C-x C-s`). (Modal Vim is on the roadmap.)
- **Configurable shortcuts** — Settings → Keymaps lists every command with its current chord; **record**
  a new shortcut (multi-key sequences supported), **reset** one to the keymap default, or reset them all.
  Rebinding warns on conflicts and applies live; overrides persist and layer on top of the active theme.
  (On macOS, the Option dead keys `Option`+`e`/`i`/`u`/`n`/`` ` `` are intercepted by the OS for
  accent composition, so a few `M-`-chords like `M-e` aren't reachable by keyboard there — the
  command palette still works.)
- **Syntax highlighting** — TextMate grammars (via [tm4e](https://github.com/eclipse/tm4e)) for 40+
  languages and formats: Java, TypeScript/JavaScript, XML, shell, PowerShell, DOS batch, Python,
  Groovy, Kotlin, Ruby, PHP, C, C++, Rust, Go, C#, Lua, Markdown, JSON, CSS, HTML, YAML, INI, TOML,
  SQL, Dockerfile, Terraform/HCL, Mermaid, `.http`, unified diffs (`.diff`/`.patch` — added/removed
  lines tint green/red), Makefile, justfile, Protocol Buffers (`.proto`), GraphQL, Java
  `.properties`, `.gitignore`/`.gitattributes`, dotenv, and common Linux/tool config files
  (systemd units, SSH/Git config, crontab, hosts, fstab, …).
- **Bundled fonts** — JetBrains Mono (default), Cascadia Code, Fira Code, IBM Plex Mono,
  and Source Code Pro ship with the app; no system install required.
- **Editor view options** — 80-column ruler and current-line highlight.
- **Auto / smart indentation** — Enter keeps the indentation and adds a level after a block opener
  (per language: braces, `:` for Python/YAML, `do`/`then` for shell, `def`/`class`/`do` for Ruby, open
  tags for XML/HTML); Enter between a matching pair opens an indented stanza; typing a closing
  bracket/keyword re-aligns the line. Indent unit (tab vs spaces) is inferred per file, or forced
  globally via Settings → Editor → "Indent style" (Detect / Spaces / Tabs).
- **Snippets** — VS Code/TextMate snippets with tab stops, placeholders, choices, and variables; manage your own per-language snippets in **Settings → Snippets** (or `Snippets: Manage Snippets…`), saved under `<configDir>/snippets/`.
- **File templates** — "New File From Template" scaffolds; manage them in **Settings → Templates** (or `Templates: Manage File Templates…`) — the shipped templates are shown read-only and editing one saves a personal override under `<configDir>/templates/`.
- **EditorConfig** — honors a project's `.editorconfig` (nearest-directory-wins, walking up to `root`):
  indent style/size and `tab_width`, `end_of_line`, `charset` (utf-8, utf-8-bom, latin1, utf-16le/be —
  round-tripped on read and save), `max_line_length` (drives the column ruler), and on-save
  `trim_trailing_whitespace` / `insert_final_newline`. On by default; toggle via Settings → Editor or the
  "View: Toggle EditorConfig" palette command.
- **Server log viewer** — `.log` files get severity highlighting (ERROR/WARN/INFO/DEBUG/TRACE, both inline
  and as a left-edge bar that works even on huge logs), a floating **Follow** toggle (`tail -f` — streams new
  lines as the file grows and auto-scrolls), **open-the-tail** for very large logs (opens read-only at the
  end), and **live level + regex filtering** (filter as you type by a level floor and a regex — or a literal
  substring when it isn't valid regex; a stack trace inherits its record's level so it stays visible).
  Detects Logback/Log4j, `java.util.logging`, syslog, nginx, structured/JSON, zerolog, and access logs. Logs
  open in **View mode** (read-only with an "Enable Editing" banner) by default — follow still streams while
  read-only. On by default (Settings → Editor → Logs, "View: Toggle Log Viewer"); `Log: Toggle Follow` / `Filter by Level` /
  `Filter by Pattern` / `Clear Filter` / `View as Log` in the palette.
- **Word/line-level undo** — undo/redo breaks at word, whitespace, and newline boundaries (and after a typing pause), so one undo removes a word or line rather than a whole typing burst.
- **Undo History** — an *Undo History* tool window (`M-g u`) lists in-session document checkpoints; double-click or Enter jumps back to any recent state.
- **Auto Close Tags** — typing the `>` of an HTML/XML open tag inserts the matching closing tag and
  leaves the caret between them; void elements, self-closing tags, comments/doctypes, and `>` inside
  attribute strings are left alone. On by default (Settings → Editor; "Toggle Auto Close Tags").
- **Auto Rename Tag** — editing an HTML/XML tag name renames the paired open/close tag as you type
  (VS Code behavior); comments, CDATA, quoted attributes, void elements, and `<script>`/`<style>`
  content are skipped, and a brand-new tag never renames an unrelated one. On by default
  (Settings → Editor; "Toggle Auto Rename Tag" in the palette).
- **Auto-close & matching brackets** — typing `([{`/quotes inserts the matching closer (type over it to
  skip, wrap a selection by typing a bracket/quote around it, Backspace clears an empty pair); the
  bracket matching the one next to the caret is highlighted.
- **Comment / uncomment** (`M-;`) — toggles a line comment for a single line and a block/region comment
  for a multi-line selection, using the language's comment syntax (`//`, `#`, `<!-- -->`, `/* */`, `--`, …).
- **Fill paragraph** (`M-q`) — re-wrap a paragraph (or the selection, with "Fill Region") to a fill column
  (`C-x f`, default 70), preserving its indentation and an adaptive fill prefix — line comments, Markdown
  blockquotes (`>`), and Javadoc (`*`) — so code comments and quoted text wrap correctly.
- **Emacs editing & movement commands** — the full set beyond the basics: backward-kill-word (`M-DEL`),
  word/region case conversion (`M-u`/`M-l`/`M-c`, `C-x C-u`/`C-x C-l`), join-line (`M-^`), whitespace
  fixups (`M-\`, `M-SPC`), delete-blank-lines (`C-x C-o`), open-line (`C-o`), kill-whole-line (`C-S-DEL`),
  zap-to-char (`M-z`), balanced-expression motion (`C-M-f`/`C-M-b`, mark/kill-sexp), defun motion
  (`C-M-a`/`C-M-e`), and mark-paragraph / mark-whole-buffer. All palette-discoverable and rebindable.
- **String manipulation** — case-style conversions on the selection or the identifier at the caret
  (camelCase / PascalCase / snake_case / SCREAMING_SNAKE_CASE / kebab-case / dot.case, a *Cycle Case Style*
  that steps a token through the styles on repeated presses, swap case) plus whole-line transforms on the
  selection or whole file (sort ascending/descending — numeric-aware and case-insensitive — sort by length,
  reverse, shuffle, remove duplicate/empty lines, trim trailing whitespace). All individual palette commands,
  or one filterable picker: "Edit: String Manipulation…" (`C-c x`).
- **Multiple cursors & column selection** — VS Code–style multi-caret editing: add a caret at the next
  occurrence of the selection / above / below, type or edit everywhere at once, `Esc` to collapse; plus
  Alt-drag column/box selection. (Powered by a personal RichTextFX fork.)
- **Spell checking** — red wavy underlines on misspelled words, with right-click suggestions,
  Add-to-Dictionary, and Ignore. Source files only check comments and string literals; plaintext and
  Markdown are checked in full. Toggle via "View: Toggle Spell Check"; choose a dictionary per file
  ("Spell Check: Set Language…", ships English en_US/en_GB, Spanish for Spain and Mexico, and French). A bundled
  **technical-terms dictionary** (`config`, `async`, `middleware`, `kubernetes`, …) keeps code-adjacent
  prose from being flagged — toggle it in Settings → Spell Check (default on). Pure-Java (Apache Lucene
  Hunspell).
- **Code intelligence (LSP)** _(Beta)_ — language smarts via the Language Server Protocol, with **21 servers**
  auto-detected on `PATH` (Java/JDT LS, TypeScript/JavaScript, Python/Pyright, Go, Rust, C/C++/clangd,
  C#, PHP, Ruby, Kotlin, Lua, Bash, XML, JSON, YAML, HTML, CSS, Dockerfile, SQL, Terraform, TOML).
  Inline diagnostics + a Problems tool window (`M-8`) + minimap/scrollbar stripes, go-to-definition
  (`M-.`), find references (`M-?`), hover docs (`C-c h`), LSP-backed completion, auto-imports, and
  **Format Document** (whole-file reformat via the server, when it advertises formatting — palette or the
  editor right-click menu). Off by default; per-server command + enable in *Settings → LSP*.
- **Search** — incremental find bar (`C-s`/`C-r`) with regex, case, and whole-word toggles, a match
  count, and live highlight-all; **Find in Files** (`C-S-f`) across the project + open buffers with
  include/exclude file globs, query history, regex capture-group replace (`$1`), `.gitignore` exclusion
  (skips `target/`, `node_modules/`, … by default — Settings → Search), and a results tool
  window (`M-6`, on the right, with a *ripgrep* badge when that faster `.gitignore`-aware backend is
  active); and **AceJump** (`M-g j`) — type a character, then a label, to fly the caret to any on-screen
  occurrence.
- **Run a file from a gutter ▶** — a green play glyph runs a Java 25 compact-source file
  (`java <file>`), a Python script (`python3`), or a shell script (`bash`); output streams into a Run
  tool window (`M-9`) with clickable stack traces, stdin, and per-file program arguments. Gated by the
  LSP feature.
- **Debugging (DAP)** _(Beta)_ — a full debugger for **Java**, **Python** (debugpy), and **JavaScript/Node**
  (vscode-js-debug): breakpoints (conditional / logpoints), step / resume / pause / run-to-cursor /
  jump-to-line, call stack, variables, watches and set-value, inline values and a value-hover popup, and
  an IntelliJ-style Debug tool window (`M-g d`). Off by default (*Settings → Debugging*); adapters are
  user-installed (helper scripts provided).
- **Read-only / View mode** — toggle a buffer read-only (`C-x C-q` or the palette) to view without
  editing; typing and edit commands are blocked while everything else keeps working. Files that aren't
  writable on disk open read-only automatically, and the per-file state is remembered across restarts.
  A Word-style "View Mode" banner docks above the editor with an **Enable Editing** button (when the
  file is writable). While read-only, Space pages down and Backspace pages up (pager-style).
- **Simple UI mode** — a single toggle (toolbar icon, **View: Toggle Simple UI Mode** in the palette,
  *Settings → Application*, or the `--simple` launch flag) that strips the window to a minimal editing
  surface: it hides the extra toolbar groups (new-from-template, recent, find-in-files, split, project
  selector), the tool-window stripe, the breadcrumb,
  the entire gutter (line numbers + fold chevrons + markers; collapsed regions are unfolded first), the
  minimap, and most status-bar segments (git, LSP, language, tab size, line endings, encoding), while
  keeping the tabs, the essential toolbar icons (including **Open**), and the file-size segment. It also **disables the heavier features** — language
  servers (LSP), debugging, the HTTP client, Git, and multiple cursors / column selection — for a quiet
  plain editor. Persists across restarts; your saved preferences (line numbers, minimap, breadcrumb, tool
  stripe, LSP/debug/HTTP/Git and multi-caret enables) are all restored when you turn it off.
- **Text zoom** — scale the editor text on top of the font size (status-bar `− 100% +`, `C-=`/`C--`/`C-0`,
  Ctrl+mouse-wheel, or the palette); persists across restarts, separate from the font-size setting.
- **Themes** — 26 switchable AtlantaFX themes: the built-in Primer, Nord, Cupertino and Dracula,
  plus the community set (Blue, Navy, Army, the Spring/Summer/Fall/Winter seasonal pairs, Autumn,
  Browny, News and Yacht). Each has a matching editor color theme (syntax + surface) that follows the
  app theme by default and is independently selectable in Settings; the community themes use an
  adaptive syntax palette drawn from each theme's own colors.
- **Markdown preview** — IntelliJ-style 3-mode view (Editor / Editor + Preview / Preview) via a
  floating control top-right of the editor, rendered natively (CommonMark + GFM: tables, task lists,
  strikethrough, autolinks, plus **YAML front matter**, **footnotes**, **heading anchors**, and
  **`++inserted++`** text) with **GitHub-style** output — task-list checkboxes, inline-code pills,
  underlined h1/h2, and **images** (local and remote). Live-updating and theme-matched; the mode is
  remembered per file. In Split mode the editor and preview scroll together (the pane under the mouse
  drives the other). Zoom the preview text with its `−`/`+` control or, in Preview mode,
  **Ctrl + mouse wheel**. The **Structure** tool window shows the document's heading outline.
- **Markdown authoring** — **paste or drag-drop images** into a saved Markdown file (saved into a sibling
  `assets/` folder with an `![](…)` link inserted), **smart link paste** (paste a URL over a selection to
  make `[selection](url)`), and **table editing** with **Tab** / **Shift-Tab** to move between cells and
  **Enter** to add a row (reflowing as you go) — alongside the existing format bar and smart list/heading
  editing.
- **Markdown lint** — a markdownlint-style rule set (heading-level increment, hard tabs, trailing
  whitespace, blank-line runs, heading-marker spacing/indent/trailing-punctuation, headings & fenced code
  surrounded by blank lines, multiple H1, first-line H1, fenced blocks missing a language, bare URLs,
  missing final newline, broken reference links) shown as inline squiggles with hover messages, a
  scrollbar **overview stripe** + minimap ticks, and a **Markdown Lint** tool window. Disable individual
  rules in Settings (or with `<!-- markdownlint-disable MDxxx -->` comments / a `.markdownlint.json`),
  and **auto-fix** the mechanical issues with "Markdown Lint: Fix Issues". On by default; toggle with
  "View: Toggle Markdown Lint".
- **LaTeX math** — render inline `$…$` and display `$$…$$` math in the preview (and block formulas in PDF
  export) via the pure-Java **JLaTeXMath**, with GitHub-style delimiter rules so prose dollar amounts are
  left alone. **On by default** — toggle under *Settings → Editor → Render LaTeX math* or with
  "View: Toggle Math Rendering".
- **Mermaid diagrams** — render Mermaid in the preview (standalone `.mmd` files and ` ```mermaid `
  fenced blocks inside Markdown), export a diagram to **SVG / PNG / PDF**, get live `maid` linting with
  inline error squiggles, and keyword + snippet autocomplete in `.mmd` files. Uses the external
  **mmdc** (mermaid-cli) to render/export and **maid** to lint (configure their commands in Settings).
  **On by default** when the `mmdc` CLI is found (inert otherwise) — toggle under *Settings → Mermaid*.
  `mmdc` drives a headless Chrome via Puppeteer; if rendering fails with *"Could not find Chrome …"*
  install it once with `npx puppeteer browsers install chrome-headless-shell` (on Linux a global
  `npm i -g @mermaid-js/mermaid-cli` sometimes skips Chrome's download). The in-app
  **Settings → Mermaid → Install…** button (and the `install.mermaidSupport` command) now does this for
  you — it installs mmdc *and* that Chrome.
- **Export to PDF** — export the active file as a real, *searchable* PDF: source code with syntax
  highlighting and optional line numbers (always a light theme), the **Markdown** preview as native
  vector text (headings, lists, tables, images, embedded diagrams), or a standalone Mermaid `.mmd`
  diagram. Run "File: Export to PDF" / "File: Export Preview to PDF" from the palette; choose line
  numbers, syntax highlighting, and page size (Letter / A4) under *Settings → Editor → PDF Export*.
- **Export to HTML** — export a Markdown file's rendered preview to a standalone, self-contained `.html`
  file (embedded stylesheet, heading anchors, math rendered as images). Run "Preview: Export to HTML" from
  the palette.
- **Print** — native printing of code or the rendered Markdown preview, with a print-preview window
  first (always light, what-you-preview-is-what-prints), reusing the PDF layout core. Run "File: Print"
  / "File: Print Preview" from the palette.
- **Snippets** — VS Code / TextMate-style templates with interactive tab stops. Type a prefix + Tab to
  expand (or pick via `C-c i` / "Snippet: Insert…"); Tab/Shift-Tab cycle fields, placeholders are
  pre-selected, mirrors update live, `$0` is the final caret. Standard body syntax (`$1`,
  `${1:default}`, mirrors, choices, variables, escapes). Snippets ship for all 21 highlighted languages
  (most from the MIT [friendly-snippets](https://github.com/rafamadriz/friendly-snippets) collection);
  add your own in `~/.editora/snippets/<language>.json` (user snippets override bundled).
- **File templates** _(Beta)_ — "New File From Template" (`C-c C-n`) creates a file (or a whole set of files) from
  a reusable template, prompting for any `${variables}` in a wizard and placing the caret at `${cursor}`.
  Bundled templates (Java class, HTML page / multi-file bundle, Markdown doc, Python script) plus your
  own in `~/.editora/templates/`.
- **Autocomplete** — appears as you type (and on demand via `C-M-i` / `M-/`). In **code**, a popup of
  **snippet** completions (accepting expands the snippet with its tab stops; Enter/Tab accept, arrows
  navigate). In **prose** (plain text / Markdown), inline **"ghost text"** — a single greyed
  continuation after the caret from the spell dictionary + your personal dictionary; **Tab** accepts.
  Settings → Editor has a master toggle plus per-source checkboxes (words/prose, snippets).
- **Welcome page** — a VSCode-style start panel (New File / Open File / recent files) shown in the editor
  area when no files are open, instead of a blank Untitled buffer; `--new-file[=name]` opens a fresh buffer
  instead.
- **Recent files** — persistent most-recently-used list.
- **Image viewer** — opening a raster image (`.png`, `.jpg`/`.jpeg`, `.gif`, `.bmp`) renders the picture in a
  read-only tab instead of dumping its binary bytes as text, with zoom out/in/fit/actual-size (and Ctrl+wheel
  zoom). SVG stays editable text.
- **Hex viewer** — opening a binary file (executable, archive, `.class`, `.pdf`, …) shows a read-only
  `offset | hex | ASCII` dump instead of garbage text. Binaries are detected by content; large files show
  their first slice with a truncation note. "View: Open as Hex" force-opens any file's bytes as hex.
- **File-type icons** — every file shows a glyph for its type (the Java/Python/CSS/… logo, an image,
  archive, PDF, table, … glyph, or a generic document fallback) everywhere it's listed: editor tabs, the
  Project tree, the Open-Files / Recent pickers, the Switcher, and the file/folder finders. Monochrome
  single-path glyphs that track the light/dark theme (Simple Icons + Material Design Icons).
- **TODO Comment Manager** — `TODO`, `FIXME`, `HACK`, `NOTE`, `XXX` (and your own keywords) are highlighted
  wherever they appear, and understood in the IntelliJ-style structured form `KEYWORD [tag] (priority) description`
  — e.g. `// TODO [auth] (high) fix token refresh` — with each part colored (tag underlined; priority by level).
  The **TODO tool window** (`M-g o`) lists every match with a **Group by** selector (File / Priority / Tag /
  Keyword), and right-clicking a match edits it in your source: **Mark Done** (→ `DONE`) / Reopen, set the
  priority, or edit the description — as one undoable edit. Configure the keywords/colors in
  Settings → Editor → TODO Highlighting.
- **Bookmarks** — toggle line bookmarks (`C-c m`) with a gutter marker and optional notes; the
  Bookmarks tool window lists them across all files, `C-c ]`/`C-c [` cycle within a file, and `M-g b`
  is a cross-file jump picker. Reorder bookmarks (and file groups) in the tool window with Alt+Up/Down,
  the right-click menu, or drag-and-drop — the jump picker follows the same order. Saved in
  `bookmarks.json`, scoped per project (switching projects shows that project's bookmarks; deleting a
  project deletes its bookmarks).
- **Personal Notes** — private annotations attached to a file *without modifying the file* (ideal for
  read-only, generated, or shared code). Add a note on a word, line, or selection range
  (`C-c n`, the editor right-click menu, or the gutter glyph); give it a body, tags, and a status
  (active / resolved / orphaned). Notes follow their content as you edit and re-anchor on reopen by the
  captured text + context — and by **content hash**, so they survive a file being renamed or moved
  outside the app (a note that can't be relocated is kept as *orphaned*, never lost). A gutter glyph, a
  soft in-editor highlight, and a hover tooltip mark each note (toggle via *Settings → Editor → Show note
  indicators*). The **Personal Notes** tool window (`M-5`) groups them per file with a filter and
  edit/resolve/delete; `M-g n` jumps across files and notes export to JSON. Stored per project in
  `notes.json`. Separate from bookmarks — both coexist in the gutter. **On by default** — toggle
  under *Settings → Application → Enable Personal Notes*.
- **Git** _(Beta)_ — uses your installed `git` (no bundled library). The status bar shows the current branch with
  ahead/behind counts (click to switch branches); the gutter draws change bars vs `HEAD` (added /
  modified / deleted); and the **Commit** tool window (`M-4`) lists Staged / Changes / Untracked files with
  stage, unstage, discard, **Stage All**, and a commit box. Palette/keys cover commit (`C-x g`), stage
  current file, switch/new branch, fetch/pull/push, and **clone** ("Git: Clone Repository…" clones a
  repo and opens a file from it — independent of projects). A **Git Log** tool window (`M-g h`, or *Show
  File History* on a tab) browses commits — select one to see its files, double-click for a read-only
  diff, right-click to Copy Hash / Checkout / Reset / Revert / Cherry-Pick / New Branch. **Inline blame**
  (`M-g a`, GitLens-style) annotates the current line with "author, time ago • summary" (toggle in
  *Settings → Git*, off by default). **Stash** push / pop / apply / drop from the palette or the branch
  dropdown. All off the UI thread; **on by default** but hidden when not in a repo or when `git` isn't on `PATH`.
- **Diff viewer & merge** _(Beta)_ — compare files in a dedicated tab: side-by-side or unified, with word-level
  intra-line highlights, prev/next-change navigation, apply-a-hunk / apply-all (undoable), live refresh,
  and patch export. Diff against `HEAD` (`C-x v =`), another commit, or any other file; a separate
  merge-conflict resolver accepts ours / theirs / both per conflict.
- **Local file history** — IntelliJ-style snapshots of local files, taken on save, auto-save, and before an
  external-change reload, independent of any VCS. A **File History** tool window (`M-g l`) lists each revision
  (date/time, reason, size; the latest tagged *Current*); double-click for a read-only diff against the
  current file, or restore one (an undoable whole-file replace). Snapshots are deduped by content and stored
  gzip-compressed under `<configDir>/history/`, pruned by configurable limits (revisions/file, age,
  size/project). On by default; local-only; off in Simple UI mode.
- **HTTP client** _(Beta)_ — open a `.http`/`.rest` file and click the green ▶ next to a request to run it with
  Editora's **built-in** HTTP client; the response (status, headers, pretty-printed JSON body, timing/
  size) shows in an HTTP Client tool window (`M-0`, docked on the **right**, which auto-opens for `.http`
  buffers and auto-hides when you switch away — close it manually to keep it shut for that file) with a content-type-highlighted viewer, an in-session
  history, **Copy as cURL** / **Import cURL**, and Open-in-editor. Close to IntelliJ's HTTP Client: `{{var}}`/
  `@var` substitution and **dynamic variables** (`{{$random.*}}`, `{{$datetime}}` with date math, `{{$dotenv.X}}`,
  …), **request chaining** (reference an earlier request's response), **multipart** and external-file bodies,
  **environment files** (`http-client.env.json` + a `$shared` section) with a picker, **Basic/Digest auth**
  shorthand, automatic URL encoding, response-to-file redirects, per-request directives, run-whole-file, and
  saving the response. On by default (*Settings → HTTP Client*).
- **HTML live preview** _(Beta)_ — a floating browser icon on any HTML file opens it in a detected desktop
  browser (Safari, Chrome, Firefox, Edge, or the system default), served over a tiny **loopback** web server
  so its CSS/JS/images load. The page **reloads live as you type** (unsaved edits included). On by default
  (*Settings → HTML Preview*); no external tool — it uses the JDK's built-in HTTP server.
- **Remote files (SFTP)** _(Beta)_ — connect to a server over SSH/SFTP (*Remote: Connect to SFTP…*) and edit its
  files as if they were local: the remote folder mounts in the Project tool window, and open/edit/save go
  straight over SFTP. Authenticates with your default `~/.ssh` keys, a chosen key file, or a password;
  saved connections (metadata only — never a password) reconnect via a picker, a **Remote Sites** tool window
  (`M-g r`), a **Settings → Remote** management page (add/edit/remove sites), or a quick-connect list on the
  Welcome page — each reopens the connection form pre-filled. Features that need a local process (language
  servers, debugging, Git, Run, the HTTP client) auto-disable for remote files. Off by default; built on
  Apache MINA SSHD.
- **Plugins** — extend Editora without forking it. A plugin is a folder under `<configDir>/plugins/<id>/`
  with a `plugin.json` manifest plus, optionally, a Java jar and asset dirs. A **Java SPI**
  (`com.editora.plugin.Plugin`) can add palette commands, keybindings, dockable tool windows, editor
  right-click items, and status-bar segments; a **declarative manifest** adds keymap bindings, external
  commands, and `snippets/`/`templates/` dirs — no code. Loaded via a child class loader, so the same jar
  works in dev and in the packaged installers. **Off by default and full-trust** (no sandbox) — enable it,
  and each plugin, in *Settings → Plugins*. **Install** by browsing a curated GitHub-hosted registry or from
  a local `.zip`. Security: the registry `index.json` is verified against a bundled **Ed25519 signature**
  (*Require signed plugins*, default on, blocks an unsigned/unverified registry); downloads are
  **SHA-256-verified** over HTTPS with bounded reads; and a **capability-disclosure** confirm (does it run
  code? which external commands? which keybindings?) is shown before any plugin is enabled. Signing proves
  *who* published — not a sandbox. See [`docs/plugins.md`](docs/plugins.md), the reference
  [`examples/example-plugin/`](examples/example-plugin/), and the live registry (every plugin's source +
  a signed `index.json`) at [adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins).
- **AI actions** _(Beta)_ — one-shot AI features that call the Anthropic API directly (streamed, no SDK):
  generate a **commit message** from the staged diff into the Commit window, **explain the selection** in
  a new Markdown buffer, or **rewrite the selection** per an instruction as a single undoable edit. Plus
  **AI inline completion**: after a typing pause, a muted one-line ghost suggestion at the caret — Tab
  accepts (its own fast model, default `claude-haiku-4-5`). Off by default (Settings → AI Actions); model
  configurable (default `claude-opus-4-8`); API key from `ANTHROPIC_API_KEY` or a Settings override.
- **AI Agent** _(Beta)_ — chat with an embedded coding agent over the
  [Agent Client Protocol](https://agentclientprotocol.com) (ACP). The default command is
  `claude-code-acp` (Claude Code's ACP adapter; any ACP agent works via Settings → AI Agent). The
  agent's file reads see open buffers' unsaved text, and its edits to open files apply as **undoable
  buffer edits** you review and save; permission requests pop a dialog. Off by default; the agent is a
  user-installed external tool, never bundled.
- **MCP server** _(Beta)_ — embed a [Model Context Protocol](https://modelcontextprotocol.io) server in the
  running editor so an LLM agent (Claude Code, etc.) can observe live editor state, edit files, and drive the
  command registry. A small **loopback-only** HTTP/JSON-RPC server with **bearer-token auth** exposes twelve
  tools — reads (`list_open_files`, `read_buffer`, `get_selection`, `get_diagnostics`, `document_symbols`,
  `git_status`, `find_in_files`, `list_commands`), writes (`edit_buffer` — undoable str-replace edits —
  `save_buffer`), and actions (`open_file`, `execute_command`) — and writes its endpoint to
  `<configDir>/mcp-endpoint.json` for discovery. A status-bar
  **MCP** indicator shows when it's running (click to copy the connection command). Off by default and
  guarded by a security-notice dialog — enable it under *Settings → MCP Server* (or the **Toggle MCP Server**
  command). No external tool or new dependency (the JDK's built-in `HttpServer`).
- **Tool windows** — IntelliJ-style dockable panels (Project, Commit, Git Log, File History, Structure, File
  Information, Bookmarks, Personal Notes, Problems, Search Results, Run, Debug, HTTP Client) — plus any
  contributed by a plugin.
- **Settings** — a category sidebar (Appearance, Editor, Tool Windows, Spell Check, Application, …) with a
  search box, a live font/theme preview, and Reset to Defaults. Changes apply instantly.
- **Multi-language interface** — run Editora in **English, Italian, Spanish, French, Portuguese, or
  German**. Pick a language under Settings → Appearance → Language (default *Automatic* follows your
  system language, falling back to English); the change applies on the next restart.

## Available plugins

A curated registry of ready-to-install plugins lives at
[adriandeleon/editora-plugins](https://github.com/adriandeleon/editora-plugins) (the baked-in default).
Enable plugins in *Settings → Plugins*, then **Browse plugins…** to install any of these (or *Install from
file…* for a local `.zip`). Each plugin's full source is in that repo under `plugins/<id>/`.

| Plugin | What it does |
| --- | --- |
| **Example Plugin** | Reference plugin exercising every extension point (command, keybinding, tool window, editor menu item, status-bar segment, snippet). |
| **Lorem Ipsum** | Insert a lorem-ipsum paragraph, or replace the selection with one. |
| **Text Tools** | Transform the selection/document: case convert, sort, unique, reverse, trim trailing, squeeze blank lines. |
| **Encode Tools** | Encode/decode: Base64, URL, HTML entities, ROT13, hex. |
| **Hash Tools** | Hash text to a hex digest — MD5 / SHA-1 / SHA-256. |
| **JSON / XML Tools** | JSON pretty-print / minify and XML pretty-print. |
| **Slug & Sequence** | Slugify text; number lines; fill a column with `1..N`. |
| **Box Banner** | Wrap the selection in an ASCII box banner. |
| **Insert Tools** | Insert a UUID or the current date/time at the caret. |
| **Markdown TOC** | Insert a table of contents built from the document's headings. |
| **Format Runner** | Format the active file with an external formatter (prettier/black/gofmt/rustfmt/clang-format). |
| **Open on GitHub** | Open the active file at the caret line on its remote's web UI. |
| **Scratchpad** | A persistent scratchpad tool window (auto-saved). |
| **Regex Tester** | A live regex tester tool window (pattern + flags + match spans/groups). |
| **Color Picker** | Pick a color and insert it as HEX / `rgb()` / `rgba()`. |
| **Word Count** | Live word/line/character count + reading time for the active buffer. |
| **Calculator** | Evaluate arithmetic expressions and insert the result. |
| **Task Runner** | Run a shell task (`npm`/`make`/…) in the file's directory and stream output. |

> Plugins run with **full trust** (no sandbox) — only install ones you trust. To build your own, see
> [`docs/plugins.md`](docs/plugins.md), the reference [`examples/example-plugin/`](examples/example-plugin/),
> and the source of every plugin above in the
> [editora-plugins](https://github.com/adriandeleon/editora-plugins) repo.

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

On **Linux**, the `.deb` installs the app under `/opt/editora/`, registers it in the application menu
(with the Editora icon), and adds an **`editora` command on `PATH`** (a `/usr/bin/editora` symlink
created by the package's maintainer scripts), so you can launch it from the menu or from a terminal
with arguments — e.g. `editora some/file.java:42` or `editora --new-file=notes.md`. The menu entry and
command are removed when you uninstall the package. (The `.rpm` installs under `/opt/editora/` too; run
`/opt/editora/bin/Editora` or add your own symlink.)

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

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow, and the **developer documentation** in
[`docs/`](docs/README.md) — architecture, the conventions a change must follow, performance rules, an
extension cookbook (add a command / LSP server / grammar / tool window / overlay), and build/test/release
guides. (User-facing docs live in the separate website repo; `docs/` is for contributors.)

All Java is auto-formatted with [Palantir Java Format](https://github.com/palantir/palantir-java-format)
(a lambda-friendly, 120-column fork of google-java-format), enforced by the
[Spotless](https://github.com/diffplug/spotless) Maven plugin. **Run `./mvnw spotless:apply` before
committing** — `spotless:check` is bound to the `verify` phase, so `./mvnw verify`, `./mvnw package`,
and CI fail on unformatted code (the `javafx:run` / `compile` dev loop is deliberately left untouched).
The pipeline removes unused imports, formats, then orders imports into three blank-line-separated groups
(JDK → `javafx` → third-party + `com.editora`, static imports last). Wrap hand-aligned code in
`// spotless:off` … `// spotless:on` to opt out.

Two conventions the build checks for:

- **Every user-facing string is localized.** Add new keys to all six
  `src/main/resources/com/editora/i18n/messages[_<lang>].properties` catalogs (English base +
  `it`/`es`/`fr`/`pt`/`de`); the test suite fails if any locale is missing a key.
- **Every action is a command.** User-facing features are registered in the command registry so they
  appear in the command palette (`M-x`); toolbar buttons and keybindings dispatch through commands too.

Tests are mostly pure logic, plus a headless-FX harness for toolkit-bound behavior (TestFX over JavaFX 26's
built-in headless platform, no display/xvfb) —
run all with `./mvnw test`, or the pure suite alone with `./mvnw test -DexcludedGroups=fx`. `./mvnw verify`
also enforces the Spotless check and the JaCoCo per-package coverage floors. See
[`docs/testing.md`](docs/testing.md).

For **manual** smoke-testing and demos there's a curated, feature-organized sample corpus under
[`samples/`](samples/README.md) (syntax per language, folding, markdown, mermaid, todo, spell, search,
editorconfig, http, log, diff, encodings — open the relevant file to exercise a feature). Large perf
inputs are generated on demand by `java scripts/GenSamples.java` (a JDK 25 compact source file;
git-ignored output, not committed).

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
  --new-file[=name]     Open a new buffer instead of the Welcome page (optionally named, e.g. notes.md)
  --single-window[=project]  Open just one window (the named project, else the no-project window)
                        instead of restoring all windows; session-only, doesn't change the saved layout
  --zen                 Start in Zen (distraction-free) mode
  --simple              Start in Simple UI mode (minimal chrome; session only)
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
files in `recent-files.json`, bookmarks and breakpoints (scoped per project) in `bookmarks.json` /
`breakpoints.json`, personal notes (also scoped per project) in `notes.json`, and saved SFTP
connections (metadata only, never a password) in `connections.json`, all alongside it.

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
