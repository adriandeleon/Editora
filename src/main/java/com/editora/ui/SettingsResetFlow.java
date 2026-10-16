package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.editora.i18n.Messages.tr;

/**
 * "Reset to Defaults" and "Restore settings from backup…": what a reset clears and keeps, the confirmation that
 * says so (Cancel is the default), the dated copy of {@code settings.json} it writes first, and putting one of
 * those copies back.
 */
final class SettingsResetFlow {

    private final ConfigManager config;
    private final Runnable commit;
    private final Consumer<String> status;

    /** {@code commit} persists and applies the settings now in memory (re-themes, re-reads every control). */
    SettingsResetFlow(ConfigManager config, Runnable commit, Consumer<String> status) {
        this.config = config;
        this.commit = commit;
        this.status = status;
    }

    /**
     * Whether a reset keeps the setting {@code key}: credentials and connections — the AI API keys and the Sync
     * connection. They cannot be re-derived, and a reset of preferences is not a sign-out. Pure.
     */
    static boolean keptByReset(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).contains("apikey") || key.startsWith("sync");
    }

    /**
     * Resets {@code live} in place, keeping {@link #keptByReset} settings and the Local History limits that are
     * looser than the defaults (a stricter default would delete revisions; the spinners ask before that).
     */
    static void reset(Settings live) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode before = json.valueToTree(live);
        Settings.resetToDefaults(live);
        ObjectNode keep = json.createObjectNode();
        for (var it = before.fields(); it.hasNext(); ) {
            var e = it.next();
            if (keptByReset(e.getKey())) {
                keep.set(e.getKey(), e.getValue());
            }
        }
        try {
            json.readerForUpdating(live).readValue((JsonNode) keep);
        } catch (IOException e) {
            throw new IllegalStateException("Could not keep the credentials across a reset", e);
        }
        int perFile = before.path("historyMaxPerFile").asInt();
        int ageDays = before.path("historyMaxAgeDays").asInt();
        int totalMb = before.path("historyMaxTotalMb").asInt();
        live.setHistoryMaxPerFile(Math.max(perFile, live.getHistoryMaxPerFile()));
        live.setHistoryMaxAgeDays(ageDays <= 0 ? 0 : Math.max(ageDays, live.getHistoryMaxAgeDays()));
        live.setHistoryMaxTotalMb(Math.max(totalMb, live.getHistoryMaxTotalMb()));
    }

    /** Asks (Cancel is the default), writes the backup it names, then resets and applies. */
    void resetAll(Window owner) {
        LocalDateTime now = LocalDateTime.now();
        Path file = config.getSettingsFile();
        ButtonType resetButton = new ButtonType(tr("settings.reset.button"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr(
                        "settings.reset.confirm",
                        SettingsResetBackup.target(file, now).getFileName().toString()),
                resetButton,
                ButtonType.CANCEL));
        confirm.initOwner(owner);
        confirm.setTitle(tr("settings.reset.title"));
        confirm.setHeaderText(tr("settings.reset.header"));
        cancelIsDefault(confirm, resetButton);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != resetButton) {
            return;
        }
        Path backup = backUp(owner, now);
        if (backup == null) {
            return; // without the copy there is no reset
        }
        reset(config.getSettings());
        commit.run();
        status.accept(tr("status.settings.reset", backup.getFileName().toString()));
    }

    /** Lets the user pick a reset backup and puts it back (the settings in force are backed up first). */
    void restore(Window owner) {
        List<Path> backups = backups(config.getSettingsFile());
        if (backups.isEmpty()) {
            Alert none =
                    Dialogs.styled(new Alert(Alert.AlertType.INFORMATION, tr("settings.restore.none"), ButtonType.OK));
            none.initOwner(owner);
            none.setTitle(tr("settings.restore.title"));
            none.setHeaderText(null);
            none.showAndWait();
            return;
        }
        ChoiceDialog<String> pick = new ChoiceDialog<>(
                backups.get(0).getFileName().toString(),
                backups.stream().map(p -> p.getFileName().toString()).toList());
        Dialogs.styled(pick);
        pick.initOwner(owner);
        pick.setTitle(tr("settings.restore.title"));
        pick.setHeaderText(tr("settings.restore.header"));
        pick.setContentText(tr("settings.restore.label"));
        Optional<String> chosen = pick.showAndWait();
        if (chosen.isEmpty()) {
            return;
        }
        Path source = config.getSettingsFile().resolveSibling(chosen.get());
        Path backup = backUp(owner, LocalDateTime.now());
        if (backup == null) {
            return;
        }
        try {
            Files.copy(source, config.getSettingsFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            failed(owner, tr("settings.restore.failed", String.valueOf(e.getMessage())));
            return;
        }
        if (config.shared().reloadSettings() == null) {
            failed(owner, tr("settings.restore.failed", chosen.get()));
            return;
        }
        commit.run();
        status.accept(tr(
                "status.settings.restored", chosen.get(), backup.getFileName().toString()));
    }

    /** The reset backups beside {@code settingsFile}, newest first (their names sort by date). */
    static List<Path> backups(Path settingsFile) {
        String prefix = settingsFile.getFileName() + ".before-reset-";
        try (Stream<Path> files = Files.list(settingsFile.getParent())) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".bak"))
                    .sorted(java.util.Comparator.comparing(
                                    (Path p) -> p.getFileName().toString())
                            .reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Writes the dated copy of the settings in force; null (after saying why) when it cannot. */
    private Path backUp(Window owner, LocalDateTime now) {
        try {
            config.save(); // the copy is to hold the settings as they are, not as of the last queued write
            return SettingsResetBackup.write(config.getSettingsFile(), now);
        } catch (IOException | RuntimeException e) {
            failed(owner, tr("settings.reset.backupFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
            return null;
        }
    }

    private static void failed(Window owner, String text) {
        Alert failed = Dialogs.styled(new Alert(Alert.AlertType.ERROR, text, ButtonType.OK));
        failed.initOwner(owner);
        failed.setTitle(tr("settings.reset.title"));
        failed.setHeaderText(null);
        failed.showAndWait();
    }

    /** Makes Cancel, not the destructive button, the one Enter presses. */
    static void cancelIsDefault(Alert alert, ButtonType destructive) {
        if (alert.getDialogPane().lookupButton(destructive) instanceof Button b) {
            b.setDefaultButton(false);
        }
        if (alert.getDialogPane().lookupButton(ButtonType.CANCEL) instanceof Button b) {
            b.setDefaultButton(true);
        }
    }
}
