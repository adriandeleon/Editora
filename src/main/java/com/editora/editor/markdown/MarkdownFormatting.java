package com.editora.editor.markdown;

import java.util.function.Consumer;
import java.util.function.Function;

import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownFormat;
import com.editora.markdown.MarkdownHeadingNumbers;
import com.editora.markdown.MarkdownLintFix;
import com.editora.markdown.MarkdownLintOptions;
import com.editora.perf.OffFx;
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

    /** {@link com.editora.perf.BlockingSites} name of a whole-document Markdown command, worked out off FX. */
    public static final String FORMAT_SITE = "MarkdownFormatting.work";

    /** How many times a command is worked out again when the text changed before its answer landed. */
    private static final int TRIES = 3;

    /**
     * Formats the whole document. The text is parsed and the edit worked out off the FX thread (PERF-01, Pauta
     * 6.2.0); {@code done} hears the outcome once it is applied, on the FX thread.
     */
    public static void formatDocument(EditorBuffer b, Consumer<Outcome> done) {
        if (!canRun(b)) {
            done.accept(Outcome.REFUSED);
            return;
        }
        int tabSize = b.getTabSize();
        MarkdownLintOptions options = options(b);
        run(b, text -> MarkdownFormat.format(text, tabSize, options), TRIES, done);
    }

    /** Formats the lines the selection touches; with no selection, the caret's line. As {@link #formatDocument}. */
    public static void formatSelection(EditorBuffer b, Consumer<Outcome> done) {
        if (!canRun(b)) {
            done.accept(Outcome.REFUSED);
            return;
        }
        CodeArea area = b.getFocusedArea() == null ? b.getArea() : b.getFocusedArea();
        var selection = area.getCaretSelectionBind();
        int from = selection.getStartParagraphIndex();
        int to = selection.getEndParagraphIndex();
        if (to > from && selection.getEndColumnPosition() == 0) {
            to--; // a selection that ends at the start of a line does not include that line
        }
        int last = to;
        int tabSize = b.getTabSize();
        MarkdownLintOptions options = options(b);
        run(b, text -> MarkdownFormat.format(text, tabSize, options, from, last), TRIES, done);
    }

    /** Adds section numbers to the headings, or brings the ones there up to date. As {@link #formatDocument}. */
    public static void numberHeadings(EditorBuffer b, Consumer<Outcome> done) {
        headingNumbers(b, true, done);
    }

    public static void removeHeadingNumbers(EditorBuffer b, Consumer<Outcome> done) {
        headingNumbers(b, false, done);
    }

    private static void headingNumbers(EditorBuffer b, boolean number, Consumer<Outcome> done) {
        if (!canRun(b)) {
            done.accept(Outcome.REFUSED);
            return;
        }
        run(
                b,
                text -> new MarkdownLintFix.Result(
                        number ? MarkdownHeadingNumbers.number(text) : MarkdownHeadingNumbers.remove(text), new int[0]),
                TRIES,
                done);
    }

    /** A command's answer, worked out off the FX thread: its result and the span of the text it changes. */
    private record Worked(MarkdownLintFix.Result result, EditorBuffer.PreparedEdit edit) {}

    /**
     * Works {@code command} out on the shown text off the FX thread, then applies it as one undo step if the text is
     * unchanged, keeping the caret and the top line; text typed meanwhile has it worked out again.
     */
    private static void run(
            EditorBuffer b, Function<String, MarkdownLintFix.Result> command, int tries, Consumer<Outcome> done) {
        CodeArea area = b.getArea();
        String text = area.getText();
        long version = b.docVersion();
        OffFx.then(
                FORMAT_SITE,
                () -> {
                    MarkdownLintFix.Result result = command.apply(text);
                    return result.text() == null || result.text().equals(text)
                            ? null
                            : new Worked(result, EditorBuffer.PreparedEdit.between(version, text, result.text()));
                },
                answer -> {
                    Worked worked = answer.value();
                    if (b.isDisposed()) {
                        done.accept(Outcome.REFUSED);
                    } else if (b.docVersion() != version) {
                        if (tries > 1 && canRun(b)) {
                            run(b, command, tries - 1, done);
                        } else {
                            done.accept(Outcome.REFUSED);
                        }
                    } else if (worked == null) {
                        done.accept(answer.ok() ? Outcome.UNCHANGED : Outcome.REFUSED);
                    } else {
                        apply(b, worked);
                        done.accept(Outcome.CHANGED);
                    }
                });
    }

    private static void apply(EditorBuffer b, Worked worked) {
        MarkdownLintFix.Result result = worked.result();
        CodeArea area = b.getArea();
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        int top = Math.max(0, area.firstVisibleParToAllParIndex());
        boolean mapped = result.lineMap().length > 0;
        b.applyPreparedVisibleEdit(area, worked.edit());
        int last = area.getParagraphs().size() - 1;
        int newLine = Math.max(0, Math.min(mapped ? result.mapLine(line) : line, last));
        area.moveTo(newLine, Math.min(column, area.getParagraphLength(newLine)));
        area.showParagraphAtTop(Math.max(0, Math.min(mapped ? result.mapLine(top) : top, last)));
    }
}
