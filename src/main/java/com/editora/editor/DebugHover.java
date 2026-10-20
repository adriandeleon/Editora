package com.editora.editor;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * IntelliJ's value popup while the debugger is paused: hovering an identifier (with the attribute chain before it,
 * {@code other.depth}) evaluates it in the selected frame and shows {@code name = value}; Show Value at Caret
 * does the same from the keyboard.
 *
 * <p>Review B7/D2/C23: the hover evaluated words inside strings and comments, and sent an {@code evaluate} for
 * every word the mouse crossed — java-debug compiles each one through JDT. A word in a comment or a string is
 * now not evaluated, and the evaluation waits until the mouse has rested on a word for {@link #debounce}.
 */
public final class DebugHover {

    /** How long the mouse rests on a word before it is evaluated. */
    private static volatile Duration debounce = Duration.millis(250);

    /** Async evaluator (DAP {@code evaluate}, context "hover"): the expression → its value (null/blank: none). */
    private @Nullable BiConsumer<String, Consumer<String>> evaluator;

    private boolean active;
    private @Nullable Tooltip tooltip;
    /** The expression shown or being evaluated, so one word is evaluated once while the mouse stays on it. */
    private @Nullable String word;

    private @Nullable PauseTransition pending;

    /** Shows the value as an expandable popup (follow-up 6); null: the one-line tooltip. */
    private @Nullable DebugValuePresenter presenter;

    void setPresenter(DebugValuePresenter presenter) {
        this.presenter = presenter;
    }

    /** Test seam: the rest the mouse needs before an evaluation ({@link Duration#ZERO}: none, synchronous). */
    public static void setDebounceForTest(Duration rest) {
        debounce = rest == null ? Duration.millis(250) : rest;
    }

    void setEvaluator(BiConsumer<String, Consumer<String>> evaluator) {
        this.evaluator = evaluator;
    }

    /** On while paused, so hovering costs nothing outside a pause; off hides the popup. */
    void setActive(boolean on) {
        active = on;
        if (!on) {
            hide();
        }
    }

    void install(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!active || evaluator == null) {
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                String expression = expressionAt(a, pos.getMajor(), pos.getMinor());
                if (expression == null) {
                    leave();
                    return;
                }
                if (expression.equals(word)) {
                    return; // already showing (or about to evaluate) this one
                }
                leave();
                word = expression;
                double sx = e.getScreenX() + 12;
                double sy = e.getScreenY() + 16;
                later(() -> evaluate(a, expression, sx, sy));
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> leave());
    }

    /**
     * Show Value at Caret: evaluates the expression at the caret of {@code a} and shows its value under it. False
     * (nothing done) when not paused or the caret is not on a name in code.
     */
    boolean showAtCaret(@Nullable CodeArea a) {
        if (!active || evaluator == null) {
            return false;
        }
        String expression = DebugExpressions.atCaret(a); // on a name, or just after one
        if (expression == null || a == null) {
            return false;
        }
        Bounds b = a.getCaretBounds().orElse(null);
        double sx = b == null ? 0 : b.getMinX();
        double sy = b == null ? 0 : b.getMaxY() + 2;
        hide();
        word = expression;
        if (presenter != null) {
            presenter.show(a, expression, sx, sy, true);
            return true;
        }
        evaluate(a, expression, sx, sy);
        return true;
    }

    /** The expression a position stands for, or null: not on a name, or the name is in a comment or string. */
    static @Nullable String expressionAt(CodeArea a, int paragraph, int column) {
        String line = a.getParagraph(paragraph).getText();
        String expression = DebugIdentifiers.expressionAt(line, column);
        if (expression == null || !isCode(a.getStyleAtPosition(paragraph, Math.min(line.length(), column + 1)))) {
            return null;
        }
        return expression;
    }

    /** Whether a styled position is code (not a comment or a string literal) — as the inline values judge it. */
    static boolean isCode(Collection<String> style) {
        return style == null || !(style.contains("comment") || style.contains("string"));
    }

    private void evaluate(CodeArea a, String expression, double sx, double sy) {
        if (!active || !expression.equals(word)) {
            return; // moved on, or resumed, while the mouse was resting
        }
        if (presenter != null) {
            if (!presenter.mouseInside()) { // on its way into the open popup, the mouse crossed this word
                presenter.show(a, expression, sx, sy, false);
            }
            return;
        }
        BiConsumer<String, Consumer<String>> ev = evaluator;
        if (ev == null) {
            return;
        }
        ev.accept(expression, value -> {
            if (!active || value == null || value.isBlank() || !expression.equals(word)) {
                return; // evaluation failed / mouse moved on — show nothing
            }
            if (tooltip == null) {
                tooltip = new Tooltip();
                tooltip.getStyleClass().add("debug-value-tooltip");
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(480);
            }
            tooltip.setText(expression + " = " + value);
            tooltip.show(a, sx, sy);
        });
    }

    private void later(Runnable action) {
        if (debounce.lessThanOrEqualTo(Duration.ZERO)) {
            action.run();
            return;
        }
        if (pending == null) {
            pending = new PauseTransition();
        }
        pending.stop();
        pending.setDuration(debounce);
        pending.setOnFinished(e -> action.run());
        pending.playFromStart();
    }

    void hide() {
        if (pending != null) {
            pending.stop();
        }
        if (tooltip != null) {
            tooltip.hide();
        }
        if (presenter != null) {
            presenter.hide();
        }
        word = null;
    }

    /**
     * The mouse left the word: the tooltip goes at once; an expandable popup only if the mouse does not go into
     * it (it is hovered to unfold a field), so it is told and decides itself.
     */
    private void leave() {
        if (presenter == null) {
            hide();
            return;
        }
        if (pending != null) {
            pending.stop();
        }
        if (tooltip != null) {
            tooltip.hide();
        }
        presenter.mouseLeft();
        word = null;
    }

    /** The popup (null until first shown) — for tests. */
    @Nullable
    Tooltip tooltip() {
        return tooltip;
    }
}
