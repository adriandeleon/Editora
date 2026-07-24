package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code public static void main(String[])} entry points in Java source text, for the editor's gutter
 * ▶ Run/Debug markers and the jdtls-free fallback (a build-tool run needs a main class to launch). A
 * lightweight, length-preserving blank-comments-and-literals pass + brace-depth walk — no real parser (the
 * {@code test/JavaTestScanner} / {@code editor/CompactSource} heuristic style; the blanker is duplicated
 * because the pure {@code run} package must not depend on {@code editor}).
 *
 * <p>Detects a {@code static void main} method whose single parameter is a {@code String[]} / {@code
 * String...} / {@code String args[]}, declared directly in a top-level class (brace depth 1). The class of a
 * match is the nearest top-level type; a {@code main} inside a nested class (depth ≥2) or with a multi-line
 * signature is not reported (jdtls's real enumeration covers those). Pure — no toolkit.
 */
public final class MainMethodScanner {

    private MainMethodScanner() {}

    /** A runnable entry point on {@code line} (0-based) in class {@code fqn} (fully qualified). */
    public record MainMethod(int line, String fqn) {}

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|enum|record)\\s+(\\w+)");
    // `… static … void main(String[] / String... / String args[] …)` on one (blanked) line.
    private static final Pattern MAIN = Pattern.compile("\\bstatic\\b[^;{}()]*\\bvoid\\s+main\\s*\\(\\s*"
            + "(?:final\\s+)?String\\s*(?:\\.\\.\\.|\\[\\s*\\]|\\w+\\s*\\[\\s*\\])[^)]*\\)");

    public static List<MainMethod> scan(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] lines = blank(source).split("\n", -1);

        String pkg = "";
        String topClass = null; // nearest top-level type name (depth-0 declaration)
        int depth = 0;
        List<MainMethod> out = new ArrayList<>();

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];

            if (pkg.isEmpty()) {
                Matcher pm = PACKAGE.matcher(line);
                if (pm.find()) {
                    pkg = pm.group(1);
                }
            }
            if (depth == 0) {
                Matcher tm = TYPE_DECL.matcher(line);
                if (tm.find()) {
                    topClass = tm.group(1);
                }
            }
            // A main method sits directly in the top-level class body (depth 1).
            if (depth == 1 && topClass != null && MAIN.matcher(line).find()) {
                out.add(new MainMethod(li, fqcn(pkg, topClass)));
            }

            depth += braceDelta(line);
            if (depth < 0) {
                depth = 0;
            }
        }
        return out;
    }

    private static String fqcn(String pkg, String cls) {
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    private static int braceDelta(String line) {
        int d = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    /**
     * Replaces comment + string/char/text-block content with spaces, preserving length + newline positions.
     * Duplicated from {@code test/JavaTestScanner} / {@code editor/CompactSource}.
     */
    static String blank(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n
                        && !(s.charAt(i) == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
            } else if (c == '"') {
                i = blankQuoted(s, n, '"', out, i);
            } else if (c == '\'') {
                i = blankQuoted(s, n, '\'', out, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int blankQuoted(String s, int n, char quote, StringBuilder out, int i) {
        out.append(' ');
        i++; // opening quote
        while (i < n && s.charAt(i) != quote) {
            if (s.charAt(i) == '\\' && i + 1 < n) {
                out.append("  ");
                i += 2;
            } else {
                out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                i++;
            }
        }
        if (i < n) {
            out.append(' ');
            i++; // closing quote
        }
        return i;
    }
}
