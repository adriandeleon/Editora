package com.editora.ui;

import java.util.List;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.vfs.RemoteConnection;

import static com.editora.i18n.Messages.tr;

/**
 * The "Remote Sites" tool window: a flat, most-recent-first list of saved SFTP connections (from
 * {@code connections.json}). Double-click / Enter opens the connection form pre-filled for that site so
 * the user can confirm (and supply a password/passphrase) before connecting; a button bar + context menu
 * offer New / Connect / Remove. Only non-secret metadata is shown — never a password.
 *
 * <p>Reads the persisted list live via a {@link Supplier} (so it always reflects edits made on the
 * Settings → Remote page or after a connect) and routes actions back through {@link Actions}. Marks itself
 * {@code editora.ownsKeys} for clean local navigation, mirroring {@link BookmarksPanel}.
 */
public final class RemoteConnectionsPanel extends VBox implements ToolWindowContent {

    /** Actions the panel asks the controller to perform. */
    public interface Actions {
        /** Open the connection form pre-filled for {@code c} (the user confirms / supplies the secret). */
        void connect(RemoteConnection c);

        /** Forget the saved connection {@code c}. */
        void remove(RemoteConnection c);

        /** Open a fresh connection form. */
        void newConnection();
    }

    private final Supplier<List<RemoteConnection>> source;
    private final Actions actions;
    private final ListView<RemoteConnection> list = new ListView<>();
    private final Label empty = new Label(tr("remotePanel.empty"));

    public RemoteConnectionsPanel(Supplier<List<RemoteConnection>> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("remote-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        empty.getStyleClass().add("remote-empty");
        empty.setWrapText(true);

        list.getStyleClass().add("remote-list");
        list.setPlaceholder(empty);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new ConnectionCell());

        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                connectSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                connectSelected();
                e.consume();
            }
        });
        list.setContextMenu(buildMenu());

        Button add = new Button(tr("remotePanel.new"));
        add.setGraphic(Icons.newFile());
        add.setOnAction(e -> actions.newConnection());
        Button connect = new Button(tr("remotePanel.connect"));
        connect.setGraphic(Icons.remote());
        connect.setOnAction(e -> connectSelected());
        Button remove = new Button(tr("remotePanel.remove"));
        remove.setGraphic(Icons.trash());
        remove.setOnAction(e -> removeSelected());
        // Connect/Remove only make sense with a selection.
        connect.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        HBox buttons = new HBox(6, add, connect, spacer(), remove);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(list, buttons);
        refresh();
    }

    /** Reloads the saved-connection list from the supplier, preserving the selected id when possible. */
    public void refresh() {
        RemoteConnection sel = list.getSelectionModel().getSelectedItem();
        String selId = sel == null ? null : sel.id();
        list.getItems().setAll(source.get());
        if (selId != null) {
            for (RemoteConnection c : list.getItems()) {
                if (selId.equals(c.id())) {
                    list.getSelectionModel().select(c);
                    break;
                }
            }
        }
    }

    @Override
    public void focusFirstItem() {
        refresh();
        if (!list.getItems().isEmpty() && list.getSelectionModel().isEmpty()) {
            list.getSelectionModel().select(0);
        }
        list.requestFocus();
    }

    private ContextMenu buildMenu() {
        MenuItem connect = new MenuItem(tr("remotePanel.connect"));
        connect.setGraphic(Icons.remote());
        connect.setOnAction(e -> connectSelected());
        MenuItem add = new MenuItem(tr("remotePanel.new"));
        add.setGraphic(Icons.newFile());
        add.setOnAction(e -> actions.newConnection());
        MenuItem remove = new MenuItem(tr("remotePanel.remove"));
        remove.setGraphic(Icons.trash());
        remove.setOnAction(e -> removeSelected());
        return new ContextMenu(connect, add, new SeparatorMenuItem(), remove);
    }

    private void connectSelected() {
        RemoteConnection c = list.getSelectionModel().getSelectedItem();
        if (c != null) {
            actions.connect(c);
        }
    }

    private void removeSelected() {
        RemoteConnection c = list.getSelectionModel().getSelectedItem();
        if (c != null) {
            actions.remove(c);
            refresh();
        }
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A two-line cell: the site label (bold) over its {@code user@host:port} id, with a remote glyph. */
    private static final class ConnectionCell extends ListCell<RemoteConnection> {
        @Override
        protected void updateItem(RemoteConnection c, boolean emptyRow) {
            super.updateItem(c, emptyRow);
            if (emptyRow || c == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            Label name = new Label(c.displayLabel());
            name.getStyleClass().add("remote-name");
            Label id = new Label(c.id());
            id.getStyleClass().add("remote-id");
            VBox text = new VBox(1, name, id);
            HBox row = new HBox(8, Icons.remote(), text);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            String lastPath = c.lastPath() == null || c.lastPath().isBlank() ? "" : "\n" + c.lastPath();
            setTooltip(new Tooltip("sftp://" + c.id() + lastPath));
        }
    }
}
