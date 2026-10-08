package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.TraversalDirection;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.ai.AiProvider;
import com.editora.config.Settings;
import com.editora.externaltool.ExternalTool;
import com.editora.git.GitPullMode;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open Settings window follows what is changed outside it — by a palette command, another window, or a
 * probe that finishes later — without treating what it then shows as an edit of its own.
 */
@Tag("fx")
class SettingsFollowsFxTest {

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
        rig.close();
    }

    private void everySync() {
        SettingsWindow w = rig.window;
        w.syncAll();
        w.syncKeymap();
        w.syncKeymapCombo();
        w.syncStoreBackedEditors();
        w.syncFileBackedEditors();
        w.syncGitBlameCheck();
        w.syncPluginsCheck();
        w.syncToolStripeCheck();
        w.syncSimpleModeCheck();
        w.syncMarkdownFormatBarCheck();
        w.syncMultiCaretCheck();
        w.syncToolbarCheck();
        w.syncLspCheck();
        w.syncHtmlPreviewCheck();
        w.syncMcpCheck();
        w.syncAgentCheck();
        w.syncAiCheck();
        w.syncLogViewerCheck();
        w.syncAutocompleteChecks();
        w.syncThemes();
        w.syncFocusModeChecks();
        w.syncViewChecks();
        w.syncProjectsCheck();
        w.syncDictionaryList();
        w.syncSpellFileTypes();
        w.syncPersonalDictionaryCheck();
        w.syncTechnicalDictionaryCheck();
        w.syncRipgrepStatus(true);
        w.refreshMacrosList();
        w.refreshDetectionStatus();
    }

    /** Commands run whether or not Settings was ever opened; a window that was never built has nothing to sync. */
    @Test
    void aWindowThatWasNeverOpenedIgnoresEveryRequestToFollow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            everySync();
            assertFalse(rig.stage().isShowing());
            assertNull(rig.control("sidebar"), "nothing was built for it");
            assertEquals(List.of(), rig.applied);
        });
    }

    @Test
    void anOpenWindowShowsWhatACommandChanged() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            // Turn every setting a command can change over, behind the window's back.
            settings.setGitSupport(true);
            settings.setGitBlameInline(!settings.isGitBlameInline());
            GitPullMode pull = GitPullMode.values()[GitPullMode.values().length - 1];
            settings.setGitPullMode(pull.id());
            settings.setGitAutoFetch(true);
            settings.setGitAutoFetchMinutes(33);
            settings.setPluginSupport(!settings.isPluginSupport());
            settings.setShowToolStripe(!settings.isShowToolStripe());
            settings.setSimpleMode(!settings.isSimpleMode());
            settings.setMarkdownFormatBar(!settings.isMarkdownFormatBar());
            settings.setMultiCaret(!settings.isMultiCaret());
            settings.setShowMenuBar(!settings.isShowMenuBar());
            settings.setShowToolbar(!settings.isShowToolbar());
            settings.setLspSupport(!settings.isLspSupport());
            settings.setHtmlPreviewSupport(!settings.isHtmlPreviewSupport());
            settings.setAgentSupport(!settings.isAgentSupport());
            settings.setAgentClient("gemini");
            settings.setAiSupport(!settings.isAiSupport());
            settings.setAiInlineCompletion(!settings.isAiInlineCompletion());
            settings.setAiProvider(AiProvider.LMSTUDIO.id());
            settings.setAiLmStudioModel("local-model");
            settings.setLogViewer(!settings.isLogViewer());
            settings.setTestRunner(!settings.isTestRunner());
            settings.setAutocomplete(false);
            settings.setCompletionDoc(!settings.isCompletionDoc());
            settings.setInlayHintMode("all");
            settings.setLspOnTypeFormatting(!settings.isLspOnTypeFormatting());
            settings.setLspPasteImports(!settings.isLspPasteImports());
            settings.setShowLineNumbers(!settings.isShowLineNumbers());
            settings.setIndentStyle("tab");
            settings.setNotesSupport(false);
            String theme = Themes.names().stream()
                    .filter(n -> !n.equals(settings.getTheme()))
                    .findFirst()
                    .orElseThrow();
            settings.setTheme(theme);
            settings.setEditorTheme(EditorThemes.defaultFor(theme));

            everySync();

            assertEquals(
                    settings.isGitBlameInline(),
                    rig.<CheckBox>control("blameCheck").isSelected());
            assertFalse(rig.<CheckBox>control("blameCheck").isDisable());
            assertEquals(
                    pull, rig.<ComboBox<GitPullMode>>control("gitPullModeCombo").getValue());
            assertTrue(rig.<CheckBox>control("gitAutoFetchCheck").isSelected());
            assertEquals(
                    33, rig.<Spinner<Integer>>control("gitAutoFetchSpinner").getValue());
            assertFalse(rig.<Spinner<Integer>>control("gitAutoFetchSpinner").isDisable());
            assertEquals(
                    settings.isPluginSupport(),
                    rig.<CheckBox>control("pluginCheck").isSelected());
            assertEquals(
                    settings.isShowToolStripe(),
                    rig.<CheckBox>control("toolStripeCheck").isSelected());
            assertEquals(
                    settings.isSimpleMode(),
                    rig.<CheckBox>control("simpleModeCheck").isSelected());
            assertEquals(
                    settings.isMarkdownFormatBar(),
                    rig.<CheckBox>control("markdownFormatBarCheck").isSelected());
            assertEquals(
                    settings.isMultiCaret(),
                    rig.<CheckBox>control("multiCaretCheck").isSelected());
            assertEquals(
                    settings.isShowMenuBar(),
                    rig.<CheckBox>control("menuBarCheck").isSelected());
            assertEquals(
                    settings.isShowToolbar(),
                    rig.<CheckBox>control("toolbarCheck").isSelected());
            assertEquals(
                    settings.isLspSupport(), rig.<CheckBox>control("lspCheck").isSelected());
            assertEquals(
                    settings.isHtmlPreviewSupport(),
                    rig.<CheckBox>control("htmlPreviewCheck").isSelected());
            assertEquals(
                    settings.isAgentSupport(),
                    rig.<CheckBox>control("agentCheck").isSelected());
            assertEquals(
                    "gemini", rig.<ComboBox<String>>control("agentClientCombo").getValue());
            assertEquals(
                    settings.isAiSupport(), rig.<CheckBox>control("aiCheck").isSelected());
            assertEquals(
                    settings.isAiInlineCompletion(),
                    rig.<CheckBox>control("aiInlineCheck").isSelected());
            assertEquals(
                    AiProvider.LMSTUDIO.id(),
                    rig.<ComboBox<String>>control("aiProviderCombo").getValue());
            assertEquals("local-model", rig.<TextField>control("aiModelField").getText());
            assertEquals(
                    settings.isLogViewer(),
                    rig.<CheckBox>control("logViewerCheck").isSelected());
            assertEquals(
                    settings.isTestRunner(),
                    rig.<CheckBox>control("testRunnerCheck").isSelected());
            assertFalse(rig.<CheckBox>control("autocompleteCheck").isSelected());
            assertTrue(rig.<CheckBox>control("autocompleteProseCheck").isDisable(), "the sources follow the master");
            assertEquals(
                    settings.isCompletionDoc(),
                    rig.<CheckBox>control("completionDocCheck").isSelected());
            assertEquals(
                    "all", rig.<ComboBox<String>>control("inlayHintModeCombo").getValue());
            assertEquals(
                    settings.isLspOnTypeFormatting(),
                    rig.<CheckBox>control("onTypeFormattingCheck").isSelected());
            assertEquals(
                    settings.isLspPasteImports(),
                    rig.<CheckBox>control("pasteImportsCheck").isSelected());
            assertEquals(
                    settings.isShowLineNumbers(),
                    rig.<CheckBox>control("lineNumbersCheck").isSelected());
            assertEquals(
                    "tab", rig.<ComboBox<String>>control("indentStyleCombo").getValue());
            assertTrue(rig.<CheckBox>control("noteIndicatorsCheck").isDisable(), "no indicators without notes");
            assertEquals(theme, rig.<ComboBox<String>>control("themeCombo").getValue());
            assertEquals(
                    EditorThemes.normalize(settings.getEditorTheme()),
                    rig.<ComboBox<String>>control("editorThemeCombo").getValue());

            assertEquals(List.of(), rig.applied, "showing what changed elsewhere is not an edit");
            assertEquals(List.of(), rig.zen);
        });
    }

    @Test
    void theChoiceLabelsAreInTheUiLanguage() {
        assertEquals(tr("settings.inlayHintMode.all"), SettingsWindow.inlayHintModeName("ALL"));
        assertEquals(tr("settings.inlayHintMode.literals"), SettingsWindow.inlayHintModeName("literals"));
        assertEquals(tr("settings.inlayHintMode.literals"), SettingsWindow.inlayHintModeName(null));
        assertEquals(tr("settings.indentStyle.space"), SettingsWindow.indentStyleName("space"));
        assertEquals(tr("settings.indentStyle.tab"), SettingsWindow.indentStyleName("tab"));
        assertEquals(tr("settings.indentStyle.detect"), SettingsWindow.indentStyleName("detect"));
        assertEquals(tr("settings.indentStyle.detect"), SettingsWindow.indentStyleName(null));
        assertEquals(tr("settings.indentStyle.detect"), SettingsWindow.indentStyleName("something else"));
        assertEquals("settings-git-found", SettingsWindow.buildToolStatusClass(true));
        assertEquals("settings-git-neutral", SettingsWindow.buildToolStatusClass(false));
    }

    // --- probes ----------------------------------------------------------------------------------

    @Test
    void theSearchPageSaysWhetherRipgrepWasFound() throws Exception {
        List<Consumer<Boolean>> probes = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Label status = rig.control("ripgrepStatusLabel");
            assertEquals(tr("settings.search.checking"), status.getText(), "no probe wired: nothing is claimed");

            rig.window.setRipgrepProbe(probes::add);
            rig.show();
            assertEquals(1, probes.size());
            assertEquals(tr("settings.search.checking"), status.getText());
            probes.get(0).accept(false);
            assertEquals(tr("settings.search.notFound"), status.getText());
            assertTrue(status.getStyleClass().contains("settings-git-missing"));

            TextField command = rig.control("ripgrepCommandField");
            SettingsRig.typeAndEnter(command, "/opt/rg");
            assertEquals("/opt/rg", settings.getRipgrepCommand());
            assertEquals(2, probes.size(), "a new command is probed again");
            probes.get(1).accept(true);
            assertEquals(tr("settings.search.found"), status.getText());
            assertTrue(status.getStyleClass().contains("settings-git-found"));
            assertFalse(status.getStyleClass().contains("settings-git-missing"));

            CheckBox ripgrep = rig.control("ripgrepCheck");
            ripgrep.setSelected(!ripgrep.isSelected());
            assertEquals(ripgrep.isSelected(), settings.isRipgrepSearch());
            assertEquals(3, probes.size());
            CheckBox gitignore = rig.control("searchGitignoreCheck");
            gitignore.setSelected(!gitignore.isSelected());
            assertEquals(gitignore.isSelected(), settings.isSearchRespectGitignore());
        });
    }

    @Test
    void theAiPageShowsTheResultOfTheConnectionCheck() throws Exception {
        List<BiConsumer<Boolean, String>> probes = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            rig.window.setAiConnectionProbe(probes::add);
            rig.show();
        });
        FxTestSupport.drainFx(); // the check is started once the fields are filled in
        FxTestSupport.runOnFx(() -> {
            Label status = rig.control("aiStatusLabel");
            assertEquals(1, probes.size());
            assertEquals(tr("settings.ai.checking"), status.getText());
            probes.get(0).accept(false, "connection refused");
            assertEquals(tr("settings.ai.connectFailed", "connection refused"), status.getText());
            assertTrue(status.getStyleClass().contains("settings-git-missing"));
            probes.get(0).accept(true, "");
            assertEquals(tr("settings.ai.connected"), status.getText());
            assertTrue(status.getStyleClass().contains("settings-git-found"));
        });
    }

    // --- AI providers ----------------------------------------------------------------------------

    @Test
    void eachAiProviderKeepsItsOwnFieldsAcrossASwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setAiEnabled(true);
            settings.setAiProvider(AiProvider.ANTHROPIC.id());
            settings.setAiApiKey("key-anthropic");
            settings.setAiApiKeyLmstudio("key-lmstudio");
            settings.setAiLmStudioModel("lm-model");
            settings.setAiModel("claude-model");
            rig.show();
            ComboBox<String> provider = rig.control("aiProviderCombo");
            TextField endpoint = rig.control("aiEndpointField");
            TextField model = rig.control("aiModelField");
            TextField key = rig.control("aiApiKeyField");
            TextField completion = rig.control("aiCompletionModelField");
            CheckBox inline = rig.control("aiInlineCheck");
            assertEquals(AiProvider.ANTHROPIC.id(), provider.getValue());
            assertEquals("claude-model", model.getText());
            assertEquals("key-anthropic", key.getText());
            assertEquals(AiCoordinator.DEFAULT_MODEL, model.getPromptText());
            assertEquals(tr("settings.ai.apiKeyPrompt"), key.getPromptText());
            assertEquals(AiProvider.ANTHROPIC.defaultEndpoint(), endpoint.getPromptText());

            // Typed but not yet committed: it belongs to the provider it was typed for.
            model.setText("claude-typed");
            key.setText("key-typed");
            provider.setValue(AiProvider.LMSTUDIO.id());
            assertEquals("claude-typed", settings.getAiModel());
            assertEquals("key-typed", settings.getAiApiKey());
            assertEquals(AiProvider.LMSTUDIO.id(), settings.getAiProvider());
            assertEquals("lm-model", model.getText(), "the fields show the new provider's values");
            assertEquals("key-lmstudio", key.getText(), "never the other provider's key");
            assertEquals(tr("settings.ai.localModelPrompt"), model.getPromptText());
            assertEquals(tr("settings.ai.lmstudioCompletionPrompt"), completion.getPromptText());
            assertEquals(tr("settings.ai.localApiKeyPrompt"), key.getPromptText());
            assertEquals(AiProvider.LMSTUDIO.defaultEndpoint(), endpoint.getPromptText());

            SettingsRig.typeAndEnter(endpoint, "http://127.0.0.1:9999/v1/chat/completions");
            SettingsRig.typeAndEnter(model, "lm-other");
            SettingsRig.typeAndEnter(key, "key-lm-2");
            SettingsRig.typeAndEnter(completion, "lm-small");
            assertEquals("http://127.0.0.1:9999/v1/chat/completions", settings.getAiLmStudioEndpoint());
            assertEquals("lm-other", settings.getAiLmStudioModel());
            assertEquals("key-lm-2", settings.getAiApiKeyLmstudio());
            assertEquals("lm-small", settings.getAiLmStudioCompletionModel());
            assertEquals("key-typed", settings.getAiApiKey(), "Anthropic's key is untouched");
            assertEquals("claude-typed", settings.getAiModel());

            // Codex signs in by itself: nothing to configure but the model.
            provider.setValue(AiProvider.CODEX.id());
            assertTrue(endpoint.isDisable() && key.isDisable() && inline.isDisable() && completion.isDisable());
            assertFalse(model.isDisable());
            assertEquals(tr("settings.ai.modelDefault"), model.getPromptText());
            assertEquals(tr("settings.ai.modelDefault"), completion.getPromptText());
            assertEquals("", key.getText());

            provider.setValue(AiProvider.OPENAI.id());
            assertFalse(endpoint.isDisable() || key.isDisable() || inline.isDisable() || completion.isDisable());
            assertEquals(tr("settings.ai.localModelPrompt"), completion.getPromptText());
            assertEquals("key-lm-2", settings.getAiApiKeyLmstudio(), "leaving Codex wrote no key anywhere");

            provider.setValue(null); // no selection is not a provider
            assertEquals(AiProvider.OPENAI.id(), settings.getAiProvider());
            assertEquals(
                    tr("settings.ai.provider.codex"), provider.getConverter().toString("codex"));
            assertEquals("", provider.getConverter().toString(null));

            inline.setSelected(!inline.isSelected());
            assertEquals(inline.isSelected(), settings.isAiInlineCompletion());

            // The master switch gates the two features under it.
            CheckBox master = rig.control("aiMasterCheck");
            CheckBox actions = rig.control("aiCheck");
            CheckBox agent = rig.control("agentCheck");
            master.setSelected(false);
            assertFalse(settings.isAiEnabled());
            assertTrue(actions.isDisable() && agent.isDisable());
            master.setSelected(true);
            assertFalse(actions.isDisable() || agent.isDisable());
            actions.setSelected(!actions.isSelected());
            assertEquals(actions.isSelected(), settings.isAiSupport());
        });
    }

    // --- lists kept in step ----------------------------------------------------------------------

    @Test
    void theExternalToolsPageReReadsAListChangedBehindIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setExternalTools(new ArrayList<>(List.of(
                    new ExternalTool(
                            "Format",
                            "fmt",
                            "",
                            "",
                            ExternalTool.StdinSource.NONE,
                            ExternalTool.OutputTarget.CONSOLE,
                            true),
                    new ExternalTool(
                            "", "x", "", "", ExternalTool.StdinSource.NONE, ExternalTool.OutputTarget.CONSOLE, true))));
            rig.show();
            Region page = rig.page("EXTERNAL_TOOLS");
            ListView<ExternalTool> list = rig.control("externalToolList");
            assertEquals(2, list.getItems().size());
            rig.stage().getScene().getRoot().applyCss();
            rig.stage().getScene().getRoot().layout();
            List<String> shown = SettingsRig.all(list, ListCell.class).stream()
                    .map(ListCell::getText)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertEquals(List.of("Format", tr("settings.externalTool.unnamed")), shown);
            list.getSelectionModel().select(0);
            ExternalTool selected = list.getSelectionModel().getSelectedItem();

            rig.window.syncStoreBackedEditors();
            assertSame(selected, list.getSelectionModel().getSelectedItem(), "nothing changed: the rows are kept");

            // Another window added a tool and changed one.
            List<ExternalTool> changed = new ArrayList<>(settings.getExternalTools());
            changed.set(
                    1,
                    new ExternalTool(
                            "Lint",
                            "lint",
                            "--all",
                            "",
                            ExternalTool.StdinSource.BUFFER,
                            ExternalTool.OutputTarget.CONSOLE,
                            false));
            changed.add(new ExternalTool(
                    "Added elsewhere",
                    "new",
                    "",
                    "",
                    ExternalTool.StdinSource.NONE,
                    ExternalTool.OutputTarget.CONSOLE,
                    true));
            settings.setExternalTools(changed);
            rig.window.syncStoreBackedEditors();

            assertEquals(
                    List.of("Format", "Lint", "Added elsewhere"),
                    list.getItems().stream().map(ExternalTool::getName).toList());
            assertEquals("Format", list.getSelectionModel().getSelectedItem().getName(), "the selection is kept");
            assertFalse(
                    list.getItems().stream().anyMatch(changed::contains),
                    "the page edits copies, not the live settings objects");
            assertTrue(page.isVisible());
            assertEquals(List.of(), rig.applied);
        });
    }

    @Test
    void theRunConfigurationsWindowIsBuiltOnceAndShownAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.window.showRunConfigs(null, rig.owner);
            Object first = rig.control("runConfigurationsWindow");
            Stage stage = FxTestSupport.field(first, "stage");
            try {
                assertTrue(stage.isShowing());
                rig.window.showRunConfigs(null, rig.owner);
                assertSame(first, rig.control("runConfigurationsWindow"));
                assertFalse(rig.stage().isShowing(), "Run Configurations does not open Settings");
            } finally {
                stage.hide();
            }
        });
    }

    // --- body editors ----------------------------------------------------------------------------

    private static KeyEvent ctrl(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }

    private static KeyEvent alt(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, true, false);
    }

    @Test
    void theBodyEditorsMoveAndDeleteWithTheEmacsKeys() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea("hello world\nsecond\n\nlast");
            SettingsWindow.installEmacsKeys(area);

            area.moveTo(3);
            Event.fireEvent(area, ctrl(KeyCode.E));
            assertEquals(11, area.getCaretPosition(), "C-e: end of line");
            Event.fireEvent(area, ctrl(KeyCode.A));
            assertEquals(0, area.getCaretPosition(), "C-a: start of line");
            Event.fireEvent(area, ctrl(KeyCode.B));
            assertEquals(0, area.getCaretPosition(), "C-b at the start stays");
            Event.fireEvent(area, ctrl(KeyCode.F));
            Event.fireEvent(area, ctrl(KeyCode.F));
            assertEquals(2, area.getCaretPosition(), "C-f: forward a character");
            Event.fireEvent(area, ctrl(KeyCode.B));
            assertEquals(1, area.getCaretPosition(), "C-b: back a character");

            Event.fireEvent(area, ctrl(KeyCode.P));
            assertEquals(0, area.getCurrentParagraph(), "C-p on the first line stays");
            area.moveTo(0, 9);
            Event.fireEvent(area, ctrl(KeyCode.N));
            assertEquals(1, area.getCurrentParagraph());
            assertEquals(6, area.getCaretColumn(), "C-n keeps the column where the line is long enough");
            Event.fireEvent(area, ctrl(KeyCode.N));
            assertEquals(2, area.getCurrentParagraph());
            assertEquals(0, area.getCaretColumn());
            Event.fireEvent(area, ctrl(KeyCode.N));
            Event.fireEvent(area, ctrl(KeyCode.N));
            assertEquals(3, area.getCurrentParagraph(), "C-n on the last line stays");
            Event.fireEvent(area, ctrl(KeyCode.P));
            assertEquals(2, area.getCurrentParagraph());

            area.moveTo(0);
            Event.fireEvent(area, alt(KeyCode.F));
            assertEquals(5, area.getCaretPosition(), "M-f: end of the word");
            Event.fireEvent(area, alt(KeyCode.F));
            assertEquals(11, area.getCaretPosition(), "M-f skips the space before the next word");
            Event.fireEvent(area, alt(KeyCode.B));
            assertEquals(6, area.getCaretPosition(), "M-b: start of the word");
            Event.fireEvent(area, alt(KeyCode.B));
            assertEquals(0, area.getCaretPosition());
            Event.fireEvent(area, alt(KeyCode.B));
            assertEquals(0, area.getCaretPosition(), "M-b at the start stays");

            Event.fireEvent(area, ctrl(KeyCode.D));
            assertEquals("ello world\nsecond\n\nlast", area.getText(), "C-d deletes the character under the caret");
            area.moveTo(4);
            Event.fireEvent(area, ctrl(KeyCode.K));
            assertEquals("ello\nsecond\n\nlast", area.getText(), "C-k kills to the end of the line");
            Event.fireEvent(area, ctrl(KeyCode.K));
            assertEquals("ellosecond\n\nlast", area.getText(), "C-k at the end of a line joins the next one");

            area.moveTo(area.getLength());
            String before = area.getText();
            Event.fireEvent(area, ctrl(KeyCode.K));
            Event.fireEvent(area, ctrl(KeyCode.D));
            Event.fireEvent(area, ctrl(KeyCode.F));
            Event.fireEvent(area, alt(KeyCode.F));
            assertEquals(before, area.getText(), "at the end of the text there is nothing to delete");
            assertEquals(area.getLength(), area.getCaretPosition());

            // Keys this does not bind are left to the editor: the text is untouched by them here.
            Event.fireEvent(area, ctrl(KeyCode.Q));
            Event.fireEvent(area, alt(KeyCode.Q));
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, true, true, false, false));
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, true, false, true, false));
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, true, true, false));
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K, false, true, false, true));
            assertEquals(before, area.getText());
        });
    }

    @Test
    void onlyShiftTabAndCtrlTabLeaveABodyEditor() {
        assertEquals(TraversalDirection.PREVIOUS, SettingsWindow.focusEscape(KeyCode.TAB, true, false, false));
        assertEquals(TraversalDirection.NEXT, SettingsWindow.focusEscape(KeyCode.TAB, false, true, false));
        assertEquals(TraversalDirection.PREVIOUS, SettingsWindow.focusEscape(KeyCode.TAB, true, true, false));
        assertNull(SettingsWindow.focusEscape(KeyCode.TAB, false, false, false), "Tab types a tab");
        assertNull(SettingsWindow.focusEscape(KeyCode.TAB, true, false, true), "Alt+Shift+Tab is the system's");
        assertNull(SettingsWindow.focusEscape(KeyCode.ENTER, true, false, false));
    }

    @Test
    void aBodyIsHighlightedForItsLanguageAndLeftPlainForNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea("class A { int x = 1; }");
            SettingsWindow.highlightSnippetBody(area, "java");
            assertTrue(
                    area.getStyleSpans(0, area.getLength()).stream()
                            .anyMatch(span -> !span.getStyle().isEmpty()),
                    "Java source gets token styles");

            for (String plain : new String[] {"global", "", null, "no-such-language"}) {
                SettingsWindow.highlightSnippetBody(area, plain);
                assertTrue(
                        area.getStyleSpans(0, area.getLength()).stream()
                                .allMatch(span -> span.getStyle().isEmpty()),
                        "no styles for " + plain);
                SettingsWindow.highlightSnippetBody(area, "java");
            }

            CodeArea empty = new CodeArea("");
            SettingsWindow.highlightSnippetBody(empty, "global"); // nothing to clear
            assertEquals("", empty.getText());
        });
    }
}
