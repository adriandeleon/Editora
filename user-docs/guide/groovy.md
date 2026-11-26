---
title: "Groovy"
description: "The Groovy language server, running Groovy scripts, Groovy and Spock tests, and debugging Groovy."
category: Languages
order: 7
---

Groovy gets code intelligence from `gls`, runs scripts and classes from the gutter, and debugs on the Java debugger.

## Language server

The Groovy server is `gls`, from the gvy project. Install it from its Settings → LSP card, the banner
on a `.groovy` file, or **Install: Language Server…**. It has no command until
it is installed. The installer sets its command to `java -jar` with the
downloaded jar, and Editora runs that with the JDK chosen for Maven, Gradle and
Run, not the first `java` on your `PATH`.

It reports Groovy compiler errors, and offers completion, hover, go to
definition, references, rename, the outline and formatting. CodeNarc's style
rules are off: they flag nearly every line of ordinary Groovy.

A `Jenkinsfile` at a project's root makes [Doctor](/docs/doctor)
expect the Groovy server, as an open `.groovy` file does. A `build.gradle` alone
does not, because most Gradle projects with a Groovy build script are Java
projects.

## Running

<a id="running-groovy-scripts"></a>A Groovy file runs with `groovy` on the JDK chosen in Settings for builds and
runs (the one Maven, Gradle and Java use), which Editora passes as
`JAVA_HOME`. Editora finds `groovy` through `GROOVY_HOME`, then your `PATH`,
then SDKMAN. When none of them has it, Run says Groovy is not installed instead
of starting anything. A Gradle build script (`build.gradle`) and a
`Jenkinsfile` are Groovy too, but they get no ▶: nobody runs those as a
program.

## Tests

In a Maven or Gradle project, a Groovy test file gets a ▶ in the gutter on each
test and on its class, and **Run Test at Caret** (`test.runAtCaret`) runs the
test the caret is in, or the whole class when the caret is between tests:

- **JUnit** tests: a `void` or `def` method marked `@Test` (or
  `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`).
- **Spock** specifications: a class that extends `Specification`. Each feature
  method, such as `def "adds a book"()`, is a test, with no annotation.

Run Test at Caret and Debug Test at Caret need a Maven or Gradle project, as
the gutter's ▶ icons do. The test runs through the build's test task (Maven's
`-Dtest=`, Gradle's `--tests`), and its results fill
[Test Results](/docs/build-tools#test-runner). **Debug Test at Caret**
(`test.debugAtCaret`) starts the test JVM suspended and attaches the debugger
to it, as for a [Java test](/docs/build-tools#test-runner).

## Debugging

Groovy is debugged by a debugger built into Editora, so there is nothing to
install. Debug on a Groovy file runs it as [Run does](#running), and
Debug on a Groovy script run configuration uses that configuration's script,
arguments, folder, environment, JDK and VM arguments.

- **Breakpoints** work in scripts, classes and closures.
- **Stepping** goes from line to line of your code and steps over Groovy's own
  runtime, which sits between nearly every two calls a script makes.
- **Variables** show the frame's locals, `this`, and the fields of any object or
  the elements of an array. A closure's frame is named after the method it is
  in, such as `Report.run { closure }`.
- **Hover and Watch** read a variable or a field path such as `order.total`.
  They never call a method, so looking cannot change the program.
- **Exceptions**: an exception that leaves your code uncaught stops where it was
  thrown. This is on by default for Groovy; **Break on Caught Exceptions** also
  stops on exceptions your own code catches. Exceptions Groovy throws and catches inside
  its runtime never stop.

Not supported yet: conditions, hit counts and log messages on a breakpoint (such
a breakpoint shows hollow and says so), evaluating expressions or method calls,
hot code replace, and debugging on a remote host or in a container.
