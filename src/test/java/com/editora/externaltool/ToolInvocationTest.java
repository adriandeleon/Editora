package com.editora.externaltool;

import java.nio.file.Path;
import java.util.List;

import com.editora.externaltool.ExternalTool.OutputTarget;
import com.editora.externaltool.ExternalTool.StdinSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Building a runnable invocation: argv tokenization, stdin mapping, working-dir default + macro. */
class ToolInvocationTest {

    private static ToolContext ctx() {
        return new ToolContext("/p/App.java", "/p", "App.java", "App", "sel", 3, 1, "/proj", "BUF");
    }

    private static ExternalTool tool(String cmd, String args, String wd, StdinSource in) {
        return new ExternalTool("t", cmd, args, wd, in, OutputTarget.CONSOLE, true);
    }

    @Test
    void tokenizesCommandAndArgsWithQuotesAndMacros() {
        ToolInvocation inv = ToolInvocation.of(tool("jq", "-r '.a b' $FilePath$", "", StdinSource.NONE), ctx(), null);
        assertEquals(List.of("jq", "-r", ".a b", "/p/App.java"), inv.argv());
        assertEquals("jq -r .a b /p/App.java", inv.displayCommand());
    }

    @Test
    void stdinSourceMapping() {
        assertNull(ToolInvocation.of(tool("c", "", "", StdinSource.NONE), ctx(), null)
                .stdin());
        assertEquals(
                "sel",
                ToolInvocation.of(tool("c", "", "", StdinSource.SELECTION), ctx(), null)
                        .stdin());
        assertEquals(
                "BUF",
                ToolInvocation.of(tool("c", "", "", StdinSource.BUFFER), ctx(), null)
                        .stdin());
    }

    @Test
    void blankWorkingDirUsesDefault() {
        Path def = Path.of("/default");
        assertEquals(
                def,
                ToolInvocation.of(tool("c", "", "  ", StdinSource.NONE), ctx(), def)
                        .workingDir());
    }

    @Test
    void workingDirMacroExpanded() {
        ToolInvocation inv = ToolInvocation.of(tool("c", "", "$ProjectFileDir$", StdinSource.NONE), ctx(), null);
        assertEquals(Path.of("/proj"), inv.workingDir());
    }

    /** A path with a space is ordinary. Expanding before tokenizing split the user's data into two argv. */
    @Test
    void aMacroAlwaysProducesExactlyOneArgvElement() {
        ToolContext spaced = new ToolContext(
                "/Users/me/My Docs/a.txt", "/Users/me/My Docs", "a.txt", "a", "sel", 1, 1, "/proj", "BUF");
        assertEquals(
                List.of("wc", "-l", "/Users/me/My Docs/a.txt"),
                ToolInvocation.of(tool("wc", "-l $FilePath$", "", StdinSource.NONE), spaced, null)
                        .argv());
    }

    /**
     * Quoting could not rescue it either: {@code '$FilePath$'} survives a space but an apostrophe in the path
     * ({@code ~/Bob's Files/}, ordinary on macOS) closed the quote early — splitting the token AND deleting
     * the apostrophe. Tokenizing the template first makes quoted and unquoted forms both correct.
     */
    @Test
    void aPathWithAnApostropheSurvivesQuotedOrNot() {
        ToolContext apos = new ToolContext(
                "/Users/me/Bob's Files/a.txt", "/Users/me/Bob's Files", "a.txt", "a", "it's fine", 1, 1, "/p", "B");
        assertEquals(
                List.of("wc", "-l", "/Users/me/Bob's Files/a.txt"),
                ToolInvocation.of(tool("wc", "-l $FilePath$", "", StdinSource.NONE), apos, null)
                        .argv());
        assertEquals(
                List.of("wc", "-l", "/Users/me/Bob's Files/a.txt"),
                ToolInvocation.of(tool("wc", "-l '$FilePath$'", "", StdinSource.NONE), apos, null)
                        .argv(),
                "a defensively-quoted template must work too");
        assertEquals(
                List.of("echo", "it's fine"),
                ToolInvocation.of(tool("echo", "$SelectedText$", "", StdinSource.NONE), apos, null)
                        .argv(),
                "a selection is one argument, apostrophe intact");
    }

    /** An empty macro must pass an empty argument, not vanish and shift every positional after it. */
    @Test
    void anEmptyMacroKeepsItsArgvSlot() {
        ToolContext noSel = new ToolContext("/p/a.txt", "/p", "a.txt", "a", "", 1, 1, "/p", "B");
        assertEquals(
                List.of("grep", "", "file.txt"),
                ToolInvocation.of(tool("grep", "$SelectedText$ file.txt", "", StdinSource.NONE), noSel, null)
                        .argv(),
                "was [grep, file.txt] — grep then took file.txt as the PATTERN and read stdin forever");
    }

    /** A macro expanding to something flag-shaped stays one argument (no shell, and no flag injection). */
    @Test
    void aSelectionIsNeverSplitIntoSeparateFlags() {
        ToolContext sel = new ToolContext("/p/a.txt", "/p", "a.txt", "a", "-rf /", 1, 1, "/p", "B");
        assertEquals(
                List.of("mytool", "-rf /"),
                ToolInvocation.of(tool("mytool", "$SelectedText$", "", StdinSource.NONE), sel, null)
                        .argv());
    }
}
