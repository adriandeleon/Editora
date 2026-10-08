package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Abbreviation;
import com.editora.editor.EditorBuffer;
import com.editora.externaltool.ExternalTool;
import com.editora.snippet.Snippet;
import com.editora.snippet.SnippetManager;
import com.editora.template.Template;
import com.editora.template.TemplateRegistry;
import com.editora.todo.TodoPattern;
import com.editora.vfs.RemoteConnection;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The list-and-form editors hosted in Settings must never lose or overwrite what the user saved. */
@Tag("fx")
class SettingsListEditorsFxTest {

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

    private static List<TextField> fields(Region page) {
        return all(page, TextField.class, new ArrayList<>());
    }

    /**
     * Runs {@code action}, closing any warning dialog it opens (a modal dialog would otherwise block the FX
     * thread forever), and returns how many dialogs were closed.
     */
    private static int dismissingDialogs(Runnable action) {
        AtomicInteger closed = new AtomicInteger();
        Platform.runLater(() -> {
            for (Window win : List.copyOf(Window.getWindows())) {
                if (win.getScene() != null && win.getScene().getRoot() instanceof DialogPane && win.isShowing()) {
                    closed.incrementAndGet();
                    win.hide();
                }
            }
        });
        action.run();
        return closed.get();
    }

    private static SettingsWindow shown(FxWindowFixture fx) {
        SettingsWindow w = FxTestSupport.field(fx.controller, "settingsWindow");
        Stage owner = new Stage();
        owner.setWidth(1400);
        owner.setHeight(900);
        w.show(owner);
        return w;
    }

    private static List<String> names(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var st = Files.list(dir)) {
            return st.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void storesChangedWhileSettingsWasClosedAreShownAndSurviveTheNextEdit() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx);
                Stage stage = FxTestSupport.field(w, "stage");
                stage.hide();

                Object editing = FxTestSupport.field(fx.controller, "editing");
                FxTestSupport.call(
                        editing, "addAbbreviation", new Class<?>[] {String.class, String.class}, "btw", "by the way");
                fx.shared.putConnection(new RemoteConnection(
                        "example.org", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Example", "/srv"));

                shown(fx);
                ObservableList<Abbreviation> abbrevs = FxTestSupport.field(w, "abbrevItems");
                ObservableList<RemoteConnection> sites = FxTestSupport.field(w, "remoteItems");
                assertEquals(1, abbrevs.size(), "the page shows the abbreviation defined from the editor");
                assertEquals(1, sites.size(), "the page shows the site Connect remembered");

                button(page(w, "ABBREVIATIONS"), tr("settings.abbrev.add")).fire();
                button(page(w, "REMOTE"), tr("settings.remote.add")).fire();
                assertTrue(
                        fx.shared.getAbbreviations().stream()
                                .anyMatch(a -> a.getAbbreviation().equals("btw")),
                        "an edit must not delete an abbreviation it never showed");
                assertTrue(
                        fx.shared.getConnections().stream().anyMatch(c -> "example.org".equals(c.host())),
                        "an edit must not delete a saved site it never showed");
                assertEquals(2, fx.shared.getConnections().size());

                // While the window stays open: a define-abbreviation syncs it, and so does getting focus back.
                FxTestSupport.call(
                        editing, "addAbbreviation", new Class<?>[] {String.class, String.class}, "omw", "on my way");
                assertTrue(abbrevs.stream().anyMatch(a -> a.getAbbreviation().equals("omw")));
                fx.shared.putConnection(new RemoteConnection(
                        "second.example", 22, "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "Second", ""));
                FxTestSupport.invoke(w, "reloadStoreBackedEditors"); // what regaining focus runs
                assertEquals(3, sites.size());
                stage.hide();
            });
        }
    }

    @Test
    void anAbbreviationSavedInSettingsReachesTheBuffersThatAreAlreadyOpen() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent("x\n");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                return b;
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx);
                Region pg = page(w, "ABBREVIATIONS");
                button(pg, tr("settings.abbrev.add")).fire();
                List<TextField> tfs = fields(pg);
                tfs.get(0).setText("omw");
                tfs.get(1).setText("on my way");
                button(pg, tr("settings.save")).fire();
                assertEquals(Map.of("omw", "on my way"), fx.shared.abbreviationMap());
                assertEquals(
                        Map.of("omw", "on my way"),
                        FxTestSupport.<Map<String, String>>field(buffer, "abbrevTable"),
                        "the open buffer expands the new abbreviation without another setting changing");

                button(pg, tr("settings.abbrev.remove")).fire();
                assertEquals(Map.of(), FxTestSupport.<Map<String, String>>field(buffer, "abbrevTable"));
                FxTestSupport.<Stage>field(w, "stage").hide();
            });
        }
    }

    @Test
    void closingSettingsKeepsWhatWasTypedInTheFieldThatStillHasTheFocus() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx);
                w.showAbbreviations(FxTestSupport.<Stage>field(w, "stage").getOwner()); // the page is on screen
                Region pg = page(w, "ABBREVIATIONS");
                button(pg, tr("settings.abbrev.add")).fire();
                List<TextField> tfs = fields(pg);
                tfs.get(0).setText("omw");
                tfs.get(1).requestFocus();
                assertTrue(tfs.get(1).isFocused());
                tfs.get(1).setText("on my way"); // no Enter, no Save, no click elsewhere
                FxTestSupport.<Stage>field(w, "stage").close();
                assertEquals(Map.of("omw", "on my way"), fx.shared.abbreviationMap());
            });
        }
    }

    @Test
    void aTemplateIsNotOverriddenByAFocusLossNorOverwrittenByARename() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            Path dir = fx.configDir.resolve("templates");
            FxTestSupport.runOnFx(() -> {
                try {
                    SettingsWindow w = shown(fx);
                    Region pg = page(w, "TEMPLATES");
                    @SuppressWarnings("unchecked")
                    ListView<Template> list = (ListView<Template>)
                            all(pg, ListView.class, new ArrayList<>()).get(0);
                    // The page's first text field is the author name; the editor's own fields are in its form.
                    List<TextField> tfs = fields(all(pg, javafx.scene.layout.GridPane.class, new ArrayList<>())
                            .get(0));
                    TextField id = tfs.get(0);
                    TextField name = tfs.get(1);
                    CodeArea body = all(pg, CodeArea.class, new ArrayList<>()).get(0);
                    int single = -1;
                    for (int i = 0; i < list.getItems().size(); i++) {
                        if (!list.getItems().get(i).isMultiFile()) {
                            single = i;
                            break;
                        }
                    }
                    list.getSelectionModel().select(single);
                    String bundledId = list.getItems().get(single).id();

                    // Enter, Save and a focus loss without an edit: still the bundled template.
                    name.fireEvent(new ActionEvent());
                    button(pg, tr("settings.save")).fire();
                    name.requestFocus();
                    list.requestFocus();
                    assertEquals(List.of(), names(dir), "no user override without an edit");

                    TemplateRegistry registry = FxTestSupport.field(w, "templateRegistry");
                    button(pg, tr("settings.template.add")).fire();
                    id.setText("mine-a");
                    body.replaceText("BODY A");
                    button(pg, tr("settings.save")).fire();
                    button(pg, tr("settings.template.add")).fire();
                    body.replaceText("BODY B");
                    String secondId = id.getText();
                    id.setText("mine-a");
                    assertEquals(
                            1,
                            dismissingDialogs(
                                    () -> button(pg, tr("settings.save")).fire()));
                    assertEquals(secondId, id.getText(), "the id field is put back");
                    assertEquals(
                            "BODY A",
                            registry.userTemplates().stream()
                                    .filter(t -> t.id().equals("mine-a"))
                                    .findFirst()
                                    .orElseThrow()
                                    .body(),
                            "the first template keeps its body");
                    assertEquals(
                            1,
                            list.getItems().stream()
                                    .filter(t -> t.id().equals("mine-a"))
                                    .count());

                    // A bundled id is refused too (it would silently shadow the shipped template)…
                    id.setText(bundledId);
                    assertEquals(1, dismissingDialogs(() -> id.fireEvent(new ActionEvent())));
                    assertFalse(names(dir).contains(bundledId + ".json"));
                    // …and so is an id that is not a plain file name.
                    id.setText("../escaped");
                    assertEquals(1, dismissingDialogs(() -> id.fireEvent(new ActionEvent())));
                    assertFalse(Files.exists(fx.configDir.resolve("escaped.json")));
                    assertEquals(List.of("mine-a.json", secondId + ".json"), names(dir));
                    // The edit that came with the refused id is still saved once the id is valid.
                    button(pg, tr("settings.save")).fire();
                    assertTrue(Files.readString(dir.resolve(secondId + ".json")).contains("BODY B"));
                    FxTestSupport.<Stage>field(w, "stage").hide();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    void snippetsKeepDistinctNamesAndAreNeverDroppedByABlankOrUneditedForm() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            Path dir = fx.configDir.resolve("snippets");
            FxTestSupport.runOnFx(() -> {
                try {
                    SettingsWindow w = shown(fx);
                    Region pg = page(w, "SNIPPETS");
                    SnippetManager manager = FxTestSupport.field(w, "snippetManager");
                    @SuppressWarnings("unchecked")
                    ListView<Snippet> list = (ListView<Snippet>)
                            all(pg, ListView.class, new ArrayList<>()).get(0);
                    @SuppressWarnings("unchecked")
                    ComboBox<String> lang = (ComboBox<String>)
                            all(pg, ComboBox.class, new ArrayList<>()).get(0);
                    List<TextField> tfs = fields(pg);
                    TextField name = tfs.get(0);
                    TextField prefix = tfs.get(1);
                    CodeArea body = all(pg, CodeArea.class, new ArrayList<>()).get(0);
                    Button add = button(pg, tr("settings.snippet.add"));
                    Button save = button(pg, tr("settings.save"));

                    // A bundled snippet whose description spans lines: Enter without an edit writes nothing.
                    lang.setValue("cpp");
                    for (int i = 0; i < list.getItems().size(); i++) {
                        list.getSelectionModel().select(i);
                        name.fireEvent(new ActionEvent());
                    }
                    assertEquals(List.of(), names(dir), "visiting bundled snippets must not create a user file");
                    lang.setValue("global");

                    // Add twice without renaming the first: two names, two entries on disk.
                    add.fire();
                    prefix.setText("first");
                    body.replaceText("FIRST BODY");
                    save.fire();
                    add.fire();
                    List<Snippet> user = manager.userSnippets("global");
                    assertEquals(2, user.size(), "both new snippets are on disk: " + user);
                    assertTrue(user.stream().anyMatch(s -> s.body().equals("FIRST BODY")));
                    assertNotEquals(user.get(0).name(), user.get(1).name());

                    // Renaming the second to the first's name is refused.
                    String firstName = user.get(0).name();
                    String secondName = name.getText();
                    name.setText(firstName);
                    body.replaceText("SECOND BODY");
                    assertEquals(1, dismissingDialogs(save::fire));
                    assertEquals(secondName, name.getText(), "the name field is put back");
                    user = manager.userSnippets("global");
                    assertEquals(2, user.size());
                    assertTrue(user.stream().anyMatch(s -> s.body().equals("FIRST BODY")), "first survives: " + user);

                    // Clearing the name keeps the snippet (and its name) instead of deleting it.
                    name.setText("");
                    name.fireEvent(new ActionEvent());
                    assertEquals(secondName, name.getText());
                    user = manager.userSnippets("global");
                    assertTrue(
                            user.stream()
                                    .anyMatch(s -> s.name().equals(secondName)
                                            && s.body().equals("SECOND BODY")),
                            "the snippet is still saved: " + user);
                    FxTestSupport.<Stage>field(w, "stage").hide();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    void anExternalToolKeepsOneCommandPerRowAndItsKeyBindingFollowsARenameOrRemoval() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx));
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            KeymapManager keymap = FxTestSupport.field(fx.controller, "keymap");
            FxTestSupport.runOnFx(() -> {
                Region pg = page(w, "EXTERNAL_TOOLS");
                Button add = button(pg, tr("settings.externalTool.add"));
                add.fire();
                add.fire();
                List<ExternalTool> tools = fx.shared.getSettings().getExternalTools();
                assertEquals(2, tools.size());
                assertNotEquals(
                        ExternalTool.commandIdFor(tools.get(0).getName()),
                        ExternalTool.commandIdFor(tools.get(1).getName()),
                        "each added tool has its own command id");
                // The second row can be edited straight away (it used to be refused as a duplicate id).
                List<TextField> tfs = fields(pg);
                tfs.get(1).setText("echo");
                assertEquals(
                        0,
                        dismissingDialogs(() -> button(pg, tr("settings.save")).fire()));
                assertEquals(
                        "echo",
                        fx.shared.getSettings().getExternalTools().get(1).getCommand());
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        2,
                        registry.all().stream()
                                .filter(c -> c.id().startsWith("externalTool.run."))
                                .count());
                Region pg = page(w, "EXTERNAL_TOOLS");
                ListView<ExternalTool> list = FxTestSupport.field(w, "externalToolList");
                list.getSelectionModel().select(0);
                List<TextField> tfs = fields(pg);
                tfs.get(0).setText("Fmt");
                button(pg, tr("settings.save")).fire();
                SettingsWindow.ShortcutActions actions = FxTestSupport.field(w, "shortcutActions");
                actions.rebind("externalTool.run.fmt", "C-M-9");
                assertEquals("externalTool.run.fmt", keymap.commandFor("C-M-9"));

                tfs.get(0).setText("Format JSON");
                button(pg, tr("settings.save")).fire();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        "externalTool.run.format-json",
                        keymap.commandFor("C-M-9"),
                        "the chord follows the tool to its new command id");
                assertFalse(
                        fx.shared
                                .getSettings()
                                .keybindingsFor(KeymapManager.isMac())
                                .containsValue("externalTool.run.fmt"),
                        "no override is left on the id nothing registers");
                ListView<ExternalTool> list = FxTestSupport.field(w, "externalToolList");
                list.getSelectionModel().select(0);
                button(page(w, "EXTERNAL_TOOLS"), tr("settings.externalTool.remove"))
                        .fire();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertNull(keymap.commandFor("C-M-9"), "removing the tool frees its chord");
                assertFalse(fx.shared
                        .getSettings()
                        .keybindingsFor(KeymapManager.isMac())
                        .containsValue("externalTool.run.format-json"));
                FxTestSupport.<Stage>field(w, "stage").hide();
            });
        }
    }

    @Test
    void theGitCommandFieldStaysEditableWhileGitIsOff() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setGitSupport(false);
                fx.shared.getSettings().setGitPath("/nonexistent/bin/git");
                SettingsWindow w = shown(fx);
                TextField path = FxTestSupport.field(w, "gitPathField");
                CheckBox git = FxTestSupport.field(w, "gitCheck");
                assertFalse(git.isSelected());
                assertFalse(path.isDisable(), "the path is how a failed detection gets fixed");
                FxTestSupport.<Stage>field(w, "stage").hide();
            });
        }
    }

    @Test
    void aMalformedTodoPatternIsFlaggedAndNotSaved() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx);
                javafx.scene.layout.VBox rows = FxTestSupport.field(w, "todoPatternsBox");
                List<TextField> tfs = all(rows.getChildren().get(0), TextField.class, new ArrayList<>());
                TextField name = tfs.get(0);
                TextField regex = tfs.get(1);
                String before = fx.shared.getSettings().getTodoPatterns().get(0).getPattern();
                assertEquals(before, regex.getText());

                regex.setText("[TODO");
                name.setText("RENAMED");
                regex.fireEvent(new ActionEvent());
                TodoPattern stored = fx.shared.getSettings().getTodoPatterns().get(0);
                assertEquals(before, stored.getPattern(), "a pattern that cannot compile is not saved");
                assertEquals("RENAMED", stored.getName(), "the row's other fields still save");
                assertTrue(regex.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
                assertTrue(regex.getTooltip() != null);

                regex.setText("\\bTODO:");
                regex.fireEvent(new ActionEvent());
                assertEquals(
                        "\\bTODO:",
                        fx.shared.getSettings().getTodoPatterns().get(0).getPattern());
                assertFalse(regex.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
                FxTestSupport.<Stage>field(w, "stage").hide();
            });
        }
    }
}
