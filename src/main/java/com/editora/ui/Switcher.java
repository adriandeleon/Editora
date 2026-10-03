package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * IntelliJ-style Switcher popup over the open files, listed in <b>tab order</b>. Navigation uses
 * Emacs-style movement: C-n/C-p (or ↑/↓) move, Enter (or releasing Ctrl) activates, C-g or Esc
 * cancels, C-d or Backspace closes the highlighted file. Opening it preselects the current tab. The
 * popup width is fixed to the longest file path on each open, so it doesn't resize while you navigate.
 * (Tool windows are reached via the stripe icons or the "Tool Windows: Jump" picker.)
 */
public class Switcher {

    private final Supplier<List<Tab>> tabsSupplier;
    private final Supplier<Tab> activeTabSupplier;
    private final Consumer<Tab> activateTab;
    private final Consumer<Tab> closeTab;

    /** Fixed list-cell height + max rows before scrolling, so the list hugs the open-file count. */
    private static final double CELL_HEIGHT = 26;

    private static final int MAX_VISIBLE = 12;

    private final ListView<Tab> filesList = new ListView<>();
    /** The key legend under the list; rebuilt from the live keymap each time the switcher is shown. */
    private final Label hint = new Label();
    /** The keymap command whose chord also closes the highlighted file (Emacs {@code C-d}); Backspace/Delete always do. */
    private static final String CLOSE_COMMAND = "edit.deleteChar";

    private final Label pathLabel = new Label();
    private VBox root;
    /** Shared in-scene overlay host (injected by MainController) + shown state. */
    private OverlayHost overlayHost;

    private boolean showing;

    public Switcher(
            Supplier<List<Tab>> tabsSupplier,
            Supplier<Tab> activeTabSupplier,
            Consumer<Tab> activateTab,
            Consumer<Tab> closeTab) {
        this.tabsSupplier = tabsSupplier;
        this.activeTabSupplier = activeTabSupplier;
        this.activateTab = activateTab;
        this.closeTab = closeTab;
        build();
    }

    private void build() {
        // Hug the open-file count (capped at MAX_VISIBLE, then scroll) so a few tabs don't leave a tall
        // empty box; width is set per-show from the longest path. Resizes as tabs are listed/closed.
        filesList.setFixedCellSize(CELL_HEIGHT);
        filesList.getItems().addListener((javafx.collections.ListChangeListener<Tab>) c -> resizeList());

        // Render the name in a Label graphic: the tab's own text is empty (its title lives in a graphic
        // node), and a Label also inherits a theme-aware fill so names stay readable in dark mode.
        filesList.setCellFactory(v -> new ListCell<>() {
            private final Label label = new Label();

            @Override
            protected void updateItem(Tab item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                // userData may be a non-buffer TabContent (the Welcome tab) — instanceof, not a raw cast.
                EditorBuffer buffer = item.getUserData() instanceof EditorBuffer b ? b : null;
                boolean dirty = buffer != null && buffer.isDirty();
                String name = buffer != null ? buffer.getTitle() : tabContentTitle(item);
                label.setText((dirty ? "• " : "") + name); // unsaved marker, like the tab strip
                label.getStyleClass().remove("dirty-name");
                if (dirty) {
                    label.getStyleClass().add("dirty-name"); // amber/italic, like a dirty tab
                }
                // File-type glyph for real files; a non-buffer tab (Welcome) gets none.
                label.setGraphic(buffer != null ? FileIcons.forFileName(name) : null);
                setGraphic(label);
                setText(null);
            }
        });

        Label header = new Label(tr("switcher.header"));
        header.getStyleClass().add("switcher-header");
        VBox column = new VBox(4, header, filesList);
        column.getStyleClass().add("switcher-column");

        pathLabel.getStyleClass().add("switcher-path");
        pathLabel.setMaxWidth(Double.MAX_VALUE);

        hint.getStyleClass().add("switcher-hint");

        root = new VBox(column, pathLabel, hint);
        root.getStyleClass().add("switcher");
        // Hug content vertically (a plain VBox would otherwise be stretched to fill the overlay,
        // leaving a tall empty card under the file list).
        root.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        root.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p/arrows for the switcher
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Releasing Ctrl activates the selection (IntelliJ Switcher behavior).
        root.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.CONTROL && showing) {
                commit();
                e.consume();
            }
        });
    }

    /** Sizes the file list to its item count, capped at {@link #MAX_VISIBLE} rows (then it scrolls). */
    private void resizeList() {
        int rows = Math.max(1, Math.min(filesList.getItems().size(), MAX_VISIBLE));
        double h = rows * CELL_HEIGHT + 2;
        filesList.setMinHeight(h);
        filesList.setPrefHeight(h);
        filesList.setMaxHeight(h);
    }

    /** Injects the shared overlay host used to show the switcher card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public void show(Window owner, boolean reverse) {
        List<Tab> tabs = tabsSupplier.get();
        hint.setText(PickerKeys.legend(
                PickerKeys.hint("open", "↵"),
                PickerKeys.hint(
                        "close",
                        PickerKeys.withChords(
                                "⌫", com.editora.command.TextInputKeymap.sharedKeymap(), CLOSE_COMMAND))));
        filesList.getItems().setAll(tabs);
        if (!tabs.isEmpty()) {
            // Preselect the current tab; arrows move from there through the list in tab order.
            int sel = Math.max(0, tabs.indexOf(activeTabSupplier.get()));
            filesList.getSelectionModel().select(sel);
            filesList.scrollTo(sel);
        }
        updateFooter();

        // Fix the width to the longest path so the popup doesn't resize as the footer path changes.
        double width = widthFor(tabs, owner);
        root.setMinWidth(width);
        root.setPrefWidth(width);
        root.setMaxWidth(width);
        pathLabel.setMaxWidth(width); // ellipsize rather than grow the popup

        if (overlayHost == null) {
            return;
        }
        showing = true;
        overlayHost.show(root, filesList::requestFocus, () -> showing = false);
    }

    /** Popup width sized to the longest file path (clamped), so it stays constant while navigating. */
    private double widthFor(List<Tab> tabs, Window owner) {
        double max = 0;
        for (Tab tab : tabs) {
            EditorBuffer buffer = tab.getUserData() instanceof EditorBuffer b ? b : null;
            if (buffer == null) {
                continue;
            }
            String path = buffer.getPath() != null ? buffer.getPath().toString() : buffer.getTitle();
            max = Math.max(max, textWidth(path));
        }
        double width = max + 56; // popup/column/list padding + scrollbar
        width = Math.max(360, width);
        if (owner.getWidth() > 0) {
            width = Math.min(width, owner.getWidth() - 80); // never wider than the window
        }
        return width;
    }

    private double textWidth(String s) {
        Text t = new Text(s == null ? "" : s);
        if (pathLabel.getFont() != null) {
            t.setFont(pathLabel.getFont());
        }
        return t.getLayoutBounds().getWidth();
    }

    public boolean isShown() {
        return showing;
    }

    private void onKey(KeyEvent e) {
        var keymap = com.editora.command.TextInputKeymap.sharedKeymap();
        PickerKeys.Action action = PickerKeys.action(e, keymap, false); // no query field: Home/End jump
        String token = com.editora.command.KeyDispatcher.chord(e);
        if (action == PickerKeys.Action.CANCEL) {
            hide();
        } else if (action == PickerKeys.Action.ACCEPT) {
            commit();
        } else if (PickerKeys.navigate(filesList, action)) {
            updateFooter();
        } else if (e.getCode() == KeyCode.BACK_SPACE
                || e.getCode() == KeyCode.DELETE
                || (keymap != null && token != null && CLOSE_COMMAND.equals(keymap.commandFor(token)))) {
            removeSelected();
        } else {
            return;
        }
        e.consume();
    }

    /** Footer shows the highlighted file's full path. */
    private void updateFooter() {
        Tab tab = filesList.getSelectionModel().getSelectedItem();
        if (tab == null) {
            pathLabel.setText(" ");
            return;
        }
        EditorBuffer buffer = tab.getUserData() instanceof EditorBuffer b ? b : null;
        Path path = buffer == null ? null : buffer.getPath();
        pathLabel.setText(path != null ? path.toString() : buffer != null ? buffer.getTitle() : tabContentTitle(tab));
    }

    /** Title for a non-buffer tab (e.g. Welcome) — read from its TabContent (the tab text is empty). */
    private static String tabContentTitle(Tab tab) {
        return tab.getUserData() instanceof com.editora.editor.TabContent tc ? tc.title() : "";
    }

    private void commit() {
        Tab tab = filesList.getSelectionModel().getSelectedItem();
        if (tab != null) {
            activateTab.accept(tab);
        }
        hide();
    }

    private void removeSelected() {
        Tab tab = filesList.getSelectionModel().getSelectedItem();
        if (tab != null) {
            closeTab.accept(tab);
            filesList.getItems().remove(tab);
        }
        updateFooter();
    }
}
