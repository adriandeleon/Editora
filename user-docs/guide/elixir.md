---
title: "Elixir"
description: "Debugging Elixir and Mix tests with ElixirLS."
category: Languages
order: 17
---

Elixir gets code intelligence and debugging from ElixirLS, the server and debugger in one (see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled)).

## Debugging

Elixir is debugged with ElixirLS (the debugger of VS Code's ElixirLS
extension), which runs a Mix task with your project's modules under Erlang's
debugger. **Debug: Start / Continue** (F5) or **Debug File** in a Mix project
(a folder with a `mix.exs`):

- on a test file (`…_test.exs`) runs `mix test` on it. **Debug Test at Caret**
  runs only the test or `describe` at the caret (`mix test file:line`);
- on another script (`.exs`) runs it with the project loaded, `mix run
  script.exs`;
- on a module (`.ex`) runs the project, `mix run` (with `--no-halt` when its
  application starts a supervision tree, which runs until you stop it).

Breakpoints stop in your project's modules and tests, with their variables.
ElixirLS runs Mix tasks only, so a script outside a Mix project isn't debugged:
Editora says so, and *Run File* runs it with `elixir`. Mix compiles the project
and its dependencies, which runs their code, so Editora asks you to trust the
folder first.

*Run File* runs the same commands without the debugger: `mix test` on a test
file, `mix run` on a script, `elixir file` outside a Mix project.

Editora finds ElixirLS as set on the **Elixir (ElixirLS)** card of **Settings →
Debugging** (its `debug_adapter.sh`, or the unpacked release folder), else in
its own install, else in VS Code's ElixirLS extension. The card's **Install…**
downloads ElixirLS 0.31.1, which is also Elixir's
[language server](/docs/lsp). ElixirLS needs Elixir and Erlang on your `PATH`;
its first start compiles it, which takes a minute or two. Debian and Ubuntu
split Erlang into packages: the debugger needs `erlang-debugger`, and the first
start needs `erlang-dev` (`sudo apt install erlang-dev erlang-debugger`).
