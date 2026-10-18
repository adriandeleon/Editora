package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.util.Duration;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.dap.DapModels;
import com.editora.editor.CompletionPopup;

import static com.editora.i18n.Messages.tr;

/**
 * Completion in a debugger expression field — the Debug console's evaluate field, the watch prompt and a
 * breakpoint's condition and log message — from the adapter's DAP {@code completions} in the stopped frame
 * (follow-up 5; java-debug completes through JDT). Ctrl+Space asks, and so does typing a {@code .}; while the list
 * is open typing narrows it, ↑/↓ move, Enter or Tab insert, Esc closes. With the list closed the keys are the
 * field's own (the evaluate field's Up/Down history keeps working). Without a stopped frame there is nothing to
 * complete against: Ctrl+Space then says so under the field instead of doing nothing.
 *
 * <p>The list is the editor's {@link CompletionPopup}, focus-less: the keys are read by a filter on the field's
 * scene, which runs before the overlay card's Enter (accept the form) and Esc (close it) filters.
 */
final class DebugCompletion {

    /** Where the completions come from: whether there is a stopped frame, and the adapter's answer. */
    interface Source {
        boolean paused();

        /** The adapter's completions of {@code text} at 0-based {@code caret}, on the FX thread (empty: none). */
        void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb);
    }

    private final TextField field;
    private final Source source;
    private final CompletionPopup popup = new CompletionPopup();
    private final Tooltip note = new Tooltip();
    private final PauseTransition noteTimer = new PauseTransition(Duration.seconds(3));
    /** Answers to an older request than this one are dropped (the text moved on meanwhile). */
    private int request;
    /** The text the list was asked for, so a change of it (typing) asks again while the list is open. */
    private boolean typedDot;

    private Scene filtered;

    private final EventHandler<KeyEvent> keys = this::onKey;

    private DebugCompletion(TextField field, Source source) {
        this.field = field;
        this.source = source;
        note.getStyleClass().add("debug-value-tooltip");
        noteTimer.setOnFinished(e -> note.hide());
        popup.setOnAccept(this::accept);
        field.addEventFilter(KeyEvent.KEY_TYPED, e -> typedDot = ".".equals(e.getCharacter()));
        field.textProperty().addListener((o, was, now) -> {
            if (typedDot) {
                typedDot = false;
                Platform.runLater(() -> ask(false));
            } else if (popup.isShowing()) {
                Platform.runLater(() -> ask(false));
            }
        });
        field.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                hide();
            }
        });
        field.sceneProperty().addListener((o, was, now) -> watch(now));
        watch(field.getScene());
    }

    /** Adds completion to {@code field}. */
    static DebugCompletion attach(TextField field, Source source) {
        DebugCompletion completion = new DebugCompletion(field, source);
        field.getProperties().put(DebugCompletion.class, completion);
        return completion;
    }

    /** The completion attached to {@code field}, or null. */
    static DebugCompletion of(TextField field) {
        return field.getProperties().get(DebugCompletion.class) instanceof DebugCompletion c ? c : null;
    }

    private void watch(Scene scene) {
        if (filtered != null) {
            filtered.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
        }
        filtered = scene;
        if (scene != null) {
            scene.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        } else {
            hide();
        }
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /** The list (for tests). */
    CompletionPopup popup() {
        return popup;
    }

    /** What Ctrl+Space said instead of a list, or "" — for tests. */
    String note() {
        return note.isShowing() ? note.getText() : "";
    }

    private void onKey(KeyEvent e) {
        if (!field.isFocused() || field.isDisabled()) {
            return;
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown() && !e.isAltDown() && !e.isShiftDown()) {
            ask(true);
            e.consume();
            return;
        }
        if (!popup.isShowing()) {
            return;
        }
        switch (e.getCode()) {
            case UP -> popup.moveUp();
            case DOWN -> popup.moveDown();
            case PAGE_UP -> popup.pageUp();
            case PAGE_DOWN -> popup.pageDown();
            case ENTER, TAB -> accept(popup.selected());
            case ESCAPE -> hide();
            default -> {
                return; // typing: the field takes it, and its text listener asks again
            }
        }
        e.consume();
    }

    /** Asks the adapter; {@code explicit} (Ctrl+Space) says so when there is nothing to show. */
    private void ask(boolean explicit) {
        if (!field.isFocused()) {
            return;
        }
        if (!source.paused()) {
            hide();
            if (explicit) {
                say(tr("debug.complete.needsPause"));
            }
            return;
        }
        String text = field.getText() == null ? "" : field.getText();
        int caret = field.getCaretPosition();
        int asked = ++request;
        source.complete(text, caret, targets -> {
            if (asked != request || !field.isFocused()) {
                return;
            }
            List<Completion> items = items(targets);
            if (items.isEmpty()) {
                popup.hide();
                if (explicit) {
                    say(tr("debug.complete.none"));
                }
                return;
            }
            note.hide();
            popup.setQuery(DebugCompletionText.prefix(text, caret));
            popup.show(field.getScene().getWindow(), caretBounds(), items, 0);
        });
    }

    private static List<Completion> items(List<DapModels.CompletionTarget> targets) {
        List<DapModels.CompletionTarget> sorted = new ArrayList<>(targets);
        // The adapter's relevance first (java-debug: locals before members before types), then by name.
        sorted.sort(Comparator.comparing((DapModels.CompletionTarget t) -> t.sortText() == null ? "" : t.sortText())
                .thenComparing(t -> t.label().toLowerCase(java.util.Locale.ROOT)));
        List<Completion> out = new ArrayList<>();
        for (DapModels.CompletionTarget t : sorted) {
            DebugCompletionText.Shown shown = DebugCompletionText.shown(t.label());
            out.add(Completion.lsp(
                    shown.label(), t.insert(), shown.detail(), null, icon(t.type()), t.sortText(), false, false, t));
        }
        return out;
    }

    private static CompletionIconKind icon(String type) {
        return switch (type == null ? "" : type) {
            case "method", "function" -> CompletionIconKind.METHOD;
            case "field", "property" -> CompletionIconKind.FIELD;
            case "variable" -> CompletionIconKind.VARIABLE;
            case "class" -> CompletionIconKind.CLASS;
            case "interface" -> CompletionIconKind.INTERFACE;
            case "enum" -> CompletionIconKind.ENUM;
            case "keyword" -> CompletionIconKind.KEYWORD;
            case "module" -> CompletionIconKind.MODULE;
            default -> CompletionIconKind.OTHER;
        };
    }

    private void accept(Completion c) {
        if (c == null || !(c.resolveToken() instanceof DapModels.CompletionTarget t)) {
            hide();
            return;
        }
        DebugCompletionText.Edit edit = DebugCompletionText.apply(
                field.getText(), field.getCaretPosition(), t, DebugCompletionText.hasParameters(t.label()));
        hide();
        field.setText(edit.text());
        field.positionCaret(edit.caret());
    }

    void hide() {
        request++;
        popup.hide();
    }

    private void say(String message) {
        note.setText(message);
        Bounds b = field.localToScreen(field.getBoundsInLocal());
        if (b != null && field.getScene() != null) {
            note.show(field, b.getMinX(), b.getMaxY() + 2);
            noteTimer.playFromStart();
        }
    }

    /** Where the caret is on screen, approximately: the text before it measured in the field's font. */
    private Bounds caretBounds() {
        Bounds b = field.localToScreen(field.getBoundsInLocal());
        if (b == null) {
            return new BoundingBox(0, 0, 0, 0);
        }
        Text measure = new Text(field.getText().substring(0, Math.min(field.getCaretPosition(), field.getLength())));
        measure.setFont(field.getFont());
        double x = Math.min(
                b.getMaxX() - 40, b.getMinX() + 8 + measure.getLayoutBounds().getWidth());
        return new BoundingBox(Math.max(b.getMinX(), x), b.getMinY(), 1, b.getHeight());
    }
}
