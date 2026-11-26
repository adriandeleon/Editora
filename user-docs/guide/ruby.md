---
title: "Ruby"
description: "Running Ruby files, RSpec, Minitest and Rails, and debugging Ruby."
category: Languages
order: 17
---

Ruby gets code intelligence from `ruby-lsp`, runs scripts, tests and web apps
from the gutter, and debugs with rdbg.

## Language server

Ruby's language server is `ruby-lsp`. Editora finds it on the PATH or installs
it for you; see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled).

## Running

<a id="running-ruby-files"></a>*Run File* (`file.run`: `C-c r` in the Emacs keymap, Ctrl+Shift+B (⌘⇧B on macOS) in VS Code) on a Ruby file runs what it is, in the project's folder (the nearest
with a `Gemfile`) and under `bundle exec` once the bundle is installed:

- an **RSpec** file (`…_spec.rb`) runs `rspec` on it;
- a **Minitest** file (`…_test.rb`, `test_….rb`) runs `bin/rails test` on it
  in a Rails app, else `ruby -Itest` on it;
- a **Rails** app's `config.ru` or `bin/rails` starts the app's development
  server, `bin/rails server`, on the first free port from 3000;
- any other file with code of its own runs as `ruby file`. A file of classes,
  modules and methods alone gets no ▶.

**Debug File** does the same under the debugger (see
[Debugging Ruby](#debugging)). Pressed in any file under a Rails app's
`app/` or `config/`, such as a controller, Debug serves the app.

## Tests

**Run Test at Caret** (`test.runAtCaret`) in an RSpec or Minitest file runs
only the test at the caret: for RSpec the example or group on that line
(`rspec file:line`), in a Rails app the test on that line
(`bin/rails test file:line`), and for plain Minitest the `def test_…` or
`test "…"` the caret is in (`-n test_name`). Run File runs the whole file. The
results fill [Test Results](/docs/build-tools#test-runner).

## Debugging

Ruby is debugged with rdbg, the debugger of Ruby's `debug` gem, which Ruby 3.1
and later ship. **Debug: Start / Continue** (`debug.start`: `C-c C-d d` in
the Emacs keymap, F5 in the VS Code, CUA and Sublime Text keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap) or **Debug
File** runs the file under rdbg as [Run File](#running) runs it, and stops at
your breakpoints:

- a **script** runs with `ruby` and the arguments set by **File: Run with
  Arguments…**;
- an **RSpec** or **Minitest** file runs its tests. **Debug Test at Caret**
  runs only the test at the caret: for RSpec the example or group on that line
  (`rspec file:line`), in a Rails app the test on that line
  (`bin/rails test file:line`), and for plain Minitest the `def test_…` or
  `test "…"` the caret is in (`-n test_name`);
- a **Rails** app: Debug on a controller, model or other file under `app/` or
  `config/` starts `bin/rails server`, and each request you send it stops at
  your breakpoints. Stop ends the server.

rdbg debugs a program run under `bundle exec` even when the `debug` gem isn't
in the bundle. The program's output
reaches the Debug console. Ruby's
[exception breakpoints](/docs/debugging#exception-breakpoints), **Ruby: any raised exception**
and **Ruby: RuntimeError**, are off by default, as in VS Code.

## Installing rdbg

Editora finds `rdbg` as set on the **Ruby (rdbg)** card of **Settings →
Debugging**, else on the PATH, else in your own gem folders (such as
`~/.local/share/gem/ruby/*/bin`). The card's **Install…** runs
`gem install --user-install debug`, which puts it in your gem folder, since a
system Ruby's own gem folder belongs to root.

A [launch.json](/docs/debugging#launchjson-configurations) `rdbg` configuration can also run a
script under `bundle exec`, or attach to a program started with `rdbg --open`.
