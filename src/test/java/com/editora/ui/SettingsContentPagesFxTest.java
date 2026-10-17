package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import com.editora.todo.TodoPattern;
import com.editora.todo.TodoPatterns;
import com.editora.todo.TodoScanner;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the General and Editor pages of Settings show and keep (Appearance, Interface, Toolbar, Workspace, Tool
 * Windows, Editor, Code Completion, TODO, Spell Check, Search), checked in a real Settings window.
 */
@Tag("fx")
class SettingsContentPagesFxTest {

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

    private ColorPicker partPicker(String field) {
        return FxTestSupport.field(rig.<Object>control("todoSettings"), field);
    }

    @Test
    void theTodoPartColorPickersShowTheStoredColorsAfterAResetAndAfterAChangeMadeElsewhere() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("TODO");
            ColorPicker tag = partPicker("tagPicker");
            tag.setValue(Color.RED);
            assertEquals("#FF0000", settings.getTodoTagColor());

            // Reset to Defaults goes through commitReset → load(): the picker must follow the stored default.
            Settings.resetToDefaults(settings);
            FxTestSupport.call(rig.window, "commitReset", new Class<?>[] {});
            assertEquals(TodoSettingsPane.parseColor(settings.getTodoTagColor()), tag.getValue());

            // Changed while the window was closed (a palette command, another window): shown on reopen.
            rig.stage().hide();
            settings.setTodoPriorityHighColor("#00FF00");
            rig.show();
            assertEquals(Color.web("#00FF00"), partPicker("highPicker").getValue());
            assertEquals("#00FF00", settings.getTodoPriorityHighColor(), "showing the stored color is not an edit");
        });
    }

    /** A toolbar backend that keeps what it is given. */
    private static final class Toolbar implements SettingsWindow.ToolbarActions {
        List<String> layout;

        Toolbar(List<String> layout) {
            this.layout = new ArrayList<>(layout);
        }

        @Override
        public List<String> current() {
            return List.copyOf(layout);
        }

        @Override
        public void apply(List<String> next) {
            layout = new ArrayList<>(next);
        }

        @Override
        public void restoreDefault() {}
    }

    private static KeyEvent pressed(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void theToolbarEditorWorksFromTheKeyboardAndOnlyOffersButtonsThatDoSomething() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> ids =
                    ToolbarCatalog.items().stream().map(ToolbarCatalog.Item::id).toList();
            Toolbar toolbar = new Toolbar(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1)));
            rig.window.setToolbarActions(toolbar);
            rig.show();
            Region page = rig.page("TOOLBAR");
            Object pane = rig.control("toolbarSettings");
            ListView<String> available = FxTestSupport.field(pane, "available");
            ListView<String> current = FxTestSupport.field(pane, "current");
            Button add = (Button) SettingsRig.button(page, tr("settings.toolbar.add"));
            Button remove = (Button) SettingsRig.button(page, tr("settings.toolbar.remove"));
            Button separator = (Button) SettingsRig.button(page, tr("settings.toolbar.addSeparator"));

            assertTrue(add.isDisabled(), "nothing chosen to add");
            assertTrue(remove.isDisabled(), "nothing chosen to remove");

            // Enter on an available item adds it after the selected bar item, and the new item is selected.
            current.getSelectionModel().select(2);
            String second = available.getItems().get(1);
            available.getSelectionModel().select(0);
            String first = available.getSelectionModel().getSelectedItem();
            available.fireEvent(pressed(KeyCode.ENTER));
            assertEquals(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1), first), toolbar.layout);
            assertEquals(first, current.getSelectionModel().getSelectedItem(), "the added item is selected");
            assertEquals(second, available.getSelectionModel().getSelectedItem(), "Add can be pressed again");

            // A separator beside a separator would be dropped by the bar: the button says so by being disabled.
            current.getSelectionModel().select(1);
            assertTrue(separator.isDisabled());
            current.getSelectionModel().select(0);
            assertTrue(separator.isDisabled(), "the next item is already a separator");
            current.getSelectionModel().select(3);
            assertFalse(separator.isDisabled());

            // Delete on a bar item removes it.
            current.getSelectionModel().select(3);
            current.fireEvent(pressed(KeyCode.DELETE));
            assertEquals(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1)), toolbar.layout);
            assertTrue(available.getItems().contains(first));
        });
    }

    @Test
    void theToolbarPageHasTheShowToolbarSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("TOOLBAR");
            SettingSwitch show = SettingsRig.all(page, SettingSwitch.class).getFirst();
            assertEquals(tr("settings.showToolbar"), show.getAccessibleText());
            boolean was = settings.isShowToolbar();
            show.setSelected(!was);
            assertEquals(!was, settings.isShowToolbar());
        });
    }

    private static boolean flagged(TextField field) {
        return field.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER);
    }

    @Test
    void rulerTextThatCannotBeReadIsFlaggedAndKeptUnsavedInsteadOfQuietlyReverted() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.window.showDoctorTarget("editor", rig.owner); // opens the Editor page
            Map<String, javafx.scene.control.Control> controls = rig.control("settingControls");
            TextField rulers = (TextField) controls.get("rulers");
            List<Integer> before = List.copyOf(settings.getRulers());

            SettingsRig.typeAndEnter(rulers, "80; 120");
            assertEquals(before, settings.getRulers(), "nothing saved");
            assertEquals("80; 120", rulers.getText(), "the typed text stays to be fixed");
            assertTrue(flagged(rulers));
            assertTrue(
                    rulers.getTooltip().getText().contains(";"),
                    rulers.getTooltip().getText());

            SettingsRig.typeAndEnter(rulers, "80, 120");
            assertEquals(List.of(80, 120), settings.getRulers());
            assertFalse(flagged(rulers));
        });
    }

    @Test
    void aLanguageRulerNameNoFileHasIsRefusedAndAnyCaseOfAKnownOneIsKeptLowerCased() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.window.showDoctorTarget("editor", rig.owner); // opens the Editor page
            Map<String, javafx.scene.control.Control> controls = rig.control("settingControls");
            TextField perLanguage = (TextField) controls.get("languageRulers");

            SettingsRig.typeAndEnter(perLanguage, "java: 100; bash: 61");
            assertEquals(Map.of(), settings.getLanguageRulers(), "nothing saved");
            assertTrue(flagged(perLanguage));
            assertTrue(perLanguage.getTooltip().getText().contains("bash"));

            SettingsRig.typeAndEnter(perLanguage, "Java: 100; Python: 79");
            assertEquals(Map.of("java", List.of(100), "python", List.of(79)), settings.getLanguageRulers());
            assertFalse(flagged(perLanguage));
            assertEquals("java: 100; python: 79", perLanguage.getText());
        });
    }

    private HBox todoRow(int i) {
        VBox rows = FxTestSupport.field(rig.<Object>control("todoSettings"), "patternsBox");
        return (HBox) rows.getChildren().get(i);
    }

    @Test
    void anAddedTodoPatternMatchesNothingUntilNamedAndThenMatchesItsName() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(new TodoPattern("TODO", "\\bTODO\\b", "", true, true))));
            rig.show();
            Region page = rig.page("TODO");
            SettingsRig.button(page, tr("settings.todo.add")).fire();
            String line = "// TODO fix and BUG here";
            // Every TODO used to be matched twice: once by TODO and once by the new row's \\bTODO\\b.
            assertEquals(
                    1,
                    TodoScanner.scan(line, TodoPatterns.compile(settings.getTodoPatterns()))
                            .size());

            TextField name = (TextField) todoRow(1).getChildren().get(1);
            TextField regex = (TextField) todoRow(1).getChildren().get(2);
            SettingsRig.typeAndEnter(name, "BUG");
            assertEquals("\\bBUG\\b", regex.getText(), "the regex follows the name it was empty for");
            assertEquals("\\bBUG\\b", settings.getTodoPatterns().get(1).getPattern());
            assertEquals(
                    2,
                    TodoScanner.scan(line, TodoPatterns.compile(settings.getTodoPatterns()))
                            .size());

            SettingsRig.typeAndEnter(name, "FIX");
            assertEquals("\\bFIX\\b", settings.getTodoPatterns().get(1).getPattern(), "still its own default");
            SettingsRig.typeAndEnter(regex, "\\bFIX(ME)?\\b");
            SettingsRig.typeAndEnter(name, "FIXES");
            assertEquals("\\bFIX(ME)?\\b", settings.getTodoPatterns().get(1).getPattern(), "a hand-written one stays");
        });
    }

    @Test
    void aTodoPatternWithNoNameOrNothingToMatchIsFlaggedAndItsDeleteIsConfirmed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(new TodoPattern("HACK", "\\bHACK\\b", "", true, true))));
            rig.show();
            Region page = rig.page("TODO");
            TextField name = (TextField) todoRow(0).getChildren().get(1);
            TextField regex = (TextField) todoRow(0).getChildren().get(2);
            CheckBox matchCase = (CheckBox) todoRow(0).getChildren().get(4);
            assertEquals(tr("settings.todo.caseTip"), matchCase.getTooltip().getText(), "\"Aa\" says what it is");

            SettingsRig.typeAndEnter(name, "  ");
            assertEquals("HACK", settings.getTodoPatterns().get(0).getName(), "a blank name is not stored");
            assertTrue(flagged(name));
            SettingsRig.typeAndEnter(name, "HACK");
            assertFalse(flagged(name));
            SettingsRig.typeAndEnter(regex, "");
            assertEquals("\\bHACK\\b", settings.getTodoPatterns().get(0).getPattern());
            assertEquals("\\bHACK\\b", regex.getText(), "an emptied regex goes back to the name's own");

            Button remove = (Button) todoRow(0).getChildren().get(5);
            List<SettingsRig.Shown> asked = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, remove::fire);
            assertEquals(1, asked.size());
            assertTrue(asked.get(0).content().contains("HACK"), asked.get(0).content());
            assertEquals(1, settings.getTodoPatterns().size(), "Cancel keeps it");
            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, remove::fire);
            assertEquals(List.of(), settings.getTodoPatterns());
            SettingsRig.button(page, tr("settings.todo.add")).fire();
        });
    }

    /** The row nodes under a side's card on the Tool Windows page, top to bottom. */
    private List<javafx.scene.Node> sideCard(ToolWindow.Side side) {
        Map<ToolWindow.Side, Object> cards = rig.control("toolWindowCards");
        VBox body = (VBox) FxTestSupport.call(cards.get(side), "body", new Class<?>[] {});
        return List.copyOf(body.getChildren());
    }

    private ToolWindowsSettingsPane.Row twRow(String id) {
        ToolWindowsSettingsPane pane = rig.control("toolWindowSettings");
        return pane.row(id);
    }

    @Test
    void toolWindowRowsSitUnderTheirSideInStripeOrderAndFollowEveryMove() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setGitSupport(true);
            rig.show();
            rig.page("TOOL_WINDOWS");
            ToolWindowManager tools = rig.toolWindows;
            ToolWindowsSettingsPane.Row commit = twRow("commit");
            tools.setSide(commit.window(), ToolWindow.Side.RIGHT);
            tools.setVisible(commit.window(), true);
            rig.stage().hide();
            rig.show();
            List<ToolWindow> peers = tools.stripeOrder(ToolWindow.Side.RIGHT);
            int at = peers.indexOf(commit.window());
            assertTrue(at >= 0 && at < peers.size() - 1, "Commit has a shown neighbour after it");
            ToolWindow next = peers.get(at + 1);
            int rowAt = sideCard(ToolWindow.Side.RIGHT).indexOf(commit.node());
            int nextAt =
                    sideCard(ToolWindow.Side.RIGHT).indexOf(twRow(next.getId()).node());
            assertTrue(rowAt >= 0 && rowAt < nextAt, "the page shows the stripe's order");

            commit.down().fire();
            assertEquals(
                    at + 1,
                    tools.stripeOrder(ToolWindow.Side.RIGHT).indexOf(commit.window()),
                    "it passed a shown window");
            assertTrue(
                    sideCard(ToolWindow.Side.RIGHT).indexOf(commit.node())
                            > sideCard(ToolWindow.Side.RIGHT)
                                    .indexOf(twRow(next.getId()).node()),
                    "and the page shows the new order");

            // A hidden window keeps a side that can be chosen; its row moves to that side's card.
            commit.show().setSelected(false);
            assertFalse(commit.side().isDisable());
            commit.side().setValue(ToolWindow.Side.BOTTOM);
            assertEquals(ToolWindow.Side.BOTTOM, tools.currentSide(commit.window()));
            assertTrue(sideCard(ToolWindow.Side.BOTTOM).contains(commit.node()));
            assertFalse(sideCard(ToolWindow.Side.RIGHT).contains(commit.node()));
        });
    }

    @Test
    void restoreDefaultLayoutPutsMovedAndHiddenWindowsBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            rig.show();
            Region page = rig.page("TOOL_WINDOWS");
            ToolWindowManager tools = rig.toolWindows;
            ToolWindow notes = twRow("notes").window();
            ToolWindow.Side home = notes.getSide();
            ToolWindow.Side away = home == ToolWindow.Side.BOTTOM ? ToolWindow.Side.LEFT : ToolWindow.Side.BOTTOM;
            twRow("notes").side().setValue(away);
            twRow("structure").show().setSelected(false);
            assertEquals(away, tools.currentSide(notes));
            assertFalse(tools.isVisible(twRow("structure").window()));

            SettingsRig.button(page, tr("settings.toolbar.restoreDefault")).fire();
            assertEquals(home, tools.currentSide(notes));
            assertTrue(tools.isVisible(twRow("structure").window()));
            assertEquals(home, twRow("notes").side().getValue(), "the page shows it");
            assertTrue(twRow("structure").show().isSelected());
            assertTrue(sideCard(home).contains(twRow("notes").node()));
            assertTrue(SettingsRig.texts(page).contains(tr("settings.toolWindows.layoutNote")));
        });
    }

    @Test
    void automaticPutsAPickedEditorThemeBackToFollowingTheAppTheme() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setEditorThemeUserSet(false);
            rig.show();
            rig.page("APPEARANCE");
            ComboBox<String> theme = rig.control("themeCombo");
            ComboBox<String> editorTheme = rig.control("editorThemeCombo");
            assertEquals("", editorTheme.getItems().getFirst(), "Automatic is the first choice");
            assertEquals("", editorTheme.getValue());
            assertEquals(
                    tr("settings.editorTheme.auto"), editorTheme.getButtonCell().getText());

            editorTheme.setValue("Dracula");
            theme.setValue("Nord Light");
            assertEquals("Dracula", settings.getEditorTheme(), "a picked theme stays");

            editorTheme.setValue("");
            assertFalse(settings.isEditorThemeUserSet());
            assertEquals(EditorThemes.defaultFor("Nord Light"), settings.getEditorTheme());
            theme.setValue("Editora Dark");
            assertEquals(EditorThemes.defaultFor("Editora Dark"), settings.getEditorTheme(), "and follows again");
            assertEquals("", editorTheme.getValue(), "still showing Automatic");
        });
    }

    /**
     * The page of a category, by name. ("EDITOR" is also a sidebar group, which {@link SettingsRig#page} finds
     * first.)
     */
    private Region categoryPage(String name) {
        Map<Object, Region> pages = rig.control("pages");
        return pages.entrySet().stream()
                .filter(e -> ((Enum<?>) e.getKey()).name().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static List<String> cardTitles(Region page) {
        return SettingsRig.all(page, javafx.scene.control.Label.class).stream()
                .filter(l -> l.getStyleClass().contains("settings-card-title"))
                .map(javafx.scene.control.Label::getText)
                .toList();
    }

    @Test
    void theEditorPageGroupsDisplayEditingAndClipboardAndPutsSavingRightAfterIndentation() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            List<String> titles = cardTitles(categoryPage("EDITOR"));
            assertEquals(
                    List.of(
                            tr("settings.section.display"),
                            tr("settings.section.editing"),
                            tr("settings.section.clipboard"),
                            tr("settings.section.indentation"),
                            tr("settings.section.saving")),
                    titles.subList(0, 5));
            List<String> texts = SettingsRig.texts(categoryPage("EDITOR"));
            assertTrue(
                    texts.indexOf(tr("settings.multiCaret")) > texts.indexOf(tr("settings.section.editing")),
                    "multiple cursors is editing, not display");
            assertTrue(texts.indexOf(tr("settings.copyLineWhenNoSelection"))
                    > texts.indexOf(tr("settings.section.clipboard")));
            assertTrue(texts.contains(tr("settings.adminSave.note")), "the admin-save note is its row's description");

            assertTrue(
                    cardTitles(rig.page("COMPLETION")).contains(tr("settings.section.lspFeatures")),
                    "the language-server card says what it is");
        });
    }

    /** The on/off switch titled {@code title} on {@code page}. */
    private static SettingSwitch switchNamed(Region page, String title) {
        return SettingsRig.all(page, SettingSwitch.class).stream()
                .filter(sw -> title.equals(sw.getAccessibleText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no switch " + title));
    }

    private static boolean noteShown(Region page, String text) {
        return SettingsRig.all(page, javafx.scene.control.Label.class).stream()
                .anyMatch(l -> text.equals(l.getText()) && l.isVisible() && l.isManaged());
    }

    @Test
    void everyLanguageServerOnlyRowIsGreyedOutWithTheReasonWhileLspIsOff() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setLspSupport(false);
            rig.show();
            Region page = rig.page("COMPLETION");
            String why = tr("settings.dependsOn", tr("settings.enableLsp"));
            for (String row :
                    List.of("settings.lspPasteImports", "settings.lspSmartSemicolon", "settings.inlayHints")) {
                assertTrue(switchNamed(page, tr(row)).isDisabled(), row);
            }
            assertTrue(noteShown(page, why));
            rig.<CheckBox>control("lspCheck").setSelected(true);
            assertFalse(switchNamed(page, tr("settings.lspPasteImports")).isDisabled());
            assertFalse(switchNamed(page, tr("settings.lspSmartSemicolon")).isDisabled());
            assertFalse(noteShown(page, why));
        });
    }

    @Test
    void rowsThatOnlyMatterWhileAnotherSwitchIsOnSaySo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region editor = categoryPage("EDITOR");
            Map<String, javafx.scene.control.Control> controls = rig.control("settingControls");

            rig.<CheckBox>control("columnRulerCheck").setSelected(false);
            assertTrue(controls.get("rulers").isDisabled(), "no rulers to place");
            assertTrue(controls.get("languageRulers").isDisabled());
            assertTrue(noteShown(editor, tr("settings.dependsOn", tr("settings.showRuler"))));
            rig.<CheckBox>control("columnRulerCheck").setSelected(true);
            assertFalse(controls.get("rulers").isDisabled());

            rig.<CheckBox>control("notesCheck").setSelected(false);
            assertTrue(switchNamed(editor, tr("settings.showNoteIndicators")).isDisabled());
            assertTrue(noteShown(editor, tr("settings.dependsOn", tr("settings.enableNotes"))));
            rig.<CheckBox>control("notesCheck").setSelected(true);
            assertFalse(switchNamed(editor, tr("settings.showNoteIndicators")).isDisabled());

            assertFalse(noteShown(editor, tr("settings.simpleModeOverrides")));
            rig.<CheckBox>control("simpleModeCheck").setSelected(true);
            assertTrue(noteShown(editor, tr("settings.simpleModeOverrides")), "Simple UI mode hides line numbers");
            rig.<CheckBox>control("simpleModeCheck").setSelected(false);

            Region spell = rig.page("SPELL_CHECK");
            rig.<CheckBox>control("spellCheckBox").setSelected(false);
            assertTrue(
                    rig.<javafx.scene.Node>control("spellFileTypes").isDisabled(),
                    "file types of a checker that is off");
            assertTrue(switchNamed(spell, tr("settings.dict.technical")).isDisabled());
            assertTrue(rig.<javafx.scene.Node>control("spellLanguageCombo").isDisabled());
            rig.<CheckBox>control("spellCheckBox").setSelected(true);
            assertFalse(rig.<javafx.scene.Node>control("spellFileTypes").isDisabled());
            assertFalse(rig.<javafx.scene.Node>control("spellLanguageCombo").isDisabled());
        });
    }

    @Test
    void aDisabledToolWindowRowNamesThePageThatTurnsItOn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setProjectSupport(false);
            rig.show();
            rig.page("TOOL_WINDOWS");
            String note = twRow("project").disabledNote().getText();
            assertTrue(note.contains(tr("settings.cat.workspace").strip()), note);
            assertTrue(twRow("project").disabledNote().isVisible());
        });
    }

    @Test
    void theAppearancePageSaysWhenTextZoomScalesTheFontSizeAndCanResetIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setFontSize(18);
            settings.setFontZoom(1.6);
            rig.show();
            Region page = rig.page("APPEARANCE");
            javafx.scene.Node preview = rig.control("preview");
            assertTrue(preview.getStyle().contains("-fx-font-size: 29px"), preview.getStyle());
            assertTrue(noteShown(page, tr("settings.fontZoom.note", "160", "29")));

            ((javafx.scene.control.Hyperlink) SettingsRig.button(page, tr("settings.fontZoom.reset"))).fire();
            assertEquals(1.0, settings.getFontZoom());
            assertEquals(18, settings.getFontSize());
            assertTrue(preview.getStyle().contains("-fx-font-size: 18px"), preview.getStyle());
            assertFalse(noteShown(page, tr("settings.fontZoom.note", "160", "29")));
            HBox zoomRow = rig.control("zoomNote");
            assertFalse(zoomRow.isVisible());
        });
    }

    @Test
    void theFontAndThemePickersSayWhatEachChoiceIs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("APPEARANCE");
            ComboBox<String> family = rig.control("fontFamily");
            ComboBox<String> theme = rig.control("themeCombo");
            javafx.scene.control.ListCell<String> fontCell =
                    family.getCellFactory().call(null);
            FxTestSupport.call(
                    fontCell, "updateItem", new Class<?>[] {Object.class, boolean.class}, "JetBrains Mono", false);
            assertEquals(tr("settings.fontFamily.bundled", "JetBrains Mono"), fontCell.getText());
            javafx.scene.control.ListCell<String> themeCell =
                    theme.getCellFactory().call(null);
            FxTestSupport.call(themeCell, "updateItem", new Class<?>[] {Object.class, boolean.class}, "Dracula", false);
            assertEquals("Dracula", themeCell.getText(), "the Dark heading says it, not a tag");
            assertTrue(family.getItems().stream().noneMatch(f -> f.startsWith("MathJax_")));
            assertTrue(SettingsRig.texts(page).contains(tr("settings.userThemes.reload")));
        });
    }

    @Test
    void eachOfThesePagesSaysWhatItIsForAndUpdatesAndTrustLiveOnAdvanced() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            for (String cat : List.of(
                    "APPEARANCE",
                    "INTERFACE",
                    "TOOLBAR",
                    "WORKSPACE",
                    "TOOL_WINDOWS",
                    "EDITOR",
                    "COMPLETION",
                    "TODO",
                    "SPELL_CHECK",
                    "SEARCH")) {
                boolean subtitled = SettingsRig.all(categoryPage(cat), javafx.scene.control.Label.class).stream()
                        .anyMatch(l -> l.getStyleClass().contains("settings-page-subtitle"));
                assertTrue(subtitled, cat);
            }
            List<String> workspace = SettingsRig.texts(categoryPage("WORKSPACE"));
            List<String> advanced = SettingsRig.texts(categoryPage("ADVANCED"));
            assertFalse(workspace.contains(tr("settings.checkForUpdates")));
            assertFalse(workspace.contains(tr("settings.section.trustedFolders")));
            assertTrue(advanced.contains(tr("settings.checkForUpdates")));
            assertTrue(advanced.contains(tr("settings.section.trustedFolders")));
            assertTrue(SettingsRig.texts(categoryPage("SEARCH")).contains(tr("settings.search.gitignore.desc")));
        });
    }

    private static javafx.scene.control.ListCell<String> cellFor(ComboBox<String> combo, String item) {
        javafx.scene.control.ListCell<String> cell = combo.getCellFactory().call(null);
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {Object.class, boolean.class}, item, false);
        return cell;
    }

    @Test
    void bothThemePickersGroupThemesUnderLightAndDarkHeadingsThatCannotBeChosen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("APPEARANCE");
            for (String field : List.of("themeCombo", "editorThemeCombo")) {
                ComboBox<String> combo = rig.control(field);
                List<String> items = combo.getItems();
                int light = items.indexOf(ThemeGroups.LIGHT);
                int dark = items.indexOf(ThemeGroups.DARK);
                assertTrue(light >= 0 && dark > light, field + " has Light then Dark");
                for (String name : List.of("Editora Light", "Primer Light", "Nord Light", "Yacht")) {
                    int at = items.indexOf(name);
                    assertTrue(at > light && at < dark, field + ": " + name + " is light");
                }
                for (String name : List.of("Editora Dark", "Primer Dark", "Nord Dark", "Dracula")) {
                    assertTrue(items.indexOf(name) > dark, field + ": " + name + " is dark");
                }
                assertTrue(
                        items.indexOf("Primer Light") + 1 == items.indexOf("Nord Light"),
                        "a family's members keep their catalogue order inside a group");

                javafx.scene.control.ListCell<String> heading = cellFor(combo, ThemeGroups.DARK);
                assertEquals(tr("settings.theme.group.dark"), heading.getText());
                assertTrue(heading.isDisable(), "the mouse cannot choose a heading");
                assertFalse(cellFor(combo, "Dracula").isDisable());

                // The keyboard steps over a heading in the direction it is going.
                combo.getSelectionModel().select(dark - 1);
                combo.getSelectionModel().selectNext();
                assertEquals(dark + 1, combo.getSelectionModel().getSelectedIndex(), field + ": down skips it");
                combo.getSelectionModel().selectPrevious();
                assertEquals(dark - 1, combo.getSelectionModel().getSelectedIndex(), field + ": up skips it");
                combo.getSelectionModel().select(light); // a jump onto a heading lands on a theme beside it
                assertFalse(ThemeGroups.isHeading(combo.getSelectionModel().getSelectedItem()), field);
                assertFalse(ThemeGroups.isHeading(combo.getValue()), field);
            }
            assertFalse(ThemeGroups.isHeading(settings.getTheme()));
            assertFalse(ThemeGroups.isHeading(settings.getEditorTheme()));
        });
    }
}
