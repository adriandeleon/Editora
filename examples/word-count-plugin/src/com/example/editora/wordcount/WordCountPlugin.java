package com.example.editora.wordcount;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;

import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A live word-count tool window: lines / words / characters (with and without spaces) + an estimated
 * reading time for the active buffer. Refreshes on a ~1.5s timer (and via a Refresh button). Pure stats in
 * {@link WordStats}. Polling reads the buffer text only while the window is open.
 */
public class WordCountPlugin implements Plugin {

    private static final int MAX_CHARS = 2_000_000; // don't poll-copy huge buffers

    @Override
    public void start(PluginContext ctx) {
        Label stats = new Label();
        stats.setWrapText(true);
        Button refresh = new Button("Refresh");

        Runnable update = () -> {
            String text = ctx.activeEditor().text();
            if (text != null && text.length() > MAX_CHARS) {
                stats.setText("Buffer too large to count live (" + text.length() + " chars). Click Refresh.");
                return;
            }
            WordStats s = WordStats.of(text);
            stats.setText("Lines: " + s.lines + "\nWords: " + s.words + "\nCharacters: " + s.chars
                    + "\nCharacters (no spaces): " + s.charsNoSpace + "\nReading time: " + s.readingTime());
        };
        refresh.setOnAction(e -> update.run());
        update.run();

        Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1.5), e -> update.run()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        Label title = new Label("Word Count");
        title.getStyleClass().add("tool-window-title");
        VBox box = new VBox(8, title, stats, refresh);
        box.setPadding(new Insets(10));
        Region content = box;

        ctx.registerToolWindow("wordcount", "Word Count", ToolWindowSide.RIGHT, content, null);
    }
}
