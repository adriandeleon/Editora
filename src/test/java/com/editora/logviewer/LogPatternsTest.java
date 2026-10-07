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
    void detectsLevelsThatOpenTheLineInAnyCase() {
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("info: Microsoft.Hosting.Lifetime[14]")); // .NET
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("fail: Microsoft.AspNetCore.Server.Kestrel[13]"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("warn: Microsoft.AspNetCore[0]"));
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("Error: Cannot find module 'x'"));
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("2026-10-06T12:00:00Z error something failed"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("2026-10-06 12:00:00,123 warning slow query"));
        assertEquals(
                LogLevel.ERROR,
                LogPatterns.levelOf("Oct  6 12:00:00 host sshd[123]: error: PAM: Authentication failure"));
        // Without the colon, a first word is prose.
        assertNull(LogPatterns.levelOf("Note the following before upgrading"));
        assertNull(LogPatterns.levelOf("Fail fast is the policy here"));
        assertNull(LogPatterns.levelOf("more info: see the manual"));
    }

    /** Microsoft.Extensions.Logging's console formatter: a four-letter level, a colon, then the category. */
    @Test
    void detectsAllSixDotNetConsoleLevels() {
        assertEquals(LogLevel.TRACE, LogPatterns.levelOf("trce: Shop.Cart.CartService[0]"));
        assertEquals(LogLevel.DEBUG, LogPatterns.levelOf("dbug: Shop.Cart.CartService[0]"));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("info: Microsoft.Hosting.Lifetime[14]"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("warn: Microsoft.AspNetCore[0]"));
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("fail: Microsoft.AspNetCore.Server.Kestrel[13]"));
        assertEquals(LogLevel.FATAL, LogPatterns.levelOf("crit: Shop.Program[0]"));
        // The indented message line under the header belongs to that record.
        assertNull(LogPatterns.levelOf("      Now listening on: http://localhost:5000"));
        assertEquals(LogLevel.DEBUG, LogLevel.fromToken("dbug"));
        assertEquals(LogLevel.TRACE, LogLevel.fromToken("trce"));
        // Lowercase is a level only as the first word plus a colon; elsewhere it is ordinary text.
        assertNull(LogPatterns.levelOf("the dbug build is slower"));
        assertNull(LogPatterns.levelOf("set trce: off in the config"));
        assertNull(LogPatterns.levelOf("dbug and trce are .NET abbreviations"));
    }

    @Test
    void detectsStructuredLevelsWhereverTheyAreInAJsonRecord() {
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("{\"level\":50,\"time\":1696,\"msg\":\"pino\"}"));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("{\"level\":30,\"msg\":\"pino\"}"));
        assertEquals(LogLevel.FATAL, LogPatterns.levelOf("{\"msg\":\"bunyan\",\"level\": 60}"));
        String late = "{\"time\":\"2026-10-06T12:00:00Z\",\"padding\":\"" + "x".repeat(120) + "\",\"level\":\"error\"}";
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf(late), "the level key is past the scanned prefix");
    }

    @Test
    void detectsKlog() {
        assertEquals(LogLevel.ERROR, LogPatterns.levelOf("E1006 12:00:00.000000       1 reflector.go:138] failed"));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("I1006 12:00:00.000000       1 main.go:1] started"));
        assertEquals(LogLevel.WARN, LogPatterns.levelOf("W1006 12:00:00.000000       1 x.go:1] slow"));
    }

    @Test
    void aLevelWordInsideAnIdentifierOrFileNameIsNotALevel() {
        assertNull(LogPatterns.levelOf("\tat com.example.ERROR_CODES.lookup(ERROR_CODES.java:12)"));
        assertNull(LogPatterns.levelOf("    at com.example.Config.load(CONFIG.java:1)"));
        assertNull(LogPatterns.levelOf("Caused by: java.lang.IllegalStateException: ERROR 42"), "a trace line");
        assertNull(LogPatterns.levelOf("loading com.example.ERROR handler"));
        assertNull(LogPatterns.levelOf("see CONFIG.md for details"));
        assertEquals(LogLevel.INFO, LogPatterns.levelOf("2026-10-06 12:00:00 INFO  Loaded ERROR_PAGE handler"));
    }

    @Test
    void everyLevelWordIsBothMappedAndMatchable() {
        for (String word : LogLevel.words()) {
            assertEquals(
                    LogLevel.fromToken(word),
                    LogPatterns.levelOf("2026-10-06 12:00:00 " + word + " something"),
                    word + " is in the vocabulary, so a line with it must be detected");
        }
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

    /** The grammar colours the level word; the model decides the level. They must know the same words. */
    @Test
    void theGrammarColoursExactlyTheWordsTheModelMaps() throws Exception {
        String grammar;
        try (var in = LogPatternsTest.class.getResourceAsStream("/com/editora/grammars/log.tmLanguage.json")) {
            grammar = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.Set<String> coloured = new java.util.TreeSet<>();
        var rule = java.util.regex.Pattern.compile(
                        "\"match\":\\s*\"\\\\\\\\b\\(\\?:([A-Z|]+)\\)\\\\\\\\b\",\\s*\"name\":\\s*\"markup\\.(\\w+)\\.log\"")
                .matcher(grammar);
        while (rule.find()) {
            LogLevel scope = LogLevel.fromToken(rule.group(2).equals("warning") ? "WARN" : rule.group(2));
            for (String word : rule.group(1).split("\\|")) {
                coloured.add(word);
                LogLevel level = LogLevel.fromToken(word);
                // FATAL has no scope of its own: it is coloured as an error.
                assertEquals(
                        scope,
                        level == LogLevel.FATAL ? LogLevel.ERROR : level,
                        word + " is coloured as another level");
            }
        }
        assertEquals(new java.util.TreeSet<>(LogLevel.words()), coloured);
    }
}
