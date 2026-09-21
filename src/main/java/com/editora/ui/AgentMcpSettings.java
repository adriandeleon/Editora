package com.editora.ui;

import java.util.List;
import java.util.function.Supplier;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import com.editora.config.AgentMcpServer;
import com.editora.config.Settings;
import com.editora.mcp.AgentMcpManager;

import static com.editora.i18n.Messages.tr;

/** Shared settings editor used by the palette and Settings. Runtime health is a read-only snapshot. */
final class AgentMcpSettings {
    private AgentMcpSettings() {}

    static void show(Window owner, Settings settings, Runnable save, Supplier<List<AgentMcpManager.Health>> health) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(tr("agent.mcp.title"));
        var rows = javafx.collections.FXCollections.observableArrayList(settings.getAgentMcpServers());
        ListView<AgentMcpServer> list = new ListView<>(rows);
        list.setPrefHeight(180);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AgentMcpServer value, boolean empty) {
                super.updateItem(value, empty);
                setText(
                        empty || value == null
                                ? null
                                : value.id() + " · "
                                        + tr(value.enabled() ? "agent.mcp.enabled" : "agent.mcp.disabled"));
            }
        });
        TextField id = new TextField();
        TextField command = new TextField();
        CheckBox enabled = new CheckBox(tr("agent.mcp.enabled"));
        enabled.setSelected(true);
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                id.setText(b.id());
                command.setText(b.command());
                enabled.setSelected(b.enabled());
            }
        });
        Label error = new Label();
        error.setWrapText(true);
        Label status = new Label();
        status.setWrapText(true);
        ScrollPane statusScroll = new ScrollPane(status);
        statusScroll.setFitToWidth(true);
        statusScroll.setPrefViewportHeight(150);
        Runnable refresh = () -> status.setText(health.get().stream()
                .map(h -> h.server() + ": " + tr("agent.mcp.state." + h.state()) + " · " + h.tools() + " "
                        + tr("agent.mcp.tools") + "\n" + String.join(", ", h.toolNames()))
                .collect(java.util.stream.Collectors.joining("\n")));
        Button put = new Button(tr("agent.mcp.save"));
        put.setOnAction(e -> {
            try {
                var server = new AgentMcpServer(
                        id.getText().strip(), command.getText().strip(), enabled.isSelected());
                var updated = new java.util.ArrayList<>(rows);
                updated.removeIf(s -> s.id().equals(server.id()));
                updated.add(server);
                settings.setAgentMcpServers(updated);
                rows.setAll(updated);
                save.run();
                error.setText("");
            } catch (IllegalArgumentException invalid) {
                error.setText(tr("agent.mcp.invalid"));
            }
        });
        Button remove = new Button(tr("agent.mcp.remove"));
        remove.setOnAction(e -> {
            var selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                rows.remove(selected);
                settings.setAgentMcpServers(rows);
                save.run();
            }
        });
        Button poll = new Button(tr("agent.mcp.refresh"));
        poll.setOnAction(e -> refresh.run());
        var hint = new Label(tr("agent.mcp.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(620);
        var content = new VBox(
                8,
                hint,
                list,
                new Label(tr("agent.mcp.id")),
                id,
                new Label(tr("agent.mcp.command")),
                command,
                enabled,
                new HBox(8, put, remove, poll),
                error,
                statusScroll);
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        refresh.run();
        dialog.show();
    }
}
