package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every tooltip shows after the same delay, set in one place: {@code ControlNames.tooltip} (which
 * {@code ControlNames.name}, {@code Icons.name}, {@code CommandTips} and {@code LazyTooltip} all go through). A
 * tooltip made with {@code new Tooltip} gets JavaFX's own second, and one given a delay by hand drifts from its
 * neighbors, so neither may appear outside that class; nor may a tooltip be declared in FXML, where no code
 * sets its delay.
 */
class TooltipDelayTest {

    private static final Path FACTORY = Path.of("src/main/java/com/editora/editor/ControlNames.java");

    @Test
    void noTooltipIsMadeOrTimedOutsideTheFactory() throws IOException {
        List<String> found = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(FACTORY))
                    .toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains("new Tooltip(")
                            || line.contains("new javafx.scene.control.Tooltip(")
                            || line.contains(".setShowDelay(")) {
                        found.add(file + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        try (var files = Files.walk(Path.of("src/main/resources"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                if (Files.readString(file).contains("<Tooltip")) {
                    found.add(file + ": a tooltip declared in FXML");
                }
            }
        }
        assertEquals(List.of(), found, "make the tooltip with ControlNames.tooltip(...)");
    }
}
