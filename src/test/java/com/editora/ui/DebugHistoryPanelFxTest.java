package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Debug window going back through its stops (Debug History), without an adapter. */
@Tag("fx")
class DebugHistoryPanelFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What the next loadChildren answers with. */
    private List<DapModels.VariableInfo> locals = List.of();

    /** The actions' calls the tests read: {@code "show:<stop>:<frame>"}, {@code "leave"}, {@code "pick"}. */
    private final List<String> calls = new ArrayList<>();

    private List<DebugHistory.Seen> picked = List.of();
    private IntConsumer pick;

    @SuppressWarnings("unchecked")
    private DebugPanel.Actions actions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "loadChildren" -> ((Consumer<List<DapModels.VariableInfo>>) a[1]).accept(locals);
                        case "showHistoryFrame" -> calls.add("show:" + ((DebugHistory.Stop) a[0]).number + ":" + a[1]);
                        case "leaveHistory" -> calls.add("leave");
                        case "pickValueHistory" -> {
                            calls.add("pick:" + a[0]);
                            picked = (List<DebugHistory.Seen>) a[1];
                            pick = (IntConsumer) a[2];
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

    private static DapModels.VariableInfo variable(String name, String value) {
        return new DapModels.VariableInfo(name, value, "int", 0, 0, 0, false, null);
    }

    private static List<DapModels.StackFrameInfo> stack(int line) {
        return List.of(
                new DapModels.StackFrameInfo(2, "Main.work", Path.of("Main.java"), line, 1),
                new DapModels.StackFrameInfo(1, "Main.main", Path.of("Main.java"), 20, 1));
    }

    private void stopAt(DebugPanel panel, int line, DapModels.VariableInfo... vars) {
        locals = List.of(vars);
        panel.setState(DapManager.State.RUNNING);
        panel.setState(DapManager.State.SUSPENDED);
        panel.setStopReason("step");
        panel.setStoppedThread(1);
        panel.setCallStack(stack(line));
        panel.setScopes(List.of(new DapModels.ScopeInfo("Local", 1, false)));
    }

    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        throw new AssertionError("no row " + name);
    }

    private static String value(DebugPanel panel, String name) {
        return row(panel, name).getValue().value();
    }

    private static boolean bannerShown(DebugPanel panel) {
        return FxTestSupport.<HBox>field(panel, "historyBanner").isVisible();
    }

    private static boolean disabled(DebugPanel panel, String button) {
        return FxTestSupport.<Button>field(panel, button).isDisable();
    }

    @Test
    void backAndForwardGoThroughTheStopsAsTheyWereShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.setState(DapManager.State.STARTING);
            stopAt(panel, 4, variable("i", "0"), variable("total", "10"));
            assertTrue(disabled(panel, "historyBack"), "one stop, the live one: nowhere to go back to");
            stopAt(panel, 5, variable("i", "1"), variable("total", "10"));
            stopAt(panel, 6, variable("i", "2"), variable("total", "12"));
            assertEquals(3, panel.history().size());
            assertFalse(disabled(panel, "historyBack"));
            assertTrue(disabled(panel, "historyForward"), "the live stop is the newest");

            panel.historyBack();
            DebugHistory.Stop two = panel.viewing();
            assertNotNull(two);
            assertEquals(2, two.number);
            assertTrue(bannerShown(panel));
            assertEquals("1", value(panel, "i"));
            TreeItem<DebugPanel.VarRow> i = row(panel, "i");
            TreeItem<DebugPanel.VarRow> total = row(panel, "total");
            assertTrue(panel.changed(i, i.getValue()), "0 at stop 1, 1 at stop 2");
            assertFalse(panel.changed(total, total.getValue()));
            ListView<DapModels.StackFrameInfo> frames = FxTestSupport.field(panel, "stack");
            assertEquals(5, frames.getItems().getFirst().line(), "the stop's own call stack");
            assertEquals(List.of("show:2:0"), calls, "the editor shows that stop's line");

            frames.getSelectionModel().select(1); // main at stop 2: no values were shown for it
            assertEquals("show:2:1", calls.getLast());
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            assertEquals(
                    tr("debugpanel.history.noValues"),
                    tree.getRoot().getChildren().getFirst().getValue().name());

            panel.historyBack();
            assertEquals(1, panel.viewing().number);
            assertTrue(disabled(panel, "historyBack"), "the first stop kept");
            panel.historyBack(); // nothing before it
            assertEquals(1, panel.viewing().number);

            panel.historyForward();
            assertEquals(2, panel.viewing().number);
            panel.historyForward(); // the next is the live stop
            assertNull(panel.viewing());
            assertFalse(bannerShown(panel));
            assertEquals("leave", calls.getLast());
            assertEquals("2", value(panel, "i"), "the live values again");
            assertEquals(6, frames.getItems().getFirst().line());
        });
    }

    @Test
    void showValueHistoryFollowsARowAndGoesBackToAStop() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.setState(DapManager.State.STARTING);
            stopAt(panel, 4, variable("total", "10"));
            stopAt(panel, 5, variable("total", "10"));
            stopAt(panel, 6, variable("total", "12"));
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            tree.getSelectionModel().select(row(panel, "total"));
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
            FxTestSupport.call(panel, "rebuildVarsMenu", new Class<?>[] {javafx.scene.control.ContextMenu.class}, menu);
            javafx.scene.control.MenuItem show = menu.getItems().stream()
                    .filter(m -> tr("debugpanel.history.valueHistory").equals(m.getText()))
                    .findFirst()
                    .orElseThrow();
            show.fire();
            assertEquals("pick:" + tr("debugpanel.history.valueHistoryTitle", "total"), calls.getLast());
            assertEquals(
                    List.of("10", "10", "12"),
                    picked.stream().map(DebugHistory.Seen::value).toList());
            assertEquals(
                    List.of(false, false, true),
                    picked.stream().map(DebugHistory.Seen::changed).toList());

            pick.accept(1);
            assertEquals(1, panel.viewing().number);
            assertEquals("10", value(panel, "total"));
            // At an earlier stop the menu only reads: copy, and the value's history.
            tree.getSelectionModel().select(row(panel, "total"));
            FxTestSupport.call(panel, "rebuildVarsMenu", new Class<?>[] {javafx.scene.control.ContextMenu.class}, menu);
            assertEquals(
                    List.of(
                            tr("debugpanel.copyValue"),
                            tr("debugpanel.copyName"),
                            tr("debugpanel.history.valueHistory")),
                    menu.getItems().stream()
                            .map(javafx.scene.control.MenuItem::getText)
                            .toList());
            tree.getSelectionModel().clearSelection();
            FxTestSupport.call(panel, "rebuildVarsMenu", new Class<?>[] {javafx.scene.control.ContextMenu.class}, menu);
            assertEquals(
                    List.of(tr("debugpanel.history.live")),
                    menu.getItems().stream()
                            .map(javafx.scene.control.MenuItem::getText)
                            .toList());

            pick.accept(3); // the live stop
            assertNull(panel.viewing());
            pick.accept(42); // forgotten, or never kept
            assertNull(panel.viewing());
        });
    }

    @Test
    void theStopsStayAfterTheSessionUntilTheNextOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.setState(DapManager.State.STARTING);
            stopAt(panel, 4, variable("i", "0"));
            stopAt(panel, 5, variable("i", "1"));
            panel.historyBack();
            assertEquals(1, panel.viewing().number);
            panel.setState(DapManager.State.RUNNING); // the program goes on: the live state is shown
            assertNull(panel.viewing());

            panel.setState(DapManager.State.INACTIVE);
            assertFalse(disabled(panel, "historyBack"), "the ended session's stops can be read");
            panel.historyBack();
            assertEquals(2, panel.viewing().number, "after the session, Back starts at its last stop");
            assertEquals("1", value(panel, "i"));
            panel.historyForward(); // nothing after the last stop, and no live one
            assertEquals(2, panel.viewing().number);
            panel.historyBack();
            panel.setState(DapManager.State.INACTIVE); // a second "ended" keeps the stop shown
            assertEquals(1, panel.viewing().number);
            panel.leaveHistory();
            assertNull(panel.viewing());
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            assertEquals(
                    tr("debugpanel.watches"),
                    tree.getRoot().getChildren().getFirst().getValue().name());

            panel.setState(DapManager.State.STARTING);
            assertTrue(panel.history().isEmpty(), "a new session starts a new history");
            assertTrue(disabled(panel, "historyBack"));
        });
    }

    @Test
    void anOpenedRowKeepsItsChildrenAndAClosedOneSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.setState(DapManager.State.STARTING);
            stopAt(panel, 4, variable("i", "0"));
            DebugHistory h = panel.history();
            h.record(0, List.of("Local", "items"), "List(1)", "List", true);
            h.record(0, List.of("Local", "items", "[0]"), "\"pencil\"", "String", false);
            h.record(0, List.of("Local", "shop"), "Shop", "Shop", true);
            h.record(0, List.of(tr("debugpanel.watches"), "i + 1"), "1", "int", false);
            h.recordException("IllegalStateException: boom");
            stopAt(panel, 5, variable("i", "1"));
            panel.historyBack();
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            List<TreeItem<DebugPanel.VarRow>> top = tree.getRoot().getChildren();
            assertEquals(DebugPanel.Kind.EXCEPTION, top.getFirst().getValue().kind());
            TreeItem<DebugPanel.VarRow> items = row(panel, "items");
            assertTrue(items.isExpanded());
            assertEquals("\"pencil\"", items.getChildren().getFirst().getValue().value());
            assertEquals(
                    tr("debugpanel.history.notOpened"),
                    row(panel, "shop").getChildren().getFirst().getValue().name());
            assertEquals(DebugPanel.Kind.WATCH, row(panel, "i + 1").getValue().kind());
            assertTrue(DebugPanelText.historyLabel(panel.viewing())
                    .startsWith(tr("debugpanel.history.label", 1, "").strip().substring(0, 4)));
            assertTrue(DebugPanelText.historyBanner(panel.viewing()).contains("Main.work"));
            assertTrue(DebugPanelText.historyEntry(panel.viewing()).contains("Main.java:5"));
        });
    }

    @Test
    void withNoStopsKeptThereIsNothingToGoBackTo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.history().setCapacity(0);
            panel.setState(DapManager.State.STARTING);
            stopAt(panel, 4, variable("i", "0"));
            stopAt(panel, 5, variable("i", "1"));
            assertTrue(panel.history().isEmpty());
            assertTrue(disabled(panel, "historyBack"));
            panel.historyBack();
            assertNull(panel.viewing());
            assertSame(null, panel.viewing());
        });
    }
}
