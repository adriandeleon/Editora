package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointNotVerifiedReason;
import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the adapter says about a breakpoint used to be thrown away with the {@code setBreakpoints} response
 * ({@code thenApply(r -> null)}), and its {@code breakpoint} events, its notices about a condition it could
 * not evaluate and the exception behind an exception stop were never read at all. These go through a real
 * socket to a scripted adapter, so the wire shapes — including java-debug's non-standard
 * {@code usernotification} — are the ones the client has to parse.
 */
class DapClientBreakpointStatusTest {

    private static final Path FILE = Path.of("/work/Main.java");

    private record Report(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {}

    private static final class RecordingHost implements DapClient.Host {
        final BlockingQueue<Report> reports = new LinkedBlockingQueue<>();
        final BlockingQueue<String> notices = new LinkedBlockingQueue<>();

        @Override
        public void onStopped(int threadId, String reason) {}

        @Override
        public void onContinued() {}

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {}

        @Override
        public void onError(String message) {}

        @Override
        public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
            reports.add(new Report(file, List.copyOf(statuses), whole));
        }

        @Override
        public void onNotice(String message, boolean error) {
            notices.add((error ? "error:" : "warning:") + message);
        }
    }

    private static DapModels.FileBreakpoints lines(int... lines0) {
        List<DapModels.LineBreakpoint> list = new ArrayList<>();
        for (int line : lines0) {
            list.add(new DapModels.LineBreakpoint(line, null, null));
        }
        return new DapModels.FileBreakpoints(FILE, list);
    }

    private static <T> T next(BlockingQueue<T> queue, String what) throws InterruptedException {
        T value = queue.poll(10, TimeUnit.SECONDS);
        assertNotNull(value, "never received " + what);
        return value;
    }

    private static FakeDebugAdapter.Session connect(FakeDebugAdapter adapter, DapClient client) throws Exception {
        client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session session = adapter.awaitSession();
        assertTrue(session.configured.await(10, TimeUnit.SECONDS));
        return session;
    }

    @Test
    void theAnswerToSetBreakpointsIsReportedForEachRequestedLine() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session session = connect(adapter, client);
            Breakpoint failed = FakeDebugAdapter.Session.breakpoint(5, false, 31, "Breakpoint added to invalid line.");
            failed.setReason(BreakpointNotVerifiedReason.FAILED);
            session.breakpointAnswer = args -> List.of(
                    FakeDebugAdapter.Session.breakpoint(1, true, 8, null),
                    FakeDebugAdapter.Session.breakpoint(2, false, 10, ""), // java-debug: no reason given
                    FakeDebugAdapter.Session.breakpoint(3, false, null, "Unbound breakpoint"), // js-debug
                    FakeDebugAdapter.Session.breakpoint(4, true, 21, null), // bound to the next line with code
                    failed);

            client.sendSetBreakpoints(lines(7, 9, 14, 19, 30)).get(10, TimeUnit.SECONDS);

            Report report = next(host.reports, "the setBreakpoints answer");
            assertEquals(FILE, report.file());
            assertTrue(report.whole(), "an answer stands for all of the file's breakpoints");
            assertEquals(
                    List.of(
                            new DapModels.BreakpointStatus(7, true, false, "", 7),
                            new DapModels.BreakpointStatus(9, false, false, "", 9),
                            new DapModels.BreakpointStatus(14, false, false, "Unbound breakpoint", -1),
                            new DapModels.BreakpointStatus(19, true, false, "", 20),
                            new DapModels.BreakpointStatus(30, false, true, "Breakpoint added to invalid line.", 30)),
                    report.statuses());
            assertTrue(report.statuses().get(1).pending(), "unverified without a reason is waiting, not refused");
            assertTrue(
                    report.statuses().get(2).pending(),
                    "a message alone does not make it refused: adapters describe the wait there too");
            assertFalse(report.statuses().get(4).pending());
            client.dispose();
        }
    }

    @Test
    void aBreakpointEventIsMatchedToTheLineItWasRequestedOnByItsId() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session session = connect(adapter, client);
            session.breakpointAnswer = args -> List.of(
                    FakeDebugAdapter.Session.breakpoint(41, false, 8, null),
                    FakeDebugAdapter.Session.breakpoint(42, false, 13, null));
            client.sendSetBreakpoints(lines(7, 12)).get(10, TimeUnit.SECONDS);
            next(host.reports, "the setBreakpoints answer");

            // java-debug, once the class is loaded: the event has the id and no source.
            session.breakpointChanged(FakeDebugAdapter.Session.breakpoint(42, true, 13, ""));

            Report event = next(host.reports, "the breakpoint event");
            assertEquals(FILE, event.file());
            assertFalse(event.whole(), "an event changes one breakpoint and leaves the others as they were");
            assertEquals(List.of(new DapModels.BreakpointStatus(12, true, false, "", 12)), event.statuses());
            client.dispose();
        }
    }

    @Test
    void aRefusedRequestRejectsEveryBreakpointWithWhatTheAdapterSaid() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session session = connect(adapter, client);
            session.breakpointFailure = "Failed to setBreakpoint. Reason: '/work/Main.java' is an invalid path.";

            client.sendSetBreakpoints(lines(3, 4)).handle((r, e) -> null).get(10, TimeUnit.SECONDS);

            Report report = next(host.reports, "the refusal");
            assertEquals(2, report.statuses().size());
            for (DapModels.BreakpointStatus status : report.statuses()) {
                assertTrue(status.failed());
                assertEquals(session.breakpointFailure, status.message());
            }
            client.dispose();
        }
    }

    @Test
    void theSessionThatOnlyStartsChildSessionsDoesNotAnswerForTheBreakpoints() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(true)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            client.connect(adapter.port(), "pwa-node").get(10, TimeUnit.SECONDS);
            client.launch(Map.of("type", "pwa-node", "request", "launch", "program", FILE.toString()))
                    .get(10, TimeUnit.SECONDS);
            FakeDebugAdapter.Session root = adapter.awaitSession();
            FakeDebugAdapter.Session child = adapter.awaitSession();
            assertTrue(child.configured.await(10, TimeUnit.SECONDS));
            root.startDebuggingAnswered.get(10, TimeUnit.SECONDS);
            // js-debug's first connection debugs nothing and calls everything unbound; the child knows.
            root.breakpointAnswer =
                    args -> List.of(FakeDebugAdapter.Session.breakpoint(1, false, null, "Unbound breakpoint"));
            child.breakpointAnswer = args -> List.of(FakeDebugAdapter.Session.breakpoint(9, true, 3, null));

            client.sendSetBreakpoints(lines(2)).get(10, TimeUnit.SECONDS);

            Report report = next(host.reports, "the child session's answer");
            assertEquals(List.of(new DapModels.BreakpointStatus(2, true, false, "", 2)), report.statuses());
            root.awaitDelivered();
            assertNull(host.reports.poll(300, TimeUnit.MILLISECONDS), "the root session's answer must not be shown");
            client.dispose();
        }
    }

    @Test
    void aUserNotificationIsPassedOn() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            RecordingHost host = new RecordingHost();
            DapClient client = new DapClient(host);
            FakeDebugAdapter.Session session = connect(adapter, client);

            session.userNotification("ERROR", "Breakpoint condition 'nosuch > 1' error: nosuch cannot be resolved");

            assertEquals(
                    "error:Breakpoint condition 'nosuch > 1' error: nosuch cannot be resolved",
                    next(host.notices, "the notification"));
            client.dispose();
        }
    }

    @Test
    void theExceptionOfAStopIsAskedForWhenTheAdapterCanSay() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            ExceptionInfoResponse info = new ExceptionInfoResponse();
            info.setExceptionId("java.lang.IllegalStateException");
            info.setDescription("boom");
            adapter.exceptionInfo = info;
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session session = connect(adapter, client);

            DapModels.ExceptionInfo answered = client.exceptionInfo(7).get(10, TimeUnit.SECONDS);

            assertEquals(new DapModels.ExceptionInfo("java.lang.IllegalStateException", "boom"), answered);
            assertTrue(session.requests.contains("exceptionInfo"));
            client.dispose();
        }
    }

    @Test
    void anAdapterWithoutExceptionInfoIsNotAskedAndTheStopTextIsUsed() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new RecordingHost());
            FakeDebugAdapter.Session session = connect(adapter, client);
            assertNull(client.exceptionInfo(7).get(10, TimeUnit.SECONDS), "nothing is known about this stop");

            session.stop(7, "exception", "Paused on exception", "ValueError: bad input");
            session.awaitDelivered();

            assertEquals(
                    new DapModels.ExceptionInfo("", "ValueError: bad input"),
                    client.exceptionInfo(7).get(10, TimeUnit.SECONDS));
            assertFalse(session.requests.contains("exceptionInfo"), "it did not say it supports the request");
            client.dispose();
        }
    }

    @Test
    void theDetailsOfAnExceptionInfoAnswerWinOverItsIdAndDescription() {
        ExceptionInfoResponse info = new ExceptionInfoResponse();
        info.setExceptionId("IllegalStateException");
        info.setDescription("java.lang.IllegalStateException: boom");
        ExceptionDetails details = new ExceptionDetails();
        details.setFullTypeName("java.lang.IllegalStateException");
        details.setMessage("boom");
        info.setDetails(details);

        assertEquals(
                new DapModels.ExceptionInfo("java.lang.IllegalStateException", "boom"), DapClient.exceptionInfo(info));
        assertNull(DapClient.exceptionInfo(new ExceptionInfoResponse()), "an empty answer says nothing");
        assertNull(DapClient.exceptionInfo(null));
    }
}
