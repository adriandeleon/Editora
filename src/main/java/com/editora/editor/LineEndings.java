package com.editora.editor;

import com.editora.editorconfig.EditorConfigTransform;

/**
 * Line-ending bookkeeping for a document whose in-memory text always uses bare {@code \n}.
 *
 * <p>RichTextFX splits paragraphs on any terminator and joins them with {@code \n}, so the editor can never
 * tell a CRLF file from an LF one by looking at its own text. The file's line ending is therefore detected
 * once, from the decoded text before it enters the editor, kept as one of the labels below, and re-applied to
 * the bytes on save. Pure.
 */
public final class LineEndings {

    public static final String LF = "LF";
    public static final String CRLF = "CRLF";
    public static final String CR = "CR";

    private LineEndings() {}

    /** Whether {@code label} names a line ending this class can apply. */
    public static boolean isLabel(String label) {
        return LF.equals(label) || CRLF.equals(label) || CR.equals(label);
    }

    /** The label for an EditorConfig {@code end_of_line} value ({@code lf}/{@code crlf}/{@code cr}), else null. */
    public static String labelOf(String editorConfigEol) {
        return switch (editorConfigEol == null ? "" : editorConfigEol) {
            case "lf" -> LF;
            case "crlf" -> CRLF;
            case "cr" -> CR;
            default -> null;
        };
    }

    /**
     * The label of the line ending that occurs most often in {@code text} (ties and no line endings at all go
     * to LF). A mixed file keeps its dominant style rather than being ratcheted to CRLF by one stray pair.
     */
    public static String dominant(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return LF; // the common case, decided by one vectorised scan
        }
        return switch (EditorConfigTransform.dominantEol(text)) {
            case "\r\n" -> CRLF;
            case "\r" -> CR;
            default -> LF;
        };
    }

    /** {@code text} with every {@code \r\n} and lone {@code \r} replaced by {@code \n}; {@code ""} for null. */
    public static String toLf(String text) {
        if (text == null) {
            return "";
        }
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The separator a label stands for; anything unrecognised is {@code \n}. */
    public static String separator(String label) {
        return CRLF.equals(label) ? "\r\n" : CR.equals(label) ? "\r" : "\n";
    }

    /** Rewrites {@code \n}-normalised {@code lfText} with the line ending {@code label} names. */
    public static String apply(String lfText, String label) {
        String separator = separator(label);
        return lfText == null || "\n".equals(separator) || lfText.indexOf('\n') < 0
                ? lfText
                : lfText.replace("\n", separator);
    }
}
