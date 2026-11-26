---
title: "Rust"
description: "rust-analyzer, running Rust programs and tests with Cargo, and debugging Rust with CodeLLDB: Rust values and panics."
category: Languages
order: 18
---

Rust gets code intelligence from `rust-analyzer`, runs and tests with Cargo,
and debugs with CodeLLDB.

## Language server

Rust's language server is `rust-analyzer`. Editora finds it on the PATH or
installs it for you; see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled).

## Running

**Run File** (`file.run`: `C-c r` in the Emacs keymap, Ctrl+Shift+B (⌘⇧B on macOS) in VS Code) runs a Rust file that
has a `fn main`, from its Cargo package, the nearest `Cargo.toml` above it. A
file outside a package doesn't run; the status line says so. Editora saves the
package's edited Rust files, runs `cargo build` (`cargo build --example name`
for a file in `examples/`) in the package's folder, then runs the program built
from that file: a `src/bin/` program or an example by its own file, otherwise
the package's main program. The build and the program's output stream into
the **Run** window.

A build runs the `build.rs` scripts and procedural macros of the package and
its dependencies, so the first one in a folder asks you to
[trust the folder](/docs/workspace#trusted-folders); declining builds nothing.
**File: Run with Arguments…** (`file.runWithArgs`) asks for the program's
arguments; they are kept for that file and used by every later Run File.

## Tests

**Run Test at Caret** (`test.runAtCaret`) in a `#[test]` function (or
`#[tokio::test]` and the like) builds the test binary (`cargo test --no-run`,
with `--test name` for an integration test in `tests/`) and runs that test
alone, by its path, exactly. Its output goes to the Run window.

**Run Tests** (`test.run`) in a Cargo project runs `cargo test` and fills the
[Test Results](/docs/build-tools#test-runner) window, where failed tests can be
run again. Debugging a test is described under [Debugging](#debugging).

## Debugging

Rust is debugged with [CodeLLDB](https://github.com/vadimcn/codelldb), LLDB
with Rust's formatters, as VS Code debugs it with rust-analyzer. A Rust file is
debugged from its Cargo package, the nearest `Cargo.toml` above it; outside a
package, Debug says so. **Debug: Start / Continue** (`debug.start`:
`C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and Sublime Text
keymaps, Shift+F9 (⌃D on macOS) in the IntelliJ IDEA keymap), or **Debug File** in the editor's right-click menu, debugs, depending
on where the caret is:

- in a `#[test]` function (or `#[tokio::test]` and the like): that test alone,
  in its test binary. **Debug Test at Caret** (`test.debugAtCaret`) does the
  same.
- in an integration test file (`tests/*.rs`), outside a test: all its tests.
- in a file of `examples/`: that example.
- anywhere else: the package's program. In a library with no program, the
  file's tests.

Cargo builds first, in the Debug console, after Editora saves the package's
edited Rust files. A build runs the `build.rs` scripts and procedural macros of
the package and its dependencies, so the first one in a folder asks you to
[trust the folder](/docs/workspace#trusted-folders), as a Cargo task does;
declining builds nothing. Stop ends the build, and a build that fails launches
nothing. CodeLLDB then debugs the program or test binary Cargo built, with the
arguments set by **File: Run with Arguments…**. **Restart** builds and runs
the same program or test again.

- **Values** show as Rust values: a `String` or a `&str` reads as its text, and
  a `Vec` or a `HashMap` unfolds to its items.
- **A panic** stops the program where it happened: the **Rust: on panic**
  [exception breakpoint](/docs/debugging#exception-breakpoints) is on by default.
- **Breakpoints** take conditions, hit counts and log messages, and
  **stepping**, **Watches** and the [Threads view](/docs/debugging#threads-view) work.
- With [program input](/docs/debugging#program-input-in-the-debug-console) on, Editora starts
  the program for CodeLLDB, so the program reads lines typed in the Debug
  console.
- The [Disassembly and Memory views](/docs/debugging#disassembly-and-memory) show the
  program's machine code and memory, and step by instruction.

![shelf.rs paused on line 49, total += subtotal, with inline values such as item: {sku:"A-2", name:"Notebook", quantity:3, …} on the lines above, and the Debug window below: the call stack at shelf::shelf::checkout, and Variables with lines, a Vec<String> of size 2, unfolded to "A-1  Pencil …" and "A-2  Notebook …", then total, item and discount = None](/screenshots/docs/debug-rust-values.png "A Rust program at a breakpoint: a Vec unfolds to its items, and each String reads as its text.")

[Attach to Process](/docs/debugging#attach-to-process) attaches CodeLLDB to a Rust program
that is already running, on Linux, when it was built with debug information.
Stop leaves that program running.
[Attach to Remote Program](/docs/debugging#attach-to-a-remote-program) debugs a
Rust program under `gdbserver` on another machine or in a container.
[Open Core Dump](/docs/debugging#opening-a-core-dump)
opens the core a crashed Rust program left in CodeLLDB.

## Installing CodeLLDB

CodeLLDB isn't bundled. Editora finds it as set on the **Rust, C and C++
(CodeLLDB)** card of **Settings → Debugging** (the `codelldb` program, or the
folder of an unpacked CodeLLDB extension), else in its own install, else in
VS Code's CodeLLDB extension (`vadimcn.vscode-lldb`), else on the PATH. The
card's **Install…** downloads CodeLLDB 1.12.3, the release tested with Editora,
for Linux, macOS or Windows, checks its SHA-256 checksum, and unpacks it into
your config folder. Cargo and Rust come from rustup; Editora doesn't install
them. The card's switch, **Enable Rust debugging (CodeLLDB)**, is on by
default, and the [Doctor](/docs/doctor) checks for CodeLLDB.
