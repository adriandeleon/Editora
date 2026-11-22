package com.editora.ui;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The window frame {@code main.fxml} declares, built in code: what every window after the first is built from.
 *
 * <p>The first window is built from {@code main.fxml}, a bundled resource the rule lets start-up read. A later
 * window would parse that XML again on the FX thread, which PERF-01 (Pauta 6.2.0) does not allow, so it is built here
 * instead: the same nodes in the same order, each with the id, style class and layout the FXML gives it, injected into
 * the controller's {@code @FXML} fields and wired to its handlers as {@code FXMLLoader} would. {@code MainViewFxTest}
 * keeps the two the same.
 */
final class MainView {

    private MainView() {}

    /** Builds the frame for {@code c}, fills its {@code @FXML} fields and returns the root. FX thread. */
    static BorderPane build(MainController c) {
        c.newButton = button("newButton", c::onNew);
        c.newFromTemplateButton = button("newFromTemplateButton", c::onNewFromTemplate);
        c.openButton = button("openButton", c::onOpen);
        c.openFolderButton = button("openFolderButton", c::onOpenFolder);
        c.closeTabButton = button("closeTabButton", c::onCloseTab);
        c.saveButton = button("saveButton", c::onSave);
        c.saveAsButton = button("saveAsButton", c::onSaveAs);
        c.recentButton = id(new MenuButton(), "recentButton");
        c.clearRecentButton = button("clearRecentButton", c::onClearRecent);
        c.undoButton = button("undoButton", c::onUndo);
        c.redoButton = button("redoButton", c::onRedo);
        c.cutButton = button("cutButton", c::onCut);
        c.copyButton = button("copyButton", c::onCopy);
        c.pasteButton = button("pasteButton", c::onPaste);
        c.findButton = button("findButton", c::onFind);
        c.findInFilesButton = button("findInFilesButton", c::onFindInFiles);
        c.runConfigCombo = id(new ComboBox<>(), "runConfigCombo");
        c.runConfigRunButton = button("runConfigRunButton", c::onRunSelectedConfig);
        c.runConfigDebugButton = button("runConfigDebugButton", c::onDebugSelectedConfig);
        c.runConfigStopButton = button("runConfigStopButton", c::onStopRun);
        c.splitVerticalButton = button("splitVerticalButton", c::onSplitVertical);
        c.splitHorizontalButton = button("splitHorizontalButton", c::onSplitHorizontal);
        c.paletteButton = button("paletteButton", c::onPalette);
        c.simpleModeButton = button("simpleModeButton", c::onToggleSimpleMode);
        c.settingsButton = button("settingsButton", c::onSettings);

        c.toolBar = id(new ToolBar(), "toolBar");
        c.toolBar
                .getItems()
                .addAll(
                        c.newButton,
                        c.newFromTemplateButton,
                        c.openButton,
                        c.openFolderButton,
                        c.closeTabButton,
                        c.saveButton,
                        c.saveAsButton,
                        separator(),
                        c.recentButton,
                        c.clearRecentButton,
                        separator(),
                        c.undoButton,
                        c.redoButton,
                        c.cutButton,
                        c.copyButton,
                        c.pasteButton,
                        separator(),
                        c.findButton,
                        c.findInFilesButton,
                        c.runConfigCombo,
                        c.runConfigRunButton,
                        c.runConfigDebugButton,
                        c.runConfigStopButton,
                        separator(),
                        c.splitVerticalButton,
                        c.splitHorizontalButton,
                        separator(),
                        c.paletteButton,
                        c.simpleModeButton,
                        c.settingsButton);
        HBox.setHgrow(c.toolBar, Priority.ALWAYS);
        c.toolbarTail = row("toolbarTail", "toolbar-tail");
        c.toolbarRow = row("toolbarRow", "toolbar-row");
        c.toolbarRow.getChildren().addAll(c.toolBar, c.toolbarTail);
        c.topBox = id(new VBox(c.toolbarRow), "topBox");

        c.tabPane = id(new TabPane(), "tabPane");
        c.workspace = id(new BorderPane(), "workspace");
        c.workspace.setCenter(c.tabPane);
        c.bottomBox = id(new VBox(), "bottomBox");

        BorderPane root = id(new BorderPane(), "root");
        root.setTop(c.topBox);
        root.setCenter(c.workspace);
        root.setBottom(c.bottomBox);
        c.root = root;
        return root;
    }

    private static <T extends Node> T id(T node, String id) {
        node.setId(id);
        return node;
    }

    private static Button button(String id, Runnable onAction) {
        Button b = id(new Button(), id);
        b.setOnAction(e -> onAction.run());
        return b;
    }

    private static Separator separator() {
        return new Separator(Orientation.VERTICAL);
    }

    private static HBox row(String id, String styleClass) {
        HBox box = id(new HBox(), id);
        box.getStyleClass().add(styleClass);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
