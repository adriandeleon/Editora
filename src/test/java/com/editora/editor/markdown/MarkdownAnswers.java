package com.editora.editor.markdown;

import java.util.concurrent.atomic.AtomicReference;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answers of the Markdown commands that work the document out off the JavaFX thread (PERF-01, Pauta 6.0.1),
 * read at once by a test that runs that work in place ({@code com.editora.ui.OffFxInPlace}): called on the FX
 * thread, each answer lands before the command returns.
 */
public final class MarkdownAnswers {

    private MarkdownAnswers() {}

    private static <T> T heard(AtomicReference<@Nullable T> answer, String what) {
        T value = answer.get();
        assertTrue(value != null, what + " answered in place (is OffFxInPlace on?)");
        return value;
    }

    /** {@link EditorBuffer#insertOrUpdateToc}: whether there was a table of contents to write. */
    public static boolean toc(EditorBuffer b) {
        AtomicReference<@Nullable Boolean> r = new AtomicReference<>();
        b.insertOrUpdateToc(r::set);
        return heard(r, "the table of contents");
    }

    /** {@link MarkdownEditing#updateTocForSave}: whether it changed the text. */
    public static boolean tocForSave(EditorBuffer b) {
        String before = b.getArea().getText();
        boolean[] went = {false};
        b.markdown().updateTocForSave(() -> went[0] = true);
        assertTrue(went[0], "the save went on");
        return !before.equals(b.getArea().getText());
    }

    /** {@link MarkdownEditing#convertLink}: whether it converted. */
    public static boolean convertLink(EditorBuffer b, boolean toInline) {
        AtomicReference<@Nullable Integer> r = new AtomicReference<>();
        b.markdown().convertLink(toInline, r::set);
        return heard(r, "the conversion") == 0;
    }

    /** {@link MarkdownEditing#convertWikiLink}: 0 converted, 1 no link, 2 not local. */
    public static int convertWikiLink(EditorBuffer b) {
        AtomicReference<@Nullable Integer> r = new AtomicReference<>();
        b.markdown().convertWikiLink(r::set);
        return heard(r, "the conversion");
    }

    /** {@link MarkdownEditing#removeLink}: whether there was a link. */
    public static boolean removeLink(EditorBuffer b) {
        AtomicReference<@Nullable Boolean> r = new AtomicReference<>();
        b.markdown().removeLink(r::set);
        return heard(r, "the removal");
    }

    /** {@link MarkdownEditing#linkForEditing}. */
    public static @Nullable String[] linkForEditing(EditorBuffer b) {
        AtomicReference<@Nullable String[]> r = new AtomicReference<>();
        b.markdown().linkForEditing(r::set);
        return heard(r, "the link");
    }

    /** {@link EditorBuffer#openLinkUnderCaret}: whether there was a link. */
    public static boolean openLink(EditorBuffer b) {
        AtomicReference<@Nullable Boolean> r = new AtomicReference<>();
        b.openLinkUnderCaret(r::set);
        return heard(r, "the link");
    }

    /** {@link MarkdownEditing#linkUnderCaret}: the destination, or null. */
    public static @Nullable String linkUnderCaret(EditorBuffer b) {
        AtomicReference<@Nullable String> r = new AtomicReference<>();
        boolean[] went = {false};
        b.markdown().linkUnderCaret(url -> {
            r.set(url);
            went[0] = true;
        });
        assertTrue(went[0], "the link answered in place");
        return r.get();
    }

    /** {@link MarkdownEditing#pasteAsMarkdown}, inserted as the paste does: whether it converted. */
    public static boolean pasteAsMarkdown(EditorBuffer b, boolean explicit) {
        boolean[] went = {false};
        AtomicReference<@Nullable String> r = new AtomicReference<>();
        b.markdown().pasteAsMarkdown(explicit, markdown -> {
            r.set(markdown);
            went[0] = true;
        });
        assertTrue(went[0], "the conversion answered in place");
        String markdown = r.get();
        if (markdown != null) {
            b.insertAtCaret(markdown);
        }
        return markdown != null;
    }
}
