package com.editora.ui;

import java.util.function.BiConsumer;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.editora.i18n.Messages.tr;

/**
 * The "Edit Link" card: a link's text and address in two fields, shown as an overlay at the caret. Enter in
 * either field applies, Esc (the overlay's own cancel) closes it without changing anything.
 */
final class LinkEditCard {

    private final VBox card = new VBox(8);
    private final TextField text = new TextField();
    private final TextField url = new TextField();

    /**
     * @param close hides the card; called before {@code onAccept}
     * @param onAccept called with (text, address) when the user applies
     */
    LinkEditCard(String linkText, String destination, Runnable close, BiConsumer<String, String> onAccept) {
        card.getStyleClass().add("table-size-picker"); // the same small-card look as the table-size picker
        card.setPadding(new Insets(12));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", true);
        Label heading = new Label(tr("link.edit.title"));
        heading.getStyleClass().add("table-size-label");
        text.setText(linkText);
        url.setText(destination);
        text.setId("link-edit-text");
        url.setId("link-edit-url");
        text.setPrefColumnCount(28);
        Label textLabel = new Label(tr("link.edit.text"));
        textLabel.setLabelFor(text);
        Label urlLabel = new Label(tr("link.edit.url"));
        urlLabel.setLabelFor(url);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.addRow(0, textLabel, text);
        grid.addRow(1, urlLabel, url);
        GridPane.setHgrow(text, Priority.ALWAYS);
        GridPane.setHgrow(url, Priority.ALWAYS);
        Label hint = new Label(tr("link.edit.hint"));
        hint.getStyleClass().addAll("table-size-label", "text-muted");
        card.getChildren().addAll(heading, grid, hint);
        card.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                close.run();
                onAccept.accept(text.getText(), url.getText());
                e.consume();
            }
        });
    }

    VBox card() {
        return card;
    }

    TextField textField() {
        return text;
    }

    TextField urlField() {
        return url;
    }

    /** Puts the keyboard where the user will type first: the address when there is text already, else the text. */
    void focus() {
        TextField first = text.getText().isEmpty() ? text : url;
        first.requestFocus();
        first.selectAll();
    }
}
