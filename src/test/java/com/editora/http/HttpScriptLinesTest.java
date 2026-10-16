package com.editora.http;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import com.editora.dap.LaunchConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which {@code .http} lines take a breakpoint, and the flags and attach request of a debugged script. */
class HttpScriptLinesTest {

    private static final String TEXT = String.join(
            "\n",
            "### one", // 0
            "< {%", // 1
            "  request.variables.set('a', 1);", // 2
            "%}", // 3
            "GET http://h/a", // 4
            "", // 5
            "> {% client.log(1) %}", // 6
            "> ./check.js", // 7
            "", // 8
            "### two", // 9
            "GET http://h/b", // 10
            "");

    @Test
    void scriptLinesAreTheInlineBlocksOnly() {
        BitSet lines = HttpScriptLines.of(TEXT);
        assertEquals("{1, 2, 3, 6}", lines.toString());
        assertTrue(HttpScriptLines.isScriptLine(TEXT, 2));
        assertFalse(HttpScriptLines.isScriptLine(TEXT, 4), "the request line");
        assertFalse(HttpScriptLines.isScriptLine(TEXT, 7), "a script file's breakpoints go in that file");
        assertFalse(HttpScriptLines.isScriptLine(TEXT, -1));
        assertTrue(HttpScriptLines.of("GET http://h/\n").isEmpty());
    }

    @Test
    void onlyARequestWithAScriptHasSomethingToDebug() {
        assertTrue(HttpScriptLines.requestHasScripts(TEXT, 0));
        assertTrue(HttpScriptLines.requestHasScripts(TEXT, 7));
        assertFalse(HttpScriptLines.requestHasScripts(TEXT, 10));
        assertFalse(HttpScriptLines.requestHasScripts("GET http://h/\n", 0));
    }

    @Test
    void aDebuggedScriptWaitsOnALoopbackInspector() {
        assertEquals(
                List.of("--allow-inspector", "--inspect-brk=127.0.0.1:0"),
                HttpScripts.debugFlags(HttpScripts.Guard.PERMISSION));
        assertEquals(List.of("--inspect-brk=127.0.0.1:0"), HttpScripts.debugFlags(HttpScripts.Guard.NONE));
        Map<String, Object> attach =
                LaunchConfig.nodeAttach("pwa-node", "Response handler of login", 9229, List.of("/c/s.js"));
        assertEquals("attach", attach.get("request"));
        assertEquals("127.0.0.1", attach.get("address"));
        assertEquals(9229, attach.get("port"));
        assertEquals(true, attach.get("continueOnAttach"));
        assertEquals(List.of("/c/s.js"), attach.get("skipFiles"));
        assertEquals("Response handler of login", attach.get("name"));
    }
}
