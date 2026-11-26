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
- **[Highlighting, completion & spelling](/docs/languages)**: grammars, supported
  languages, and autocomplete.
- **[Language servers (LSP)](/docs/lsp)**: definitions, references, diagnostics,
  and completion.
- **[Navigation & search](/docs/navigation)**: jump pickers, find, and
  project-wide search.
- **[Code navigation](/docs/code-navigation)**: go to symbol, peek definition,
  sticky scroll, and related files.

Running, tools, and version control:

- **[Running programs](/docs/run-debug)**: run files, a project's main class,
  and saved run configurations, locally, on a remote host or in a container.
- **[Debugging](/docs/debugging)**: breakpoints, stepping, values, watches and
  threads in every language with a debug adapter, from Java, Python and
  JavaScript to Go, Rust, C and C++, C#, Ruby, PHP, Dart, Lua, Elixir, Bash
  and PowerShell.
- **Languages**: what is particular to one language (its language server, how
  it runs, how it debugs, its test frameworks) is on its own page, such as
  [Java](/docs/java), [Python](/docs/python), [JavaScript &
  TypeScript](/docs/javascript-typescript), [Go](/docs/go) and
  [Rust](/docs/rust); the rest are under **Languages** in the sidebar.
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
- **[Project Map](/docs/project-map)**: the project as columns of folders,
  files and symbols, with code cards you can open side by side.
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

- **[FAQ](/docs/faq)**, **[Command line](/docs/cli)**,
  **[Troubleshooting](/docs/troubleshooting)**, and **[Doctor](/docs/doctor)**,
  the health report of the tools Editora uses.

## This guide inside Editora

**Help ▸ Documentation** (`help.documentation`) opens this guide in your
browser from the copy that ships inside Editora: the same pages as the website,
with their pictures, search and every command's page, for the version you are
running, and without a network connection. A command's page from the
Command Palette (the help key its footer names, on the selected command), the
*Help for* links in Settings and Pauta Conformance open it too. Links to pages it
doesn't hold, such as the news, go to the website. **Help ▸ Online
Documentation** (`help.onlineDocumentation`) opens the website's copy for your
version instead. A build that carries no copy, such as one built from source
without the website next to it, opens the website for both.

## Reference

- **[Commands](/commands)**: every command, grouped by area.
- **[Keybindings](/keybindings)**: the default Emacs keymap and the four others.

Editora is built in the open. The source, issues, and changelog live on
[GitHub](https://github.com/adriandeleon/Editora).
