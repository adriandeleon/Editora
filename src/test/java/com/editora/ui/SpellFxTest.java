package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.BufferSpell;
import com.editora.editor.EditorBuffer;
import com.editora.editor.SpellDictionaries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spell check through a real window: which files and which words are checked, the context-menu items, the
 * keyboard commands, the per-file dictionary, the personal dictionary, and the two caches that keep typing
 * and scrolling cheap. Each test names the review finding it pins.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpellFxTest {

    private FxWindowFixture fx;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        Stage stage = FxTestSupport.field(fx.controller, "stage");
        FxTestSupport.runOnFx(() -> {
            stage.setWidth(1000);
            stage.setHeight(700);
            stage.show();
        });
        open("warmup.txt", "warm up the dictionary\n");
        for (int i = 0; i < 200 && SpellDictionaries.ifReady("en_US").isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertTrue(SpellDictionaries.ifReady("en_US").isPresent(), "the en_US dictionary builds");
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(150);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    private EditorBuffer open(String name, String content) throws Exception {
        Path dir = fx.configDir.resolve("f" + (files++));
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        for (int i = 0; i < 100; i++) {
            settle();
            EditorBuffer b = active();
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                settle();
                settle(); // highlighting, which decides what counts as a comment or a tag
                return b;
            }
        }
        throw new IllegalStateException("did not open " + file);
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
    }

    private void select(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(b.getPath(), 0));
        settle();
    }

    private void run(String commandId) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registry.run(commandId);
        });
        settle();
    }

    private static Object overlay(EditorBuffer b) {
        return FxTestSupport.field(b, "spellOverlay");
    }

    /** The words the overlay underlines, over the whole document. */
    @SuppressWarnings("unchecked")
    private static List<String> squiggled(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            if (!b.spell().isActive()) {
                return out;
            }
            for (int p = 0; p < b.getArea().getParagraphs().size(); p++) {
                List<int[]> hits =
                        (List<int[]>) FxTestSupport.call(overlay(b), "misspellingsIn", new Class[] {int.class}, p);
                for (int[] h : hits) {
                    out.add(b.getArea().getText(h[0], h[1]));
                }
            }
            return out;
        });
    }

    private static BufferSpell.Hit hitOn(EditorBuffer b, String needle) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            int at = b.getArea().getText().indexOf(needle);
            assertTrue(at >= 0, needle + " is in the document");
            return b.spell().hitAt(at + 1);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<MenuItem> menuAt(EditorBuffer b, String needle, ObservableList<MenuItem> menu) {
        int at = b.getArea().getText().indexOf(needle);
        List<MenuItem> items = (List<MenuItem>)
                FxTestSupport.call(b.spell(), "menuItems", new Class[] {int.class, ObservableList.class}, at + 1, menu);
        menu.setAll(items);
        return items;
    }

    /**
     * Returns once the suggestion search the last menu started has finished and its rows are in the menu.
     * The searches run one at a time on a single worker, so a task queued behind the search ends after it —
     * and after the search has posted its rows to the FX thread, which the drain then lets through.
     */
    private static void awaitSuggestions() throws Exception {
        java.lang.reflect.Field worker = BufferSpell.class.getDeclaredField("SUGGESTER");
        worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> {}).get(30, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
    }

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElse(null);
    }

    private Settings settings() {
        return fx.shared.getSettings();
    }

    private String status() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });
    }

    // ------------------------------------------------------------------- S13: which files are checked

    @Test
    void dataAndConfigurationFormatsAreOffByDefaultAndProseAndCodeAreOn() throws Exception {
        assertTrue(settings().isSpellCheck(), "the master switch is on out of the box");
        EditorBuffer json =
                open("package.json", "{\n  \"name\": \"myapp\",\n  \"description\": \"A smal exampel\"\n}\n");
        assertFalse(FxTestSupport.callOnFx(() -> json.spell().isActive()), "JSON is not spell-checked by default");
        assertTrue(squiggled(json).isEmpty());
        for (String[] off : new String[][] {
            {"ci.yaml", "name: bild # runn the bild\n"},
            {"app.properties", "# commment line\ngreeting=Helo wrld\n"},
            {"conf.toml", "title = \"Helo wrld\" # commment\n"},
            {"data.csv", "nme,valu\nhelo,wrld\n"},
            {"pom.xml", "<!-- commment --><project><name>Helo</name></project>\n"},
            {"app.ini", "; commment\nkey=valu\n"},
        }) {
            EditorBuffer b = open(off[0], off[1]);
            assertFalse(FxTestSupport.callOnFx(() -> b.spell().isActive()), off[0] + " is off by default");
        }
        EditorBuffer txt = open("notes.txt", "Plain paragraf here\n");
        assertEquals(List.of("paragraf"), squiggled(txt));
        EditorBuffer java =
                open("A.java", "// a coment here\nclass A { String s = \"a mispeled strng\"; int valu; }\n");
        assertEquals(List.of("coment", "mispeled", "strng"), squiggled(java), "code: comments and strings only");
    }

    @Test
    void theFileTypeSwitchTurnsALanguageOnAndOffEverywhere() throws Exception {
        EditorBuffer json = open("tsconfig.json", "{ \"description\": \"A smal exampel\" }\n");
        assertTrue(squiggled(json).isEmpty());
        run("spell.toggleForLanguage");
        assertFalse(settings().getSpellDisabledLanguages().contains("json"));
        assertEquals(List.of("smal", "exampel"), squiggled(json), "on for JSON: its strings are checked");
        assertTrue(status().contains("json"), status());
        run("spell.toggleForLanguage");
        assertTrue(settings().getSpellDisabledLanguages().contains("json"));
        assertTrue(squiggled(json).isEmpty());

        // The same list from the Settings page's tick box.
        EditorBuffer md = open("page.md", "A paragraf.\n");
        assertEquals(List.of("paragraf"), squiggled(md));
        SpellFileTypesEditor editor = FxTestSupport.callOnFx(() -> new SpellFileTypesEditor(this::settings, () -> {
            EditorSettingsCoordinator es = FxTestSupport.field(fx.controller, "editorSettings");
            es.applyViewSettingsToAllBuffers(settings());
        }));
        assertTrue(FxTestSupport.callOnFx(() -> editor.isTicked("markdown")));
        assertFalse(FxTestSupport.callOnFx(() -> editor.isTicked("yaml")), "data formats start unticked");
        FxTestSupport.runOnFx(() -> editor.setTicked("markdown", false));
        settle();
        assertTrue(settings().getSpellDisabledLanguages().contains("markdown"));
        assertTrue(squiggled(md).isEmpty(), "unticked: Markdown is no longer checked");
        FxTestSupport.runOnFx(() -> editor.setTicked("markdown", true));
        settle();
        assertEquals(List.of("paragraf"), squiggled(md));
        // A language set by hand follows the list too.
        FxTestSupport.runOnFx(() -> md.setLanguageOverride("yaml"));
        settle();
        assertFalse(FxTestSupport.callOnFx(() -> md.spell().isActive()), "now a YAML buffer: off");
    }

    @Test
    void htmlAndTypstHaveTheirTextCheckedNotTheirMarkup() throws Exception {
        EditorBuffer html = open(
                "index.html",
                "<!-- a commment -->\n<html><head><title>Titel</title>\n<style>\n.contaner { colr: red; }\n</style>\n"
                        + "<script>\nvar helo = fooo(\"strng\"); // coment\n</script>\n</head>\n"
                        + "<body class=\"contaner mx-auto\"><p>Paragraf with <b>mispeled</b> text &amp; more</p>\n"
                        + "<a href=\"/abuot\">Abuot</a>\n<pre>\nxzf blokk\n</pre>\n</body></html>\n");
        List<String> flagged = squiggled(html);
        assertTrue(
                flagged.containsAll(List.of("commment", "Titel", "Paragraf", "mispeled", "Abuot")), flagged.toString());
        for (String markup :
                List.of("contaner", "colr", "helo", "fooo", "strng", "coment", "abuot", "xzf", "blokk", "amp")) {
            assertFalse(flagged.contains(markup), markup + " is markup or code, not text: " + flagged);
        }

        EditorBuffer typ = open(
                "doc.typ",
                "= Titel\n\nThis Typst paragraf is *boldd* prose with a refrence @labl <lbl>. // coment\n"
                        + "#let valu = 1\n#set text(font: \"Libertinus\")\n$ a + b = cee $\n`rawcode` here\n- itemm one\n");
        List<String> typst = squiggled(typ);
        assertTrue(
                typst.containsAll(List.of("Titel", "paragraf", "boldd", "refrence", "coment", "itemm")),
                typst.toString());
        for (String code : List.of("labl", "lbl", "valu", "Libertinus", "cee", "rawcode")) {
            assertFalse(typst.contains(code), code + " is Typst code, not prose: " + typst);
        }
    }

    // ----------------------------------------------------------------- S6 / S3 / S7 through a document

    @Test
    void markdownEmphasisSpanishMarksAndForeignScripts() throws Exception {
        EditorBuffer md = open(
                "prose.md",
                "An _emphasised mispeled_ word and __boldd__ and ~~strikke~~.\n\n¿Qeu pasa? ¡Holaa!\n\n"
                        + "这是一个测试 Привет café don’t\n\n```bash\nsudo xzf mispeledinfence\n```\n");
        List<String> flagged = squiggled(md);
        assertTrue(flagged.containsAll(List.of("mispeled", "boldd", "strikke", "Qeu", "Holaa")), flagged.toString());
        for (String fine : List.of("这是一个测试", "Привет", "café", "don’t", "xzf", "mispeledinfence")) {
            assertFalse(flagged.contains(fine), fine + " must not be flagged: " + flagged);
        }
    }

    // ------------------------------------------------------------------------ S14 / S15 / S20: the menu

    @Test
    void theMenuOffersCorrectionsOnlyForWordsThatAreUnderlined() throws Exception {
        EditorBuffer md = open("fence.md", "A paragraf here.\n\n```bash\nsudo xzf archive\n```\n");
        assertNotNull(hitOn(md, "paragraf"));
        assertNull(hitOn(md, "xzf"), "a word inside a code fence is not squiggled, so it gets no spelling items");
        ObservableList<MenuItem> menu = FXCollections.observableArrayList();
        assertTrue(FxTestSupport.callOnFx(() -> menuAt(md, "xzf", menu)).isEmpty());
    }

    @Test
    void suggestionsFillTheMenuAndAreDisabledInAReadOnlyBuffer() throws Exception {
        EditorBuffer txt = open("menu.txt", "we recieve it untill then\n");
        ObservableList<MenuItem> menu = FXCollections.observableArrayList();
        FxTestSupport.runOnFx(() -> menuAt(txt, "recieve", menu));
        awaitSuggestions(); // a slow search fills the placeholder row after the menu is built
        MenuItem receive = FxTestSupport.callOnFx(() -> item(menu, "receive"));
        assertNotNull(receive, "the suggestion is in the menu");
        assertFalse(receive.isDisable());
        assertTrue(receive.getStyleClass().contains("spell-suggestion"));
        assertNotNull(FxTestSupport.callOnFx(() -> item(menu, com.editora.i18n.Messages.tr("editmenu.ignore"))));
        assertNull(
                FxTestSupport.callOnFx(() -> item(menu, com.editora.i18n.Messages.tr("editmenu.spell.searching"))),
                "the placeholder is gone once the suggestions are in");

        FxTestSupport.runOnFx(() -> menuAt(txt, "untill", menu));
        awaitSuggestions();
        assertNotNull(FxTestSupport.callOnFx(() -> item(menu, "until")));
        assertNull(FxTestSupport.callOnFx(() -> item(menu, "until l")), "no split-word junk");

        FxTestSupport.runOnFx(() -> txt.setViewMode(true));
        FxTestSupport.runOnFx(() -> menuAt(txt, "recieve", menu));
        awaitSuggestions();
        MenuItem readOnly = FxTestSupport.callOnFx(() -> item(menu, "receive"));
        assertNotNull(readOnly);
        assertTrue(readOnly.isDisable(), "a suggestion cannot be applied to a read-only buffer, and says so");
        FxTestSupport.runOnFx(readOnly::fire);
        assertTrue(FxTestSupport.callOnFx(() -> txt.getArea().getText()).contains("recieve"));

        FxTestSupport.runOnFx(() -> txt.setViewMode(false));
        FxTestSupport.runOnFx(() -> menuAt(txt, "recieve", menu));
        awaitSuggestions();
        MenuItem again = FxTestSupport.callOnFx(() -> item(menu, "receive"));
        FxTestSupport.runOnFx(again::fire);
        assertTrue(FxTestSupport.callOnFx(() -> txt.getArea().getText()).startsWith("we receive it"));
    }

    // ---------------------------------------------------------------------- S2 / S9 / S19: add, ignore

    @Test
    void addToDictionaryWorksForAWordWithATypographicApostrophe() throws Exception {
        EditorBuffer txt = open("apos.txt", "a zzq’abc and Zzeditora’s here\n");
        assertEquals(List.of("zzq’abc", "Zzeditora’s"), squiggled(txt));
        ObservableList<MenuItem> menu = FXCollections.observableArrayList();
        FxTestSupport.runOnFx(() -> {
            menuAt(txt, "zzq’abc", menu);
            item(menu, com.editora.i18n.Messages.tr("editmenu.addToDictionary")).fire();
            menuAt(txt, "Zzeditora’s", menu);
            item(menu, com.editora.i18n.Messages.tr("editmenu.addToDictionary")).fire();
        });
        settle();
        assertTrue(squiggled(txt).isEmpty(), "the squiggles are gone: " + squiggled(txt));
        assertTrue(
                fx.shared.getUserDictionary().contains("zzq'abc"),
                fx.shared.getUserDictionary().toString());
        assertTrue(fx.shared.getUserDictionary().contains("zzeditora"), "stored without the possessive");
        String onDisk = Files.readString(fx.shared.getUserDictionaryFile());
        assertTrue(onDisk.contains("zzq'abc") && onDisk.contains("zzeditora"), onDisk);
        // …so the bare word and its plural are accepted as well.
        EditorBuffer other = open("apos2.txt", "Zzeditora and zzeditoras and zzeditora's\n");
        assertTrue(squiggled(other).isEmpty(), squiggled(other).toString());
    }

    @Test
    void ignoreHoldsInEveryOpenBufferForTheSession() throws Exception {
        EditorBuffer one = open("ign1.txt", "the zzignoredq word\n");
        EditorBuffer two = open("ign2.txt", "again zzignoredq and zzotherq\n");
        assertEquals(List.of("zzignoredq", "zzotherq"), squiggled(two));
        // Seed the other buffer's memo, so the test fails if it is not told to drop it.
        FxTestSupport.runOnFx(() -> {
            Map<String, Boolean> memo = FxTestSupport.field(overlay(one), "spellCache");
            memo.put("zzignoredq", true);
        });
        ObservableList<MenuItem> menu = FXCollections.observableArrayList();
        FxTestSupport.runOnFx(() -> {
            menuAt(two, "zzignoredq", menu);
            item(menu, com.editora.i18n.Messages.tr("editmenu.ignore")).fire();
        });
        settle();
        assertEquals(List.of("zzotherq"), squiggled(two));
        assertTrue(squiggled(one).isEmpty(), "the same word in another tab is no longer flagged");
        assertFalse(fx.shared.getUserDictionary().contains("zzignoredq"), "ignoring is not adding");
    }

    // ------------------------------------------------------------------------------- S5: the commands

    @Test
    void theSpellCommandsWalkAndActOnMisspellingsFromTheKeyboard() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        for (String id : List.of(
                "spell.nextMisspelling",
                "spell.previousMisspelling",
                "spell.correctWord",
                "spell.addWord",
                "spell.ignoreWord",
                "spell.toggleForLanguage",
                "spell.reloadDictionary",
                "spell.setLanguage")) {
            assertTrue(registry.all().stream().anyMatch(c -> c.id().equals(id)), id + " is registered");
        }
        EditorBuffer txt = open("walk.txt", "one zzfirstq two\nthree\nfour zzsecondq five zzthirdq\n");
        FxTestSupport.runOnFx(() -> txt.getArea().moveTo(0));
        run("spell.nextMisspelling");
        assertEquals("zzfirstq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()));
        run("spell.nextMisspelling");
        assertEquals("zzsecondq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()));
        run("spell.nextMisspelling");
        assertEquals("zzthirdq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()));
        run("spell.nextMisspelling");
        assertEquals("zzfirstq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()), "wraps round");
        run("spell.previousMisspelling");
        assertEquals("zzthirdq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()), "wraps back");
        run("spell.previousMisspelling");
        assertEquals("zzsecondq", FxTestSupport.callOnFx(() -> txt.getArea().getSelectedText()));

        run("spell.ignoreWord"); // the selected word: zzsecondq
        assertEquals(List.of("zzfirstq", "zzthirdq"), squiggled(txt));
        run("spell.nextMisspelling");
        run("spell.addWord"); // zzthirdq
        assertEquals(List.of("zzfirstq"), squiggled(txt));
        assertTrue(fx.shared.getUserDictionary().contains("zzthirdq"));

        // Nothing at the caret: the command says so instead of doing nothing.
        FxTestSupport.runOnFx(() -> txt.getArea().moveTo(1));
        run("spell.addWord");
        assertEquals(com.editora.i18n.Messages.tr("status.spell.noWordAtCaret"), status());

        // Correct: the suggestions arrive off the FX thread; applying one replaces the word.
        EditorBuffer fix = open("fix.txt", "we recieve it\n");
        BufferSpell.Hit hit = hitOn(fix, "recieve");
        List<List<String>> got = new ArrayList<>();
        FxTestSupport.runOnFx(() -> fix.spell().suggest(hit, got::add));
        for (int i = 0; i < 100 && got.isEmpty(); i++) {
            settle();
        }
        assertTrue(got.get(0).contains("receive"), got.toString());
        assertTrue(FxTestSupport.callOnFx(() -> fix.spell().replace(hit, "receive")));
        assertEquals(
                "we receive it\n", FxTestSupport.callOnFx(() -> fix.getArea().getText()));
        assertFalse(FxTestSupport.callOnFx(() -> fix.spell().replace(hit, "x")), "the word is no longer there");

        EditorBuffer clean = open("clean.txt", "nothing wrong here\n");
        run("spell.nextMisspelling");
        assertEquals(com.editora.i18n.Messages.tr("status.spell.none"), status());
        assertNotNull(clean);
    }

    // -------------------------------------------------------------------- S12 / S16: the dictionary

    @Test
    void aFilesOwnDictionaryCanBeSetShownAndCleared() throws Exception {
        EditorBuffer txt = open("lang.txt", "hola mundo\n");
        EditorSettingsCoordinator es = FxTestSupport.field(fx.controller, "editorSettings");
        StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
        javafx.scene.control.Label segment = FxTestSupport.field(bar, "spell");
        assertEquals("en-US", FxTestSupport.callOnFx(segment::getText), "the status bar names the dictionary");
        assertTrue(FxTestSupport.callOnFx(segment::isVisible));

        FxTestSupport.runOnFx(() -> es.spell().setLanguage(txt, "es_MX"));
        settle();
        assertEquals("es_MX", FxTestSupport.callOnFx(txt::getSpellLanguage));
        assertEquals("es-MX", FxTestSupport.callOnFx(segment::getText));
        assertEquals("es_MX", FxTestSupport.callOnFx(() -> es.spell().languageFor(txt)), "remembered for the file");
        assertTrue(
                status().contains(SpellCoordinator.languageName("es_MX")), "a localized name, not the id: " + status());

        FxTestSupport.runOnFx(() -> es.spell().setLanguage(txt, null)); // "use the default"
        settle();
        assertEquals("en_US", FxTestSupport.callOnFx(txt::getSpellLanguage));
        assertEquals("en_US", FxTestSupport.callOnFx(() -> es.spell().languageFor(txt)), "the override is gone");

        // A buffer with no file keeps its choice when the settings are applied again.
        EditorBuffer untitled = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("bonjour\n");
            FxTestSupport.call(fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
        FxTestSupport.runOnFx(() -> es.spell().setLanguage(untitled, "fr"));
        FxTestSupport.runOnFx(() -> es.applyViewSettingsToAllBuffers(settings()));
        settle();
        assertEquals("fr", FxTestSupport.callOnFx(untitled::getSpellLanguage));

        // The status segment is hidden where nothing is checked.
        open("off.json", "{}\n");
        assertFalse(FxTestSupport.callOnFx(segment::isVisible), "JSON is not checked: no dictionary shown");
        assertEquals("English (US)", SpellCoordinator.languageName("en_US"));
        assertEquals("xx", SpellCoordinator.languageName("xx"));
    }

    @Test
    void anUnknownDefaultLanguageFallsBackAndSaysSo() throws Exception {
        String before = settings().getSpellLanguage();
        try {
            settings().setSpellLanguage("de"); // no German dictionary is bundled
            EditorBuffer txt = open("fallback.txt", "a paragraf here\n");
            assertEquals("en_US", FxTestSupport.callOnFx(txt::getSpellLanguage));
            assertEquals(List.of("paragraf"), squiggled(txt), "checking carries on in the built-in default");
        } finally {
            settings().setSpellLanguage(before);
        }
    }

    // ------------------------------------------------------------------- S8: dictionary.txt by hand

    @Test
    void aHandEditedDictionaryFileAppliesWhenItIsSaved() throws Exception {
        EditorBuffer txt = open("hand.txt", "a zzhandaddedq word and zzhandkeptq\n");
        assertEquals(List.of("zzhandaddedq", "zzhandkeptq"), squiggled(txt));
        Path dictionary = fx.shared.getUserDictionaryFile();
        String existing = Files.exists(dictionary) ? Files.readString(dictionary) : "";
        Files.writeString(dictionary, existing + "zzhandaddedq\n");
        EditorBuffer dict = open0(dictionary);
        FxTestSupport.runOnFx(() -> dict.getArea().appendText("zzhandkeptq\n"));
        run("file.save");
        settle();
        assertTrue(
                fx.shared.getUserDictionary().contains("zzhandkeptq"),
                fx.shared.getUserDictionary().toString());
        assertTrue(fx.shared.getUserDictionary().contains("zzhandaddedq"));
        assertTrue(squiggled(txt).isEmpty(), "the saved words are accepted at once: " + squiggled(txt));

        // Removing a line by hand flags the word again.
        FxTestSupport.runOnFx(() -> {
            String text = dict.getArea().getText();
            dict.getArea().replaceText(text.replace("zzhandkeptq\n", ""));
        });
        run("file.save");
        settle();
        assertEquals(List.of("zzhandkeptq"), squiggled(txt));

        // Edited by another program: the reload command picks it up.
        Files.writeString(dictionary, Files.readString(dictionary) + "zzhandkeptq\n");
        select(txt);
        run("spell.reloadDictionary");
        assertTrue(squiggled(txt).isEmpty(), squiggled(txt).toString());
    }

    private EditorBuffer open0(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        for (int i = 0; i < 100; i++) {
            settle();
            EditorBuffer b = active();
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                settle();
                return b;
            }
        }
        throw new IllegalStateException("did not open " + file);
    }

    // ------------------------------------------------------------------------- S21 / S1 / S11: caches

    @Test
    void applyingUnchangedSettingsKeepsTheMemoizedVerdicts() throws Exception {
        EditorBuffer txt = open("memo.txt", "a paragraf here\n");
        EditorSettingsCoordinator es = FxTestSupport.field(fx.controller, "editorSettings");
        int scanned = FxTestSupport.callOnFx(() -> {
            Map<String, Boolean> memo = FxTestSupport.field(overlay(txt), "spellCache");
            memo.put("zzseededq", true);
            return (int) FxTestSupport.call(overlay(txt), "linesScannedForTest", new Class[] {});
        });
        FxTestSupport.runOnFx(() -> es.applyViewSettingsToAllBuffers(settings()));
        settle();
        FxTestSupport.runOnFx(() -> es.applyViewSettingsToAllBuffers(settings()));
        settle();
        Map<String, Boolean> memo = FxTestSupport.callOnFx(() -> FxTestSupport.field(overlay(txt), "spellCache"));
        assertTrue(memo.containsKey("zzseededq"), "an apply that changes nothing spell-related clears nothing");
        assertEquals(
                scanned,
                (int) FxTestSupport.callOnFx(
                        () -> (int) FxTestSupport.call(overlay(txt), "linesScannedForTest", new Class[] {})),
                "and no line is scanned again");
        // A change that does matter still clears.
        FxTestSupport.runOnFx(() -> txt.spell().setTechnicalDictionaryEnabled(false));
        assertFalse(FxTestSupport.callOnFx(() -> memo.containsKey("zzseededq")));
        FxTestSupport.runOnFx(() -> txt.spell().setTechnicalDictionaryEnabled(true));
    }

    @Test
    void scrollingADenseViewportDoesNotMeasureItsWordsAgain() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("Linea ").append(i).append(" con muchas palabras equivocadas para el diccionario ingles\n");
        }
        EditorBuffer txt = open("dense.txt", sb.toString());
        Object ov = overlay(txt);
        List<double[]> log = new ArrayList<>();
        FxTestSupport.runOnFx(() -> txt.getArea().showParagraphAtTop(40));
        settle();
        int afterFirstVisit = wordsMeasured(ov);
        assertTrue(afterFirstVisit > 50, "the viewport is dense with squiggles: " + afterFirstVisit);

        // Repainting the same viewport — a caret blink, a selection change, a horizontal nudge — measures nothing.
        long cachedNanos = FxTestSupport.callOnFx(() -> {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                FxTestSupport.invoke(ov, "redraw");
            }
            return (System.nanoTime() - t0) / 20;
        });
        assertEquals(afterFirstVisit, wordsMeasured(ov), "a repaint of laid-out paragraphs asks the layout nothing");

        // The remembered positions are the ones a fresh measurement gives.
        FxTestSupport.runOnFx(() -> setPaintLog(ov, log));
        Paints first = rememberedAndFresh(ov, log);
        assertSamePaint(first.remembered(), first.fresh());
        long freshNanos = FxTestSupport.callOnFx(() -> {
            long total = 0;
            for (int i = 0; i < 20; i++) {
                FxTestSupport.invoke(ov, "invalidateGeometry");
                long t0 = System.nanoTime();
                FxTestSupport.invoke(ov, "redraw");
                total += System.nanoTime() - t0;
            }
            return total / 20;
        });
        System.out.println(
                "[spell-perf] dense viewport redraw (" + first.remembered().size() + " squiggles): measured "
                        + freshNanos / 1000 + " µs, remembered " + cachedNanos / 1000 + " µs");

        // Scroll away and back: the lines that stayed laid out are not measured again, and they are painted
        // where they now are.
        FxTestSupport.runOnFx(() -> txt.getArea().showParagraphAtTop(43));
        settle();
        Paints scrolled = rememberedAndFresh(ov, log);
        assertSamePaint(scrolled.remembered(), scrolled.fresh());
        assertFalse(scrolled.remembered().isEmpty());

        // A different wrap width or font moves the words: both are measured afresh and still agree.
        FxTestSupport.runOnFx(() -> {
            txt.setWordWrap(true);
            txt.setFont("Monospaced", 19);
        });
        settle();
        Paints restyled = rememberedAndFresh(ov, log);
        assertSamePaint(restyled.remembered(), restyled.fresh());

        // Something that moves the text inside every paragraph's box without changing the paragraphs or the
        // overlay's width — here the line-number gutter going away — is noticed on the next frame.
        FxTestSupport.runOnFx(() -> {
            txt.setWordWrap(false);
            txt.setLineNumbersVisible(true);
        });
        settle();
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(ov, "redraw"));
        FxTestSupport.runOnFx(() -> txt.setLineNumbersVisible(false));
        settle();
        Paints shifted = rememberedAndFresh(ov, log);
        assertSamePaint(shifted.remembered(), shifted.fresh());
        assertFalse(shifted.remembered().isEmpty());
        FxTestSupport.runOnFx(() -> setPaintLog(ov, null));
    }

    @Test
    void typingInALongLineWithNoWhitespaceStaysCheap() throws Exception {
        // Minified JSON opened where it is checked (a .txt; JSON itself is off by default): no whitespace, so
        // the whole line was one token and each misspelled word rescanned all 60 KB of it.
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; sb.length() < 60_000; i++) {
            sb.append("\"kee").append(i).append("\":\"A_smal_exampel_valu\",\"itms\":[\"abc\",\"recieve\"],");
        }
        String minified = sb.append("\"z\":1}\n").toString();
        EditorBuffer min = open("data.min.txt", minified);
        assertTrue(FxTestSupport.callOnFx(() -> min.spell().isActive()));
        long whole = medianKeystrokeMillis(min);
        assertTrue(squiggled(min).isEmpty(), "a line this long is data, not a paragraph");

        // A long line that IS short enough to check, in the same shape: bounded squiggles, each token judged once.
        EditorBuffer shorter = open("data.short.txt", minified.substring(0, 12_000) + "\n");
        List<String> flagged = squiggled(shorter);
        assertTrue(flagged.contains("recieve"), "its words are checked");
        assertTrue(flagged.size() <= 250, "at most 250 squiggles on one line, got " + flagged.size());
        long part = medianKeystrokeMillis(shorter);
        System.out.println("[spell-perf] keystroke-to-idle, spell on: 60 KB no-whitespace line " + whole
                + " ms; 12 KB line with " + flagged.size() + " squiggles " + part + " ms");
        assertTrue(whole < 200, "median keystroke in the 60 KB line took " + whole + " ms");
        assertTrue(part < 400, "median keystroke in the 12 KB line took " + part + " ms");
    }

    private static long medianKeystrokeMillis(EditorBuffer b) throws Exception {
        long[] ms = new long[5];
        for (int i = 0; i < ms.length; i++) {
            long t0 = System.nanoTime();
            FxTestSupport.runOnFx(() -> b.getArea().insertText(10, "x"));
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        java.util.Arrays.sort(ms);
        return ms[2];
    }

    private static int wordsMeasured(Object overlay) throws Exception {
        return FxTestSupport.callOnFx(() -> (int) FxTestSupport.call(overlay, "wordsMeasuredForTest", new Class[] {}));
    }

    private static void setPaintLog(Object overlay, List<double[]> log) {
        try {
            var f = overlay.getClass().getDeclaredField("paintLog");
            f.setAccessible(true);
            f.set(overlay, log);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One viewport painted twice: from the remembered positions, then from a fresh measurement. */
    private record Paints(List<double[]> remembered, List<double[]> fresh) {}

    /**
     * Paints from what the overlay remembers and again after it has forgotten, in a single FX turn, and
     * returns both. The turn matters twice over. No pulse comes between the two paints, so no layout pass
     * does either and they are paints of the same viewport — a just-enabled word wrap is still re-measuring
     * for many pulses. And the paint log is read here, on the thread that writes it: forgetting the geometry
     * also queues a repaint of its own, which runs as soon as the turn ends and logs every squiggle a second
     * time — into a list the test thread used to be copying at that moment.
     */
    private static Paints rememberedAndFresh(Object overlay, List<double[]> log) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            log.clear();
            FxTestSupport.invoke(overlay, "redraw");
            List<double[]> remembered = new ArrayList<>(log);
            FxTestSupport.invoke(overlay, "invalidateGeometry");
            log.clear();
            FxTestSupport.invoke(overlay, "redraw");
            return new Paints(remembered, new ArrayList<>(log));
        });
    }

    private static void assertSamePaint(List<double[]> expected, List<double[]> actual) {
        assertEquals(expected.size(), actual.size(), "the same squiggles are painted");
        for (int i = 0; i < expected.size(); i++) {
            for (int k = 0; k < 3; k++) {
                assertEquals(expected.get(i)[k], actual.get(i)[k], 0.51, "squiggle " + i + " coordinate " + k);
            }
        }
    }
}
