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
import com.editora.editor.completion.CompletionPopup;
import org.jspecify.annotations.Nullable;

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

    /** The field completed in: a plain {@link TextField} or an expression {@link org.fxmisc.richtext.CodeArea}. */
    interface Input {
        javafx.scene.Node node();

        javafx.beans.value.ObservableValue<String> textProperty();

        String text();

        int caret();

        void replace(String text, int caret);

        /** Where the caret is on screen (approximately for a text field); null when not shown. */
        @Nullable
        Bounds caretOnScreen();
    }

    private final Input field;
    private final javafx.scene.Node node;
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

    private DebugCompletion(Input field, Source source) {
        this.field = field;
        this.node = field.node();
        this.source = source;
        note.getStyleClass().add("debug-value-tooltip");
        noteTimer.setOnFinished(e -> note.hide());
        popup.setOnAccept(this::accept);
        node.addEventFilter(KeyEvent.KEY_TYPED, e -> typedDot = ".".equals(e.getCharacter()));
        field.textProperty().addListener((o, was, now) -> {
            if (typedDot) {
                typedDot = false;
                Platform.runLater(() -> ask(false));
            } else if (popup.isShowing()) {
                Platform.runLater(() -> ask(false));
            }
        });
        node.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                hide();
            }
        });
        node.sceneProperty().addListener((o, was, now) -> watch(now));
        watch(node.getScene());
    }

    /** Adds completion to {@code field}. */
    static DebugCompletion attach(TextField field, Source source) {
        return attach(textFieldInput(field), source);
    }

    /** Adds completion to an expression {@code area}. */
    static DebugCompletion attach(org.fxmisc.richtext.CodeArea area, Source source) {
        return attach(codeAreaInput(area), source);
    }

    private static DebugCompletion attach(Input input, Source source) {
        DebugCompletion completion = new DebugCompletion(input, source);
        input.node().getProperties().put(DebugCompletion.class, completion);
        return completion;
    }

    /** The completion attached to {@code field}, or null. */
    static @Nullable DebugCompletion of(javafx.scene.Node field) {
        return field.getProperties().get(DebugCompletion.class) instanceof DebugCompletion c ? c : null;
    }

    private static Input textFieldInput(TextField field) {
        return new Input() {
            @Override
            public javafx.scene.Node node() {
                return field;
            }

            @Override
            public javafx.beans.value.ObservableValue<String> textProperty() {
                return field.textProperty();
            }

            @Override
            public String text() {
                return field.getText() == null ? "" : field.getText();
            }

            @Override
            public int caret() {
                return field.getCaretPosition();
            }

            @Override
            public void replace(String text, int caret) {
                field.setText(text);
                field.positionCaret(caret);
            }

            @Override
            public @Nullable Bounds caretOnScreen() {
                // Approximately: the text before the caret measured in the field's font.
                Bounds b = field.localToScreen(field.getBoundsInLocal());
                if (b == null) {
                    return null;
                }
                Text measure = new Text(text().substring(0, Math.min(caret(), text().length())));
                measure.setFont(field.getFont());
                double x = Math.min(
                        b.getMaxX() - 40,
                        b.getMinX() + 8 + measure.getLayoutBounds().getWidth());
                return new BoundingBox(Math.max(b.getMinX(), x), b.getMinY(), 1, b.getHeight());
            }
        };
    }

    private static Input codeAreaInput(org.fxmisc.richtext.CodeArea area) {
        return new Input() {
            @Override
            public javafx.scene.Node node() {
                return area;
            }

            @Override
            public javafx.beans.value.ObservableValue<String> textProperty() {
                return area.textProperty();
            }

            @Override
            public String text() {
                return area.getText();
            }

            @Override
            public int caret() {
                return area.getCaretPosition();
            }

            @Override
            public void replace(String text, int caret) {
                area.replaceText(text);
                area.moveTo(Math.clamp(caret, 0, area.getLength()));
            }

            @Override
            public @Nullable Bounds caretOnScreen() {
                Bounds caret = area.getCaretBounds().orElse(null);
                if (caret != null) {
                    return caret;
                }
                return area.localToScreen(area.getBoundsInLocal());
            }
        };
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
        if (!node.isFocused() || node.isDisabled()) {
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
        if (!node.isFocused()) {
            return;
        }
        if (!source.paused()) {
            hide();
            if (explicit) {
                say(tr("debug.complete.needsPause"));
            }
            return;
        }
        String text = field.text();
        int caret = field.caret();
        int asked = ++request;
        source.complete(text, caret, targets -> {
            if (asked != request || !node.isFocused() || node.getScene() == null) {
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
            popup.show(node.getScene().getWindow(), caretBounds(), items, 0);
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
        DebugCompletionText.Edit edit =
                DebugCompletionText.apply(field.text(), field.caret(), t, DebugCompletionText.hasParameters(t.label()));
        hide();
        field.replace(edit.text(), edit.caret());
    }

    void hide() {
        request++;
        popup.hide();
    }

    private void say(String message) {
        note.setText(message);
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        if (b != null && node.getScene() != null) {
            note.show(node, b.getMinX(), b.getMaxY() + 2);
            noteTimer.playFromStart();
        }
    }

    /** Where the caret is on screen. */
    private Bounds caretBounds() {
        Bounds b = field.caretOnScreen();
        return b == null ? new BoundingBox(0, 0, 0, 0) : b;
    }
}
