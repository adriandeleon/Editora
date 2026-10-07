package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.config.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Settings actions that destroyed data on a click: stepping a Local History retention spinner deleted
 * every revision outside the new limit at once (stepping back restored nothing), and "Reset to Defaults"
 * rewrote settings.json — API keys, external tools and all — without keeping a copy.
 */
@Tag("fx")
class SettingsDataLossFxTest {

    private static final long DAY = 86_400_000L;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Polls {@code probe} on the FX thread until it holds. */
    private static void await(String what, Callable<Boolean> probe) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(probe)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    /** Waits for a dialog titled {@code title}, presses its {@code button} and returns the text it showed. */
    private static String answerDialog(String title, ButtonBar.ButtonData button) throws Exception {
        String[] text = new String[1];
        await("the \"" + title + "\" dialog", () -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane
                        && title.equals(((Stage) window).getTitle())) {
                    text[0] = pane.getContentText();
                    ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                    .filter(type -> type.getButtonData() == button)
                                    .findFirst()
                                    .orElseThrow()))
                            .fire();
                    return true;
                }
            }
            return false;
        });
        return text[0];
    }

    private static boolean anyDialogShowing() {
        return Window.getWindows().stream()
                .anyMatch(w -> w.getScene() != null && w.getScene().getRoot() instanceof DialogPane);
    }

    private static SettingsWindow showSettings(FxWindowFixture fx) throws Exception {
        SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        FxTestSupport.drainFx();
        return window;
    }

    private static HistoryRevision rev(String path, long timestamp, String sha) {
        return new HistoryRevision(path, timestamp, 10, sha, HistoryRevision.REASON_SAVE);
    }

    @Test
    void aLoweredHistoryLimitDeletesNothingUntilTheUserConfirmsTheCount(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path config = Files.createDirectory(dir.resolve("config"));
        String a = PathKeys.normalizedKey(dir.resolve("a.txt"));
        String b = PathKeys.normalizedKey(dir.resolve("b.txt"));
        ConfigManager seed = new ConfigManager(config);
        seed.load();
        seed.shared()
                .historyBucket("")
                .put(
                        a,
                        List.of(
                                rev(a, now - 5 * DAY, "a5"),
                                rev(a, now - 15 * DAY, "a15"),
                                rev(a, now - 20 * DAY, "a20")));
        seed.shared()
                .historyBucket("some-project")
                .put(b, List.of(rev(b, now - DAY, "b1"), rev(b, now - 25 * DAY, "b25")));
        seed.shared().saveHistory();
        assertTrue(seed.shared().flushWrites());
        seed.shared().shutdown();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            Settings settings = fx.shared.getSettings();
            Callable<Integer> revisions = () -> fx.shared.historyByProject().values().stream()
                    .flatMap(bucket -> bucket.values().stream())
                    .mapToInt(List::size)
                    .sum();
            async.awaitFx();
            async.awaitWorker(worker);
            async.awaitFx();
            assertEquals(5, FxTestSupport.callOnFx(revisions), "the default 30 days keeps all five");

            SettingsWindow window = showSettings(fx);
            Spinner<Integer> age = FxTestSupport.field(window, "historyMaxAgeSpinner");
            try {
                // 30 → 29 would delete nothing: applied without a question.
                FxTestSupport.runOnFx(() -> age.getValueFactory().setValue(29));
                await("29 days to be stored", () -> settings.getHistoryMaxAgeDays() == 29);
                assertFalse(FxTestSupport.callOnFx(SettingsDataLossFxTest::anyDialogShowing));
                assertEquals(5, FxTestSupport.callOnFx(revisions));

                // 29 → 10 would delete three revisions in two files: nothing happens until the user answers.
                FxTestSupport.runOnFx(() -> age.getValueFactory().setValue(10));
                String question = answerDialog(tr("dialog.history.limits.title"), ButtonBar.ButtonData.CANCEL_CLOSE);
                assertEquals(tr("dialog.history.limits.confirm", 3, 2), question, "the count and the files are stated");
                await("the spinner to return to the limit in force", () -> age.getValue() == 29);
                assertEquals(29, settings.getHistoryMaxAgeDays(), "declined: the setting was never written");
                async.awaitWorker(worker);
                async.awaitFx();
                assertEquals(5, FxTestSupport.callOnFx(revisions), "and nothing was deleted");

                // The same step, confirmed.
                FxTestSupport.runOnFx(() -> age.getValueFactory().setValue(10));
                answerDialog(tr("dialog.history.limits.title"), ButtonBar.ButtonData.OK_DONE);
                await("the confirmed limit to be applied", () -> revisions.call() == 2);
                assertEquals(10, settings.getHistoryMaxAgeDays());

                // Loosening is immediate and asks nothing.
                FxTestSupport.runOnFx(() -> age.getValueFactory().setValue(60));
                await("60 days to be stored", () -> settings.getHistoryMaxAgeDays() == 60);
                assertFalse(FxTestSupport.callOnFx(SettingsDataLossFxTest::anyDialogShowing));
            } finally {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.<Stage>field(window, "stage").hide());
            }
        }
    }

    @Test
    void aLimitLoweredBehindTheSettingsWindowIsNotAppliedInThisSession(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path config = Files.createDirectory(dir.resolve("config"));
        String a = PathKeys.normalizedKey(dir.resolve("a.txt"));
        ConfigManager seed = new ConfigManager(config);
        seed.load();
        seed.shared().historyBucket("").put(a, List.of(rev(a, now - 5 * DAY, "a5"), rev(a, now - 20 * DAY, "a20")));
        seed.shared().saveHistory();
        assertTrue(seed.shared().flushWrites());
        seed.shared().shutdown();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            async.awaitFx();
            async.awaitWorker(worker);
            async.awaitFx();

            // Whatever writes the setting without asking (a reset, a hand-edited file re-read, another
            // process): the sweep that every settings apply runs must not act on it.
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setHistoryMaxAgeDays(10);
                history.applySupport();
            });
            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                            () -> fx.shared.historyBucket("").get(a).size()));
        }
    }

    @Test
    void theSetLimitCommandsAskTheSameQuestion(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path config = Files.createDirectory(dir.resolve("config"));
        String a = PathKeys.normalizedKey(dir.resolve("a.txt"));
        ConfigManager seed = new ConfigManager(config);
        seed.load();
        seed.shared()
                .historyBucket("")
                .put(a, List.of(rev(a, now - DAY, "a1"), rev(a, now - 2 * DAY, "a2"), rev(a, now - 3 * DAY, "a3")));
        seed.shared().saveHistory();
        assertTrue(seed.shared().flushWrites());
        seed.shared().shutdown();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            Settings settings = fx.shared.getSettings();
            Callable<Integer> revisions =
                    () -> fx.shared.historyBucket("").get(a).size();
            async.awaitFx();
            async.awaitWorker(worker);
            async.awaitFx();

            // What history.setMaxPerFile does with the number typed into its prompt.
            FxTestSupport.runOnFx(() -> history.changeLimit("Max revisions", HistoryCoordinator.Limit.MAX_PER_FILE, 1));
            String question = answerDialog(tr("dialog.history.limits.title"), ButtonBar.ButtonData.CANCEL_CLOSE);
            assertEquals(tr("dialog.history.limits.confirm", 2, 1), question);
            async.awaitWorker(worker);
            async.awaitFx();
            assertEquals(50, settings.getHistoryMaxPerFile(), "declined: the limit is unchanged");
            assertEquals(3, FxTestSupport.callOnFx(revisions));

            FxTestSupport.runOnFx(() -> history.changeLimit("Max revisions", HistoryCoordinator.Limit.MAX_PER_FILE, 1));
            answerDialog(tr("dialog.history.limits.title"), ButtonBar.ButtonData.OK_DONE);
            await("the confirmed limit to be applied", () -> revisions.call() == 1);
            assertEquals(1, settings.getHistoryMaxPerFile());
        }
    }

    @Test
    void resetToDefaultsKeepsACopyOfTheSettingsAndSaysWhere() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Settings settings = fx.shared.getSettings();
            FxTestSupport.runOnFx(() -> {
                settings.setAuthorName("Grace Hopper");
                settings.setHistoryMaxAgeDays(365);
            });
            SettingsWindow window = showSettings(fx);
            try {
                FxTestSupport.runOnFx(
                        () -> javafx.application.Platform.runLater(() -> FxTestSupport.invoke(window, "resetAll")));
                answerDialog(tr("settings.reset.title"), ButtonBar.ButtonData.OK_DONE);
                await("the reset", () -> settings.getAuthorNameRaw().isEmpty());

                List<Path> backups;
                try (var files = Files.list(fx.configDir)) {
                    backups = files.filter(f -> f.getFileName().toString().startsWith("settings.json.before-reset-"))
                            .toList();
                }
                assertEquals(1, backups.size(), "one backup beside settings.json");
                assertTrue(
                        Files.readString(backups.get(0)).contains("Grace Hopper"), "holding the settings as they were");
                assertFalse(
                        Files.readString(fx.configDir.resolve("settings.json")).contains("Grace Hopper"));
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                String expected =
                        tr("status.settings.reset", backups.get(0).getFileName().toString());
                await(
                        "the status message naming the backup",
                        () -> statusText(statusBar).contains(expected));
                assertEquals(
                        365,
                        settings.getHistoryMaxAgeDays(),
                        "a reset does not lower a Local History limit: that would delete revisions unasked");
            } finally {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.<Stage>field(window, "stage").hide());
            }
        }
    }

    private static String statusText(StatusBar statusBar) {
        StringBuilder all = new StringBuilder();
        collectText(statusBar, all);
        return all.toString();
    }

    private static void collectText(javafx.scene.Node node, StringBuilder out) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            out.append(labeled.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectText(child, out));
        }
    }
}
