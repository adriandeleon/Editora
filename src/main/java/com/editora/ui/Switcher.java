package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * IntelliJ-style Switcher popup. Two columns: recent files on the left, tool windows on the right.
 * Navigation uses Emacs-style movement: C-n/C-p down/up, C-f/C-b right/left column, Enter to
 * activate, C-g or Esc to cancel, C-d or Backspace to close the highlighted entry.
 */
public class Switcher {

    private static final PseudoClass ACTIVE_COLUMN = PseudoClass.getPseudoClass("active");

    private final Supplier<List<Tab>> recentTabsSupplier;
    private final Consumer<Tab> activateTab;
    private final Consumer<Tab> closeTab;
    private final ToolWindowManager toolWindows;

    private final Popup popup = new Popup();
    private final ListView<Tab> filesList = new ListView<>();
    private final ListView<ToolWindow> toolsList = new ListView<>();
    private final VBox leftColumn;
    private final VBox rightColumn;

    private boolean leftFocused = true;

    public Switcher(Supplier<List<Tab>> recentTabsSupplier,
                    Consumer<Tab> activateTab,
                    Consumer<Tab> closeTab,
                    ToolWindowManager toolWindows) {
        this.recentTabsSupplier = recentTabsSupplier;
        this.activateTab = activateTab;
        this.closeTab = closeTab;
        this.toolWindows = toolWindows;
        this.leftColumn = buildColumn("Recent Files", filesList);
        this.rightColumn = buildColumn("Tool Windows", toolsList);
        build();
    }

    private VBox buildColumn(String title, ListView<?> list) {
        Label header = new Label(title);
        header.getStyleClass().add("switcher-header");
        VBox column = new VBox(4, header, list);
        column.getStyleClass().add("switcher-column");
        return column;
    }

    private void build() {
        filesList.setPrefSize(280, 320);
        toolsList.setPrefSize(220, 320);

        filesList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Tab item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getText());
            }
        });

        toolsList.setCellFactory(v -> new ListCell<>() {
            private final Label label = new Label();
            private final HBox box = new HBox(8);

            {
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(ToolWindow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Node icon = item.createIcon();
                icon.getStyleClass().add("toolbar-icon");
                label.setText(item.getTitle());
                box.getChildren().setAll(icon, label);
                setGraphic(box);
                setText(null);
            }
        });

        HBox content = new HBox(8, leftColumn, rightColumn);
        content.getStyleClass().add("switcher");
        content.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Releasing Ctrl activates the selection (IntelliJ Switcher behavior).
        content.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.CONTROL && popup.isShowing()) {
                commit();
                e.consume();
            }
        });
        popup.getContent().add(content);
        popup.setAutoHide(true);
    }

    public void show(Window owner, boolean reverse) {
        List<Tab> recent = recentTabsSupplier.get();
        filesList.getItems().setAll(recent);
        toolsList.getItems().setAll(toolWindows.getRegisteredToolWindows());

        // Default selection: 2nd-most-recent file (so a quick activate jumps you back).
        // Reverse mode starts at the bottom of the list (least-recent).
        if (!filesList.getItems().isEmpty()) {
            int initial = reverse
                    ? filesList.getItems().size() - 1
                    : Math.min(1, filesList.getItems().size() - 1);
            filesList.getSelectionModel().select(initial);
            filesList.scrollTo(initial);
            leftFocused = true;
        } else if (!toolsList.getItems().isEmpty()) {
            toolsList.getSelectionModel().select(0);
            leftFocused = false;
        }
        applyColumnFocus();

        double width = 540;
        double x = owner.getX() + (owner.getWidth() - width) / 2;
        double y = owner.getY() + 120;
        popup.show(owner, x, y);
        focusedList().requestFocus();
    }

    public boolean isShown() {
        return popup.isShowing();
    }

    private void onKey(KeyEvent e) {
        boolean ctrl = e.isControlDown();
        switch (e.getCode()) {
            case ESCAPE -> {
                popup.hide();
                e.consume();
            }
            case ENTER -> {
                commit();
                e.consume();
            }
            case TAB -> {
                // Tab toggles focus between the Recent Files and Tool Windows columns.
                focusColumn(!leftFocused);
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case LEFT -> {
                focusColumn(true);
                e.consume();
            }
            case RIGHT -> {
                focusColumn(false);
                e.consume();
            }
            case BACK_SPACE -> {
                removeSelected();
                e.consume();
            }
            default -> {
                if (!ctrl) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        move(1);
                        e.consume();
                    }
                    case P -> {
                        move(-1);
                        e.consume();
                    }
                    case F -> {
                        focusColumn(false);
                        e.consume();
                    }
                    case B -> {
                        focusColumn(true);
                        e.consume();
                    }
                    case G -> {
                        popup.hide();
                        e.consume();
                    }
                    case D -> {
                        removeSelected();
                        e.consume();
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private ListView<?> focusedList() {
        return leftFocused ? filesList : toolsList;
    }

    private void move(int delta) {
        ListView<?> list = focusedList();
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        if (idx < 0) {
            idx = delta > 0 ? -1 : size;
        }
        int next = Math.floorMod(idx + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void focusColumn(boolean left) {
        ListView<?> target = left ? filesList : toolsList;
        if (target.getItems().isEmpty()) {
            return;
        }
        leftFocused = left;
        if (target.getSelectionModel().getSelectedIndex() < 0) {
            target.getSelectionModel().select(0);
        }
        applyColumnFocus();
        target.requestFocus();
    }

    private void applyColumnFocus() {
        leftColumn.pseudoClassStateChanged(ACTIVE_COLUMN, leftFocused);
        rightColumn.pseudoClassStateChanged(ACTIVE_COLUMN, !leftFocused);
    }

    private void commit() {
        if (leftFocused) {
            Tab tab = filesList.getSelectionModel().getSelectedItem();
            if (tab != null) {
                activateTab.accept(tab);
            }
        } else {
            ToolWindow tw = toolsList.getSelectionModel().getSelectedItem();
            if (tw != null) {
                toolWindows.toggle(tw);
            }
        }
        popup.hide();
    }

    private void removeSelected() {
        if (leftFocused) {
            Tab tab = filesList.getSelectionModel().getSelectedItem();
            if (tab != null) {
                closeTab.accept(tab);
                filesList.getItems().remove(tab);
            }
        } else {
            ToolWindow tw = toolsList.getSelectionModel().getSelectedItem();
            if (tw != null) {
                toolWindows.close(tw);
            }
        }
    }
}
