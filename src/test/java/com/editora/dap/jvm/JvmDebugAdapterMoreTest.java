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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.PauseArguments;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM adapter's exception stops and its inspection of a stopped Java program: a caught exception stops where
 * it is thrown, the wrapper rethrown around it does not stop again, an exception the runtime throws and catches
 * inside itself is no stop, and an uncaught one stops last. At those stops the locals, fields and array elements
 * read as the Variables view shows them, and evaluate answers a variable or field path and refuses the rest.
 */
class JvmDebugAdapterMoreTest {

    private static final long WAIT = 60;

    private static final String PROGRAM = """
            package p;
            public class Boom {
                int count = 2;
                String label = "a \\"quoted\\"\\nline";
                Boom next;
                int[] empty = {};
                public static void main(String[] args) {
                    Boom b = new Boom();
                    b.next = new Boom();
                    b.work(new int[] {1, 2, 3});
                    throw new IllegalArgumentException("last");
                }
                void work(int[] nums) {
                    Integer boxed = 7;
                    Long big = 9L;
                    Character ch = 'c';
                    Boolean ok = true;
                    Object nothing = null;
                    StringBuilder sb = new StringBuilder("x");
                    try {
                        try {
                            fail("first");
                        } catch (IllegalStateException e) {
                            throw new RuntimeException("wrapped", e);
                        }
                    } catch (RuntimeException e) {
                        System.out.println("caught " + e.getMessage() + sb + boxed + big + ch + ok + nothing);
                    }
                }
                void fail(String why) {
                    throw new IllegalStateException(why);
                }
            }
            """;

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
        init.setSupportsRunInTerminalRequest(false);
        server.initialize(init).get(WAIT, TimeUnit.SECONDS);
        return new Session(client, server, socket);
    }

    private static List<StackFrame> frames(IDebugProtocolServer server, int threadId, Integer levels) throws Exception {
        StackTraceArguments st = new StackTraceArguments();
        st.setThreadId(threadId);
        st.setLevels(levels);
        return List.of(server.stackTrace(st).get(WAIT, TimeUnit.SECONDS).getStackFrames());
    }

    private static List<Variable> variables(IDebugProtocolServer server, int ref, Integer start, Integer count)
            throws Exception {
        VariablesArguments va = new VariablesArguments();
        va.setVariablesReference(ref);
        va.setStart(start);
        va.setCount(count);
        return List.of(server.variables(va).get(WAIT, TimeUnit.SECONDS).getVariables());
    }

    private static List<Variable> locals(IDebugProtocolServer server, int frameId) throws Exception {
        ScopesArguments sa = new ScopesArguments();
        sa.setFrameId(frameId);
        int ref = server.scopes(sa).get(WAIT, TimeUnit.SECONDS).getScopes()[0].getVariablesReference();
        return variables(server, ref, null, null);
    }

    private static Variable named(List<Variable> vars, String name) {
        for (Variable v : vars) {
            if (v.getName().equals(name)) {
                return v;
            }
        }
        throw new AssertionError(
                name + " is not among " + vars.stream().map(Variable::getName).toList());
    }

    private static EvaluateResponse evaluate(IDebugProtocolServer server, Integer frameId, String expression)
            throws Exception {
        EvaluateArguments ev = new EvaluateArguments();
        ev.setExpression(expression);
        ev.setFrameId(frameId);
        return server.evaluate(ev).get(WAIT, TimeUnit.SECONDS);
    }

    /** The message an evaluate is refused with. */
    private static String refusal(IDebugProtocolServer server, Integer frameId, String expression) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> evaluate(server, frameId, expression));
        return e.getCause().getMessage();
    }

    private static void resume(IDebugProtocolServer server, int threadId) throws Exception {
        ContinueArguments cont = new ContinueArguments();
        cont.setThreadId(threadId);
        assertTrue(server.continue_(cont).get(WAIT, TimeUnit.SECONDS).getAllThreadsContinued());
    }

    private static StoppedEventArguments nextStop(Session s) throws InterruptedException {
        StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
        assertNotNull(stop, () -> "a stop arrives: " + s.client().output);
        return stop;
    }

    @Test
    void caughtAndUncaughtExceptionsStopOnceEachAndTheStopsCanBeInspected(@TempDir Path dir) throws Exception {
        Path source = Files.createDirectories(dir.resolve("p")).resolve("Boom.java");
        Files.writeString(source, PROGRAM);
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests run on a JDK");
        assertEquals(
                0, javac.run(null, null, null, "-g", "-d", dir.resolve("out").toString(), source.toString()));

        Session s = open();
        try {
            List<String> argv = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    dir.resolve("out").toString(),
                    "p.Boom");
            // No variable named: the agent goes in JAVA_TOOL_OPTIONS, after the options the program already has.
            Map<String, Object> launch = Map.of(
                    "argv", argv,
                    "cwd", dir.toString(),
                    "env", Map.of("JAVA_TOOL_OPTIONS", "-Dfrom.env=1"),
                    "language", "java",
                    "skipClasses", List.of("com.example.Skipped"),
                    "sourceRoots", List.of(dir.toString()),
                    "sourceFiles", List.of(source.toString()),
                    "connectTimeoutMs", 30_000);
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
            ex.setFilters(new String[] {JvmDebugAdapter.FILTER_CAUGHT});
            s.server().setExceptionBreakpoints(ex).get(WAIT, TimeUnit.SECONDS);
            // Set again, now with both: the first request is replaced, not doubled.
            ex.setFilters(new String[] {JvmDebugAdapter.FILTER_CAUGHT, JvmDebugAdapter.FILTER_UNCAUGHT});
            s.server().setExceptionBreakpoints(ex).get(WAIT, TimeUnit.SECONDS);
            SetBreakpointsArguments bps = new SetBreakpointsArguments();
            Source src = new Source();
            src.setPath(source.toString());
            bps.setSource(src);
            SourceBreakpoint line = new SourceBreakpoint();
            line.setLine(27); // the println in the outer catch
            bps.setBreakpoints(new SourceBreakpoint[] {line});
            s.server().setBreakpoints(bps).get(WAIT, TimeUnit.SECONDS);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            // 1. The caught IllegalStateException, where fail() throws it. The JVM's own start-up exceptions
            // (class loading, caught inside the runtime) came first and were no stop.
            StoppedEventArguments thrown = nextStop(s);
            assertEquals("exception", thrown.getReason());
            assertEquals("java.lang.IllegalStateException: first", thrown.getText());
            List<StackFrame> stack = frames(s.server(), thrown.getThreadId(), null);
            assertEquals("Boom.fail", stack.get(0).getName());
            assertEquals(31, stack.get(0).getLine());
            assertEquals(source.toString(), stack.get(0).getSource().getPath());
            assertEquals(1, frames(s.server(), thrown.getThreadId(), 1).size(), "levels limits the frames");

            // The locals of work(): this, the array, boxed values, null, a plain object.
            StackFrame work = stack.get(1);
            List<Variable> vars = locals(s.server(), work.getId());
            Variable self = named(vars, "this");
            assertTrue(self.getValue().startsWith("Boom (id="), self.getValue());
            assertTrue(self.getVariablesReference() > 0, "an object expands");
            Variable nums = named(vars, "nums");
            assertEquals("int[3]", nums.getValue());
            assertEquals(3, nums.getIndexedVariables());
            assertEquals("7", named(vars, "boxed").getValue());
            assertEquals(0, named(vars, "boxed").getVariablesReference(), "a boxed number does not expand");
            assertEquals("9", named(vars, "big").getValue());
            assertEquals("c", named(vars, "ch").getValue());
            assertEquals("true", named(vars, "ok").getValue());
            assertEquals("null", named(vars, "nothing").getValue());
            assertNull(named(vars, "nothing").getType());
            assertTrue(named(vars, "sb").getValue().startsWith("StringBuilder (id="));

            // The array's elements, all of them and a window of them.
            List<Variable> all = variables(s.server(), nums.getVariablesReference(), null, null);
            assertEquals(
                    List.of("[0]", "[1]", "[2]"),
                    all.stream().map(Variable::getName).toList());
            assertEquals(
                    List.of("1", "2", "3"), all.stream().map(Variable::getValue).toList());
            List<Variable> window = variables(s.server(), nums.getVariablesReference(), 1, 1);
            assertEquals("[1]", window.get(0).getName());
            assertEquals("2", window.get(0).getValue());
            assertEquals(List.of(), variables(s.server(), nums.getVariablesReference(), 3, null));

            // The object's fields: a string shown quoted and escaped, an empty array that does not expand.
            List<Variable> fields = variables(s.server(), self.getVariablesReference(), null, null);
            assertEquals("2", named(fields, "count").getValue());
            assertEquals("\"a \\\"quoted\\\"\\nline\"", named(fields, "label").getValue());
            assertEquals("int[0]", named(fields, "empty").getValue());
            assertEquals(0, named(fields, "empty").getVariablesReference());
            assertTrue(named(fields, "next").getValue().startsWith("Boom (id="));
            assertEquals(List.of(), variables(s.server(), 999_999, null, null), "an unknown reference is empty");

            // Evaluate: a local, a field of this by its bare name, a field path, a boxed value's field.
            assertEquals("int[3]", evaluate(s.server(), work.getId(), "nums").getResult());
            assertEquals("2", evaluate(s.server(), work.getId(), "count").getResult());
            assertEquals(
                    "2", evaluate(s.server(), work.getId(), "this.next.count").getResult());
            assertEquals("null", evaluate(s.server(), work.getId(), "next.next").getResult());
            assertEquals(
                    "7", evaluate(s.server(), work.getId(), " boxed.value ").getResult());
            assertTrue(evaluate(s.server(), work.getId(), "this").getVariablesReference() > 0);
            assertEquals(
                    "Only variables and their fields can be evaluated here",
                    refusal(s.server(), work.getId(), "count + 1"));
            assertEquals("No frame to evaluate in", refusal(s.server(), null, "count"));
            assertEquals("No frame to evaluate in", refusal(s.server(), 999_999, "count"));
            assertEquals("'nope' is not a variable here", refusal(s.server(), work.getId(), "nope"));
            assertEquals("'count' has no fields", refusal(s.server(), work.getId(), "count.size"));
            assertEquals("'nope' is not a field of p.Boom", refusal(s.server(), work.getId(), "next.nope"));
            assertEquals("'nothing' has no fields", refusal(s.server(), work.getId(), "nothing.x"));
            StackFrame main = stack.get(2);
            assertEquals("'count' is not a variable here", refusal(s.server(), main.getId(), "count"));

            // A pause for a thread the adapter does not know stops the main thread; it needs its own continue.
            PauseArguments pause = new PauseArguments();
            pause.setThreadId(999_999);
            s.server().pause(pause).get(WAIT, TimeUnit.SECONDS);
            StoppedEventArguments paused = nextStop(s);
            assertEquals("pause", paused.getReason());
            resume(s.server(), paused.getThreadId());
            // A step on a thread that does not exist is refused.
            NextArguments next = new NextArguments();
            next.setThreadId(999_999);
            assertThrows(ExecutionException.class, () -> s.server().next(next).get(WAIT, TimeUnit.SECONDS));
            resume(s.server(), thrown.getThreadId());

            // 2. The RuntimeException that wraps it is the same failure on its way out: no stop. The breakpoint is.
            StoppedEventArguments hit = nextStop(s);
            assertEquals("breakpoint", hit.getReason(), () -> String.valueOf(hit.getText()));
            assertEquals(27, frames(s.server(), hit.getThreadId(), null).get(0).getLine());
            resume(s.server(), hit.getThreadId());

            // 3. The uncaught IllegalArgumentException from main.
            StoppedEventArguments uncaught = nextStop(s);
            assertEquals("exception", uncaught.getReason());
            assertEquals("java.lang.IllegalArgumentException: last", uncaught.getText());
            assertEquals(
                    "Boom.main",
                    frames(s.server(), uncaught.getThreadId(), null).get(0).getName());
            resume(s.server(), uncaught.getThreadId());

            assertEquals(1, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            s.client().terminated.get(WAIT, TimeUnit.SECONDS);
            assertTrue(
                    s.client().output.toString().contains("caught wrapped"),
                    s.client().output.toString());
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aLaunchWithoutACommandOrAProgramThatNeverConnectsFailsWithItsReason(@TempDir Path dir) throws Exception {
        Session s = open();
        try {
            ExecutionException none = assertThrows(
                    ExecutionException.class,
                    () -> s.server().launch(Map.of("argv", List.of())).get(WAIT, TimeUnit.SECONDS));
            assertEquals(
                    "Nothing to launch: the request names no command",
                    none.getCause().getMessage());

            // A program that exits at once, without the agent: the launch ends with its exit code, not a timeout.
            List<String> argv = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version"));
            ExecutionException died = assertThrows(
                    ExecutionException.class,
                    () -> s.server()
                            .launch(Map.of(
                                    "argv",
                                    argv,
                                    "cwd",
                                    dir.toString(),
                                    "jvmOptionsVariable",
                                    "EDITORA_UNUSED_OPTIONS",
                                    "connectTimeoutMs",
                                    20_000))
                            .get(WAIT, TimeUnit.SECONDS));
            String message = died.getCause().getMessage();
            assertTrue(
                    message.startsWith("The program exited (code 0)")
                            || message.startsWith("The program did not connect"),
                    message);
        } finally {
            s.socket().close();
        }
    }

    @Test
    void beforeAnyProgramRequestsAnswerEmptyAndAnAttachWithoutAJvmFails() throws Exception {
        Session s = open();
        try {
            assertEquals(0, s.server().threads().get(WAIT, TimeUnit.SECONDS).getThreads().length);
            StackTraceArguments st = new StackTraceArguments();
            st.setThreadId(5);
            assertEquals(
                    0, s.server().stackTrace(st).get(WAIT, TimeUnit.SECONDS).getStackFrames().length);
            ScopesArguments sa = new ScopesArguments();
            sa.setFrameId(5);
            assertEquals(0, s.server().scopes(sa).get(WAIT, TimeUnit.SECONDS).getScopes().length);
            SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
            ex.setFilters(new String[] {JvmDebugAdapter.FILTER_UNCAUGHT});
            ExecutionException noVm = assertThrows(
                    ExecutionException.class,
                    () -> s.server().setExceptionBreakpoints(ex).get(WAIT, TimeUnit.SECONDS));
            assertEquals("No program is being debugged", noVm.getCause().getMessage());

            ExecutionException noPort = assertThrows(
                    ExecutionException.class,
                    () -> s.server().attach(Map.of("port", "none")).get(WAIT, TimeUnit.SECONDS));
            assertEquals(
                    "Nothing to attach to: the request names no port",
                    noPort.getCause().getMessage());
            int closed;
            try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
                closed = free.getLocalPort();
            }
            int port = closed;
            ExecutionException refused = assertThrows(
                    ExecutionException.class,
                    () -> s.server()
                            .attach(Map.of("hostName", "", "port", (double) port))
                            .get(WAIT, TimeUnit.SECONDS));
            assertTrue(
                    refused.getCause().getMessage().startsWith("Could not attach to 127.0.0.1:" + port),
                    refused.getCause().getMessage());

            // Ending a session that never ran a program still tells the client it ended.
            s.server()
                    .disconnect(new org.eclipse.lsp4j.debug.DisconnectArguments())
                    .get(WAIT, TimeUnit.SECONDS);
            s.client().terminated.get(WAIT, TimeUnit.SECONDS);
            assertTrue(s.client().stops.isEmpty());
        } finally {
            s.socket().close();
        }
    }
}
