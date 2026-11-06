package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.text.TextFlow;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Variables tree marks the values that changed since the previous stop in the same frame. */
@Tag("fx")
class DebugPanelChangedValuesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What the next loadChildren answers with. */
    private List<DapModels.VariableInfo> locals = List.of();

    @SuppressWarnings("unchecked")
    private DebugPanel.Actions actions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    if (m.getName().equals("loadChildren")) {
                        ((Consumer<List<DapModels.VariableInfo>>) a[1]).accept(locals);
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

    private static final List<DapModels.StackFrameInfo> STACK =
            List.of(new DapModels.StackFrameInfo(1, "Main.main", Path.of("Main.java"), 4, 1));

    private void stopWith(DebugPanel panel, DapModels.VariableInfo... vars) {
        locals = List.of(vars);
        panel.setState(DapManager.State.RUNNING);
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(STACK);
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

    @Test
    void aValueThatChangedSinceThePreviousStopIsMarked() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions());
            panel.setState(DapManager.State.STARTING);
            stopWith(panel, variable("i", "0"), variable("total", "10"));
            TreeItem<DebugPanel.VarRow> i = row(panel, "i");
            assertFalse(panel.changed(i, i.getValue()), "the first stop has nothing to compare with");

            stopWith(panel, variable("i", "1"), variable("total", "10"), variable("fresh", "3"));
            i = row(panel, "i");
            TreeItem<DebugPanel.VarRow> total = row(panel, "total");
            TreeItem<DebugPanel.VarRow> fresh = row(panel, "fresh");
            assertTrue(panel.changed(i, i.getValue()), "i went from 0 to 1");
            assertFalse(panel.changed(total, total.getValue()), "total did not change");
            assertFalse(panel.changed(fresh, fresh.getValue()), "a new variable is not marked");

            panel.setState(DapManager.State.RUNNING);
            assertFalse(panel.changed(i, i.getValue()), "nothing is marked while the program runs");

            panel.setState(DapManager.State.INACTIVE);
            panel.setState(DapManager.State.STARTING);
            stopWith(panel, variable("i", "7"));
            i = row(panel, "i");
            assertFalse(panel.changed(i, i.getValue()), "a new session compares with nothing");
        });
    }

    @Test
    void aMarkedRowCarriesTheChangedStyle() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel.VarRow row = new DebugPanel.VarRow("i", "1", "int", 0, 7, DebugPanel.Kind.VARIABLE);
            TextFlow marked = DebugPanel.renderRow(row, true);
            TextFlow plain = DebugPanel.renderRow(row, false);
            assertTrue(marked.getStyleClass().contains("debug-row-changed"));
            assertFalse(plain.getStyleClass().contains("debug-row-changed"));
            assertEquals(
                    1,
                    marked.getChildren().stream()
                            .filter(n -> n.getStyleClass().contains("debug-val"))
                            .count(),
                    "the value runs are what turns bold");
        });
    }
}
