package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps production source files within the agreed upper bound as features grow. */
class SourceFileSizeTest {
    @Test
    void productionJavaFilesStayWithinTenThousandLines() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "Run from the Maven project root");
        var oversized = new ArrayList<String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                try (var lines = Files.lines(file)) {
                    long count = lines.count();
                    if (count > 10_000) {
                        oversized.add(sourceRoot.relativize(file) + ": " + count + " lines");
                    }
                }
            }
        }
        assertTrue(
                oversized.isEmpty(),
                () -> "Extract cohesive responsibilities from oversized files:\n" + String.join("\n", oversized));
    }
}
