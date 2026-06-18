package com.editora.logviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPatternsTest {

    @Test
    void detectsSpringBootLevels() {
        assertEquals(
                LogLevel.INFO, LogPatterns.levelOf("2024-01-02 10:11:12.345  INFO 1234 --- [main] c.e.App : started"));
        assertEquals(
                LogLevel.ERROR, LogPatterns.levelOf("2024-01-02 10:11:12.345 ERROR 1234 --- [main] c.e.App : boom"));
        assertEquals(
                LogLevel.WARN, LogPatterns.levelOf("2024-01-02 10:11:12.345  WARN 1234 --- [main] c.e.App : careful"));
    }

    @Test
    void detectsBracketedAndJulAndSyslogLevels() {
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("[ERROR] something failed"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("Jan 02 10:11:12 host app[12]: WARNING low disk"));
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("SEVERE: NullPointerException")); // java.util.logging
        assertEquals(LogLevel.DEBUG, LogPatterns.levelOf("10:11:12 FINE cache miss")); // JUL FINE -> DEBUG
        assertEquals(LogLevel.FATAL, LogPatterns.levelOf("<2>kernel: EMERG meltdown"));
    }

    @Test
    void doesNotMatchLevelWordInsideMessageBody() {
        // "error" deep in the message must not reclassify an INFO line.
        String line = "2024-01-02 10:11:12 INFO  handled the previous error gracefully and recovered fully";
        assertEquals(LogLevel.INFO, LogPatterns.levelOf(line));
        // A line whose only "error" is far past the scan prefix stays unleveled.
        assertNull(LogPatterns.levelOf("a plain sentence that merely mentions an error near its tail end "
                + "after a very very very very very very long lead in segment here"));
    }

    @Test
    void detectsLowercaseLevelsOnlyWhenBracketedOrKeyValue() {
        // nginx-style bracketed lowercase.
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("2024/01/02 10:11:12 [error] 123#0: open() failed"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("2024/01/02 10:11:12 [warn] upstream slow"));
        // structured key=value / JSON.
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("ts=2024 level=error msg=\"db down\""));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("{\"level\":\"info\",\"msg\":\"ok\"}"));
        // zerolog 3-letter uppercase.
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("10:11AM ERR something broke"));
        // a bare lowercase "error" in prose is NOT a level.
        assertNull(LogPatterns.levelOf("the deployment finished without any error at all today"));
    }

    @Test
    void mapsAccessLogStatusToLevel() {
        String base = "127.0.0.1 - - [02/Jan/2024:10:11:12 +0000] \"GET /x HTTP/1.1\" ";
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf(base + "500 12"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf(base + "404 0"));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf(base + "200 1024"));
    }

    @Test
    void continuationAndBlankLinesAreUnleveled() {
        assertNull(LogPatterns.levelOf("\tat com.example.App.run(App.java:42)"));
        assertNull(LogPatterns.levelOf("    ... 17 more"));
        assertNull(LogPatterns.levelOf(""));
        assertNull(LogPatterns.levelOf("   "));
    }

    @Test
    void looksLikeLogSniff() {
        String log = String.join(
                "\n",
                "2024-01-02 10:11:12 INFO  starting",
                "2024-01-02 10:11:13 DEBUG loading config",
                "2024-01-02 10:11:14 WARN  retrying",
                "2024-01-02 10:11:15 ERROR gave up");
        assertTrue(LogPatterns.looksLikeLog(log));

        String prose = String.join(
                "\n",
                "Dear team,",
                "Here is the weekly update on our project.",
                "We shipped two features and fixed a bug.",
                "Thanks, Alice");
        assertFalse(LogPatterns.looksLikeLog(prose));
        assertFalse(LogPatterns.looksLikeLog(""));
        assertFalse(LogPatterns.looksLikeLog(null));
    }
}
