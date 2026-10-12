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
                tr("settings.markdownPreviewTheme.follow"),
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
}
