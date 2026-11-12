package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every dialog the app builds goes through {@link Dialogs#styled}, at the point it is built: that is what
 * gives it the app stylesheet, the one button order and buttons worded from the catalog. A dialog built
 * outside it reads "Cancel" in the JVM's language and draws its buttons in the operating system's order.
 */
class DialogsSourceTest {

    /** A JavaFX dialog being constructed, with or without its package spelled out. */
    private static final Pattern DIALOG = Pattern.compile(
            "new\\s+(?:javafx\\.scene\\.control\\.)?(Alert|TextInputDialog|ChoiceDialog|Dialog)\\s*(?:<[^>]*>)?\\s*\\(");

    @Test
    void everyDialogIsBuiltInsideDialogsStyled() throws IOException {
        List<String> outside = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/editora"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("javafx.scene.control")) {
                    continue; // another library's Alert (the Markdown parser has one)
                }
                Matcher dialog = DIALOG.matcher(source);
                while (dialog.find()) {
                    String before = source.substring(0, dialog.start()).stripTrailing();
                    if (!before.endsWith("Dialogs.styled(") && !before.endsWith(" styled(")) {
                        long line = source.substring(0, dialog.start()).lines().count() + 1;
                        outside.add(file + ":" + line + " new " + dialog.group(1));
                    }
                }
            }
        }
        assertTrue(
                outside.isEmpty(),
                "build each of these as Dialogs.styled(new …), so it gets the stylesheet, the button order and"
                        + " catalog-worded buttons:\n" + String.join("\n", outside));
    }
}
