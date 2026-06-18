package com.editora.logviewer;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

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
            "2024-01-02 10:00:03 WARN  falling back to cache");

    @Test
    void levelFloorKeepsHigherLevelsOnly() {
        String out = LogFilter.filter(DOC, LogLevel.WARN, null, null);
        assertEquals(
                String.join(
                        "\n",
                        "2024-01-02 10:00:02 ERROR failed to connect",
                        "java.net.ConnectException: refused",
                        "\tat com.example.Db.open(Db.java:42)",
                        "2024-01-02 10:00:03 WARN  falling back to cache"),
                out,
                "ERROR + its inherited stack-trace lines + WARN survive a WARN+ floor");
    }

    @Test
    void continuationLinesInheritPrecedingLevel() {
        // ERROR-only floor must keep the stack trace (unleveled lines inherit ERROR), but drop WARN.
        String out = LogFilter.filter(DOC, LogLevel.ERROR, null, null);
        assertEquals(
                String.join(
                        "\n",
                        "2024-01-02 10:00:02 ERROR failed to connect",
                        "java.net.ConnectException: refused",
                        "\tat com.example.Db.open(Db.java:42)"),
                out);
    }

    @Test
    void regexFilterCombinesWithLevel() {
        Pattern p = Pattern.compile("connect", Pattern.CASE_INSENSITIVE);
        String out = LogFilter.filter(DOC, null, p, null);
        assertEquals(
                String.join("\n", "2024-01-02 10:00:02 ERROR failed to connect", "java.net.ConnectException: refused"),
                out);
    }

    @Test
    void emptyFilterReturnsEverything() {
        assertEquals(DOC, LogFilter.filter(DOC, null, null, null));
        assertEquals("", LogFilter.filter("", LogLevel.ERROR, null, null));
    }

    @Test
    void startCarryAppliesToAppendedChunk() {
        // Simulate filtering an appended tail that begins mid-stack-trace of an earlier ERROR.
        String appended = "\tat com.example.More(More.java:7)\n2024-01-02 10:00:09 INFO done";
        String out = LogFilter.filter(appended, LogLevel.ERROR, null, LogLevel.ERROR);
        assertEquals("\tat com.example.More(More.java:7)", out, "inherited ERROR keeps the frame; the INFO line drops");
    }

    @Test
    void compileFilterTreatsValidInputAsRegexAndInvalidAsLiteral() {
        // A valid regex is honored as a regex (alternation, '.' as any, case-insensitive).
        Pattern alt = LogFilter.compileFilter("ERROR|WARN");
        assertTrue(alt.matcher("a warn here").find());
        assertTrue(alt.matcher("an Error here").find());
        assertFalse(alt.matcher("just info").find());
        assertTrue(LogFilter.compileFilter("a.c").matcher("xabcx").find());

        // An invalid/partial regex falls back to a literal substring match (no exception, still filters).
        Pattern lit = LogFilter.compileFilter("GET /api(v2");
        assertTrue(lit.matcher("127.0.0.1 GET /api(v2/orders 200").find());
        assertFalse(lit.matcher("GET /apiv2/orders").find());

        assertNull(LogFilter.compileFilter(""));
        assertNull(LogFilter.compileFilter(null));
    }

    @Test
    void endCarryTracksLastLevel() {
        assertEquals(LogLevel.WARN, LogFilter.endCarry(DOC, null));
        assertEquals(LogLevel.ERROR, LogFilter.endCarry("\tat x\n\tat y", LogLevel.ERROR));
    }
}
