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

        String contentEol = content.contains("\r\n") ? "\r\n" : content.indexOf('\r') >= 0 ? "\r" : "\n";
        String target =
                switch (eol == null ? "" : eol) {
                    case "crlf" -> "\r\n";
                    case "cr" -> "\r";
                    case "lf" -> "\n";
                    default -> contentEol; // EOL not specified → keep the file's dominant style
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
