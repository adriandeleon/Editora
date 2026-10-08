package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import com.editora.agent.AcpJson;
import com.editora.config.AgentSessionHistory;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The agent's permission prompt appears while the user is typing somewhere else. Whatever key is on its way
 * when the dialog takes focus must not be the answer, and the answer a stray key can give must not be "yes".
 */
@Tag("fx")
class AgentPermissionFxTest {

    private static final List<AcpJson.PermissionOption> ALLOW_FIRST = List.of(
            new AcpJson.PermissionOption("allow_always", "Always Allow", "allow_always"),
            new AcpJson.PermissionOption("allow", "Allow", "allow_once"),
            new AcpJson.PermissionOption("reject", "Reject", "reject_once"));

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theDialogOpensOnTheRejectingChoiceAndAKeystrokeInFlightApprovesNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), new Ops());
            coordinator.permissionClock = clock::get;
            async.onClose(coordinator::shutdown);
            async.onClose(AgentPermissionFxTest::hideDialogs);

            CompletableFuture<String> answer = coordinator.requestPermission("Run: rm -rf build/ src/", ALLOW_FIRST);
            DialogPane pane = awaitDialog();

            assertEquals("Reject", focusedButton(pane), "the agent listed 'Always Allow' first");
            // The keys the user was typing into the editor land here, and so can a click already under way.
            press(pane, KeyCode.ENTER);
            press(pane, KeyCode.SPACE);
            FxTestSupport.runOnFx(() -> button(pane, "Always Allow").fire());
            FxTestSupport.runOnFx(() -> button(pane, "Allow").fire());
            FxTestSupport.drainFx();
            assertFalse(answer.isDone(), "nothing typed or clicked as the dialog appeared may answer it");

            clock.addAndGet(AgentCoordinator.PERMISSION_GRACE_NANOS + 1);
            // A deliberate key now acts — on the focused, rejecting choice. Space, because that is the key
            // that presses a focused button everywhere: on macOS Enter only ever reaches a default button,
            // and this dialog has none on purpose.
            press(pane, KeyCode.SPACE);
            assertEquals("reject", answer.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void withoutARejectOptionCancelHasTheFocusAndApprovingStillWorksOnceArmed() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), new Ops());
            coordinator.permissionClock = clock::get;
            async.onClose(coordinator::shutdown);
            async.onClose(AgentPermissionFxTest::hideDialogs);
            List<AcpJson.PermissionOption> options = List.of(
                    new AcpJson.PermissionOption("allow_always", "Always Allow", "allow_always"),
                    new AcpJson.PermissionOption("allow", "Allow", "allow_once"),
                    new AcpJson.PermissionOption("never", "Never Allow", "reject_always"));

            CompletableFuture<String> answer = coordinator.requestPermission("Edit a file", options);
            DialogPane pane = awaitDialog();
            // A standing "never" is not something a stray key should set either: Cancel, which decides nothing.
            assertEquals(javafx.scene.control.ButtonType.CANCEL.getText(), focusedButton(pane));
            press(pane, KeyCode.SPACE);
            FxTestSupport.drainFx();
            assertFalse(answer.isDone());

            clock.addAndGet(AgentCoordinator.PERMISSION_GRACE_NANOS + 1);
            FxTestSupport.runOnFx(() -> button(pane, "Allow").fire());
            assertEquals("allow", answer.get(10, TimeUnit.SECONDS));

            CompletableFuture<String> second = coordinator.requestPermission("Edit a file", options);
            DialogPane again = awaitDialog();
            clock.addAndGet(AgentCoordinator.PERMISSION_GRACE_NANOS + 1);
            press(again, KeyCode.SPACE);
            assertNull(second.get(10, TimeUnit.SECONDS), "Cancel answers 'cancelled'");
        }
    }

    private static DialogPane awaitDialog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            DialogPane pane = FxTestSupport.callOnFx(() -> {
                for (Window w : Window.getWindows()) {
                    if (w.isShowing()
                            && w.getScene() != null
                            && w.getScene().getRoot() instanceof DialogPane dp
                            && w.getScene().getFocusOwner() instanceof Button) {
                        return dp;
                    }
                }
                return null;
            });
            if (pane != null) {
                FxTestSupport.drainFx(); // the focus request made as the dialog was shown has been applied
                return pane;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the permission dialog did not appear");
    }

    private static String focusedButton(DialogPane pane) throws Exception {
        return FxTestSupport.callOnFx(
                () -> pane.getScene().getFocusOwner() instanceof Button b ? b.getText() : "(no button)");
    }

    private static Button button(DialogPane pane, String text) {
        for (Node n : pane.lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
    }

    private static void press(DialogPane pane, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node target = pane.getScene().getFocusOwner();
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
        });
    }

    private static void hideDialogs() {
        FxTestSupport.runOnFxUnchecked(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane) {
                    w.hide();
                }
            }
        });
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
}
