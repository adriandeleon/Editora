package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Javadoc comment may end on the line directly above another one. The first of two stacked comments
 * documents nothing: it is almost always a member's doc that lost its member when something was inserted
 * between them, so the reader of either member sees the wrong text.
 */
class StackedJavadocTest {

    @Test
    void noJavadocCommentSitsDirectlyAboveAnother() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "Run from the Maven project root");
        var stacked = new ArrayList<String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file);
                String name = sourceRoot.relativize(file).toString().replace('\\', '/');
                for (int line : stackedComments(lines)) {
                    stacked.add(name + ":" + line);
                }
            }
        }
        assertTrue(
                stacked.isEmpty(),
                () -> "Move each stacked Javadoc to the member it describes (or delete it, or make it a plain"
                        + " comment):\n" + String.join("\n", stacked));
    }

    /** The 1-based first lines of the Javadoc comments that end directly above another {@code /**}. */
    static List<Integer> stackedComments(List<String> lines) {
        var hits = new ArrayList<Integer>();
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).strip().startsWith("/**")) {
                int start = i;
                while (i < lines.size() && !lines.get(i).strip().endsWith("*/")) {
                    i++;
                }
                if (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("/**")) {
                    hits.add(start + 1);
                }
            }
            i++;
        }
        return hits;
    }
}
