package com.editora.editor;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The code-action lightbulb's state for one buffer (IntelliJ's intention bulb): once the caret rests, it asks the
 * language server what it offers there and shows a bulb on that line in the gutter, which {@link EditorBuffer} draws
 * through its fold gutter. A move off the line takes the bulb away at once; an answer for text or a line that has
 * since changed is dropped.
 */
public final class CodeActionBulb {

    /**
     * Asks the language server what it offers at {@code line}:{@code column} (0-based): answers {@code "fix"} for a
     * quick fix, {@code "assist"} for another quick action, or null for nothing worth a bulb.
     */
    @FunctionalInterface
    public interface Probe {
        void probe(int line, int column, java.util.function.Consumer<@Nullable String> answer);
    }

    /** How long the caret rests before the server is asked: moving through a file asks nothing. */
    static final double DELAY_MILLIS = 350;

    private final Supplier<@Nullable CodeArea> view;
    private final BooleanSupplier possible;
    private final LongSupplier version;
    private final IntConsumer repaintLine;
    private final PauseTransition timer = new PauseTransition(javafx.util.Duration.millis(DELAY_MILLIS));

    private @Nullable Probe probe;
    private boolean enabled = true;
    private int line = -1;
    private @Nullable String kind;

    /**
     * {@code view}: the view whose caret counts; {@code possible}: whether the buffer can show a bulb at all (a server
     * with code actions, an editable small file); {@code version}: the document's edit version; {@code repaintLine}:
     * redraws one gutter row.
     */
    CodeActionBulb(
            Supplier<@Nullable CodeArea> view,
            BooleanSupplier possible,
            LongSupplier version,
            IntConsumer repaintLine) {
        this.view = view;
        this.possible = possible;
        this.version = version;
        this.repaintLine = repaintLine;
        timer.setOnFinished(e -> probe());
    }

    public void setProbe(@Nullable Probe probe) {
        this.probe = probe;
        schedule();
    }

    public void setEnabled(boolean on) {
        enabled = on;
        schedule();
    }

    public int line() {
        return line;
    }

    public @Nullable String kind() {
        return kind;
    }

    /** The bulb's style class on {@code row}, or null: what the gutter asks for each row it builds. */
    @Nullable
    String kindAt(int row) {
        return row == line ? kind : null;
    }

    private boolean wanted() {
        return enabled && probe != null && possible.getAsBoolean();
    }

    /** After a caret move, an edit or new problems: asks once the caret has rested. */
    void schedule() {
        CodeArea a = view.get();
        int at = a == null ? -1 : a.getCurrentParagraph();
        if (line >= 0 && (at != line || !wanted())) {
            set(-1, null);
        }
        if (!wanted()) {
            timer.stop();
            return;
        }
        timer.playFromStart();
    }

    /** Asks now: what {@link #schedule} runs when the caret has rested. */
    public void probe() {
        Probe p = probe;
        CodeArea a = view.get();
        if (p == null || a == null || !wanted()) {
            return;
        }
        int at = a.getCurrentParagraph();
        int column = a.getCaretColumn();
        long asked = version.getAsLong();
        p.probe(at, column, answer -> {
            CodeArea now = view.get();
            if (now == null || asked != version.getAsLong() || now.getCurrentParagraph() != at || !wanted()) {
                return; // typed or moved since: a fresher answer is on its way
            }
            set(answer == null ? -1 : at, answer);
        });
    }

    private void set(int row, @Nullable String newKind) {
        if (row == line && Objects.equals(newKind, kind)) {
            return;
        }
        int was = line;
        line = row;
        kind = row < 0 ? null : newKind;
        if (was >= 0) {
            repaintLine.accept(was);
        }
        if (row >= 0) {
            repaintLine.accept(row);
        }
    }
}
