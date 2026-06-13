package com.example.editora.color;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * A color picker tool window: choose a color, choose an output format (HEX / rgb() / rgba()), and insert it
 * at the caret. Demonstrates a JavaFX-rich tool window plus {@code insertAtCaret}.
 */
public class ColorPickerPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ColorPicker picker = new ColorPicker(Color.web("#4F86C6"));
        ComboBox<String> format = new ComboBox<>();
        format.getItems().addAll("HEX", "rgb()", "rgba()");
        format.setValue("HEX");

        Label preview = new Label();
        preview.setMinWidth(160);
        Runnable refresh = () -> preview.setText(format(picker.getValue(), format.getValue()));
        picker.valueProperty().addListener((o, a, b) -> refresh.run());
        format.valueProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        Button insert = new Button("Insert at caret");
        insert.setOnAction(e -> ctx.activeEditor().insertAtCaret(format(picker.getValue(), format.getValue())));

        Label title = new Label("Color Picker");
        title.getStyleClass().add("tool-window-title");
        HBox row = new HBox(8, picker, format, insert);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, title, row, preview);
        box.setPadding(new Insets(10));
        Region content = box;

        ctx.registerToolWindow("color", "Color Picker", ToolWindowSide.RIGHT, content, null);
    }

    /** Formats a JavaFX color as a CSS string. Pure. */
    static String format(Color c, String fmt) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return switch (fmt == null ? "HEX" : fmt) {
            case "rgb()" -> "rgb(" + r + ", " + g + ", " + b + ")";
            case "rgba()" -> "rgba(" + r + ", " + g + ", " + b + ", "
                    + String.format(java.util.Locale.ROOT, "%.2f", c.getOpacity()) + ")";
            default -> String.format("#%02x%02x%02x", r, g, b);
        };
    }
}
