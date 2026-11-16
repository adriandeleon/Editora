package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

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
    private final BackupChooser chooser;

    /** Lets the user choose one of {@code backups}, newest first; {@code onChosen} is not called on a cancel. */
    interface BackupChooser {
        void choose(List<Path> backups, Consumer<Path> onChosen);
    }

    /**
     * {@code commit} persists and applies the settings now in memory (re-themes, re-reads every control);
     * {@code chooser} is the picker a restore asks with.
     */
    SettingsResetFlow(ConfigManager config, Runnable commit, Consumer<String> status, BackupChooser chooser) {
        this.config = config;
        this.commit = commit;
        this.status = status;
        this.chooser = chooser;
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
        // The default keymap first, the way a keymap switch goes: bindings of commands no keymap binds (macros,
        // external tools, run configurations) come along, the others stay parked with the keymap they were made in.
        KeymapLayers.switchKeymap(live, new Settings().getKeymap());
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

    /** The words of the question before every setting is reset, naming the backup file it writes first. */
    static Confirm.Question resetQuestion(String backupFileName) {
        return new Confirm.Question(
                tr("settings.reset.title"),
                tr("settings.reset.header"),
                tr("settings.reset.confirm", backupFileName),
                tr("settings.reset.button"));
    }

    /** Asks (Cancel is the default), writes the backup it names, then resets and applies. */
    void resetAll(Window owner) {
        LocalDateTime now = LocalDateTime.now();
        Path file = config.getSettingsFile();
        if (!Confirm.ask(
                owner,
                resetQuestion(
                        SettingsResetBackup.target(file, now).getFileName().toString()))) {
            return;
        }
        backUp(
                owner,
                now,
                backup -> { // without the copy there is no reset
                    // The reset works on JSON trees: made on a copy, on the config writer's thread (PERF-01, Pauta
                    // 6.0.1), and the result put into the live settings here, field by field.
                    Settings copy = com.editora.config.Snapshots.copy(config.getSettings());
                    config.shared()
                            .afterWrites(
                                    () -> {
                                        reset(copy);
                                        return copy;
                                    },
                                    (reset, failure) -> {
                                        if (reset == null) { // a bug, not the user's: nothing was reset
                                            java.util.logging.Logger.getLogger(SettingsResetFlow.class.getName())
                                                    .log(
                                                            java.util.logging.Level.SEVERE,
                                                            "Reset to defaults failed",
                                                            failure);
                                            return;
                                        }
                                        com.editora.config.Snapshots.assignFields(reset, config.getSettings());
                                        commit.run();
                                        status.accept(tr(
                                                "status.settings.reset",
                                                backup.getFileName().toString()));
                                    });
                });
    }

    /** Lets the user pick a reset backup and puts it back (the settings in force are backed up first). */
    void restore(Window owner) {
        // Listed off the FX thread (PERF-01, Pauta 6.0.1); the choice is offered when the list lands.
        Path settingsFile = config.getSettingsFile();
        config.shared().afterWrites(() -> backups(settingsFile), (found, e) -> offerBackups(owner, found));
    }

    private void offerBackups(Window owner, @Nullable List<Path> found) {
        List<Path> backups = found == null ? List.of() : found;
        if (backups.isEmpty()) {
            Alert none =
                    Dialogs.styled(new Alert(Alert.AlertType.INFORMATION, tr("settings.restore.none"), ButtonType.OK));
            none.initOwner(owner);
            none.setTitle(tr("settings.restore.title"));
            none.setHeaderText(null);
            Dialogs.showAndWait(none);
            return;
        }
        chooser.choose(backups, source -> restoreFrom(owner, source));
    }

    /** Puts the backup {@code source} back, after saving the settings in force as a backup of their own. */
    private void restoreFrom(Window owner, Path source) {
        String chosen = source.getFileName().toString();
        backUp(owner, LocalDateTime.now(), backup -> {
            // The copy and the re-read run on the config writer's thread, after the writes queued before them.
            Path settingsFile = config.getSettingsFile();
            config.shared()
                    .reloadSettings(
                            () -> Files.copy(source, settingsFile, StandardCopyOption.REPLACE_EXISTING),
                            (copyFailed, issues) -> {
                                if (copyFailed != null) {
                                    failed(
                                            owner,
                                            tr("settings.restore.failed", String.valueOf(copyFailed.getMessage())));
                                } else if (issues == null) {
                                    failed(owner, tr("settings.restore.failed", chosen));
                                } else {
                                    commit.run();
                                    status.accept(tr(
                                            "status.settings.restored",
                                            chosen,
                                            backup.getFileName().toString()));
                                }
                            });
        });
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

    /**
     * Writes the dated copy of the settings in force, then hands it to {@code then}; says why instead when it
     * cannot. The copy is made on the config writer's thread after the save queued here, so it holds the settings
     * as they are, not as of the last queued write, and the FX thread waits for neither (PERF-01, Pauta 6.0.1).
     */
    private void backUp(Window owner, LocalDateTime now, Consumer<Path> then) {
        try {
            config.save(); // queued: the copy below runs after it
        } catch (RuntimeException e) {
            failed(owner, tr("settings.reset.backupFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
            return;
        }
        Path settingsFile = config.getSettingsFile();
        config.shared().afterWrites(() -> SettingsResetBackup.write(settingsFile, now), (backup, e) -> {
            if (backup == null) {
                String why = e == null ? "" : e.getMessage() == null ? e.toString() : e.getMessage();
                failed(owner, tr("settings.reset.backupFailed", why));
            } else {
                then.accept(backup);
            }
        });
    }

    private static void failed(Window owner, String text) {
        Alert failed = Dialogs.styled(new Alert(Alert.AlertType.ERROR, text, ButtonType.OK));
        failed.initOwner(owner);
        failed.setTitle(tr("settings.reset.title"));
        failed.setHeaderText(null);
        Dialogs.showAndWait(failed);
    }
}
