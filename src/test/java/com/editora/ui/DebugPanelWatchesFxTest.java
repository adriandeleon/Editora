package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Debug panel's variables tree as the user works it: watches added, edited and removed, a value set, a
 * large array paged, the row menu — and the stack, the evaluate field and the toolbar around it. The
 * adapter is a scripted {@link DebugPanel.Actions}; the prompt is answered by the test.
 */
@Tag("fx")
class DebugPanelWatchesFxTest {

    private static final int LOCALS = 1000;
    private static final int ARRAY = 2000;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Plays the adapter: canned children per reference, canned watch results, and a record of what was asked. */
    private static final class Adapter implements DebugPanel.Actions {
        final Map<Integer, List<DapModels.VariableInfo>> children = new HashMap<>();
        final Map<String, DapModels.EvalResult> watchResults = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<DapModels.StackFrameInfo> frames = new ArrayList<>();
        int arrayLength;

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void pause() {
            calls.add("pause");
        }

        @Override
        public void stepOver() {
            calls.add("stepOver");
        }

        @Override
        public void stepInto() {
            calls.add("stepInto");
        }

        @Override
        public void stepOut() {
            calls.add("stepOut");
        }

        @Override
        public void runToCursor() {
            calls.add("runToCursor");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public void restart() {
            calls.add("restart");
        }

        @Override
        public void selectThread(int threadId) {
            calls.add("thread " + threadId);
        }

        @Override
        public void selectFrame(DapModels.StackFrameInfo frame) {
            frames.add(frame);
        }

        @Override
        public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
            calls.add("children " + ref);
            cb.accept(children.getOrDefault(ref, List.of()));
        }

        @Override
        public void loadChildrenPage(
                int ref, boolean indexed, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            calls.add("page " + ref + (indexed ? " indexed " + start + "+" + count : " named"));
            List<DapModels.VariableInfo> out = new ArrayList<>();
            if (indexed) {
                for (int i = start; i < Math.min(arrayLength, start + count); i++) {
                    out.add(new DapModels.VariableInfo("[" + i + "]", Integer.toString(i), "int", 0));
                }
            } else {
                out.add(new DapModels.VariableInfo("length", Integer.toString(arrayLength), "int", 0));
            }
            cb.accept(out);
        }

        @Override
        public void evaluate(String expression, int frameId, Consumer<String> cb) {
            calls.add("evaluate " + expression + " @" + frameId);
            cb.accept(expression.startsWith("null") ? null : "val(" + expression + ")");
        }

        @Override
        public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {
            calls.add("watch " + expression);
            cb.accept(
                    watchResults.getOrDefault(expression, new DapModels.EvalResult("w(" + expression + ")", 0, null)));
        }

        @Override
        public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {
            calls.add("set " + parentRef + " " + name + "=" + value);
            cb.accept("<" + value + ">"); // what the adapter says the value now is
        }

        @Override
        public void sendInput(String line) {
            calls.add("input " + line);
        }

        @Override
        public void endInput() {
            calls.add("endInput");
        }
    }

    private Adapter adapter;
    private DebugPanel panel;
    private final List<String> prompts = new ArrayList<>();
    private String promptAnswer;
    private int watchChanges;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new Adapter();
        adapter.children.put(
                LOCALS,
                List.of(
                        new DapModels.VariableInfo("count", "3", "int", 0),
                        new DapModels.VariableInfo("name", "\"Ada\"", null, 0),
                        new DapModels.VariableInfo("items", "int[250]", "int[]", ARRAY, 1, 250)));
        adapter.arrayLength = 250;
        prompts.clear();
        watchChanges = 0;
        panel = FxTestSupport.callOnFx(() -> {
            DebugPanel p = new DebugPanel(adapter);
            p.setPrompt((title, label, initial, onAccept) -> {
                prompts.add(title + "|" + label + "|" + initial);
                onAccept.accept(promptAnswer);
            });
            p.setOnWatchesChanged(() -> watchChanges++);
            return p;
        });
    }

    // --- harness --------------------------------------------------------------------------------------

    private TreeView<DebugPanel.VarRow> tree() {
        return FxTestSupport.field(panel, "variables");
    }

    /** Suspends on a two-frame stack and shows the Locals scope, expanded. FX thread. */
    private void stopWithLocals() {
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(List.of(
                new DapModels.StackFrameInfo(1, "work", Path.of("loop.py"), 2, 1),
                new DapModels.StackFrameInfo(2, "<module>", Path.of("loop.py"), 9, 1)));
        panel.setScopes(List.of(new DapModels.ScopeInfo("Locals", LOCALS, false)));
    }

    /** The row called {@code names…} from the top of the tree. FX thread. */
    private TreeItem<DebugPanel.VarRow> row(String... names) {
        TreeItem<DebugPanel.VarRow> item = tree().getRoot();
        for (String name : names) {
            item = item.getChildren().stream()
                    .filter(c -> c.getValue().name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row '" + name + "' in " + rowNames(tree().getRoot())));
        }
        return item;
    }

    private static List<String> rowNames(TreeItem<DebugPanel.VarRow> parent) {
        return parent.getChildren().stream()
                .map(c -> c.getValue().name()
                        + (c.getValue().value().isEmpty()
                                ? ""
                                : "=" + c.getValue().value()))
                .toList();
    }

    private List<String> watchRows() throws Exception {
        return FxTestSupport.callOnFx(
                () -> rowNames(tree().getRoot().getChildren().get(0)));
    }

    private void doubleClick(TreeItem<DebugPanel.VarRow> item) {
        tree().getSelectionModel().select(item);
        tree().fireEvent(click(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 2));
    }

    private static MouseEvent click(javafx.event.EventType<MouseEvent> type, MouseButton button, int clicks) {
        return new MouseEvent(
                type, 5, 5, 5, 5, button, clicks, false, false, false, false, true, false, false, false, false, true,
                null);
    }

    /** The row menu's entries for the selected row, built the way showing it builds them. FX thread. */
    private List<MenuItem> menuFor(TreeItem<DebugPanel.VarRow> item) {
        tree().getSelectionModel().select(item);
        ContextMenu menu = tree().getContextMenu();
        menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
        return List.copyOf(menu.getItems());
    }

    private static List<String> labels(List<MenuItem> items) {
        return items.stream().map(MenuItem::getText).toList();
    }

    // --- watches ----------------------------------------------------------------------------------------

    @Test
    void watchesAreListedWhileIdleAndEvaluatedOnceTheProgramStops() throws Exception {
        adapter.watchResults.put("broken(", DapModels.EvalResult.failure("Cannot evaluate: broken("));
        adapter.watchResults.put("items", new DapModels.EvalResult("int[250]", ARRAY, "int[]", 1, 250));
        FxTestSupport.runOnFx(
                () -> panel.setWatches(java.util.Arrays.asList("count * 2", null, "  ", "broken(", "items")));

        assertEquals(List.of("count * 2", "broken(", "items"), FxTestSupport.callOnFx(panel::getWatches));
        assertEquals(
                List.of("count * 2", "broken(", "items", tr("debugpanel.addWatch")),
                watchRows(),
                "no values while nothing is stopped");
        assertEquals(List.of(), adapter.calls);

        FxTestSupport.runOnFx(this::stopWithLocals);

        assertEquals(
                List.of(
                        "count * 2=w(count * 2)",
                        "broken(=Cannot evaluate: broken(",
                        "items=int[250]",
                        tr("debugpanel.addWatch")),
                watchRows());
        FxTestSupport.runOnFx(() -> {
            DebugPanel.VarRow failed = row(tr("debugpanel.watches"), "broken(").getValue();
            assertTrue(failed.failed(), "the adapter's message is not a value");
            DebugPanel.VarRow structured =
                    row(tr("debugpanel.watches"), "items").getValue();
            assertEquals(ARRAY, structured.ref(), "a structured result can be expanded");
            assertEquals("int[]", structured.type());
        });

        FxTestSupport.runOnFx(() -> panel.setWatches(null));
        assertEquals(List.of(tr("debugpanel.addWatch")), watchRows());
    }

    @Test
    void aWatchIsAddedEditedAndRemovedFromTheTree() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stopWithLocals();
            promptAnswer = "  count * 2 ";
            doubleClick(row(tr("debugpanel.watches"), tr("debugpanel.addWatch")));
        });
        assertEquals(tr("debugpanel.addWatchTitle") + "|" + tr("debugpanel.watchExpr") + "|", prompts.get(0));
        assertEquals(List.of("count * 2"), FxTestSupport.callOnFx(panel::getWatches));
        assertEquals(List.of("count * 2=w(count * 2)", tr("debugpanel.addWatch")), watchRows());
        assertEquals(1, watchChanges, "the controller is told, so the watch is saved");

        FxTestSupport.runOnFx(() -> {
            promptAnswer = " ";
            doubleClick(row(tr("debugpanel.watches"), tr("debugpanel.addWatch")));
            promptAnswer = null;
            panel.addWatch(); // the palette command: the same prompt
        });
        assertEquals(1, watchChanges, "a blank or cancelled expression adds nothing");

        FxTestSupport.runOnFx(() -> {
            promptAnswer = "count * 3";
            doubleClick(row(tr("debugpanel.watches"), "count * 2"));
        });
        assertEquals(
                tr("debugpanel.editWatchTitle") + "|" + tr("debugpanel.watchExpr") + "|count * 2",
                prompts.get(prompts.size() - 1),
                "the edit starts from the expression as it is");
        assertEquals(List.of("count * 3"), FxTestSupport.callOnFx(panel::getWatches));
        assertEquals(2, watchChanges);

        FxTestSupport.runOnFx(() -> {
            promptAnswer = "  ";
            doubleClick(row(tr("debugpanel.watches"), "count * 3"));
        });
        assertEquals(List.of("count * 3"), FxTestSupport.callOnFx(panel::getWatches), "blanking it is not a removal");

        FxTestSupport.runOnFx(() -> {
            List<MenuItem> menu = menuFor(row(tr("debugpanel.watches"), "count * 3"));
            assertEquals(
                    List.of(tr("debugpanel.editWatch"), tr("debugpanel.removeWatch"), tr("debugpanel.addWatch")),
                    labels(menu));
            menu.get(1).fire();
        });
        assertEquals(List.of(), FxTestSupport.callOnFx(panel::getWatches));
        assertEquals(3, watchChanges);
        assertEquals(List.of(tr("debugpanel.addWatch")), watchRows());
    }

    @Test
    void theRowMenuOffersWhatFitsTheRowAndItsEntriesDoWhatDoubleClickDoes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setWatches(List.of("count"));
            stopWithLocals();

            assertEquals(List.of(tr("debugpanel.addWatch")), labels(menuFor(row("Locals"))), "a scope: only Add");
            List<MenuItem> onVariable = menuFor(row("Locals", "count"));
            assertEquals(List.of(tr("debugpanel.setValue"), tr("debugpanel.addWatch")), labels(onVariable));

            promptAnswer = "7";
            onVariable.get(0).fire();
            assertEquals("<7>", row("Locals", "count").getValue().value());

            promptAnswer = "name.length()";
            onVariable.get(1).fire();
            assertEquals(List.of("count", "name.length()"), panel.getWatches());

            promptAnswer = "count + 1";
            menuFor(row(tr("debugpanel.watches"), "count")).get(0).fire();
            assertEquals(List.of("count + 1", "name.length()"), panel.getWatches());

            tree().getSelectionModel().clearSelection();
            ContextMenu menu = tree().getContextMenu();
            menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
            assertEquals(List.of(tr("debugpanel.addWatch")), labels(menu.getItems()), "no row: still a way to add");
        });
    }

    @Test
    void withNoPromptToAskThroughNothingIsAddedOrSet() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setPrompt(null);
            panel.setWatches(List.of("count"));
            stopWithLocals();
            adapter.calls.clear();

            doubleClick(row(tr("debugpanel.watches"), tr("debugpanel.addWatch")));
            doubleClick(row(tr("debugpanel.watches"), "count"));
            doubleClick(row("Locals", "count"));
            panel.addWatch();
            panel.setSelectedValue();
        });

        assertEquals(List.of("count"), FxTestSupport.callOnFx(panel::getWatches));
        assertEquals(List.of(), adapter.calls);
        assertEquals(0, watchChanges);
    }

    // --- setting a value --------------------------------------------------------------------------------

    @Test
    void aLeafVariableIsSetByDoubleClickOrF2AndShowsWhatTheAdapterAnswered() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setWatches(List.of("count"));
            stopWithLocals();
            adapter.calls.clear();
            promptAnswer = "42";
            doubleClick(row("Locals", "count"));
        });
        assertEquals(tr("debugpanel.setValueTitle") + "|count|3", prompts.get(0));
        assertTrue(adapter.calls.contains("set " + LOCALS + " count=42"), adapter.calls.toString());
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    "<42>", row("Locals", "count").getValue().value(), "the adapter's rendering, not the typed text");
            assertEquals("int", row("Locals", "count").getValue().type());
        });
        assertTrue(adapter.calls.contains("watch count"), "a watch may depend on it: re-evaluated");

        FxTestSupport.runOnFx(() -> {
            adapter.calls.clear();
            promptAnswer = "\"Grace\"";
            tree().getSelectionModel().select(row("Locals", "name"));
            tree().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false));
            assertEquals("<\"Grace\">", row("Locals", "name").getValue().value());

            promptAnswer = null; // the prompt was cancelled
            adapter.calls.clear();
            panel.setSelectedValue();
            assertEquals(List.of(), adapter.calls, "a cancelled prompt sets nothing");

            prompts.clear();
            doubleClick(row("Locals", "items")); // an expandable row: double-click expands, it does not edit
            doubleClick(row("Locals"));
            tree().getSelectionModel().select(row("Locals"));
            tree().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false));
            tree().fireEvent(click(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 1));
            assertEquals(List.of(), prompts);
        });
    }

    @Test
    void aVariablesRowShowsItsTypeWhenTheAdapterGaveOne() throws Exception {
        List<List<String>> rendered = FxTestSupport.callOnFx(() -> {
            stopWithLocals();
            List<List<String>> out = new ArrayList<>();
            for (String name : List.of("count", "name")) {
                TextFlow flow = (TextFlow) FxTestSupport.call(
                        panel,
                        "renderRow",
                        new Class<?>[] {DebugPanel.VarRow.class},
                        row("Locals", name).getValue());
                out.add(flow.getChildren().stream()
                        .map(n -> ((Text) n).getText())
                        .toList());
            }
            return out;
        });

        assertEquals(List.of("count", " = ", "3", "  int"), rendered.get(0));
        assertEquals(List.of("name", " = ", "\"Ada\""), rendered.get(1), "no type: nothing after the value");
    }

    // --- a large array is fetched a page at a time ------------------------------------------------------

    @Test
    void aLargeArrayShowsItsNamedChildrenAndOnePageWithAWayToTheNext() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stopWithLocals();
            adapter.calls.clear();
            row("Locals", "items").setExpanded(true);
        });
        assertEquals(
                List.of("page " + ARRAY + " named", "page " + ARRAY + " indexed 0+100"),
                adapter.calls,
                "only the first hundred elements are asked for");

        FxTestSupport.runOnFx(() -> {
            List<TreeItem<DebugPanel.VarRow>> shown = row("Locals", "items").getChildren();
            assertEquals(102, shown.size(), "length, a hundred elements, and the row that offers more");
            assertEquals("length", shown.get(0).getValue().name());
            assertEquals("[99]", shown.get(100).getValue().name());
            TreeItem<DebugPanel.VarRow> more = shown.get(101);
            assertEquals(tr("debugpanel.showMore", 100, 150), more.getValue().name());

            tree().getSelectionModel().select(more);
            tree().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals("page " + ARRAY + " indexed 100+100", adapter.calls.get(2));

        FxTestSupport.runOnFx(() -> {
            List<TreeItem<DebugPanel.VarRow>> shown = row("Locals", "items").getChildren();
            assertEquals(202, shown.size());
            assertEquals(
                    "[100]",
                    tree().getSelectionModel().getSelectedItem().getValue().name(),
                    "the selection stays where the row that was activated had been");
            doubleClick(shown.get(201)); // the last fifty, by double-click this time
        });
        assertEquals("page " + ARRAY + " indexed 200+50", adapter.calls.get(3));
        FxTestSupport.runOnFx(() -> {
            List<TreeItem<DebugPanel.VarRow>> shown = row("Locals", "items").getChildren();
            assertEquals(251, shown.size(), "everything is shown and nothing more is offered");
            assertEquals("[249]", shown.get(250).getValue().name());
        });
    }

    // --- stack, toolbar and the evaluate field ----------------------------------------------------------

    @Test
    void aClickOnTheFrameAlreadySelectedGoesBackToIt() throws Exception {
        ListView<DapModels.StackFrameInfo> stack = FxTestSupport.field(panel, "stack");
        FxTestSupport.runOnFx(() -> {
            stopWithLocals();
            assertEquals(1, adapter.frames.size(), "the stop itself selects the top frame");

            stack.fireEvent(click(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 1));
            stack.fireEvent(click(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 1));
            assertEquals(2, adapter.frames.size(), "its tab may have been closed: the click reopens it");
            assertEquals(1, adapter.frames.get(1).id());

            stack.fireEvent(click(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 1));
            stack.fireEvent(click(MouseEvent.MOUSE_CLICKED, MouseButton.SECONDARY, 1));
            assertEquals(2, adapter.frames.size(), "a right click is not a request to go there");

            panel.setState(DapManager.State.RUNNING);
            stack.fireEvent(click(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 1));
            stack.fireEvent(click(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 1));
            assertEquals(2, adapter.frames.size(), "the program has moved on: that frame is no longer anywhere");
        });
    }

    @Test
    void whileABuildPreparesTheLaunchOnlyStopIsOffered() throws Exception {
        Button start = FxTestSupport.field(panel, "start");
        Button stop = FxTestSupport.field(panel, "stop");

        FxTestSupport.runOnFx(() -> {
            assertFalse(start.isDisabled());
            assertTrue(stop.isDisabled());

            panel.setPreparing(true);
            assertTrue(start.isDisabled(), "a second launch must not start a second build");
            assertFalse(stop.isDisabled(), "Stop cancels the build");

            panel.setPreparing(false);
            assertFalse(start.isDisabled());
            assertTrue(stop.isDisabled());
        });
    }

    @Test
    void theEvaluateFieldEvaluatesWhilePausedAndSaysOnceWhyItCannotWhileRunning() throws Exception {
        TextField eval = FxTestSupport.field(panel, "evalInput");
        CodeArea console = FxTestSupport.field(panel, "console");
        Runnable enter = () -> eval.fireEvent(new javafx.event.ActionEvent());

        FxTestSupport.runOnFx(() -> {
            stopWithLocals();
            adapter.calls.clear();
            eval.setText("count + 1");
            enter.run();
            eval.setText("  ");
            enter.run();
            eval.setText("nullResult");
            enter.run();
        });
        FxTestSupport.drainFx();
        assertEquals(List.of("evaluate count + 1 @1", "evaluate nullResult @1"), adapter.calls);
        assertEquals(
                "> count + 1\nval(count + 1)\n> nullResult\n\n",
                FxTestSupport.callOnFx(console::getText),
                "an expression with no result still gets its line");

        FxTestSupport.runOnFx(() -> {
            panel.clearConsole();
            panel.setState(DapManager.State.RUNNING);
            eval.setText("count");
            enter.run();
            enter.run();
            assertEquals("count", eval.getText(), "what was typed is kept for when the program pauses");
        });
        FxTestSupport.drainFx();
        assertEquals(tr("debugpanel.evalNeedsPause") + "\n", FxTestSupport.callOnFx(console::getText));
    }

    @Test
    void whenTheProgramReadsInputTheFieldSendsLinesAndControlDEndsIt() throws Exception {
        TextField eval = FxTestSupport.field(panel, "evalInput");
        KeyEvent ctrlD = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D, false, true, false, false);

        FxTestSupport.runOnFx(() -> {
            panel.setState(DapManager.State.RUNNING);
            eval.fireEvent(ctrlD);
            assertEquals(List.of(), adapter.calls, "no program of ours is reading: Ctrl+D is only a key");

            panel.setProgramInput(true);
            assertEquals(tr("debugpanel.inputPrompt"), eval.getPromptText());
            eval.setText("Ada");
            eval.fireEvent(new javafx.event.ActionEvent());
            eval.setText("");
            eval.fireEvent(new javafx.event.ActionEvent());
            eval.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D, true, true, false, false));
            eval.fireEvent(ctrlD);
        });

        assertEquals(List.of("input Ada", "input ", "endInput"), adapter.calls, "an empty line is a line too");
    }
}
