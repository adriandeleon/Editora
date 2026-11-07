package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Threads view: every thread with its state, in place of the frames; Pause / Resume / Show Frames for each. */
@Tag("fx")
class DebugPanelThreadsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private List<DapModels.ThreadStatus> statuses = List.of(
            new DapModels.ThreadStatus(1, "main", true, "breakpoint", true),
            new DapModels.ThreadStatus(2, "worker", false, "", false),
            new DapModels.ThreadStatus(3, "Reference Handler", false, "", false),
            new DapModels.ThreadStatus(4, "pool-1-thread-1", true, "pause", false));

    private final List<String> calls = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private DebugPanel.Actions actions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "threadStatuses" -> {
                            calls.add("list");
                            ((Consumer<List<DapModels.ThreadStatus>>) a[0]).accept(statuses);
                        }
                        case "pauseThread" -> calls.add("pause " + a[0]);
                        case "resumeThread" -> calls.add("resume " + a[0]);
                        case "showThread" -> calls.add("show " + ((DapModels.ThreadStatus) a[0]).id());
                        default -> {}
                    }
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == List.class) {
                        return List.of();
                    }
                    return null;
                });
    }

    private static DebugPanel stopped(DebugPanel.Actions actions) {
        DebugPanel panel = new DebugPanel(actions);
        panel.setState(DapManager.State.STARTING);
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(List.of(new DapModels.StackFrameInfo(1, "Main.main", Path.of("Main.java"), 4, 1)));
        return panel;
    }

    private static List<String> menu(DebugPanel panel) {
        ListView<DapModels.ThreadStatus> list = panel.threadList();
        ContextMenu menu = list.getContextMenu();
        menu.getOnShowing().handle(null);
        return menu.getItems().stream().map(MenuItem::getText).toList();
    }

    private static MenuItem item(DebugPanel panel, String text) {
        return panel.threadList().getContextMenu().getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theThreadsViewListsEveryThreadInPlaceOfTheFrames() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            ListView<?> stack = FxTestSupport.field(panel, "stack");
            ComboBox<?> dropdown = FxTestSupport.field(panel, "threads");
            assertFalse(panel.threadsShown());
            assertTrue(stack.isVisible());

            panel.showThreadsView(true);
            assertTrue(panel.threadList().isVisible());
            assertFalse(stack.isVisible(), "in place of the frames");
            assertFalse(dropdown.isVisible(), "and of the thread dropdown");
            assertEquals(
                    List.of(1, 2, 4),
                    panel.threadList().getItems().stream()
                            .map(DapModels.ThreadStatus::id)
                            .toList(),
                    "the JVM's own running threads left out");

            // A state change lists them again.
            calls.clear();
            panel.setState(DapManager.State.RUNNING);
            assertEquals(List.of("list"), calls);

            panel.showThreadsView(false);
            assertTrue(stack.isVisible());
            assertTrue(dropdown.isVisible());
        });
    }

    @Test
    void eachThreadOffersWhatItsStateAllows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.showThreadsView(true);
            ListView<DapModels.ThreadStatus> list = panel.threadList();

            list.getSelectionModel().select(1); // worker, running
            assertEquals(List.of(tr("debugpanel.pauseThread")), menu(panel));
            item(panel, tr("debugpanel.pauseThread")).fire();

            list.getSelectionModel().select(2); // pool thread, suspended
            assertEquals(List.of(tr("debugpanel.showFrames"), tr("debugpanel.resumeThread")), menu(panel));
            item(panel, tr("debugpanel.resumeThread")).fire();
            item(panel, tr("debugpanel.showFrames")).fire();
            assertEquals(List.of("list", "pause 2", "resume 4", "show 4"), calls);
            assertFalse(panel.threadsShown(), "Show Frames goes back to the frames");
        });
    }

    @Test
    void theShownThreadIsNotShownAgainAndARunningOneHasNoFrames() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.showThreadsView(true);
            calls.clear();
            panel.threadList().getSelectionModel().select(0); // main, already shown
            menu(panel);
            item(panel, tr("debugpanel.showFrames")).fire();
            assertEquals(List.of(), calls, "nothing to fetch");
            assertFalse(panel.threadsShown());

            panel.showThreadsView(true);
            calls.clear();
            panel.threadList().getSelectionModel().select(1); // running
            panel.threadList()
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ENTER,
                            false,
                            false,
                            false,
                            false));
            assertEquals(List.of(), calls);
            assertTrue(panel.threadsShown(), "a running thread has no frames to go to");
        });
    }

    @Test
    void withNoSessionThereAreNoThreads() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.showThreadsView(true);
            panel.setState(DapManager.State.INACTIVE);
            assertTrue(panel.threadList().getItems().isEmpty());
        });
    }
}
