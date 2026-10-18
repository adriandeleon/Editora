package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.editora.config.ConfigImport;
import com.editora.config.ConfigImport.Part;
import com.editora.config.SharedConfig;

import static com.editora.i18n.Messages.tr;

/**
 * Import Configuration… ({@code config.import}, and Settings → Advanced): pick an Editora export zip, see what
 * it holds and what each part would replace, untick parts, import. The current configuration is copied to
 * {@code backups/} first. Preferences, key bindings, external tools, snippets, templates, abbreviations and the
 * dictionary apply in every open window at once; the rest takes effect at the next start, which the last
 * dialog offers. Reading and writing run off the FX thread.
 */
final class ConfigImportFlow {

    private final Window owner;
    private final SharedConfig shared;
    private final WindowManager windows;
    private final Consumer<String> status;
    private final BackgroundTasks tasks;

    /** Picks the zip, starting in the given folder (null: cancelled). Replaceable by tests (no native chooser). */
    static java.util.function.BiFunction<Window, Path, Path> chooser = ConfigImportFlow::chooseZip;
    /** Asks which parts to import (empty: cancelled). Replaceable by tests. */
    static Function<Question, Optional<Set<Part>>> ask = ConfigImportFlow::askDialog;
    /** Says how it went; {@code restart} is offered when parts need one. Replaceable by tests. */
    static Consumer<Outcome> tell = ConfigImportFlow::tellDialog;
    /** Restarts Editora (null: not available, so the last dialog only says to restart). */
    static Runnable restart; // set by WindowCommandHost.importConfig to the app.restart command

    /** Puts the real chooser and dialogs back after a test replaced them. */
    static void resetSeams() {
        chooser = ConfigImportFlow::chooseZip;
        ask = ConfigImportFlow::askDialog;
        tell = ConfigImportFlow::tellDialog;
    }

    /** What the parts dialog shows. */
    record Question(Window owner, String zipName, List<ConfigImport.Summary> parts, int leftOut) {}

    /** How an import ended: the parts applied, those that wait for a restart, the backup; or why it failed. */
    record Outcome(Window owner, Set<Part> applied, Set<Part> needRestart, Path backup, String failure) {}

    ConfigImportFlow(
            Window owner, SharedConfig shared, WindowManager windows, Consumer<String> status, BackgroundTasks tasks) {
        this.owner = owner;
        this.shared = shared;
        this.windows = windows;
        this.status = status;
        this.tasks = tasks;
    }

    /** Runs the whole flow from the file chooser. */
    void start() {
        Path zip = chooser.apply(owner, shared.exportDirectory()); // where Export Configuration puts its zips
        if (zip == null) {
            return;
        }
        BackgroundTasks.Handle task = tasks.start(tr("dialog.importConfig.title"));
        CompletableFuture.supplyAsync(() -> {
                    try {
                        ConfigImport.Scan scan = ConfigImport.scan(zip, shared);
                        return Map.entry(scan, scan.summaries(shared.getConfigDir()));
                    } catch (ConfigImport.Refused | IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                })
                .whenComplete((scanned, failure) -> Platform.runLater(() -> {
                    task.done();
                    if (failure != null) {
                        refused(failure.getCause() == null ? failure : failure.getCause());
                        return;
                    }
                    Optional<Set<Part>> parts = ask.apply(new Question(
                            owner,
                            zip.getFileName().toString(),
                            scanned.getValue(),
                            scanned.getKey().leftOut().size()));
                    if (parts.isPresent() && !parts.get().isEmpty()) {
                        apply(scanned.getKey(), parts.get());
                    }
                }));
    }

    private void apply(ConfigImport.Scan scan, Set<Part> parts) {
        BackgroundTasks.Handle task = tasks.start(tr("dialog.importConfig.title"));
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return ConfigImport.apply(scan, parts, shared);
                    } catch (ConfigImport.Refused | IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                })
                .whenComplete((backup, failure) -> Platform.runLater(() -> {
                    task.done();
                    if (failure != null) {
                        Throwable why = failure.getCause() == null ? failure : failure.getCause();
                        status.accept(tr("status.importConfig.failed", String.valueOf(why.getMessage())));
                        tell.accept(new Outcome(owner, Set.of(), Set.of(), null, String.valueOf(why.getMessage())));
                        return;
                    }
                    reloadLive(parts);
                    Set<Part> later = EnumSet.noneOf(Part.class);
                    parts.stream().filter(p -> !p.live()).forEach(later::add);
                    status.accept(
                            tr("status.importConfig.done", backup.getFileName().toString()));
                    tell.accept(new Outcome(owner, parts, later, backup, null));
                }));
    }

    /** Makes the open windows show what was imported, for the parts that can change without a restart. */
    private void reloadLive(Set<Part> parts) {
        if (parts.stream().anyMatch(Part::inSettingsFile)) {
            windows.settingsFileSaved(); // re-reads settings.json and applies it everywhere, keymap included
        }
        if (parts.contains(Part.ABBREVIATIONS)) {
            shared.reloadAbbreviations();
            windows.broadcastSettingsApplied(); // each buffer holds its own copy of the abbreviation map
        }
        if (parts.contains(Part.DICTIONARY) && shared.reloadUserDictionary()) {
            windows.broadcastUserDictionaryChanged();
        }
        if (parts.contains(Part.SNIPPETS)) {
            windows.broadcastSnippetsChanged(null);
        }
        if (parts.contains(Part.SNIPPETS) || parts.contains(Part.TEMPLATES)) {
            windows.refreshFileBackedSettings();
        }
    }

    private void refused(Throwable why) {
        String text = why instanceof ConfigImport.Refused r
                ? tr("dialog.importConfig.refused." + r.reason().name(), r.entry() == null ? "" : r.entry())
                : tr("dialog.importConfig.unreadable", String.valueOf(why.getMessage()));
        status.accept(text);
        tell.accept(new Outcome(owner, Set.of(), Set.of(), null, text));
    }

    // --- the dialogs -----------------------------------------------------------------------------------------

    private static Path chooseZip(Window owner, Path start) {
        FileChooser fc = new FileChooser();
        fc.setTitle(tr("dialog.importConfig.title"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("dialog.importConfig.zipFilter"), "*.zip"));
        if (start != null && Files.isDirectory(start)) {
            fc.setInitialDirectory(start.toFile());
        }
        File f = fc.showOpenDialog(owner);
        return f == null ? null : f.toPath();
    }

    /** The parts dialog, not yet shown (tests look inside it). Cancel is the default button. */
    static Dialog<Set<Part>> build(Question q) {
        Dialog<Set<Part>> dialog = Dialogs.styled(new Dialog<>());
        if (q.owner() != null) {
            dialog.initOwner(q.owner());
        }
        dialog.setTitle(tr("dialog.importConfig.title"));
        dialog.setHeaderText(tr("dialog.importConfig.header", q.zipName()));
        VBox content = new VBox(8);
        Map<Part, CheckBox> boxes = new LinkedHashMap<>();
        for (ConfigImport.Summary s : q.parts()) {
            CheckBox box =
                    new CheckBox(tr("dialog.importConfig.part." + s.part().name()));
            box.setId("import-" + s.part().name());
            box.setSelected(s.available() && s.part().onByDefault());
            box.setDisable(!s.available());
            Label what = new Label(describe(s));
            what.getStyleClass().add("settings-row-desc");
            what.setWrapText(true);
            what.setMaxWidth(480);
            VBox.setMargin(what, new javafx.geometry.Insets(0, 0, 0, 24));
            content.getChildren().addAll(box, what);
            boxes.put(s.part(), box);
        }
        if (q.leftOut() > 0) {
            content.getChildren().add(note(tr("dialog.importConfig.leftOut", q.leftOut())));
        }
        content.getChildren().add(note(tr("dialog.importConfig.backupNote")));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);
        dialog.getDialogPane().setContent(scroll);
        ButtonType importButton = new ButtonType(tr("dialog.importConfig.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(importButton, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(importButton)).setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        dialog.setResultConverter(button -> {
            if (button != importButton) {
                return null;
            }
            Set<Part> chosen = EnumSet.noneOf(Part.class);
            boxes.forEach((part, box) -> {
                if (box.isSelected() && !box.isDisabled()) {
                    chosen.add(part);
                }
            });
            return chosen;
        });
        return dialog;
    }

    /** One part's line under its checkbox: what the zip has and what it would do here. */
    static String describe(ConfigImport.Summary s) {
        if (!s.available()) {
            return tr("dialog.importConfig.notInFile");
        }
        List<String> parts = new ArrayList<>();
        parts.add(
                s.part().inSettingsFile()
                        ? tr("dialog.importConfig.replacesYours")
                        : tr("dialog.importConfig.files", s.files(), s.replaced(), s.added(), s.removed()));
        if (!s.part().live()) {
            parts.add(tr("dialog.importConfig.afterRestart"));
        }
        if (s.part() == Part.PLUGINS) {
            parts.add(tr("dialog.importConfig.pluginsNote"));
        }
        if (s.part() == Part.BOOKMARKS_NOTES) {
            parts.add(tr("dialog.importConfig.bookmarksNote"));
        }
        return String.join(" ", parts);
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        l.setWrapText(true);
        l.setMaxWidth(480);
        return l;
    }

    private static Optional<Set<Part>> askDialog(Question q) {
        return build(q).showAndWait();
    }

    private static void tellDialog(Outcome o) {
        boolean failed = o.failure() != null;
        Alert alert = Dialogs.styled(new Alert(failed ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION));
        if (o.owner() != null) {
            alert.initOwner(o.owner());
        }
        alert.setTitle(tr("dialog.importConfig.title"));
        alert.setHeaderText(tr(failed ? "dialog.importConfig.failed" : "dialog.importConfig.done"));
        if (failed) {
            alert.setContentText(o.failure());
            alert.showAndWait();
            return;
        }
        StringBuilder body =
                new StringBuilder(tr("dialog.importConfig.backup", o.backup().toString()));
        if (!o.needRestart().isEmpty()) {
            List<String> names = new ArrayList<>();
            o.needRestart().forEach(p -> names.add(tr("dialog.importConfig.part." + p.name())));
            body.append("\n\n").append(tr("dialog.importConfig.restartFor", String.join(", ", names)));
        }
        alert.setContentText(body.toString());
        ButtonType restartNow = new ButtonType(tr("dialog.importConfig.restartNow"), ButtonBar.ButtonData.OK_DONE);
        if (!o.needRestart().isEmpty() && restart != null) {
            alert.getButtonTypes().setAll(restartNow, ButtonType.CLOSE);
        }
        if (alert.showAndWait().orElse(ButtonType.CLOSE) == restartNow && restart != null) {
            restart.run();
        }
    }
}
