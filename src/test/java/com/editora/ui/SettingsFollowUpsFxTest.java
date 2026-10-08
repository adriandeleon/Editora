package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.config.Abbreviation;
import com.editora.config.Settings;
import com.editora.macro.Macro;
import com.editora.macro.MacroService;
import com.editora.macro.MacroStep;
import com.editora.template.Template;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the first Settings fix round left open: late commits, late reloads and the last plain checkboxes. */
@Tag("fx")
class SettingsFollowUpsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Region page(SettingsWindow w, String category) {
        Map<?, Region> pages = FxTestSupport.field(w, "pages");
        for (var e : pages.entrySet()) {
            if (e.getKey().toString().equals(category)) {
                return e.getValue();
            }
        }
        throw new IllegalStateException(category);
    }

    private static Button button(Region page, String text) {
        for (Button b : all(page, Button.class, new ArrayList<>())) {
            if (text.equals(b.getText())) {
                return b;
            }
        }
        throw new IllegalStateException(text);
    }

    private static SettingsWindow shown(MainController controller) {
        SettingsWindow w = FxTestSupport.field(controller, "settingsWindow");
        w.show(FxTestSupport.<Stage>field(controller, "stage"));
        return w;
    }

    private static void hide(SettingsWindow w) {
        FxTestSupport.<Stage>field(w, "stage").hide();
    }

    private static MainController secondWindow(FxWindowFixture fx) {
        fx.windowManager.newWindow();
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        return (MainController) FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> names(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var st = Files.list(dir)) {
            return st.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // --- 3: a write that failed before there was a window ---------------------------------------------

    @Test
    void aConfigWriteThatFailedWhileStartingIsShownOnceTheWindowIsUp() throws Exception {
        Path tmp = Files.createTempDirectory("editora-unwritable");
        // A config dir that can never be created: its parent is a regular file.
        Path dir = Files.writeString(tmp.resolve("not-a-directory"), "x").resolve("config");
        FxWindowFixture fx = FxWindowFixture.create(dir, shared -> {});
        try {
            FxTestSupport.drainFx();
            List<MessageLog.Entry> entries = FxTestSupport.callOnFx(() -> {
                StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
                return FxTestSupport.<MessageLog>field(status, "messageLog").entries();
            });
            List<MessageLog.Entry> told = entries.stream()
                    .filter(e -> e.text().equals(tr("status.config.saveFailed", "bookmarks.json")))
                    .toList();
            assertFalse(told.isEmpty(), "the first write failed while loading, before any window: " + entries);
            assertEquals(MessageLog.Severity.ERROR, told.get(0).severity());
        } finally {
            try {
                fx.dispose();
            } catch (Exception ignored) {
                // the config dir never existed, so there is nothing to delete
            }
            Files.deleteIfExists(tmp.resolve("not-a-directory"));
            Files.deleteIfExists(tmp);
        }
    }

    // --- 4: text fields commit on Enter, on focus loss and on close — not per keystroke ----------------

    @Test
    void textFieldsAreCommittedOnEnterOrCloseNotPerKeystroke() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx.controller);
                Settings s = fx.shared.getSettings();
                Path file = fx.configDir.resolve("settings.json");
                ComboBox<String> provider = FxTestSupport.field(w, "aiProviderCombo");
                var ai = com.editora.ai.AiProvider.from(provider.getValue());
                Map<BuildTool, TextField> buildTools = FxTestSupport.field(w, "buildToolCommandFields");

                Map<String, Supplier<String>> stored = new LinkedHashMap<>();
                Map<String, TextField> fields = new LinkedHashMap<>();
                fields.put("typst", FxTestSupport.field(w, "typstPathField"));
                stored.put("typst", s::getTypstPath);
                fields.put("ripgrep", FxTestSupport.field(w, "ripgrepCommandField"));
                stored.put("ripgrep", s::getRipgrepCommand);
                fields.put("maven command", buildTools.get(BuildTool.MAVEN));
                stored.put("maven command", () -> BuildTool.MAVEN.commandIn(s));
                fields.put("maven catalog", FxTestSupport.field(w, "mavenArchetypeCatalogField"));
                stored.put("maven catalog", s::getMavenArchetypeCatalogUrlRaw);
                fields.put("plugin registry", FxTestSupport.field(w, "pluginRegistryField"));
                stored.put("plugin registry", s::getPluginRegistryUrlRaw);
                fields.put("ai endpoint", FxTestSupport.field(w, "aiEndpointField"));
                stored.put("ai endpoint", () -> s.getAiEndpointFor(ai));
                fields.put("ai model", FxTestSupport.field(w, "aiModelField"));
                stored.put("ai model", () -> s.getAiModelFor(ai));
                fields.put("ai key", FxTestSupport.field(w, "aiApiKeyField"));
                stored.put("ai key", () -> s.getApiKeyFor(ai));
                fields.put("ai completion model", FxTestSupport.field(w, "aiCompletionModelField"));
                stored.put("ai completion model", () -> s.getAiCompletionModelFor(ai));
                fields.put("author", FxTestSupport.field(w, "templateAuthorField"));
                stored.put("author", s::getAuthorNameRaw);
                fields.put("git", FxTestSupport.field(w, "gitPathField"));
                stored.put("git", s::getGitPath);

                fields.forEach((name, field) -> {
                    String before = stored.get(name).get();
                    String typed = "https://example.test/" + name.replace(' ', '-');
                    delete(file);
                    for (int n = 1; n <= typed.length(); n++) {
                        field.setText(typed.substring(0, n));
                    }
                    assertEquals(before, stored.get(name).get(), name + ": nothing is applied while typing");
                    assertFalse(Files.exists(file), name + ": and nothing is saved per keystroke");

                    field.fireEvent(new ActionEvent());
                    assertEquals(typed, stored.get(name).get(), name + ": Enter commits");
                    assertTrue(Files.exists(file), name + ": and saves");

                    delete(file);
                    field.fireEvent(new ActionEvent());
                    assertFalse(Files.exists(file), name + ": an unchanged field is not saved again");
                });

                // A URL field shows the built-in URL when none is configured; emptying it goes back to that.
                TextField registry = fields.get("plugin registry");
                registry.setText("");
                registry.fireEvent(new ActionEvent());
                assertEquals("", s.getPluginRegistryUrlRaw());
                assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, s.getPluginRegistryUrl());
                delete(file);
                registry.fireEvent(new ActionEvent());
                assertFalse(Files.exists(file), "an emptied URL field is not re-saved on every Enter / focus loss");

                // Closing the window commits the field being typed in.
                fields.get("typst").setText("/opt/typst/bin/typst");
                fields.get("maven catalog").setText("https://nexus.example/archetype-catalog.xml");
                assertNotEquals("/opt/typst/bin/typst", s.getTypstPath());
                hide(w);
                assertEquals("/opt/typst/bin/typst", s.getTypstPath());
                assertEquals("https://nexus.example/archetype-catalog.xml", s.getMavenArchetypeCatalogUrl());
            });
        }
    }

    // --- 5: a store change reaches an open Settings window at once -----------------------------------

    @Test
    void aStoreChangeReachesEveryOpenSettingsWindowWithoutAFocusChange() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                MainController other = secondWindow(fx);
                SettingsWindow a = shown(fx.controller);
                SettingsWindow b = shown(other);
                try {
                    ObservableList<RemoteConnection> sitesA = FxTestSupport.field(a, "remoteItems");
                    ObservableList<RemoteConnection> sitesB = FxTestSupport.field(b, "remoteItems");
                    ObservableList<Abbreviation> abbrevsA = FxTestSupport.field(a, "abbrevItems");

                    // What a Connect that finishes while the user is on Settings > Remote does.
                    fx.shared.putConnection(new RemoteConnection(
                            "example.org", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Example", "/srv"));
                    assertEquals(1, sitesA.size(), "shown without leaving and re-entering the window");
                    assertEquals(1, sitesB.size());

                    // The next edit on the page keeps it (a stale list wrote itself back over the store).
                    button(page(a, "REMOTE"), tr("settings.remote.add")).fire();
                    assertTrue(fx.shared.getConnections().stream().anyMatch(c -> "example.org".equals(c.host())));
                    assertEquals(2, sitesB.size(), "and the other window's page follows that edit");

                    // Define Abbreviation run in the other window.
                    Object editing = FxTestSupport.field(other, "editing");
                    FxTestSupport.call(
                            editing,
                            "addAbbreviation",
                            new Class<?>[] {String.class, String.class},
                            "btw",
                            "by the way");
                    assertTrue(
                            abbrevsA.stream().anyMatch(x -> x.getAbbreviation().equals("btw")));

                    fx.shared.removeConnection(sitesA.get(0).id());
                    assertEquals(1, sitesA.size());
                    assertEquals(1, sitesB.size());
                } finally {
                    hide(a);
                    hide(b);
                }
            });
        }
    }

    /**
     * The same change, landing while the user is in the middle of a field of that very form. Re-reading the
     * list re-selects the row and reloads the form, which used to throw away the half-typed text.
     */
    @Test
    void aStoreChangeKeepsWhatIsBeingTypedIntoTheForm() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                RemoteConnection example = new RemoteConnection(
                        "example.org", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Example", "/srv");
                fx.shared.putConnection(example);
                Object editing = FxTestSupport.field(fx.controller, "editing");
                FxTestSupport.call(
                        editing, "addAbbreviation", new Class<?>[] {String.class, String.class}, "btw", "by the way");
                SettingsWindow w = shown(fx.controller);
                try {
                    // --- Remote sites ---
                    ObservableList<RemoteConnection> sites = FxTestSupport.field(w, "remoteItems");
                    ListView<RemoteConnection> siteList = FxTestSupport.field(w, "remoteList");
                    siteList.getSelectionModel().select(0);
                    List<TextField> remote = all(
                            all(page(w, "REMOTE"), javafx.scene.layout.GridPane.class, new ArrayList<>())
                                    .get(0),
                            TextField.class,
                            new ArrayList<>());
                    TextField label = remote.get(0);
                    TextField host = remote.get(1);
                    assertEquals("example.org", host.getText(), "precondition: the form shows the selected site");
                    host.setText("staging.example.or"); // typing; not committed (no Enter, focus not moved)
                    host.positionCaret(7);

                    // A Connect that finishes elsewhere remembers another site.
                    fx.shared.putConnection(new RemoteConnection(
                            "other.org", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Other", "/"));
                    assertEquals(2, sites.size(), "the list is refreshed");
                    assertEquals(
                            "example.org",
                            siteList.getSelectionModel().getSelectedItem().host());
                    assertEquals("staging.example.or", host.getText(), "what was being typed is still there");
                    assertEquals(7, host.getCaretPosition(), "with the caret where it was");
                    assertEquals("Example", label.getText(), "and the fields that were not touched follow the store");

                    // The site being edited is itself updated elsewhere (Connect remembers its last folder):
                    // the typed text stays, and committing it keeps what the other change wrote.
                    fx.shared.putConnection(new RemoteConnection(
                            "example.org",
                            22,
                            "me",
                            RemoteConnection.AuthMethod.DEFAULT_KEYS,
                            "",
                            "Example",
                            "/var/www"));
                    assertEquals("staging.example.or", host.getText());
                    host.setText("staging.example.org");
                    host.getOnAction().handle(new javafx.event.ActionEvent());
                    RemoteConnection saved = fx.shared.getConnections().stream()
                            .filter(c -> "staging.example.org".equals(c.host()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals("/var/www", saved.lastPath(), "merged: the typed host and the newer remembered path");
                    assertTrue(fx.shared.getConnections().stream().anyMatch(c -> "other.org".equals(c.host())));

                    // Removed elsewhere: there is nothing left to attach the typed text to.
                    host.setText("gone.example.org");
                    fx.shared.removeConnection(saved.id());
                    assertFalse("gone.example.org".equals(host.getText()), "the form follows the new selection");

                    // --- Abbreviations ---
                    ObservableList<Abbreviation> abbrevs = FxTestSupport.field(w, "abbrevItems");
                    ListView<Abbreviation> abbrevList = FxTestSupport.field(w, "abbrevList");
                    abbrevList.getSelectionModel().select(0);
                    List<TextField> fields = all(
                            all(page(w, "ABBREVIATIONS"), javafx.scene.layout.GridPane.class, new ArrayList<>())
                                    .get(0),
                            TextField.class,
                            new ArrayList<>());
                    TextField expansion = fields.get(1);
                    assertEquals("by the way", expansion.getText(), "precondition");
                    expansion.setText("by the way, ");
                    expansion.positionCaret(12);

                    FxTestSupport.call(
                            editing,
                            "addAbbreviation",
                            new Class<?>[] {String.class, String.class},
                            "afaik",
                            "as far as I know");
                    assertEquals(2, abbrevs.size(), "the list is refreshed");
                    assertEquals(
                            "btw",
                            abbrevList.getSelectionModel().getSelectedItem().getAbbreviation());
                    assertEquals("by the way, ", expansion.getText(), "what was being typed is still there");
                    assertEquals(12, expansion.getCaretPosition());
                    expansion.getOnAction().handle(new javafx.event.ActionEvent());
                    assertTrue(fx.shared.getAbbreviations().stream()
                            .anyMatch(a -> a.getAbbreviation().equals("btw")
                                    && a.getExpansion().equals("by the way, ")));
                    assertTrue(
                            fx.shared.getAbbreviations().stream()
                                    .anyMatch(a -> a.getAbbreviation().equals("afaik")),
                            "and committing it does not undo the abbreviation that was added meanwhile");
                } finally {
                    hide(w);
                }
            });
        }
    }

    // --- 6: templates ------------------------------------------------------------------------------

    @Test
    void aBundledTemplateSurvivesAWhitespaceEditAndKeepsItsMultiLineDescription() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            Path dir = fx.configDir.resolve("templates");
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx.controller);
                try {
                    Region pg = page(w, "TEMPLATES");
                    @SuppressWarnings("unchecked")
                    ListView<Template> list = (ListView<Template>)
                            all(pg, ListView.class, new ArrayList<>()).get(0);
                    List<TextField> tfs = all(
                            all(pg, javafx.scene.layout.GridPane.class, new ArrayList<>())
                                    .get(0),
                            TextField.class,
                            new ArrayList<>());
                    TextField name = tfs.get(1);
                    TextField description = tfs.get(2);
                    TextField language = tfs.get(3);

                    // A bundled template (not in the user set) whose description spans two lines.
                    ObservableList<Template> items = FxTestSupport.field(w, "templateItems");
                    Template bundled = new Template(
                            "two-line",
                            "Two Line",
                            "First line\nsecond line",
                            "java",
                            "${name}.java",
                            "class X {}",
                            null);
                    items.add(bundled);
                    list.getSelectionModel().select(bundled);
                    assertEquals("Two Line", name.getText());
                    assertFalse(description.getText().contains("\n"), "the single-line field flattens what it shows");

                    name.setText("Two Line  ");
                    name.fireEvent(new ActionEvent());
                    language.setText(" java");
                    button(pg, tr("settings.save")).fire();
                    assertEquals(List.of(), names(dir), "whitespace the save trims away is not an edit");
                    java.util.Set<String> userIds = FxTestSupport.field(w, "templateUserIds");
                    assertFalse(userIds.contains("two-line"), "still a bundled template");

                    name.setText("Two Lines");
                    name.fireEvent(new ActionEvent());
                    assertEquals(List.of("two-line.json"), names(dir), "a real edit makes the user override");
                    Template saved = FxTestSupport.<com.editora.template.TemplateRegistry>field(w, "templateRegistry")
                            .userTemplates()
                            .stream()
                            .filter(t -> t.id().equals("two-line"))
                            .findFirst()
                            .orElseThrow();
                    assertEquals("Two Lines", saved.name());
                    assertEquals(
                            "First line\nsecond line", saved.description(), "the untouched description is kept whole");

                    description.setText("One line now");
                    description.fireEvent(new ActionEvent());
                    assertEquals(
                            "One line now",
                            items.get(list.getSelectionModel().getSelectedIndex())
                                    .description(),
                            "an edited description is what the user typed");
                } finally {
                    hide(w);
                }
            });
        }
    }

    // --- 7: the keymap, shown in more than one place and in more than one window ---------------------

    private static String chordOf(SettingsWindow window, String commandId) {
        SettingsWindow.ShortcutActions actions = FxTestSupport.field(window, "shortcutActions");
        return actions.rows().stream()
                .filter(r -> r.id().equals(commandId))
                .findFirst()
                .orElseThrow()
                .chord();
    }

    private static String listedChord(SettingsWindow window, String commandId) {
        Map<String, HBox> rows = FxTestSupport.field(window, "shortcutRowsById");
        return all(rows.get(commandId), Label.class, new ArrayList<>()).get(1).getText();
    }

    /** The chord label of the Macros page's key-binding row (its first child; the buttons follow). */
    private static Label macroChordLabel(SettingsWindow w) {
        return (Label) macroKeybindingRow(w).getChildren().get(0);
    }

    private static javafx.scene.layout.Pane macroKeybindingRow(SettingsWindow w) {
        MacroSettingsPane pane = FxTestSupport.field(w, "macroPane");
        return pane.keybindingRow();
    }

    /**
     * M15: the Macros note names the chords of the keymap in use. It used to say "F3 (start) and F4 (stop)"
     * under every keymap, though only the Emacs one binds them — in CUA and Sublime F3 is Find Next.
     */
    @Test
    void theMacrosNoteNamesTheChordsOfTheActiveKeymap() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx.controller);
                try {
                    Label note = FxTestSupport.field(w, "macroNote");
                    ComboBox<String> keymap = FxTestSupport.field(w, "keymapCombo");
                    keymap.setValue("emacs");
                    String start = tr("command.macro.startRecording");
                    assertTrue(note.getText().contains(chordOf(w, "macro.startRecording")), note.getText());
                    assertFalse(note.getText().contains(tr("settings.macro.note.unbound", start)));

                    keymap.setValue("vscode"); // binds no macro command
                    assertTrue(note.getText().contains(tr("settings.macro.note.unbound", start)), note.getText());
                    assertFalse(note.getText().toLowerCase().contains("f3"), note.getText());
                } finally {
                    hide(w);
                }
            });
        }
    }

    @Test
    void theMacrosKeyBindingRowFollowsTheLiveKeymap() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getMacroStore().put(new Macro("Greet", List.of(MacroStep.text("hello"))));
                FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommandsAllWindows");
                SettingsWindow w = shown(fx.controller);
                try {
                    w.refreshMacrosList();
                    @SuppressWarnings("unchecked")
                    ListView<Macro> list = (ListView<Macro>) all(page(w, "MACROS"), ListView.class, new ArrayList<>())
                            .get(0);
                    list.getSelectionModel().select(0);
                    String id =
                            MacroService.commandIdFor(fx.shared.getMacroStore().findByName("Greet"));
                    assertEquals(
                            tr("settings.shortcuts.unbound"), macroChordLabel(w).getText());

                    // Bound from the Keymaps page (or by a keymap switch) while this macro stays selected.
                    FxTestSupport.call(
                            w, "commitRecording", new Class<?>[] {String.class, String.class}, id, "C-M-S-F9");
                    String chord = chordOf(w, id);
                    assertNotNull(chord);
                    assertEquals(chord, macroChordLabel(w).getText(), "the row shows the binding made elsewhere");

                    SettingsWindow.ShortcutActions actions = FxTestSupport.field(w, "shortcutActions");
                    actions.reset(id);
                    assertEquals(
                            tr("settings.shortcuts.unbound"), macroChordLabel(w).getText());

                    // Not while the user is recording a chord in the row itself.
                    all(page(w, "MACROS"), Button.class, new ArrayList<>()).stream()
                            .filter(b -> tr("settings.shortcuts.record").equals(b.getText()))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    javafx.scene.layout.Pane row = macroKeybindingRow(w);
                    Node capture = row.getChildren().get(0);
                    assertTrue(capture instanceof TextField);
                    ComboBox<String> keymap = FxTestSupport.field(w, "keymapCombo");
                    keymap.setValue("cua".equals(keymap.getValue()) ? "emacs" : "cua");
                    assertEquals(capture, row.getChildren().get(0), "a recording in progress is left alone");
                } finally {
                    hide(w);
                }
            });
        }
    }

    @Test
    void aSecondWindowsSettingsFollowsAKeymapChangeMadeInTheFirst() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                MainController other = secondWindow(fx);
                SettingsWindow a = shown(fx.controller);
                SettingsWindow b = shown(other);
                try {
                    ComboBox<String> keymapA = FxTestSupport.field(a, "keymapCombo");
                    ComboBox<String> keymapB = FxTestSupport.field(b, "keymapCombo");
                    keymapA.setValue("emacs");
                    String emacsSave = chordOf(a, "file.save");
                    keymapA.setValue("cua");
                    String cuaSave = chordOf(a, "file.save");
                    assertNotEquals(emacsSave, cuaSave);

                    assertEquals("cua", keymapB.getValue(), "the other window's combo follows at once");
                    assertEquals(cuaSave, listedChord(b, "file.save"), "and so does its shortcut list");
                    Map<String, Label> chipsB = FxTestSupport.field(b, "chordChips");
                    String blame = chordOf(b, "git.toggleBlame");
                    assertEquals(
                            blame == null ? "" : blame,
                            chipsB.get("git.toggleBlame").getText());

                    // A single rebind made in A reaches B's list too.
                    FxTestSupport.call(
                            a, "commitRecording", new Class<?>[] {String.class, String.class}, "file.save", "C-M-S-F7");
                    assertEquals(chordOf(a, "file.save"), listedChord(b, "file.save"));
                } finally {
                    hide(a);
                    hide(b);
                }
            });
        }
    }

    // --- 9: the last two plain checkboxes are switch rows ---------------------------------------------

    @Test
    void enableAiActionsAndExpandAbbreviationsAreSwitchRows() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx.controller);
                try {
                    Settings s = fx.shared.getSettings();
                    for (String category : List.of("AI", "ABBREVIATIONS")) {
                        Region pg = page(w, category);
                        assertEquals(
                                List.of(),
                                all(pg, CheckBox.class, new ArrayList<>()),
                                category + ": no bare checkbox left on the page");
                    }

                    Region abbrev = page(w, "ABBREVIATIONS");
                    SettingSwitch abbrevSwitch =
                            all(abbrev, SettingSwitch.class, new ArrayList<>()).get(0);
                    assertEquals(tr("settings.abbrevMode"), abbrevSwitch.getAccessibleText());
                    assertTrue(
                            all(abbrev, Label.class, new ArrayList<>()).stream()
                                    .anyMatch(l -> tr("settings.abbrev.note").equals(l.getText())),
                            "the explanation is the row's description");
                    boolean was = s.isAbbrevMode();
                    abbrevSwitch.setSelected(!was);
                    assertEquals(!was, s.isAbbrevMode());
                    CheckBox abbrevCheck = FxTestSupport.field(w, "abbrevModeCheck");
                    assertEquals(!was, abbrevCheck.isSelected(), "the palette toggle still drives the same state");

                    Region ai = page(w, "AI");
                    SettingSwitch aiSwitch =
                            all(ai, SettingSwitch.class, new ArrayList<>()).get(0);
                    assertEquals(tr("settings.ai.enable"), aiSwitch.getAccessibleText());
                    CheckBox master = FxTestSupport.field(w, "aiMasterCheck");
                    master.setSelected(true);
                    assertFalse(aiSwitch.isDisabled());
                    boolean aiWas = s.isAiSupport();
                    aiSwitch.setSelected(!aiWas);
                    assertEquals(!aiWas, s.isAiSupport());
                    master.setSelected(false);
                    assertTrue(aiSwitch.isDisabled(), "still follows the master AI switch");
                    assertFalse(
                            all(ai, Node.class, new ArrayList<>()).stream()
                                    .filter(n -> n.getStyleClass().contains("settings-info-icon"))
                                    .toList()
                                    .isEmpty(),
                            "the list of what it turns on is still one hover away");
                } finally {
                    hide(w);
                }
            });
        }
    }

    // --- 8: layout that depends on the language -------------------------------------------------------

    @Test
    void theSearchPromptFitsItsFieldInEveryLanguage() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx.controller);
                try {
                    TextField search = FxTestSupport.field(w, "searchField");
                    search.getScene().getRoot().applyCss();
                    search.getScene().getRoot().layout();
                    double room = search.getWidth()
                            - search.getInsets().getLeft()
                            - search.getInsets().getRight();
                    assertTrue(room > 100, "the field was laid out: " + room);
                    for (String lang : List.of("en", "de", "es", "fr", "it", "pt")) {
                        com.editora.i18n.Messages.init(lang);
                        javafx.scene.text.Text prompt = new javafx.scene.text.Text(tr("settings.search.prompt"));
                        prompt.setFont(search.getFont());
                        double width = prompt.getLayoutBounds().getWidth();
                        // The prompt stays visible while the field has the focus, which it has when the window
                        // opens: a longer one is cut off mid-word. Some slack for a wider system font.
                        assertTrue(
                                width <= room - 16,
                                lang + ": '" + prompt.getText() + "' is " + width + "px in a " + room + "px field");
                    }
                } finally {
                    com.editora.i18n.Messages.init("en");
                    hide(w);
                }
            });
        }
    }

    @Test
    void theRemoteKeyFileFieldKeepsAUsableWidthInGermanAtTheSmallestSize() throws Exception {
        com.editora.i18n.Messages.init("de"); // the pages are built with the window, in the language of the moment
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.field(fx.controller, "settingsWindow");
            FxTestSupport.runOnFx(() -> {
                fx.shared.putConnection(new RemoteConnection(
                        "build.example.org",
                        22,
                        "deploy",
                        RemoteConnection.AuthMethod.KEY,
                        "/home/deploy/.ssh/id_ed25519",
                        "Build",
                        ""));
                shown(fx.controller);
                Stage stage = FxTestSupport.field(w, "stage");
                stage.setWidth(900);
                stage.setHeight(600);
            });
            FxTestSupport.drainFx();
            try {
                FxTestSupport.runOnFx(() -> {
                    Stage stage = FxTestSupport.field(w, "stage");
                    ListView<Object> sidebar = FxTestSupport.field(w, "sidebar");
                    sidebar.getSelectionModel()
                            .select(sidebar.getItems().stream()
                                    .filter(i -> i.toString().equals("REMOTE"))
                                    .findFirst()
                                    .orElseThrow());
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    stage.getScene().getRoot().layout();
                    Region pg = page(w, "REMOTE");
                    TextField key = all(pg, TextField.class, new ArrayList<>()).stream()
                            .filter(t -> tr("remote.keyPrompt").equals(t.getPromptText()))
                            .findFirst()
                            .orElseThrow();
                    Button browse = button(pg, tr("dialog.clone.browse"));
                    assertTrue(key.getWidth() >= 150, "beside \"Durchsuchen…\" it was about 60px: " + key.getWidth());
                    assertTrue(browse.getWidth() >= browse.prefWidth(-1) - 0.5, "and the button is not squeezed");
                    double right = pg.localToScene(pg.getBoundsInLocal()).getMaxX();
                    assertTrue(
                            key.localToScene(key.getBoundsInLocal()).getMaxX() <= right + 0.5
                                    && browse.localToScene(browse.getBoundsInLocal())
                                                    .getMaxX()
                                            <= right + 0.5,
                            "nothing runs off the page");
                });
            } finally {
                FxTestSupport.runOnFx(() -> hide(w));
            }
        } finally {
            com.editora.i18n.Messages.init("en");
        }
    }
}
