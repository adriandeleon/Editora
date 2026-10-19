package com.editora.dap.jvm;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.editora.run.JvmScriptRun;
import com.editora.run.JvmTools;
import org.eclipse.lsp4j.debug.ContinueArguments;
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
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editora's JVM debug adapter against a real Clojure program, started as Debug starts it ({@link JvmScriptRun}:
 * {@code clojure -J-agentlib… -J-Dclojure.compiler.disable-locals-clearing=true -M -m my-app.core}). Clojure
 * compiles {@code src/my_app/core.clj} to classes such as {@code my_app.core$greet} whose source is
 * {@code my_app/core.clj}, so a breakpoint in the file binds by source name, the frame reads as the function, and
 * its locals show. Skipped where the Clojure CLI is not installed (its first run downloads Clojure itself).
 */
class ClojureDebugAdapterTest {

    private static final long WAIT = 180;

    private static final class Client implements IDebugProtocolClient {
        final BlockingQueue<StoppedEventArguments> stops = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> initialized = new CompletableFuture<>();
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

    private static StackFrame[] frames(IDebugProtocolServer server, int threadId) throws Exception {
        StackTraceArguments st = new StackTraceArguments();
        st.setThreadId(threadId);
        return server.stackTrace(st).get(WAIT, TimeUnit.SECONDS).getStackFrames();
    }

    private static Map<String, String> locals(IDebugProtocolServer server, int frameId) throws Exception {
        ScopesArguments sa = new ScopesArguments();
        sa.setFrameId(frameId);
        int ref = server.scopes(sa).get(WAIT, TimeUnit.SECONDS).getScopes()[0].getVariablesReference();
        VariablesArguments va = new VariablesArguments();
        va.setVariablesReference(ref);
        Map<String, String> out = new LinkedHashMap<>();
        for (Variable v : server.variables(va).get(WAIT, TimeUnit.SECONDS).getVariables()) {
            out.put(v.getName(), v.getValue());
        }
        return out;
    }

    private static String clojure() {
        String clojure = JvmTools.locate("clojure");
        Assumptions.assumeTrue(clojure != null, "the Clojure CLI is not installed here");
        return clojure;
    }

    /** The adapter's launch request for {@code file}, built as Debug builds it. */
    private static Map<String, Object> launchRequest(String clojure, Path file, List<String> args) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        JvmScriptRun.Launch how = JvmScriptRun.launch(
                "clojure", "clojure", clojure, file, file.toString(), args, "", true, env, System.getenv());
        assertTrue(how.argv().contains("-J-Dclojure.compiler.disable-locals-clearing=true"), how.argv()::toString);
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("argv", how.argv());
        launch.put("cwd", how.directory().toString());
        launch.put("env", how.env());
        launch.put("language", "clojure");
        launch.put("jvmOptionPrefix", JvmScriptRun.jvmOptionPrefix("clojure"));
        launch.put("sourceRoots", List.of(how.directory().toString()));
        return launch;
    }

    @Test
    void aNamespacesFunctionStopsShowsItsLocalsAndStepsInADepsProject(@TempDir Path dir) throws Exception {
        String clojure = clojure();
        Files.writeString(dir.resolve("deps.edn"), "{:paths [\"src\"]}\n");
        Path source = Files.createDirectories(dir.resolve("src/my_app")).resolve("core.clj");
        Files.writeString(source, """
                (ns my-app.core)
                (defn greet [who]
                  (let [msg (str "Hello, " who)]
                    (println msg)
                    (count msg)))
                (defn -main [& args]
                  (greet (or (first args) "world")))
                """);
        Session s = open();
        try {
            CompletableFuture<Void> launched = s.server().launch(launchRequest(clojure, source, List.of("adl")));
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), source, 4);
            SetExceptionBreakpointsArguments none = new SetExceptionBreakpointsArguments();
            none.setFilters(new String[0]);
            s.server().setExceptionBreakpoints(none).get(WAIT, TimeUnit.SECONDS);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, () -> "the breakpoint in greet is hit: " + s.client().output);
            assertEquals("breakpoint", stop.getReason());
            StackFrame frame = frames(s.server(), stop.getThreadId())[0];
            assertEquals(4, frame.getLine());
            assertEquals(source.toString(), frame.getSource().getPath());
            assertEquals("my-app.core/greet", frame.getName());
            Map<String, String> locals = locals(s.server(), frame.getId());
            assertEquals("\"adl\"", locals.get("who"), locals::toString);
            assertEquals("\"Hello, adl\"", locals.get("msg"), locals::toString);
            // The caller is the program's too, named as Clojure names it; the runtime between is not stepped into.
            List<String> names = new ArrayList<>();
            for (StackFrame f : frames(s.server(), stop.getThreadId())) {
                names.add(f.getName());
            }
            assertTrue(names.contains("my-app.core/-main"), names::toString);

            NextArguments next = new NextArguments();
            next.setThreadId(stop.getThreadId());
            s.server().next(next).get(WAIT, TimeUnit.SECONDS);
            StoppedEventArguments stepped = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stepped, "the step stops");
            StackFrame after = frames(s.server(), stepped.getThreadId())[0];
            assertEquals(5, after.getLine(), after::getName);
            assertEquals(source.toString(), after.getSource().getPath());

            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stepped.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(s.client().output.toString().contains("Hello, adl"), s.client().output::toString);
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aLeiningenProjectIsDebuggedThroughJvmOpts(@TempDir Path dir) throws Exception {
        String lein = JvmTools.locate("lein");
        Assumptions.assumeTrue(lein != null, "Leiningen is not installed here");
        Files.writeString(
                dir.resolve("project.clj"),
                "(defproject demo \"0.1.0\" :dependencies [[org.clojure/clojure \"1.12.3\"]])\n");
        Path source = Files.createDirectories(dir.resolve("src/demo")).resolve("core.clj");
        Files.writeString(source, """
                (ns demo.core)
                (defn -main [& args]
                  (let [n (count args)]
                    (println "args" n)))
                """);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", System.getProperty("java.home"));
        JvmScriptRun.Launch how = JvmScriptRun.launch(
                "clojure", "lein", lein, source, source.toString(), List.of("x", "y"), "", true, env, System.getenv());
        assertEquals(List.of(lein, "run", "-m", "demo.core", "x", "y"), how.argv());
        assertTrue(how.env().get("JVM_OPTS").contains(com.editora.run.ClojureRun.KEEP_LOCALS), how.env()::toString);
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("argv", how.argv());
        launch.put("cwd", how.directory().toString());
        launch.put("env", how.env());
        launch.put("language", "clojure");
        launch.put("jvmOptionsVariable", JvmScriptRun.jvmOptionsVariable("clojure", "lein"));
        Session s = open();
        try {
            CompletableFuture<Void> launched = s.server().launch(launch);
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), source, 4);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);
            // Leiningen's own JVM reads LEIN_JVM_OPTS: the agent is in the project's JVM, the one that stops.
            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, () -> "the breakpoint in -main is hit: " + s.client().output);
            StackFrame frame = frames(s.server(), stop.getThreadId())[0];
            assertEquals(source.toString(), frame.getSource().getPath());
            assertEquals("demo.core/-main", frame.getName());
            assertEquals("2", locals(s.server(), frame.getId()).get("n"));
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(0, s.client().exited.get(WAIT, TimeUnit.SECONDS));
        } finally {
            s.socket().close();
        }
    }

    @Test
    void aStandaloneScriptStopsAtATopLevelFormAndOnItsUncaughtException(@TempDir Path dir) throws Exception {
        String clojure = clojure();
        Path script = dir.resolve("tally.clj");
        Files.writeString(script, """
                (defn tally [xs]
                  (reduce + xs))
                (println "total" (tally [1 2 3]))
                (throw (ex-info "boom" {:n 7}))
                """);
        Session s = open();
        try {
            CompletableFuture<Void> launched = s.server().launch(launchRequest(clojure, script, List.of()));
            s.client().initialized.get(WAIT, TimeUnit.SECONDS);
            breakAt(s.server(), script, 2);
            SetExceptionBreakpointsArguments ex = new SetExceptionBreakpointsArguments();
            ex.setFilters(new String[] {JvmDebugAdapter.FILTER_UNCAUGHT});
            s.server().setExceptionBreakpoints(ex).get(WAIT, TimeUnit.SECONDS);
            s.server().configurationDone(null);
            launched.get(WAIT, TimeUnit.SECONDS);

            StoppedEventArguments stop = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(stop, () -> "the breakpoint in tally is hit: " + s.client().output);
            StackFrame frame = frames(s.server(), stop.getThreadId())[0];
            assertEquals(2, frame.getLine());
            assertEquals(script.toString(), frame.getSource().getPath());
            assertEquals("user/tally", frame.getName());
            // The adapter runs no code in the program (no toString): a collection reads as its class, to expand.
            String xs = locals(s.server(), frame.getId()).get("xs");
            assertTrue(xs != null && xs.startsWith("PersistentVector"), String.valueOf(xs));

            breakAt(s.server(), script);
            ContinueArguments cont = new ContinueArguments();
            cont.setThreadId(stop.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            StoppedEventArguments thrown = s.client().stops.poll(WAIT, TimeUnit.SECONDS);
            assertNotNull(thrown, () -> "the uncaught exception stops: " + s.client().output);
            assertEquals("exception", thrown.getReason());
            assertTrue(thrown.getText().contains("boom"), thrown.getText());
            assertEquals(4, frames(s.server(), thrown.getThreadId())[0].getLine());

            cont.setThreadId(thrown.getThreadId());
            s.server().continue_(cont).get(WAIT, TimeUnit.SECONDS);
            assertEquals(1, s.client().exited.get(WAIT, TimeUnit.SECONDS));
            assertTrue(s.client().output.toString().contains("total 6"), s.client().output::toString);
        } finally {
            s.socket().close();
        }
    }
}
