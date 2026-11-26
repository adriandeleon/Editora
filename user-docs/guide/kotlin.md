---
title: "Kotlin"
description: "JetBrains' Kotlin language server, running Kotlin files and scripts, Kotlin tests, and debugging Kotlin."
category: Languages
order: 12
---

Kotlin gets code intelligence from JetBrains' Kotlin LSP, runs `.kt` files and `.kts` scripts from the gutter, and debugs on the Java debugger.

## Language server

The Kotlin server is JetBrains' Kotlin LSP. Install it from its Settings → LSP
card, the banner on a `.kt` file, or **Install: Language Server…**; Editora says
how large the download is and waits for your answer. The installer sets the
server's command to the downloaded launcher. Left blank, the command is
`kotlin-lsp --stdio`, the name Homebrew's `kotlin-lsp` gives it. A command you
set yourself is kept, so fwcd's `kotlin-language-server` still works there.

The server imports the project's Gradle or Maven build, so its root is the
folder with `settings.gradle.kts` (or `settings.gradle`), else the nearest
`build.gradle.kts`, `build.gradle` or `pom.xml`: every module of a multi-module
build shares one server. In a folder you have not trusted, the server waits
until you trust it, since the import runs the build (see
[Servers that load a project's build](/docs/lsp#servers-that-load-a-projects-build)).
Once the import ends (a few seconds for a small
project, longer the first time Gradle fetches its plugins) it reports
compiler errors and warnings and offers completion, hover, go to definition,
references, rename, the outline, formatting and code actions.

The server is an alpha. It checks the `.kt` files in the build's source
folders; a `.kts` script, `build.gradle.kts`, and a `.kt` file outside a build
get no diagnostics or hover from it. A folder without a build file is not
imported at all.

## Running Kotlin files

Kotlin runs with the Kotlin compiler, `kotlinc`, on the JDK chosen in Settings
for builds and runs (the one Maven, Gradle and Java use). Editora finds
`kotlinc` through `KOTLIN_HOME`, then your `PATH`, then SDKMAN (`sdk install
kotlin`). When none of them has it, Run says Kotlin is not installed instead of
starting anything. Only `kotlinc` is used: JetBrains' newer Kotlin toolchain
command is also called `kotlin` and is not the compiler.

- A **script** (`.kts`, `.main.kts`) runs with `kotlinc -script`, from the top.
- A **program** (a `.kt` file with a top-level `fun main`) is compiled first,
  together with the Kotlin standard library, into a jar that Editora keeps
  in its config folder, and then runs with `java -jar`. The compile shows in the
  Run window, and Stop ends it. Running the file again without changing it
  skips the compile; after an edit the file is compiled again and the old jar
  is deleted. The file is compiled on its own, so a `.kt` file that uses other
  files of a Gradle project runs through the build tool instead.

A Gradle build script (`build.gradle.kts`, `settings.gradle.kts`) gets no ▶. A
Kotlin program in a [remote folder](/docs/remote) is not compiled from here;
a Kotlin script there runs on its host.

## Tests

In a Maven or Gradle project, a Kotlin test file gets a ▶ in the gutter on each
test and on its class, and **Run Test at Caret** (`test.runAtCaret`) runs the
test the caret is in, or the whole class when the caret is between tests:

- **JUnit** tests: a function marked `@Test` (or `@ParameterizedTest`,
  `@RepeatedTest`, `@TestFactory`, `@TestTemplate`), also with a backtick name
  such as ``fun `adds a book`()``.
- **Kotest** specs: a class that extends `StringSpec`, `FunSpec`,
  `DescribeSpec`, `ShouldSpec`, `BehaviorSpec`, `WordSpec`, `FreeSpec`,
  `FeatureSpec`, `ExpectSpec` or `AnnotationSpec`. A Kotest spec runs as a
  whole: its tests are strings, not functions, so a single one isn't run on
  its own (an `AnnotationSpec`'s `@Test` functions are).

Run Test at Caret and Debug Test at Caret need a Maven or Gradle project, as
the gutter's ▶ icons do. The test runs through the build's test task (Maven's
`-Dtest=`, Gradle's `--tests`), and its results fill
[Test Results](/docs/build-tools#test-runner). **Debug Test at Caret**
(`test.debugAtCaret`) starts the test JVM suspended and attaches the debugger
to it, as for a [Java test](/docs/build-tools#test-runner).

## Debugging

Kotlin is debugged by a debugger built into Editora, so there is nothing to
install beyond the Kotlin compiler. Debug on a Kotlin file runs it as
[Run does](#running-kotlin-files), compiling a program first, and Debug on a Kotlin run
configuration uses that configuration's file, arguments, folder, environment,
JDK and VM arguments.

- **Breakpoints** work in programs, scripts and lambdas, whether or not the
  file sits in a folder named after its package.
- **Frames** are named as Kotlin names them: `Hello.main` for a top-level
  function in `Hello.kt`, and `Hello.main { lambda }` for a lambda inside it.
- **Stepping** goes from line to line of your code and steps over the Kotlin
  standard library and the coroutines machinery.
- **Variables** show the frame's locals, `this`, and the fields of any object or
  the elements of an array. **Hover and Watch** read a variable or a field
  path; they never call a method.
- **Exceptions**: an exception that leaves your code uncaught stops where it is
  thrown, on by default; **Break on Caught Exceptions** also stops on
  exceptions your code catches.

For a Gradle project, open a Kotlin file and use
[Debug via Build Tool](/docs/java#debug-via-the-build-tool): Gradle starts the app under
a suspended JVM and Editora's Kotlin debugger attaches to it, so breakpoints in
`.kt` files bind. **Debug: Attach to Running Program…** (`debug.attach`) from a Kotlin file attaches the same
way to any JVM listening for a debugger.

Not supported yet: conditions, hit counts and log messages on a breakpoint (such
a breakpoint shows hollow and says so), evaluating expressions or method calls,
hot code replace, and debugging on a remote host or in a container.
