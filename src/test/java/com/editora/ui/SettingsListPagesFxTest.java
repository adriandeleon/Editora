package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import com.editora.config.Abbreviation;
import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import com.editora.snippet.SnippetManager;
import com.editora.template.Template;
import com.editora.template.TemplateRegistry;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static com.editora.ui.SettingsShortcutsFxTest.all;
import static com.editora.ui.SettingsShortcutsFxTest.button;
import static com.editora.ui.SettingsShortcutsFxTest.setField;
import static com.editora.ui.SettingsShortcutsFxTest.shown;
import static com.editora.ui.SettingsShortcutsFxTest.stage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snippets, Templates, Abbreviations and Macros in a real Settings window: what the user edits is what is kept. */
@Tag("fx")
class SettingsListPagesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static Region page(SettingsWindow w, String category) {
        java.util.Map<?, Region> pages = FxTestSupport.field(w, "pages");
        return pages.entrySet().stream()
                .filter(e -> e.getKey().toString().equals(category))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    /** Answers every loss question ("Delete …?") with {@code yes} and records what was asked. */
    static List<String> answer(SettingsWindow w, boolean yes) {
        List<String> asked = new ArrayList<>();
        setField(w, "confirmLoss", (BiPredicate<String, String>) (title, body) -> asked.add(body) && yes);
        return asked;
    }

    /** Waits (polling, up to 5 s) for {@code condition} to hold on the FX thread. */
    static void waitFor(Callable<Boolean> condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!FxTestSupport.callOnFx(condition)) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out: " + what);
            }
            Thread.sleep(20);
        }
    }

    // --- C17: no Save buttons; the pages apply as you type -------------------------------------------

    @Test
    void theListPagesHaveNoSaveButtonAndApplyAPausedEdit() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "SNIPPETS"));
            FxTestSupport.runOnFx(() -> {
                for (String p : List.of("SNIPPETS", "TEMPLATES", "ABBREVIATIONS")) {
                    assertTrue(
                            all(page(w, p), Button.class, new ArrayList<>()).stream()
                                    .noneMatch(b -> tr("settings.save").equals(b.getText())),
                            p + " has no Save button");
                }
                Region pg = page(w, "SNIPPETS");
                button(pg, tr("settings.snippet.add")).fire();
                CodeArea body = all(pg, CodeArea.class, new ArrayList<>()).get(0);
                body.requestFocus(); // the user is typing in the body
                body.replaceText("TYPED BODY"); // no Enter, no focus change
            });
            SnippetManager manager = FxTestSupport.field(w, "snippetManager");
            waitFor(
                    () -> manager.userSnippets("global").stream()
                            .anyMatch(s -> s.body().equals("TYPED BODY")),
                    "the body is saved once typing pauses");
            FxTestSupport.runOnFx(() -> stage(w).hide());
        }
    }

    // --- C7: Delete asks; Cancel keeps -----------------------------------------------------------------

    @Test
    void removingASnippetOrAnAbbreviationAsksFirst() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "SNIPPETS"));
            SnippetManager manager = FxTestSupport.field(w, "snippetManager");
            FxTestSupport.runOnFx(() -> {
                Region pg = page(w, "SNIPPETS");
                button(pg, tr("settings.snippet.add")).fire();
                String created = manager.userSnippets("global").get(0).name();
                List<String> asked = answer(w, false);
                button(pg, tr("settings.snippet.remove")).fire();
                assertEquals(List.of(tr("settings.lists.deleteConfirm", created)), asked);
                assertEquals(1, manager.userSnippets("global").size(), "Cancel keeps the snippet");
                answer(w, true);
                button(pg, tr("settings.snippet.remove")).fire();
                assertEquals(0, manager.userSnippets("global").size());

                fx.shared.setAbbreviations(new ArrayList<>(List.of(new Abbreviation("btw", "by the way"))));
                fx.shared.saveAbbreviations();
                AbbreviationsEditor ed = FxTestSupport.field(w, "abbreviationsEditor");
                ed.list.getSelectionModel().select(0);
                List<String> askedAbbrev = answer(w, false);
                button(page(w, "ABBREVIATIONS"), tr("settings.abbrev.remove")).fire();
                assertEquals(List.of(tr("settings.lists.deleteConfirm", "btw")), askedAbbrev);
                assertEquals(1, fx.shared.getAbbreviations().size(), "Cancel keeps the abbreviation");
                stage(w).hide();
            });
        }
    }

    // --- C12: abbreviation keys --------------------------------------------------------------------------

    @Test
    void anAbbreviationKeyIsNeverStoredBlankNorRepeated() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "ABBREVIATIONS"));
            FxTestSupport.runOnFx(() -> {
                fx.shared.setAbbreviations(new ArrayList<>(List.of(new Abbreviation("teh", "the"))));
                fx.shared.saveAbbreviations();
                AbbreviationsEditor ed = FxTestSupport.field(w, "abbreviationsEditor");
                Region pg = page(w, "ABBREVIATIONS");
                button(pg, tr("settings.abbrev.add")).fire();
                assertEquals(1, fx.shared.getAbbreviations().size(), "an empty Add is not stored");

                ed.abbrev.setText("TEH"); // the same key: expansion ignores case
                int warned = SettingsShortcutsFxTest.dismissingDialogs(() -> ed.abbrev.fireEvent(new ActionEvent()));
                assertEquals(1, warned, "the user is told");
                assertEquals("", ed.abbrev.getText(), "the key is put back");
                assertEquals(1, fx.shared.getAbbreviations().size());
                assertEquals("the", fx.shared.abbreviationMap().get("teh"), "the first one is untouched");

                ed.abbrev.setText("omw");
                ed.expansion.setText("on my way");
                ed.abbrev.fireEvent(new ActionEvent());
                assertEquals("on my way", fx.shared.abbreviationMap().get("omw"));
                stage(w).hide();
            });
        }
    }

    // --- C8: a blank template id keeps the other edits ---------------------------------------------------

    @Test
    void aBlankTemplateIdStillSavesTheRestOfTheForm() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            Path dir = fx.configDir.resolve("templates");
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "TEMPLATES"));
            FxTestSupport.runOnFx(() -> {
                Region pg = page(w, "TEMPLATES");
                button(pg, tr("settings.template.add")).fire();
                List<TextField> tfs = all(
                        all(pg, javafx.scene.layout.GridPane.class, new ArrayList<>())
                                .get(0),
                        TextField.class,
                        new ArrayList<>());
                TextField id = tfs.get(0);
                String created = id.getText();
                CodeArea body = all(pg, CodeArea.class, new ArrayList<>()).get(0);
                id.setText("");
                body.replaceText("IMPORTANT TEXT");
                tfs.get(1).fireEvent(new ActionEvent()); // Enter in Name
                assertEquals(created, id.getText(), "the id is put back");
                TemplateRegistry registry = FxTestSupport.field(w, "templateRegistry");
                Template saved = registry.userTemplates().stream()
                        .filter(t -> t.id().equals(created))
                        .findFirst()
                        .orElseThrow();
                assertEquals("IMPORTANT TEXT", saved.body(), "the body was saved, not dropped");
                stage(w).hide();
            });
            assertTrue(Files.readString(dir.resolve("new-template.json")).contains("IMPORTANT TEXT"));
        }
    }

    // --- C13: two macros of one name ---------------------------------------------------------------------

    @Test
    void aMacroCannotTakeAnotherMacrosName() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "MACROS"));
            FxTestSupport.runOnFx(() -> {
                fx.shared
                        .getMacroStore()
                        .put(new Macro("m1", "Wrap Line", new ArrayList<>(List.of(MacroStep.text("a")))));
                fx.shared.getMacroStore().put(new Macro("m2", "Other", new ArrayList<>(List.of(MacroStep.text("b")))));
                w.refreshMacrosList();
                MacroSettingsPane pane = FxTestSupport.field(w, "macroPane");
                pane.macroList()
                        .getSelectionModel()
                        .select(pane.macroList().getItems().stream()
                                .filter(m -> m.id().equals("m2"))
                                .findFirst()
                                .orElseThrow());
                TextField name = all(pane, TextField.class, new ArrayList<>()).get(0);
                name.setText("wrap line");
                Label clash = all(pane, Label.class, new ArrayList<>()).stream()
                        .filter(l -> l.getText().equals(tr("settings.macro.nameExists", "Wrap Line")))
                        .findFirst()
                        .orElse(null);
                assertNotNull(clash, "the clash is said while typing");
                assertTrue(clash.isVisible());
                button(pane, tr("settings.save")).fire();
                assertEquals("Other", fx.shared.getMacroStore().findById("m2").name(), "the name is not taken");
                stage(w).hide();
            });
        }
    }

    // --- C14: every language, and triggers Tab cannot expand ----------------------------------------------

    @Test
    void aSnippetCanBeAddedForALanguageWithNoneYetAndASpacedTriggerIsPointedOut() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "SNIPPETS"));
            SnippetManager manager = FxTestSupport.field(w, "snippetManager");
            FxTestSupport.runOnFx(() -> {
                Region pg = page(w, "SNIPPETS");
                @SuppressWarnings("unchecked")
                ComboBox<String> lang = (ComboBox<String>)
                        all(pg, ComboBox.class, new ArrayList<>()).get(0);
                String none = lang.getItems().stream()
                        .filter(l -> !manager.languagesWithSnippets().contains(l))
                        .findFirst()
                        .orElse(null);
                assertNotNull(none, "a language without snippets is offered: " + lang.getItems());
                lang.setValue(none);
                button(pg, tr("settings.snippet.add")).fire();
                assertEquals(1, manager.userSnippets(none).size());

                List<TextField> tfs = all(pg, TextField.class, new ArrayList<>());
                tfs.get(2).setText("my trig");
                tfs.get(2).fireEvent(new ActionEvent());
                assertTrue(
                        all(pg, Label.class, new ArrayList<>()).stream()
                                .anyMatch(l -> l.isVisible()
                                        && l.getText().contains(tr("settings.snippet.triggerSpace", "my trig"))),
                        "Tab cannot expand a trigger with a space: said");
                stage(w).hide();
            });
        }
    }

    // --- C15: body editors follow the keymap -----------------------------------------------------------------

    @Test
    void underCuaCtrlAInASnippetBodyIsNotEmacsLineStart() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                com.editora.command.KeymapManager.isMac(), "select all is Cmd+A there");
        try (var fx = SettingsShortcutsFxTest.cua()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "SNIPPETS"));
            FxTestSupport.runOnFx(() -> {
                Region pg = page(w, "SNIPPETS");
                button(pg, tr("settings.snippet.add")).fire();
                CodeArea body = all(pg, CodeArea.class, new ArrayList<>()).get(0);
                body.replaceText("one\ntwo");
                body.moveTo(body.getLength());
                body.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, true, false, false));
                assertEquals("one\ntwo", body.getSelectedText(), "Ctrl+A selects all, as everywhere under CUA");
                stage(w).hide();
            });
        }
    }

    // --- C16: the shortcut list uses the window's height ---------------------------------------------------

    @Test
    void theShortcutListGrowsWithTheWindow() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "KEYMAPS"));
            FxTestSupport.runOnFx(() -> {
                ScrollPane scroll = FxTestSupport.field(w, "shortcutScroll");
                stage(w).setHeight(760);
                double small = scroll.getPrefHeight();
                stage(w).setHeight(1000);
                assertTrue(scroll.getPrefHeight() >= small + 200, small + " → " + scroll.getPrefHeight());
                assertFalse(scroll.getPrefHeight() < 320);
                stage(w).hide();
            });
        }
    }
}
