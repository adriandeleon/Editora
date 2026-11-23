---
title: Introduction
description: What Editora is and how the documentation is organized.
category: Getting started
order: 1
---

**Editora** is a fast, keyboard-driven, cross-platform programmer's text editor.
Every action is a registered command, reachable through your choice of keymap
(Emacs, CUA, Sublime Text, VS Code, or IntelliJ IDEA) or a fuzzy command
palette. It's free and open source under the MIT License.

It runs on macOS, Windows, and Linux, ships its own Java runtime in the native
installers, and keeps everything local: no accounts, no telemetry, no analytics
calls.

Editora is built with the help of AI coding tools.

## Start here

- **[Getting Started](/docs/getting-started)**: install Editora and learn the
  handful of keys that make it feel fast.
- **[Configuration](/docs/configuration)**: the config folder, `settings.json`,
  and how preferences and session state are stored.
- **[Keymaps & keybindings](/docs/keymaps)**: switch keymaps or rebind anything.

## By area

Editing and code:

- **[Editing](/docs/editing)**: multiple cursors, auto-indent, brackets,
  comments, and the small text operations.
- **[Keyboard macros](/docs/macros)**: record and replay edits.
- **[Undo History](/docs/undo-history)**: jump back to any recent checkpoint,
  and recover unsaved edits after a crash.
- **[EditorConfig](/docs/editorconfig)**: honor a project's `.editorconfig`.
- **[Snippets & templates](/docs/snippets-templates)**: expand boilerplate and
  scaffold new files.
- **[Markdown, Mermaid & preview](/docs/markdown)**: the live preview, the
  format bar, Mermaid, and export to PDF.
- **[HTML](/docs/html)**: tag editing, links, markup checks, the HTML language
  server, and a live preview in your browser.
- **[Typst](/docs/typst)**: a live preview and export for `.typ` files.
- **[Diagrams as code](/docs/diagrams)**: DOT and PlantUML preview and export.
- **[Smart file previews](/docs/previews)**: JSON/YAML/TOML/XML trees, OpenAPI
  docs, and decoded config files.
- **[Print & PDF export](/docs/print-pdf)**: print or export a file, a
  selection, a preview or a table, with a print preview and page settings.
- **[Languages & highlighting](/docs/languages)**: grammars, supported
  languages, and autocomplete.
- **[Language servers (LSP)](/docs/lsp)**: definitions, references, diagnostics,
  and completion.
- **[Navigation & search](/docs/navigation)**: jump pickers, find, and
  project-wide search.
- **[Code navigation](/docs/code-navigation)**: go to symbol, peek definition,
  sticky scroll, and related files.

Running, tools, and version control:

- **[Running & debugging](/docs/run-debug)**: run files and debug Java, Python,
  and JavaScript.
- **[Build tools](/docs/build-tools)**: Maven, npm, Cargo, Go, and Gradle.
- **[Deployments](/docs/deployments)**: keep a project here and run or debug it
  on an SSH host.
- **[HTTP client](/docs/http-client)**: send requests from `.http` files.
- **[Git](/docs/git)**: the built-in Git integration.
- **[GitHub](/docs/github)**: pull requests, reviews, and CI logs through the
  `gh` CLI.
- **[Diff & merge](/docs/diff-merge)**: the diff viewer and merge resolver.

Workspace and customization:

- **[Projects, windows & files](/docs/workspace)**: workspaces, tabs, editor
  groups, focus modes, and Local History.
- **[Bookmarks & notes](/docs/bookmarks-notes)**: mark lines and annotate code.
- **[TODO highlighting](/docs/todo)**: highlight and list TODO/FIXME patterns.
- **[Server log viewer](/docs/log-viewer)**: severity highlighting, follow, and filtering for `.log` files.
- **[CSV & TSV](/docs/csv)**: rainbow columns and an editable grid for data files.
- **[Terminal](/docs/terminal)**: shells in a tool window, in the editor's theme.
- **[Encrypted files](/docs/encrypted-files)**: open and save GnuPG-encrypted
  files as their text, and sign and verify.
- **[Remote files (SFTP)](/docs/remote)**: edit over SSH, and run Git, builds,
  language servers and debuggers on the host.
- **[Themes & fonts](/docs/themes-fonts)**: appearance and zoom.
- **[External tools](/docs/external-tools)**: run your own CLI commands.
- **[Settings sync](/docs/settings-sync)**: the same snippets, abbreviations,
  templates, dictionary, and shortcuts on every computer.
- **[AI assistance](/docs/ai)**: AI actions and an embedded coding agent.
- **[Plugins](/docs/plugins)**: install and write extensions.
- **[MCP server](/docs/mcp)**: let an LLM agent observe and drive the editor.

Help:

- **[FAQ](/docs/faq)**, **[Command line](/docs/cli)**, and
  **[Troubleshooting](/docs/troubleshooting)**.

## Reference

- **[Commands](/commands)**: every command, grouped by area.
- **[Keybindings](/keybindings)**: the default Emacs keymap and the four others.
- **[Roadmap](/roadmap)**: what's shipped and what's planned.

Editora is built in the open. The source, issues, and changelog live on
[GitHub](https://github.com/adriandeleon/Editora).
