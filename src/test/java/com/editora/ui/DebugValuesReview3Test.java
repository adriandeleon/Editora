package com.editora.ui;

import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java debugging review 3, the values half on the UI side: edited lines paired by content (B203), a 1 MB String row
 * (B204), the hint after a replace that leaves main() on its old code (B209), the notes a row carries (B201/B202)
 * and the String popup's character count.
 */
class DebugValuesReview3Test {

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static final List<String> L = List.of("a();", "b();", "c();", "d();", "e();");

    private static LaunchedLines.Mapping map(List<String> launched, List<String> current) {
        return LaunchedLines.Mapping.between(launched, current);
    }

    // --- B203: pairing by content ------------------------------------------------------------------------

    @Test
    void wrappingTheStoppedLineInABlockKeepsItsBreakpointAndStopLine() {
        List<String> launched = List.of("for (int i = 0; i < n; i++) {", "    work(i);", "}");
        List<String> current =
                List.of("for (int i = 0; i < n; i++) {", "    if (i >= 0) {", "        work(i);", "    }", "}");
        var m = map(launched, current);
        assertEquals(2, m.toCurrent(1), "the stop on work(i) is painted on work(i), not on the if");
        assertEquals(1, m.toLaunched(2), "the breakpoint on work(i) is still the launched line");
        assertEquals(-1, m.toLaunched(1), "the if is new");
        assertEquals(-1, m.toLaunched(3));
    }

    @Test
    void aNewLineAboveAnEditedLineIsTheNewOne() {
        var m = map(L, List.of("a();", "log();", "B();", "c();", "d();", "e();"));
        assertEquals(-1, m.toLaunched(1), "log() was added");
        assertEquals(1, m.toLaunched(2), "B() is b() edited");
        assertEquals(2, m.toCurrent(1));
    }

    @Test
    void joinedLinesBothMapToTheJoinedLine() {
        var m = map(L, List.of("a();", "b(); c();", "d();", "e();"));
        assertEquals(1, m.toCurrent(1));
        assertEquals(1, m.toCurrent(2), "a stop on c() is on the joined line, not on d()");
        assertEquals(2, m.toCurrent(3));
    }

    @Test
    void reindentingAndEditingInPlaceKeepTheirLines() {
        var re = map(L, List.of("a();", "        b();", "c();", "d();", "e();"));
        assertEquals(1, re.toLaunched(1));
        var edited = map(List.of("x = \"v1\";"), List.of("x = \"v2\";"));
        assertEquals(0, edited.toLaunched(0), "review 2's B104 case still pairs");
        var rewrite = map(L, List.of("a();", "x();", "y();", "z();", "d();", "e();"));
        assertEquals(1, rewrite.toLaunched(1), "nothing alike: paired from the top, as before");
        assertEquals(-1, rewrite.toLaunched(3));
    }

    @Test
    void likenessNeedsMostOfTheLine() {
        assertEquals(0, LaunchedLines.Mapping.likeness("foo();", "barBaz(1, 2);", true));
        assertTrue(LaunchedLines.Mapping.likeness("b();", "B();", true) > 0);
        assertTrue(LaunchedLines.Mapping.likeness("c();", "if (ok) c();", true) > 0, "wrapped on one line");
        assertEquals(0, LaunchedLines.Mapping.likeness("b();", "B();", false), "a huge region: equal lines only");
        assertEquals(2, LaunchedLines.Mapping.likeness("work(i);", "work(i);", false));
    }

    @Test
    void aHugeChangedRegionStillMapsQuickly() {
        List<String> before = new java.util.ArrayList<>();
        List<String> after = new java.util.ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            before.add("old" + i + "();");
            after.add("    new" + i + "(x);");
        }
        long t = System.nanoTime();
        var m = map(before, after);
        assertTrue(System.nanoTime() - t < 5_000_000_000L);
        assertEquals(0, m.toLaunched(0), "paired from the top");
    }

    // --- B204: a 1 MB String ------------------------------------------------------------------------------------

    @Test
    void aHugeValueIsCutForTheRowOnly() {
        String mb = "\"" + "x".repeat(1_000_000) + "\"";
        String shown = DebugValues.display(mb);
        assertTrue(shown.length() <= DebugValues.MAX_SHOWN + 2, "rows below it are drawn again");
        assertTrue(shown.endsWith("…\""), "still reads as a string");
        assertEquals("short", DebugValues.display("short"));
        String emoji = "😀".repeat(400); // 800 UTF-16 units
        String cut = DebugValues.capped(emoji);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "never half an emoji");
    }

    // --- B209: main() left on its old code ------------------------------------------------------------------

    @Test
    void theHintSaysMainKeepsItsOldCodeWhenItIsTheOnlyOldCodeFrame() {
        String obsoleteMain = "Main.Obsolete method()[native method]";
        assertEquals(
                tr("debug.hcr.restartProgramHint", "Main"),
                CodeReplaceText.oldCodeHint(List.of("Main.greet(int)", obsoleteMain)),
                "greet runs the new code; only main is on the old one, and nothing can call main again");
        assertEquals(
                tr("debug.hcr.restartFrameHint"),
                CodeReplaceText.oldCodeHint(List.of("Main.Obsolete method()[native method]", "Main.main(String[])")));
        assertEquals(tr("debug.hcr.restartFrameHint"), CodeReplaceText.oldCodeHint(List.of()));
        assertEquals(tr("debug.hcr.restartFrameHint"), CodeReplaceText.oldCodeHint(List.of("Main.main(String[])")));
        assertEquals("Main", CodeReplaceText.frameClass("com.acme.Main.work(int)"));
        assertEquals("Main", CodeReplaceText.frameClass(obsoleteMain));
        assertNull(CodeReplaceText.frameClass("main"));
        assertTrue(DebugPanelText.isObsoleteFrame(obsoleteMain));
        assertFalse(DebugPanelText.isObsoleteFrame("Main.main(String[])"));
    }

    // --- B201/B202: notes on a row ------------------------------------------------------------------------------

    @Test
    void aRowSaysWhyItShowsOnlyTypeAtId() {
        var notLoaded =
                new DapModels.VariableInfo("loops", "Loops@12", "Loops", 5, 0, 0, true, DapModels.ValueNote.NOT_LOADED);
        assertTrue(DebugPanel.variableRow(notLoaded, 3).notLoaded());
        var stillLazy = new DapModels.VariableInfo("x", "Thing@9", "Thing", 6, 0, 0, true);
        assertTrue(DebugPanel.variableRow(stillLazy, 3).notLoaded(), "unresolved: a click loads it too");
        var paused = new DapModels.VariableInfo(
                "p", "Point@4", "Point", 7, 2, 0, false, DapModels.ValueNote.NO_TO_STRING_AFTER_PAUSE);
        assertFalse(DebugPanel.variableRow(paused, 3).notLoaded());
        assertEquals(
                DapModels.ValueNote.NO_TO_STRING_AFTER_PAUSE,
                DebugPanel.variableRow(paused, 3).note());
        assertNull(DebugPanel.variableRow(new DapModels.VariableInfo("n", "3", "int", 0), 3)
                .note());
        for (DapModels.ValueNote note : DapModels.ValueNote.values()) {
            assertFalse(DebugPanelText.valueNote(note).startsWith("debugpanel."), note.name());
        }
    }
}
