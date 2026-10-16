package com.editora.ui;

import java.time.ZoneId;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.editora.http.HttpResult;

/** A History row: the status code coloured by outcome (or ⚠), then the time, the name and the request. */
final class HttpHistoryCell extends ListCell<HttpHistoryEntry> {
    private final Label code = new Label();
    private final ZoneId zone;

    HttpHistoryCell(ZoneId zone) {
        this.zone = zone;
        code.getStyleClass().add("http-history-code");
        // A column as wide as a three-digit code, so a "⚠" row's time lines up with a "404" row's.
        // Measured in the row's font, which a status style class does not change.
        fontProperty().addListener((o, was, f) -> fixCodeWidth(f));
        fixCodeWidth(getFont());
    }

    private void fixCodeWidth(Font f) {
        double w = codeColumnWidth(f);
        code.setMinWidth(w);
        code.setPrefWidth(w);
    }

    @Override
    protected void updateItem(HttpHistoryEntry e, boolean empty) {
        super.updateItem(e, empty);
        if (empty || e == null) {
            setText(null);
            setGraphic(null);
            return;
        }
        HttpResult r = e.result();
        code.setText(r.failed() ? "⚠" : String.valueOf(r.status()));
        code.getStyleClass().removeAll(HttpStatusText.ALL);
        code.getStyleClass().add(HttpStatusText.styleClass(r));
        setGraphic(code);
        String full = e.label(zone); // "404  14:03:07  Get user — GET http://…": the code is the graphic
        setText(full.substring(Math.min(full.length(), code.getText().length())).strip());
    }

    /** The width of a bold three-digit status code in {@code font}, plus the gap after it. */
    static double codeColumnWidth(Font font) {
        Font f = font == null ? Font.getDefault() : font;
        Text t = new Text("000");
        t.setFont(Font.font(f.getFamily(), javafx.scene.text.FontWeight.BOLD, f.getSize()));
        return Math.ceil(t.getLayoutBounds().getWidth()) + 10;
    }
}
