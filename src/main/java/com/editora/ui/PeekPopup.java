package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

import static com.editora.i18n.Messages.tr;

/**
 * Shows a definition where you are, instead of taking you to it.
 *
 * <p>Go-to-definition answers the question by moving you, which costs your place, your scroll position and
 * usually a tab. Most of the time the question is only "what is this" — a signature, a couple of lines of
 * body — and the answer does not justify the move. This shows those lines over the editor and leaves
 * everything else exactly where it was; {@code Enter} still commits to the real jump.
 *
 * <p>Shown through the shared {@link OverlayHost} rather than as a {@code javafx.stage.Popup}, for the
 * reason recorded in the in-scene-overlays decision: a Popup is a separate native window that on Windows
 * does not reliably take keyboard focus, and this needs Enter and Escape.
 */
final class PeekPopup {

    /** Lines shown before the definition, for context. */
    static final int LINES_BEFORE = 2;
    /** Lines shown from the definition onward. */
    static final int LINES_AFTER = 12;

    /** Tokenizing happens here, never on the FX thread: tm4e grammars are shared with the highlighters. */
    private static final ExecutorService HIGHLIGHT = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "peek-highlight");
        t.setDaemon(true);
        return t;
    });

    /**
     * What to show: the {@code lines} of the target, the 0-based index within them that is the definition
     * itself, and the 0-based document line the first of them came from (so the gutter numbers are real).
     */
    record Snippet(String title, List<String> lines, int focusIndex, int firstLine, String language) {}

    /**
     * The window of {@code text} around 0-based {@code line}, clamped to the document.
     *
     * <p>Pure, so the windowing is testable without a toolkit. The window is deliberately asymmetric —
     * a couple of lines of context above and a dozen from the definition down — because what you want
     * from a peek is the signature and the start of the body, not what happens to precede it.
     */
    static Snippet build(String title, String text, int line, String language) {
        String[] all = text.split("\n", -1);
        int focus = Math.max(0, Math.min(line, all.length - 1));
        int from = Math.max(0, focus - LINES_BEFORE);
        int to = Math.min(all.length, focus + LINES_AFTER);
        List<String> lines = new ArrayList<>(List.of(all).subList(from, Math.max(from + 1, to)));
        return new Snippet(title, lines, focus - from, from, language);
    }

    private final OverlayHost overlayHost;
    private final VBox rows = new VBox();
    private final Label title = new Label();
    private final VBox card;
    private Runnable onJump;
    private long generation;

    PeekPopup(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
        title.getStyleClass().add("palette-title");
        Label hint = new Label(tr("lsp.peek.hint"));
        hint.getStyleClass().add("palette-hint");
        rows.getStyleClass().add("peek-body");
        card = new VBox(6, title, rows, hint);
        card.getStyleClass().addAll("command-palette", "peek-popup");
        card.setPrefWidth(760);
        card.setMaxSize(760, Region.USE_PREF_SIZE);
        // Without this the global dispatcher treats Enter/Escape as editor chords and the popup never
        // sees them; every other in-scene overlay claims its keys the same way.
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        card.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            Runnable jump = onJump;
            overlayHost.hide();
            if (jump != null) {
                jump.run();
            }
            e.consume();
        }
        // Escape and C-g are the host's to handle, as everywhere else.
    }

    /** Shows {@code snippet}; {@code onJump} runs if the user commits to the real navigation. */
    void show(Snippet snippet, String fontFamily, int fontSize, Runnable onJump) {
        this.onJump = onJump;
        title.setText(snippet.title());
        rows.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        renderPlain(snippet);
        card.requestFocus();
        overlayHost.show(card, card::requestFocus, () -> this.onJump = null);
        scheduleHighlight(snippet);
    }

    /**
     * Renders the snippet as plain text immediately. Highlighting arrives afterwards if it arrives at
     * all — the popup must be readable the instant it opens, and a peek that waited on a tokenizer would
     * be slower than the jump it exists to avoid.
     */
    private void renderPlain(Snippet snippet) {
        rows.getChildren().clear();
        for (int i = 0; i < snippet.lines().size(); i++) {
            rows.getChildren()
                    .add(row(snippet, i, List.of(new Text(snippet.lines().get(i)))));
        }
    }

    private TextFlow row(Snippet snippet, int index, List<Text> runs) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("peek-row");
        if (index == snippet.focusIndex()) {
            flow.getStyleClass().add("peek-row-focus");
        }
        Text number = new Text(String.format("%4d  ", snippet.firstLine() + index + 1));
        number.getStyleClass().addAll("text", "peek-line-number");
        flow.getChildren().add(number);
        for (Text run : runs) {
            run.getStyleClass().add("text");
            flow.getChildren().add(run);
        }
        return flow;
    }

    /** Tokenizes off-thread and fills the styles in, discarding a result the next peek has superseded. */
    private void scheduleHighlight(Snippet snippet) {
        long gen = ++generation;
        String text = String.join("\n", snippet.lines());
        String language = snippet.language();
        if (language == null || language.isBlank() || text.isBlank()) {
            return;
        }
        HIGHLIGHT.submit(() -> {
            StyleSpans<Collection<String>> spans;
            try {
                var grammar = GrammarRegistry.shared().forLanguageName(language);
                if (grammar == null) {
                    return;
                }
                spans = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException | Error ex) {
                return; // the plain rendering already on screen is a perfectly good answer
            }
            Platform.runLater(() -> {
                if (gen == generation) {
                    applyHighlight(snippet, text, spans);
                }
            });
        });
    }

    private void applyHighlight(Snippet snippet, String text, StyleSpans<Collection<String>> spans) {
        List<List<Text>> perLine = splitByLine(text, spans);
        if (perLine.size() != snippet.lines().size()) {
            return; // the split disagrees with what is displayed; leave the plain rendering alone
        }
        rows.getChildren().clear();
        for (int i = 0; i < perLine.size(); i++) {
            rows.getChildren().add(row(snippet, i, perLine.get(i)));
        }
    }

    /**
     * Splits the tokenized text into one run list per line. The spans cover the joined text including its
     * newlines, so a span crossing a line boundary has to be cut at it rather than assigned to one side.
     */
    static List<List<Text>> splitByLine(String text, StyleSpans<Collection<String>> spans) {
        List<List<Text>> lines = new ArrayList<>();
        List<Text> current = new ArrayList<>();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int end = Math.min(text.length(), pos + span.getLength());
            while (pos < end) {
                int newline = text.indexOf('\n', pos);
                int stop = newline < 0 || newline >= end ? end : newline;
                if (stop > pos) {
                    Text t = new Text(text.substring(pos, stop));
                    t.getStyleClass().addAll(span.getStyle());
                    current.add(t);
                }
                if (newline >= 0 && newline < end) {
                    lines.add(current);
                    current = new ArrayList<>();
                    pos = newline + 1;
                } else {
                    pos = stop;
                }
            }
        }
        // Text past the last span still has to be split, or the line count comes back short and the
        // caller's size check quietly discards the whole highlight.
        while (pos < text.length()) {
            int newline = text.indexOf('\n', pos);
            int stop = newline < 0 ? text.length() : newline;
            if (stop > pos) {
                current.add(new Text(text.substring(pos, stop)));
            }
            if (newline < 0) {
                break;
            }
            lines.add(current);
            current = new ArrayList<>();
            pos = newline + 1;
        }
        lines.add(current);
        return lines;
    }
}
