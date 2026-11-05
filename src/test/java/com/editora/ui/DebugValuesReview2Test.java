package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java debugging review 2, the window side of the values and hot code replace fixes: lines edited in place during a
 * session (B104), obsolete frames and the applied message (B103), the library stop's file name (B107) and a String's
 * text in the value popup (C105).
 */
class DebugValuesReview2Test {

    private static final List<String> LAUNCHED =
            List.of("class M {", "  void f() {", "    int x = 1;", "    String s = \"v1\";", "    return;", "  }", "}");

    @Test
    void aLineEditedInPlaceIsStillTheLaunchedLine() {
        List<String> now = List.of(
                "class M {",
                "  void f() {",
                "    int x = 1; // note",
                "    String s = \"v2\";",
                "    return;",
                "  }",
                "}");
        LaunchedLines.Mapping map = LaunchedLines.Mapping.between(LAUNCHED, now);
        assertEquals(2, map.toLaunched(2), "a breakpoint on it is still sent");
        assertEquals(3, map.toLaunched(3));
        assertEquals(3, map.toCurrent(3), "a stop on it is painted on it, not on the next line");
        assertEquals(4, map.toCurrent(4));
    }

    @Test
    void aLineSplitInTwoKeepsItsFirstHalfAndTheSecondIsNew() {
        List<String> now = List.of(
                "class M {",
                "  void f() {",
                "    int x = 1;",
                "    String s =",
                "        \"v1\";",
                "    return;",
                "  }",
                "}");
        LaunchedLines.Mapping map = LaunchedLines.Mapping.between(LAUNCHED, now);
        assertEquals(3, map.toLaunched(3));
        assertEquals(-1, map.toLaunched(4), "added during the session");
        assertEquals(4, map.toLaunched(5));
        assertEquals(5, map.toCurrent(4));
    }

    @Test
    void anObsoleteFrameSaysItRunsOldCode() {
        assertEquals(
                tr("debugpanel.obsoleteFrame", "Main"),
                DebugPanelText.frameName("Main.Obsolete method()[native method]"));
        assertEquals(tr("debugpanel.obsoleteFrame", "a.B$C"), DebugPanelText.frameName("a.B$C.Obsolete method()"));
        assertEquals("Main.main(String[])", DebugPanelText.frameName("Main.main(String[])"));
    }

    @Test
    void anAppliedReplaceNamesItsClassesOrElseTheSavedFiles() {
        assertEquals("Main", CodeReplaceText.appliedNames(List.of("demo.Main"), Set.of(Path.of("/p/Other.java"))));
        assertEquals("Main", CodeReplaceText.appliedNames(List.of(), Set.of(Path.of("/p/Main.java"))));
        assertEquals("", CodeReplaceText.appliedNames(List.of(), Set.of()));
    }

    @Test
    void aLibraryFrameIsNamedByItsFileNotByAPieceOfItsJdtQuery() {
        // Windows cannot hold a jdt: URI in a Path, so there a library frame carries no file at all.
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win"));
        Path jdt = Path.of("jdt:/contents/java.base/java.lang/String.java?=project_1/%5C/jdk%5C/lib%5C/jrt-fs.jar"
                + "%60java.base=/%3Cjava.lang%28String.class");
        assertEquals("String.java", DebugValues.sourceName(jdt));
    }

    @Test
    void aJavaStringIsShownAsItsTextAndOtherValuesAsBefore() {
        assertTrue(DebugValuePopup.isText(new DapModels.EvalResult("\"line one\nline two\"", 12, "String")));
        assertTrue(DebugValuePopup.isText(new DapModels.EvalResult("\"x\"", 3, "java.lang.String")));
        assertFalse(DebugValuePopup.isText(new DapModels.EvalResult("Main$Point@17 \"P\"", 3, "Main$Point")));
        assertFalse(DebugValuePopup.isText(new DapModels.EvalResult("null", 0, "String")));
        assertFalse(DebugValuePopup.isText(DapModels.EvalResult.failure("\"x\"")));
        assertTrue(DebugValuePopup.textHeight("one line") < DebugValuePopup.textHeight("a\nb\nc\nd\ne\nf"));
        assertTrue(DebugValuePopup.textHeight("x\n".repeat(500)) <= DebugValuePopup.MAX_HEIGHT);
    }
}
