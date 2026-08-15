package com.editora.ui;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import static com.editora.i18n.Messages.tr;

/** Wraps a {@link ToolWindow}'s content with an IntelliJ-style header (title + float + maximize + close). */
final class ToolWindowPanel extends BorderPane {

    /** Set while keyboard focus is anywhere inside this tool window, so its header can be highlighted. */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final Button maximize;
    private final Button floatButton;

    /** Header state, mirrored so the context menu can label itself the same way the buttons do. */
    private boolean floating;

    private boolean maximized;

    private final MenuItem floatItem = new MenuItem();
    private final MenuItem maximizeItem = new MenuItem();
    private final ContextMenu headerMenu;

    ToolWindowPanel(ToolWindow tw, Runnable onClose, Runnable onToggleMaximize, Runnable onToggleFloat) {
        getStyleClass().add("tool-window");

        Label title = new Label();
        title.textProperty().bind(tw.titleProperty()); // updates live when a tool window retitles itself
        title.getStyleClass().add("tool-window-title");
        // The same glyph the stripe button shows, so an open panel's header reads as the stripe entry that
        // opened it rather than as an unrelated caption. createIcon() hands back a fresh node each call — a
        // JavaFX node can only have one parent, and the stripe already owns one.
        title.setGraphic(tw.createIcon());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        maximize = new Button();
        maximize.getStyleClass().addAll("button-icon", "flat", "tool-window-maximize");
        maximize.setOnAction(e -> onToggleMaximize.run());

        floatButton = new Button();
        floatButton.getStyleClass().addAll("button-icon", "flat", "tool-window-float");
        floatButton.setOnAction(e -> onToggleFloat.run());

        Button close = new Button();
        close.setGraphic(Icons.closeSmall());
        close.getStyleClass().addAll("button-icon", "flat", "tool-window-close");
        close.setTooltip(new Tooltip(tr("toolwindow.hide")));
        close.setOnAction(e -> onClose.run());

        HBox header = new HBox(8, title, spacer, floatButton, maximize, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tool-window-header");
        headerMenu = installHeaderMenu(header, onClose, onToggleMaximize, onToggleFloat);

        setFloating(false); // seeds the float button's glyph + tooltip
        setMaximized(false);

        StackPane content = new StackPane(tw.getContent());
        content.getStyleClass().add("tool-window-content");

        setTop(header);
        setCenter(content);
    }

    /**
     * The same three actions the header icons offer, as a right-click menu on the header.
     *
     * <p>On the <em>header</em> only, never the whole panel: the content is a tree, a list or a console,
     * each with its own context menu that must keep the right-click.
     *
     * <p>Built once and relabelled by the state setters rather than rebuilt: the two toggles have to read
     * as the direction they will actually go — an item saying "Maximize" on an already-maximized window is
     * a lie about what it does. Driven from the setters rather than from the menu's own {@code onShowing}
     * because they are the single place the state changes, which keeps the menu and the buttons saying the
     * same thing by construction instead of by two parallel updates that can drift.
     */
    private ContextMenu installHeaderMenu(
            HBox header, Runnable onClose, Runnable onToggleMaximize, Runnable onToggleFloat) {
        floatItem.setOnAction(e -> onToggleFloat.run());
        maximizeItem.setOnAction(e -> onToggleMaximize.run());
        MenuItem hideItem = new MenuItem(tr("toolwindow.hide"), Icons.closeSmall());
        hideItem.setOnAction(e -> onClose.run());

        ContextMenu menu = new ContextMenu(floatItem, maximizeItem, hideItem);
        header.setOnContextMenuRequested(e -> {
            menu.show(header, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return menu;
    }

    /** Points each toggle at the state it would move to, matching its button's tooltip exactly. */
    private void refreshMenuLabels() {
        floatItem.setText(tr(floating ? "toolwindow.dock" : "toolwindow.float"));
        floatItem.setGraphic(floating ? Icons.dock() : Icons.detach());
        maximizeItem.setText(tr(maximized ? "toolwindow.restoreSize" : "toolwindow.maximize"));
        maximizeItem.setGraphic(maximized ? Icons.restoreSize() : Icons.maximize());
        // Maximize is meaningless for a detached stage — the same reason its button hides.
        maximizeItem.setVisible(!floating);
    }

    /** Highlights this panel's header when it holds keyboard focus (driven by the scene's focus owner). */
    void setActive(boolean active) {
        pseudoClassStateChanged(ACTIVE, active);
    }

    /**
     * Flips the header between docked and detached: the button offers Float or Dock, and Maximize goes away
     * while floating — there is no split for a detached stage to take over, so the button would do nothing.
     */
    void setFloating(boolean floating) {
        this.floating = floating;
        floatButton.setGraphic(floating ? Icons.dock() : Icons.detach());
        floatButton.setTooltip(new Tooltip(tr(floating ? "toolwindow.dock" : "toolwindow.float")));
        maximize.setVisible(!floating);
        maximize.setManaged(!floating);
        refreshMenuLabels();
    }

    /**
     * Flips the header button between Maximize and Restore. The button is the only affordance that names a
     * tool window unambiguously — the command has to infer its target — so it has to say which way it goes.
     */
    void setMaximized(boolean maximized) {
        this.maximized = maximized;
        maximize.setGraphic(maximized ? Icons.restoreSize() : Icons.maximize());
        maximize.setTooltip(new Tooltip(tr(maximized ? "toolwindow.restoreSize" : "toolwindow.maximize")));
        refreshMenuLabels();
    }
}
