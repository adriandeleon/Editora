package com.editora.ui;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.config.Abbreviation;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing commands that ask before they act, run through the command registry in a real window and
 * answered through the real prompt and picker: query-replace's two prompts, the rectangle commands, occur,
 * the kill-ring picker, the abbreviation and fill-column prompts — each with the answers that must change
 * nothing (empty, cancelled, malformed) as well as the one that does the work.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditingPromptsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private EditingCoordinator editing;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        editing = FxTestSupport.field(fx.controller, "editing");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @AfterEach
    void closeOverlays() throws Exception {
        FxTestSupport.runOnFx(() -> {
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            if (overlay.isShowing()) {
                overlay.hide();
            }
            FxTestSupport.invoke(fx.controller, "cancel"); // a query-replace or zap still waiting for a key
        });
    }

    private EditorBuffer buffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            return b;
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private void select(EditorBuffer b, int from, int to) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(from, to));
    }

    private String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    private void type(EditorBuffer b, char c) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(
                        KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false)));
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field f = EditingCoordinator.class.getDeclaredField(name);
        f.setAccessible(true);
        FxTestSupport.runOnFxUnchecked(() -> {
            try {
                f.set(editing, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private boolean prompting() throws Exception {
        return FxPrompts.showing(fx.controller);
    }

    // --- query replace ----------------------------------------------------------------------------------

    @Test
    void queryReplaceAsksForTheSearchThenTheReplacementAndStartsAtTheFirstMatch() throws Exception {
        EditorBuffer b = buffer("cat dog cat\ncat");
        select(b, 4, 7); // "dog": a single-line selection seeds the search
        run("edit.queryReplace");
        assertEquals("dog", FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, "cat");
        assertEquals("", FxPrompts.text(fx.controller), "the replacement prompt starts empty");
        FxPrompts.answer(fx.controller, "bird");
        assertFalse(prompting());
        assertEquals(tr("status.queryReplace.prompt", 0), status());

        type(b, '!');
        assertEquals("cat dog bird\nbird", text(b), "from the caret (the selection's start) on");
        assertEquals(tr("status.queryReplace.done", 2), status());
    }

    @Test
    void queryReplaceDoesNothingForAnEmptySearchACancelledPromptOrAMultiLineSeed() throws Exception {
        EditorBuffer b = buffer("one\ntwo");
        select(b, 0, 7);
        run("edit.queryReplace");
        assertEquals("", FxPrompts.text(fx.controller), "a selection that spans lines is not a search seed");
        FxPrompts.answer(fx.controller, "");
        assertFalse(prompting(), "an empty search asks for no replacement");

        run("edit.queryReplace");
        FxPrompts.cancel(fx.controller);
        run("edit.queryReplace");
        FxPrompts.answer(fx.controller, "one");
        FxPrompts.cancel(fx.controller); // at the replacement prompt
        assertEquals("one\ntwo", text(b));
        assertNull(FxTestSupport.field(editing, "queryReplaceSession"));
    }

    @Test
    void queryReplaceRegexpRefusesABadPatternAndOtherwiseReplacesWithGroups() throws Exception {
        EditorBuffer b = buffer("a1 b2");
        run("edit.queryReplaceRegexp");
        FxPrompts.answer(fx.controller, "(");
        assertFalse(prompting(), "no replacement prompt for a pattern that does not compile");
        assertTrue(status().startsWith(tr("find.badRegex", "").strip()), status());

        run("edit.queryReplaceRegexp");
        FxPrompts.answer(fx.controller, "([a-z])(\\d)");
        FxPrompts.answer(fx.controller, "$2$1");
        type(b, '!');
        assertEquals("1a 2b", text(b));
    }

    @Test
    void aSecondQueryReplaceEndsTheOneInProgress() throws Exception {
        EditorBuffer b = buffer("x x x");
        run("edit.queryReplace");
        FxPrompts.answer(fx.controller, "x");
        FxPrompts.answer(fx.controller, "y");
        Object first = FxTestSupport.field(editing, "queryReplaceSession");
        assertTrue(first != null);

        run("edit.queryReplace");
        assertNull(FxTestSupport.field(editing, "queryReplaceSession"), "the dangling session was finished");
        FxPrompts.answer(fx.controller, "x");
        FxPrompts.answer(fx.controller, "z");
        type(b, 'y');
        assertEquals("z x x", text(b), "the keys now drive the new session only");
    }

    @Test
    void queryReplaceWithNoMatchSaysSo() throws Exception {
        EditorBuffer b = buffer("nothing here");
        run("edit.queryReplace");
        FxPrompts.answer(fx.controller, "absent");
        FxPrompts.answer(fx.controller, "x");
        assertEquals(tr("status.queryReplace.none"), status());
        assertNull(FxTestSupport.field(editing, "queryReplaceSession"));
        assertEquals("nothing here", text(b));
    }

    // --- rectangles -------------------------------------------------------------------------------------

    @Test
    void stringRectangleReplacesEachLinesSegmentWithTheTypedText() throws Exception {
        EditorBuffer b = buffer("abcd\nefgh\nijkl");
        select(b, 1, 13); // columns 1..3 of all three lines
        run("edit.stringRectangle");
        FxPrompts.answer(fx.controller, "--");
        assertEquals("a--d\ne--h\ni--l", text(b));
    }

    @Test
    void numberRectangleNumbersTheLinesFromTheGivenStartAndRefusesANonNumber() throws Exception {
        EditorBuffer b = buffer("aa\nbb\ncc");
        select(b, 0, 6);
        run("edit.numberRectangle");
        assertEquals("1", FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, "nine");
        assertEquals(tr("status.rectangle.badNumber"), status());
        assertEquals("aa\nbb\ncc", text(b));

        select(b, 0, 6);
        run("edit.numberRectangle");
        FxPrompts.answer(fx.controller, " 9 ");
        assertEquals(
                List.of("9 aa", "10 bb", "11 cc"),
                text(b).lines().map(String::strip).toList());
    }

    @Test
    void aRectangleCommandNeedsARegionAndAnEditableBuffer() throws Exception {
        EditorBuffer b = buffer("abcd\nefgh");
        run("edit.stringRectangle");
        assertFalse(prompting());
        assertEquals(tr("status.rectangle.noRegion"), status());

        setField("killedRectangle", List.of()); // nothing killed yet in this session
        run("edit.yankRectangle");
        assertEquals(tr("status.rectangle.empty"), status());

        select(b, 0, 7);
        FxTestSupport.runOnFx(() -> b.setViewMode(true));
        for (String id : List.of("edit.stringRectangle", "edit.killRectangle", "edit.yankRectangle")) {
            run(id);
            assertFalse(prompting(), id);
            assertTrue(status().startsWith(tr("status.bufferReadOnly")), id + ": " + status());
        }
        assertEquals("abcd\nefgh", text(b));
    }

    @Test
    void aKilledRectangleIsYankedBackAsABlock() throws Exception {
        EditorBuffer b = buffer("abcd\nefgh\nijkl");
        select(b, 1, 8); // columns 1..3 of the first two lines
        run("edit.killRectangle");
        assertEquals("ad\neh\nijkl", text(b));
        assertEquals(tr("status.rectangle.killed", 2), status());

        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0));
        run("edit.yankRectangle");
        assertEquals("bcad\nfgeh\nijkl", text(b));

        select(b, 0, 7);
        run("edit.copyRectangle");
        assertEquals(tr("status.rectangle.copied", 2), status());
        assertEquals("bcad\nfgeh\nijkl", text(b), "copying leaves the text alone");
    }

    // --- occur ------------------------------------------------------------------------------------------

    @Test
    void occurListsTheMatchingLinesAndJumpsToTheChosenOne() throws Exception {
        EditorBuffer b = buffer("alpha\n  beta one\ngamma\nbeta two\n");
        run("edit.occur");
        FxPrompts.answer(fx.controller, "^\\s*beta");
        List<String> rows = FxPrompts.rows(fx.controller);
        assertEquals(2, rows.size(), rows.toString());

        FxPrompts.choose(fx.controller, 1);
        assertFalse(prompting());
        assertEquals(3, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "the caret is on 'beta two'");
    }

    @Test
    void occurSaysWhenNothingMatchesAndRefusesABadPattern() throws Exception {
        EditorBuffer b = buffer("alpha\nbeta\n");
        select(b, 0, 5);
        run("edit.occur");
        assertEquals("alpha", FxPrompts.text(fx.controller), "seeded from the selection");
        FxPrompts.answer(fx.controller, "");
        assertFalse(prompting(), "an empty pattern lists nothing");

        run("edit.occur");
        FxPrompts.answer(fx.controller, "zeta");
        assertEquals(tr("status.occur.none"), status());
        assertFalse(prompting());

        run("edit.occur");
        FxPrompts.answer(fx.controller, "[");
        assertTrue(status().startsWith(tr("find.badRegex", "").strip()), status());
        assertFalse(prompting());
        assertEquals(0, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
    }

    // --- the kill ring ----------------------------------------------------------------------------------

    @Test
    void theKillRingPickerInsertsAnyPastKillOverTheSelection() throws Exception {
        EditorBuffer b = buffer("first\nsecond\nthird");
        FxTestSupport.runOnFx(() -> FxTestSupport.<com.editora.editops.KillRing>field(editing, "killRing")
                .clear());
        run("edit.yankFromRing");
        assertEquals(tr("status.yankPop.ringEmpty"), status());
        assertFalse(prompting());

        run("edit.killLine"); // kills "first"
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(1)); // somewhere else: a separate kill
        run("edit.killLine"); // kills "second"
        assertEquals("\n\nthird", text(b));

        select(b, 2, 7); // "third"
        run("edit.yankFromRing");
        List<String> rows = FxPrompts.rows(fx.controller);
        assertEquals(List.of("second", "first"), rows, "newest first");
        FxPrompts.choose(fx.controller, 1);
        assertEquals("\n\nfirst", text(b), "the chosen kill replaced the selection");
        assertEquals(7, FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()));

        FxTestSupport.runOnFx(() -> b.setViewMode(true));
        run("edit.yankFromRing");
        assertFalse(prompting(), "a read-only buffer is not offered a yank");
    }

    @Test
    void aLongOrMultiLineKillIsShownOnOneElidedLine() {
        assertEquals("a⏎b c⏎", EditingCoordinator.killRingLabel("  a\nb\tc\n"));
        String longEntry = "x".repeat(EditingCoordinator.KILL_RING_LABEL_MAX + 5);
        String label = EditingCoordinator.killRingLabel(longEntry);
        assertEquals(EditingCoordinator.KILL_RING_LABEL_MAX + 1, label.length());
        assertTrue(label.endsWith("…"));
        assertEquals(
                "y".repeat(EditingCoordinator.KILL_RING_LABEL_MAX),
                EditingCoordinator.killRingLabel("y".repeat(EditingCoordinator.KILL_RING_LABEL_MAX)));
    }

    // --- abbreviations and the fill column --------------------------------------------------------------

    @Test
    void definingAnAbbreviationSeedsTheWordBeforeTheCaretAndReplacesAnOlderDefinition() throws Exception {
        EditorBuffer b = buffer("see btw");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(7));
        run("edit.defineAbbrev");
        assertEquals("btw", FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, "   ");
        assertFalse(prompting(), "a blank abbreviation asks for no expansion");

        run("edit.defineAbbrev");
        FxPrompts.answer(fx.controller, " btw ");
        FxPrompts.answer(fx.controller, "by the way");
        assertEquals(tr("status.abbrev.defined", "btw"), status());

        run("edit.defineAbbrev");
        FxPrompts.answer(fx.controller, "BTW");
        FxPrompts.answer(fx.controller, "between");
        List<Abbreviation> defined = fx.shared.getAbbreviations().stream()
                .filter(a -> a.getAbbreviation().equalsIgnoreCase("btw"))
                .toList();
        assertEquals(1, defined.size(), "the key is case-insensitive: redefining replaces");
        assertEquals("between", defined.getFirst().getExpansion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-4", "wide", ""})
    void theFillColumnRefusesAnythingButAPositiveNumber(String typed) throws Exception {
        int before = fx.shared.getSettings().getFillColumn();
        run("edit.setFillColumn");
        assertEquals(Integer.toString(before), FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, typed);
        assertEquals(tr("status.fillColumn.invalid"), status());
        assertEquals(before, fx.shared.getSettings().getFillColumn());
    }

    @Test
    void theFillColumnIsSetFromThePrompt() throws Exception {
        int before = fx.shared.getSettings().getFillColumn();
        try {
            run("edit.setFillColumn");
            FxPrompts.answer(fx.controller, " 64 ");
            assertEquals(64, fx.shared.getSettings().getFillColumn());
            assertEquals(tr("status.fillColumn.set", 64), status());
        } finally {
            fx.shared.getSettings().setFillColumn(before);
        }
    }

    // --- zap to char ------------------------------------------------------------------------------------

    @Test
    void zapToCharKillsThroughTheTypedCharacterOrSaysItIsNotThere() throws Exception {
        EditorBuffer b = buffer("alpha,beta,gamma");
        run("edit.zapToChar");
        type(b, ',');
        assertEquals("beta,gamma", text(b));

        run("edit.zapToChar");
        type(b, '#');
        assertEquals(tr("status.zapNotFound", "#"), status());
        assertEquals("beta,gamma", text(b));

        run("edit.zapToChar");
        type(b, '\u001B'); // Escape arrives as a control character: cancel
        type(b, 'a'); // no longer armed: an ordinary character again, not a zap target
        assertEquals("abeta,gamma", text(b));
    }
}
