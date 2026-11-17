package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No NullAway report is suppressed in the shipped code (Pauta QUAL-02): what NullAway reports is fixed, by
 * marking what may be null {@code @Nullable} or by restructuring the code until NullAway can prove it. A
 * {@code @SuppressWarnings} naming {@code NullAway} (or one of its sub-keys such as {@code NullAway.Init}),
 * alone or in a list, fails this test.
 */
@Tag("guard")
class NullAwaySuppressionTest {

    private static final Pattern SUPPRESSION =
            Pattern.compile("@SuppressWarnings\\s*\\((?:[^)]*?)\"NullAway(?:\\.[A-Za-z]+)?\"", Pattern.DOTALL);

    @Test
    void noNullAwayReportIsSuppressedInMainSources() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "Run from the Maven project root");
        var found = new ArrayList<String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                for (int line : suppressions(source)) {
                    found.add(file.toString().replace('\\', '/') + ":" + line);
                }
            }
        }
        assertTrue(
                found.isEmpty(),
                () -> "Fix what NullAway reports instead of suppressing it (docs/conventions.md, null annotations):\n"
                        + String.join("\n", found));
    }

    @Test
    void theMatcherFindsEveryFormAndNothingElse() {
        assertFalse(suppressions("@SuppressWarnings(\"NullAway\")\nvoid a() {}").isEmpty());
        assertFalse(
                suppressions("@SuppressWarnings({\"unchecked\", \"NullAway\"})").isEmpty());
        assertFalse(suppressions("@SuppressWarnings(value = {\n  \"NullAway.Init\"\n})")
                .isEmpty());
        assertTrue(suppressions("@SuppressWarnings(\"unchecked\")\nString s = \"NullAway\";")
                .isEmpty());
        assertTrue(suppressions("// NullAway proves this").isEmpty());
    }

    /** The 1-based lines on which a NullAway suppression starts. */
    static List<Integer> suppressions(String source) {
        var lines = new ArrayList<Integer>();
        var matcher = SUPPRESSION.matcher(source);
        while (matcher.find()) {
            int line = 1;
            for (int i = 0; i < matcher.start(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }
}
