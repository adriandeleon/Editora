package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import static com.editora.i18n.Messages.tr;

/**
 * The "HTTP Client" tool window: shows the {@code ijhttp} response report for a run request, with an
 * environment picker (for {@code {{var}}} resolution), Save response, and Clear. A
 * {@link ToolWindowContent} modeled on {@link RunPanel}; the controller drives it via
 * {@code started}/{@code showResult} on the FX thread.
 */
public final class HttpClientPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CHARS = 400_000;

    private final Label status = new Label();
    private final ComboBox<String> envCombo = new ComboBox<>();
    private final TextArea output = new TextArea();
    private final Button saveButton = new Button();
    private final Button clearButton = new Button();
    /** Receives the chosen environment name ({@code ""} = none) so the controller can persist it. */
    private Consumer<String> onEnvironmentChanged;

    private boolean updatingEnv;

    public HttpClientPanel(Runnable onSaveResponse) {
        getStyleClass().add("http-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("http-status");

        envCombo.getStyleClass().add("http-env");
        envCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null || s.isEmpty() ? tr("httppanel.noEnv") : s;
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        envCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingEnv && onEnvironmentChanged != null) {
                onEnvironmentChanged.accept(b == null ? "" : b);
            }
        });

        saveButton.setText(tr("httppanel.save"));
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> {
            if (onSaveResponse != null) {
                onSaveResponse.run();
            }
        });
        clearButton.setText(tr("httppanel.clear"));
        clearButton.setOnAction(e -> {
            output.clear();
            saveButton.setDisable(true);
        });

        Label envLabel = new Label(tr("httppanel.environment"));
        HBox header = new HBox(8, status, spacer(), envLabel, envCombo, clearButton, saveButton);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("http-output");

        VBox.setVgrow(output, Priority.ALWAYS);
        getChildren().addAll(header, output);
        idle();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** No request run yet / cleared. */
    public void idle() {
        status.setText(tr("httppanel.idle"));
    }

    /** A request started: clears the console and shows a running note for {@code label} (method + URL). */
    public void started(String label) {
        output.clear();
        saveButton.setDisable(true);
        status.setText(tr("httppanel.running", label));
    }

    /** Shows the finished response report and the exit status; enables Save when there is output. */
    public void showResult(String text, int exit) {
        String t = text == null ? "" : text;
        if (t.length() > MAX_CHARS) {
            t = t.substring(t.length() - MAX_CHARS);
        }
        output.setText(t);
        output.positionCaret(0);
        output.setScrollTop(0);
        saveButton.setDisable(output.getLength() == 0);
        status.setText(exit == 0 ? tr("httppanel.done") : tr("httppanel.failed", exit));
    }

    /** Populates the environment picker; {@code active} ({@code ""} = none) is selected. */
    public void setEnvironments(List<String> names, String active) {
        updatingEnv = true;
        List<String> items = new ArrayList<>();
        items.add(""); // the "no environment" option
        if (names != null) {
            items.addAll(names);
        }
        envCombo.getItems().setAll(items);
        envCombo.setValue(active == null ? "" : active);
        envCombo.setDisable(items.size() <= 1);
        updatingEnv = false;
    }

    /** The selected environment name, or {@code ""} for none. */
    public String getSelectedEnvironment() {
        String v = envCombo.getValue();
        return v == null ? "" : v;
    }

    public void setOnEnvironmentChanged(Consumer<String> onEnvironmentChanged) {
        this.onEnvironmentChanged = onEnvironmentChanged;
    }

    /** The current response text (for Save response). */
    public String getResponseText() {
        return output.getText();
    }

    @Override
    public void focusFirstItem() {
        output.requestFocus();
    }
}
