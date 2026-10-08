package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import com.editora.AppInfo;
import com.editora.config.Settings;
import com.editora.markdown.MarkdownLint;
import com.editora.plugin.PluginManager;
import com.editora.todo.TodoPattern;
import com.editora.update.ReleaseInfo;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings pages that are more than a row of switches: their lists, buttons and links. */
@Tag("fx")
class SettingsPagesFxTest {

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

    private static KeyEvent key(KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false);
    }

    @Test
    void aDoctorRowOpensThePageItsCheckBelongsTo() throws Exception {
        Map<String, String> targets = new java.util.LinkedHashMap<>();
        targets.put("git", "GIT");
        targets.put("github", "GITHUB");
        targets.put("search", "SEARCH");
        targets.put("mermaid", "MERMAID");
        targets.put("diagrams", "DIAGRAMS");
        targets.put("typst", "TYPST");
        targets.put("lsp", "LSP");
        targets.put("debug", "DEBUG");
        targets.put("web", "WEB");
        targets.put("ai", "AI_GENERAL");
        targets.put("buildTools", "BUILD_TOOLS");
        targets.put("editor", "EDITOR");
        FxTestSupport.runOnFx(() -> {
            targets.forEach((key, page) -> {
                rig.window.showDoctorTarget(key, rig.owner);
                assertEquals(page, rig.openPage(), key);
            });
            assertTrue(rig.stage().isShowing());

            // A key this version does not know still opens Settings, on the page it was on.
            rig.window.showDoctorTarget("a-check-added-later", rig.owner);
            assertEquals("EDITOR", rig.openPage());
            rig.window.showDoctorTarget(null, rig.owner);
            assertEquals("EDITOR", rig.openPage());
        });
    }

    @Test
    void theNamedEntryPointsOpenTheirPages() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.window.showSnippets(rig.owner);
            assertEquals("SNIPPETS", rig.openPage());
            rig.window.showSync(rig.owner);
            assertEquals("SYNC", rig.openPage());
            rig.window.showAbbreviations(rig.owner);
            assertEquals("ABBREVIATIONS", rig.openPage());
            rig.window.showToolbar(rig.owner);
            assertEquals("TOOLBAR", rig.openPage());
            rig.window.showTemplates(rig.owner);
            assertEquals("TEMPLATES", rig.openPage());
            rig.window.showWorkspace(rig.owner);
            assertEquals("WORKSPACE", rig.openPage());
        });
    }

    @Test
    void theSidebarKeepsAKeyPressedWithAModifierForWhoeverOwnsThatChord() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            ListView<Object> sidebar = rig.control("sidebar");
            rig.page("EDITOR");

            for (KeyEvent chord : List.of(
                    key(KeyCode.DOWN, true, false, false),
                    key(KeyCode.DOWN, false, true, false),
                    key(KeyCode.DOWN, false, false, true),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, true))) {
                KeyEvent[] seen = new KeyEvent[1];
                javafx.event.EventHandler<KeyEvent> probe = e -> seen[0] = e;
                sidebar.addEventHandler(KeyEvent.KEY_PRESSED, probe);
                Event.fireEvent(sidebar, chord);
                sidebar.removeEventHandler(KeyEvent.KEY_PRESSED, probe);
                assertNotNull(seen[0], "the chord is passed on, not swallowed");
            }

            rig.page("EDITOR");
            Event.fireEvent(sidebar, key(KeyCode.A, false, false, false)); // not a navigation key
            assertEquals("EDITOR", rig.openPage());

            Event.fireEvent(sidebar, key(KeyCode.HOME, false, false, false));
            assertEquals("APPEARANCE", rig.openPage(), "Home is the first page, not the group header above it");
            Event.fireEvent(sidebar, key(KeyCode.UP, false, false, false));
            assertEquals("APPEARANCE", rig.openPage(), "and Up from there stays put");
            Event.fireEvent(sidebar, key(KeyCode.END, false, false, false));
            assertEquals("ADVANCED", rig.openPage());
        });
    }

    @Test
    void escapeClearsASearchFirstAndClosesTheWindowSecond() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            TextField search = rig.control("searchField");
            ListView<Object> sidebar = rig.control("sidebar");
            search.setText("font");

            Event.fireEvent(sidebar, key(KeyCode.ESCAPE, false, false, true)); // Alt+Esc is the system's
            assertEquals("font", search.getText());
            assertTrue(rig.stage().isShowing());

            Event.fireEvent(sidebar, key(KeyCode.ESCAPE, false, false, false));
            assertEquals("", search.getText());
            assertTrue(rig.stage().isShowing(), "the first Escape only leaves the search");

            Event.fireEvent(sidebar, key(KeyCode.ESCAPE, false, false, false));
            assertFalse(rig.stage().isShowing());
        });
    }

    @Test
    void escapeInsideATextEditorOrAShortcutRecorderIsNotARequestToClose() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region snippets = rig.page("SNIPPETS");
            CodeArea body = SettingsRig.all(snippets, CodeArea.class).get(0);
            Event.fireEvent(body, key(KeyCode.ESCAPE, false, false, false));
            assertTrue(rig.stage().isShowing(), "Escape in a snippet body belongs to the editor");
        });
    }

    @Test
    void theTrustedFoldersListRevokesOneFolderOrAll() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> trusted = new ArrayList<>(List.of("/work/a", "/work/b", "/work/c"));
            rig.show();
            Region page = rig.page("WORKSPACE");
            ListView<String> list = rig.control("trustedFoldersList");
            Button revoke = (Button) SettingsRig.button(page, tr("settings.trustedFolders.revoke"));
            Button revokeAll = (Button) SettingsRig.button(page, tr("settings.trustedFolders.revokeAll"));

            assertEquals(List.of(), list.getItems(), "no trust store: nothing listed");
            assertTrue(revokeAll.isDisable());
            revokeAll.fire(); // and nothing to do, with or without a store
            revoke.fire();

            rig.window.setTrustActions(new SettingsWindow.TrustActions() {
                @Override
                public List<String> trustedRoots() {
                    return List.copyOf(trusted);
                }

                @Override
                public void revoke(String root) {
                    trusted.remove(root);
                }

                @Override
                public void revokeAll() {
                    trusted.clear();
                }
            });
            assertEquals(List.of("/work/a", "/work/b", "/work/c"), list.getItems());
            assertTrue(revoke.isDisable(), "nothing selected yet");
            revoke.fire();
            assertEquals(3, trusted.size(), "Revoke with no selection revokes nothing");

            list.getSelectionModel().select("/work/b");
            assertFalse(revoke.isDisable());
            revoke.fire();
            assertEquals(List.of("/work/a", "/work/c"), trusted);
            assertEquals(List.of("/work/a", "/work/c"), list.getItems());

            trusted.add("/work/granted-meanwhile"); // trust granted from a prompt while the page is open
            rig.window.refreshTrustedFolders();
            assertEquals(3, list.getItems().size());

            revokeAll.fire();
            assertEquals(List.of(), trusted);
            assertEquals(List.of(), list.getItems());
            assertTrue(revokeAll.isDisable());
        });
    }

    @Test
    void anInstallButtonAsksForItsOwnToolAndDoesNothingUntilAnInstallerIsWired() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Map<String, Button> buttons = rig.control("installButtons");
            List<String> languages = new ArrayList<>();
            List<String> servers = new ArrayList<>();

            buttons.values().forEach(Button::fire); // no installer wired: a click is not an error
            assertTrue(languages.isEmpty() && servers.isEmpty());

            rig.window.setInstallActions(languages::add);
            rig.window.setInstallServerActions(servers::add);
            for (String language : List.of("java", "python", "javascript", "mermaid", "typst")) {
                buttons.get(language).fire();
            }
            assertEquals(List.of("java", "python", "javascript", "mermaid", "typst"), languages);
            assertEquals(List.of(), servers);

            // The servers with an installer of their own, Typst's language server among them: its button is
            // not the Typst page's button for the typst program.
            List<String> serverKeys = buttons.keySet().stream()
                    .filter(k -> k.startsWith("server:"))
                    .sorted()
                    .toList();
            serverKeys.forEach(k -> buttons.get(k).fire());
            assertEquals(
                    com.editora.install.InstallCatalog.installableServerIds().stream()
                            .filter(id ->
                                    !List.of("java", "python", "typescript").contains(id))
                            .sorted()
                            .toList(),
                    servers.stream().sorted().toList());
            assertTrue(servers.contains("typst"));
            assertEquals(5, languages.size());
        });
    }

    /**
     * The typst program and Typst's language server are two tools with two Install buttons. They were tracked
     * under one key, so one button never changed and the other showed whichever probe finished last.
     */
    @Test
    void theTypstProgramAndItsLanguageServerEachHaveTheirOwnInstalledState(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win"));
        Path server = Files.writeString(dir.resolve("tinymist"), "#!/bin/sh\n");
        assertTrue(server.toFile().setExecutable(true));
        Path missing = dir.resolve("not-installed");
        try (SettingsRig detecting = SettingsRig.createWithDetection()) {
            com.editora.typst.TypstService typst =
                    FxTestSupport.callOnFx(() -> FxTestSupport.<TypstCoordinator>field(detecting.fx.controller, "typst")
                            .service());
            Button[] buttons = new Button[2];
            String installed = tr("settings.install.installed");
            String install = tr("settings.install.button");

            // The program is there, the server is not.
            FxTestSupport.runOnFx(() -> {
                setCached(typst, true);
                detecting.settings.setTypstLspCommand(missing.toString());
                detecting.show();
                Map<String, Button> all = detecting.control("installButtons");
                buttons[0] = all.get("typst");
                buttons[1] = all.get("server:typst");
                assertTrue(
                        SettingsRig.all(detecting.page("TYPST"), Button.class).contains(buttons[0]));
                assertTrue(SettingsRig.all(detecting.page("LSP"), Button.class).contains(buttons[1]));
            });
            SettingsRig.awaitFx("the typst program to be found", () -> buttons[0].isDisable());
            SettingsRig.awaitFx("the server probe", () -> lspStatus(detecting).contains(tr("settings.lsp.notFound")));
            FxTestSupport.runOnFx(() -> {
                assertEquals(installed, buttons[0].getText());
                assertFalse(buttons[1].isDisable(), "the server is still to be installed");
                assertEquals(install, buttons[1].getText());
            });

            // And the other way round.
            FxTestSupport.runOnFx(() -> {
                setCached(typst, false);
                detecting.settings.setTypstLspCommand(server.toString());
                detecting.show();
            });
            SettingsRig.awaitFx("the server to be found", () -> buttons[1].isDisable());
            SettingsRig.awaitFx("the program probe", () -> !buttons[0].isDisable());
            FxTestSupport.runOnFx(() -> {
                assertEquals(installed, buttons[1].getText());
                assertEquals(install, buttons[0].getText());
            });
        }
    }

    private static String lspStatus(SettingsRig rig) {
        Map<String, Label> labels = rig.control("lspStatusLabels");
        return labels.get("typst").getText();
    }

    /** What the Typst service last found, without running the program to find out. */
    private static void setCached(com.editora.typst.TypstService service, boolean present) {
        try {
            java.lang.reflect.Field cached = com.editora.typst.TypstService.class.getDeclaredField("cached");
            cached.setAccessible(true);
            cached.set(service, present);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theAdvancedPageOpensTheFilesItNamesAndRunsItsActions() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("ADVANCED");
            Path settingsFile = rig.config.getSettingsFile();
            Path sessionLog = DebugLog.sessionFile(rig.config.getConfigDir());

            SettingsRig.button(page, SettingsWindow.displaySettingsPath(settingsFile))
                    .fire();
            SettingsRig.button(page, SettingsWindow.displaySettingsPath(sessionLog))
                    .fire();
            assertEquals(List.of(settingsFile, sessionLog), rig.opened);

            SettingsRig.button(page, tr("settings.exportConfig")).fire();
            SettingsRig.button(page, tr("settings.debugLog")).fire();
            assertEquals(List.of("export", "debugLog"), rig.events);
        });
    }

    @Test
    void aPathUnderTheHomeDirectoryIsShownWithATilde() {
        String home = System.getProperty("user.home", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(home.isEmpty());
        Path inside = Path.of(home).resolve("cfg").resolve("settings.json");
        assertEquals("~" + inside.toString().substring(home.length()), SettingsWindow.displaySettingsPath(inside));
        Path outside = Path.of(home).getRoot().resolve("somewhere-else").resolve("settings.json");
        org.junit.jupiter.api.Assumptions.assumeFalse(outside.toString().startsWith(home));
        assertEquals(outside.toString(), SettingsWindow.displaySettingsPath(outside));
    }

    @Test
    void thePersonalDictionaryEditorAddsEachWordOnceAndRemovesTheSelectedOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("SPELL_CHECK");
            ListView<String> list = rig.control("dictionaryList");
            TextField input = rig.control("dictionaryInput");
            Button add = (Button) SettingsRig.button(page, tr("settings.dict.add"));
            Button remove = (Button) SettingsRig.button(page, tr("settings.dict.remove"));
            assertTrue(remove.isDisable(), "nothing selected");

            input.setText("   ");
            add.fire();
            assertEquals(List.of(), list.getItems(), "blank input adds nothing");
            assertEquals(List.of(), rig.applied);

            input.setText("Zzyzx  kubectl");
            add.fire(); // a phrase is one entry per word, in the form the checker looks words up by
            assertTrue(rig.config.getUserDictionary().containsAll(List.of("zzyzx", "kubectl")));
            assertEquals(List.of("kubectl", "zzyzx"), list.getItems());
            assertEquals("", input.getText());
            assertEquals("kubectl", list.getSelectionModel().getSelectedItem(), "the word just added is selected");
            assertEquals(1, rig.applied.size(), "the open editors re-check their text once");

            SettingsRig.typeAndEnter(input, "quux");
            assertEquals(List.of("kubectl", "quux", "zzyzx"), list.getItems());

            input.setText("zz"); // what is being typed narrows the list, and stores nothing
            assertEquals(List.of("zzyzx"), list.getItems());
            assertEquals(3, rig.config.getUserDictionary().size());
            input.setText("two words"); // a phrase does not filter
            assertEquals(3, list.getItems().size());
            input.clear();

            list.getSelectionModel().select("quux");
            assertFalse(remove.isDisable());
            remove.fire();
            assertFalse(rig.config.getUserDictionary().contains("quux"));
            assertEquals(List.of("kubectl", "zzyzx"), list.getItems());
            assertEquals(3, rig.applied.size());

            // A word added from an editor's context menu while the page is open.
            rig.config.addUserWord("fromeditor");
            rig.window.syncDictionaryList();
            assertTrue(list.getItems().contains("fromeditor"));
        });
        assertEquals(
                List.of("fromeditor", "kubectl", "zzyzx"),
                Files.readAllLines(rig.config.getUserDictionaryFile()).stream()
                        .sorted()
                        .toList());
    }

    @Test
    void theDictionaryLinksOpenTheirFilesOnceTheyAreWired() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("SPELL_CHECK");
            Hyperlink technical = (Hyperlink) SettingsRig.button(page, tr("settings.dict.openTechnical"));
            Hyperlink personal = (Hyperlink) SettingsRig.button(page, tr("settings.dict.openPersonal"));
            technical.fire();
            personal.fire();
            assertEquals(List.of(), rig.events);

            rig.window.setDictionaryActions(() -> rig.events.add("technical"), () -> rig.events.add("personal"));
            technical.fire();
            personal.fire();
            assertEquals(List.of("technical", "personal"), rig.events);

            // The two dictionary switches, in both directions.
            CheckBox personalOn = rig.control("dictEnableCheck");
            CheckBox technicalOn = rig.control("techDictEnableCheck");
            personalOn.setSelected(!personalOn.isSelected());
            technicalOn.setSelected(!technicalOn.isSelected());
            assertEquals(personalOn.isSelected(), settings.isPersonalDictionary());
            assertEquals(technicalOn.isSelected(), settings.isTechnicalDictionary());
            int applied = rig.applied.size();
            settings.setPersonalDictionary(!settings.isPersonalDictionary());
            settings.setTechnicalDictionary(!settings.isTechnicalDictionary());
            rig.window.syncPersonalDictionaryCheck();
            rig.window.syncTechnicalDictionaryCheck();
            assertEquals(settings.isPersonalDictionary(), personalOn.isSelected());
            assertEquals(settings.isTechnicalDictionary(), technicalOn.isSelected());
            assertEquals(applied, rig.applied.size(), "showing a change made elsewhere is not an edit");
        });
    }

    @Test
    void theTodoPatternEditorAddsEditsAndRemovesPatterns() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(new TodoPattern("FIXME", "\\bFIXME\\b", "", true, true))));
            rig.show();
            Region page = rig.page("TODO");
            VBox rows = rig.control("todoPatternsBox");
            assertEquals(1, rows.getChildren().size());

            SettingsRig.button(page, tr("settings.todo.add")).fire();
            assertEquals(2, settings.getTodoPatterns().size());
            TodoPattern added = settings.getTodoPatterns().get(1);
            assertEquals(tr("settings.todo.newName"), added.getName());
            assertEquals("\\bTODO\\b", added.getPattern());
            assertTrue(added.isEnabled());
            assertEquals(2, rows.getChildren().size());
            assertEquals(1, rig.applied.size());

            HBox row = (HBox) rows.getChildren().get(1);
            CheckBox enabled = (CheckBox) row.getChildren().get(0);
            TextField name = (TextField) row.getChildren().get(1);
            TextField regex = (TextField) row.getChildren().get(2);
            ColorPicker color = (ColorPicker) row.getChildren().get(3);
            CheckBox caseSensitive = (CheckBox) row.getChildren().get(4);

            SettingsRig.typeAndEnter(name, "HACK");
            SettingsRig.typeAndEnter(regex, "\\bHACK\\b");
            color.setValue(Color.web("#102030"));
            caseSensitive.setSelected(true);
            enabled.setSelected(false);
            TodoPattern edited = settings.getTodoPatterns().get(1);
            assertEquals("HACK", edited.getName());
            assertEquals("\\bHACK\\b", edited.getPattern());
            assertEquals("#102030", edited.getColor());
            assertTrue(edited.isCaseSensitive());
            assertFalse(edited.isEnabled());

            // A malformed expression would silently switch the keyword off: it is flagged and not stored.
            SettingsRig.typeAndEnter(regex, "(unclosed");
            assertEquals("\\bHACK\\b", settings.getTodoPatterns().get(1).getPattern());
            assertNotNull(regex.getTooltip());
            assertTrue(regex.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            SettingsRig.typeAndEnter(regex, "HACK!?");
            assertEquals("HACK!?", settings.getTodoPatterns().get(1).getPattern());
            assertNull(regex.getTooltip());
            assertFalse(regex.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));

            // An unusable stored colour shows the default swatch instead of failing the page.
            ColorPicker first = (ColorPicker)
                    ((HBox) rows.getChildren().get(0)).getChildren().get(3);
            assertEquals(Color.web("#E5C07B"), first.getValue());

            ((Button) ((HBox) rows.getChildren().get(0)).getChildren().get(5)).fire(); // remove FIXME
            assertEquals(1, settings.getTodoPatterns().size());
            assertEquals("HACK", settings.getTodoPatterns().get(0).getName());
            assertEquals(1, rows.getChildren().size());
            assertEquals(
                    "HACK",
                    ((TextField) ((HBox) rows.getChildren().get(0))
                                    .getChildren()
                                    .get(1))
                            .getText());
        });
    }

    @Test
    void aStoredTodoColourThatIsNotAColourShowsTheDefault() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(
                    new TodoPattern("A", "A", "not-a-colour", true, true),
                    new TodoPattern("B", "B", "#0000FF", true, true))));
            rig.show();
            VBox rows = rig.control("todoPatternsBox");
            assertEquals(
                    Color.web("#E5C07B"),
                    ((ColorPicker) ((HBox) rows.getChildren().get(0))
                                    .getChildren()
                                    .get(3))
                            .getValue());
            assertEquals(
                    Color.BLUE,
                    ((ColorPicker) ((HBox) rows.getChildren().get(1))
                                    .getChildren()
                                    .get(3))
                            .getValue());
        });
    }

    @Test
    void aMarkdownLintRuleIsSwitchedOffAndOnByItsCode() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String first = MarkdownLint.RULES.get(0).code();
            String second = MarkdownLint.RULES.get(1).code();
            List<String> stored = new ArrayList<>();
            stored.add(" " + second.toLowerCase(java.util.Locale.ROOT) + " "); // hand-edited: case and spaces
            stored.add(null);
            settings.setMarkdownLintDisabledRules(stored);
            rig.show();
            VBox rules = rig.control("markdownLintRulesBox");
            assertEquals(MarkdownLint.RULES.size(), rules.getChildren().size());
            CheckBox firstBox = (CheckBox) rules.getChildren().get(0);
            CheckBox secondBox = (CheckBox) rules.getChildren().get(1);
            assertTrue(firstBox.isSelected());
            assertFalse(secondBox.isSelected(), "a rule disabled in the file shows unticked");
            assertTrue(firstBox.getAccessibleText().startsWith(first + " "));

            firstBox.setSelected(false);
            assertTrue(settings.getMarkdownLintDisabledRules().contains(first));
            firstBox.setSelected(true);
            assertFalse(settings.getMarkdownLintDisabledRules().contains(first));
            assertEquals(2, rig.applied.size());
        });
    }

    @Test
    void thePluginsPageRunsItsActionsOnceTheyAreWired() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("PLUGINS");
            Button browse = (Button) SettingsRig.button(page, tr("settings.plugins.browse"));
            Button fromFile = (Button) SettingsRig.button(page, tr("settings.plugins.installFromFile"));
            Button reload = (Button) SettingsRig.button(page, tr("settings.plugins.reload"));

            browse.fire();
            fromFile.fire();
            reload.fire();
            assertTrue(rig.stage().isShowing(), "nothing to browse with: the window stays");
            assertEquals(List.of(), rig.events);
            VBox list = rig.control("pluginListBox");
            assertEquals(List.of(tr("settings.plugins.none")), SettingsRig.texts(list));

            rig.window.setPluginActions(
                    () -> rig.events.add("browse"), () -> rig.events.add("file"), id -> rig.events.add("remove " + id));
            fromFile.fire();
            assertEquals(List.of("file"), rig.events);
            browse.fire();
            assertEquals(List.of("file", "browse"), rig.events);
            assertFalse(rig.stage().isShowing(), "the registry picker opens in the main window, behind this one");
        });
    }

    @Test
    void aRegistryThatIsNotTheBundledOneIsNamedAsAWarning() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("PLUGINS");
            TextField registry = rig.control("pluginRegistryField");
            Label warn = rig.control("pluginRegistryWarn");
            assertFalse(warn.isVisible());
            assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, registry.getText());

            SettingsRig.typeAndEnter(registry, "https://plugins.example.org/index.json");
            assertEquals("https://plugins.example.org/index.json", settings.getPluginRegistryUrl());
            assertTrue(warn.isVisible());
            assertEquals(tr("settings.plugins.customRegistry", "plugins.example.org"), warn.getText());

            SettingsRig.typeAndEnter(registry, "not a url at all");
            assertEquals(tr("settings.plugins.customRegistry", "not a url at all"), warn.getText());

            SettingsRig.typeAndEnter(registry, "mailto:someone"); // a URI with no host
            assertEquals(tr("settings.plugins.customRegistry", "?"), warn.getText());

            int applied = rig.applied.size();
            SettingsRig.typeAndEnter(registry, ""); // emptied: back to the bundled registry
            assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, settings.getPluginRegistryUrl());
            assertFalse(warn.isVisible());
            assertEquals("", warn.getText());
            assertEquals(applied + 1, rig.applied.size());
            registry.fireEvent(new ActionEvent()); // still empty: nothing more to save
            assertEquals(applied + 1, rig.applied.size());

            CheckBox signatures = rig.control("pluginRequireSigCheck");
            signatures.setSelected(!signatures.isSelected());
            assertEquals(signatures.isSelected(), settings.isPluginRequireSignature());
            CheckBox plugins = rig.control("pluginCheck");
            plugins.setSelected(!plugins.isSelected());
            assertEquals(plugins.isSelected(), settings.isPluginSupport());
            settings.setPluginSupport(!settings.isPluginSupport());
            rig.window.syncPluginsCheck();
            assertEquals(settings.isPluginSupport(), plugins.isSelected());
        });
    }

    private static void writePlugin(Path pluginsDir, String id, String manifest) throws Exception {
        Path dir = Files.createDirectories(pluginsDir.resolve(id));
        Files.writeString(dir.resolve("plugin.json"), manifest);
    }

    @Test
    void anInstalledPluginIsEnabledOnlyAfterItsCapabilitiesAreConfirmed() throws Exception {
        Path pluginsDir = rig.config.getPluginsDir();
        writePlugin(pluginsDir, "greeter", "{\"id\":\"greeter\",\"name\":\"Greeter\",\"version\":\"1.2\"}");
        writePlugin(pluginsDir, "plain", "{\"id\":\"plain\"}");
        writePlugin(pluginsDir, "broken", "{ this is not json");
        FxTestSupport.runOnFx(() -> {
            settings.setPluginSupport(true);
            PluginManager manager = new PluginManager(
                    pluginsDir, id -> rig.config.getPluginStore().isEnabled(id));
            manager.discover();
            rig.window.setPluginManager(manager);
            rig.window.setPluginActions(() -> {}, () -> {}, id -> rig.events.add("remove " + id));
            rig.show();
            rig.page("PLUGINS");
            VBox list = rig.control("pluginListBox");
            assertEquals(manager.descriptors().size(), list.getChildren().size());
            List<CheckBox> boxes = SettingsRig.all(list, CheckBox.class);
            CheckBox greeter = boxes.stream()
                    .filter(b -> b.getText().equals("Greeter  1.2  (greeter)"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            boxes.stream().map(CheckBox::getText).toList().toString()));
            assertTrue(boxes.stream().anyMatch(b -> b.getText().equals("plain  (plain)")), "no name: the id");
            assertFalse(greeter.isSelected());
            assertFalse(greeter.isDisable());
            manager.descriptors().stream()
                    .filter(d -> d.loadError() != null)
                    .forEach(d -> assertTrue(SettingsRig.texts(list).contains(d.loadError()), d.loadError()));

            List<SettingsRig.Shown> asked =
                    SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, () -> greeter.setSelected(true));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.plugins.enableTitle"), asked.get(0).title());
            assertEquals(tr("dialog.plugins.enableHeader"), asked.get(0).header());
            assertTrue(asked.get(0).content().contains("Greeter"));
            assertFalse(greeter.isSelected(), "declined: the box goes back");
            assertFalse(rig.config.getPluginStore().isEnabled("greeter"));

            asked = SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> greeter.setSelected(true));
            assertEquals(1, asked.size());
            assertTrue(greeter.isSelected());
            assertTrue(rig.config.getPluginStore().isEnabled("greeter"));

            assertEquals(
                    List.of(),
                    SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> greeter.setSelected(false)),
                    "switching a plugin off needs no confirmation");
            assertFalse(rig.config.getPluginStore().isEnabled("greeter"));

            HBox header = (HBox) greeter.getParent();
            ((Button) SettingsRig.button(header, tr("settings.plugins.remove"))).fire();
            assertEquals(List.of("remove greeter"), rig.events);

            // With plugin support off, the list is shown but nothing in it can be switched.
            rig.<CheckBox>control("pluginCheck").setSelected(false);
            rig.show();
            assertTrue(SettingsRig.all(list, CheckBox.class).stream().allMatch(CheckBox::isDisable));

            // Reload re-reads the folder: a plugin removed on disk is gone from the list.
            try {
                Files.delete(pluginsDir.resolve("plain").resolve("plugin.json"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            SettingsRig.button(rig.page("PLUGINS"), tr("settings.plugins.reload"))
                    .fire();
            assertFalse(SettingsRig.all(list, CheckBox.class).stream()
                    .anyMatch(b -> b.getText().contains("(plain)")));
        });
    }

    /**
     * Shows the About panel and runs {@code inside} while it is open. The panel is modal: whatever
     * {@code inside} does, it is closed afterwards, and a failure in there fails the test.
     */
    private void about(
            Path settingsFile,
            java.util.function.Consumer<Path> openFile,
            java.util.function.Consumer<String> openUrl,
            String commit,
            ReleaseInfo update,
            java.util.function.Consumer<DialogPane> inside) {
        Throwable[] failure = new Throwable[1];
        javafx.application.Platform.runLater(() -> {
            DialogPane pane = aboutPaneOrNull();
            try {
                assertNotNull(pane, "the About panel is showing");
                inside.accept(pane);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                if (pane != null && pane.getScene().getWindow().isShowing()) {
                    pane.getScene().getWindow().hide();
                }
            }
        });
        SettingsWindow.showAbout(rig.owner, settingsFile, openFile, openUrl, commit, update);
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    void theAboutPanelLinksOpenWhatTheyNameAndCopyDetailsCopiesTheEnvironment() throws Exception {
        Path settingsFile = rig.config.getSettingsFile();
        List<String> urls = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            String[] details = new String[1];
            boolean[] closedByUpdateRow = new boolean[1];
            about(
                    settingsFile,
                    files::add,
                    urls::add,
                    "abc1234",
                    new ReleaseInfo("9.9.9", "https://example.org/releases/9.9.9", "Nine"),
                    pane -> {
                        SettingsRig.button(pane, AppInfo.LICENSE).fire();
                        SettingsRig.button(pane, tr("about.homepage")).fire();
                        SettingsRig.button(pane, tr("about.releases")).fire();
                        Button copy = (Button) SettingsRig.button(pane, tr("about.copyDetails"));
                        copy.fire();
                        assertEquals(tr("about.copied"), copy.getText());
                        details[0] = Clipboard.getSystemClipboard().getString();
                        Window panel = pane.getScene().getWindow();
                        assertTrue(panel.isShowing(), "none of these dismisses the panel");
                        // The update row closes the panel and opens the release it names.
                        SettingsRig.button(pane, tr("about.updateAvailable") + " 9.9.9")
                                .fire();
                        closedByUpdateRow[0] = !panel.isShowing();
                    });
            assertTrue(closedByUpdateRow[0]);
            assertEquals(
                    List.of(
                            AppInfo.HOMEPAGE,
                            AppInfo.HOMEPAGE,
                            AppInfo.RELEASES_PAGE,
                            "https://example.org/releases/9.9.9"),
                    urls);
            assertEquals(List.of(), files);
            assertNotNull(details[0]);
            assertTrue(details[0].startsWith(AppInfo.NAME + " " + AppInfo.VERSION), details[0]);
            assertTrue(details[0].contains(tr("about.commit", "abc1234")));
            assertTrue(details[0].contains("Java: " + System.getProperty("java.version")));
            assertTrue(details[0].contains("Config: " + SettingsWindow.displaySettingsPath(settingsFile.getParent())));
        });
    }

    @Test
    void theAboutPanelSettingsLinkClosesItAndOpensTheFile() throws Exception {
        Path settingsFile = rig.config.getSettingsFile();
        List<Path> files = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            String[] details = new String[1];
            boolean[] closed = new boolean[1];
            about(settingsFile, files::add, null, " ", null, pane -> {
                assertFalse(
                        SettingsRig.texts(pane).stream().anyMatch(t -> t.startsWith(tr("about.updateAvailable"))),
                        "no newer release known: no update row");
                ((Button) SettingsRig.button(pane, tr("about.copyDetails"))).fire();
                details[0] = Clipboard.getSystemClipboard().getString();
                Window panel = pane.getScene().getWindow();
                SettingsRig.button(
                                pane,
                                tr("settings.aboutSettingsLabel") + " "
                                        + SettingsWindow.displaySettingsPath(settingsFile))
                        .fire();
                closed[0] = !panel.isShowing();
            });
            assertTrue(closed[0]);
            assertEquals(List.of(settingsFile), files);
            assertFalse(details[0].contains(tr("about.commit", " ")), "a build with no commit recorded says nothing");
        });
    }

    @Test
    void theAboutPanelWorksWithNothingToOpenLinksWith() throws Exception {
        FxTestSupport.runOnFx(() -> {
            boolean[] closed = new boolean[2];
            about(rig.config.getSettingsFile(), null, null, null, new ReleaseInfo("2.0", "", ""), pane -> {
                Window panel = pane.getScene().getWindow();
                SettingsRig.button(pane, AppInfo.LICENSE).fire();
                SettingsRig.button(pane, tr("about.homepage")).fire();
                SettingsRig.button(pane, tr("about.releases")).fire();
                closed[0] = !panel.isShowing();
                SettingsRig.button(pane, tr("about.updateAvailable") + " 2.0").fire();
                closed[1] = !panel.isShowing();
            });
            assertFalse(closed[0]);
            assertTrue(closed[1], "the update row closes the panel even with no browser to open");
        });
    }

    /** A release that names no page of its own is opened on the releases page. */
    @Test
    void anUpdateWithNoPageOfItsOwnOpensTheReleasesPage() throws Exception {
        List<String> urls = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            about(
                    rig.config.getSettingsFile(),
                    null,
                    urls::add,
                    null,
                    new ReleaseInfo("2.0", null, null),
                    pane -> SettingsRig.button(pane, tr("about.updateAvailable") + " 2.0")
                            .fire());
            assertEquals(List.of(AppInfo.RELEASES_PAGE), urls);
        });
    }

    private static DialogPane aboutPaneOrNull() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing()
                    && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane
                    && pane.getStyleClass().contains("about-dialog")) {
                return pane;
            }
        }
        return null;
    }
}
