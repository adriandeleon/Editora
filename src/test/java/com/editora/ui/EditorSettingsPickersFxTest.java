package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette's way of changing a setting: a picker or a prompt in the window, whose answer is written,
 * applied to the open editors, shown in an open Settings window, and reported in the status bar.
 */
@Tag("fx")
class EditorSettingsPickersFxTest {

    @TempDir
    Path dir;

    private AsyncTestScope async;
    private FxWindowFixture fx;
    private MainController controller;
    private EditorSettingsCoordinator editorSettings;
    private Settings settings;
    private SettingsWindow settingsWindow;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        async = new AsyncTestScope();
        fx = async.own(FxWindowFixture.create());
        controller = fx.controller;
        settings = fx.shared.getSettings();
        editorSettings = FxTestSupport.field(controller, "editorSettings");
        settingsWindow = FxTestSupport.field(controller, "settingsWindow");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<Stage>field(settingsWindow, "stage").hide();
                javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT));
            });
        } finally {
            async.close();
        }
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private EditorBuffer addBuffer(String content) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent(content);
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    private <T> T settingsControl(String name) {
        return FxTestSupport.field(settingsWindow, name);
    }

    @Test
    void theTabWidthPickerOpensOnTheWidthInForceAndAppliesTheChoiceEverywhere() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTabSize(4);
            EditorBuffer buffer = addBuffer("a\n");
            settingsWindow.show(FxTestSupport.field(controller, "stage"));

            editorSettings.chooseTabSize();
            assertEquals(List.of("2", "4", "8"), OverlayDriver.choices(controller));
            assertEquals("4", OverlayDriver.preselected(controller));
            OverlayDriver.pick(controller, "8");

            assertFalse(OverlayDriver.showing(controller), "choosing closes the picker");
            assertEquals(8, settings.getTabSize());
            assertEquals(8, (int) FxTestSupport.<Integer>field(buffer, "tabSize"), "the open editor has it");
            assertEquals(tr("status.tabSize", 8), echo());
        });
    }

    @Test
    void theIndentStylePickerDecidesWhatTabInsertsInEveryEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setIndentStyle("detect");
            settings.setTabSize(4);
            EditorBuffer buffer = addBuffer("a\n");
            settingsWindow.show(FxTestSupport.field(controller, "stage"));
            assertNull(FxTestSupport.field(buffer, "indentInsertSpacesOverride"), "detect: the file decides");

            editorSettings.chooseIndentStyle();
            assertEquals(List.of("detect", "space", "tab"), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, "space");
            assertEquals("space", settings.getIndentStyle());
            assertEquals(Boolean.TRUE, FxTestSupport.field(buffer, "indentInsertSpacesOverride"));
            assertEquals(4, (int) FxTestSupport.<Integer>field(buffer, "indentSizeOverride"), "the global tab width");
            assertEquals(
                    tr(
                            "status.settingChanged",
                            editorSettings.titleOf("editor.setIndentStyle"),
                            tr("settings.indentStyle.space")),
                    echo());
            assertEquals(
                    "space",
                    this.<ComboBox<String>>settingsControl("indentStyleCombo").getValue());

            editorSettings.chooseIndentStyle();
            OverlayDriver.pick(controller, "tab");
            assertEquals(Boolean.FALSE, FxTestSupport.field(buffer, "indentInsertSpacesOverride"));

            editorSettings.chooseIndentStyle();
            OverlayDriver.pick(controller, "detect");
            assertNull(FxTestSupport.field(buffer, "indentInsertSpacesOverride"));
        });
    }

    @Test
    void theThemePickersSwitchTheAppThemeWithItsEditorThemeOrTheEditorThemeAlone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settingsWindow.show(FxTestSupport.field(controller, "stage"));
            String app = Themes.names().stream()
                    .filter(n -> !n.equals(settings.getTheme()))
                    .findFirst()
                    .orElseThrow();

            editorSettings.chooseAppTheme();
            assertEquals(Themes.names(), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, app);
            assertEquals(app, settings.getTheme());
            assertEquals(EditorThemes.defaultFor(app), settings.getEditorTheme(), "the editor theme follows");
            assertFalse(settings.isEditorThemeUserSet());
            assertEquals(Themes.stylesheetFor(app), javafx.application.Application.getUserAgentStylesheet());
            assertEquals(tr("status.appTheme", app), echo());
            assertEquals(
                    app, this.<ComboBox<String>>settingsControl("themeCombo").getValue());

            String editor = EditorThemes.names().stream()
                    .filter(n -> !n.equals(settings.getEditorTheme()))
                    .findFirst()
                    .orElseThrow();
            editorSettings.chooseEditorTheme();
            assertEquals(EditorThemes.names(), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, editor);
            assertEquals(editor, settings.getEditorTheme());
            assertTrue(settings.isEditorThemeUserSet(), "chosen: it no longer follows the app theme");
            assertEquals(app, settings.getTheme());
            assertEquals(tr("status.editorTheme", editor), echo());
            assertEquals(
                    editor,
                    this.<ComboBox<String>>settingsControl("editorThemeCombo").getValue());
        });
    }

    @Test
    void theKeymapPickerSwitchesTheLiveKeymap() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String before = settings.getKeymap();
            String other = com.editora.command.KeymapManager.AVAILABLE.keySet().stream()
                    .filter(id -> !id.equals(before))
                    .findFirst()
                    .orElseThrow();
            settingsWindow.show(FxTestSupport.field(controller, "stage"));

            editorSettings.chooseKeymap();
            OverlayDriver.pick(controller, other);
            assertEquals(other, settings.getKeymap());
            assertEquals(tr("status.keymap.changed", com.editora.command.KeymapManager.displayName(other)), echo());
            assertEquals(
                    other, this.<ComboBox<String>>settingsControl("keymapCombo").getValue());

            controller.setStatus("unchanged");
            editorSettings.applyKeymap(null); // the picker was dismissed
            assertEquals(other, settings.getKeymap());
            assertEquals("unchanged", echo());
        });
    }

    @Test
    void theLanguagePickerOverridesTheActiveBuffersGrammar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            editorSettings.chooseLanguage(); // no buffer: nothing to set a language for
            EditorBuffer buffer = addBuffer("class A {}\n");
            editorSettings.chooseLanguage();
            List<String> offered = OverlayDriver.choices(controller);
            assertTrue(offered.contains("java") && offered.size() > 20, "every grammar is offered");
            assertEquals(buffer.getLanguage(), OverlayDriver.preselected(controller));
            OverlayDriver.pick(controller, "java");
            assertEquals("java", buffer.getLanguage());
            assertEquals(tr("status.language", "java"), echo());
        });
    }

    @Test
    void theRemainingChoicePickersWriteTheirSettings() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settingsWindow.show(FxTestSupport.field(controller, "stage"));

            editorSettings.chooseInlayHintMode();
            assertEquals(List.of("literals", "all"), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, "all");
            assertEquals("all", settings.getInlayHintMode());
            assertEquals(
                    tr(
                            "status.settingChanged",
                            editorSettings.titleOf("lsp.setInlayHintMode"),
                            tr("settings.inlayHintMode.all")),
                    echo());
            assertEquals(
                    "all",
                    this.<ComboBox<String>>settingsControl("inlayHintModeCombo").getValue());

            editorSettings.choosePdfPageSize();
            OverlayDriver.pick(controller, "a4");
            assertEquals("a4", settings.getPdfPageSize());
            assertEquals(
                    "a4",
                    this.<ComboBox<String>>settingsControl("pdfPageSizeCombo").getValue());

            String font = SettingsWindow.fontFamilyChoices().stream()
                    .filter(f -> !f.equals(settings.getFontFamily()))
                    .findFirst()
                    .orElseThrow();
            editorSettings.chooseFont();
            OverlayDriver.pick(controller, font);
            assertEquals(font, settings.getFontFamily());
            assertEquals(
                    font, this.<ComboBox<String>>settingsControl("fontFamily").getValue());
            assertEquals(tr("status.settingChanged", editorSettings.titleOf("appearance.setFont"), font), echo());

            editorSettings.chooseUiLanguage();
            List<String> languages = OverlayDriver.choices(controller);
            assertEquals("", languages.get(0), "Automatic comes first");
            OverlayDriver.pick(controller, "es");
            assertEquals("es", settings.getUiLanguage());
            assertEquals(tr("dialog.language.restart"), echo());
            assertEquals(
                    "es",
                    this.<ComboBox<String>>settingsControl("languageCombo").getValue());
            settings.setUiLanguage("");
        });
    }

    @Test
    void theLineEndingPickerConvertsTheActiveBuffer() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
        FxTestSupport.runOnFx(() -> {
            assertEquals("LF", buffer.getLineEnding());
            editorSettings.chooseLineEndings();
            assertEquals(List.of("LF", "CRLF"), OverlayDriver.choices(controller));
            assertEquals("LF", OverlayDriver.preselected(controller));
            OverlayDriver.pick(controller, "CRLF");
            assertEquals("CRLF", buffer.getLineEnding());
            assertEquals(tr("status.lineEndingsSet", "CRLF"), echo());

            // A read-only buffer cannot be converted: no picker is offered for it.
            buffer.setViewMode(true);
            editorSettings.chooseLineEndings();
            assertFalse(OverlayDriver.showing(controller));
        });
    }

    @Test
    void aLineEndingFixedByEditorConfigIsNotConvertedByHand() throws Exception {
        Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nend_of_line = lf\n");
        Path file = Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
        FxTestSupport.runOnFx(() -> {
            assertTrue(buffer.isLineEndingForced(), "the .editorconfig rule is in force");
            editorSettings.chooseLineEndings();
            OverlayDriver.pick(controller, "CRLF");
            assertEquals("LF", buffer.getLineEnding(), "a conversion could never reach the disk");
            assertEquals(tr("status.lineEndingsEditorConfig", "LF"), echo());

            // The status-bar indicator opens the file that decides it.
            editorSettings.openActiveEditorConfig();
        });
        EditorBuffer config = WindowMcpBridgeFxTest.awaitLoaded(async, controller, dir.resolve(".editorconfig"));
        assertEquals("root = true\n[*]\nend_of_line = lf\n", FxTestSupport.callOnFx(config::getContent));
    }

    @Test
    void openingTheEditorConfigOfABufferThatHasNoneSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            editorSettings.openActiveEditorConfig();
            assertEquals(tr("status.editorConfig.none"), echo(), "no buffer at all");
            controller.setStatus("");
            addBuffer("untitled\n");
            editorSettings.openActiveEditorConfig();
            assertEquals(tr("status.editorConfig.none"), echo(), "an unsaved buffer has no folder to look in");
        });
    }

    @Test
    void aTodoPartColourIsPickedThenTypedAndMustBeAWebColour() throws Exception {
        FxTestSupport.runOnFx(() -> {
            editorSettings.chooseTodoPartColor();
            assertEquals(List.of("tag", "critical", "high", "medium", "low"), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, "high");
            assertEquals(
                    settings.getTodoPriorityHighColor(), OverlayDriver.promptText(controller), "the colour in force");
            OverlayDriver.answer(controller, " #A1b2C3 ");
            assertEquals("#A1b2C3", settings.getTodoPriorityHighColor());
            assertEquals(tr("status.settingChanged", tr("settings.todo.part.high"), "#A1b2C3"), echo());

            editorSettings.chooseTodoPartColor();
            OverlayDriver.pick(controller, "tag");
            String before = settings.getTodoTagColor();
            OverlayDriver.answer(controller, "red");
            assertEquals(before, settings.getTodoTagColor(), "a colour name is not a web colour");
            assertEquals(tr("status.todo.badColor"), echo());

            editorSettings.applyTodoPartColor("tag", "#010203");
            editorSettings.applyTodoPartColor("critical", "#040506");
            editorSettings.applyTodoPartColor("medium", "#070809");
            editorSettings.applyTodoPartColor("low", "#0A0B0C");
            assertEquals("#010203", settings.getTodoTagColor());
            assertEquals("#040506", settings.getTodoPriorityCriticalColor());
            assertEquals("#070809", settings.getTodoPriorityMediumColor());
            assertEquals("#0A0B0C", settings.getTodoPriorityLowColor());

            controller.setStatus("unchanged");
            editorSettings.applyTodoPartColor("a-part-added-later", "#111111");
            assertEquals("unchanged", echo());
            editorSettings.applyTodoPartColor("tag", null);
            assertEquals(tr("status.todo.badColor"), echo());
            assertEquals("#010203", settings.getTodoTagColor());
        });
    }

    @Test
    void aNumberPromptClampsToItsRangeAndRefusesWhatIsNotANumber() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settingsWindow.show(FxTestSupport.field(controller, "stage"));
            String command = "buffer.setTabSize";
            int[] applied = {0};

            editorSettings.promptIntSetting(
                    command, settings::getFillColumn, 20, 200, settings::setFillColumn, () -> applied[0]++);
            assertEquals(Integer.toString(settings.getFillColumn()), OverlayDriver.promptText(controller));
            OverlayDriver.answer(controller, " 96 ");
            assertEquals(96, settings.getFillColumn());
            assertEquals(1, applied[0]);
            assertEquals(tr("status.settingChanged", editorSettings.titleOf(command), "96"), echo());
            assertEquals(
                    96,
                    this.<Spinner<Integer>>settingsControl("fillColumnSpinner").getValue());

            editorSettings.promptIntSetting(command, settings::getFillColumn, 20, 200, settings::setFillColumn, null);
            OverlayDriver.answer(controller, "5000");
            assertEquals(200, settings.getFillColumn(), "above the range is the top of it");
            editorSettings.promptIntSetting(command, settings::getFillColumn, 20, 200, settings::setFillColumn, null);
            OverlayDriver.answer(controller, "-3");
            assertEquals(20, settings.getFillColumn());

            editorSettings.promptIntSetting(
                    command, settings::getFillColumn, 20, 200, settings::setFillColumn, () -> applied[0]++);
            OverlayDriver.answer(controller, "wide");
            assertEquals(20, settings.getFillColumn());
            assertEquals(tr("status.setting.invalidNumber", "wide"), echo());
            assertEquals(1, applied[0], "nothing is applied for a value that was refused");
        });
    }

    @Test
    void aTextPromptStoresTheTrimmedAnswerAndAToggleFlipsAndReports() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settingsWindow.show(FxTestSupport.field(controller, "stage"));
            String command = "buffer.setTabSize";
            int[] applied = {0};

            editorSettings.promptStringSetting(command, settings::getGitPath, settings::setGitPath, () -> applied[0]++);
            assertEquals(settings.getGitPath(), OverlayDriver.promptText(controller));
            OverlayDriver.answer(controller, "  /opt/git  ");
            assertEquals("/opt/git", settings.getGitPath());
            assertEquals(1, applied[0]);
            assertEquals(tr("status.settingChanged", editorSettings.titleOf(command), "/opt/git"), echo());

            editorSettings.promptStringSetting(command, settings::getGitPath, settings::setGitPath, null);
            OverlayDriver.answer(controller, "git");
            assertEquals("git", settings.getGitPath());

            boolean before = settings.isShowMinimap();
            editorSettings.toggleSetting(
                    command, settings::isShowMinimap, settings::setShowMinimap, () -> applied[0]++);
            assertEquals(!before, settings.isShowMinimap());
            assertEquals(2, applied[0]);
            assertEquals(
                    tr(
                            "status.settingToggled",
                            editorSettings.titleOf(command),
                            tr(before ? "common.off" : "common.on")),
                    echo());
            editorSettings.toggleSetting(command, settings::isShowMinimap, settings::setShowMinimap, null);
            assertEquals(before, settings.isShowMinimap());

            assertEquals("no.such.command", editorSettings.commandTitle("no.such.command"));
        });
    }

    @Test
    void largeFileModeIsSwitchedForTheActiveBufferOnly() throws Exception {
        FxTestSupport.runOnFx(() -> {
            controller.setStatus("unchanged");
            editorSettings.toggleLargeFileMode(); // no buffer
            assertEquals("unchanged", echo());

            EditorBuffer buffer = addBuffer("text\n");
            assertFalse(buffer.isHeavyFile());
            editorSettings.toggleLargeFileMode();
            assertTrue(buffer.isHeavyFile());
            assertEquals(tr("status.largeFileMode.on"), echo());
            editorSettings.toggleLargeFileMode();
            assertFalse(buffer.isHeavyFile());
            assertEquals(tr("status.largeFileMode.off"), echo());
        });
    }
}
