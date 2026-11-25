package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The debugger's expression fields and value popup: completion in the stopped frame for the watch prompt and the
 * breakpoint forms, and the hover / Show Value at Caret popup whose objects unfold. Part of {@link DebugCoordinator}.
 */
final class ValuePopups {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    ValuePopups(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * What a debugger expression field completes against: the selected frame of a stopped session (DAP
     * {@code completions}). {@code braces}: a log message, where only the {@code {expression}} at the caret is code.
     */
    DebugCompletion.Source completionSource(boolean braces) {
        return new DebugCompletion.Source() {
            @Override
            public boolean paused() {
                return d.dap().state() == DapManager.State.SUSPENDED && d.s().debugFrameId >= 0;
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                int from = braces ? DebugCompletionText.braceStart(text, caret) : 0;
                if (from < 0) {
                    cb.accept(List.of()); // outside {…}: plain log text
                    return;
                }
                d.dap().completions(d.s().debugFrameId, text.substring(from, caret), caret - from, cb);
            }
        };
    }

    /** Add / Edit Watch: the window's one-field prompt, its field completing expressions in the stopped frame. */
    void expressionPrompt(String title, String label, String initial, Consumer<String> onAccept) {
        Label promptLabel = new Label(label);
        TextField field = new TextField(initial == null ? "" : initial);
        field.setPrefColumnCount(32);
        DebugCompletion.attach(field, completionSource(false));
        com.editora.command.TextInputKeymap.installShared(field);
        OverlayInput.show(
                d.host.overlayHost(),
                title,
                UiZoom.spacing(new javafx.scene.layout.VBox(promptLabel, field), 6),
                field,
                tr("dialog.ok"),
                null,
                () -> onAccept.accept(field.getText()),
                null,
                false);
    }

    @Nullable
    DebugValuePopup valuePopup;

    /**
     * What the editors show a value with: the {@link #valuePopup} (a popup window and two menus of its own),
     * made the first time a value is shown, not with every editor of a window that may never debug.
     */
    final com.editora.editor.debug.DebugValuePresenter valuePresenter =
            new com.editora.editor.debug.DebugValuePresenter() {
                @Override
                public void show(org.fxmisc.richtext.CodeArea area, String expr, double x, double y, boolean key) {
                    valuePopup().show(area, expr, x, y, key);
                }

                @Override
                public void showEvaluated(org.fxmisc.richtext.CodeArea area, String expr, double x, double y) {
                    valuePopup().showEvaluated(area, expr, x, y);
                }

                @Override
                public void hide() {
                    if (valuePopup != null) {
                        valuePopup.hide();
                    }
                }

                @Override
                public void mouseLeft() {
                    if (valuePopup != null) {
                        valuePopup.mouseLeft();
                    }
                }

                @Override
                public boolean mouseInside() {
                    return valuePopup != null && valuePopup.mouseInside();
                }

                @Override
                public boolean isShowing() {
                    return valuePopup != null && valuePopup.isShowing();
                }
            };

    /** The hover / Show Value at Caret popup, shared by this window's editors. */
    DebugValuePopup valuePopup() {
        if (valuePopup == null) {
            valuePopup = new DebugValuePopup(new DebugValuePopup.Source() {
                @Override
                public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                    d.renderedEvaluate(expression, d.s().debugFrameId, "hover", cb);
                }

                @Override
                public void children(
                        int ref,
                        @Nullable String filter,
                        int start,
                        int count,
                        Consumer<List<DapModels.VariableInfo>> cb) {
                    d.renderedVariables(ref, filter, start, count, cb);
                }

                @Override
                public void addWatch(String expression) {
                    d.ops.openToolWindow();
                    d.panel().addWatchPrompt(expression);
                }

                @Override
                public void evaluateCode(String expression, Consumer<DapModels.EvalResult> cb) {
                    d.dap().evaluateFull(expression, d.s().debugFrameId, "repl", r -> {
                        cb.accept(r);
                        if (r != null && !r.failed()) {
                            d.refreshStopValues(); // Quick Evaluate runs a call: what it changed shows (B205)
                        }
                    });
                }

                @Override
                public void pin(org.fxmisc.richtext.CodeArea area, int line, String expression) {
                    EditorBuffer[] owner = new EditorBuffer[1];
                    d.host.forEachBuffer(b -> {
                        if (owner[0] == null && (b.getArea() == area || b.getFocusedArea() == area)) {
                            owner[0] = b;
                        }
                    });
                    if (owner[0] != null) {
                        d.pinnedValues().toggle(owner[0], line, expression);
                    }
                }
            });
            if (valuePopup.popup().getSkin() != null) {
                DebugPanel.setPlainValueColours(valuePopup.popup().getSkin().getNode(), d.plainValueColours()); // C111
            }
            valuePopup.setMarks(value -> d.s().marks.labelOf(value)); // the focused session's marks
        }
        return valuePopup;
    }
}
