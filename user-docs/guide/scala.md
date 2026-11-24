---
title: "Scala"
description: "Metals, running Scala programs and scripts, and debugging Scala."
category: Languages
order: 9
---

Scala gets code intelligence from Metals, runs programs and `.sc` scripts from the gutter, and debugs through Metals.

## Language server

The Scala server is [Metals](https://scalameta.org/metals/) 1.6.9. Install it
from its Settings → LSP card, the banner on a `.scala` file, or **Install:
Language Server…**. It has no command until it is installed: the installer
sets it to `java` with Metals' JVM options and the fetched jars, and Editora
runs that with the JDK chosen for Maven, Gradle and Run, not the first `java`
on your `PATH`.

Metals works on the build it finds at the workspace root: an sbt build
(`build.sbt`), a Mill build (`build.mill`, or a Mill 0.x `build.sc`), a Scala
CLI project (`project.scala`), a build already exported over BSP (`.bsp/`), or a
Gradle or Maven build. The first time it sees one it asks whether to **import
the build**; the question appears as a dialog, and the editor stays usable while
it is open. An import runs the build tool and compiles the project, which takes
a minute or more the first time. Metals shows its progress in the status bar.
In a folder you have not trusted, Metals waits until you trust it (see
[Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build)).

It reports compiler errors, and offers completion, hover, go to definition,
references, rename, the outline and formatting (with a `.scalafmt.conf`). When
Metals asks for a value or a choice of its own, a text field or a picker opens.

A `build.sbt`, `build.mill`, `build.sc` or `project.scala` at a project's root
makes [Doctor](/docs/troubleshooting#doctor) expect Metals.

Any server's question with buttons gets the same dialog.

## Running Scala files

How a Scala file runs depends on the build it belongs to:

- A file of an **sbt** build (a `build.sbt` above it) runs its main class
  through sbt, in the sbt console of the [Output window](/docs/build-tools):
  `sbt "runMain demo.hello"` with your arguments, from the build's root (the
  highest folder with a `build.sbt`).
- A file of a **Mill** build runs through Mill the same way:
  `./mill app.runMain demo.hello`, where `app` is the module whose `src` folder
  holds the file. The project's own `./mill` launcher is used when it has one.
- A file of a **Gradle** or **Maven** build is run from that build's tool
  window; Run says so instead of running the file on its own.
- Any other file, and a `.sc` script, runs with **Scala CLI**:
  `scala-cli run Main.scala -- args`. In a Scala CLI project (a folder with
  `project.scala`), the whole folder runs, with the file's main class. Scala
  CLI reads the `//> using` directives for dependencies and the Scala version.

sbt and Mill compile and run the build's own Scala code (`build.sbt` and
`project/`, `build.mill` or `build.sc`), and Scala CLI given a project folder
runs the macros and compiler plugins of every source there. So the first run in
a folder you have not trusted asks you to
[trust the folder](/docs/workspace#trusted-folders), as the
[build tools](/docs/build-tools) do, and so does Debug; declining runs nothing.
A file Scala CLI runs on its own is run without asking.

Scala CLI runs on the JDK chosen in Settings for builds and runs, which Editora
passes as `JAVA_HOME`. Editora finds it as `scala-cli` on your `PATH`, then
`scala` (the `scala` command is Scala CLI since Scala 3.5), then SDKMAN's
`scalacli` and `scala`. When there is none, Run says Scala CLI is not installed
instead of starting anything. The first run of a Scala version downloads its
compiler, so it takes longer.

## Debugging

A file of an sbt, Mill, Gradle or Maven build is debugged by
[Metals](#language-server), which has imported the build: Debug asks Metals to
compile the file's build target and start its debug adapter, which runs the
program on the build's classpath. Metals needs to be installed, running for the
file, and done importing the build; otherwise Debug says so. The first session
waits for the compile.

A `.sc` script, a file outside any build, and a Scala CLI project are debugged
by a debugger built into Editora, so there is nothing to install: the program
runs with Scala CLI as Run runs it, with the debugger's agent passed as a
`--java-opt`. Breakpoints bind by file name, since a Scala file's package does
not have to match its folder. Stepping skips the standard library
(`scala.runtime`, the collections, `Predef`). A lambda's frame reads
`Main { lambda }`, a top-level definition's `Main.greet`, and an object's
methods `Util.twice`. Locals, `this` and fields are shown without running any
code in the program. Conditional breakpoints and logpoints are not supported
there yet.
