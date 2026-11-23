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
                        case "threadDump" -> calls.add("dump");
                        case "moreFrames" -> {
                            calls.add("more " + a[0]);
                            ((Consumer<DapModels.StackPage>) a[1])
                                    .accept(new DapModels.StackPage(
                                            List.of(
                                                    new DapModels.StackFrameInfo(
                                                            7, "Main.outer", Path.of("Main.java"), 9, 1),
                                                    new DapModels.StackFrameInfo(
                                                            8, "Main.main", Path.of("Main.java"), 2, 1)),
                                            4,
                                            false));
                        }
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
    void theThreadsToggleKeepsItsPlaceWhenClicked() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.StackPane(panel), 1600, 400);
            scene.getStylesheets().add(Themes.stylesheetFor("Editora Light"));
            scene.getStylesheets()
                    .add(DebugPanelThreadsFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            javafx.scene.control.ToggleButton toggle = FxTestSupport.field(panel, "threadsToggle");
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            javafx.geometry.Bounds before = toggle.localToScene(toggle.getBoundsInLocal());

            panel.showThreadsView(true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            javafx.geometry.Bounds after = toggle.localToScene(toggle.getBoundsInLocal());
            assertEquals(before.getMinY(), after.getMinY(), 0.5, "the toggle did not move up");
            assertEquals(before.getMaxX(), after.getMaxX(), 0.5, "nor sideways");
        });
    }

    @Test
    void eachThreadOffersWhatItsStateAllows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.showThreadsView(true);
            ListView<DapModels.ThreadStatus> list = panel.threadList();

            list.getSelectionModel().select(1); // worker, running
            // Every thread's menu ends with Thread Dump and Parallel Stacks, after a separator (a null text).
            assertEquals(
                    java.util.Arrays.asList(
                            tr("debugpanel.pauseThread"),
                            null,
                            tr("debugpanel.threadDump"),
                            tr("debugpanel.parallelStacks")),
                    menu(panel));
            item(panel, tr("debugpanel.pauseThread")).fire();

            list.getSelectionModel().select(2); // pool thread, suspended
            assertEquals(
                    java.util.Arrays.asList(
                            tr("debugpanel.showFrames"),
                            tr("debugpanel.resumeThread"),
                            null,
                            tr("debugpanel.threadDump"),
                            tr("debugpanel.parallelStacks")),
                    menu(panel));
            item(panel, tr("debugpanel.resumeThread")).fire();
            item(panel, tr("debugpanel.showFrames")).fire();
            item(panel, tr("debugpanel.threadDump")).fire();
            assertEquals(List.of("list", "pause 2", "resume 4", "show 4", "dump"), calls);
            assertFalse(panel.threadsShown(), "Show Frames goes back to the frames");
        });
    }

    /**
     * The commands behind the rows ({@link DebugRowCommands}) run the row the selected thread's menu offers, and
     * nothing where it offers none: Resume on a running thread is not there to run.
     */
    @Test
    void theThreadCommandsRunTheRowTheSelectedThreadOffers() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.showThreadsView(true);
            ListView<DapModels.ThreadStatus> list = panel.threadList();
            calls.clear();

            list.getSelectionModel().select(1); // worker, running
            assertFalse(panel.runMenuRow("debug.resumeSelectedThread"), "not offered for a running thread");
            assertTrue(panel.runMenuRow("debug.pauseSelectedThread"));

            list.getSelectionModel().select(2); // pool thread, suspended
            assertTrue(panel.runMenuRow("debug.resumeSelectedThread"));
            assertFalse(panel.runMenuRow("debug.historyLive"), "no earlier stop is shown");
            assertEquals(List.of("pause 2", "resume 4"), calls);

            // Each row of the menu names the command that runs it.
            menu(panel);
            for (MenuItem row : list.getContextMenu().getItems()) {
                if (!(row instanceof javafx.scene.control.SeparatorMenuItem)) {
                    assertTrue(com.editora.editor.MenuChords.commandOf(row) != null, row.getText() + " has a command");
                }
            }
        });
    }

    /** Copy Call Stack ({@code debug.copyCallStack}) is offered once there are frames, and copies them. */
    @Test
    void copyCallStackRunsWhereTheCallStacksMenuOffersIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel idle = new DebugPanel(actions());
            assertFalse(idle.runMenuRow("debug.copyCallStack"), "no frames: the menu has no Copy Call Stack");

            DebugPanel panel = stopped(actions());
            javafx.scene.input.ClipboardContent marker = new javafx.scene.input.ClipboardContent();
            marker.putString("before");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(marker);
            assertTrue(panel.runMenuRow("debug.copyCallStack"));
            assertTrue(
                    javafx.scene.input.Clipboard.getSystemClipboard()
                            .getString()
                            .contains("Main.main"),
                    "the frames are on the clipboard");
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

    /** A busy pool's thread events must not keep the Threads view from ever being listed again (perf review D13). */
    @Test
    void threadChurnDoesNotPutTheThreadsViewsRefreshOffForever() throws Exception {
        DebugPanel[] panel = {null};
        FxTestSupport.runOnFx(() -> {
            panel[0] = stopped(actions());
            panel[0].showThreadsView(true);
            calls.clear();
            panel[0].threadsChanged();
        });
        javafx.animation.PauseTransition refresh = FxTestSupport.field(panel[0], "threadsRefresh");
        // A thread event every 10 ms, for as long as it takes.
        javafx.animation.Timeline churn = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(10), e -> panel[0].threadsChanged()));
        churn.setCycleCount(javafx.animation.Animation.INDEFINITE);
        FxTestSupport.runOnFx(churn::play);
        try {
            com.editora.testkit.Await.until(
                    () -> {
                        try {
                            return FxTestSupport.callOnFx(() -> !calls.isEmpty());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    "listed again while the events kept coming",
                    java.time.Duration.ofSeconds(5));
        } finally {
            FxTestSupport.runOnFx(() -> {
                churn.stop();
                refresh.stop();
            });
        }
    }

    /** A stop lists the top of a deep stack and a row that lists the frames below when chosen (perf review D3). */
    @Test
    void theMoreFramesRowListsTheFramesBelow() throws Exception {
        DebugPanel[] panel = {null};
        FxTestSupport.runOnFx(() -> {
            panel[0] = new DebugPanel(actions());
            panel[0].setState(DapManager.State.STARTING);
            panel[0].setState(DapManager.State.SUSPENDED);
            panel[0].setCallStack(
                    List.of(
                            new DapModels.StackFrameInfo(1, "Main.inner", Path.of("Main.java"), 4, 1),
                            new DapModels.StackFrameInfo(2, "Main.middle", Path.of("Main.java"), 6, 1)),
                    0,
                    4,
                    true);
            ListView<DapModels.StackFrameInfo> stack = FxTestSupport.field(panel[0], "stack");
            assertEquals(3, stack.getItems().size());
            assertTrue(StackFolding.isMore(stack.getItems().get(2)));
            assertEquals(2, stack.getItems().get(2).line(), "two more frames");
            calls.clear();
            stack.getSelectionModel().select(2);
        });
        FxTestSupport.runOnFx(() -> {}); // the row's runLater
        FxTestSupport.runOnFx(() -> {
            ListView<DapModels.StackFrameInfo> stack = FxTestSupport.field(panel[0], "stack");
            assertEquals(List.of("more 2"), calls);
            assertEquals(
                    List.of(1, 2, 7, 8),
                    stack.getItems().stream().map(DapModels.StackFrameInfo::id).toList(),
                    "the frames below, and no more row");
            assertEquals(1, stack.getSelectionModel().getSelectedItem().id(), "the frame selected stays selected");
            assertEquals(4, panel[0].callStack().size());
        });
    }
}
