---
title: "Rust"
description: "Debugging Rust with CodeLLDB: Cargo builds, Rust values and panics."
category: Languages
order: 12
---

Rust gets code intelligence from `rust-analyzer` (see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled)) and debugs with CodeLLDB.

## Debugging

Rust is debugged with [CodeLLDB](https://github.com/vadimcn/codelldb), LLDB
with Rust's formatters, as VS Code debugs it with rust-analyzer. A Rust file is
debugged from its Cargo package, the nearest `Cargo.toml` above it; outside a
package, Debug says so. **Debug: Start / Continue** (`debug.start`, F5), or
**Debug File** in the editor's right-click menu, debugs, depending on where the
caret is:

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

![shelf.rs paused on line 49, total += subtotal, with inline values such as item: {sku:"A-2", name:"Notebook", quantity:3, …} on the lines above, and the Debug window below: the call stack at shelf::shelf::checkout, and Variables with lines, a Vec<String> of size 2, unfolded to "A-1  Pencil …" and "A-2  Notebook …", then total, item and discount = None](/screenshots/docs/debug-rust-values.png "A Rust program at a breakpoint: a Vec unfolds to its items, and each String reads as its text.")
- **Breakpoints** take conditions, hit counts and log messages, and
  **stepping**, **Watches** and the [Threads view](/docs/debugging#threads-view) work.
- With [program input](/docs/debugging#program-input-in-the-debug-console) on, Editora starts
  the program for CodeLLDB, so the program reads lines typed in the Debug
  console.
- The [Disassembly and Memory views](/docs/debugging#disassembly-and-memory) show the
  program's machine code and memory, and step by instruction.

[Attach to Process](/docs/debugging#attach-to-process) attaches CodeLLDB to a Rust program
that is already running, on Linux, when it was built with debug information.
Stop leaves that program running. [Open Core Dump](/docs/debugging#opening-a-core-dump)
opens the core a crashed Rust program left in CodeLLDB.

CodeLLDB isn't bundled. Editora finds it as set on the **Rust, C and C++
(CodeLLDB)** card of **Settings → Debugging** (the `codelldb` program, or the
folder of an unpacked CodeLLDB extension), else in its own install, else in
VS Code's CodeLLDB extension (`vadimcn.vscode-lldb`), else on the PATH. The
card's **Install…** downloads CodeLLDB 1.12.3, the release tested with Editora,
for Linux, macOS or Windows, checks its SHA-256 checksum, and unpacks it into
your config folder. Cargo and Rust come from rustup; Editora doesn't install
them. The card's switch, **Enable Rust debugging (CodeLLDB)**, is on by
default, and the [Doctor](/docs/troubleshooting#doctor) checks for CodeLLDB.
