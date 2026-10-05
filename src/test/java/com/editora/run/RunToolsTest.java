package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        StackTraceLinks.Link l = StackTraceLinks.parse("\tat com.example.Main.run(Main.java:42)");
        assertEquals("Main.java", l.file());
        assertEquals(42, l.line());
        // The synthetic compact-source frame shape works too.
        assertEquals(7, StackTraceLinks.parse("\tat Hello.main(Hello.java:7)").line());
    }

    @Test
    void parseFindsPythonAndNodeFrames() {
        StackTraceLinks.Link py = StackTraceLinks.parse("  File \"/tmp/app/main.py\", line 13, in <module>");
        assertEquals("/tmp/app/main.py", py.file());
        assertEquals(13, py.line());
        StackTraceLinks.Link js = StackTraceLinks.parse("    at doIt (/tmp/app/index.js:9:15)");
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

    /**
     * The whole console line is kept alongside the parsed pieces: jdtls's
     * {@code java.project.resolveStackTraceLocation} resolves a frame from the <em>line</em>, not from the
     * file name we picked out of it, and it can place frames this regex never could — inside a dependency
     * or the JDK (#744).
     */
    @Test
    void aParsedLinkKeepsTheWholeConsoleLine() {
        String line = "\tat demo.Person.of(Person.java:12)";

        StackTraceLinks.Link link = StackTraceLinks.parse(line);

        assertNotNull(link);
        assertEquals(line, link.raw());
        assertEquals("Person.java", link.file());
        assertEquals(12, link.line());
    }

    @Test
    void everyRecognizedFormatCarriesItsRawLine() {
        String python = "  File \"/src/app.py\", line 7, in main";
        String node = "    at run (/srv/app/index.js:44:9)";

        assertEquals(python, StackTraceLinks.parse(python).raw());
        assertEquals(node, StackTraceLinks.parse(node).raw());
    }

    /** The two-arg form still works for callers that never needed the original line. */
    @Test
    void theShortLinkFormLeavesTheRawLineNull() {
        assertNull(new StackTraceLinks.Link("A.java", 3).raw());
    }

    @Test
    void consoleUrlsFindHttpLinksAndTrimSurroundingPunctuation() {
        String line = "Local http://localhost:4321/ and docs (https://example.com/a_(b)).";

        List<ConsoleUrls.Link> links = ConsoleUrls.find(line);

        assertEquals(
                List.of("http://localhost:4321/", "https://example.com/a_(b)"),
                links.stream().map(ConsoleUrls.Link::url).toList());
        assertEquals(links.get(0), ConsoleUrls.at(line, line.indexOf("localhost")));
        assertNull(ConsoleUrls.at(line, 0));

        String multiline = "plain\nsee https://example.com/docs\nafter";
        ConsoleUrls.Link multilineLink = ConsoleUrls.at(multiline, multiline.indexOf("example"));
        assertEquals(multiline.indexOf("https://"), multilineLink.start());
        assertEquals("https://example.com/docs", multilineLink.url());
    }

    @Test
    void consoleUrlsIgnoreNonWebSchemesAndNullInput() {
        assertEquals(List.of(), ConsoleUrls.find("file:///tmp/a.txt ftp://example.com"));
        assertEquals(List.of(), ConsoleUrls.find(null));
    }

    // --- console link helpers on hostile output ---------------------------------------------------

    @Test
    void aLongUnbrokenTokenDoesNotStallTheNodePattern() {
        String token = "QUJD".repeat(16_000); // 64,000 characters, the pump's line cap
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> assertNull(StackTraceLinks.parse(token)));
        // The anchor must not cost the ordinary frames their links.
        assertEquals(
                "/tmp/app/index.js",
                StackTraceLinks.parse("    at doIt (/tmp/app/index.js:9:15)").file());
        assertEquals(
                "C:\\app\\index.js",
                StackTraceLinks.parse("at C:\\app\\index.js:9:15").file());
    }

    @Test
    void anAbsurdLineNumberIsNotALinkAndDoesNotThrow() {
        assertNull(StackTraceLinks.parse("\tat Foo.bar(Foo.java:99999999999)"));
        assertNull(StackTraceLinks.parse("app.js:12345678901234"));
        assertNull(StackTraceLinks.parse("  File \"/tmp/a.py\", line 99999999999"));
    }

    @Test
    void aFrameWithThousandsOfSlashesDoesNotOverflowTheStack() {
        String raw = "at " + "a/".repeat(20_000) + "com.foo.Bar.baz(Bar.java:12)";
        assertEquals("com/foo/Bar.java", StackTraceLinks.javaSourcePath(new StackTraceLinks.Link("Bar.java", 12, raw)));
    }

    @Test
    void urlLookupOnAnEmptyFirstLineReturnsNothing() {
        String text = "\n> dev\nLocal: http://localhost:5173/\n";
        assertNull(ConsoleUrls.at(text, 0)); // threw StringIndexOutOfBoundsException: Range [1, 0)
        assertEquals(
                "http://localhost:5173/",
                ConsoleUrls.at(text, text.indexOf("http") + 3).url());
    }
}
