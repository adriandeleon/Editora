package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import com.editora.agent.AcpJson;
import com.editora.config.AgentSessionHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent events reach the FX thread in batches. A reply streams as hundreds of small chunks from the reader
 * thread; each used to be queued as its own FX task. The order they arrive in must survive the batching.
 */
@Tag("fx")
class AgentInboxFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();

        @Override
        public Settings settings() {
            return settings;
        }
    }

    private static final class Ops implements AgentCoordinator.Ops {
        @Override
        public Path projectRoot() {
            return null;
        }

        @Override
        public EditorBuffer bufferForPath(String path) {
            return null;
        }

        @Override
        public void toggleToolWindow() {}

        @Override
        public void openToolWindow(boolean focus) {}

        @Override
        public void closeToolWindow() {}

        @Override
        public void setToolWindowAvailable(boolean available) {}

        @Override
        public void refreshProjectTree() {}

        @Override
        public void openBackgroundBuffer(Path target) {}

        @Override
        public void openPath(Path file) {}

        @Override
        public void rememberSession(
                String sessionId, String cwd, String candidateLabel, long updatedAt, String agentId) {}

        @Override
        public ObservableList<AgentSessionHistory.Entry> sessionHistory() {
            return FXCollections.observableArrayList();
        }
    }

    private static AcpJson.Update update(AcpJson.UpdateKind kind, String text) {
        return new AcpJson.Update("s", kind, text, List.of());
    }

    private static String textOf(Node node) {
        if (node instanceof Text text) {
            return text.getText();
        }
        if (node instanceof Labeled labeled) {
            return labeled.getText();
        }
        StringBuilder out = new StringBuilder();
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                out.append(textOf(child));
            }
        }
        return out.toString();
    }

    @Test
    void aBurstOfChunksIsAppliedByOneFxTaskInArrivalOrder() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            AgentCoordinator coordinator = new AgentCoordinator(new Host(), new Ops());
            async.onClose(coordinator::shutdown);
            AgentPanel panel = FxTestSupport.callOnFx(coordinator::panel);

            // Hold the FX thread, as a busy editor would, while the reader thread delivers a reply.
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Platform.runLater(() -> {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            async.await(entered, "the FX thread to be held");
            try {
                for (int i = 0; i < 300; i++) {
                    coordinator.onUpdate(update(AcpJson.UpdateKind.AGENT_MESSAGE, "a" + i + " "));
                }
                coordinator.onUpdate(update(AcpJson.UpdateKind.TOOL_CALL, "ls -la"));
                for (int i = 0; i < 300; i++) {
                    coordinator.onUpdate(update(AcpJson.UpdateKind.AGENT_MESSAGE, "b" + i + " "));
                }
            } finally {
                release.countDown();
            }
            async.awaitFx();

            int drains = FxTestSupport.callOnFx(coordinator::inboxDrains);
            List<String> entries = FxTestSupport.callOnFx(() -> {
                panel.setBusy(false); // finish the streaming message so all of it is rendered
                VBox transcript = FxTestSupport.field(panel, "transcriptBox");
                List<String> texts = new ArrayList<>();
                for (Node entry : transcript.getChildren()) {
                    texts.add(textOf(entry));
                }
                return texts;
            });

            assertEquals(1, drains, "601 updates that arrived while the FX thread was busy share one task");
            assertEquals(3, entries.size(), "reply text, the tool call, reply text: " + entries);
            assertTrue(entries.get(0).startsWith("a0 a1 ") && entries.get(0).contains("a299"), entries.get(0));
            assertTrue(entries.get(1).contains("ls -la"), entries.get(1));
            assertTrue(entries.get(2).startsWith("b0 b1 ") && entries.get(2).contains("b299"), entries.get(2));
        }
    }
}
