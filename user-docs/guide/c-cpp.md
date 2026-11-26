---
title: "C & C++"
description: "clangd, running C and C++ programs and GoogleTest, Catch2 and doctest tests, and debugging with CodeLLDB or GDB."
category: Languages
order: 1
---

C and C++ get code intelligence from `clangd`, run their programs and tests
from CMake, Make or a lone file, and debug with CodeLLDB or GDB.

## Language server

The C and C++ language server is `clangd`. Editora finds it on the PATH or
installs it for you; see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled).

## Running

**Run File** (`file.run`, `C-c r` in the Emacs keymap) on a C or C++ file with
a `main` builds the program as [Debug File](#debugging) builds it (CMake, Make,
or the file alone), with the same trust question, then runs it in the **Run**
window with the arguments set by **File: Run with Arguments…**
(`file.runWithArgs`). A file of tests runs its tests (see [Tests](#tests)).

## Tests

A C or C++ file that includes GoogleTest (`gtest/gtest.h`, `gmock/gmock.h`),
Catch2 (`catch2/catch_…`, or Catch2 2's `catch.hpp`) or doctest
(`doctest/doctest.h`, `doctest.h`) is a test file. Each test has a ▶ in the
gutter, and the first line has one for the whole file:

- **GoogleTest:** `TEST`, `TEST_F`, `TEST_P` (every value it is instantiated
  with), `TYPED_TEST` and `TYPED_TEST_P`.
- **Catch2:** `TEST_CASE`, `TEST_CASE_METHOD`, `SCENARIO`, `SCENARIO_METHOD`
  and the template test cases (every type).
- **doctest:** `TEST_CASE`, `TEST_CASE_FIXTURE`, `SCENARIO` and
  `TEST_CASE_TEMPLATE`.

A test made by a macro of your project's own isn't seen.

Its program is built as [Debug File](#debugging) builds it (CMake, Make, or
the file alone), then run with the test's own filter: `--gtest_filter` for
GoogleTest, the test's name as a Catch2 test spec, `--test-case` for doctest.

- **Run File** and the first line's ▶ run every test of the program.
- **Run Test at Caret** (`test.runAtCaret`) and a test's ▶ run that test; with
  the caret outside every test, every test runs.
- **Debug Test at Caret** (`test.debugAtCaret`), and **Debug Test** in a ▶'s
  menu, debug the program on CodeLLDB or GDB, run with the test's filter.

The results fill [Test Results](/docs/build-tools#test-runner): each test
passed, failed or skipped, a failed check with its values and its section, and
a row that opens the line where it failed. **Rerun**, **Rerun Failed** and
**Rerun This Test** build the program again first. The test frameworks aren't
installed by Editora; CMake's `find_package` (or your Makefile) finds them.
CTest isn't run: the test program runs directly.

## Debugging

C and C++ are debugged with the same [CodeLLDB](/docs/rust#debugging) as Rust, or
with GDB if you choose it. **Debug: Start / Continue** (`debug.start`:
`C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and Sublime Text
keymaps) or **Debug File** in the editor's right-click menu builds the program
first, in the Debug console, after Editora saves the project's edited C and C++
files:

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
CodeLLDB. [Attach to Remote Program](/docs/debugging#attach-to-a-remote-program)
debugs a program under `gdbserver` on another machine, in a container or on a
board. With GDB, **Record Execution** lets you
[step backward](/docs/debugging#stepping-backward) through what the program just did.

## Installing the debugger

CodeLLDB is installed from the **Rust, C and C++ (CodeLLDB)** card of
**Settings → Debugging**, as described on the
[Rust page](/docs/rust#installing-codelldb). The same card has the switch
**Enable C and C++ debugging (CodeLLDB)**, on by default, and **C and C++
debugger**, which picks **CodeLLDB** or **GDB**. GDB debugs through its own
Debug Adapter Protocol mode (`gdb -i dap`), which needs GDB 14 or later. Editora
finds `gdb` as set in **GDB path** on the same card, else on the PATH; without
it, Debug says GDB was not found. GDB isn't installed by Editora.

![The Language picker of Settings → Debugging set to Rust, C and C++ (CodeLLDB), and under it that card: Enable Rust debugging (CodeLLDB) on, Enable C and C++ debugging (CodeLLDB) on, C and C++ debugger set to CodeLLDB with the hint that GDB debugs through its own DAP mode (GDB 14 or later), an empty GDB path, an empty CodeLLDB (codelldb) path with Browse…, and Detected codelldb: found](/screenshots/docs/debug-settings-cpp.png "The Rust, C and C++ card: C and C++ can switch from CodeLLDB to GDB.")

