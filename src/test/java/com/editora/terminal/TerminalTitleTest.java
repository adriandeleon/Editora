package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the tab and the title bar say. Every case here is something a user reads and believes.
 */
class TerminalTitleTest {

    private static final String HOME = "/home/adl";

    // --- the ordinary case: no shell title, so the directory answers -------------------------

    @Test
    void theWindowShowsThePathAndTheTabShowsItsLastSegment() {
        assertEquals("~/src/adl/Editora", TerminalTitle.window("", "/home/adl/src/adl/Editora", HOME));
        assertEquals("Editora", TerminalTitle.tab("", "/home/adl/src/adl/Editora", HOME));
    }

    @Test
    void homeItselfStaysTildeInBothPlaces() {
        // Not "adl": the tab is meant to say where you are, and the last segment of the home
        // directory is the user's own login name, which is the same in every tab they will ever open.
        assertEquals("~", TerminalTitle.window("", HOME, HOME));
        assertEquals("~", TerminalTitle.tab("", HOME, HOME));
    }

    @Test
    void aPathOutsideHomeIsShownAsItIs() {
        assertEquals("/etc/apt", TerminalTitle.window("", "/etc/apt", HOME));
        assertEquals("apt", TerminalTitle.tab("", "/etc/apt", HOME));
    }

    @Test
    void rootIsShownAsRootRatherThanAsNothing() {
        assertEquals("/", TerminalTitle.window("", "/", HOME));
        assertEquals("/", TerminalTitle.tab("", "/", HOME));
    }

    // --- the shell's own title wins -----------------------------------------------------------

    @Test
    void aTitleTheShellSetTakesPrecedenceOverTheDirectory() {
        // vim naming the file being edited is more use than the directory it is obviously in.
        assertEquals("vim README.md", TerminalTitle.window("vim README.md", "/home/adl/src", HOME));
        assertEquals("vim README.md", TerminalTitle.tab("vim README.md", "/home/adl/src", HOME));
    }

    @Test
    void clearingTheShellTitleGivesTheDirectoryBack() {
        // The case that motivates keeping the two apart: a program sets a title, then resets it to
        // empty on exit. The directory has to reappear, which it only can if it was never lost.
        assertEquals("~/src", TerminalTitle.window("", "/home/adl/src", HOME));
        assertEquals("~/src", TerminalTitle.window("   ", "/home/adl/src", HOME));
    }

    @Test
    void withNeitherATitleNorADirectoryTheAppNamesItself() {
        assertEquals(TerminalTitle.UNTITLED, TerminalTitle.window("", "", HOME));
        assertEquals(TerminalTitle.UNTITLED, TerminalTitle.tab("", "", HOME));
        assertEquals(TerminalTitle.UNTITLED, TerminalTitle.window(null, null, HOME));
        assertEquals(TerminalTitle.UNTITLED, TerminalTitle.tab(null, null, HOME));
    }

    // --- the tab's hover ------------------------------------------------------------------------

    @Test
    void theTooltipIsThePathTheTabHadNoRoomFor() {
        assertEquals("~/src/Editora", TerminalTitle.tooltip("", "/home/adl/src/Editora", HOME));
        assertEquals("Editora", TerminalTitle.tab("", "/home/adl/src/Editora", HOME));
    }

    @Test
    void theTooltipKeepsThePathWhenTheShellHasSetATitle() {
        // The one place shellTitle is deliberately ignored, and the case the hover exists for: the
        // tab has already spent its width saying "vim", so repeating it adds nothing, while the
        // directory that program is running in has nowhere else to appear.
        assertEquals("~/src/Editora", TerminalTitle.tooltip("vim README.md", "/home/adl/src/Editora", HOME));
        assertEquals("vim README.md", TerminalTitle.tab("vim README.md", "/home/adl/src/Editora", HOME));
    }

    @Test
    void thereIsNoTooltipWhenTheDirectoryIsUnknown() {
        // Windows has no ProcessCwd, so this is every tab there rather than an edge case. Nothing is
        // shown for it at all — a tooltip bound to "" is a small empty box, which reads as broken.
        assertEquals("", TerminalTitle.tooltip("vim", "", HOME));
        assertEquals("", TerminalTitle.tooltip("", "", HOME));
        assertEquals("", TerminalTitle.tooltip("", null, HOME));
    }

    // --- collapseHome, where the tempting implementation is wrong ------------------------------

    @Test
    void onlyWholeSegmentsCollapse() {
        // A prefix match on the string would rewrite this to "~iteral", naming a directory that
        // does not exist and hiding one that does.
        assertEquals("/home/adliteral", TerminalTitle.collapseHome("/home/adliteral", HOME));
        assertEquals("/home/adl2/src", TerminalTitle.collapseHome("/home/adl2/src", HOME));
    }

    @Test
    void aTrailingSlashOnHomeDoesNotBreakTheMatch() {
        assertEquals("~/src", TerminalTitle.collapseHome("/home/adl/src", "/home/adl/"));
        assertEquals("~", TerminalTitle.collapseHome("/home/adl", "/home/adl/"));
    }

    @Test
    void withNoHomeKnownThePathIsLeftAlone() {
        assertEquals("/home/adl/src", TerminalTitle.collapseHome("/home/adl/src", ""));
        assertEquals("/home/adl/src", TerminalTitle.collapseHome("/home/adl/src", null));
    }

    // --- baseName -----------------------------------------------------------------------------

    @Test
    void baseNameTakesTheLastSegment() {
        assertEquals("Editora", TerminalTitle.baseName("/home/adl/src/Editora"));
        assertEquals("Editora", TerminalTitle.baseName("/home/adl/src/Editora/"));
        assertEquals("tmp", TerminalTitle.baseName("/tmp"));
        assertEquals("relative", TerminalTitle.baseName("relative"));
    }

    @Test
    void baseNameOfRootIsRoot() {
        assertEquals("/", TerminalTitle.baseName("/"));
        assertEquals("/", TerminalTitle.baseName("///"));
    }

    @Test
    void aDirectoryWithASpaceOrADotSurvivesIntact() {
        assertEquals("My Project", TerminalTitle.tab("", "/home/adl/My Project", HOME));
        assertEquals(".config", TerminalTitle.tab("", "/home/adl/.config", HOME));
    }
}
