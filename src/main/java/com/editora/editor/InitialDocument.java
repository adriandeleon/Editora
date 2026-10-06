package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.ReadOnlyStyledDocumentBuilder;
import org.fxmisc.richtext.model.StyledSegment;

/**
 * A loaded file's text made ready for the one RichTextFX insertion a load needs: the LF-normalised text, the
 * line ending the file used, and — when prepared ahead — the immutable styled document itself.
 *
 * <p>Splitting a large text into paragraphs is most of what {@code replaceText} costs (two thirds of the
 * FX-thread time of a 49 MB open), and it touches no scene-graph state: the result is an immutable value
 * built from the text, the area's segment operations and its initial styles, all of which are final. So
 * {@link #prepare} may run on the thread that read the file, leaving only the document swap for the FX thread.
 *
 * @param text the document text, never holding a {@code \r}
 * @param lineEnding the {@link LineEndings} label of the file's dominant line ending
 * @param mixedLineEndings whether the file used more than one kind of line ending
 * @param document the prepared paragraphs, or null to let the area split {@link #text} itself
 */
public record InitialDocument(
        String text,
        String lineEnding,
        boolean mixedLineEndings,
        ReadOnlyStyledDocument<Collection<String>, String, Collection<String>> document) {

    /** A paragraph wider than this is installed as several style segments (see {@link #segmented}). */
    private static final int LONG_LINE_SEGMENT_CHARS = 4 * 1024;

    private static final Collection<String> LONG_LINE_SEGMENT_A = List.of("long-line-segment-a");
    private static final Collection<String> LONG_LINE_SEGMENT_B = List.of("long-line-segment-b");

    /** Normalises {@code content} only; the area splits it into paragraphs when it is installed. */
    static InitialDocument ofText(String content) {
        return new InitialDocument(
                LineEndings.toLf(content), LineEndings.dominant(content), LineEndings.mixed(content), null);
    }

    /**
     * Normalises {@code content} and builds its paragraphs for {@code area}. Safe away from the FX thread:
     * only the area's immutable segment operations and initial styles are read.
     *
     * @param segmentLongLines split giant paragraphs into visually identical style segments
     */
    static InitialDocument prepare(CodeArea area, String content, boolean segmentLongLines) {
        String text = LineEndings.toLf(content);
        ReadOnlyStyledDocument<Collection<String>, String, Collection<String>> document = segmentLongLines
                ? segmented(area, text)
                : ReadOnlyStyledDocument.fromString(
                        text, area.getInitialParagraphStyle(), area.getInitialTextStyle(), area.getSegOps());
        // RichTextFX asks the inserted document for its text while it publishes the change (the plain-text
        // identity filter), which joins every paragraph — a quarter of a 49 MB insertion. The document caches
        // the answer, so ask here, on the reading thread, and the FX thread finds it ready.
        document.getText();
        return new InitialDocument(text, LineEndings.dominant(content), LineEndings.mixed(content), document);
    }

    /**
     * JavaFX Text lays out one RichTextFX segment as one text node; a minified 300 KiB source in a single node
     * can monopolize the FX thread for many seconds. Alternating two no-op style classes keeps the document
     * text and paragraph model exact while bounding each node's glyph run.
     */
    static ReadOnlyStyledDocument<Collection<String>, String, Collection<String>> segmented(
            CodeArea area, String text) {
        var builder = new ReadOnlyStyledDocumentBuilder<Collection<String>, String, Collection<String>>(
                area.getSegOps(), area.getInitialParagraphStyle());
        int paragraphStart = 0;
        while (true) {
            int newline = text.indexOf('\n', paragraphStart);
            int paragraphEnd = newline < 0 ? text.length() : newline;
            int length = paragraphEnd - paragraphStart;
            if (length == 0) {
                builder.addParagraph("", Collections.emptyList());
            } else if (length <= LONG_LINE_SEGMENT_CHARS) {
                builder.addParagraph(text.substring(paragraphStart, paragraphEnd), Collections.emptyList());
            } else {
                List<StyledSegment<String, Collection<String>>> segments =
                        new ArrayList<>((length + LONG_LINE_SEGMENT_CHARS - 1) / LONG_LINE_SEGMENT_CHARS);
                int chunk = 0;
                for (int start = paragraphStart; start < paragraphEnd; start += LONG_LINE_SEGMENT_CHARS) {
                    int end = Math.min(paragraphEnd, start + LONG_LINE_SEGMENT_CHARS);
                    Collection<String> style = (chunk++ & 1) == 0 ? LONG_LINE_SEGMENT_A : LONG_LINE_SEGMENT_B;
                    segments.add(new StyledSegment<>(text.substring(start, end), style));
                }
                builder.addParagraph(segments);
            }
            if (newline < 0) {
                return builder.build();
            }
            paragraphStart = newline + 1;
        }
    }
}
