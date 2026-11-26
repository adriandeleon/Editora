---
title: "Elixir"
description: "ElixirLS for code intelligence and debugging, running Mix projects and ExUnit tests."
category: Languages
order: 5
---

Elixir gets code intelligence and debugging from ElixirLS, the server and
debugger in one, and runs with Mix.

## Language server

Elixir's language server is ElixirLS (see
[language servers](/docs/lsp#servers-are-auto-detected-never-bundled)); it is
installed with the debugger, as described under [Installing
ElixirLS](#installing-elixirls).

## Running

*Run File* (`file.run`, `C-c r` in the Emacs keymap) in a Mix project (the
nearest folder with a `mix.exs`) runs, in the project's folder:

- a test file (`…_test.exs`): `mix test` on it;
- another script (`.exs`): `mix run script.exs`, with the project loaded.
  Mix's own configuration files, such as `mix.exs` and `config/*.exs`, get no
  ▶;
- a module (`.ex`): the project, `mix run` (with `--no-halt` when its
  application starts a supervision tree, which runs until you stop it).

Mix compiles the project and its dependencies, which runs their code, so
Editora asks you to [trust the folder](/docs/workspace#trusted-folders) first.
A file outside a Mix project runs with `elixir file`.

## Tests

**Run Test at Caret** (`test.runAtCaret`) in a test file runs only the test or
`describe` at the caret (`mix test file:line`), and its results fill
[Test Results](/docs/build-tools#test-runner) test by test. Debugging a test is
described under [Debugging](#debugging).

## Debugging

Elixir is debugged with ElixirLS (the debugger of VS Code's ElixirLS
extension), which runs a Mix task with your project's modules under Erlang's
debugger. **Debug: Start / Continue** (`debug.start`: `C-c C-d d` in the Emacs
keymap, F5 in the VS Code, CUA and Sublime Text keymaps) or **Debug File** in a
Mix project (a folder with a `mix.exs`):

- on a test file (`…_test.exs`) runs `mix test` on it. **Debug Test at Caret**
  (`test.debugAtCaret`) runs only the test or `describe` at the caret
  (`mix test file:line`);
- on another script (`.exs`) runs it with the project loaded, `mix run
  script.exs`;
- on a module (`.ex`) runs the project, `mix run` (with `--no-halt` when its
  application starts a supervision tree, which runs until you stop it).

Breakpoints stop in your project's modules and tests, with their variables.
ElixirLS runs Mix tasks only, so a script outside a Mix project isn't debugged:
Editora says so, and *Run File* runs it with `elixir`. Debug asks the same
trust question as Run File.

## Installing ElixirLS

Editora finds ElixirLS as set on the **Elixir (ElixirLS)** card of **Settings →
Debugging** (its `debug_adapter.sh`, or the unpacked release folder), else in
its own install, else in VS Code's ElixirLS extension. The card's **Install…**
downloads ElixirLS 0.31.1, which is also Elixir's
[language server](/docs/lsp). ElixirLS needs Elixir and Erlang on your `PATH`;
its first start compiles it, which takes a minute or two. Debian and Ubuntu
split Erlang into packages: the debugger needs `erlang-debugger`, and the first
start needs `erlang-dev` (`sudo apt install erlang-dev erlang-debugger`).
