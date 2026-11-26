---
title: "Lua"
description: "Lua code intelligence with lua-language-server, and debugging with Local Lua Debugger."
category: Languages
order: 13
---

Lua gets code intelligence from **lua-language-server** and debugging from
**Local Lua Debugger**, the debugger of VS Code's Local Lua Debugger extension.

## Code intelligence

`lua-language-server` is Lua's [language server](/docs/lsp): diagnostics,
completion, hover, go to definition, references, rename and document symbols.
Editora finds it on your `PATH`, or at the command set on the Lua card of
**Settings → LSP**; the card's **Install…** downloads its release
for your system into your config folder
(see [One-click install](/docs/lsp#one-click-install)).

## Debugging

**Debug: Start / Continue** (`debug.start`: `C-c C-d d` in the Emacs keymap,
F5 in the VS Code, CUA and Sublime Text keymaps) on a `.lua`
script debugs it, in its folder, with the arguments set by **File: Run with Arguments…**:

- **Breakpoints**, with conditions.
- **Stepping** and the **call stack**.
- **Variables**: the frame's locals, upvalues and globals. **Set Value**
  changes a variable.
- **Watches**, hovers and the Debug console evaluate an expression in the
  stopped frame.
- An error that is raised **stops** the program where it was raised.
- **The script's output** appears in the Debug console.

The script runs on the `lua` on your `PATH`; without one, Editora takes the
first of `lua5.4`, `lua5.3`, `luajit`, `lua5.2` and `lua5.1` it finds.

A **launch.json** configuration of type `lua-local` with `request: launch`
works too, as it does in VS Code: its `program` names the `lua` interpreter and
the `file` to run, with its `args` and `cwd`. Local Lua Debugger only launches
scripts, so a `lua-local` attach is not supported.

## Installing Local Lua Debugger

Editora finds Local Lua Debugger's `debugAdapter.js`:

- at the path set on the **Lua (local-lua-debugger)** card of
  **Settings → Debugging** (the file or its folder);
- else in Editora's own install, which the card's **Install…** downloads
  (Local Lua Debugger 0.3.3, checked against its SHA-256);
- else in VS Code's Local Lua Debugger extension
  (`tomblind.local-lua-debugger-vscode`).

The debugger runs on **Node.js**, which must be on your `PATH`. The card's
**Enable Lua debugging** switch turns Lua debugging off and on, and
[Doctor](/docs/troubleshooting#doctor) has a row for it.
