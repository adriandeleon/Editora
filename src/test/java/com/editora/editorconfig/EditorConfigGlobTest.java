package com.editora.editorconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigGlobTest {

    @Test
    void starMatchesBasenameInAnyDir() {
        assertTrue(EditorConfigGlob.matches("*.py", "foo.py"));
        assertTrue(EditorConfigGlob.matches("*.py", "src/pkg/foo.py"));
        assertFalse(EditorConfigGlob.matches("*.py", "foo.pyc"));
        assertFalse(EditorConfigGlob.matches("*.py", "foo.py.bak"));
    }

    @Test
    void starDoesNotCrossSlash() {
        assertTrue(EditorConfigGlob.matches("/lib/*.js", "lib/a.js"));
        assertFalse(EditorConfigGlob.matches("/lib/*.js", "lib/sub/a.js"));
    }

    @Test
    void doubleStarCrossesSlashIncludingZero() {
        assertTrue(EditorConfigGlob.matches("/src/**/*.py", "src/a.py"));
        assertTrue(EditorConfigGlob.matches("/src/**/*.py", "src/x/y/a.py"));
        assertFalse(EditorConfigGlob.matches("/src/**/*.py", "lib/a.py"));
    }

    @Test
    void questionMark() {
        assertTrue(EditorConfigGlob.matches("a?c.txt", "abc.txt"));
        assertFalse(EditorConfigGlob.matches("a?c.txt", "ac.txt"));
        assertFalse(EditorConfigGlob.matches("a?c.txt", "a/c.txt"));
    }

    @Test
    void charClassAndNegation() {
        assertTrue(EditorConfigGlob.matches("*.[ch]", "main.c"));
        assertTrue(EditorConfigGlob.matches("*.[ch]", "main.h"));
        assertFalse(EditorConfigGlob.matches("*.[ch]", "main.o"));
        assertTrue(EditorConfigGlob.matches("*.[!ch]", "main.o"));
        assertFalse(EditorConfigGlob.matches("*.[!ch]", "main.c"));
    }

    @Test
    void braceAlternation() {
        assertTrue(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.ts"));
        assertTrue(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.tsx"));
        assertFalse(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.py"));
        // A single-element brace is literal per the EditorConfig spec.
        assertTrue(EditorConfigGlob.matches("a{b}c", "a{b}c"));
    }

    @Test
    void numericRange() {
        assertTrue(EditorConfigGlob.matches("file{1..3}.txt", "file2.txt"));
        assertFalse(EditorConfigGlob.matches("file{1..3}.txt", "file5.txt"));
        assertTrue(EditorConfigGlob.matches("file{-1..1}.txt", "file-1.txt"));
    }

    @Test
    void anchoredVsFloating() {
        // No slash → matches in any directory.
        assertTrue(EditorConfigGlob.matches("Makefile", "sub/Makefile"));
        // Leading slash → anchored to the .editorconfig directory.
        assertTrue(EditorConfigGlob.matches("/Makefile", "Makefile"));
        assertFalse(EditorConfigGlob.matches("/Makefile", "sub/Makefile"));
    }

    @Test
    void anOversizedNumericRangeDoesNotThrow() {
        // A hostile .editorconfig section like [{1..99999999999999999999}] overflowed Long.parseLong and the
        // exception escaped matches() (its try/catch was after the pattern build), throwing all the way out of
        // EditorConfig.resolveFor — so merely OPENING any file it governed threw. It must degrade to matching
        // any integer instead.
        assertTrue(EditorConfigGlob.matches("file{1..99999999999999999999}.txt", "file7.txt"));
        assertTrue(EditorConfigGlob.matches("v{0..99999999999999999999}", "v12345"));
        assertFalse(EditorConfigGlob.matches("v{0..99999999999999999999}", "vNaN"));
    }

    @Test
    void anInRangeNumericRangeStillEnumerates() {
        assertTrue(EditorConfigGlob.matches("f{1..3}.txt", "f2.txt"));
        assertFalse(EditorConfigGlob.matches("f{1..3}.txt", "f9.txt"));
    }

    // --- hostile section globs: bounded, never trusted --------------------------------------------------

    private static final java.time.Duration PROMPT = java.time.Duration.ofSeconds(10);

    /** {@code [*a*a*…*b]} against {@code aaaa…a}: every star can end at every {@code a}, so a backtracking
     *  match tries them all before giving up. Before the bound this did not return within the lifetime of the
     *  window (it ran on the FX thread while a file was opening). */
    @Test
    void aManyWildcardGlobIsAbandonedInsteadOfBacktrackingForever() {
        String glob = "*a".repeat(13) + "*b";
        String name = "a".repeat(80);
        assertFalse(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                PROMPT, () -> EditorConfigGlob.matches(glob, name)));
        // …and the same shape still matches when it genuinely does.
        assertTrue(EditorConfigGlob.matches("*a*b", "xxaxxb"));
    }

    @Test
    void stackedAlternationsAreAbandonedToo() {
        String glob = "{a,a}".repeat(40) + "b";
        String name = "a".repeat(40);
        assertFalse(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                PROMPT, () -> EditorConfigGlob.matches(glob, name)));
    }

    @Test
    void aSectionWithMoreWildcardsThanTheCapIsIgnored() {
        String glob = "*x".repeat(EditorConfigGlob.MAX_WILDCARDS + 4);
        // it would match this name; the cap ignores the section rather than run it
        assertFalse(EditorConfigGlob.matches(glob, "x".repeat(EditorConfigGlob.MAX_WILDCARDS + 4)));
    }

    @Test
    void aRunOfStarsCollapsesToOneWildcard() {
        String stars = "*".repeat(3000);
        assertTrue(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                PROMPT, () -> EditorConfigGlob.matches(stars + ".md", "docs/deep/readme.md")));
        assertFalse(EditorConfigGlob.matches(stars + ".md", "docs/deep/readme.txt"));
        assertTrue(EditorConfigGlob.matches("/src/" + "**/".repeat(500) + "*.py", "src/x/y/a.py"));
        assertTrue(EditorConfigGlob.matches("/src/" + "**/".repeat(500) + "*.py", "src/a.py"));
        assertFalse(EditorConfigGlob.matches("/src/" + "**/".repeat(500) + "*.py", "lib/a.py"));
    }

    @Test
    void anOverlongOrDeeplyNestedSectionIsIgnoredWithoutThrowing() {
        assertFalse(EditorConfigGlob.matches("a".repeat(EditorConfigGlob.MAX_GLOB_LENGTH + 1), "a"));
        String nested = "{a,".repeat(1500) + "a" + "}".repeat(1500);
        assertFalse(EditorConfigGlob.matches(nested, "a"));
        // nesting within the cap still works
        assertTrue(EditorConfigGlob.matches("*.{js,{ts,tsx},{c,{h,hpp}}}", "a.hpp"));
        // a wall of maximal numeric ranges cannot expand without limit
        assertFalse(EditorConfigGlob.matches("{1..8000}".repeat(400), "1"));
    }

    @Test
    void aSharedBudgetBoundsAWholeFile() {
        EditorConfigGlob.Budget budget = new EditorConfigGlob.Budget(50_000);
        assertTrue(EditorConfigGlob.matches("*.md", "a.md", budget), "ordinary sections cost next to nothing");
        String hostile = "*a".repeat(13) + "*b";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(PROMPT, () -> {
            for (int i = 0; i < 10_000; i++) { // ten thousand hostile sections in one file
                assertFalse(EditorConfigGlob.matches(hostile, "a".repeat(80), budget));
            }
        });
        assertTrue(budget.exhausted());
        assertFalse(EditorConfigGlob.matches("*.md", "a.md", budget), "nothing matches once it is spent");
        assertTrue(EditorConfigGlob.matches("*.md", "a.md"), "a fresh budget is unaffected");
    }

    @Test
    void theWholeFileResolveStaysPromptForAHostileEditorConfig(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        StringBuilder ec = new StringBuilder("root = true\n[*.md]\nindent_size = 3\n");
        for (int i = 0; i < 2_000; i++) {
            ec.append('[').append("*a".repeat(12)).append("*b").append(i).append("]\nindent_size = 7\n");
        }
        java.nio.file.Files.writeString(dir.resolve(".editorconfig"), ec);
        java.nio.file.Path file = dir.resolve("a".repeat(100) + ".md");
        EditorConfigProperties props =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(PROMPT, () -> EditorConfig.resolveFor(file));
        assertEquals(Integer.valueOf(3), props.indentSize(), "the ordinary section before the hostile ones applied");
    }

    /**
     * Ordinary globs must behave exactly as before the bounds went in: this compares the bounded matcher with
     * the previous, unbounded translation (kept below verbatim as the oracle) over a corpus of real-world
     * section names and paths.
     */
    @Test
    void ordinaryGlobsMatchExactlyAsTheUnboundedTranslationDid() {
        java.util.List<String> globs = java.util.List.of(
                "*",
                "*.md",
                "*.{js,ts,tsx}",
                "*.[ch]",
                "*.[!ch]",
                "[*]",
                "Makefile",
                "/Makefile",
                "makefile*",
                "**",
                "**.py",
                "**/*.py",
                "/src/**/*.py",
                "/src/**",
                "lib/**.js",
                "lib/**/test_*.{py,pyi}",
                "{package.json,.travis.yml}",
                "*.{json,yml,yaml}",
                "docs/**/*.md",
                "a?c.txt",
                "??.txt",
                "file{1..3}.txt",
                "file{-1..1}.txt",
                "v{0..120}/*.h",
                "a{b}c",
                "{a,b}/{c,d}.x",
                "*.min.*",
                "**/node_modules/**",
                "test/*/fixtures/**",
                "[a-c]*.txt",
                "[!a-c]*.txt",
                "*.{c,{h,hpp}}",
                "***.md",
                "a**b",
                "dir/***/x",
                "weird[",
                "weird{",
                "a+b.(c)|d^$.txt",
                "**/",
                "/",
                "");
        java.util.List<String> paths = java.util.List.of(
                "a.md",
                "README.md",
                "docs/a.md",
                "docs/deep/er/a.md",
                "a.js",
                "src/a.tsx",
                "main.c",
                "main.h",
                "main.o",
                "Makefile",
                "sub/Makefile",
                "makefile.inc",
                "src/a.py",
                "src/x/y/a.py",
                "lib/a.py",
                "lib/a.js",
                "lib/x/a.js",
                "lib/x/test_a.py",
                "lib/x/test_a.pyi",
                "package.json",
                ".travis.yml",
                "x/package.json",
                "abc.txt",
                "ac.txt",
                "a/c.txt",
                "ab.txt",
                "file2.txt",
                "file5.txt",
                "file-1.txt",
                "v7/a.h",
                "v120/a.h",
                "v121/a.h",
                "a{b}c",
                "a/c.x",
                "b/d.x",
                "b/e.x",
                "app.min.js",
                "app.js",
                "node_modules/x/y.js",
                "a/node_modules/x/y.js",
                "test/unit/fixtures/a/b.json",
                "b1.txt",
                "d1.txt",
                "a.hpp",
                "a.h",
                "a.c",
                "ab",
                "axxb",
                "a/x/b",
                "dir/x",
                "dir/a/b/x",
                "weird[",
                "weird{",
                "a+b.(c)|d^$.txt",
                "dir/",
                "",
                "x");
        int compared = 0;
        for (String glob : globs) {
            for (String path : paths) {
                assertEquals(
                        UnboundedOracle.matches(glob, path),
                        EditorConfigGlob.matches(glob, path),
                        "glob [" + glob + "] against " + path);
                compared++;
            }
        }
        assertTrue(compared > 2000, "the corpus is not trivially small");
    }

    /** The translation as it was before the bounds: the reference for {@link #ordinaryGlobsMatchExactlyAsTheUnboundedTranslationDid}. */
    private static final class UnboundedOracle {
        /** Cap on a numeric-range alternation; beyond this we fall back to a generic integer pattern. */
        private static final int MAX_RANGE = 8192;

        private static final Pattern NUM_RANGE = Pattern.compile("(-?\\d+)\\.\\.(-?\\d+)");

        static boolean matches(String glob, String relPath) {
            if (glob == null || relPath == null) {
                return false;
            }
            String g = glob;
            if (g.indexOf('/') < 0) {
                g = "**/" + g; // no separator → match the basename in any directory
            } else if (g.startsWith("/")) {
                g = g.substring(1); // leading slash → anchored to the .editorconfig directory
            }
            try {
                StringBuilder re = new StringBuilder("^");
                appendPattern(re, g); // also inside the try: a malformed glob must never throw out of matches()
                re.append('$');
                return Pattern.compile(re.toString()).matcher(relPath).matches();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** A brace-range bound as a long, or null when it doesn't fit (so the caller degrades to "any integer"). */
        private static Long parseBound(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException overflow) {
                return null;
            }
        }

        private static void appendPattern(StringBuilder re, String g) {
            int n = g.length();
            int i = 0;
            while (i < n) {
                char c = g.charAt(i);
                switch (c) {
                    case '*' -> {
                        if (i + 1 < n && g.charAt(i + 1) == '*') {
                            if (i + 2 < n && g.charAt(i + 2) == '/') {
                                re.append("(?:.*/)?"); // `**/` matches any number of directories, including none
                                i += 3;
                            } else {
                                re.append(".*");
                                i += 2;
                            }
                        } else {
                            re.append("[^/]*");
                            i++;
                        }
                    }
                    case '?' -> {
                        re.append("[^/]");
                        i++;
                    }
                    case '[' -> {
                        int close = classEnd(g, i);
                        if (close < 0) {
                            re.append("\\[");
                            i++;
                        } else {
                            appendClass(re, g, i, close);
                            i = close + 1;
                        }
                    }
                    case '{' -> {
                        int close = matchingBrace(g, i);
                        if (close < 0) {
                            re.append("\\{");
                            i++;
                        } else {
                            appendBrace(re, g.substring(i + 1, close));
                            i = close + 1;
                        }
                    }
                    default -> {
                        if ("\\.^$+|()".indexOf(c) >= 0) {
                            re.append('\\');
                        }
                        re.append(c);
                        i++;
                    }
                }
            }
        }

        private static void appendClass(StringBuilder re, String g, int open, int close) {
            re.append('[');
            int j = open + 1;
            if (j < close && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
                re.append('^');
                j++;
            }
            while (j < close) {
                char c = g.charAt(j++);
                if (c == '\\' || c == '[') {
                    re.append('\\');
                }
                re.append(c);
            }
            re.append(']');
        }

        private static void appendBrace(StringBuilder re, String inner) {
            Matcher m = NUM_RANGE.matcher(inner);
            if (m.matches()) {
                // NUM_RANGE accepts any digit count, so a bound past Long.MAX (e.g. `{1..99999999999999999999}` in
                // a hostile .editorconfig) overflows Long.parseLong. The MAX_RANGE cap only fires once BOTH bounds
                // parse, so the throw escaped — and matches()' try/catch is after this call. A bound we can't hold
                // in a long can't be a small enumerable range anyway, so fall back to "match any integer".
                Long lo = parseBound(m.group(1));
                Long hi = parseBound(m.group(2));
                re.append(lo == null || hi == null ? "-?\\d+" : numericRange(lo, hi));
                return;
            }
            List<String> parts = splitTopLevelCommas(inner);
            if (parts.size() == 1) {
                // A single alternative with no comma isn't a brace expansion — treat literally (e.g. `{foo}`).
                re.append("\\{");
                appendPattern(re, inner);
                re.append("\\}");
                return;
            }
            re.append("(?:");
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) {
                    re.append('|');
                }
                appendPattern(re, parts.get(k));
            }
            re.append(')');
        }

        private static String numericRange(long a, long b) {
            long lo = Math.min(a, b);
            long hi = Math.max(a, b);
            if (hi - lo > MAX_RANGE) {
                return "-?\\d+"; // pathological range → match any integer
            }
            StringBuilder sb = new StringBuilder("(?:");
            for (long v = lo; v <= hi; v++) {
                if (v > lo) {
                    sb.append('|');
                }
                sb.append(v);
            }
            return sb.append(')').toString();
        }

        /** Index of the closing {@code ]} of a character class started at {@code open}, or -1. */
        private static int classEnd(String g, int open) {
            // A `]` right after `[` or `[!`/`[^` is a literal member, not the close.
            int j = open + 1;
            if (j < g.length() && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
                j++;
            }
            if (j < g.length() && g.charAt(j) == ']') {
                j++;
            }
            for (; j < g.length(); j++) {
                if (g.charAt(j) == ']') {
                    return j;
                }
            }
            return -1;
        }

        private static int matchingBrace(String g, int open) {
            int depth = 0;
            for (int j = open; j < g.length(); j++) {
                char c = g.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (--depth == 0) {
                        return j;
                    }
                }
            }
            return -1;
        }

        private static List<String> splitTopLevelCommas(String inner) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int j = 0; j < inner.length(); j++) {
                char c = inner.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(inner.substring(start, j));
                    start = j + 1;
                }
            }
            parts.add(inner.substring(start));
            return parts;
        }
    }
}
