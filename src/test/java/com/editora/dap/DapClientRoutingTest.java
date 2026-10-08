package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@link DapClient} sends a request and what it passes on: with a child session (vscode-js-debug)
 * everything about the debuggee goes to the child and the child's events become the session's own; a
 * session that was disposed opens nothing; and the adapter process a session was given dies with it.
 */
class DapClientRoutingTest {

    private static final Path FILE = Path.of("app.js").toAbsolutePath();

    /** Records what the session reports, in arrival order. */
    private static class Host implements DapClient.Host {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override
        public void onStopped(int threadId, String reason) {
            events.add("stopped:" + threadId + ":" + reason);
        }

        @Override
        public void onContinued() {
            events.add("continued");
        }

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {
            events.add("terminated");
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }

        @Override
        public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
            if (!whole) {
                events.add("breakpoint:" + file.getFileName() + ":"
                        + statuses.get(0).line() + ":" + statuses.get(0).verified());
            }
        }

        @Override
        public void onNotice(String message, boolean error) {
            events.add("notice:" + message + ":" + error);
        }

        String next(String what) throws InterruptedException {
            String event = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(event, "never received " + what);
            return event;
        }
    }

    /** Connects to a js-debug-like adapter and returns {root, child} once the child is configured. */
    private static FakeDebugAdapter.Session[] launchWithChild(FakeDebugAdapter adapter, DapClient client)
            throws Exception {
        client.connect(adapter.port(), "pwa-node").get(10, TimeUnit.SECONDS);
        client.launch(Map.of("type", "pwa-node", "request", "launch", "program", FILE.toString()))
                .get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session root = adapter.awaitSession();
        FakeDebugAdapter.Session child = adapter.awaitSession();
        assertTrue(child.configured.await(10, TimeUnit.SECONDS), "the child session was never configured");
        root.startDebuggingAnswered.get(10, TimeUnit.SECONDS);
        return new FakeDebugAdapter.Session[] {root, child};
    }

    private static FakeDebugAdapter.Session launchAlone(FakeDebugAdapter adapter, DapClient client) throws Exception {
        client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
        client.launch(Map.of("request", "launch")).get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session only = adapter.awaitSession();
        assertTrue(only.configured.await(10, TimeUnit.SECONDS));
        return only;
    }

    @Test
    void inspectionAndControlOfAStoppedDebuggeeGoToItsChildSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            adapter.gotoTargets = true;
            ExceptionInfoResponse thrown = new ExceptionInfoResponse();
            thrown.setExceptionId("TypeError");
            thrown.setDescription("x is not a function");
            adapter.exceptionInfo = thrown;
            Host host = new Host();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session[] sessions = launchWithChild(adapter, client);
            FakeDebugAdapter.Session root = sessions[0];
            FakeDebugAdapter.Session child = sessions[1];
            child.variables.put(
                    FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("count", "3", 0)));
            child.gotoTargetIds = List.of(11);

            child.stop(7, "exception");
            assertEquals("stopped:7:exception", host.next("the child's stop"));

            assertEquals(
                    "Locals", client.scopes(1).get(10, TimeUnit.SECONDS).get(0).name());
            assertEquals(
                    "count",
                    client.variables(FakeDebugAdapter.Session.LOCALS)
                            .get(10, TimeUnit.SECONDS)
                            .get(0)
                            .name());
            client.variables(FakeDebugAdapter.Session.LOCALS, "named", 0, 0).get(10, TimeUnit.SECONDS);
            assertEquals(
                    org.eclipse.lsp4j.debug.VariablesArgumentsFilter.NAMED,
                    child.variableRequests.get(1).getFilter());
            assertEquals("val(count)", client.evaluate("count", 1, "repl").get(10, TimeUnit.SECONDS));
            assertEquals(
                    "val(count)",
                    client.evaluateFull("count", 1, "watch")
                            .get(10, TimeUnit.SECONDS)
                            .result());
            assertEquals(
                    "4",
                    client.setVariable(FakeDebugAdapter.Session.LOCALS, "count", "4")
                            .get(10, TimeUnit.SECONDS));
            DapModels.ExceptionInfo info = client.exceptionInfo(7).get(10, TimeUnit.SECONDS);
            assertEquals("TypeError", info.type());
            assertEquals("x is not a function", info.message());
            assertTrue(client.supportsGotoTargets(), "the capability of the session that debugs");
            assertEquals(List.of(11), client.gotoTargets(FILE, 4).get(10, TimeUnit.SECONDS));
            client.gotoTarget(7, 11).get(10, TimeUnit.SECONDS);
            assertEquals(11, child.gotoRequests.get(0).getTargetId());
            client.pause(7);
            child.awaitRequest("pause");

            for (String request : List.of(
                    "scopes",
                    "variables",
                    "evaluate",
                    "setVariable",
                    "exceptionInfo",
                    "gotoTargets",
                    "goto",
                    "pause")) {
                assertTrue(child.requests.contains(request), "the child never got " + request);
                assertFalse(root.requests.contains(request), "the root session debugs nothing, yet got " + request);
            }
            client.dispose();
        }
    }

    @Test
    void whatAChildSessionReportsBecomesTheSessionsOwn() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            client.setBreakpoints(
                    List.of(new DapModels.FileBreakpoints(FILE, List.of(new DapModels.LineBreakpoint(2, null, null)))));
            FakeDebugAdapter.Session child = launchWithChild(adapter, client)[1];

            child.userNotification("ERROR", "  Cannot evaluate the condition 'i ==' \n");
            assertEquals("notice:Cannot evaluate the condition 'i ==':true", host.next("the notice"));

            // An event naming no id is matched by its source and line instead.
            org.eclipse.lsp4j.debug.Breakpoint bound = FakeDebugAdapter.Session.breakpoint(null, true, 3, null);
            org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
            source.setPath(FILE.toString());
            bound.setSource(source);
            // One that names neither says nothing about any breakpoint the user has: it is passed over.
            child.breakpointChanged(FakeDebugAdapter.Session.breakpoint(null, true, 3, null));
            child.breakpointChanged(bound);
            assertEquals("breakpoint:app.js:2:true", host.next("the breakpoint change"));

            child.stop(7, "breakpoint");
            assertEquals("stopped:7:breakpoint", host.next("the stop"));
            child.continued(7, true);
            assertEquals("continued", host.next("the child resuming"));
            client.dispose();
        }
    }

    @Test
    void anExceptionFilterChangeReachesEveryLiveSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            DapClient client = new DapClient(new Host());
            FakeDebugAdapter.Session[] sessions = launchWithChild(adapter, client);

            client.setExceptionFilters(List.of("caught"));

            sessions[1].awaitRequest("setExceptionBreakpoints");
            assertEquals(
                    List.of("caught"),
                    List.of(sessions[1].exceptionBreakpoints.get(0).getFilters()));
            sessions[0].awaitRequest("setExceptionBreakpoints");
            client.dispose();
        }
    }

    @Test
    void aChildSessionTheAdapterRefusesToStartEndsTheSessionWithItsReason() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            adapter.childLaunchFailure = "Cannot find the target to attach to";
            Host host = new Host();
            DapClient client = new DapClient(host);
            client.connect(adapter.port(), "pwa-node").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "pwa-node", "request", "launch")).get(10, TimeUnit.SECONDS);

            String refusal = host.next("the refusal");
            assertTrue(refusal.startsWith("error:Could not start the debug target: "), refusal);
            assertTrue(refusal.contains("Cannot find the target to attach to"), refusal);
            assertEquals("terminated", host.next("the end of a session with nothing left to debug"));
            assertEquals(0, client.childCount());
            client.dispose();
        }
    }

    @Test
    void aContinuedEventAndALostConnectionReachAHostThatOnlyKnowsTheBasics() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session only = launchAlone(adapter, client);

            only.continued(7, null);
            assertEquals("continued", host.next("the continued event"));

            adapter.close(); // the adapter is gone without a `terminated`
            assertEquals("terminated", host.next("the lost connection"));
            client.dispose();
        }
    }

    @Test
    void aLogpointIsSentWithItsMessageAndASessionNotYetConnectedSendsNothing() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            DapModels.FileBreakpoints logpoint = new DapModels.FileBreakpoints(
                    FILE,
                    List.of(
                            new DapModels.LineBreakpoint(4, "i > 2", "i is {i}"),
                            new DapModels.LineBreakpoint(6, null, " ")));
            assertNull(client.sendSetBreakpoints(logpoint).get(1, TimeUnit.SECONDS), "not connected: nothing to send");
            assertNull(client.sendSetBreakpoints(null).get(1, TimeUnit.SECONDS));
            assertFalse(client.supportsGotoTargets(), "no capabilities yet");

            FakeDebugAdapter.Session only = launchAlone(adapter, client);
            client.sendSetBreakpoints(logpoint).get(10, TimeUnit.SECONDS);

            var sent = only.breakpoints.get(only.breakpoints.size() - 1).getBreakpoints();
            assertEquals(5, sent[0].getLine());
            assertEquals("i > 2", sent[0].getCondition());
            assertEquals("i is {i}", sent[0].getLogMessage());
            assertNull(sent[1].getLogMessage(), "a blank message is no logpoint");
            client.dispose();
        }
    }

    @Test
    void aDisposedSessionOpensNoConnection() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            client.dispose();

            CompletableFuture<?> connecting = client.connect(adapter.port(), "java");

            assertThrows(CancellationException.class, () -> connecting.get(10, TimeUnit.SECONDS));
            assertEquals(0, adapter.sessionCount());
        }
    }

    @Test
    void aProgramTheHostCannotStartIsAnsweredWithTheHostsReason() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host() {
                @Override
                public CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
                    return CompletableFuture.failedFuture(new CompletionException(
                            new java.io.IOException("Cannot run program \"" + argv.get(0) + "\"")));
                }
            });
            client.setRunsDebuggee(true);
            FakeDebugAdapter.Session only = launchAlone(adapter, client);

            ExecutionException refused = assertThrows(
                    ExecutionException.class,
                    () -> only.runInTerminal("/work", List.of("nojava", "App"), Map.of())
                            .get(10, TimeUnit.SECONDS));

            assertTrue(
                    String.valueOf(refused.getCause().getMessage()).contains("Cannot run program \"nojava\""),
                    refused.getCause().toString());
            client.dispose();
        }
    }

    @Test
    void aHostThatStartsNoProgramsRefusesTheRequestAndAProcessIdTooLargeIsLeftOut() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient plain = new DapClient(new Host());
            plain.setRunsDebuggee(true);
            FakeDebugAdapter.Session only = launchAlone(adapter, plain);
            ExecutionException refused = assertThrows(
                    ExecutionException.class,
                    () -> only.runInTerminal("/work", List.of("java", "App"), Map.of())
                            .get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(refused.getCause().getMessage()).contains("runInTerminal"), refused.toString());
            plain.dispose();

            DapClient client = new DapClient(new Host() {
                @Override
                public CompletableFuture<Long> onRunInTerminal(String cwd, List<String> argv, Map<String, String> env) {
                    return CompletableFuture.completedFuture(argv.size() > 1 ? 5_000_000_000L : 4242L);
                }
            });
            client.setRunsDebuggee(true);
            FakeDebugAdapter.Session second = launchAlone(adapter, client);
            assertEquals(
                    4242,
                    second.runInTerminal("/work", List.of("java"), null)
                            .get(10, TimeUnit.SECONDS)
                            .getProcessId());
            assertNull(
                    second.runInTerminal("/work", List.of("java", "App"), Map.of())
                            .get(10, TimeUnit.SECONDS)
                            .getProcessId(),
                    "DAP's process id is an int");
            client.dispose();
        }
    }

    @Test
    void anExceptionIsNamedByItsShortTypeWhenTheAdapterGivesNoFullOne() {
        ExceptionInfoResponse response = new ExceptionInfoResponse();
        response.setExceptionId("id-17");
        response.setDescription("from the description");
        ExceptionDetails details = new ExceptionDetails();
        details.setFullTypeName(" ");
        details.setTypeName("ValueError");
        details.setMessage(" ");
        response.setDetails(details);

        DapModels.ExceptionInfo info = DapClient.exceptionInfo(response);

        assertEquals("ValueError", info.type());
        assertEquals("from the description", info.message());
        assertNull(DapClient.exceptionInfo(null));
        assertNull(DapClient.exceptionInfo(new ExceptionInfoResponse()), "an answer naming nothing is no answer");
    }

    // --- the adapter process (a /bin/sh stand-in: no debug adapter is started) ---------------------------

    private static Process standIn(String script) throws Exception {
        return new ProcessBuilder("/bin/sh", "-c", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in adapter process is /bin/sh
    void theAdapterProcessOfASocketSessionDiesWithTheSession() throws Exception {
        Process server = standIn("exec sleep 300");
        try {
            DapClient client = new DapClient(new Host());
            client.setAdapterProcess(server);
            assertTrue(server.isAlive());

            client.dispose();

            assertTrue(server.waitFor(20, TimeUnit.SECONDS), "the adapter process was killed");

            Process late = standIn("exec sleep 300");
            try {
                client.setAdapterProcess(late); // handed over after the session ended: not left running
                assertTrue(late.waitFor(20, TimeUnit.SECONDS));
            } finally {
                late.destroyForcibly();
            }
        } finally {
            server.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in adapter process is /bin/sh
    void anAdapterProcessThatExitsByItselfEndsTheSession() throws Exception {
        Host host = new Host();
        DapClient client = new DapClient(host);
        Process server = standIn("exit 3");
        try {
            client.setAdapterProcess(server);

            assertEquals("terminated", host.next("the adapter's exit"));
        } finally {
            client.dispose();
            server.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in adapter process is /bin/sh
    void aStdioSessionOwnsItsAdapterProcess() throws Exception {
        // Holds its pipes open like an adapter that is still starting: it never answers initialize.
        Process adapterProcess = standIn("exec sleep 300");
        try {
            Host host = new Host();
            DapClient client = new DapClient(host);
            CompletableFuture<?> initialized = client.connectStdio(adapterProcess, "python");
            assertFalse(initialized.isDone(), "nothing has answered yet");
            assertTrue(adapterProcess.isAlive());

            client.dispose();

            assertTrue(adapterProcess.waitFor(20, TimeUnit.SECONDS), "the adapter process was killed");
            assertTrue(host.events.isEmpty(), "a deliberate dispose is not reported as a lost adapter: " + host.events);

            Process late = standIn("exec sleep 300");
            try {
                CompletableFuture<?> refused = client.connectStdio(late, "python");
                assertThrows(CancellationException.class, () -> refused.get(10, TimeUnit.SECONDS));
                assertTrue(
                        late.waitFor(20, TimeUnit.SECONDS), "a process handed to an ended session is not left running");
            } finally {
                late.destroyForcibly();
            }
        } finally {
            adapterProcess.destroyForcibly();
        }
    }
}
