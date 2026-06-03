package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** Wraps a {@link ToolWindow}'s content with an IntelliJ-style header (title + close button). */
final class ToolWindowPanel extends BorderPane {

    /** Set while keyboard focus is anywhere inside this tool window, so its header can be highlighted. */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    ToolWindowPanel(ToolWindow tw, Runnable onClose) {
        getStyleClass().add("tool-window");

        Label title = new Label(tw.getTitle());
        title.getStyleClass().add("tool-window-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button close = new Button();
        close.setGraphic(Icons.closeSmall());
        close.getStyleClass().addAll("button-icon", "flat", "tool-window-close");
        close.setTooltip(new Tooltip(tr("toolwindow.hide")));
        close.setOnAction(e -> onClose.run());

        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tool-window-header");

        StackPane content = new StackPane(tw.getContent());
        content.getStyleClass().add("tool-window-content");

        setTop(header);
        setCenter(content);
    }

    /** Highlights this panel's header when it holds keyboard focus (driven by the scene's focus owner). */
    void setActive(boolean active) {
        pseudoClassStateChanged(ACTIVE, active);
    }
}
