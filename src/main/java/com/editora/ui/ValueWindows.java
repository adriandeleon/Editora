package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

/**
 * The windows a value opens in: the Evaluate windows (Evaluate Expression) with their shared history, and the View as
 * Table windows. Part of {@link DebugCoordinator}.
 */
final class ValueWindows {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    ValueWindows(DebugCoordinator d) {
        this.d = d;
    }

    /** The View as Table windows open: one per collection asked for. */
    final List<DebugTableWindow> tableWindows = new ArrayList<>();

    /**
     * View as Table: opens a window showing the collection {@code ref} (named {@code name}, given by {@code
     * expression}) as a table of its elements and their fields ({@link DebugTableWindow}).
     */
    void viewAsTable(String name, String expression, int ref, int named, int indexed) {
        tableWindow().show(d.host.window(), name, expression, ref, named, indexed);
    }

    /**
     * View as DataFrame / View as Array: opens a window showing the pandas DataFrame or Series, or numpy array, {@code
     * expression} (named {@code name}) of the Python program, as a table of its rows ({@link PythonDataTable}).
     */
    void viewAsData(String name, String expression) {
        tableWindow().showData(d.host.window(), name, expression);
    }

    /** A new View as Table window, of the session shown. */
    private DebugTableWindow tableWindow() {
        DebugSession session = d.s();
        DebugTableWindow window = new DebugTableWindow(new DebugTableWindow.Actions() {
            @Override
            public void children(
                    int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                if (session != d.s()) {
                    cb.accept(List.of()); // another session is shown: this one's references are not its
                    return;
                }
                d.renderedVariables(ref, filter, start, count, cb);
            }

            @Override
            public void openText(String title, String text, String language) {
                d.ops.openReadOnlyDoc(title, text, language);
            }

            @Override
            public void evaluate(String expression, Consumer<DapModels.@Nullable EvalResult> cb) {
                if (!canRefresh()) {
                    cb.accept(null);
                    return;
                }
                d.renderedEvaluate(expression, d.panel().selectedFrameId(), "watch", cb::accept);
            }

            @Override
            public void evaluateData(String expression, Consumer<DapModels.@Nullable EvalResult> cb) {
                if (!canRefresh()) {
                    cb.accept(null);
                    return;
                }
                // debugpy's clipboard context: the helper's answer whole, where the others cut a string at 1,000.
                d.dap().evaluateFull(expression, d.panel().selectedFrameId(), "clipboard", cb::accept);
            }

            @Override
            public boolean canRefresh() {
                return session == d.s() && d.dap().state() == DapManager.State.SUSPENDED;
            }
        });
        tableWindows.removeIf(w -> !w.isShowing());
        tableWindows.add(window);
        return window;
    }

    /** The View as Table windows open (tests). */
    List<DebugTableWindow> tableWindows() {
        tableWindows.removeIf(w -> !w.isShowing());
        return List.copyOf(tableWindows);
    }

    /** Focuses the evaluate field at the top of Variables ({@code debug.focusEvaluate}). */
    void focusEvaluate() {
        d.ops.openToolWindow();
        d.panel().focusEvaluate();
        d.later(d.panel()::focusEvaluate); // a window opened just now is laid out on the next pulse
    }

    /** The expressions evaluated in this window's evaluate fields, oldest first: one list, walked by every field. */
    private DebugPanelText.@Nullable History evaluateHistory;

    /** Every Evaluate window opened; a closed one is reused by the next Evaluate. */
    final List<DebugEvaluateWindow> evaluateWindows = new ArrayList<>();

    DebugPanelText.History evaluateHistory() {
        if (evaluateHistory == null) {
            evaluateHistory = new DebugPanelText.History();
            d.ops.debugEvaluateHistory().forEach(evaluateHistory::add);
        }
        return evaluateHistory;
    }

    /**
     * Debug: Evaluate Expression ({@code debug.evaluate}, IntelliJ's Alt+F8): opens an Evaluate window, its
     * expression the editor's selection (one line) or the expression at the caret. Another Evaluate while one is open
     * opens another, as IntelliJ does — unless the open one was opened for the same text.
     */
    void evaluateExpression() {
        EditorBuffer b = d.host.activeBuffer();
        String initial = null;
        if (b != null) {
            var area = b.getFocusedArea();
            String selected = area.getSelectedText();
            initial = selected != null && !selected.isBlank()
                    ? selected.strip()
                    : com.editora.editor.debug.DebugExpressions.atCaret(area);
        }
        openEvaluateWindow(initial);
    }

    /** Opens an Evaluate window with {@code initial} as its expression (null: the field as it was). */
    DebugEvaluateWindow openEvaluateWindow(@Nullable String initial) {
        javafx.stage.Window owner = d.host.window();
        DebugEvaluateWindow window = null;
        int open = 0;
        for (DebugEvaluateWindow w : evaluateWindows) {
            if (w.isShowing()) {
                open++;
            } else if (window == null) {
                window = w;
            }
        }
        if (window == null) {
            window = new DebugEvaluateWindow(evaluateSource(), evaluateHistory());
            evaluateWindows.add(window);
        }
        window.setPlainValueColours(d.plainValueColours());
        window.show(
                owner, initial, d.s().panel == null ? "" : d.panel().expressionLanguageFile(), open * UiZoom.px(28.0));
        return window;
    }

    /** The Evaluate windows (tests). */
    List<DebugEvaluateWindow> evaluateWindows() {
        return List.copyOf(evaluateWindows);
    }

    private DebugEvaluateWindow.Source evaluateSource() {
        return new DebugEvaluateWindow.Source() {
            @Override
            public boolean paused() {
                return d.dap().state() == DapManager.State.SUSPENDED;
            }

            @Override
            public String frameName() {
                DapModels.StackFrameInfo frame = d.s().shownFrame;
                if (frame == null) {
                    return "";
                }
                String where = frame.file() == null
                        ? ""
                        : (frame.line() >= 0 ? ":" + (frame.line() + 1) : "") + ", "
                                + DebugValues.sourceName(frame.file());
                return DebugPanelText.frameName(frame.name()) + where;
            }

            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                d.renderedEvaluate(expression, d.s().debugFrameId, "repl", cb);
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                d.dap().completions(d.s().debugFrameId, text, caret, cb);
            }

            @Override
            public void addWatch(String expression) {
                d.ops.openToolWindow();
                d.panel().addWatchExpression(expression);
            }

            @Override
            public void evaluated(boolean succeeded) {
                d.ops.persistDebugEvaluateHistory(evaluateHistory().entries());
                if (succeeded) {
                    d.refreshStopValues(); // what it changed shows in Variables, the watches and the inline values
                }
            }

            @Override
            public void children(
                    int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                d.renderedVariables(ref, filter, start, count, cb);
            }
        };
    }
}
