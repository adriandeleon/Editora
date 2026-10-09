package com.editora;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line as it can really arrive: no argument list at all (an embedding launcher), a {@code null}
 * among the arguments (the single-instance pipe decoding a damaged record), an option at the very end with
 * its value missing.
 */
class AppArgsEdgesTest {

    private static List<String> withNull(String... args) {
        List<String> list = new ArrayList<>(Arrays.asList(args));
        list.add(1, null);
        return list;
    }

    @Test
    void noArgumentListAtAllIsALaunchWithNoOptions() {
        assertNull(App.configDirArg(null));
        assertNull(App.projectArg(null));
        assertNull(App.newFileArg(null));
        assertNull(App.singleWindowArg(null));
        assertNull(App.diffUiArg(null));
        assertFalse(App.devFlag(null));
        assertFalse(App.newInstanceFlag(null));
        assertFalse(App.zenFlag(null));
        assertFalse(App.expertFlag(null));
        assertFalse(App.simpleFlag(null));
        assertFalse(App.diffUiFlag(null));
        assertFalse(App.noSessionFlag(null));
        assertFalse(App.shouldForwardLaunch(null), "nothing to hand to a running editor");
        assertEquals(List.of(), App.fileTargets(null));
        assertEquals(List.of(), App.fileTargets(null, p -> true));
    }

    @Test
    void aNullAmongTheArgumentsIsSkippedAndTheRestStillCount() {
        assertEquals("/cfg", App.configDirArg(withNull("--zen", "--config-dir=/cfg")));
        assertEquals("/cfg", App.configDirArg(withNull("--zen", "--config-dir", "/cfg")));
        assertEquals("/repo", App.projectArg(withNull("--zen", "--project", "/repo")));
        assertEquals("/repo", App.projectArg(withNull("--zen", "--project=/repo")));
        assertEquals("", App.newFileArg(withNull("--zen", "--new-file")));
        assertEquals("a.txt", App.newFileArg(withNull("--zen", "--new-file= a.txt ")));
        assertEquals("", App.singleWindowArg(withNull("--zen", "--single-window")));
        assertEquals("Proj", App.singleWindowArg(withNull("--zen", "--single-window=Proj")));
        assertEquals(
                1,
                App.fileTargets(withNull("--zen", "", "notes.txt"), p -> true).size());
        assertTrue(App.zenFlag(withNull("--zen", "x")));
    }

    @Test
    void anOptionWhoseValueIsMissingOrBlankNamesNothing() {
        List<String> endsWithNull = new ArrayList<>(List.of("--config-dir"));
        endsWithNull.add(null);
        assertNull(App.configDirArg(endsWithNull));
        assertNull(App.configDirArg(List.of("--config-dir", "   ")));
        List<String> projectThenNull = new ArrayList<>(List.of("--project"));
        projectThenNull.add(null);
        assertNull(App.projectArg(projectThenNull));
        assertNull(App.projectArg(List.of("--project", "  ")));
        assertNull(App.projectArg(List.of("--project=")));
        assertNull(App.projectArg(List.of("--project")));
    }

    @Test
    void aDiffNeedsTwoPathsThatAreReallyPaths() {
        assertNull(App.diffUiArg(List.of("a.txt", "b.txt")), "no --diff-ui, no diff");
        var request = App.diffUiArg(List.of("--zen", "--diff-ui", "left.txt", "right.txt"));
        assertEquals(Path.of("left.txt"), request.left());
        assertEquals(Path.of("right.txt"), request.right());

        for (List<String> bad : List.of(
                List.of("--diff-ui"),
                List.of("--diff-ui", "only-one.txt"),
                List.of("--diff-ui", "  ", "right.txt"),
                List.of("--diff-ui", "left.txt", ""))) {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> App.diffUiArg(bad), bad.toString());
            assertEquals("--diff-ui requires LEFT and RIGHT paths", refused.getMessage());
        }
        List<String> nullSide = new ArrayList<>(List.of("--diff-ui", "left.txt"));
        nullSide.add(null);
        assertThrows(IllegalArgumentException.class, () -> App.diffUiArg(nullSide));

        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class, () -> App.diffUiArg(List.of("--diff-ui", "a\u0000b", "right.txt")));
        assertTrue(invalid.getMessage().startsWith("--diff-ui received an invalid path"), invalid.getMessage());
    }

    @Test
    void aForwardedLaunchKeepsEveryFocusFlagItWasGiven() {
        Path cwd = Path.of("work").toAbsolutePath();
        assertEquals(
                List.of("--zen", "--expert", "--simple", cwd.resolve("a.txt").toString()),
                App.forwardArgs(List.of("--simple", "--expert", "--zen", "--no-session", "a.txt"), cwd, p -> true));
        assertEquals(List.of(), App.forwardArgs(List.of("--no-session"), cwd, p -> true));
        // The default overload looks at the real filesystem: a file that is not there is still a target the
        // user typed, so it is forwarded and reported as missing by the editor that opens it.
        assertEquals(
                List.of(cwd.resolve("no-such-file-anywhere.txt").toString()),
                App.forwardArgs(List.of("no-such-file-anywhere.txt"), cwd));
    }

    @Test
    void theHelpTextNamesTheProgramAndEveryOptionItParses() {
        Messages.init("en");
        String help = App.helpText();
        assertTrue(help.contains(AppInfo.NAME), help);
        for (String option : List.of(
                "--config-dir",
                "--project",
                "--zen",
                "--expert",
                "--simple",
                "--new-file",
                "--single-window",
                "--no-session",
                "--diff-ui",
                "--new-instance",
                "--dev",
                "--help",
                "--version",
                "FILE:LINE:COLUMN")) {
            assertTrue(help.contains(option), option + " is documented in --help");
            if (option.startsWith("--") && !List.of("--help", "--version").contains(option)) {
                assertTrue(App.isEditoraOption(option), option + " is recognised as Editora's own");
            }
        }
    }

    @Test
    void theSingleWindowEntryKeepsItsContinuationLine() {
        // The "instead of restoring all windows" line explains --single-window; it once sat under --no-session,
        // which made that option read as if it rewrote the saved layout.
        Messages.init("en");
        List<String> lines = App.helpText().lines().toList();
        int singleWindow = indexOfOption(lines, "--single-window");
        int noSession = indexOfOption(lines, "--no-session");
        assertEquals(singleWindow + 1, indexOfLineContaining(lines, "instead of restoring all windows"));
        assertEquals(singleWindow + 2, noSession, "--no-session follows --single-window and its continuation");
        assertTrue(lines.get(noSession + 1).trim().startsWith("--"), "--no-session has no continuation line");
    }

    private static int indexOfOption(List<String> lines, String option) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(option)) {
                return i;
            }
        }
        throw new AssertionError(option + " is documented in --help");
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError(text + " appears in --help");
    }
}
