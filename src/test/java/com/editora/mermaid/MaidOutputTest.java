package com.editora.mermaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class MaidOutputTest {

    @Test
    void parsesJsonArrayOfErrors() {
        String json = "[{\"line\":2,\"column\":7,\"severity\":\"error\","
                + "\"code\":\"FL-ARROW-INVALID\",\"message\":\"Invalid arrow\"}]";
        List<MaidOutput.Diagnostic> d = MaidOutput.parse(json);
        assertEquals(1, d.size());
        assertEquals(2, d.get(0).line());
        assertEquals(7, d.get(0).column());
        assertEquals("FL-ARROW-INVALID", d.get(0).code());
        assertEquals("Invalid arrow", d.get(0).message());
        assertTrue(d.get(0).isError());
    }

    @Test
    void parsesJsonObjectWithErrorsContainer() {
        String json = "{\"valid\":false,\"errors\":["
                + "{\"line\":12,\"column\":3,\"severity\":\"error\",\"code\":\"SE-AND\",\"message\":\"m\"},"
                + "{\"line\":1,\"column\":1,\"severity\":\"warning\",\"code\":\"W1\",\"message\":\"w\"}]}";
        List<MaidOutput.Diagnostic> d = MaidOutput.parse(json);
        assertEquals(2, d.size());
        assertEquals(12, d.get(0).line());
        assertEquals("warning", d.get(1).severity());
    }

    @Test
    void parsesRealMaidJsonOutput() {
        // Verbatim shape from `npx -y @probelabs/maid --format json` (errors[] with line/column/code/message,
        // a hint, and a second error that omits "code").
        String json = "{\"file\":\"<stdin>\",\"valid\":false,\"errorCount\":2,\"warningCount\":0,"
                + "\"errors\":[{\"line\":3,\"column\":5,\"message\":\"Invalid arrow syntax: -> (use --> instead)\","
                + "\"severity\":\"error\",\"code\":\"FL-ARROW-INVALID\",\"hint\":\"Replace -> with -->\",\"length\":2},"
                + "{\"line\":2,\"column\":9,\"severity\":\"error\",\"message\":\"Expecting tokens\",\"length\":1}],"
                + "\"warnings\":[],\"diagramCount\":1}";
        List<MaidOutput.Diagnostic> d = MaidOutput.parse(json);
        assertEquals(2, d.size());
        assertEquals(3, d.get(0).line());
        assertEquals(5, d.get(0).column());
        assertEquals("FL-ARROW-INVALID", d.get(0).code());
        assertTrue(d.get(0).message().startsWith("Invalid arrow"));
        assertEquals(2, d.get(1).line());
        assertEquals("", d.get(1).code()); // second error has no code
    }

    @Test
    void parsesTextFallback() {
        String text = "error[FL-ARROW-INVALID]: Invalid arrow syntax: -> (use --> instead) "
                + "at diagram.mmd:2:7\n  hint: use -->";
        List<MaidOutput.Diagnostic> d = MaidOutput.parse(text);
        assertEquals(1, d.size());
        assertEquals(2, d.get(0).line());
        assertEquals(7, d.get(0).column());
        assertEquals("FL-ARROW-INVALID", d.get(0).code());
        assertTrue(d.get(0).message().startsWith("Invalid arrow syntax"));
    }

    @Test
    void emptyOrCleanOutputYieldsNoDiagnostics() {
        assertTrue(MaidOutput.parse("").isEmpty());
        assertTrue(MaidOutput.parse(null).isEmpty());
        assertTrue(MaidOutput.parse("All diagrams valid").isEmpty());
    }

    @Test
    void commandTokenizesPathOrDefault() {
        assertEquals(List.of("mmdc"), Mermaid.command("", "mmdc"));
        assertEquals(List.of("mmdc"), Mermaid.command(null, "mmdc"));
        assertEquals(List.of("/opt/mmdc"), Mermaid.command("  /opt/mmdc  ", "mmdc"));
        // A multi-token command (e.g. the maid default) splits on whitespace.
        assertEquals(List.of("npx", "-y", "@probelabs/maid"),
                Mermaid.command("", "npx -y @probelabs/maid"));
        assertEquals(List.of("npx", "-y", "@probelabs/maid"),
                Mermaid.command("npx -y @probelabs/maid", "maid"));
    }

    @Test
    void renderArgsPrefixesTheBaseCommand() {
        List<String> args = Mermaid.renderArgs(List.of("mmdc"), Path.of("/tmp/in.mmd"),
                Path.of("/tmp/out.png"), true);
        assertEquals(List.of("mmdc", "-i", "/tmp/in.mmd", "-o", "/tmp/out.png", "-t", "dark",
                "-b", "transparent", "-s", "2"), args);
        assertEquals("default",
                Mermaid.renderArgs(List.of("mmdc"), Path.of("a"), Path.of("b"), false).get(6));
        // Multi-token base (npx) is preserved at the front.
        assertEquals(List.of("npx", "-y", "mmdc-pkg", "-i", "a", "-o", "b", "-t", "dark", "-b",
                "transparent", "-s", "2"),
                Mermaid.renderArgs(List.of("npx", "-y", "mmdc-pkg"), Path.of("a"), Path.of("b"), true));
    }
}
