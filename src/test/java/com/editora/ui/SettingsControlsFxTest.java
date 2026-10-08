package com.editora.ui;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

import com.editora.config.Settings;
import com.editora.git.GitPullMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings window is live-apply: a control writes its own setting and applies it the moment it changes,
 * and the window shows the settings in force each time it is opened. Covered here control by control, in
 * both directions, with input the control must refuse.
 */
@Tag("fx")
class SettingsControlsFxTest {

    private SettingsRig rig;
    private Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
        settings = rig.settings;
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            FxTestSupport.runOnFx(
                    () -> javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT)));
        } finally {
            rig.close();
        }
    }

    /** {@code maven-pom} → {@code MavenPom}: the server id as it appears in the names of its settings. */
    private static String cap(String id) {
        StringBuilder out = new StringBuilder();
        for (String part : id.split("-")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static Object get(Settings settings, String getter) {
        try {
            return Settings.class.getMethod(getter).invoke(settings);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(getter, e);
        }
    }

    private static void set(Settings settings, String setter, Class<?> type, Object value) {
        try {
            Method m = Settings.class.getMethod(setter, type);
            m.invoke(settings, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(setter, e);
        }
    }

    @Test
    void openingTheWindowAppliesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.show(); // and neither does bringing it to the front again
            assertEquals(List.of(), rig.applied, "showing the settings in force is not a change");
            assertTrue(rig.stage().isShowing());
        });
    }

    /** Each language-server row writes the setting of its own server, and no other row's. */
    @Test
    void aLanguageServerCommandIsWrittenToItsOwnSettingOnEnter() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Map<String, TextField> fields = rig.control("lspCommandFields");
            assertTrue(fields.size() >= 24, "every configurable server has a row: " + fields.keySet());
            fields.forEach((id, field) -> {
                int before = rig.applied.size();
                SettingsRig.typeAndEnter(field, "/opt/servers/" + id);
                assertEquals("/opt/servers/" + id, get(settings, "get" + cap(id) + "LspCommand"), id);
                assertEquals(before + 1, rig.applied.size(), id + " is applied once");
            });
            // Every setting still holds its own row's text: no row overwrote a neighbour's.
            fields.keySet()
                    .forEach(id -> assertEquals("/opt/servers/" + id, get(settings, "get" + cap(id) + "LspCommand")));
        });
    }

    @Test
    void aLanguageServerSwitchIsWrittenToItsOwnSetting() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Map<String, CheckBox> checks = rig.control("lspEnableChecks");
            checks.forEach((id, check) -> {
                boolean was = (Boolean) get(settings, "is" + cap(id) + "LspEnabled");
                assertEquals(was, check.isSelected(), id + " shows the setting");
                check.setSelected(!was);
                assertEquals(!was, get(settings, "is" + cap(id) + "LspEnabled"), id);
                assertSame(settings, rig.applied.getLast());
            });
        });
    }

    @Test
    void theLanguageServerRowsShowTheSettingsInForceWhenTheWindowOpens() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Map<String, TextField> fields = rig.control("lspCommandFields");
            Map<String, CheckBox> checks = rig.control("lspEnableChecks");
            rig.stage().hide();
            for (String id : fields.keySet()) {
                set(settings, "set" + cap(id) + "LspCommand", String.class, "changed-elsewhere-" + id);
                boolean on = (Boolean) get(settings, "is" + cap(id) + "LspEnabled");
                set(settings, "set" + cap(id) + "LspEnabled", boolean.class, !on);
            }
            rig.applied.clear();

            rig.show();

            fields.forEach((id, field) -> assertEquals("changed-elsewhere-" + id, field.getText(), id));
            checks.forEach(
                    (id, check) -> assertEquals(get(settings, "is" + cap(id) + "LspEnabled"), check.isSelected(), id));
            assertEquals(List.of(), rig.applied);
        });
    }

    @Test
    void aTextFieldCommitsOnEnterOrWhenTheWindowClosesAndOnlyWhenItsTextChanged() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            TextField gh = rig.control("ghPathField");
            TextField mmdc = rig.control("mmdcPathField");
            TextField maid = rig.control("maidPathField");
            TextField dot = rig.control("dotPathField");
            TextField plantuml = rig.control("plantumlPathField");

            SettingsRig.typeAndEnter(gh, "/usr/local/bin/gh");
            assertEquals("/usr/local/bin/gh", settings.getGhPath());
            int afterFirst = rig.applied.size();
            assertEquals(1, afterFirst);

            gh.fireEvent(new javafx.event.ActionEvent()); // Enter again on the same text
            assertEquals(afterFirst, rig.applied.size(), "unchanged text is not applied a second time");

            // Typing alone changes nothing: a half-typed path is never probed or saved.
            mmdc.setText("/opt/mmdc");
            maid.setText("/opt/maid");
            dot.setText("/opt/dot");
            plantuml.setText("/opt/plantuml");
            assertEquals(afterFirst, rig.applied.size());
            assertNotEquals("/opt/mmdc", settings.getMmdcPath());

            rig.stage().hide(); // closing the window commits whatever was typed last
            assertEquals("/opt/mmdc", settings.getMmdcPath());
            assertEquals("/opt/maid", settings.getMaidPath());
            assertEquals("/opt/dot", settings.getDotPath());
            assertEquals("/opt/plantuml", settings.getPlantumlPath());
            assertEquals(afterFirst + 4, rig.applied.size());

            mmdc.setText(null); // a cleared field is the empty setting, not a failure
            mmdc.fireEvent(new javafx.event.ActionEvent());
            assertEquals("", settings.getMmdcPath());
        });
    }

    @Test
    void theToolPathFieldsShowTheSettingsInForce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setGhPath("gh-x");
            settings.setMmdcPath("mmdc-x");
            settings.setMaidPath("maid-x");
            settings.setDotPath("dot-x");
            settings.setPlantumlPath("plantuml-x");
            settings.setGithubSupport(false);
            rig.show();
            assertEquals("gh-x", rig.<TextField>control("ghPathField").getText());
            assertTrue(rig.<TextField>control("ghPathField").isDisable(), "no path to set while GitHub is off");
            assertEquals("mmdc-x", rig.<TextField>control("mmdcPathField").getText());
            assertEquals("maid-x", rig.<TextField>control("maidPathField").getText());
            assertEquals("dot-x", rig.<TextField>control("dotPathField").getText());
            assertEquals(
                    "plantuml-x", rig.<TextField>control("plantumlPathField").getText());

            rig.<CheckBox>control("githubCheck").setSelected(true);
            assertTrue(settings.isGithubSupport());
            assertFalse(rig.<TextField>control("ghPathField").isDisable());
        });
    }

    @Test
    void theDebugAdapterRowsWriteAndShowTheirOwnSettings() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setDebugSupport(true);
            settings.setPythonDebugEnabled(false);
            settings.setJsDebugEnabled(true);
            settings.setPythonDebugCommand("python-in-force");
            rig.show();
            Map<String, TextField> fields = rig.control("debugCommandFields");
            Map<String, CheckBox> enables = rig.control("debugEnableChecks");
            assertEquals("python-in-force", fields.get("python").getText());
            assertFalse(enables.get("python").isSelected());
            assertTrue(enables.get("javascript").isSelected());
            assertFalse(enables.containsKey("java"), "the Java adapter follows the Java language server");

            SettingsRig.typeAndEnter(fields.get("java"), "/opt/java-debug.jar");
            SettingsRig.typeAndEnter(fields.get("python"), "/opt/python3");
            SettingsRig.typeAndEnter(fields.get("javascript"), "/opt/dapDebugServer.js");
            assertEquals("/opt/java-debug.jar", settings.getJavaDebugPluginPath());
            assertEquals("/opt/python3", settings.getPythonDebugCommand());
            assertEquals("/opt/dapDebugServer.js", settings.getJsDebugPath());

            enables.get("python").setSelected(true);
            enables.get("javascript").setSelected(false);
            assertTrue(settings.isPythonDebugEnabled());
            assertFalse(settings.isJsDebugEnabled());

            // The master switch gates every adapter row, and is itself a setting.
            CheckBox debug = rig.control("debugCheck");
            CheckBox console = rig.control("debugProgramConsoleCheck");
            debug.setSelected(false);
            assertFalse(settings.isDebugSupport());
            assertTrue(fields.get("python").isDisable());
            assertTrue(enables.get("python").isDisable());
            assertTrue(console.isDisable());
            debug.setSelected(true);
            assertTrue(settings.isDebugSupport());
            assertFalse(fields.get("python").isDisable());
            assertFalse(console.isDisable());

            console.setSelected(!console.isSelected());
            assertEquals(console.isSelected(), settings.isDebugProgramConsole());
        });
    }

    @Test
    void theAgentRowsWriteAndShowTheirOwnSettings() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setAgentSupport(false);
            settings.setAgentCommand("claude-in-force");
            rig.show();
            Map<String, TextField> fields = rig.control("agentCommandFields");
            assertEquals("claude-in-force", fields.get("claude").getText());

            SettingsRig.typeAndEnter(fields.get("claude"), "/opt/claude-acp");
            SettingsRig.typeAndEnter(fields.get("gemini"), "/opt/gemini");
            assertEquals("/opt/claude-acp", settings.getAgentCommand());
            assertEquals("/opt/gemini", settings.getGeminiAgentCommand());

            CheckBox agent = rig.control("agentCheck");
            assertFalse(agent.isSelected());
            agent.setSelected(true);
            assertTrue(settings.isAgentSupport());

            CheckBox context = rig.control("agentIncludeContextCheck");
            context.setSelected(!context.isSelected());
            assertEquals(context.isSelected(), settings.isAgentIncludeContext());

            // Without an agent coordinator there is nothing to switch: the combo must not pretend it did.
            ComboBox<String> client = rig.control("agentClientCombo");
            String before = settings.getAgentClient();
            int applied = rig.applied.size();
            String other = client.getItems().stream()
                    .filter(id -> !id.equals(client.getValue()))
                    .findFirst()
                    .orElseThrow();
            client.setValue(other);
            assertEquals(before, settings.getAgentClient());
            assertEquals(applied, rig.applied.size());
            assertEquals(
                    tr("settings.agent.client." + other), client.getConverter().toString(other));
            assertEquals("", client.getConverter().toString(null));
        });
    }

    @Test
    void changingTheApplicationThemeTakesTheEditorThemeAlongUntilTheUserPicksOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setEditorThemeUserSet(false);
            rig.show();
            ComboBox<String> theme = rig.control("themeCombo");
            ComboBox<String> editorTheme = rig.control("editorThemeCombo");
            assertEquals(Themes.normalize(settings.getTheme()), theme.getValue());
            List<String> others = theme.getItems().stream()
                    .filter(name -> !name.equals(theme.getValue()))
                    .limit(2)
                    .toList();

            theme.setValue(others.get(0));
            assertEquals(others.get(0), settings.getTheme());
            assertEquals(EditorThemes.defaultFor(others.get(0)), settings.getEditorTheme());
            assertEquals(settings.getEditorTheme(), editorTheme.getValue(), "the combo follows");
            assertFalse(settings.isEditorThemeUserSet(), "following is not the user's choice");
            assertEquals(Themes.stylesheetFor(others.get(0)), javafx.application.Application.getUserAgentStylesheet());
            assertEquals(1, rig.applied.size());
            assertTrue(
                    rig.stage().getScene().getStylesheets().contains(EditorThemes.stylesheetFor(editorTheme.getValue()))
                            || EditorThemes.stylesheetFor(editorTheme.getValue()) == null,
                    "the preview is recoloured");

            String picked = editorTheme.getItems().stream()
                    .filter(name -> !name.equals(editorTheme.getValue()))
                    .findFirst()
                    .orElseThrow();
            editorTheme.setValue(picked);
            assertEquals(picked, settings.getEditorTheme());
            assertTrue(settings.isEditorThemeUserSet());

            theme.setValue(others.get(1));
            assertEquals(others.get(1), settings.getTheme());
            assertEquals(picked, settings.getEditorTheme(), "a chosen editor theme is kept");
            assertEquals(picked, editorTheme.getValue());

            theme.setValue(null); // a cleared selection is not a theme
            assertEquals(others.get(1), settings.getTheme());
        });
    }

    @Test
    void aThemeNameThatNoLongerExistsIsReplacedByTheDefaultWhenTheWindowOpens() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTheme("A Theme That Was Removed");
            settings.setEditorTheme("An Editor Theme That Was Removed");
            settings.setFontFamily("A Font This Machine Lacks");
            rig.show();
            ComboBox<String> theme = rig.control("themeCombo");
            ComboBox<String> editorTheme = rig.control("editorThemeCombo");
            ComboBox<String> family = rig.control("fontFamily");
            assertEquals(Themes.normalize("A Theme That Was Removed"), theme.getValue());
            assertEquals(theme.getValue(), settings.getTheme());
            assertEquals(EditorThemes.normalize("An Editor Theme That Was Removed"), editorTheme.getValue());
            assertEquals(editorTheme.getValue(), settings.getEditorTheme());
            assertEquals("A Font This Machine Lacks", family.getValue(), "the font in force stays selectable");
            assertEquals("A Font This Machine Lacks", family.getItems().get(0));
            assertEquals(List.of(), rig.applied);
        });
    }

    @Test
    void theChoiceControlsWriteTheirSettings() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setPdfPageSize("letter");
            settings.setGitPullMode(GitPullMode.values()[0].id());
            settings.setAutoSave(FileWorkflowCoordinator.AUTOSAVE_OFF);
            rig.show();

            ComboBox<String> pageSize = rig.control("pdfPageSizeCombo");
            assertEquals("letter", pageSize.getValue());
            pageSize.setValue("a4");
            assertEquals("a4", settings.getPdfPageSize());
            assertEquals(tr("settings.pdf.pageSize.a4"), pageSize.getConverter().toString("a4"));
            assertEquals(
                    tr("settings.pdf.pageSize.letter"), pageSize.getConverter().toString("letter"));
            pageSize.setValue(null);
            assertEquals("a4", settings.getPdfPageSize(), "no selection is not a page size");

            ComboBox<GitPullMode> pull = rig.control("gitPullModeCombo");
            assertEquals(GitPullMode.values()[0], pull.getValue());
            GitPullMode last = GitPullMode.values()[GitPullMode.values().length - 1];
            pull.setValue(last);
            assertEquals(last.id(), settings.getGitPullMode());
            assertEquals(GitCoordinator.pullModeLabel(last), pull.getConverter().toString(last));
            assertEquals("", pull.getConverter().toString(null));
            pull.setValue(null);
            assertEquals(last.id(), settings.getGitPullMode());

            ComboBox<String> autoSave = rig.control("autoSaveCombo");
            Spinner<Integer> delay = rig.control("autoSaveDelaySpinner");
            assertEquals(FileWorkflowCoordinator.AUTOSAVE_OFF, autoSave.getValue());
            assertTrue(delay.isDisable(), "a delay means nothing while auto-save is off");
            autoSave.setValue(FileWorkflowCoordinator.AUTOSAVE_DELAY);
            assertEquals(FileWorkflowCoordinator.AUTOSAVE_DELAY, MainController.autoSaveModeOf(settings.getAutoSave()));
            assertFalse(delay.isDisable());
            delay.getValueFactory().setValue(7);
            assertEquals(7000, settings.getAutoSaveDelayMillis());
            autoSave.setValue(FileWorkflowCoordinator.AUTOSAVE_FOCUS);
            assertTrue(delay.isDisable());
            assertEquals(
                    MainController.autoSaveLabel(FileWorkflowCoordinator.AUTOSAVE_FOCUS),
                    autoSave.getConverter().toString(FileWorkflowCoordinator.AUTOSAVE_FOCUS));
            assertEquals("", autoSave.getConverter().toString(null));
            autoSave.setValue(null);
            assertEquals(FileWorkflowCoordinator.AUTOSAVE_FOCUS, MainController.autoSaveModeOf(settings.getAutoSave()));

            ComboBox<String> spell = rig.control("spellLanguageCombo");
            String otherLanguage = spell.getItems().stream()
                    .filter(id -> !id.equals(spell.getValue()))
                    .findFirst()
                    .orElseThrow();
            spell.setValue(otherLanguage);
            assertEquals(otherLanguage, settings.getSpellLanguage());
            assertEquals("", spell.getConverter().toString(null));
            spell.setValue(null);
            assertEquals(otherLanguage, settings.getSpellLanguage());
        });
    }

    @Test
    void aSpinnerWritesItsSettingAndRefusesWhatIsNotANumberInRange() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setGitAutoFetch(false);
            rig.show();
            Spinner<Integer> fill = rig.control("fillColumnSpinner");
            fill.getValueFactory().setValue(72);
            assertEquals(72, settings.getFillColumn());
            int applied = rig.applied.size();

            fill.getEditor().setText("wide");
            fill.getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(72, settings.getFillColumn(), "text that is not a number changes nothing");
            assertEquals("72", fill.getEditor().getText(), "and the field shows the value in force again");
            assertEquals(applied, rig.applied.size());

            fill.getEditor().setText("999999");
            fill.getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(Settings.MAX_FILL_COLUMN, settings.getFillColumn(), "past the range is the bound");
            assertEquals(
                    Integer.toString(Settings.MAX_FILL_COLUMN), fill.getEditor().getText());

            Spinner<Integer> large = rig.control("largeFileThresholdSpinner");
            large.getEditor().setText("-5");
            large.getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(0, settings.getLargeFileThreshold(), "0 is \"never\", and the lowest it goes");
            large.getValueFactory().setValue(25_000);
            assertEquals(25_000, settings.getLargeFileThreshold());

            CheckBox autoFetch = rig.control("gitAutoFetchCheck");
            Spinner<Integer> minutes = rig.control("gitAutoFetchSpinner");
            assertTrue(minutes.isDisable(), "no interval while automatic fetch is off");
            autoFetch.setSelected(true);
            assertTrue(settings.isGitAutoFetch());
            assertFalse(minutes.isDisable());
            minutes.getValueFactory().setValue(45);
            assertEquals(45, settings.getGitAutoFetchMinutes());
            minutes.getEditor().setText("0");
            minutes.getEditor().fireEvent(new javafx.event.ActionEvent());
            assertEquals(1, settings.getGitAutoFetchMinutes());
            autoFetch.setSelected(false);
            assertFalse(settings.isGitAutoFetch());
            assertTrue(minutes.isDisable());
        });
    }

    @Test
    void theSpinnersShowTheSettingsInForce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setFillColumn(101);
            settings.setLargeFileThreshold(3000);
            settings.setGitAutoFetch(true);
            settings.setGitAutoFetchMinutes(17);
            settings.setAutoSave(FileWorkflowCoordinator.AUTOSAVE_DELAY);
            settings.setAutoSaveDelayMillis(12_400);
            settings.setHistoryMaxPerFile(11);
            settings.setHistoryMaxAgeDays(12);
            settings.setHistoryMaxTotalMb(13);
            settings.setLocalHistory(true);
            rig.show();
            assertEquals(101, rig.<Spinner<Integer>>control("fillColumnSpinner").getValue());
            assertEquals(
                    3000,
                    rig.<Spinner<Integer>>control("largeFileThresholdSpinner").getValue());
            assertEquals(
                    17, rig.<Spinner<Integer>>control("gitAutoFetchSpinner").getValue());
            assertFalse(rig.<Spinner<Integer>>control("gitAutoFetchSpinner").isDisable());
            assertTrue(rig.<CheckBox>control("gitAutoFetchCheck").isSelected());
            assertEquals(
                    12, rig.<Spinner<Integer>>control("autoSaveDelaySpinner").getValue(), "whole seconds");
            assertFalse(rig.<Spinner<Integer>>control("autoSaveDelaySpinner").isDisable());
            assertEquals(
                    11,
                    rig.<Spinner<Integer>>control("historyMaxPerFileSpinner").getValue());
            assertEquals(
                    12, rig.<Spinner<Integer>>control("historyMaxAgeSpinner").getValue());
            assertEquals(
                    13, rig.<Spinner<Integer>>control("historyMaxTotalSpinner").getValue());
            assertEquals(List.of(), rig.applied);
        });
    }

    /** With nothing to ask about deleted revisions (no history backend), a limit is written directly. */
    @Test
    void aHistoryLimitIsWrittenDirectlyWhenNothingStandsBetween() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setLocalHistory(true);
            rig.show();
            Spinner<Integer> perFile = rig.control("historyMaxPerFileSpinner");
            Spinner<Integer> age = rig.control("historyMaxAgeSpinner");
            Spinner<Integer> total = rig.control("historyMaxTotalSpinner");
            perFile.getValueFactory().setValue(9);
            age.getValueFactory().setValue(0);
            total.getValueFactory().setValue(4);
            assertEquals(9, settings.getHistoryMaxPerFile());
            assertEquals(0, settings.getHistoryMaxAgeDays());
            assertEquals(4, settings.getHistoryMaxTotalMb());
            assertEquals(3, rig.applied.size());

            CheckBox history = rig.control("localHistoryCheck");
            history.setSelected(false);
            assertFalse(settings.isLocalHistory());
            assertTrue(perFile.isDisable() && age.isDisable() && total.isDisable());
            history.setSelected(true);
            assertFalse(perFile.isDisable() || age.isDisable() || total.isDisable());
        });
    }

    /** A limit the handler declines is not written, and the spinner goes back to the limit in force. */
    @Test
    void aDeclinedHistoryLimitIsNotWrittenAndTheSpinnerGoesBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setLocalHistory(true);
            settings.setHistoryMaxPerFile(50);
            boolean[] accept = {false};
            int[] asked = new int[3];
            rig.window.setHistoryLimits((perFile, ageDays, totalMb, owner, done) -> {
                asked[0] = perFile;
                asked[1] = ageDays;
                asked[2] = totalMb;
                if (accept[0]) {
                    settings.setHistoryMaxPerFile(perFile);
                }
                done.accept(accept[0]);
            });
            rig.show();
            Spinner<Integer> perFile = rig.control("historyMaxPerFileSpinner");

            perFile.getValueFactory().setValue(5);
            assertEquals(5, asked[0]);
            assertEquals(settings.getHistoryMaxAgeDays(), asked[1]);
            assertEquals(settings.getHistoryMaxTotalMb(), asked[2]);
            assertEquals(50, settings.getHistoryMaxPerFile());
            assertEquals(50, perFile.getValue(), "back to the limit in force");
            assertEquals(List.of(), rig.applied);

            accept[0] = true;
            perFile.getValueFactory().setValue(6);
            assertEquals(6, settings.getHistoryMaxPerFile());
            assertEquals(6, perFile.getValue());
            assertEquals(1, rig.applied.size());
        });
    }

    @Test
    void changingTheLanguageIsSavedAndSaysARestartIsNeeded() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setUiLanguage("");
            rig.show();
            ComboBox<String> language = rig.control("languageCombo");
            assertEquals("", language.getValue());
            assertEquals(tr("settings.language.auto"), language.getConverter().toString(""));
            assertEquals(tr("settings.language.auto"), language.getConverter().toString(null));
            assertEquals(
                    com.editora.i18n.Messages.languageName("es"),
                    language.getConverter().toString("es"));

            List<SettingsRig.Shown> dialogs =
                    SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> language.setValue("es"));

            assertEquals("es", settings.getUiLanguage());
            assertEquals(1, dialogs.size());
            assertEquals(tr("dialog.language.title"), dialogs.get(0).title());
            assertEquals(tr("dialog.language.restart"), dialogs.get(0).content());
            assertEquals(List.of(), rig.applied, "the running windows keep their language until the restart");

            assertEquals(
                    List.of(),
                    SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> language.setValue(null)),
                    "no selection asks nothing");
            assertEquals("es", settings.getUiLanguage());
        });
        // The choice is on disk: it is read before any window exists on the next start.
        assertTrue(java.nio.file.Files.readString(rig.config.getSettingsFile()).contains("\"es\""));
    }

    @Test
    void enablingTheMcpServerIsOnlyWrittenOnceTheNoticeIsAccepted() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setMcpSupport(false);
            boolean[] accept = {false};
            int[] asked = {0};
            rig.window.setMcpConfirm(() -> {
                asked[0]++;
                return accept[0];
            });
            rig.show();
            CheckBox mcp = rig.control("mcpCheck");
            assertFalse(mcp.isSelected());
            assertEquals(0, asked[0], "showing the setting asks nothing");

            mcp.setSelected(true);
            assertEquals(1, asked[0]);
            assertFalse(mcp.isSelected(), "declined: the box goes back");
            assertFalse(settings.isMcpSupport());
            assertEquals(List.of(), rig.applied);

            accept[0] = true;
            mcp.setSelected(true);
            assertEquals(2, asked[0]);
            assertTrue(settings.isMcpSupport());
            assertEquals(1, rig.applied.size());

            mcp.setSelected(false);
            assertEquals(2, asked[0], "turning it off needs no notice");
            assertFalse(settings.isMcpSupport());

            // Changed from outside (the mcp.toggle command): shown without asking again.
            settings.setMcpSupport(true);
            rig.window.syncMcpCheck();
            assertTrue(mcp.isSelected());
            assertEquals(2, asked[0]);
            assertEquals(2, rig.applied.size());
        });
    }

    @Test
    void theZenAndExpertSwitchesGoToTheWindowAndNotToTheSettingsFile() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            CheckBox zen = rig.control("zenCheck");
            CheckBox expert = rig.control("expertCheck");
            zen.setSelected(true);
            expert.setSelected(true);
            assertTrue(zen.isSelected() && expert.isSelected(), "the boxes show the modes the window is in");
            zen.setSelected(false);
            assertEquals(List.of(true, false), rig.zen);
            assertEquals(List.of(true), rig.expert);
            assertEquals(List.of(), rig.applied);

            // Changed from outside (a command, another window): shown without being sent back.
            rig.config.getWorkspaceState().setZenMode(true);
            rig.config.getWorkspaceState().setExpertMode(false);
            rig.window.syncFocusModeChecks();
            assertTrue(zen.isSelected());
            assertFalse(expert.isSelected());
            assertEquals(List.of(true, false), rig.zen);
            assertEquals(List.of(true), rig.expert);
        });
    }
}
