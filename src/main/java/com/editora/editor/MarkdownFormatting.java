package com.editora.editor;

import java.util.function.Function;

import com.editora.markdown.MarkdownFormat;
import com.editora.markdown.MarkdownHeadingNumbers;
import com.editora.markdown.MarkdownLintFix;
import com.editora.markdown.MarkdownLintOptions;
import org.fxmisc.richtext.CodeArea;

/**
 * The whole-document Markdown commands — Format Document / Selection and heading numbers — applied to a
 * buffer: one undo step each, the caret and the top line kept where they were, and refused (false, nothing
 * changed) on a buffer that is not editable Markdown or is shown as a preview only.
 *
 * <p>They work on the text the editor shows, so a narrowed buffer formats its visible part.
 */
public final class MarkdownFormatting {

    private MarkdownFormatting() {}

    /** What a command did. */
    public enum Outcome {
        /** Not an editable Markdown buffer (or preview-only): nothing was attempted. */
        REFUSED,
        /** The text was already as the command would leave it. */
        UNCHANGED,
        CHANGED
    }

    /**
     * The lint options a buffer is formatted with — the project's {@code .markdownlint} choices, so the
     * formatter and the linter agree on, say, which bullet a list uses. Installed by the window; the
     * default is markdownlint's own.
     */
    private static volatile Function<EditorBuffer, MarkdownLintOptions> optionsSource =
            b -> MarkdownLintOptions.DEFAULT;

    public static void setOptionsSource(Function<EditorBuffer, MarkdownLintOptions> source) {
        optionsSource = source == null ? b -> MarkdownLintOptions.DEFAULT : source;
    }

    private static MarkdownLintOptions options(EditorBuffer b) {
        try {
            MarkdownLintOptions o = optionsSource.apply(b);
            return o == null ? MarkdownLintOptions.DEFAULT : o;
        } catch (RuntimeException e) {
            return MarkdownLintOptions.DEFAULT;
        }
    }

    public static boolean canRun(EditorBuffer b) {
        return b != null && b.canFormatMarkdown() && !b.isPreviewOnly();
    }

    public static Outcome formatDocument(EditorBuffer b) {
        if (!canRun(b)) {
            return Outcome.REFUSED;
        }
        CodeArea area = b.getArea();
        String text = area.getText();
        return replace(b, text, MarkdownFormat.format(text, b.getTabSize(), options(b)));
    }

    /** Formats the lines the selection touches; with no selection, the caret's line. */
    public static Outcome formatSelection(EditorBuffer b) {
        if (!canRun(b)) {
            return Outcome.REFUSED;
        }
        CodeArea area = b.getFocusedArea() == null ? b.getArea() : b.getFocusedArea();
        var selection = area.getCaretSelectionBind();
        int from = selection.getStartParagraphIndex();
        int to = selection.getEndParagraphIndex();
        if (to > from && selection.getEndColumnPosition() == 0) {
            to--; // a selection that ends at the start of a line does not include that line
        }
        String text = area.getText();
        return replace(b, text, MarkdownFormat.format(text, b.getTabSize(), options(b), from, to));
    }

    /** Adds section numbers to the headings, or brings the ones there up to date. */
    public static Outcome numberHeadings(EditorBuffer b) {
        return headingNumbers(b, true);
    }

    public static Outcome removeHeadingNumbers(EditorBuffer b) {
        return headingNumbers(b, false);
    }

    private static Outcome headingNumbers(EditorBuffer b, boolean number) {
        if (!canRun(b)) {
            return Outcome.REFUSED;
        }
        String text = b.getArea().getText();
        String next = number ? MarkdownHeadingNumbers.number(text) : MarkdownHeadingNumbers.remove(text);
        return replace(b, text, new MarkdownLintFix.Result(next, new int[0]));
    }

    private static Outcome replace(EditorBuffer b, String text, MarkdownLintFix.Result result) {
        if (result.text() == null || result.text().equals(text)) {
            return Outcome.UNCHANGED;
        }
        CodeArea area = b.getArea();
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        int top = Math.max(0, area.firstVisibleParToAllParIndex());
        boolean mapped = result.lineMap().length > 0;
        b.replaceVisibleText(area, result.text());
        int last = area.getParagraphs().size() - 1;
        int newLine = Math.max(0, Math.min(mapped ? result.mapLine(line) : line, last));
        area.moveTo(newLine, Math.min(column, area.getParagraphLength(newLine)));
        area.showParagraphAtTop(Math.max(0, Math.min(mapped ? result.mapLine(top) : top, last)));
        return Outcome.CHANGED;
    }
}
