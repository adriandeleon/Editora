package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import com.editora.command.KeyDispatcher;
import com.editora.dap.DapModels;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

/**
 * The debugger's expression input — the evaluate field at the top of Variables and the Evaluate window's Expression
 * field. IntelliJ's evaluate field is a small code editor, and so is this one:
 *
 * <ul>
 *   <li>syntax-highlighted in the debugged program's language ({@link #setLanguageFile});
 *   <li>the configured keymap's caret motion and editing ({@link CodeFieldKeymap}: Emacs {@code C-a}, {@code M-f},
 *       {@code C-k}…);
 *   <li>one line by default, where Enter evaluates; <b>Shift+Enter</b> expands it to a code fragment of several lines
 *       (Enter then breaks the line, keeping its indentation, and <b>Ctrl+Enter</b> evaluates), and collapses it back
 *       while it is one line. Pasted or recalled text of several lines expands it on its own;
 *   <li>the history of what was evaluated: Up / Down on the first / last line, and {@code M-p} / {@code M-n} anywhere;
 *   <li>the adapter's completions (Ctrl+Space, or after a {@code .}) through {@link DebugCompletion}.
 * </ul>
 *
 * Long text wraps: the field grows with it, up to a bound, and then scrolls.
 */
final class DebugExpressionField {

    /** What the field asks of its owner. */
    interface Owner {
        /** Whether there is a stopped frame (completion, Ctrl+Space's note). */
        boolean paused();

        /** The adapter's completions of {@code text} at {@code caret}, on the FX thread. */
        void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb);

        /** Enter (Ctrl+Enter in a code fragment) on non-blank text: evaluate it. */
        void submit(String expression);
    }

    private static final PseudoClass MULTI_LINE = PseudoClass.getPseudoClass("multi-line");
    /** Over this, the text is not highlighted: an expression field is not where files are pasted. */
    static final int MAX_HIGHLIGHTED = 8_000;

    /** The keys the field handles itself even where the keymap binds them to a global command. */
    static final java.util.Set<String> CLAIMED =
            java.util.Set.of("enter", "S-enter", "C-enter", "Cmd-enter", "up", "down", "M-p", "M-n", "tab", "S-tab");

    private final CodeArea area = AreaUndo.bounded(new CodeArea());
    private final VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
    /** Carries the frame: the scroll pane lays the area over its own insets, which would cover a border of its own. */
    private final javafx.scene.layout.StackPane frame = new javafx.scene.layout.StackPane(scroll);

    private final Owner owner;
    private final DebugPanelText.History history;
    private final BooleanProperty multiLine = new SimpleBooleanProperty(false);
    private final Label placeholder = new Label();
    private final DebugCompletion completion;
    private @Nullable IGrammar grammar;
    private String languageFile = "";
    private double lineHeight = 18;
    private @Nullable Consumer<Boolean> onTab;

    DebugExpressionField(Owner owner, DebugPanelText.History history) {
        this(owner, history, com.editora.command.TextInputKeymap::sharedKeymap);
    }

    /** As the field of the app, its keys from {@code keymap} (read at each key press) — a test's own keymap. */
    DebugExpressionField(
            Owner owner,
            DebugPanelText.History history,
            java.util.function.Supplier<com.editora.command.@Nullable KeymapManager> keymap) {
        this.owner = owner;
        this.history = history;
        area.getStyleClass().addAll("editor-area", "debug-expression");
        scroll.getStyleClass().add("debug-expression-scroll");
        frame.getStyleClass().add("debug-expression-frame");
        area.setWrapText(true);
        placeholder.getStyleClass().add("debug-expression-placeholder");
        area.setPlaceholder(placeholder, javafx.geometry.Pos.CENTER_LEFT);
        area.getProperties().put(KeyDispatcher.CLAIMED_KEYS, CLAIMED);
        area.setAccessibleRole(javafx.scene.AccessibleRole.TEXT_AREA);
        // Its own keys first (Enter, history), then the keymap's; completion's list keys run before both, on the scene.
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        CodeFieldKeymap.install(
                area,
                new CodeFieldKeymap.Hooks() {
                    @Override
                    public void lineUpAtTop() {
                        recall(true);
                    }

                    @Override
                    public void lineDownAtBottom() {
                        recall(false);
                    }
                },
                keymap);
        completion = DebugCompletion.attach(area, new DebugCompletion.Source() {
            @Override
            public boolean paused() {
                return owner.paused();
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                owner.complete(text, caret, cb);
            }
        });
        area.textProperty().addListener((o, was, now) -> {
            if (now.indexOf('\n') >= 0 && !multiLine.get()) {
                multiLine.set(true); // pasted, or recalled from the history: a fragment of several lines
            }
            highlight();
        });
        multiLine.addListener((o, was, now) -> {
            area.pseudoClassStateChanged(MULTI_LINE, now);
            frame.pseudoClassStateChanged(MULTI_LINE, now);
            fitHeight();
        });
        area.totalHeightEstimateProperty().addListener((o, was, now) -> fitHeight());
        area.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                area.deselect();
            }
        });
        fitHeight();
    }

    /** The node to lay out. */
    Region node() {
        return frame;
    }

    /** The editable area itself (focus, tests). */
    CodeArea area() {
        return area;
    }

    DebugCompletion completion() {
        return completion;
    }

    String getText() {
        return area.getText();
    }

    /** Replaces the text, the caret at its end. */
    void setText(@Nullable String text) {
        String t = text == null ? "" : text;
        if (!t.equals(area.getText())) { // the same text again would only lose its highlighting
            area.replaceText(t);
        }
        area.moveTo(area.getLength());
        area.requestFollowCaret();
    }

    void clear() {
        area.clear();
        multiLine.set(false);
    }

    void requestFocus() {
        area.requestFocus();
    }

    void selectAll() {
        area.selectAll();
    }

    void setPromptText(String text) {
        placeholder.setText(text);
    }

    void setDisable(boolean disabled) {
        area.setDisable(disabled);
    }

    boolean isDisabled() {
        return area.isDisabled();
    }

    BooleanProperty multiLineProperty() {
        return multiLine;
    }

    boolean isMultiLine() {
        return multiLine.get();
    }

    /** Shift+Enter's switch, also on a button: a code fragment of several lines, or back to one line. */
    void toggleMultiLine() {
        if (multiLine.get() && area.getText().indexOf('\n') >= 0) {
            return; // several lines do not fit in one: delete the line breaks first
        }
        multiLine.set(!multiLine.get());
        area.requestFocus();
    }

    /** Where Tab and Shift+Tab ({@code true}) go on one line; a code fragment indents instead. */
    void setOnTab(@Nullable Consumer<Boolean> onTab) {
        this.onTab = onTab;
    }

    /** The editor's font, so an expression reads as the code it is about. */
    void setFont(String family, int size) {
        area.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
        placeholder.setStyle("-fx-font-size: " + size + "px;");
        lineHeight = Math.max(12, size * 1.45);
        fitHeight();
    }

    /**
     * Highlights in the grammar of files named like {@code fileName} (the stopped frame's source: {@code Main.java},
     * {@code app.py}); blank: no highlighting. The grammar loads off the FX thread the first time.
     */
    void setLanguageFile(@Nullable String fileName) {
        String name = fileName == null ? "" : fileName;
        if (name.equals(languageFile)) {
            return;
        }
        languageFile = name;
        if (name.isBlank()) {
            grammar = null;
            highlight();
            return;
        }
        GrammarRegistry registry = GrammarRegistry.shared();
        IGrammar cached = registry.cachedForFileName(name);
        if (cached != null) {
            grammar = cached;
            highlight();
            return;
        }
        Thread.ofVirtual().name("debug-expression-grammar").start(() -> {
            IGrammar loaded;
            try {
                loaded = registry.forFileName(name);
            } catch (RuntimeException e) {
                loaded = null;
            }
            IGrammar found = loaded;
            Platform.runLater(() -> {
                if (name.equals(languageFile)) {
                    grammar = found;
                    highlight();
                }
            });
        });
    }

    /** The grammar file the field highlights for ("" for none) — for tests. */
    String languageFile() {
        return languageFile;
    }

    private void highlight() {
        String text = area.getText();
        IGrammar g = grammar;
        if (text.isEmpty()) {
            return;
        }
        if (g == null || text.length() > MAX_HIGHLIGHTED) {
            area.clearStyle(0, text.length());
            return;
        }
        try {
            StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, g);
            if (spans != null) {
                area.setStyleSpans(0, spans);
            }
        } catch (RuntimeException ignored) {
            // A tokenizer hiccup: the text stays as it was styled.
        }
    }

    /** As tall as its text (it wraps), between one line (four for a code fragment) and a bound; then it scrolls. */
    private void fitHeight() {
        double pad = multiLine.get() ? 26 : 9; // the content's padding and the frame, and a fragment's line to type on
        double min = (multiLine.get() ? 4 : 1) * lineHeight + pad;
        double max = (multiLine.get() ? 14 : 4) * lineHeight + pad;
        Double estimate = area.totalHeightEstimateProperty().getValue();
        double content = estimate == null ? min : estimate + pad;
        double h = Math.clamp(content, min, max);
        frame.setPrefHeight(h);
        frame.setMinHeight(min);
        frame.setMaxHeight(max);
    }

    private void onKey(KeyEvent e) {
        if (e.isConsumed()) {
            return; // the completion list's Enter / arrows
        }
        boolean shortcut = e.isShortcutDown() || e.isControlDown();
        boolean plain = !shortcut && !e.isAltDown() && !e.isMetaDown() && !e.isShiftDown();
        switch (e.getCode()) {
            case ENTER -> {
                if (e.isShiftDown() && !shortcut && !e.isAltDown()) {
                    toggleMultiLine();
                } else if (shortcut && !e.isShiftDown() && !e.isAltDown()) {
                    submit();
                } else if (plain && multiLine.get()) {
                    newLine();
                } else if (plain) {
                    submit();
                } else {
                    return;
                }
                e.consume();
            }
            case UP, DOWN -> {
                if (!plain) {
                    return;
                }
                boolean up = e.getCode() == KeyCode.UP;
                boolean atEdge = up
                        ? area.getCurrentParagraph() == 0
                        : area.getCurrentParagraph() >= area.getParagraphs().size() - 1;
                if (!multiLine.get() || atEdge) {
                    recall(up);
                    e.consume();
                }
            }
            case P, N -> {
                if (e.isAltDown() && !shortcut && !e.isMetaDown() && !e.isShiftDown()) {
                    recall(e.getCode() == KeyCode.P);
                    e.consume();
                }
            }
            case TAB -> {
                if (shortcut || e.isAltDown()) {
                    return;
                }
                if (multiLine.get() && !e.isShiftDown()) {
                    area.replaceSelection("    ");
                } else if (onTab != null) {
                    onTab.accept(e.isShiftDown());
                }
                e.consume(); // never a tab character in the expression
            }
            default -> {}
        }
    }

    private void submit() {
        String text = area.getText();
        if (text.isBlank()) {
            return;
        }
        completion.hide();
        history.add(text.strip());
        owner.submit(text.strip());
    }

    /** A line break that keeps the current line's indentation. */
    private void newLine() {
        String text = area.getText();
        int caret = area.getCaretPosition();
        int start = com.editora.command.TextInputKeymap.lineStart(text, caret);
        int indent = start;
        while (indent < caret && (text.charAt(indent) == ' ' || text.charAt(indent) == '\t')) {
            indent++;
        }
        area.replaceSelection("\n" + text.substring(start, indent));
        area.requestFollowCaret();
    }

    /** Up / M-p ({@code older}) or Down / M-n through what was evaluated. */
    private void recall(boolean older) {
        String text = older ? history.up(area.getText()) : history.down();
        if (text != null) {
            setText(text);
        }
    }

    /** The history entries, newest last (the window's history list). */
    List<String> historyEntries() {
        return history.entries();
    }
}
