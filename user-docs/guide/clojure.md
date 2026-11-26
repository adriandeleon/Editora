---
title: "Clojure"
description: "clojure-lsp, running Clojure files with the Clojure CLI, Leiningen or Babashka, and debugging Clojure."
category: Languages
order: 3
---

Clojure gets code intelligence from `clojure-lsp`, runs files with the Clojure CLI, Leiningen or Babashka, and debugs on the Java debugger.

## Language server

The Clojure server is clojure-lsp. Editora uses the `clojure-lsp` on your `PATH`,
or installs its own copy from the server's Settings → LSP card, the banner on a
Clojure file, or **Install: Language Server…**. The install is the native build
for your platform (Linux and macOS on x64 and arm64, Windows on x64), pinned to
one release and checked against its SHA-256.

It serves `.clj`, `.cljs`, `.cljc` and `.edn` files with clj-kondo's diagnostics,
completion, hover, go to definition, references, rename, code actions, the
outline and formatting. To resolve the project's dependencies it asks the
project's own tool for the classpath (`clojure -Spath`, `lein classpath`,
`bb print-deps` or `npx shadow-cljs classpath`), which it finds on the same
`PATH` Editora gives every language server. Without that tool it still works on
the project's own files. In a folder you have not trusted, a `project.clj`,
`build.boot` or `shadow-cljs.edn` makes the server wait until you trust it
(see [Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build)).

A server runs per project root: the folder with a `deps.edn`, `project.clj`,
`bb.edn`, `shadow-cljs.edn` or `build.boot`, else the repository. Any of those
files at a project's root also makes [Doctor](/docs/doctor)
expect the Clojure server.

## Running

<a id="running-clojure-files"></a>A Clojure file runs with one of three tools, picked from the file and its
project, the nearest folder above it with a `deps.edn`, `project.clj` or
`bb.edn`:

| The file | Runs as |
| --- | --- |
| a namespace with `-main` in a `deps.edn` project | `clojure -M -m my-app.core`, in the project folder |
| a namespace with `-main` in a `project.clj` project | `lein run -m my-app.core`, in the project folder |
| a namespace with `-main` in no project | `clojure -M -m hello`, with the file's folder on the classpath |
| any other `.clj` or `.cljc` file | a script: `clojure -M <file>` (or `lein run -m clojure.main <file>`) |
| a `.bb` file, a `#!/usr/bin/env bb` script, or any file of a project with only a `bb.edn` | `bb <file>` |

A namespace runs through `-m` only when its name matches its path, such as
`my-app.core` in `src/my_app/core.clj`, so the tool can find it on the
classpath. A namespace outside `src` is added to it for the run. A project with
both a `deps.edn` and a `project.clj` runs with the Clojure CLI.

A run in a project folder loads the project's own file, which can run code of
its own: Leiningen evaluates `project.clj` and its plugins, a `deps.edn` alias
can add JVM options such as a `-javaagent`, and Babashka reads `bb.edn`. So
Run and Debug on a file of such a project first ask you to
[trust the folder](/docs/workspace#trusted-folders), as the tool's
[tasks window](/docs/build-tools#clojure) does; declining runs nothing, and
Rerun asks again if you revoked the trust since. A file in no project is run
without asking.

The Clojure CLI and Leiningen run on the JDK chosen in Settings for builds and
runs. Since Clojure 1.10.1 the CLI writes an uncaught exception's stack trace
to a temporary file and prints only a summary; Run sets
`clojure.main.report=stderr`, so the whole report is in the Run console and its
frames are links.

Editora finds `clojure`, `lein` and `bb` on your `PATH`. When the one a file
needs is missing, Run says so instead of starting anything. On Windows the
Clojure CLI is best used under WSL; see
[Running in a container or WSL](/docs/run-debug#running-in-a-container-or-wsl).

## Tests

Clojure tests run from the [Clojure CLI, Leiningen or Babashka
window](/docs/build-tools#clojure), such as the `-M:test` alias or `lein test`.
Their output stays in the Output console, since the
[Test Results](/docs/build-tools#test-runner) window does not read Clojure test
runners' output, and a Clojure test file has no ▶ per test.

## Debugging

Clojure is debugged by the same built-in debugger as Groovy, with nothing to
install. Debug on a Clojure file runs it as [Run does](#running), under
the Clojure CLI or Leiningen, and Debug on a Clojure script run configuration
uses that configuration's file, arguments, folder, environment, JDK and VM
arguments.

- **Breakpoints** work in a namespace's functions and in a script's top-level
  forms. Clojure compiles `src/my_app/core.clj` to classes that name
  `core.clj` as their source, which is how a breakpoint finds them.
- **Stepping** goes from line to line of your code and steps over Clojure's
  runtime and `clojure.core`.
- **Frames** are named as Clojure names the function, such as
  `my-app.core/greet` (not the class `my_app.core$greet`).
- **Variables** show a frame's locals, kept alive for the whole function during
  a session (`clojure.compiler.disable-locals-clearing`), so a local still reads
  after its last use. A value shows as its class, such as `PersistentVector`;
  expand it for its fields. Nothing in the program is called to print it.
- **Exceptions**: an exception that leaves your code uncaught stops where it was
  thrown, on by default as for Groovy.

Babashka scripts can't be debugged: `bb` is a native program, with no JVM for
the debugger to attach to. Debug says so and runs nothing; run the script, or
debug the same code under the Clojure CLI.

The debugger sees the classes your files were compiled to when the program
loaded them. A function you redefine from a REPL is compiled again to a new
class, so a breakpoint set before the redefinition does not stop in it, and
code evaluated in a REPL has no file to stop in. Breakpoint conditions and log
messages, evaluating expressions, and debugging on a remote host are not
supported yet, as for Groovy.
