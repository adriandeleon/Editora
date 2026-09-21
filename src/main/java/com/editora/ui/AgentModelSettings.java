package com.editora.ui;

import java.util.ArrayList;

import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import com.editora.ai.AiProvider;
import com.editora.config.*;

import static com.editora.i18n.Messages.tr;

/** Per-model settings; network discovery happens later on the native setup worker. */
final class AgentModelSettings {
    private AgentModelSettings() {}

    static void show(Window owner, Settings settings, Runnable saved) {
        var provider = AiProvider.from(settings.getAiProvider());
        String model = settings.getAiModelFor(provider);
        if (model.isBlank() && provider == AiProvider.ANTHROPIC) model = AiCoordinator.DEFAULT_MODEL;
        String modelId = model;
        var current = settings.agentModelProfile(provider.id(), model);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(tr("agent.profile.title"));
        dialog.setHeaderText(provider.id() + " / " + (model.isBlank() ? tr("agent.profile.serverDefault") : model));
        var context = new Spinner<Integer>(0, 2_000_000, current.contextTokens(), 1024);
        var preferred = new Spinner<Integer>(0, 131072, current.outputTokens(), 1024);
        var maximum = new Spinner<Integer>(0, 131072, current.maxOutputTokens(), 1024);
        context.setEditable(true);
        preferred.setEditable(true);
        maximum.setEditable(true);
        var tools = new ComboBox<String>();
        tools.getItems().setAll("UNKNOWN", "SUPPORTED", "UNSUPPORTED");
        tools.setValue(current.tools());
        var discovery = new CheckBox(tr("agent.profile.discovery"));
        discovery.setSelected(current.discovery());
        var temperature = new TextField(
                current.temperature() == null ? "" : current.temperature().toString());
        var seed = new TextField(current.seed() == null ? "" : current.seed().toString());
        seed.setDisable(!provider.usesOpenAiApi());
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label(tr("agent.profile.context")), context);
        grid.addRow(1, new Label(tr("agent.profile.output")), preferred);
        grid.addRow(2, new Label(tr("agent.profile.maximum")), maximum);
        grid.addRow(3, new Label(tr("agent.profile.tools")), tools);
        grid.addRow(4, new Label(tr("agent.profile.temperature")), temperature);
        grid.addRow(5, new Label(tr("agent.profile.seed")), seed);
        grid.add(discovery, 0, 6, 2, 1);
        Label hint = new Label(tr("agent.profile.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        grid.add(hint, 0, 7, 2, 1);
        Label error = new Label();
        error.setWrapText(true);
        grid.add(error, 0, 8, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                context.commitValue();
                preferred.commitValue();
                maximum.commitValue();
                var value = new AgentModelProfileConfig(
                        provider.id(),
                        modelId,
                        context.getValue(),
                        preferred.getValue(),
                        maximum.getValue(),
                        tools.getValue(),
                        discovery.isSelected(),
                        temperature.getText().isBlank() ? null : Double.valueOf(temperature.getText()),
                        seed.getText().isBlank() ? null : Long.valueOf(seed.getText()));
                var profiles = new ArrayList<>(settings.getAgentModelProfiles());
                profiles.removeIf(
                        p -> p.provider().equals(provider.id()) && p.model().equals(modelId));
                profiles.add(value);
                settings.setAgentModelProfiles(profiles);
                saved.run();
            } catch (IllegalArgumentException failure) {
                error.setText(tr("agent.profile.invalid"));
                event.consume();
            }
        });
        dialog.showAndWait();
    }
}
