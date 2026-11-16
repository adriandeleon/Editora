package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.NumberViews.Radix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Show Value As in the Variables view: a whole number is shown in the radix chosen for it, at this stop and the next,
 * an array's choice is its elements', a watch has its own, Copy Value copies what is shown, a value that is no number
 * grays the menu saying why, and the row commands choose as the menu does. The adapter is a scripted {@link
 * DebugPanel.Actions} speaking for a Java session; {@code ValueFormatsProbeFxTest} drives the real debuggers.
 */
@Tag("fx")
class ShowValueAsFxTest {

    private static final int LOCALS = 1000;
    private static final int BYTES = 2000;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Adapter implements DebugPanel.Actions {
        final Map<Integer, List<DapModels.VariableInfo>> children = new HashMap<>();
        final Map<String, Radix> formats = new HashMap<>();

        @Override
        public void start() {}

        @Override
        public void pause() {}

        @Override
        public void stepOver() {}

        @Override
        public void stepInto() {}

        @Override
        public void stepOut() {}

        @Override
        public void runToCursor() {}

        @Override
        public void stop() {}

        @Override
        public void restart() {}

        @Override
        public void selectThread(int threadId) {}

        @Override
        public void selectFrame(DapModels.StackFrameInfo frame) {}

        @Override
        public void sendInput(String line) {}

        @Override
        public void endInput() {}

        @Override
        public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
            cb.accept(children.getOrDefault(ref, List.of()));
        }

        @Override
        public void loadChildrenPage(
                int ref, boolean indexed, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            loadChildren(ref, cb);
        }

        @Override
        public void evaluate(String expression, int frameId, Consumer<com.editora.dap.ConsoleEvaluation> cb) {
            cb.accept(com.editora.dap.ConsoleEvaluation.value(expression));
        }

        @Override
        public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {
            cb.accept(new DapModels.EvalResult("-42", 0, "int"));
        }

        @Override
        public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {
            cb.accept(value);
        }

        @Override
        public String language() {
            return "java";
        }

        @Override
        public Map<String, Radix> valueFormats() {
            return formats; // the window's, as the coordinator keeps it
        }
    }

    private Adapter adapter;
    private DebugPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new Adapter();
        adapter.children.put(
                LOCALS,
                List.of(
                        variable("n", "42", "int", 0, "n"),
                        variable("neg", "-42", "int", 0, "neg"),
                        variable("c", "A", "char", 0, "c"),
                        variable("name", "\"Ada\"", "String", 0, "name"),
                        variable("bytes", "byte[3]@9", "byte[]", BYTES, "bytes")));
        adapter.children.put(
                BYTES,
                List.of(
                        variable("0", "1", "byte", 0, "bytes[0]"),
                        variable("1", "-1", "byte", 0, "bytes[1]"),
                        variable("2", "127", "byte", 0, "bytes[2]")));
        panel = FxTestSupport.callOnFx(() -> new DebugPanel(adapter));
    }

    private static DapModels.VariableInfo variable(String name, String value, String type, int ref, String expr) {
        return new DapModels.VariableInfo(name, value, type, ref, 0, 0, false, null, expr);
    }

    private TreeView<DebugPanel.VarRow> tree() {
        return FxTestSupport.field(panel, "variables");
    }

    private void stop() {
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(List.of(new DapModels.StackFrameInfo(1, "main", Path.of("Main.java"), 4, 1)));
        panel.setScopes(List.of(new DapModels.ScopeInfo("Locals", LOCALS, false)));
    }

    private TreeItem<DebugPanel.VarRow> row(String... names) {
        TreeItem<DebugPanel.VarRow> item = tree().getRoot();
        for (String name : names) {
            item = item.getChildren().stream()
                    .filter(c -> c.getValue().name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row '" + name + "'"));
        }
        return item;
    }

    /** What the row shows. */
    private String shown(String... names) {
        TreeItem<DebugPanel.VarRow> item = row(names);
        return panel.shown(item, item.getValue()).value();
    }

    private Menu showAs(TreeItem<DebugPanel.VarRow> item) {
        tree().getSelectionModel().select(item);
        ContextMenu menu = tree().getContextMenu();
        menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
        return menu.getItems().stream()
                .filter(m -> tr("debugpanel.showAs").equals(m.getText()))
                .map(Menu.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static void choose(Menu menu, Radix radix) {
        MenuItem choice = menu.getItems().get(List.of(Radix.values()).indexOf(radix));
        assertEquals(tr("debugpanel.showAs." + radix.name().toLowerCase(java.util.Locale.ROOT)), choice.getText());
        choice.fire();
    }

    private static Radix ticked(Menu menu) {
        for (int i = 0; i < menu.getItems().size(); i++) {
            if (((RadioMenuItem) menu.getItems().get(i)).isSelected()) {
                return Radix.values()[i];
            }
        }
        return null;
    }

    @Test
    void aNumberIsShownInTheRadixChosenForItAndKeepsItAtTheNextStop() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stop();
            row("Locals").setExpanded(true);
            Menu menu = showAs(row("Locals", "neg"));
            assertFalse(menu.isDisable());
            assertEquals(Radix.DEFAULT, ticked(menu), "as the debugger shows it, at first");
            choose(menu, Radix.HEX);
            assertEquals("0xffffffd6", shown("Locals", "neg"), "an int's two's complement");
            assertEquals("42", shown("Locals", "n"), "the others as they were");
            assertEquals(Radix.HEX, ticked(showAs(row("Locals", "neg"))));
            choose(showAs(row("Locals", "c")), Radix.DECIMAL);
            assertEquals("65", shown("Locals", "c"), "a char's code");

            // Copy Value copies what is on screen.
            tree().getSelectionModel().select(row("Locals", "neg"));
            panel.copySelectedValue();
            assertEquals(
                    "0xffffffd6",
                    javafx.scene.input.Clipboard.getSystemClipboard().getString());

            // The next stop: the same expression, the same radix.
            stop();
            row("Locals").setExpanded(true);
            assertEquals("0xffffffd6", shown("Locals", "neg"));
            choose(showAs(row("Locals", "neg")), Radix.DEFAULT);
            assertEquals("-42", shown("Locals", "neg"), "back to the debugger's own");
        });
        assertEquals(Map.of("java\0c", Radix.DECIMAL), adapter.formats, "kept by language and expression");
    }

    @Test
    void anArraysChoiceIsItsElementsUntilOneHasItsOwn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stop();
            row("Locals").setExpanded(true);
            row("Locals", "bytes").setExpanded(true);
            Menu menu = showAs(row("Locals", "bytes"));
            assertFalse(menu.isDisable(), "an array: its numbers");
            choose(menu, Radix.BINARY);
            assertEquals("0b1", shown("Locals", "bytes", "0"));
            assertEquals("0b11111111", shown("Locals", "bytes", "1"), "a byte's two's complement");
            assertEquals("byte[3]@9", shown("Locals", "bytes"), "the array's own text is no number");
            choose(showAs(row("Locals", "bytes", "2")), Radix.HEX);
            assertEquals("0x7f", shown("Locals", "bytes", "2"), "its own choice");
            assertEquals("0b11111111", shown("Locals", "bytes", "1"));
        });
    }

    @Test
    void aWatchHasItsOwnAndAValueThatIsNoNumberSaysWhy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setWatches(List.of("n - 84"));
            stop();
            TreeItem<DebugPanel.VarRow> watch = row(tr("debugpanel.watches"), "n - 84");
            choose(showAs(watch), Radix.OCTAL);
            assertEquals("0o37777777726", shown(tr("debugpanel.watches"), "n - 84"));

            row("Locals").setExpanded(true);
            Menu onString = showAs(row("Locals", "name"));
            assertTrue(onString.isDisable(), "a string is no number");
            assertTrue(onString.getItems().stream().allMatch(MenuItem::isDisable));
        });
    }

    @Test
    void theRowCommandsChooseAsTheMenuDoes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stop();
            row("Locals").setExpanded(true);
            tree().getSelectionModel().select(row("Locals", "n"));
            assertTrue(panel.runMenuRow("debug.showSelectedAsCharacter"));
            assertEquals("'*'", shown("Locals", "n"));
            assertTrue(panel.runMenuRow("debug.showSelectedAsDefault"));
            assertEquals("42", shown("Locals", "n"));

            tree().getSelectionModel().select(row("Locals", "name"));
            assertFalse(panel.runMenuRow("debug.showSelectedAsHex"), "grayed for a string");
            assertTrue(panel.rowRefusal("debug.showSelectedAsHex").contains(tr("debugpanel.showAs.notANumber")));
        });
        assertNull(adapter.formats.get("java\0name"));
        for (Radix r : Radix.values()) {
            assertTrue(DebugRowCommands.IDS.contains(DebugPanel.showAsCommand(r)), r + " has a command");
        }
        List<String> titles = new ArrayList<>();
        for (Radix r : Radix.values()) {
            titles.add(tr("command." + DebugPanel.showAsCommand(r)));
        }
        assertEquals(6, titles.stream().distinct().count());
    }
}
