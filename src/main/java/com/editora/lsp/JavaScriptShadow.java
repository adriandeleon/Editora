package com.editora.lsp;

import java.nio.file.Path;

/**
 * How jdtls is shown a Java document that is not named {@code *.java} — an extensionless
 * {@code #!/usr/bin/env -S java --source 25} script, say.
 *
 * <p>jdtls makes a compilation unit only of a readable file named {@code *.java}. Shown the script under its
 * own name it ignores it: no diagnostics, completion, hover or outline, and a command about it fails with a
 * stack trace in the Debug Log. So the server is told about a {@code .java} file standing in for it
 * (a "shadow", written by {@link LspManager}), and its text on the wire has the shebang line turned into a
 * comment. Everything else — the buffer, the edit checks, what the user sees — stays the script.
 *
 * <p>The comment keeps every position where it was: {@code #!} becomes {@code /*} and the line's last two
 * characters {@code *}{@code /}, so the line is just as long. A block comment rather than {@code //} because
 * JDT then reads it as a file header and puts an added {@code import} after it; above a {@code //} line it
 * inserts at the very start, which would push the shebang off the first line and break the script.
 */
final class JavaScriptShadow {
    private JavaScriptShadow() {}

    /** Pure: whether {@code file}, opened as {@code routeLanguageId}, has to be shown to jdtls as a stand-in. */
    static boolean wanted(Path file, String routeLanguageId) {
        if (!"java".equals(routeLanguageId) || file == null) {
            return false;
        }
        Path name = file.getFileName();
        return name != null && !name.toString().endsWith(".java");
    }

    /**
     * Pure: the stand-in's file name — the script's, made a Java identifier (the implicit class of a compact
     * source file is named after its file), plus {@code .java}.
     */
    static String fileName(Path file) {
        String name = file.getFileName().toString();
        StringBuilder out = new StringBuilder(name.length() + 6);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) {
            out.insert(0, '_');
        }
        return out + ".java";
    }

    /**
     * Pure: {@code text} as the server sees it — a leading shebang line made a comment of the same length.
     * Text without one is returned as is. A line too short for {@code /*…*}{@code /}, or holding a
     * {@code *}{@code /} that would end the comment early, becomes a {@code //} comment instead.
     */
    static String wireText(String text) {
        if (text == null || !text.startsWith("#!")) {
            return text;
        }
        int end = text.indexOf('\n');
        if (end < 0) {
            end = text.length();
        }
        if (end > 0 && text.charAt(end - 1) == '\r') {
            end--;
        }
        if (end >= 4 && !text.substring(2, end - 2).contains("*/")) {
            return "/*" + text.substring(2, end - 2) + "*/" + text.substring(end);
        }
        return "//" + text.substring(2);
    }
}
