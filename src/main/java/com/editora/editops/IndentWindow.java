package com.editora.editops;

import com.editora.text.HtmlElements;
import com.editora.text.HtmlRegions;
import org.jspecify.annotations.Nullable;

/**
 * The Enter / Tab / closer-dedent edits of {@link Indenter} and {@link PlainTab}, computed from a bounded
 * slice of the document instead of all of it.
 *
 * <p>Those algorithms take the whole text as a String but only ever read the caret's line and a bounded
 * stretch above it ({@link Indenter#MAX_SCAN} characters of back-scan, plus the lines of one wrapped
 * statement). An editor whose document is not a String has to build one to call them, which made every
 * Enter and Tab cost a copy of the document. This reads the slice they need through {@link Doc}, runs the
 * same code on it and moves the resulting offsets back. The slice always starts at a line start and reaches
 * further back than the algorithms can look, so the answer is the one the whole text gives.
 *
 * <p>Pure: no toolkit dependency.
 */
public final class IndentWindow {

    private IndentWindow() {}

    /** Line-addressed read access to a document; lines are separated by {@code '\n'}. */
    public interface Doc {
        int length();

        /** The line holding {@code offset}; an offset on a line break belongs to the line it ends. */
        int lineOf(int offset);

        int lineStart(int line);

        /** The line's length without its line break. */
        int lineLength(int line);

        String text(int from, int to);
    }

    /** How much of the document's head {@link Indenter#detectUnit} reads. */
    public static final int HEAD_CHARS = Indenter.MAX_SCAN;

    /**
     * The document's indent unit, as {@link Indenter#unitFor} gives it for the whole text: it is inferred
     * from the first {@link #HEAD_CHARS} characters only, and not read at all under an override. A caller
     * may keep the result until one of those characters changes.
     */
    public static String unit(Doc doc, int tabSize, Boolean insertSpaces, Integer indentSize) {
        return unit(doc, tabSize, insertSpaces, indentSize, null);
    }

    /** As {@link #unit(Doc, int, Boolean, Integer)}, detecting the unit as {@code language} reads indentation. */
    public static String unit(
            Doc doc,
            int tabSize,
            @Nullable Boolean insertSpaces,
            @Nullable Integer indentSize,
            @Nullable String language) {
        String head = insertSpaces != null ? "" : doc.text(0, Math.min(doc.length(), HEAD_CHARS));
        return Indenter.unitFor(head, tabSize, insertSpaces, indentSize, language);
    }

    /** How far below the caret's line Enter looks in a brace language (see {@link CommentContinuation}). */
    static final int LOOK_BELOW_CHARS = 4096;

    /** {@link Indenter#enterEdit(String, int, String, int, Boolean, Integer)} at {@code caret}, for {@code unit}. */
    public static Indenter.EnterEdit enterEdit(Doc doc, int caret, String language, String unit) {
        int line = doc.lineOf(caret);
        Embedded embedded = embedded(doc, caret, language);
        if (embedded != null) {
            language = embedded.language(); // the brace rules inside an HTML page's <script> or <style> (C10)
        }
        // Shell, Python, Lisp and brace languages read the stretch above the caret (blocks, quotes, wrapped
        // statements, open forms, case labels, open argument lists); the rest only the line.
        Indenter.Style style = Indenter.styleFor(language);
        int start = style == Indenter.Style.SHELL
                        || style == Indenter.Style.BRACES
                        || style == Indenter.Style.LISP
                        || "python".equals(language)
                ? backScanStart(doc, line)
                : doc.lineStart(line);
        if (embedded != null) {
            start = Math.max(start, embedded.start()); // the markup above is not code to read
        }
        int end = doc.lineStart(line) + doc.lineLength(line);
        if (style == Indenter.Style.BRACES) {
            end = Math.min(doc.length(), end + LOOK_BELOW_CHARS); // is a `/**` here already closed below?
        }
        return Indenter.enterEdit(doc.text(start, end), caret - start, language, unit);
    }

    /** {@link Indenter#closerAlignIndent(Indenter.Style, String, int, int, String, char)} at {@code caret}. */
    public static String closerAlignIndent(
            Indenter.Style style, Doc doc, int caret, int tabSize, String currentIndent, char typed) {
        return closerAlignIndent(style, doc, caret, tabSize, currentIndent, typed, null);
    }

    /** As above, knowing the document's indent {@code unit} (see {@link Indenter#closerAlignIndent}). */
    public static String closerAlignIndent(
            Indenter.Style style,
            Doc doc,
            int caret,
            int tabSize,
            String currentIndent,
            char typed,
            @Nullable String unit) {
        return closerAlignIndent(style, doc, caret, tabSize, currentIndent, typed, unit, null);
    }

    /**
     * As above in a {@code language} buffer: in an HTML page's {@code <script>} or {@code <style>} the opener is
     * looked for in the element's content only (HTML review C10).
     */
    public static String closerAlignIndent(
            Indenter.Style style,
            Doc doc,
            int caret,
            int tabSize,
            String currentIndent,
            char typed,
            @Nullable String unit,
            @Nullable String language) {
        int line = doc.lineOf(caret);
        int start = backScanStart(doc, line);
        Embedded embedded = language == null ? null : embedded(doc, caret, language);
        if (embedded != null) {
            start = Math.max(start, embedded.start());
        }
        String window = doc.text(start, doc.lineStart(line) + doc.lineLength(line));
        return Indenter.closerAlignIndent(style, window, caret - start, tabSize, currentIndent, typed, unit);
    }

    /**
     * The indentation style at {@code caret} of a {@code language} buffer: {@link Indenter#styleFor} of the language,
     * or the brace style inside an HTML page's {@code <script>} or {@code <style>} (HTML review C10), so a {@code }}
     * typed there steps back to its opener. Only a bracket closer {@code typed} is looked into: this runs on every
     * character typed.
     */
    public static Indenter.Style styleAt(Doc doc, int caret, String language, char typed) {
        if (typed != '}' && typed != ')' && typed != ']') {
            return Indenter.styleFor(language);
        }
        Embedded embedded = embedded(doc, caret, language);
        return Indenter.styleFor(embedded != null ? embedded.language() : language);
    }

    /** An HTML page's script or style the caret is in: its language and the first line start of its content. */
    private record Embedded(String language, int start) {}

    /**
     * The {@code <script>} or {@code <style>} of an HTML-family buffer that {@code caret} sits in, read back over the
     * slice a back-scan may see, or null — also when the caret is still on the line of the element's start tag,
     * which indents as markup ({@code <script>} then Enter opens an indented line).
     */
    private static @Nullable Embedded embedded(Doc doc, int caret, String language) {
        if (!HtmlElements.isHtmlLanguage(language) || doc.length() == 0) {
            return null;
        }
        int line = doc.lineOf(caret);
        int start = backScanStart(doc, line);
        String slice = doc.text(start, caret);
        int[] contentStart = new int[1];
        String embedded = HtmlRegions.embeddedBefore(slice, slice.length(), contentStart);
        if (embedded == null) {
            return null;
        }
        int content = start + contentStart[0];
        int contentLine = doc.lineOf(content);
        int lineEnd = doc.lineStart(contentLine) + doc.lineLength(contentLine);
        if (doc.text(content, lineEnd).isBlank()) {
            contentLine++; // "<script>" alone on its line: the code starts on the next one
        }
        if (line < contentLine) {
            return null;
        }
        return new Embedded(embedded, doc.lineStart(contentLine));
    }

    /**
     * The Tab ({@code shift == false}) or Shift-Tab edit over {@code [selStart, selEnd)}: the smart Tab of a
     * language with an indentation style, the plain one otherwise — {@link Indenter#smartTab} falling back
     * to {@link PlainTab#edit}, for {@code unit}.
     */
    public static Indenter.TabEdit tabEdit(
            Doc doc, int selStart, int selEnd, String language, int tabSize, boolean shift, String unit) {
        int a = Math.min(selStart, selEnd);
        int b = Math.max(selStart, selEnd);
        int firstLine = doc.lineOf(a);
        // A selection is rewritten line by line with no look at what is above it, except in a shell script, where
        // a here-doc opened above leaves its lines alone (Bash review C2); a bare caret in leading whitespace is
        // re-indented from the lines above.
        boolean above = a == b || Indenter.styleFor(language) == Indenter.Style.SHELL;
        int start = above ? backScanStart(doc, firstLine) : doc.lineStart(firstLine);
        int lastLine = doc.lineOf(b > a ? b - 1 : b);
        String window = doc.text(start, doc.lineStart(lastLine) + doc.lineLength(lastLine));
        Indenter.TabEdit edit = Indenter.smartTab(window, a - start, b - start, language, tabSize, shift, unit);
        if (edit == null) {
            edit = PlainTab.edit(window, a - start, b - start, tabSize, shift, PlainTab.unit(language, unit));
        }
        return new Indenter.TabEdit(
                edit.from() + start,
                edit.to() + start,
                edit.replacement(),
                edit.selStart() + start,
                edit.selEnd() + start);
    }

    /**
     * Where the slice for a back-scan from the start of {@code line} begins: a line start no scan can reach
     * past. A scan walks whole lines upward until it has covered {@link Indenter#MAX_SCAN} characters, and
     * from the last line it visits it may follow one wrapped statement {@link Indenter#MAX_STATEMENT_LINES}
     * lines further; one more line keeps "reached the top of the slice" from ever being mistaken for
     * "reached the top of the document".
     */
    static int backScanStart(Doc doc, int line) {
        int first = line;
        for (int scanned = 0; first > 0 && scanned < Indenter.MAX_SCAN; ) {
            first--;
            scanned += doc.lineLength(first) + 1;
        }
        return doc.lineStart(Math.max(0, first - Indenter.MAX_STATEMENT_LINES - 1));
    }

    /** A {@link Doc} over a String — for callers that already hold the text, and for tests. */
    public static Doc of(String text) {
        int count = 1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            count++;
        }
        int[] starts = new int[count];
        for (int i = text.indexOf('\n'), k = 1; i >= 0; i = text.indexOf('\n', i + 1)) {
            starts[k++] = i + 1;
        }
        return new Doc() {
            @Override
            public int length() {
                return text.length();
            }

            @Override
            public int lineOf(int offset) {
                int at = java.util.Arrays.binarySearch(starts, offset);
                return at >= 0 ? at : -at - 2;
            }

            @Override
            public int lineStart(int line) {
                return starts[line];
            }

            @Override
            public int lineLength(int line) {
                return (line + 1 < starts.length ? starts[line + 1] - 1 : text.length()) - starts[line];
            }

            @Override
            public String text(int from, int to) {
                return text.substring(from, to);
            }
        };
    }
}
