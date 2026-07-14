package com.editora.editorconfig;

/**
 * Pure save-time text transform applying the EditorConfig "fix on save" properties:
 * {@code trim_trailing_whitespace}, {@code insert_final_newline}, and {@code end_of_line} normalization.
 * Returns the transformed text; the caller encodes it to bytes ({@link EditorConfigCharset}). When none of
 * those three properties are set it returns the input unchanged, so line endings are never touched
 * gratuitously. Idempotent.
 */
public final class EditorConfigTransform {

    private EditorConfigTransform() {}

    public static String transform(String content, EditorConfigProperties p) {
        if (content == null || p == null) {
            return content;
        }
        boolean trim = Boolean.TRUE.equals(p.trimTrailingWhitespace());
        Boolean finalNewline = p.insertFinalNewline();
        String eol = p.endOfLine();
        boolean eolSet = "lf".equals(eol) || "crlf".equals(eol) || "cr".equals(eol);
        if (!trim && finalNewline == null && !eolSet) {
            return content; // nothing to do — leave the bytes (and existing EOLs) untouched
        }

        String target =
                switch (eol == null ? "" : eol) {
                    case "crlf" -> "\r\n";
                    case "cr" -> "\r";
                    case "lf" -> "\n";
                    default -> dominantEol(content); // EOL not specified → keep the file's dominant style
                };

        String lf = content.replace("\r\n", "\n").replace("\r", "\n");
        if (trim) {
            lf = trimTrailing(lf);
        }
        if (Boolean.TRUE.equals(finalNewline)) {
            if (!lf.isEmpty() && !lf.endsWith("\n")) {
                lf += "\n";
            }
        } else if (Boolean.FALSE.equals(finalNewline)) {
            int end = lf.length();
            while (end > 0 && lf.charAt(end - 1) == '\n') {
                end--;
            }
            lf = lf.substring(0, end);
        }
        return "\n".equals(target) ? lf : lf.replace("\n", target);
    }

    /**
     * The file's <b>dominant</b> line ending — the one that occurs most often, ties going to LF.
     *
     * <p>This used to be "does the text contain a CRLF <em>anywhere</em>". Mixed-EOL files are common (a
     * Windows-touched repo, a pasted snippet, a test fixture), so a predominantly-LF file with a single stray
     * CRLF was rewritten <b>entirely as CRLF</b> the first time {@code trim_trailing_whitespace} or
     * {@code insert_final_newline} was set with no {@code end_of_line} — every line of the file changed, with
     * no user action and no indication. And it only ever ratcheted toward CRLF, never back.
     */
    static String dominantEol(String content) {
        int crlf = 0;
        int cr = 0;
        int lf = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    crlf++;
                    i++; // consumed the pair
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        if (crlf > lf && crlf >= cr) {
            return "\r\n";
        }
        if (cr > lf && cr > crlf) {
            return "\r";
        }
        return "\n"; // LF wins, including on a tie and for a file with no line endings at all
    }

    /** Removes trailing spaces/tabs from every line (operating on {@code \n}-normalized text). */
    private static String trimTrailing(String lf) {
        StringBuilder sb = new StringBuilder(lf.length());
        int i = 0;
        int n = lf.length();
        while (i <= n) {
            int nl = lf.indexOf('\n', i);
            int end = nl < 0 ? n : nl;
            int e = end;
            while (e > i && (lf.charAt(e - 1) == ' ' || lf.charAt(e - 1) == '\t')) {
                e--;
            }
            sb.append(lf, i, e);
            if (nl < 0) {
                break;
            }
            sb.append('\n');
            i = nl + 1;
        }
        return sb.toString();
    }
}
