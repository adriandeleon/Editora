package com.editora.dap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link DapClient} sends and reads for java-debug's code-change and frame requests, over a real DAP
 * connection to {@link FakeDebugAdapter}: {@code redefineClasses} (hot code replace) and its {@code
 * hotcodereplace} event, {@code restartFrame}, and {@code stepInTargets} with a targeted {@code stepIn}.
 */
class DapClientCodeChangesTest {

    /** Records the hot code replace events the session reports. */
    private static final class Host implements DapClient.Host {
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
        public void onHotCodeReplace(String changeType, String message) {
            notices.add(changeType + ":" + message);
        }
    }

    private static FakeDebugAdapter.Session connect(FakeDebugAdapter adapter, DapClient client) throws Exception {
        client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
        return adapter.awaitSession();
    }

    @Test
    void redefineClassesReadsWhichClassesTheAdapterReplaced() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            try {
                FakeDebugAdapter.Session session = connect(adapter, client);
                session.redefineAnswer = Map.of("changedClasses", List.of("demo.Main", "demo.Main$Inner"));

                DapModels.CodeReplace done = client.redefineClasses().get(10, TimeUnit.SECONDS);

                // lsp4j drops the body of an answer to a request it has no type for: without the declared
                // answer type this was an empty replace, and Editora said "nothing changed" after replacing.
                assertEquals(List.of("demo.Main", "demo.Main$Inner"), done.changedClasses());
                assertFalse(done.failed());
                assertEquals(
                        List.of("redefineClasses"),
                        session.requests.stream()
                                .filter("redefineClasses"::equals)
                                .toList());
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void aReplaceJavaDebugCouldNotDoCarriesItsReason() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            try {
                FakeDebugAdapter.Session session = connect(adapter, client);
                session.redefineAnswer =
                        Map.of("changedClasses", List.of(), "errorMessage", "Failed to redefine classes: Add method");

                DapModels.CodeReplace done = client.redefineClasses().get(10, TimeUnit.SECONDS);

                assertTrue(done.failed());
                assertEquals("Failed to redefine classes: Add method", done.error());

                session.redefineFailure = "JVM doesn't support hot reload classes";
                ExecutionException refused = assertThrows(
                        ExecutionException.class, () -> client.redefineClasses().get(10, TimeUnit.SECONDS));
                assertTrue(String.valueOf(refused.getCause().getMessage()).contains("hot reload"));
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void theHotCodeReplaceEventReachesTheSession() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            Host host = new Host();
            DapClient client = new DapClient(host);
            try {
                FakeDebugAdapter.Session session = connect(adapter, client);

                session.hotCodeReplace("ERROR", "  Add method not implemented \n");

                String notice = host.notices.poll(10, TimeUnit.SECONDS);
                assertNotNull(notice, "the event was dropped (lsp4j logs an unsupported notification)");
                assertEquals("ERROR:Add method not implemented", notice);
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void restartFrameIsOfferedOnlyByAnAdapterThatCanAndNamesTheFrame() throws Exception {
        try (FakeDebugAdapter plain = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            try {
                connect(plain, client);
                assertFalse(client.supportsRestartFrame());
                assertFalse(client.supportsStepInTargets());
            } finally {
                client.dispose();
            }
        }
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.restartFrame = true;
            adapter.stepInTargets = true;
            DapClient client = new DapClient(new Host());
            try {
                FakeDebugAdapter.Session session = connect(adapter, client);
                assertTrue(client.supportsRestartFrame());
                assertTrue(client.supportsStepInTargets());

                client.restartFrame(42).get(10, TimeUnit.SECONDS);

                assertEquals(List.of(42), session.restartedFrames);
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void smartStepIntoListsTheCallsAndStepsIntoThePickedOne() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.stepInTargets = true;
            DapClient client = new DapClient(new Host());
            try {
                FakeDebugAdapter.Session session = connect(adapter, client);
                session.frameLine = 24;
                session.stepTargetLabels = List.of("add(twice(1),twice(2))", "twice(1)");

                List<DapModels.StepTarget> targets = client.stepInTargets(9).get(10, TimeUnit.SECONDS);

                assertEquals(
                        List.of(
                                new DapModels.StepTarget(100, "add(twice(1),twice(2))", 23),
                                new DapModels.StepTarget(101, "twice(1)", 23)),
                        targets);
                assertEquals(List.of(9), session.stepTargetFrames);

                client.stepIn(5, 101).get(10, TimeUnit.SECONDS);
                client.stepIn(5).get(10, TimeUnit.SECONDS);

                assertEquals(2, session.stepIns.size());
                assertEquals(101, session.stepIns.get(0).getTargetId());
                assertEquals(5, session.stepIns.get(0).getThreadId());
                assertNull(session.stepIns.get(1).getTargetId(), "plain Step Into names no target");
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void anAnswerReadsTheSameHoweverLsp4jHandsItOver() {
        assertEquals(
                new DapModels.CodeReplace(List.of("a.B"), ""),
                DapClient.codeReplace(JsonParser.parseString("{\"changedClasses\":[\"a.B\",\" \"]}")));
        assertEquals(
                new DapModels.CodeReplace(List.of(), "boom"),
                DapClient.codeReplace(Map.of("changedClasses", List.of(), "errorMessage", "boom")));
        assertEquals(new DapModels.CodeReplace(List.of(), ""), DapClient.codeReplace(null));
        assertEquals(new DapModels.CodeReplace(List.of(), ""), DapClient.codeReplace(JsonParser.parseString("3")));
    }
}
