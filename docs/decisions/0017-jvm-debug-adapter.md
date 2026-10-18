# 0017 — Editora's own JVM debug adapter for JVM languages other than Java

**Status:** Accepted

## Context

Java is debugged through the microsoft java-debug plugin that jdtls hosts. It finds the class a breakpoint belongs
to by parsing the file as Java (`JdtSourceLookUpProvider`), so a breakpoint in a `.groovy`, `.kt` or `.clj` file
never binds there, although the JVM itself (JDWP) debugs any language whose classes carry a `SourceFile` attribute
and a line table. Groovy has no debug adapter of its own; Clojure's is an nREPL bridge; Kotlin's and Scala's live
inside their language servers, one undocumented and alpha. Editora already depends on lsp4j.debug (its DAP client)
and runs on a JDK that has JDI.

## Decision

- `com.editora.dap.jvm.JvmDebugAdapter` is a DAP server written on JDI and lsp4j.debug, served **in Editora's own
  process** on a loopback port (`JvmDebugServer`) so the existing `DapClient` connects to it like any socket
  adapter. `DapServerRegistry.Kind.JVM` routes a language to it (Groovy first).
- Breakpoints bind **by source name**: a class whose `sourcePaths` end the file's path
  (`JvmSourceMap.compiledFrom`), in loaded classes and, through `ClassPrepareRequest.addSourceNameFilter`, in each
  class as it loads. No source is parsed, so the same adapter serves every JVM language.
- The adapter listens (`server=n`) and the program connects back: the JDWP agent goes into the variable the
  language's launcher hands its JVM (`JAVA_OPTS` for `groovy`), never `JAVA_TOOL_OPTIONS`, which every forked JVM
  would read. The client starts the program (`runInTerminal`), so output, input and exit code are the Debug
  console's, exactly as for Python and Java.
- Inspection never runs code in the program: locals, `this`, fields, array elements, and `evaluate` of a variable or
  field path. No `toString()` is invoked.
- Exception stops are judged against the program's own frames (classes with source here, outside the runtime's
  packages): "uncaught" means the exception escapes every program frame — Groovy's script runner catches
  everything, so the JVM's own notion never fires — and a rethrow or wrapper of an exception already stopped for is
  not a second stop.
- `requires jdk.jdi` adds JDI to the packaged runtime; no third-party dependency is added. microsoft java-debug's
  core library (RxJava, commons-lang3 and others) was the alternative.

## Consequences

- Groovy breakpoints, stepping, locals and exception stops work with nothing to install; Clojure (and Kotlin or
  Scala as a fallback) can reuse it.
- Kotlin uses it as its primary debugger (its classes carry `SourceFile Foo.kt`). Kotlin need not keep a file in its
  package's folder, so a breakpoint file's own `package` line also counts (`JvmSourceMap.declaredPackage`). A
  compiled program started by `java` itself takes the agent on its command line. The adapter also attaches
  (`attach`: `hostName`, `port`) to a JVM a build tool started under a suspended agent, which is how Debug via Build
  Tool reaches a Kotlin program; kotlin-lsp's own debug server was not adopted (undocumented, alpha, open path
  bugs).
- No expression evaluation beyond variable and field paths, no conditional breakpoints or logpoints, no hot code
  replace. Those need code to run in the program, which this adapter deliberately does not do yet.
- Debugging on a remote host is not covered: the adapter runs in Editora's process and connects to a loopback port
  (an attach may name another host, but nothing maps that host's paths).
