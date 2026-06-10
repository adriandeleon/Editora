package com.editora.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure helpers behind the Run console: version parse, argv tokenizer, stack-trace links. */
class RunToolsTest {

    // --- RunService.javaMajorOf ------------------------------------------------------------------

    @Test
    void javaMajorParsesModernAndLegacySchemes() {
        assertEquals(25, RunService.javaMajorOf("openjdk version \"25.0.3\" 2025-10-21"));
        assertEquals(21, RunService.javaMajorOf("openjdk version \"21\" 2023-09-19"));
        assertEquals(8, RunService.javaMajorOf("java version \"1.8.0_392\""));
        assertEquals(-1, RunService.javaMajorOf("zsh: command not found: java"));
        assertEquals(-1, RunService.javaMajorOf(null));
    }

    // --- ProgramArgs.tokenize --------------------------------------------------------------------

    @Test
    void tokenizeSplitsOnWhitespaceAndHonorsQuotes() {
        assertEquals(List.of("a", "b c", "d e", "f"), ProgramArgs.tokenize("a \"b c\" 'd e'  f"));
        assertEquals(List.of(), ProgramArgs.tokenize("   "));
        assertEquals(List.of(), ProgramArgs.tokenize(null));
        assertEquals(List.of("--name=Jo Do"), ProgramArgs.tokenize("--name=\"Jo Do\""));
        assertEquals(List.of(""), ProgramArgs.tokenize("\"\"")); // an explicit empty arg survives
    }

    // --- StackTraceLinks.parse -------------------------------------------------------------------

    @Test
    void parseFindsJavaFrames() {
        StackTraceLinks.Link l = StackTraceLinks.parse(
                "\tat com.example.Main.run(Main.java:42)");
        assertEquals("Main.java", l.file());
        assertEquals(42, l.line());
        // The synthetic compact-source frame shape works too.
        assertEquals(7, StackTraceLinks.parse("\tat Hello.main(Hello.java:7)").line());
    }

    @Test
    void parseFindsPythonAndNodeFrames() {
        StackTraceLinks.Link py = StackTraceLinks.parse(
                "  File \"/tmp/app/main.py\", line 13, in <module>");
        assertEquals("/tmp/app/main.py", py.file());
        assertEquals(13, py.line());
        StackTraceLinks.Link js = StackTraceLinks.parse(
                "    at doIt (/tmp/app/index.js:9:15)");
        assertEquals("/tmp/app/index.js", js.file());
        assertEquals(9, js.line());
        StackTraceLinks.Link bare = StackTraceLinks.parse("/tmp/app/index.js:3:1");
        assertEquals(3, bare.line());
    }

    @Test
    void parseIgnoresPlainOutput() {
        assertNull(StackTraceLinks.parse("Hello, world"));
        assertNull(StackTraceLinks.parse("total 4 (compiled in 1.2s)"));
        assertNull(StackTraceLinks.parse(""));
        assertNull(StackTraceLinks.parse(null));
    }
}
