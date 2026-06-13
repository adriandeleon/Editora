package com.example.editora.scratchpad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A persistent scratchpad tool window: a plain text area whose contents are saved to the plugin's
 * {@code dataDir()} (debounced) and reloaded on startup. Demonstrates {@code registerToolWindow} + building
 * a JavaFX content node + per-plugin data storage.
 */
public class ScratchpadPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        Path file = ctx.dataDir().resolve("scratch.txt");

        TextArea area = new TextArea(readQuietly(file));
        area.setPromptText("Jot anything here — it's saved automatically.");
        area.setWrapText(true);
        VBox.setVgrow(area, Priority.ALWAYS);

        // Debounced auto-save: coalesce bursts of typing into one write ~600ms after the last keystroke.
        javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(Duration.millis(600));
        debounce.setOnFinished(e -> writeQuietly(ctx, file, area.getText()));
        area.textProperty().addListener((obs, was, now) -> debounce.playFromStart());
        // Also flush immediately when focus leaves the pad.
        area.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                writeQuietly(ctx, file, area.getText());
            }
        });

        Label header = new Label("Scratchpad");
        header.getStyleClass().add("tool-window-title");
        VBox box = new VBox(6, header, area);
        box.setPadding(new Insets(8));
        Region content = box;

        ctx.registerToolWindow("scratchpad", "Scratchpad",
                com.editora.plugin.ToolWindowSide.RIGHT, content, null);
    }

    private static String readQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeQuietly(PluginContext ctx, Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text == null ? "" : text);
        } catch (IOException e) {
            ctx.log("Scratchpad save failed: " + e.getMessage());
        }
    }
}
