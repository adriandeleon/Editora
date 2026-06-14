#!/usr/bin/env python3
"""One-time generator for the feature pages (src/content/features/*.md).
Edit the Markdown files directly after this; re-running overwrites them."""
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "content", "features")
os.makedirs(OUT, exist_ok=True)

KB = "Keyboard & commands"
ED = "Editing"
CI = "Code intelligence"
RD = "Run & debug"
GD = "Git & diff"
DD = "Docs & diagrams"
WF = "Workspace & files"
CE = "Customization & extensibility"

# (slug, group, order, beta, title, summary, body)
F = [
("command-driven-core", KB, 1, False, "Command-driven core",
 'Hunting through menus? Every action is a registered <code>Command</code> — bound to a chord or one <kbd>M-x</kbd> search away. 200+ commands, nothing buried.',
"""Editora has no hidden actions. Every capability — save, toggle a bookmark, start the debugger, switch a theme — is a registered `Command` with an id and a title. That one decision powers three things at once:

- The **command palette** (`M-x`) fuzzy-searches all 200+ commands, each with a one-line description.
- **Keybindings** are just a map from a chord to a command id, so anything can be bound — or rebound.
- **Toolbar buttons** dispatch the same commands, so the UI and the keyboard never drift apart.

If you can describe it, you can find it by typing a few letters. Browse the full [command list](/commands)."""),

("keymaps", KB, 2, False, "Keymaps your way",
 'Pick <strong>Emacs</strong>, <strong>CUA</strong>, <strong>Sublime Text</strong>, <strong>VS Code</strong>, or <strong>IntelliJ IDEA</strong> — switch live, no restart. Or rebind any command yourself in the built-in keybinding editor (multi-key chords like <kbd>C-x C-s</kbd> supported).',
"""Editora ships five complete keymaps — **Emacs** (default), **CUA**, **Sublime Text**, **VS Code**, and **IntelliJ IDEA** — selectable in **Settings → Keymaps** and switchable **live, with no restart**. Each is a chord→command map over the same command ids, so switching changes accelerators without stranding any functionality.

Prefer your own bindings? The built-in keybinding editor records multi-key chords (like `C-x C-s`), rebinds any command, and resets to defaults. Overrides are saved in `settings.toml`, layered on top of the active keymap, so you only specify what you change.

On macOS the non-Emacs keymaps use ⌘ wherever the [keybindings reference](/keybindings) shows Ctrl."""),

("jump-to-popups", KB, 3, False, "Jump-to popups",
 'Lost in a big project? Fuzzy-jump to recent files, symbols, open tabs, and tool windows — plus an Emacs <code>find-file</code>-style path finder.',
"""Keyboard-first navigation: fuzzy pickers that get you anywhere without the mouse.

- **Recent files** — `C-x C-r`
- **Symbols / file structure** — `M-g i`
- **Open tabs** — `C-x b`
- **Tool windows** — `M-g t`
- **Bookmarks** — `M-g b`, **Notes** — `M-g n`

There's also an Emacs `find-file`-style **path finder** (`C-x C-f`) with prefix autocomplete — type and Tab to complete, Enter to descend a folder or open (or create) a file. Every picker shows a footer legend of its navigation keys."""),

("multiple-cursors", KB, 4, False, "Multiple cursors",
 'Add a caret at the next occurrence or above/below, or <kbd>Alt</kbd>-drag a column/box selection — edit many places at once, VS Code-style.',
"""Edit many places at once, VS Code-style. Add a caret at the **next occurrence** of the selection, or **above / below** the current line, or **Alt-drag** a column/box selection. Type, and the edit fans out to every caret; `Esc` collapses back to one.

It's powered by Editora's RichTextFX fork, which adds multiple cursors and column selection as a layered input map that's completely transparent when there's a single caret.

*Note:* movement chords act on the primary caret and don't fan out — use the arrow keys for multi-caret movement."""),

("snippets", ED, 1, False, "Snippets",
 'Retyping the same boilerplate? Expand VS Code / TextMate templates with tab stops, mirrors, choices, and variables — a prefix + <kbd>Tab</kbd>. Ships for all 21 languages.',
"""Expand boilerplate with interactive templates. Type a prefix and press **Tab**, or pick from the **Snippet: Insert…** list (`C-c i`).

Placeholders are pre-selected to overtype, **Tab / Shift-Tab** cycle the fields, mirrors update live, choice fields show a dropdown, and `$0` is the final caret. Bodies use the standard VS Code / TextMate syntax — `$1`, `${1:default}`, mirrors, `${1|a,b|}` choices, and variables (`$TM_FILENAME`, `$CLIPBOARD`, date/time, the selection…).

Snippets ship for all 21 highlighted languages; add your own in `~/.editora/snippets/<language>.json` (user snippets override the bundled ones)."""),

("smart-indentation", ED, 2, False, "Smart indentation",
 'Per-language auto-indent on <kbd>Enter</kbd> (block openers, matching-pair stanzas, closer re-alignment), plus smart backspace that clears a whole indent level in one press.',
"""Enter does the right thing per language: it keeps the current line's indent, adds a level after a block opener (braces, `:` in Python/YAML, `do`/`then` in shell, an open tag in XML/HTML…), and splits a matching pair into an indented stanza with the closer dropped below. Typing a closer (`)]}` or a keyword like `end`/`fi`/`done`) re-aligns the line to its opener.

**Smart backspace** removes a whole indent level in one press — and on a blank, auto-indented line, a single Backspace jumps back to the end of the previous line. The indent unit (tabs vs spaces) is inferred from the file."""),

("auto-close-brackets", ED, 3, False, "Auto-close & bracket matching",
 'Typing <code>([{</code> or quotes inserts the closer, type-over to skip, wrap a selection, and Backspace clears an empty pair — and the bracket beside the caret is highlighted with its match.',
"""Typing `(`, `[`, `{`, `\"`, `'`, or `` ` `` inserts the matching closer and keeps the caret between them. Type the closer when it's already next to the caret and Editora types over it; type an opener with a selection and it wraps the selection; Backspace inside an empty pair deletes both halves.

Quotes aren't auto-paired next to a word character, so the apostrophe in `don't` is left alone. And whenever the caret sits next to a bracket, both it and its match are highlighted."""),

("comment-toggling", ED, 4, False, "Comment toggling",
 "<kbd>M-;</kbd> comments or uncomments the line or selection using the language's syntax (<code>//</code>, <code>#</code>, <code>&lt;!-- --&gt;</code>, <code>/* */</code>, <code>--</code>, …).",
"""`M-;` (Emacs comment-dwim) toggles comments using the language's own syntax. A single line toggles a line comment; a multi-line selection toggles a block/region comment — `//` and `/* */` for Java and C-likes, `#` for Python/shell/YAML, `<!-- -->` for XML/HTML/Markdown, `--` for SQL, and so on.

It preserves indentation, falls back gracefully for line-only or block-only languages, and is a no-op for languages without comments."""),

("spell-checking", ED, 5, False, "Spell checking",
 'Red wavy underlines with right-click suggestions, Add-to-Dictionary, and Ignore — full text for prose, comments &amp; strings for code. Pure-Java Hunspell; English, Spanish, French.',
"""Misspelled words get a red wavy underline; right-click for **suggestions** (click one to replace), **Add to Dictionary**, or **Ignore**. In source files only comments and string literals are checked (identifiers aren't flagged); plaintext and Markdown are checked in full.

It's powered by Apache Lucene's pure-Java **Hunspell** engine — no native dependency. Ships **English (en_US, en_GB)**, **Spanish**, and **French**; pick a dictionary per file with *Spell Check: Set Language…*, or set a default in Settings. Your added words live in `dictionary.txt`."""),

("syntax-highlighting", CI, 1, False, "Syntax highlighting",
 'TextMate grammars (via tm4e) for 21 languages: Java, Python, Rust, Go, Kotlin, C/C++, C#, Ruby, SQL, Markdown, and more.',
"""Highlighting uses **TextMate grammars** (via tm4e) for 21 languages — Java, XML, shell, PowerShell, DOS batch, Python, Groovy, Kotlin, Ruby, C, C++, Rust, Go, C#, Markdown, JSON, CSS, HTML, YAML, INI, and SQL — plus TypeScript/JavaScript, PHP, Lua, Dockerfile, Terraform, TOML, and more added alongside their language servers.

Tokenization is **stateful** (it carries grammar state across lines, so block comments and heredocs highlight correctly) and **incremental** — an edit re-tokenizes only from the changed line, off the UI thread. Token colors are themed per editor theme."""),

("lsp", CI, 2, True, "Language servers (LSP)",
 'Go-to-definition, find references, hover docs, diagnostics, and completions via 20+ language servers (Java, TypeScript, Python, Go, Rust, C/C++, and more) — auto-detected, never bundled.',
"""Editora speaks the **Language Server Protocol**, so you get real language smarts:

- **Go to definition** — `M-.`
- **Find references** — `M-?`
- **Hover docs** — `C-c h`
- Inline **diagnostics** (with a Problems tool window and minimap/scrollbar marks) and **completions**

Over 20 servers are supported — Java, TypeScript/JavaScript, Python, Go, Rust, C/C++, C#, Ruby, PHP, Kotlin, HTML, CSS, YAML, JSON, Bash, Lua, SQL, Terraform, TOML, and more. Servers are **auto-detected on your PATH, never bundled** (and configurable in Settings → LSP).

Off by default — enable it under Settings → LSP."""),

("autocomplete", CI, 3, False, "Autocomplete",
 'As-you-type completion — a popup for code (LSP + snippets) and inline ghost text for prose. Trigger with <kbd>C-M-i</kbd> / <kbd>M-/</kbd>.',
"""Completion appears as you type, debounced and off the hot path.

**Code** buffers get a popup that merges LSP results and snippets — Enter or Tab to accept. Accepting a snippet starts a full tab-stop session; accepting an LSP item can auto-add its import.

**Prose** buffers get inline **ghost text** — a muted suffix you accept with Tab.

Trigger manually with `C-M-i` or `M-/`. Per-source toggles (words, snippets) live in Settings → Editor."""),

("run-files", RD, 1, False, "Run files",
 'Run a Java compact source file, Python, or shell script from a gutter ▶ — output and <code>stdin</code> in the Run console, with clickable stack traces and per-file arguments.',
"""A green ▶ in the gutter runs the current file — a **Java compact source file** (JEP 512), a **Python** script, or a **shell** script.

Output streams to the Run console, which also accepts **stdin** so `readln`-style programs work, and **stack-trace lines are clickable** (Java, Python, and Node frames) to jump to the file and line. Pass per-file **program arguments** (remembered across runs), and re-run the last file with *Rerun Last Run*. Bind it to `C-c r` or run from the palette."""),

("debugging", RD, 2, True, "Debugging (DAP)",
 'Full debugging for Java, Python, and JavaScript: breakpoints, step in/over/out, watches, set-value, run-to-cursor, inline values, and an interactive console.',
"""Full debugging for **Java**, **Python**, and **JavaScript** through the Debug Adapter Protocol, with an IntelliJ-style Debug tool window:

- Breakpoints (including conditional and logpoints), step in / over / out, **run-to-cursor**, and **jump-to-line**
- A threads + call-stack view and a lazy variables tree with **set-value**
- **Watches** and an evaluate console

While suspended, **inline values** appear after each line and hovering a variable shows its value. The adapters (java-debug, debugpy, vscode-js-debug) are user-installed, not bundled. Off by default — enable it under Settings → Debugging."""),

("http-client", RD, 3, True, "HTTP client",
 'Run <code>.http</code> / <code>.rest</code> requests from a gutter ▶ — environments, variables, and a formatted response view. Built on the JDK HTTP client.',
"""Open a `.http` or `.rest` file and click the green ▶ next to a request to send it — no external tool, it uses the JDK's built-in HTTP client.

Define multiple requests separated by `###`, use `{{variables}}` and dynamic built-ins (`{{$uuid}}`, `{{$timestamp}}`…), and switch **environments** from `http-client.env.json`. The response shows status, headers, timing, and a pretty-printed JSON body in a tool window, with a Save-response action. Run one request or the whole file. Off by default — enable it under Settings → HTTP Client."""),

("git", GD, 1, True, "Git integration",
 'Native Git: status-bar branch, gutter change bars vs HEAD, a Commit tool window, fetch / pull / push + branches — plus a history/log view, inline blame, and stash.',
"""Native Git that shells out to your installed `git` — no bundled library.

- The **status bar** shows the current branch with ahead/behind counts and a dropdown to switch/create branches, pull, fetch, and push.
- **Gutter change bars** mark added/modified/deleted lines vs HEAD (hover for the hunk diff).
- The **Commit** tool window groups staged / changed / untracked files with stage, unstage, discard, and a commit box.
- Plus a **history / log** view, **inline blame**, and **stash**.

Off by default — enable it under Settings → Git."""),

("diff-merge", GD, 2, True, "Diff & merge",
 'Side-by-side and unified diff (vs HEAD, a commit, or another file) with word-level highlights and apply-hunk arrows — plus a merge-conflict resolver.',
"""Compare files in a dedicated tab — **side-by-side** or **unified** — with per-line backgrounds and intra-line **word-level** highlights.

Diff a file against **HEAD** (`C-x v =`), a **commit**, or **another file**, and apply changes hunk-by-hunk with gutter arrows (or apply-all) — all undoable. Open diffs **refresh live** when the underlying files change.

When a file has Git conflict markers, the **merge resolver** lists each conflict with Accept Ours / Theirs / Both and writes the result back."""),

("markdown-preview", DD, 1, False, "Markdown preview",
 'IntelliJ-style 3-mode view (Editor / Split / Preview), rendered natively with CommonMark + GFM. GitHub-style output: task-list checkboxes, code pills, and local/remote images. Live and theme-matched.',
"""An IntelliJ-style 3-mode view — **Editor**, **Editor + Preview** (split), and **Preview** — via a floating control at the top-right of any Markdown file.

It renders **natively** (no WebView) from CommonMark + GFM, GitHub-style: real task-list checkboxes, inline-code pills, underlined headings, tables, and **images** (local and remote, including SVG badges). It updates live as you type, follows the active theme (or its own light/dark toggle), and remembers its mode per file.

Zoom with the −/+ control or Ctrl+wheel; right-click to copy, or export to PDF / print."""),

("mermaid", DD, 2, False, "Mermaid diagrams",
 'Render Mermaid diagrams inline in Markdown and in standalone <code>.mmd</code> files, with live linting and export to SVG / PNG / PDF.',
"""Mermaid diagrams render inline. A fenced ` ```mermaid ` block in Markdown becomes a diagram in the preview, and standalone `.mmd` files get the same 3-mode preview.

Rendering uses the `mmdc` CLI (rasterized faithfully and cached per diagram), with **live linting** via `maid` that underlines errors with precise line/column messages as you type. Export a diagram to **SVG / PNG / PDF**.

Off by default — enable it under Settings → Mermaid (point it at your `mmdc`/`maid`, or use `npx`)."""),

("export-pdf-print", DD, 3, False, "Export to PDF & print",
 'Export code or the Markdown preview to a syntax-highlighted PDF, or print with a preview — light-themed and generated off-thread.',
"""Export **code** to a syntax-highlighted PDF (with optional line numbers), or the **Markdown / Mermaid preview** to a richly-formatted PDF — headings, lists, tables, code blocks, and images rendered as native vector text.

Or **print** either, with a page-by-page preview first (what you preview is what prints). Output is always light-themed and generated off the UI thread, via Apache PDFBox / `javafx.print`. Page size and options live in Settings → Editor → Export & Print."""),

("html-live-preview", DD, 4, True, "HTML live preview",
 'Click the globe on any HTML file to open it in a detected browser (Safari, Chrome, Firefox, Edge…), served over a loopback web server with live-as-you-type reload — sibling CSS, JS, and images included.',
"""A floating browser-globe button on any `.html`/`.htm`/`.xhtml` file opens it in a **detected browser** — Safari, Chrome, Firefox, Edge, or the system default.

The file is served over a tiny embedded web server bound to **loopback only**, so its sibling CSS, JS, and images load, and a small injected script **reloads the page live as you type** (it serves the buffer's in-memory text, so you don't have to save). No external tool — it uses the JDK's built-in HTTP server.

Off by default — enable it under Settings → HTML Preview. Read the [deep-dive](/blog/html-live-preview)."""),

("projects", WF, 1, False, "Projects",
 'VS Code single-folder workspaces: a root folder plus its own saved session (open files, layout, folds), shown as a filterable file tree.',
"""Projects are VS Code-style single-folder workspaces: a root folder plus its **own saved session** — open files (with carets and pins), the active tab, folds, and tool-window layout.

Open one with `C-x C-p`, switch with `C-x p`, and close to return to the global session. The **Project tool window** shows the tree with keyboard navigation and a filter that runs a bounded project-wide filename search. Bookmarks and notes are scoped per project. Off by default — enable it in Settings."""),

("bookmarks-notes", WF, 2, False, "Bookmarks & notes",
 'Line bookmarks (gutter markers, cross-file jump, per-project), plus Personal Notes attached to a word/line/range — stored outside the file, surviving renames, with Markdown bodies.',
"""Two ways to mark up code.

**Bookmarks** toggle on a line (`C-c m`) with a gutter marker and an optional note; the Bookmarks tool window lists them across files, `C-c ]` / `C-c [` cycle within a file, and `M-g b` is a cross-file picker — reorderable and scoped per project.

**Personal Notes** attach an annotation to a word, line, or range, stored *outside* the file (great for read-only or generated code). They survive edits and renames via content-hash identity and text anchoring, render Markdown, and have their own tool window and `M-g n` picker. See the [deep-dive](/blog/personal-notes-that-survive-edits)."""),

("find-in-files", WF, 3, False, "Find in files",
 'Project-wide search and replace with a results panel, plus AceJump to leap the caret to any visible spot by typing a label.',
"""Project-wide search with a results panel (`C-S-f`): matches grouped by file, with case / regex / whole-word options — and it searches open buffers' unsaved text too. **Replace-in-files** rewrites matches across the project (undoable in open buffers).

In-file find (`C-s` / `C-r`) is incremental with highlight-all and a match count. And **AceJump** (`M-g j`) lets you leap the caret to any visible spot by typing the label that appears over it."""),

("file-templates", WF, 4, True, "File templates",
 'New File From Template — single- or multi-file scaffolds with interactive placeholders (author, date, file name, …).',
"""**New File From Template** (`C-c C-n`) scaffolds a file — or a whole set of files — from a reusable template, prompting for any variables (author, date, file name, package…) in a small wizard.

Templates use the same `${var}` / `$0` syntax as snippets; bundled ones cover a Java class, an HTML page/bundle, a Markdown doc, and a Python script. Add your own under `~/.editora/templates/`."""),

("read-only-view-mode", WF, 5, False, "Read-only / View mode",
 'Toggle a buffer read-only to browse without editing; pager-style <kbd>Space</kbd>/<kbd>Backspace</kbd> paging and a Word-style View Mode banner.',
"""Toggle a buffer read-only with `C-x C-q` so it can't be edited by accident — typing and edit commands are blocked while highlighting, folding, search, and copy keep working.

A file that isn't writable on disk opens read-only automatically, and the per-file state is remembered. A Word-style **View Mode banner** docks above the editor with an *Enable Editing* button (when the file is writable), and while read-only, **Space pages down / Backspace pages up** like a pager."""),

("remote-sftp", WF, 6, True, "Remote files (SFTP)",
 'Browse, edit, search, and save files on a remote host over SSH/SFTP — the project tree, search, bookmarks, and notes all work over the wire.',
"""Edit files on a remote host over **SSH/SFTP**. *Remote: Connect to SFTP…* mounts the remote folder as the Project tree, and from there editing, syntax highlighting, search, bookmarks, notes, and preview all work over the wire — Save writes straight back.

Auth supports your default `~/.ssh` keys, a key file, or a password; connections are remembered (without secrets). Off by default; local-only features (running, LSP, Git) are gated off for remote files."""),

("themes-fonts", CE, 1, False, "Themes & fonts",
 'Six editor themes (Primer, Nord, Cupertino, Dracula, Islands — light &amp; dark) that follow the app theme, plus five bundled monospace fonts — no install needed.',
"""Six editor color themes — **Primer, Nord, Cupertino, Dracula**, and JetBrains-style **Islands** (light & dark) — that follow the AtlantaFX app theme by default and are independently selectable. Each themes the syntax tokens, editor surface, gutter, and project tree together.

Five monospace fonts ship with the app — JetBrains Mono, Cascadia Code, Fira Code, IBM Plex Mono, and Source Code Pro — so no system install is needed, plus per-editor **text zoom**."""),

("plugins", CE, 2, False, "Plugins",
 'Extend the editor with commands, tool windows, and integrations — install from a built-in registry of 19 plugins, or write your own (Java or a simple manifest).',
"""Extend Editora without forking it. A plugin adds commands, keybindings, tool windows, editor-menu items, and status-bar segments — written in Java against a small exported API, or declared in a simple `plugin.json` manifest (with snippets and templates folders).

Install from the built-in **registry** of 19 plugins (*Browse plugins…*), or from a `.zip` on disk; downloads are sha-256-verified and the registry index is Ed25519-signed. See the [plugins catalog](/plugins) and the [docs](/docs/plugins).

Off by default — and plugins aren't sandboxed, so only install ones you trust."""),

("simple-ui-mode", CE, 3, False, "Simple UI mode",
 'One toggle strips the editor to the essentials — hiding the extra toolbar groups, tool-window stripe, breadcrumb, gutter, and minimap for a calm, minimal surface.',
"""One toggle strips the editor to the essentials — hiding the extra toolbar groups, the tool-window stripe, the breadcrumb, the **entire gutter** (line numbers, fold chevrons, and all markers), and the minimap — and turns off the heavier features (LSP, debugging, Git, multiple cursors) for a calm, minimal surface.

Toggle it from Settings → Application, the toolbar, the palette, or the `--simple` CLI flag (session-only). Toggling off restores everything exactly."""),

("localized-ui", CE, 4, False, "Localized UI",
 'The whole interface is translated — English, Italian, Spanish, French, Portuguese, and German — selectable in Settings.',
"""Editora's entire interface is translated — **English, Italian, Spanish, French, Portuguese, and German** — covering the command palette, toolbar tooltips, tool windows, Settings, the status bar, dialogs, and menus.

Pick a language in Settings → Appearance (or let it follow your OS locale); the choice applies on restart. A key-parity test keeps every translation complete."""),
]

for slug, group, order, beta, title, summary, body in F:
    fm = (
        "---\n"
        f"title: {json.dumps(title)}\n"
        f"group: {json.dumps(group)}\n"
        f"order: {order}\n"
        f"beta: {'true' if beta else 'false'}\n"
        f"summary: {json.dumps(summary)}\n"
        "---\n\n"
    )
    open(os.path.join(OUT, slug + ".md"), "w").write(fm + body.strip() + "\n")

print(f"Wrote {len(F)} feature pages to {os.path.abspath(OUT)}")
