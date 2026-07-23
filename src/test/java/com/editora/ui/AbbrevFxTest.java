package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.config.Abbreviation;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abbreviation expansion against a real buffer through the registry. The pure {@code AbbrevTest} covers the
 * lookup + case adaptation; this covers the wiring — the on-demand command, auto-expand on a typed
 * terminator (and its deferred caret restore), and that Settings drive the dictionary.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbbrevFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private Settings settings;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        settings = fx.shared.getSettings();
        FxTestSupport.runOnFx(() -> fx.shared.setAbbreviations(new java.util.ArrayList<>(
                List.of(new Abbreviation("btw", "by the way"), new Abbreviation("afaik", "as far as I know")))));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    /** Opens a buffer with the dictionary applied and abbrev-mode as given, caret at the end. */
    private EditorBuffer open(String content, boolean autoExpand) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            settings.setAbbrevMode(autoExpand);
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setAbbrevs(fx.shared.abbreviationMap(), autoExpand);
            b.getArea().moveTo(b.getArea().getLength());
            return b;
        });
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private void type(EditorBuffer b, String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            FxTestSupport.runOnFx(() -> b.getArea().insertText(b.getArea().getCaretPosition(), ch));
        }
    }

    // --- on demand -------------------------------------------------------------------------------

    @Test
    void expandCommandExpandsTheWordBeforeTheCaret() throws Exception {
        EditorBuffer b = open("btw", false);
        run("edit.expandAbbrev");
        assertEquals("by the way", text(b));
    }

    @Test
    void expandCommandCarriesTheTypedCase() throws Exception {
        EditorBuffer b = open("BTW", false);
        run("edit.expandAbbrev");
        assertEquals("BY THE WAY", text(b));
    }

    @Test
    void expandCommandLeavesANonAbbreviationAlone() throws Exception {
        EditorBuffer b = open("hello", false);
        run("edit.expandAbbrev");
        assertEquals("hello", text(b), "not in the dictionary — unchanged");
    }

    // --- auto-expand (abbrev mode) ---------------------------------------------------------------

    @Test
    void typingATerminatorExpandsInAbbrevMode() throws Exception {
        EditorBuffer b = open("well ", true);
        type(b, "btw"); // no expansion yet — still inside the word
        assertEquals("well btw", text(b));
        type(b, " "); // the space terminates the word → expand
        assertEquals("well by the way ", text(b));
    }

    @Test
    void theTerminatorAndCaretSurviveTheExpansion() throws Exception {
        EditorBuffer b = open("", true);
        type(b, "afaik.");
        assertEquals("as far as I know.", text(b), "the '.' that triggered the expansion is kept after it");
        int caret = FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
        assertEquals(text(b).length(), caret, "the caret stayed at the end, past the expansion");
    }

    @Test
    void abbrevModeOffDoesNotAutoExpand() throws Exception {
        EditorBuffer b = open("", false);
        type(b, "btw ");
        assertEquals("btw ", text(b), "with the mode off, typing a terminator expands nothing");
    }

    // --- define ----------------------------------------------------------------------------------

    @Test
    void defineAbbreviationAddsToTheDictionaryAndPersists() throws Exception {
        int before = FxTestSupport.callOnFx(() -> fx.shared.getAbbreviations().size());
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "addAbbreviation", new Class[] {String.class, String.class}, "omw", "on my way"));
        List<Abbreviation> after = FxTestSupport.callOnFx(() -> fx.shared.getAbbreviations());
        assertEquals(before + 1, after.size());
        assertTrue(after.stream()
                .anyMatch(a ->
                        a.getAbbreviation().equals("omw") && a.getExpansion().equals("on my way")));
    }

    @Test
    void definingAnExistingAbbreviationReplacesItCaseInsensitively() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "addAbbreviation", new Class[] {String.class, String.class}, "ty", "thank you"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "addAbbreviation", new Class[] {String.class, String.class}, "TY", "thanks"));
        List<Abbreviation> after = FxTestSupport.callOnFx(() -> fx.shared.getAbbreviations());
        long ty = after.stream()
                .filter(a -> a.getAbbreviation().equalsIgnoreCase("ty"))
                .count();
        assertEquals(1, ty, "the second definition replaced the first rather than duplicating it");
    }
}
