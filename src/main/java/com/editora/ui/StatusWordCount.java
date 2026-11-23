package com.editora.ui;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.util.Duration;

import com.editora.config.Settings;
import com.editora.editor.ControlNames;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.WordCount;
import com.editora.text.LanguageRegistry;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The status bar's word count, for prose buffers (Markdown and plain text): the document's words, or the
 * selection's while there is one, with the reading time in the tooltip.
 *
 * <p>Never on the keystroke path, never on the FX thread. The document is counted where the size segment is — once
 * per typing burst, from the text that segment already took — on a background thread, whatever its size. A selection
 * change only notes the new length and restarts a pause; the selected text is read when the pause ends.
 */
final class StatusWordCount {

    /** Where words are counted: off the FX thread, whatever the size (PERF-01, Pauta 6.2.0). */
    static final String COUNT_SITE = "StatusWordCount.count";

    private final Label label = new Label();
    private final Supplier<Settings> settings;
    private final PauseTransition selectionPause = new PauseTransition(Duration.millis(250));

    /** Where the text is counted: where {@code OffFx} runs work; replaceable so a test can run it inline. */
    Executor counter = task -> com.editora.perf.OffFx.start("status-words", task);

    private long generation;
    private int documentWords = -1;
    private int selectionLength;
    private @Nullable CodeArea selectionArea;

    StatusWordCount(Supplier<Settings> settings) {
        this.settings = settings;
        label.getStyleClass().addAll("status-segment", "status-word-count");
        selectionPause.setOnFinished(e -> countSelection());
        hide();
    }

    Label label() {
        return label;
    }

    static boolean counts(@Nullable EditorBuffer buffer) {
        return buffer != null && (buffer.isMarkdown() || LanguageRegistry.PLAINTEXT.equals(buffer.getLanguage()));
    }

    private boolean enabled(@Nullable EditorBuffer buffer, boolean simpleMode) {
        Settings s = settings.get();
        return !simpleMode && s != null && s.isStatusBarWordCount() && counts(buffer);
    }

    /** The document changed, or another buffer is active: {@code content} is its text now (may be null). */
    void document(@Nullable EditorBuffer buffer, @Nullable String content, boolean simpleMode) {
        long gen = ++generation;
        selectionPause.stop();
        selectionLength = 0;
        selectionArea = null;
        if (buffer == null || !enabled(buffer, simpleMode) || content == null) {
            documentWords = -1;
            hide();
            return;
        }
        // Counted off the FX thread; the count shown stays until the latest one lands, and is applied only to the
        // document version it was taken from (a newer version calls this again).
        long version = buffer.docVersion();
        counter.execute(() -> {
            com.editora.perf.BlockingSites.enter(COUNT_SITE);
            int words = WordCount.words(content);
            com.editora.perf.OffFx.onFx(() -> {
                if (gen == generation && buffer.docVersion() == version) {
                    documentWords = words;
                    show(words, false);
                    selection(buffer.getFocusedArea());
                }
            });
        });
    }

    /** The caret or selection moved in {@code area}: cheap, called on every such move. */
    void selection(CodeArea area) {
        if (documentWords < 0 || area == null) {
            return;
        }
        int length = area.getSelection().getLength();
        if (length == selectionLength && area == selectionArea) {
            return;
        }
        selectionLength = length;
        selectionArea = area;
        if (length == 0) {
            selectionPause.stop();
            show(documentWords, false);
        } else {
            selectionPause.playFromStart();
        }
    }

    /** Counts the selection now (the pause calls it; tests call it to skip the wait). */
    void countSelection() {
        CodeArea area = selectionArea;
        if (documentWords < 0 || area == null || area.getSelection().getLength() == 0) {
            return;
        }
        String selected = area.getSelectedText();
        long gen = generation;
        int length = selected.length();
        counter.execute(() -> {
            com.editora.perf.BlockingSites.enter(COUNT_SITE);
            int words = WordCount.words(selected);
            com.editora.perf.OffFx.onFx(() -> {
                if (gen == generation && selectionLength == length) {
                    show(words, true);
                }
            });
        });
    }

    private void show(int words, boolean selected) {
        label.setText(tr(selected ? "statusbar.wordsSelected" : "statusbar.words", words));
        ControlNames.tip(label, tr("statusbar.tip.words", WordCount.readingMinutes(words)));
        label.setVisible(true);
        label.setManaged(true);
    }

    private void hide() {
        label.setVisible(false);
        label.setManaged(false);
    }
}
