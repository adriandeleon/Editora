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
}
