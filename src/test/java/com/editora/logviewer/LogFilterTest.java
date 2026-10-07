package com.editora.logviewer;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFilterTest {

    private static final String DOC = String.join(
                    "\n",
                    "2024-01-02 10:00:00 INFO  starting up",
                    "2024-01-02 10:00:01 DEBUG loaded 12 beans",
                    "2024-01-02 10:00:02 ERROR failed to connect",
                    "java.net.ConnectException: refused",
                    "\tat com.example.Db.open(Db.java:42)",
                    "2024-01-02 10:00:03 WARN  falling back to cache")
            + "\n";

    @Test
    void levelFloorKeepsHigherLevelsOnly() {
        LogFilter.Run run = LogFilter.run(DOC, LogLevel.WARN, null);
        assertEquals(
                String.join(
                                "\n",
                                "2024-01-02 10:00:02 ERROR failed to connect",
                                "java.net.ConnectException: refused",
                                "\tat com.example.Db.open(Db.java:42)",
                                "2024-01-02 10:00:03 WARN  falling back to cache")
                        + "\n",
                run.text(),
                "the stack trace inherits ERROR and stays with its record");
        assertArrayEquals(new int[] {2, 3, 4, 5}, run.lines(), "each kept line knows its line in the source");
        assertEquals(LogLevel.ERROR.ordinal(), run.levels()[3 - 2], "a trace line carries its record's level");
        assertEquals(4, run.kept());
        assertEquals(6, run.total());
    }

    @Test
    void everyKeptLineEndsWithANewlineSoAppendedLinesCannotJoinTheLast() {
        LogFilter.Run run = LogFilter.run(DOC, null, Pattern.compile("starting"));
        assertEquals("2024-01-02 10:00:00 INFO  starting up\n", run.text());
    }

    @Test
    void aPatternKeepsTheWholeRecordWhenItsFirstLineMatches() {
        LogFilter.Run run = LogFilter.run(DOC, null, Pattern.compile("failed to connect"));
        assertArrayEquals(new int[] {2, 3, 4}, run.lines(), "the trace of a matched record is part of it");
    }

    @Test
    void aPatternKeepsTheWholeRecordWhenOnlyALaterLineMatches() {
        LogFilter.Run run = LogFilter.run(DOC, null, Pattern.compile("Db\\.open"));
        assertArrayEquals(
                new int[] {2, 3, 4},
                run.lines(),
                "the ERROR line and the exception line were held, then kept with the frame that matched");
        assertTrue(run.text().startsWith("2024-01-02 10:00:02 ERROR failed to connect\n"));
    }

    @Test
    void patternAndLevelCombine() {
        LogFilter.Run run = LogFilter.run(DOC, LogLevel.WARN, Pattern.compile("cache|beans"));
        assertArrayEquals(new int[] {5}, run.lines(), "the DEBUG line matches the pattern but not the floor");
    }

    @Test
    void linesBeforeTheFirstRecordStandAlone() {
        String doc = "banner one\nbanner two\n2024-01-02 10:00:00 INFO up\n";
        assertArrayEquals(
                new int[] {1}, LogFilter.run(doc, null, Pattern.compile("two")).lines());
        assertArrayEquals(
                new int[] {2},
                LogFilter.run(doc, LogLevel.INFO, null).lines(),
                "under a floor, lines with no level to inherit are hidden");
    }

    @Test
    void anUnfinishedLastLineIsLeftToTheCaller() {
        String doc = "INFO a\nERROR half writ";
        LogFilter.Run run = LogFilter.run(doc, null, null);
        assertEquals("INFO a\n", run.text());
        assertEquals(7, run.consumed());
        assertEquals(1, run.total());
        assertEquals("ERROR half writ", doc.substring(run.consumed()));
    }

    @Test
    void theFilterGoesOnWhereTheDocumentEnded() {
        LogFilter.Run run = LogFilter.run("2024-01-02 10:00:02 ERROR boom\n", LogLevel.ERROR, null);
        LogFilter.Collector more = new LogFilter.Collector(16);
        run.filter().accept("\tat com.example.More(More.java:7)", 1, more);
        run.filter().accept("2024-01-02 10:00:03 INFO fine again", 2, more);
        assertEquals("\tat com.example.More(More.java:7)\n", more.text.toString(), "inherited ERROR keeps the frame");
    }

    @Test
    void emptyFilterReturnsEverything() {
        assertEquals(DOC, LogFilter.run(DOC, null, null).text());
        assertEquals("", LogFilter.run("", LogLevel.ERROR, null).text());
        assertEquals("", LogFilter.run(null, LogLevel.ERROR, null).text());
    }

    @Test
    void compileFilterTreatsValidInputAsRegexAndInvalidAsLiteral() {
        Pattern alt = LogFilter.compileFilter("error|warn");
        assertTrue(alt.matcher("a warn here").find());
        assertTrue(alt.matcher("an Error here").find());
        assertFalse(alt.matcher("just info").find());
        assertTrue(LogFilter.compileFilter("a.c").matcher("xabcx").find());

        Pattern lit = LogFilter.compileFilter("GET /api(v2");
        assertTrue(lit.matcher("127.0.0.1 GET /api(v2/orders 200").find());
        assertFalse(lit.matcher("GET /apiv2/orders").find());

        assertNull(LogFilter.compileFilter(""));
        assertNull(LogFilter.compileFilter(null));

        assertTrue(LogFilter.isValidRegex("error|warn"));
        assertTrue(LogFilter.isValidRegex(""));
        assertFalse(LogFilter.isValidRegex("GET /api(v2"));
    }
}
