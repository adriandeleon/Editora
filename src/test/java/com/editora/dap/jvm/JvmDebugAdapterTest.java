package com.editora.dap.jvm;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editora's JVM debug adapter against a real program, over the Debug Adapter Protocol as the Debug panel speaks
 * it: a breakpoint binds by source name, the stop shows the frame, its locals and an evaluated variable, a step
 * moves one line, and the program runs to its exit code. Java stands in for every JVM language — the adapter
 * never parses source — and a Groovy script runs too where Groovy is installed.
 */
class JvmDebugAdapterTest {

    private static final long WAIT = 60;

    /** A DAP client that records what the adapter tells it. */
    private static final class Client implements IDebugProtocolClient {
        final BlockingQueue<StoppedEventArguments> stops = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> initialized = new CompletableFuture<>();
        final CompletableFuture<Void> terminated = new CompletableFuture<>();
        final CompletableFuture<Integer> exited = new CompletableFuture<>();
        final StringBuffer output = new StringBuffer();

        @Override
        public void initialized() {
            initialized.complete(null);
        }

        @Override
        public void stopped(StoppedEventArguments args) {
            stops.add(args);
        }

        @Override
        public void output(OutputEventArguments args) {
            output.append(args.getOutput());
        }

        @Override
        public void exited(ExitedEventArguments args) {
            exited.complete(args.getExitCode());
        }

        @Override
        public void terminated(TerminatedEventArguments args) {
            terminated.complete(null);
        }
    }

    private record Session(Client client, IDebugProtocolServer server, Socket socket) {}

    private static Session open() throws Exception {
        int port = JvmDebugServer.start(new JvmDebugAdapter());
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        Client client = new Client();
        Launcher<IDebugProtocolServer> launcher =
                DSPLauncher.createClientLauncher(client, socket.getInputStream(), socket.getOutputStream());
        launcher.startListening();
        IDebugProtocolServer server = launcher.getRemoteProxy();
        InitializeRequestArguments init = new InitializeRequestArguments();
        init.setAdapterID("editora-jvm");
        init.setLinesStartAt1(true);
        init.setPathFormat("path");
        server.initialize(init).get(WAIT, TimeUnit.SECONDS);
        return new Session(client, server, socket);
    }

    private static void breakAt(IDebugProtocolServer server, Path file, int... lines) throws Exception {
        SetBreakpointsArguments bps = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(file.toString());
        bps.setSource(source);
        SourceBreakpoint[] wanted = new SourceBreakpoint[lines.length];
        for (int i = 0; i < lines.length; i++) {
            wanted[i] = new SourceBreakpoint();
            wanted[i].setLine(lines[i]);
        }
        bps.setBreakpoints(wanted);
        server.setBreakpoints(bps).get(WAIT, TimeUnit.SECONDS);
    }

    private static StackFrame top(IDebugProtocolServer server, int threadId) throws Exception {
        StackTraceArguments st = new StackTraceArguments();
        st.setThreadId(threadId);
        StackFrame[] frames = server.stackTrace(st).get(WAIT, TimeUnit.SECONDS).getStackFrames();
        assertTrue(frames.length > 0);
        return frames[0];
    }

    private static String frames(IDebugProtocolServer server, int threadId) {
        try {
            StackTraceArguments st = new StackTraceArguments();
            st.setThreadId(threadId);
            StringBuilder sb = new StringBuilder();
            for (StackFrame f :
                    server.stackTrace(st).get(WAIT, TimeUnit.SECONDS).getStackFrames()) {
                sb.append(f.getName()).append(':').append(f.getLine()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static List<Variable> locals(IDebugProtocolServer server, int frameId) throws Exception {
        ScopesArguments sa = new ScopesArguments();
        sa.setFrameId(frameId);
        int ref = server.scopes(sa).get(WAIT, TimeUnit.SECONDS).getScopes()[0].getVariablesReference();
        VariablesArguments va = new VariablesArguments();
        va.setVariablesReference(ref);
        return List.of(server.variables(va).get(WAIT, TimeUnit.SECONDS).getVariables());
    }

    private static String valueOf(List<Variable> vars, String name) {
        for (Variable v : vars) {
            if (v.getName().equals(name)) {
                return v.getValue();
            }
        }
        return null;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    void aJavaProgramStopsShowsItsLocalsStepsAndExits(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Path source = pkg.resolve("Hello.java");
        Files.writeString(source, """
                package p;
                public class Hello {
                    public static void main(String[] args) {
                        int x = 41;
                        String who = "adl";
                        x++;
                        System.out.println(who + " " + x);
                        System.exit(3);
                    }
                }
                """);
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests run on a JDK");
        assertEquals(
                0, javac.run(null, null, null, "-g", "-d", dir.resolve("out").toString(), source.toString()));

        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv",
                    List.of(javaExecutable(), "-cp", dir.resolve("out").toString(), "p.Hello"),
                    "cwd",
                    dir.toString(),
                    "jvmOptionsVariable",
                    "JDK_JAVA_OPTIONS",
                    "language",
                    "java");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            // A condition would need code run in the program: not set, and said so — never a plain stop instead.
            SetBreakpointsArguments conditional = new SetBreakpointsArguments();
            Source src = new Source();
            src.setPath(source.toString());
            conditional.setSource(src);
            SourceBreakpoint when = new SourceBreakpoint();
            when.setLine(4);
            when.setCondition("x > 40");
            conditional.setBreakpoints(new SourceBreakpoint[] {when});
            var refused = s.server()
                    .setBreakpoints(conditional)
                    .get(WAIT, TimeUnit.SECONDS)
                    .getBreakpoints()[0];
            assertEquals(false, refused.isVerified());
            assertEquals(JvmDebugAdapter.CONDITIONS_UNSUPPORTED, refused.getMessage());
            breakAt(s.server(), source, 5);
            // A plain instance, not an anonymous subclass: Gson writes an anonymous class as null, so the
            // adapter received no arguments at all.
            SetExceptionBreakpointsArguments noExceptions = new SetExceptionBreakpointsArguments();
            noExceptions.setFilters(new String[0]);
            s.server().setExceptionBreakpoints(noExceptions);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint is hit");
            assertEquals("breakpoint", stop.getReason());
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(5, frame.getLine());
            assertEquals(source.toString(), frame.getSource().getPath());
            assertEquals("Hello.main", frame.getName());
            assertEquals("41", valueOf(locals(s.server(), frame.getId()), "x"));

            NextArguments next = new NextArguments();
            next.setThreadId(stop.getThreadId());
            s.server().next(next).get(WAIT, TimeUnit.SECONDS);
            StoppedEventArguments stepped = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stepped, "the step stops");
            assertEquals("step", stepped.getReason());
            StackFrame after = top(s.server(), stepped.getThreadId());
            assertEquals(6, after.getLine());
            assertEquals("\"adl\"", valueOf(locals(s.server(), after.getId()), "who"));
            EvaluateArguments ev = new EvaluateArguments();
            ev.setExpression("who");
            ev.setFrameId(after.getId());
            assertEquals(
                    "\"adl\"",
                    s.server().evaluate(ev).get(WAIT, TimeUnit.SECONDS).getResult());

            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stepped.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(3, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            s.client().terminated.get(WAIT, TimeUnit.SECONDS);
            assertTrue(
                    s.client().output.toString().contains("adl 42"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aGroovyScriptStopsInsideAClosureWhereGroovyIsInstalled(@TempDir Path dir) throws Exception {
        String groovy = com.editora.run.JvmTools.locate("groovy");
        Assumptions.assumeTrue(groovy != null, "Groovy is not installed here");
        Path script = dir.resolve("Tally.groovy");
        Files.writeString(script, """
                def total = 0
                [1, 2, 3].each { n ->
                    total += n
                }
                println "total=$total"
                """);
        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv", List.of(groovy, script.toString()),
                    "cwd", dir.toString(),
                    "env", Map.of("JAVA_HOME", System.getProperty("java.home")),
                    "jvmOptionsVariable", "JAVA_OPTS",
                    "language", "groovy");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), script, 3);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint in the closure is hit: " + s.client().output);
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(3, frame.getLine());
            assertEquals(script.toString(), frame.getSource().getPath());
            List<String> names = new ArrayList<>();
            for (Variable v : locals(s.server(), frame.getId())) {
                names.add(v.getName());
            }
            assertTrue(names.contains("n"), names.toString());
            assertEquals("1", valueOf(locals(s.server(), frame.getId()), "n"));

            breakAt(s.server(), script); // clear it: the loop runs to the end
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(
                    s.client().output.toString().contains("total=6"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aGroovyScriptsUncaughtExceptionStopsWhereItIsThrownAndNowhereElse(@TempDir Path dir) throws Exception {
        String groovy = com.editora.run.JvmTools.locate("groovy");
        Assumptions.assumeTrue(groovy != null, "Groovy is not installed here");
        Path script = dir.resolve("Boom.groovy");
        Files.writeString(script, """
                def risky(int n) {
                    try { Integer.parseInt("x$n") } catch (NumberFormatException ignored) { }
                    throw new IllegalStateException("boom $n")
                }
                risky(7)
                """);
        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv", List.of(groovy, script.toString()),
                    "cwd", dir.toString(),
                    "env", Map.of("JAVA_HOME", System.getProperty("java.home")),
                    "jvmOptionsVariable", "JAVA_OPTS",
                    "language", "groovy");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
            ex.setFilters(new String[] {JvmDebugAdapter.FILTER_UNCAUGHT});
            s.server().setExceptionBreakpoints(ex).get(WAIT, TimeUnit.SECONDS);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            // Groovy's startup and the caught NumberFormatException are not stops: the first stop is the throw.
            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the uncaught exception stops: " + s.client().output);
            assertEquals("exception", stop.getReason());
            assertTrue(stop.getText().startsWith("java.lang.IllegalStateException: boom 7"), stop.getText());
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(3, frame.getLine(), () -> frames(s.server(), stop.getThreadId()));
            assertEquals(script.toString(), frame.getSource().getPath());

            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(1, s.client().exited.get(WAIT, TimeUnit.SECONDS));
        } finally {
            s.socket().close();
        }
    }

    /** Scala CLI here: {@code -Deditora.test.scalaCli=<path>}, else the one {@link com.editora.run.JvmTools} finds. */
    private static String scalaCli() {
        String set = System.getProperty("editora.test.scalaCli", "");
        return !set.isBlank() ? set : com.editora.run.JvmTools.locate("scala-cli");
    }

    /**
     * A Scala file run by Scala CLI, with the agent on its command line ({@code --java-opt}): a breakpoint in a
     * lambda binds although the file's package ({@code demo}) is not its folder, the frame reads as the source
     * names it, and the program's output arrives. Scala CLI compiles first (and on a first run fetches the
     * compiler), so this waits minutes, not seconds.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES) // a first Scala CLI run downloads the compiler
    void aScalaProgramRunByScalaCliStopsInALambdaWhereScalaCliIsInstalled(@TempDir Path dir) throws Exception {
        String cli = scalaCli();
        Assumptions.assumeTrue(cli != null, "Scala CLI is not installed here");
        Path main = dir.resolve("Main.scala");
        Files.writeString(main, """
                package demo

                @main def tally(): Unit =
                  val doubled = List(1, 2, 3).map { n =>
                    n * 2
                  }
                  println(s"total=${doubled.sum}")
                """);
        Session s = open();
        try {
            Map<String, Object> launch = new java.util.LinkedHashMap<>();
            // --server=false: compiled in Scala CLI's own process, so the test needs no Bloop daemon.
            launch.put(
                    "argv",
                    com.editora.run.JvmScriptRun.scalaCliDebug(
                            cli, List.of("--server=false", main.toString()), List.of(), List.of()));
            launch.put("agentArgument", com.editora.run.JvmScriptRun.AGENT_ARGUMENT);
            launch.put("connectTimeoutMs", 600_000);
            launch.put("cwd", dir.toString());
            launch.put("env", Map.of("JAVA_HOME", System.getProperty("java.home")));
            launch.put("language", "scala");
            CompletableFuture<Void> launched = s.server().launch(launch);
            launched.exceptionally(e -> {
                s.client().initialized.completeExceptionally(e); // a failed start ends the wait at once
                return null;
            });
            s.client().initialized.get(4, TimeUnit.MINUTES);
            breakAt(s.server(), main, 5);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint in the lambda is hit: " + s.client().output);
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(5, frame.getLine());
            assertEquals(main.toString(), frame.getSource().getPath());
            // Scala 3 names the lambda $anonfun$1, without its enclosing method.
            assertEquals("Main { lambda }", frame.getName(), frames(s.server(), stop.getThreadId()));
            assertEquals("1", valueOf(locals(s.server(), frame.getId()), "n"));

            breakAt(s.server(), main);
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(
                    s.client().output.toString().contains("total=12"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    /** Compiles {@code source} with {@code kotlinc} into {@code jar} on this JDK; fails the test when it does not. */
    private static void kotlinc(String kotlinc, Path source, Path jar) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                        com.editora.run.KotlinRun.compileArgv(kotlinc, source.toString(), jar.toString()))
                .redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(p.waitFor(180, TimeUnit.SECONDS), "kotlinc finished");
        assertEquals(0, p.exitValue(), out);
    }

    @Test
    void aKotlinProgramStopsInItsFileWhicheverFolderItSitsInAndNamesItsFramesAsKotlin(@TempDir Path dir)
            throws Exception {
        String kotlinc = com.editora.run.JvmTools.locate("kotlinc");
        Assumptions.assumeTrue(kotlinc != null, "Kotlin is not installed here");
        // package demo, in a folder that is not demo/: Kotlin allows it, and its classes say demo/Hello.kt.
        Path source = dir.resolve("Hello.kt");
        Files.writeString(source, """
                package demo

                fun greet(name: String): String {
                    val msg = "Hello, $name"
                    return msg
                }

                fun main(args: Array<String>) {
                    val twice: (Int) -> Int = { n ->
                        n * 2
                    }
                    println(greet("Kotlin") + " " + twice(21))
                }
                """);
        Path jar = dir.resolve("out/Hello.jar");
        Files.createDirectories(jar.getParent());
        kotlinc(kotlinc, source, jar);
        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv",
                            com.editora.run.KotlinRun.programArgv(
                                    javaExecutable(), List.of(), jar.toString(), List.of()),
                    "cwd", dir.resolve("out").toString(),
                    "jvmOptionsInArgv", true,
                    "sourceFiles", List.of(source.toString()),
                    "language", "kotlin");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), source, 5, 10);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint in greet is hit: " + s.client().output);
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(5, frame.getLine());
            assertEquals(source.toString(), frame.getSource().getPath());
            assertEquals("Hello.greet", frame.getName(), () -> frames(s.server(), stop.getThreadId()));
            assertEquals("\"Hello, Kotlin\"", valueOf(locals(s.server(), frame.getId()), "msg"));
            assertTrue(frames(s.server(), stop.getThreadId()).contains("Hello.main:12"), "the caller, as Kotlin");

            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            StoppedEventArguments inLambda = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(inLambda, "the breakpoint in the lambda is hit: " + s.client().output);
            StackFrame lambda = top(s.server(), inLambda.getThreadId());
            assertEquals(10, lambda.getLine());
            assertEquals(source.toString(), lambda.getSource().getPath());
            assertEquals("Hello.main { lambda }", lambda.getName(), () -> frames(s.server(), inLambda.getThreadId()));
            assertEquals("21", valueOf(locals(s.server(), lambda.getId()), "n"));

            breakAt(s.server(), source);
            cont.setThreadId(inLambda.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(
                    s.client().output.toString().contains("Hello, Kotlin 42"),
                    s.client().output.toString());
            assertFalse(s.client().output.toString().contains("Picked up"), "no launcher note: the agent is in argv");
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aKotlinScriptStopsOnItsLine(@TempDir Path dir) throws Exception {
        String kotlinc = com.editora.run.JvmTools.locate("kotlinc");
        Assumptions.assumeTrue(kotlinc != null, "Kotlin is not installed here");
        Path script = dir.resolve("tally.main.kts");
        Files.writeString(script, """
                var total = 0
                for (n in listOf(1, 2, 3)) {
                    total += n
                }
                println("total=$total")
                """);
        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv", com.editora.run.KotlinRun.scriptArgv(kotlinc, script.toString(), List.of()),
                    "cwd", dir.toString(),
                    "env", Map.of("JAVA_HOME", System.getProperty("java.home")),
                    "jvmOptionsVariable", "JAVA_OPTS",
                    "sourceFiles", List.of(script.toString()),
                    "language", "kotlin");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), script, 3);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(180, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint in the script is hit: " + s.client().output);
            StackFrame frame = top(s.server(), stop.getThreadId());
            assertEquals(3, frame.getLine());
            assertEquals(script.toString(), frame.getSource().getPath());

            breakAt(s.server(), script);
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(180, TimeUnit.SECONDS));
            assertTrue(
                    s.client().output.toString().contains("total=6"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    /**
     * A loaded class whose SourceDebugExtension JDI cannot parse makes JDI throw InternalError, not
     * AbsentInformationException, from every read of its source information. Kotlin 2.4.10's compiler loads two;
     * this builds one by hand, so the case is covered where no Kotlin is installed. With a frame of that class on
     * the stack, the stack trace still lists it, and setting breakpoints (which asks every loaded class) works.
     */
    @Test
    void aClassWithAnUnreadableSourceDebugExtensionBreaksNeitherBreakpointsNorTheStack(@TempDir Path dir)
            throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Path source = pkg.resolve("Main.java");
        Files.writeString(source, """
                package p;
                public class Main {
                    public static void main(String[] args) {
                        Bad.run(() -> {
                            int x = 7;
                            System.out.println("x=" + x);
                        });
                    }
                }
                """);
        Path bad = pkg.resolve("Bad.java");
        Files.writeString(bad, """
                package p;
                public class Bad {
                    static void run(Runnable r) {
                        r.run();
                    }
                }
                """);
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path out = dir.resolve("out");
        assertEquals(0, javac.run(null, null, null, "-g", "-d", out.toString(), source.toString(), bad.toString()));
        // A line section JDI's SMAP parser stops at: `x` where a line number and ':' belong.
        Path badClass = out.resolve("p/Bad.class");
        var cf = java.lang.classfile.ClassFile.of();
        byte[] smap = "SMAP\nBad.java\nJava\n*S Java\n*F\n1 Bad.java\n*L\nx\n*E\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(
                badClass,
                cf.transformClass(
                        cf.parse(badClass),
                        java.lang.classfile.ClassTransform.endHandler(
                                b -> b.with(java.lang.classfile.attribute.SourceDebugExtensionAttribute.of(smap)))));

        Session s = open();
        try {
            Map<String, Object> launch = Map.of(
                    "argv",
                    List.of(javaExecutable(), "-cp", out.toString(), "p.Main"),
                    "cwd",
                    dir.toString(),
                    "jvmOptionsVariable",
                    "JDK_JAVA_OPTIONS",
                    "language",
                    "java");
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), source, 5);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, "the breakpoint in the lambda is hit: " + s.client().output);
            String stack = frames(s.server(), stop.getThreadId());
            assertEquals(5, top(s.server(), stop.getThreadId()).getLine(), stack);
            assertTrue(stack.contains("Bad.run:"), "the frame of the unreadable class is listed: " + stack);

            breakAt(s.server(), source); // asks every loaded class, Bad among them
            breakAt(s.server(), bad, 4);
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(
                    s.client().output.toString().contains("x=7"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    @Test
    void anAttachConnectsToAWaitingJvmStopsAndDetachesLeavingItRunning(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("p"));
        Path source = pkg.resolve("Wait.java");
        Files.writeString(source, """
                package p;
                public class Wait {
                    public static void main(String[] args) {
                        int x = 7;
                        System.out.println("x=" + x);
                    }
                }
                """);
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(
                0, javac.run(null, null, null, "-g", "-d", dir.resolve("out").toString(), source.toString()));
        Process program = new ProcessBuilder(
                        javaExecutable(),
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                        "-cp",
                        dir.resolve("out").toString(),
                        "p.Wait")
                .redirectErrorStream(true)
                .start();
        try {
            java.io.BufferedReader out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(program.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            int port = com.editora.test.TestDebug.jdwpPort(out.readLine());
            assertTrue(port > 0, "the JVM says where it listens");
            Session s = open();
            try {
                Map<String, Object> attach =
                        Map.of("hostName", "127.0.0.1", "port", port, "cwd", dir.toString(), "language", "java");
                CompletableFuture<Void> attached = s.server().attach(attach);
                s.client().initialized.get(WAIT, TimeUnit.SECONDS);
                breakAt(s.server(), source, 5);
                s.server().configurationDone(null);
                attached.get(WAIT, TimeUnit.SECONDS);

                StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
                assertNotNull(stop, "the breakpoint is hit in the attached JVM");
                StackFrame frame = top(s.server(), stop.getThreadId());
                assertEquals(5, frame.getLine());
                assertEquals("7", valueOf(locals(s.server(), frame.getId()), "x"));

                s.server()
                        .disconnect(new org.eclipse.lsp4j.debug.DisconnectArguments())
                        .get(WAIT, TimeUnit.SECONDS);
                s.client().terminated.get(WAIT, TimeUnit.SECONDS);
                String line;
                do {
                    line = out.readLine(); // (the agent's own lines come first)
                } while (line != null && !line.startsWith("x="));
                assertEquals("x=7", line, "detached, the program carries on");
                assertTrue(program.waitFor(WAIT, TimeUnit.SECONDS));
                assertEquals(0, program.exitValue());
            } finally {
                s.socket().close();
            }
        } finally {
            program.destroyForcibly();
        }
    }
}
