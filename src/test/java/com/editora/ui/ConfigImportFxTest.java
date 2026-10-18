package com.editora.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigImport;
import com.editora.config.ConfigImport.Part;
import com.editora.config.Settings;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import Configuration in a real window, on the window's own temporary config folder (never ~/.editora): the
 * zip chooser, the parts question and the last dialog are answered through the flow's seams.
 */
@Tag("fx")
class ConfigImportFxTest {

    @TempDir
    Path tmp;

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigImportFlow.chooser = (w, d) -> null;
        ConfigImportFlow.ask = q -> Optional.empty();
        ConfigImportFlow.tell = o -> {};
        if (fx != null) {
            fx.dispose();
        }
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path zip = tmp.resolve("editora-config-test.zip");
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    /** Runs Import Configuration with {@code zip} and {@code parts}; returns the question and the outcome. */
    private Map.Entry<ConfigImportFlow.Question, ConfigImportFlow.Outcome> importing(Path zip, Part... parts)
            throws Exception {
        AtomicReference<ConfigImportFlow.Question> asked = new AtomicReference<>();
        CompletableFuture<ConfigImportFlow.Outcome> told = new CompletableFuture<>();
        ConfigImportFlow.chooser = (w, start) -> zip;
        ConfigImportFlow.ask = q -> {
            asked.set(q);
            return Optional.of(parts.length == 0 ? EnumSet.noneOf(Part.class) : EnumSet.of(parts[0], parts));
        };
        ConfigImportFlow.tell = told::complete;
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> assertTrue(registry.run("config.import")));
        ConfigImportFlow.Outcome outcome = told.get(60, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
        return Map.entry(
                asked.get() == null ? new ConfigImportFlow.Question(null, "", List.of(), 0) : asked.get(), outcome);
    }

    @Test
    void theTickedPartsArriveAndTheLiveOnesApplyAtOnce() throws Exception {
        fx = FxWindowFixture.create();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("settings.json", "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + ", \"fontSize\": 19}");
        entries.put("snippets/java.json", "{\"main\": {\"prefix\": \"psvm\", \"body\": \"x\"}}");
        entries.put(
                "abbreviations.json",
                "{\"schemaVersion\":1,\"abbreviations\":[{\"abbreviation\":\"btw\",\"expansion\":\"by the way\"}]}");
        entries.put("macros.json", "{\"schemaVersion\":1,\"macros\":[]}");
        entries.put("workspace-state.json", "{}"); // the other computer's windows: left out
        Map.Entry<ConfigImportFlow.Question, ConfigImportFlow.Outcome> run =
                importing(zip(entries), Part.PREFERENCES, Part.SNIPPETS, Part.ABBREVIATIONS, Part.MACROS);

        ConfigImportFlow.Outcome outcome = run.getValue();
        assertNull(outcome.failure(), outcome.failure());
        assertEquals(1, run.getKey().leftOut());
        assertEquals(EnumSet.of(Part.MACROS), outcome.needRestart(), "only macros wait for a restart");
        FxTestSupport.runOnFx(() -> {
            assertEquals(19, fx.shared.getSettings().getFontSize(), "preferences apply at once");
            assertTrue(
                    fx.shared.getAbbreviations().stream()
                            .anyMatch(a -> "btw".equals(a.getAbbreviation()) && "by the way".equals(a.getExpansion())),
                    "abbreviations apply at once");
        });
        assertTrue(Files.readString(fx.configDir.resolve("snippets/java.json")).contains("psvm"));
        assertEquals("{\"schemaVersion\":1,\"macros\":[]}", Files.readString(fx.configDir.resolve("macros.json")));
        assertNotNull(outcome.backup());
        assertTrue(Files.isRegularFile(outcome.backup()), "the previous configuration was saved first");
        assertTrue(outcome.backup().startsWith(fx.configDir.resolve(ConfigImport.BACKUPS_DIR)));
    }

    @Test
    void aZipThatLeavesTheFolderIsRefusedAndNothingIsWritten() throws Exception {
        fx = FxWindowFixture.create();
        Path zip = zip(Map.of(
                "settings.json", "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + "}", "../../outside.txt", "x"));
        ConfigImportFlow.Outcome outcome = importing(zip, Part.PREFERENCES).getValue();
        assertEquals(tr("dialog.importConfig.refused.UNSAFE_PATH", "../../outside.txt"), outcome.failure());
        assertFalse(Files.exists(fx.configDir.resolve(ConfigImport.BACKUPS_DIR)), "not even a backup");
        assertFalse(Files.exists(tmp.resolve("outside.txt")));
    }

    /** The parts question: Cancel is the default, plugins and bookmarks start unticked, absent parts greyed. */
    @Test
    void thePartsQuestionStartsSafe() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ConfigImportFlow.Question q = new ConfigImportFlow.Question(
                    null,
                    "editora-config.zip",
                    List.of(
                            new ConfigImport.Summary(Part.PREFERENCES, 1, 1, 0, 0),
                            new ConfigImport.Summary(Part.SNIPPETS, 3, 1, 2, 0),
                            new ConfigImport.Summary(Part.MACROS, 0, 0, 0, 0),
                            new ConfigImport.Summary(Part.PLUGINS, 4, 0, 4, 0)),
                    2);
            Dialog<java.util.Set<Part>> d = ConfigImportFlow.build(q);
            assertEquals(tr("dialog.importConfig.header", "editora-config.zip"), d.getHeaderText());
            assertTrue(((Button) d.getDialogPane().lookupButton(ButtonType.CANCEL)).isDefaultButton());
            javafx.scene.Node parts =
                    ((javafx.scene.control.ScrollPane) d.getDialogPane().getContent()).getContent();
            CheckBox snippets = (CheckBox) parts.lookup("#import-SNIPPETS");
            CheckBox macros = (CheckBox) parts.lookup("#import-MACROS");
            CheckBox plugins = (CheckBox) parts.lookup("#import-PLUGINS");
            assertTrue(snippets.isSelected());
            assertTrue(macros.isDisabled() && !macros.isSelected(), "not in the file");
            assertFalse(plugins.isSelected(), "code: only when asked for");
            ButtonType importButton = d.getDialogPane().getButtonTypes().getFirst();
            assertEquals(
                    EnumSet.of(Part.PREFERENCES, Part.SNIPPETS),
                    d.getResultConverter().call(importButton));
            assertNull(d.getResultConverter().call(ButtonType.CANCEL));
            assertEquals(
                    tr("dialog.importConfig.files", 3, 1, 2, 0),
                    ConfigImportFlow.describe(new ConfigImport.Summary(Part.SNIPPETS, 3, 1, 2, 0)));
        });
    }

    /** Settings → Advanced has Import Configuration… next to Export, and it runs the command. */
    @Test
    void theAdvancedPageOffersImport() throws Exception {
        fx = FxWindowFixture.create();
        boolean[] chooserShown = {false};
        ConfigImportFlow.chooser = (w, start) -> {
            chooserShown[0] = true;
            return null; // cancelled
        };
        FxTestSupport.runOnFx(() -> {
            SettingsWindow w = fx.controller.settingsWindow;
            w.show(FxTestSupport.field(fx.controller, "stage"));
            java.util.Map<?, javafx.scene.layout.Region> pages = FxTestSupport.field(w, "pages");
            javafx.scene.layout.Region page = pages.entrySet().stream()
                    .filter(e -> e.getKey().toString().equals("ADVANCED"))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
            ((Button) SettingsRig.button(page, tr("settings.importConfig"))).fire();
        });
        assertTrue(chooserShown[0]);
    }
}
