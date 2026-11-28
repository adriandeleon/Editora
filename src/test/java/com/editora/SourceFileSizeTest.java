package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps production source files within the agreed upper bound as features grow. */
@Tag("guard")
class SourceFileSizeTest {
    /**
     * Lowered from 10,000 once MainController, EditorBuffer and DapManager were split. The largest file is then
     * MapSurface at about 6,900 lines; the cap keeps any file from growing back towards the old sizes.
     */
    static final int MAX_LINES = 7_000;

    @Test
    void productionJavaFilesStayWithinSevenThousandLines() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "Run from the Maven project root");
        var oversized = new ArrayList<String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                try (var lines = Files.lines(file)) {
                    long count = lines.count();
                    if (count > MAX_LINES) {
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
