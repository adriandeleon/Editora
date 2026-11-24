---
title: "C & C++"
description: "Debugging C and C++ with CodeLLDB or GDB."
category: Languages
order: 13
---

C and C++ get code intelligence from `clangd` (see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled)) and debug with CodeLLDB or GDB.

## Debugging

C and C++ are debugged with the same [CodeLLDB](/docs/rust#debugging) as Rust, or
with GDB if you choose it. **Debug: Start / Continue** (`debug.start`, F5) or
**Debug File** in the editor's right-click menu builds the program first, in
the Debug console, after Editora saves the project's edited C and C++ files:

- **In a CMake project** (a `CMakeLists.txt` in the file's folder or above),
  it configures a Debug build in `build/editora-debug` and builds it, then
  debugs the program whose sources hold the file, else the project's only
  program.
- **In a Make project** (the nearest makefile above the file), it runs `make`
  in that folder, then debugs the program it built: the one named after the
  file, in the folder or in its `bin/` or `build/`, else the only one, else the
  newest.
- **A file outside a project** is compiled with `cc`, or `c++` for C++, with
  `-g -O0`, into Editora's cache folder. A header outside a project says to
  open its source file instead.

CMake and Make run the project's own code, so the first build asks you to
[trust the folder](/docs/workspace#trusted-folders), as Maven and Cargo do. A
lone file compiled with `cc` asks nothing. A build that fails launches nothing.
**Restart** builds again, and the arguments set by **File: Run with
Arguments…** are passed to the program.

- **Values:** `std::string` reads as its text, and `std::vector` and
  `std::map` unfold to their items.
- **A thrown C++ exception** stops the program where it was thrown: the
  **C++: on throw** [exception breakpoint](/docs/debugging#exception-breakpoints) is on by
  default.
- **Breakpoints**, **stepping**, **Watches**, the
  [Threads view](/docs/debugging#threads-view) and the
  [Disassembly and Memory views](/docs/debugging#disassembly-and-memory) work.

[Attach to Process](/docs/debugging#attach-to-process) lists running C and C++ programs built
with debug information, on Linux, and attaches CodeLLDB to the one you pick.
[Open Core Dump](/docs/debugging#opening-a-core-dump) opens a crashed program's core in
CodeLLDB. With GDB, **Record Execution** lets you
[step backward](/docs/debugging#stepping-backward) through what the program just did.

The **Rust, C and C++ (CodeLLDB)** card of **Settings → Debugging** has the
switch **Enable C and C++ debugging (CodeLLDB)**, on by default, and **C and
C++ debugger**, which picks **CodeLLDB** or **GDB**. GDB debugs through its own
Debug Adapter Protocol mode (`gdb -i dap`), which needs GDB 14 or later. Editora
finds `gdb` as set in **GDB path** on the same card, else on the PATH; without
it, Debug says GDB was not found. GDB isn't installed by Editora.

![The Language picker of Settings → Debugging set to Rust, C and C++ (CodeLLDB), and under it that card: Enable Rust debugging (CodeLLDB) on, Enable C and C++ debugging (CodeLLDB) on, C and C++ debugger set to CodeLLDB with the hint that GDB debugs through its own DAP mode (GDB 14 or later), an empty GDB path, an empty CodeLLDB (codelldb) path with Browse…, and Detected codelldb: found](/screenshots/docs/debug-settings-cpp.png "The Rust, C and C++ card: C and C++ can switch from CodeLLDB to GDB.")
