package com.editora.ui;

import java.time.ZoneId;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.editora.http.HttpResult;
import com.editora.http.HttpTests;

/**
 * A History row: the status code coloured by outcome (or ⚠), a ✓ or ✗ when the response ran tests, then the time,
 * the name and the request.
 */
final class HttpHistoryCell extends ListCell<HttpHistoryEntry> {
    private final Label code = new Label();
    /** ✓ all tests passed, ✗ one failed or a handler stopped; hidden when the response ran no tests. */
    private final Label testMark = new Label();

    private final HBox graphic = new HBox(2, code, testMark);
    private final ZoneId zone;

    HttpHistoryCell(ZoneId zone) {
        this.zone = zone;
        code.getStyleClass().add("http-history-code");
        testMark.getStyleClass().add("http-history-tests");
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
        HttpTests t = r.tests();
        boolean tested = !t.tests().isEmpty() || t.error() != null;
        testMark.setText(tested ? (t.hasFailures() ? "✗" : "✓") : "");
        testMark.getStyleClass().removeAll("http-test-passed", "http-test-failed");
        testMark.getStyleClass().add(t.hasFailures() ? "http-test-failed" : "http-test-passed");
        testMark.setVisible(tested);
        testMark.setManaged(tested);
        setGraphic(graphic);
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
