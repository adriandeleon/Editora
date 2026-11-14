package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.paint.Color;

import com.editora.config.Breakpoint;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions of the Java debugging review 2, area C (Debug window and menus). */
class DebugReview2CTest {

    // --- C106: the Edit Breakpoint form -----------------------------------------------------------------

    @Test
    void theFormTitleNamesTheFileAndTheOneBasedLine() {
        assertEquals(
                tr("dialog.debug.breakpointTitleAt", "Main.java", 27),
                BreakpointFormText.title(Path.of("/p/src/Main.java"), 26));
        assertEquals(tr("dialog.debug.breakpointTitle"), BreakpointFormText.title(null, 3));
        assertEquals(
                tr("dialog.debug.breakpointTitleAt", "Big.java", 12345),
                BreakpointFormText.title(Path.of("Big.java"), 12344),
                "a line number is never digit-grouped");
    }

    @Test
    void theHitCountHintOffersOnlyWhatTheFilesDebuggerTakes() {
        assertEquals(tr("dialog.debug.hitConditionPromptPython"), BreakpointFormText.hitConditionPrompt("python"));
        assertEquals(tr("dialog.debug.hitConditionPromptNumber"), BreakpointFormText.hitConditionPrompt("java"));
        assertEquals(tr("dialog.debug.hitConditionPromptNumber"), BreakpointFormText.hitConditionPrompt(null));
        assertFalse(BreakpointFormText.hitConditionPrompt("java").contains("Python"));
        assertEquals("int total = 1;", BreakpointFormText.lineText("        int total = 1;   ", 64));
        assertEquals("abcde…", BreakpointFormText.lineText("abcdefgh", 5));
    }

    // --- C107: the Breakpoints list ---------------------------------------------------------------------

    private static Breakpoint bp(String condition, String log, boolean enabled) {
        return new Breakpoint(3, condition, log, enabled, "x();");
    }

    @Test
    void eachKindHasTheGuttersShapeAndColour() {
        assertEquals("●", BreakpointsPicker.mark(bp("", "", true)));
        assertEquals("●", BreakpointsPicker.mark(bp("x > 1", "", true)));
        assertEquals("◆", BreakpointsPicker.mark(bp("", "n={n}", true)));
        assertEquals("○", BreakpointsPicker.mark(bp("", "", false)));
        assertEquals("◇", BreakpointsPicker.mark(bp("", "n={n}", false)));
        assertEquals("breakpoint-plain", BreakpointsPicker.kindClass(bp("", "", true)));
        assertEquals("breakpoint-conditional", BreakpointsPicker.kindClass(bp("x > 1", "", true)));
        assertEquals(
                "breakpoint-conditional",
                BreakpointsPicker.kindClass(bp("", "", true).withHitCondition("5")));
        assertEquals("breakpoint-logpoint", BreakpointsPicker.kindClass(bp("x > 1", "n={n}", true)));
        assertEquals("breakpoint-disabled", BreakpointsPicker.kindClass(bp("x > 1", "", false)));
    }

    @Test
    void theFolderIsProjectRelativeOrHomeShortenedAndComesAfterWhatTheBreakpointDoes() {
        Path root = Path.of("/home/ada/work/app");
        Path home = Path.of("/home/ada");
        assertEquals("", BreakpointsPicker.folder(root, root, home), "at the project root: no folder at all");
        assertEquals("src/demo", BreakpointsPicker.folder(root.resolve("src/demo"), root, home));
        assertEquals("~/notes", BreakpointsPicker.folder(home.resolve("notes"), root, home));
        // Elsewhere: the folder as the platform writes it (a backslash path on Windows).
        String opt = Path.of("/opt/lib").toString();
        assertEquals(opt, BreakpointsPicker.folder(Path.of("/opt/lib"), root, home));
        assertEquals(opt, BreakpointsPicker.folder(Path.of("/opt/lib"), null, null));

        BreakpointsPicker.Entry e =
                new BreakpointsPicker.Entry(root.resolve("src/demo/Main.java"), "k", 2, bp("x > 1", "", true), null);
        String detail = BreakpointsPicker.detail(e, root, home);
        assertTrue(detail.endsWith("  ·  src/demo"), detail);
        assertFalse(detail.contains("/home/ada"), detail);
        assertEquals("Main.java:3  x();", BreakpointsPicker.label(e), "the kind is the glyph, not a text mark");
    }

    // --- C108: the chord of a command bound through a wrapper -------------------------------------------

    @Test
    void stepIntoShowsTheChordOfTheCommandThatWrapsIt() {
        Map<String, String> cua = Map.of("debug.stepIntoOrFullScreen", "F11", "debug.stepOver", "F10");
        assertEquals("F11", DebugPanelText.chordOf(cua::get, "debug.stepInto"));
        assertEquals("F10", DebugPanelText.chordOf(cua::get, "debug.stepOver"));
        Map<String, String> vscode = Map.of("debug.stepInto", "F11");
        assertEquals("F11", DebugPanelText.chordOf(vscode::get, "debug.stepInto"));
        assertNull(DebugPanelText.chordOf(Map.<String, String>of()::get, "debug.stepInto"));
        assertNull(DebugPanelText.chordOf(cua::get, "debug.pause"));
    }

    // --- C111: values on a UI theme and an editor theme of opposite lightness ----------------------------

    @Test
    void tokenColoursAreDroppedOnlyWhenTheThemesDisagreeOnLightness() {
        Color white = Color.WHITE;
        Color dark = Color.web("#1b1e2a");
        assertFalse(DebugPanel.themesDisagree(white, Color.web("#f5f5f7")));
        assertFalse(DebugPanel.themesDisagree(dark, Color.web("#282a36")));
        assertTrue(DebugPanel.themesDisagree(dark, white), "dark UI, light editor theme");
        assertTrue(DebugPanel.themesDisagree(white, dark), "light UI, dark editor theme");
        assertTrue(DebugPanel.themesDisagree(
                Themes.backgroundFor("Editora Dark"), EditorThemes.editorBackgroundFor("Editora Light")));
        assertFalse(DebugPanel.themesDisagree(
                Themes.backgroundFor("Editora Dark"), EditorThemes.editorBackgroundFor("Editora Dark")));
    }

    // --- C103: the Run menu -----------------------------------------------------------------------------

    @Test
    void theRunMenuKeepsSessionActionsOnTopAndTheRestInSubmenus() {
        MenuBarModel.MenuSpec run = MenuBarModel.menus().stream()
                .filter(m -> m.titleKey().equals("menubar.run"))
                .findFirst()
                .orElseThrow();
        List<String> top = run.entries();
        for (String id : List.of("debug.start", "debug.stop", "debug.restart", "debug.attach", "debug.mainClass")) {
            assertTrue(top.contains(id), id + " stays at the top of Run");
        }
        for (String id : List.of("debug.stepOver", "debug.evaluate", "debug.breakpoints", "debug.exceptions.caught")) {
            assertFalse(top.contains(id), id + " is in a submenu");
        }
        // One Debugger submenu at first; four by purpose since it grew past the screen's height.
        for (String key : List.of(
                "menubar.run.launch", "menubar.run.stepping", "menubar.run.breakpoints", "menubar.run.debugger")) {
            assertTrue(run.submenus().stream().anyMatch(m -> m.titleKey().equals(key)), key);
        }
        assertTrue(run.allEntries().containsAll(List.of("debug.stepOver", "debug.stepInto", "debug.evaluate")));
        long rows =
                top.stream().filter(id -> !id.equals(MenuBarModel.SEPARATOR)).count();
        assertTrue(rows <= 20, "Run fits a laptop screen: " + rows + " rows");
    }
}
