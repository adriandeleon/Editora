package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No production code may call {@code setShowCaret}: {@code CaretVisibility.OFF} and {@code ON} subscribe a
 * RichTextFX caret to a static stream that then holds the area — and, through its panel, the whole window —
 * for the life of the process (see docs/gotchas.md and {@code WindowReleasedOnCloseFxTest}). The default,
 * AUTO, already hides the caret of a read-only area and shows it in a focused editable one, which is all any
 * caller here has wanted.
 */
class CaretVisibilityPolicyTest {

    @Test
    void productionCodeLeavesCaretVisibilityOnAuto() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(CaretVisibilityPolicyTest::callsSetShowCaret)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), offenders, "setShowCaret(OFF/ON) pins the area through a static RichTextFX stream");
    }

    private static boolean callsSetShowCaret(Path file) {
        try {
            return Files.readString(file).lines().anyMatch(line -> {
                String code = line.strip();
                return !code.startsWith("//") && !code.startsWith("*") && code.contains(".setShowCaret(");
            });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
