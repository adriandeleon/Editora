---
title: "Go"
description: "gopls, running Go programs and tests, and debugging Go with Delve: goroutines, tests and core dumps."
category: Languages
order: 6
---

Go gets code intelligence from `gopls`, runs and tests with the `go` command,
and debugs with Delve.

## Language server

Go's language server is `gopls`. Editora finds it on the PATH or installs it
for you; see [language servers](/docs/lsp#servers-are-auto-detected-never-bundled).

## Running

**Run File** (`file.run`, `C-c r` in the Emacs keymap) runs a Go file that has
`package main` and a `func main()`. Editora saves the file, and the module's
other edited Go files, then builds the program with `go build` into its cache
folder and runs it in the file's folder:

- A file of a module (a `go.mod` in its folder or above) builds its
  **package**, as `go run .` does.
- A file outside any module is built alone, as `go run file.go` does.

The build command and the program's output stream into the **Run** window.
**File: Run with Arguments…** (`file.runWithArgs`) asks for the program's
arguments; they are kept for that file and used by every later Run File.
**Rerun Last** builds and runs it again. Go builds don't ask you to
[trust the folder](/docs/workspace#trusted-folders). A file on a
[remote host](/docs/remote) doesn't run.

## Tests

In a `_test.go` file, **Run Test at Caret** (`test.runAtCaret`) builds the
package's test binary (`go test -c`) and runs the test function the caret is
in (`-test.run ^Name$ -test.v`), or every test of the package when the caret
is outside a test function. Its output goes to the Run window. Run File on a
`_test.go` file runs the package's tests.

**Run Tests** (`test.run`) in a Go module runs `go test -json ./...` and fills
the [Test Results](/docs/build-tools#test-runner) window, where failed tests
can be run again. Debugging a test is described under [Debugging](#debugging).

## Debugging

Go is debugged with [Delve](https://github.com/go-delve/delve), the debugger
VS Code's Go extension uses. Debug a Go file with **Debug: Start / Continue**
(`debug.start`: `C-c C-d d` in the Emacs keymap, F5 in the VS Code, CUA and
Sublime Text keymaps), or with
**Debug File** in the editor's right-click menu:

- A file of a module (a `go.mod` in its folder or above) debugs its
  **package**, as `go run .` runs it.
- A file outside any module is debugged alone, as `go run file.go` runs it.
- In a `_test.go` file, Debug and **Debug Test at Caret**
  (`test.debugAtCaret`) run the test function the caret is in on its own
  (a `Benchmark…` function runs alone as a benchmark), and every test of the
  package when the caret is outside a test function.

**Restart** runs the same program or test again, wherever the caret is by
then. The arguments set by **File: Run with Arguments…** (`file.runWithArgs`)
are passed to the program, or to the test binary.

Delve builds the program itself, with optimizations off, using the `go` that the
[Go build tool](/docs/build-tools) runs. It builds from the files on disk, so
Debug and Restart save the package's edited Go files first. Its build goes to
the temporary folder, so no `__debug_bin` file is left in your package. The
program's output reaches the Debug console.

- **Breakpoints** take conditions, hit counts and log messages. A line that
  holds only a comment takes none.
- **Stepping**, **Variables**, **Watches**, hover values and inline values
  work. A struct, slice or map unfolds to its fields or items.
- **Goroutines are threads.** The call stack's thread list and the
  [Threads view](/docs/debugging#threads-view) name each one, such as
  `[Go 6] main.restock.func1`. Delve lists at most 1024 goroutines. See
  [Goroutines](#goroutines) for which are listed.
- **A panic** stops the program where it happened. Delve does that by itself,
  so Go has no [exception breakpoints](/docs/debugging#exception-breakpoints) to choose.

![restock.go paused on line 16, restocked++, inside a goroutine that restock starts, with inline values on the lines above, and the Debug window below: the Threads view listing the program's goroutines, such as [Go 1] sync.runtime_SemacquireWaitGroup, runtime.gopark ones and the suspended main.restock.func1, Variables with w, mu, restocked and wg, and the program's output in the console](/screenshots/docs/debug-go-goroutines.png "A Go program stopped in a goroutine: each goroutine is a thread in the Threads view.")

[Attach to Process](/docs/debugging#attach-to-process) attaches Delve to a Go program that is
already running, on Linux and macOS. Stop leaves that program running.
[Open Core Dump](/docs/debugging#opening-a-core-dump) opens the core a crashed Go program
left in Delve.

### Goroutines

The Threads view lists your program's goroutines. The runtime's own, such as
the garbage collector's workers, the finalizer and the scavenger, are left out.
A goroutine started under pprof labels (`pprof.Do`) shows them in its name:
`[Go 20 job:resize-0] main.worker`.

Right-click the Threads view for:

- **Hide Runtime Goroutines** (`debug.toggleRuntimeGoroutines`): untick it to
  list the runtime's goroutines again.
- **Filter Goroutines…** (`debug.filterGoroutines`): list only the goroutines
  that Delve's own filter keeps, such as `-with label job=*`,
  `-without go runtime.*` or `-with user`. Empty lists every goroutine.

Both apply to the running program at once and are kept. A filter can leave out
the goroutine the program stopped on.

**Go goroutines** in **Settings → Debugging** holds the same choices:
**Hide runtime goroutines** (on), **pprof labels in names** (`*` for every
label, or the keys to show, such as `job, region`; empty shows none, from the
next session on) and **Goroutine filter**.

## Installing Delve

Delve isn't bundled. Editora finds `dlv` as set on the **Go (Delve)** card of
**Settings → Debugging**, else on the PATH, else where `go install` puts it
(`$GOBIN`, `$GOPATH/bin` or `~/go/bin`), so a Delve installed while Editora runs
is found too. The card's **Install…** runs
`go install github.com/go-delve/delve/cmd/dlv@latest`, which needs Go. Without
Delve, Debug says *Delve (dlv) was not found* and how to get it. The card's
switch, **Enable Go debugging (Delve)**, is on by default, and the
[Doctor](/docs/troubleshooting#doctor) checks for Delve.
