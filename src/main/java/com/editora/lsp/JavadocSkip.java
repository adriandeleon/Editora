package com.editora.lsp;

/**
 * Where a definition inside library source really starts. jdtls answers a {@code jdt://} class-file definition
 * with the member's whole range, which begins at its Javadoc, so Go to Definition on {@code assertEquals}
 * landed on a {@code /**} five lines above the method (Java review B16). Pure.
 */
public final class JavadocSkip {

    private JavadocSkip() {}

    /**
     * The first line of the declaration when {@code line} (0-based) opens a Javadoc or block comment in
     * {@code text}: the line after the comment, past blank lines and annotations. {@code line} itself
     * otherwise, or when the comment never closes.
     */
    public static int declarationLine(String text, int line) {
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length || !lines[line].strip().startsWith("/*")) {
            return line;
        }
        int i = line;
        while (i < lines.length && !lines[i].contains("*/")) {
            i++;
        }
        if (i >= lines.length) {
            return line;
        }
        i++;
        while (i < lines.length && (lines[i].isBlank() || isAnnotationOnly(lines[i]))) {
            i++;
        }
        return i < lines.length ? i : line;
    }

    /** A line holding just an annotation ({@code @Deprecated}, {@code @SuppressWarnings("x")}). */
    private static boolean isAnnotationOnly(String line) {
        return ANNOTATION.matcher(line.strip()).matches();
    }

    private static final java.util.regex.Pattern ANNOTATION = java.util.regex.Pattern.compile("@[\\w.]+(\\(.*\\))?");
}
