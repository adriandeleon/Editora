package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Variables filter, Go to Index in a long container, and the hexadecimal toggle. */
@Tag("fx")
class DebugPanelFilterFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final int LOCALS = 1;
    private static final int ITEMS = 2;
    private static final int SIZE = 500;

    private boolean hex;
    private final List<Boolean> hexAsked = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private DebugPanel.Actions actions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "loadChildren" ->
                            ((Consumer<List<DapModels.VariableInfo>>) a[1]).accept(children((int) a[0]));
                        case "loadChildrenPage" -> {
                            int start = (int) a[2];
                            int count = (int) a[3];
                            List<DapModels.VariableInfo> page = new ArrayList<>();
                            for (int i = start; i < Math.min(SIZE, start + count); i++) {
                                page.add(variable("[" + i + "]", Integer.toString(i), 0, 0));
                            }
                            ((Consumer<List<DapModels.VariableInfo>>) a[4]).accept(page);
                        }
                        case "hexValues" -> {
                            return hex;
                        }
                        case "setHexValues" -> {
                            hex = (boolean) a[0];
                            hexAsked.add(hex);
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

    private static List<DapModels.VariableInfo> children(int ref) {
        return ref == LOCALS
                ? List.of(
                        variable("alpha", "1", 0, 0),
                        variable("beta", "2", 0, 0),
                        variable("items", "ArrayList@7 size=500", ITEMS, SIZE))
                : List.of();
    }

    private static DapModels.VariableInfo variable(String name, String value, int ref, int indexed) {
        return new DapModels.VariableInfo(name, value, "", ref, 0, indexed, false, null);
    }

    private static DebugPanel stopped(DebugPanel.Actions actions) {
        DebugPanel panel = new DebugPanel(actions);
        panel.setState(DapManager.State.STARTING);
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(List.of(new DapModels.StackFrameInfo(1, "Main.main", Path.of("Main.java"), 4, 1)));
        panel.setScopes(List.of(new DapModels.ScopeInfo("Local", LOCALS, false)));
        return panel;
    }

    private static TreeItem<DebugPanel.VarRow> locals(DebugPanel panel) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            if (top.getValue() != null && "Local".equals(top.getValue().name())) {
                return top;
            }
        }
        throw new AssertionError("no Local scope");
    }

    private static List<String> names(TreeItem<DebugPanel.VarRow> item) {
        return item.getChildren().stream().map(c -> c.getValue().name()).toList();
    }

    @Test
    void theFilterNarrowsEachScopeToTheNamesThatContainIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            assertEquals(List.of("alpha", "beta", "items"), names(locals(panel)));

            panel.variablesFilter().setText("AL");
            assertEquals(List.of("alpha"), names(locals(panel)), "any case");
            panel.variablesFilter().setText("zzz");
            assertEquals(List.of(), names(locals(panel)));
            panel.variablesFilter().clear();
            assertEquals(List.of("alpha", "beta", "items"), names(locals(panel)), "all back, in their order");

            // It holds for the next stop's rows as they arrive, and leaves Watches alone.
            panel.variablesFilter().setText("bet");
            panel.setState(DapManager.State.RUNNING);
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(List.of(new DapModels.StackFrameInfo(1, "Main.main", Path.of("Main.java"), 5, 1)));
            panel.setScopes(List.of(new DapModels.ScopeInfo("Local", LOCALS, false)));
            assertEquals(List.of("beta"), names(locals(panel)));
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            TreeItem<DebugPanel.VarRow> watches = tree.getRoot().getChildren().get(0);
            assertFalse(watches.getChildren().isEmpty(), "Watches keeps its Add Watch row");
        });
    }

    @Test
    void goToIndexShowsALongContainerFromThatElement() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            TreeItem<DebugPanel.VarRow> items = locals(panel).getChildren().get(2);
            assertFalse(DebugPanel.offersGoToIndex(items), "not before it is open");
            items.setExpanded(true);
            assertEquals("[0]", items.getChildren().get(0).getValue().name());
            assertTrue(DebugPanel.offersGoToIndex(items));

            panel.goToIndex(items, 250);
            assertEquals("[250]", items.getChildren().get(0).getValue().name());
            assertEquals("[349]", items.getChildren().get(99).getValue().name());
            assertEquals(
                    DebugPanel.Kind.MORE,
                    items.getChildren().get(100).getValue().kind(),
                    "and Show more after");

            panel.goToIndex(items, SIZE); // past the end: nothing changes
            assertEquals("[250]", items.getChildren().get(0).getValue().name());
            assertFalse(DebugPanel.offersGoToIndex(locals(panel).getChildren().get(0)), "alpha is no container");
        });
    }

    @Test
    void theHexadecimalToggleAsksForTheOtherFormat() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = stopped(actions());
            panel.toggleHexValues();
            panel.toggleHexValues();
            assertEquals(List.of(true, false), hexAsked);
        });
    }
}
