package com.editora.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markdown settings page's rows for the preview theme, remote images, the pasted-image folder and the
 * table-of-contents levels: each control writes its setting, and each palette command that changes the same
 * setting is shown by the open window.
 */
@Tag("fx")
class MarkdownSettingsPageFxTest {

    private static FxWindowFixture fx;
    private static SettingsWindow window;
    private static CommandRegistry registry;
    private static Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        settings = fx.shared.getSettings();
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        FxTestSupport.drainFx();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
            fx.dispose();
        }
    }

    private static <T> T control(String name) {
        return FxTestSupport.field(window, name);
    }

    @Test
    void thePreviewThemeIsAThreeStateChoice() throws Exception {
        ComboBox<String> theme = control("markdownPreviewThemeCombo");
        assertEquals(java.util.List.of("", "light", "dark"), FxTestSupport.callOnFx(theme::getItems));
        assertEquals(
                tr("markdown.previewTheme.follow"),
                FxTestSupport.callOnFx(() -> theme.getConverter().toString("")));
        assertEquals(
                tr("markdown.previewTheme.dark"),
                FxTestSupport.callOnFx(() -> theme.getConverter().toString("dark")));
        FxTestSupport.runOnFx(() -> theme.setValue("dark"));
        assertEquals("dark", settings.getMarkdownPreviewTheme());
        FxTestSupport.runOnFx(() -> theme.setValue(""));
        assertEquals("", settings.getMarkdownPreviewTheme(), "back to following the app theme");

        // The preview's own toggle (and its command) writes the same setting: the open window shows it.
        FxTestSupport.runOnFx(() -> {
            settings.setMarkdownPreviewTheme("light");
            window.syncAll();
        });
        assertEquals("light", FxTestSupport.callOnFx(theme::getValue));
        FxTestSupport.runOnFx(() -> theme.setValue(""));
    }

    @Test
    void remoteImagesHaveASwitchAndACommand() throws Exception {
        CheckBox remote = control("markdownRemoteImagesCheck");
        assertTrue(FxTestSupport.callOnFx(remote::isSelected), "on by default");
        FxTestSupport.runOnFx(() -> remote.setSelected(false));
        assertFalse(settings.isMarkdownRemoteImages());
        FxTestSupport.runOnFx(() -> registry.run("view.toggleMarkdownRemoteImages"));
        assertTrue(settings.isMarkdownRemoteImages());
        assertTrue(FxTestSupport.callOnFx(remote::isSelected), "the window follows the palette");
    }

    @Test
    void theAssetsFolderIsCommittedOnEnterAndShowsWhatWasKept() throws Exception {
        TextField folder = control("markdownAssetsField");
        assertEquals("assets", FxTestSupport.callOnFx(folder::getText));
        FxTestSupport.runOnFx(() -> {
            folder.setText("docs/img/");
            folder.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("docs/img", settings.getMarkdownAssetsFolder());
        assertEquals("docs/img", FxTestSupport.callOnFx(folder::getText));
        FxTestSupport.runOnFx(() -> {
            folder.setText("../../outside");
            folder.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("assets", settings.getMarkdownAssetsFolder(), "a folder outside the document's is not kept");
        assertEquals("assets", FxTestSupport.callOnFx(folder::getText), "and the field says so");
    }

    @Test
    void theTableOfContentsLevelsStayARange() throws Exception {
        Spinner<Integer> min = control("markdownTocMinSpinner");
        Spinner<Integer> max = control("markdownTocMaxSpinner");
        FxTestSupport.runOnFx(() -> {
            min.getValueFactory().setValue(2);
            max.getValueFactory().setValue(4);
        });
        assertEquals(2, settings.getMarkdownTocMinLevel());
        assertEquals(4, settings.getMarkdownTocMaxLevel());
        FxTestSupport.runOnFx(() -> min.getValueFactory().setValue(5));
        assertEquals(5, settings.getMarkdownTocMinLevel());
        assertEquals(5, settings.getMarkdownTocMaxLevel(), "the other end follows");
        assertEquals(5, (int) FxTestSupport.callOnFx(max::getValue));
        FxTestSupport.runOnFx(() -> max.getValueFactory().setValue(3));
        assertEquals(3, settings.getMarkdownTocMinLevel());
        assertEquals(3, (int) FxTestSupport.callOnFx(min::getValue));
        FxTestSupport.runOnFx(() -> {
            min.getValueFactory().setValue(1);
            max.getValueFactory().setValue(6);
        });
    }

    /** The lint card says what a project's markdownlint config does; the row is absent when there is nothing to say. */
    @Test
    void theLintCardShowsTheProjectConfigNoteWhenThereIsOne() throws Exception {
        javafx.scene.control.Label note = control("markdownLintConfigNoteLabel");
        assertFalse(FxTestSupport.callOnFx(note::isManaged), "no Markdown file in front: nothing to say");
        String[] text = {"2 rules disabled by .markdownlint.json"};
        FxTestSupport.runOnFx(() -> window.setMarkdownLintConfigNote(() -> text[0]));
        assertEquals(text[0], FxTestSupport.callOnFx(note::getText));
        assertTrue(FxTestSupport.callOnFx(note::isManaged));
        // Not observable: it is read again when the window is synced (and when the page is shown).
        text[0] = "";
        FxTestSupport.runOnFx(window::syncAll);
        assertFalse(FxTestSupport.callOnFx(note::isManaged));
        assertEquals("", FxTestSupport.callOnFx(note::getText));
    }

    // --- the rows of the feature-gap pass -------------------------------------------------------------------

    /** The control of the row for setting {@code key}. */
    @SuppressWarnings("unchecked")
    private static <T> T row(String key) {
        T control = (T) FxTestSupport.<java.util.Map<String, ?>>field(window, "settingControls")
                .get(key);
        if (control == null) {
            throw new AssertionError("no row for " + key);
        }
        return control;
    }

    private record Switch(String key, java.util.function.Predicate<Settings> get, String command) {}

    @Test
    void everyOnOffSettingHasARowThatWritesItAndACommandTheRowFollows() throws Exception {
        java.util.List<Switch> switches = java.util.List.of(
                new Switch("typewriterMode", Settings::isTypewriterMode, "view.toggleTypewriterMode"),
                new Switch("statusBarWordCount", Settings::isStatusBarWordCount, "view.toggleStatusBarWordCount"),
                new Switch("markdownScrollSync", Settings::isMarkdownScrollSync, "view.toggleMarkdownScrollSync"),
                new Switch(
                        "markdownHideFrontMatter",
                        Settings::isMarkdownHideFrontMatter,
                        "view.toggleMarkdownFrontMatter"),
                new Switch(
                        "markdownSmartTypography",
                        Settings::isMarkdownSmartTypography,
                        "view.toggleMarkdownSmartTypography"),
                new Switch("markdownTocSkipTitle", Settings::isMarkdownTocSkipTitle, "view.toggleMarkdownTocSkipTitle"),
                new Switch("markdownTocOrdered", Settings::isMarkdownTocOrdered, "view.toggleMarkdownTocOrdered"),
                new Switch(
                        "markdownTocUpdateOnSave", Settings::isMarkdownTocUpdateOnSave, "view.toggleMarkdownTocOnSave"),
                new Switch(
                        "markdownImageAltPrompt",
                        Settings::isMarkdownImageAltPrompt,
                        "view.toggleMarkdownImageAltPrompt"),
                new Switch(
                        "markdownLintFixOnSave", Settings::isMarkdownLintFixOnSave, "view.toggleMarkdownLintFixOnSave"),
                new Switch(
                        "htmlExportEmbedImages", Settings::isHtmlExportEmbedImages, "view.toggleHtmlExportEmbedImages"),
                new Switch(
                        "htmlExportHighlightCode",
                        Settings::isHtmlExportHighlightCode,
                        "view.toggleHtmlExportHighlightCode"));
        assertEquals(
                switches.stream().map(Switch::command).sorted().toList(),
                MarkdownSettingCommands.toggleIds().stream().sorted().toList(),
                "every toggle command is covered here");
        for (Switch sw : switches) {
            CheckBox box = row(sw.key());
            boolean stored = sw.get().test(settings);
            assertEquals(stored, FxTestSupport.callOnFx(box::isSelected), sw.key() + " shows the stored value");
            FxTestSupport.runOnFx(() -> box.setSelected(!stored));
            assertEquals(!stored, sw.get().test(settings), sw.key() + ": the row writes the setting");
            FxTestSupport.runOnFx(() -> registry.run(sw.command()));
            assertEquals(stored, sw.get().test(settings), sw.command() + " flips the same setting");
            assertEquals(stored, FxTestSupport.callOnFx(box::isSelected), sw.key() + ": the row follows the palette");
        }
    }

    @Test
    void theChoiceSizeAndNameRowsWriteWhatIsKept() throws Exception {
        ComboBox<String> view = row("markdownDefaultView");
        assertEquals(Settings.MARKDOWN_VIEW_MODES, FxTestSupport.callOnFx(view::getItems));
        assertEquals(
                tr("settings.markdownDefaultView.split"),
                FxTestSupport.callOnFx(() -> view.getConverter().toString("split")));
        FxTestSupport.runOnFx(() -> view.setValue("preview"));
        assertEquals("preview", settings.getMarkdownDefaultViewMode());
        FxTestSupport.runOnFx(() -> view.setValue("editor"));

        ComboBox<String> links = row("markdownUpdateLinks");
        assertEquals("ask", FxTestSupport.callOnFx(links::getValue));
        FxTestSupport.runOnFx(() -> links.setValue("always"));
        assertEquals("always", settings.getMarkdownUpdateLinksOnRename());
        FxTestSupport.runOnFx(() -> links.setValue("ask"));

        Spinner<Integer> size = row("markdownPreviewFontSize");
        FxTestSupport.runOnFx(() -> size.getValueFactory().setValue(3));
        assertEquals(Settings.MIN_PREVIEW_FONT_SIZE, settings.getMarkdownPreviewFontSize());
        assertEquals(
                Settings.MIN_PREVIEW_FONT_SIZE,
                (int) FxTestSupport.callOnFx(size::getValue),
                "the row shows what is kept");
        FxTestSupport.runOnFx(() -> size.getValueFactory().setValue(0));
        assertEquals(0, settings.getMarkdownPreviewFontSize());

        Spinner<Integer> width = row("markdownPreviewWidth");
        FxTestSupport.runOnFx(() -> width.getValueFactory().setValue(900));
        assertEquals(900, settings.getMarkdownPreviewWidth());
        FxTestSupport.runOnFx(() -> width.getValueFactory().setValue(0));

        TextField name = row("markdownImageName");
        FxTestSupport.runOnFx(() -> {
            name.setText("shot");
            name.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("shot", settings.getMarkdownImageNamePattern());
        FxTestSupport.runOnFx(() -> {
            name.setText("../up");
            name.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("pasted-image", settings.getMarkdownImageNamePattern());
        assertEquals("pasted-image", FxTestSupport.callOnFx(name::getText));
    }

    @Test
    void aLintRuleRowTurnsItsRuleOnOrOffAndSetsItsSeverity() throws Exception {
        javafx.scene.layout.VBox rules = control("markdownLintRulesBox");
        String code = com.editora.markdown.MarkdownLint.RULES.get(0).code();
        javafx.scene.layout.HBox first = FxTestSupport.callOnFx(
                () -> (javafx.scene.layout.HBox) rules.getChildren().get(0));
        CheckBox on = (CheckBox) first.getChildren().get(0);
        @SuppressWarnings("unchecked")
        ComboBox<String> severity =
                (ComboBox<String>) first.getChildren().get(first.getChildren().size() - 1);
        assertEquals(code, severity.getProperties().get("editora.lintSeverity"));
        assertEquals("warning", FxTestSupport.callOnFx(severity::getValue));
        FxTestSupport.runOnFx(() -> severity.setValue("error"));
        assertEquals("error", settings.markdownLintSeverity(code));
        FxTestSupport.runOnFx(() -> on.setSelected(false));
        assertFalse(settings.isMarkdownLintRuleOn(code));
        FxTestSupport.runOnFx(() -> {
            on.setSelected(true);
            severity.setValue("warning");
        });
        assertTrue(settings.isMarkdownLintRuleOn(code));
        assertTrue(settings.getMarkdownLintSeverities().isEmpty());
    }
}
