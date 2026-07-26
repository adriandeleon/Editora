package com.editora.process;

import java.util.List;

import com.editora.process.CommandLogFormat.Line;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLogFormatTest {

    private static CommandLog.Entry entry(int exit, String out, String err) {
        return new CommandLog.Entry(List.of("git", "status"), exit, out, err, 12);
    }

    private static List<String> texts(List<Line> lines) {
        return lines.stream().map(Line::text).toList();
    }

    @Test
    void echoesTheCommandThenOutputThenAFooter() {
        List<Line> lines = CommandLogFormat.format(entry(0, "a\nb\n", ""));
        assertEquals(List.of("$ git status", "a", "b", "exit 0 · 12 ms"), texts(lines));
        assertEquals(CommandLogFormat.ECHO_STYLE, lines.get(0).styleClass());
        assertNull(lines.get(1).styleClass());
        assertEquals(CommandLogFormat.FOOTER_STYLE, lines.get(3).styleClass());
    }

    @Test
    void argumentsWithWhitespaceOrQuotesAreQuoted() {
        assertEquals(
                "$ git commit -m \"fix: a thing\"",
                CommandLogFormat.commandLine(List.of("git", "commit", "-m", "fix: a thing")));
        assertEquals(
                "$ gh pr create --body \"say \\\"hi\\\"\"",
                CommandLogFormat.commandLine(List.of("gh", "pr", "create", "--body", "say \"hi\"")));
        assertEquals("$ git \"\"", CommandLogFormat.commandLine(List.of("git", "")));
    }

    /**
     * git writes normal progress to stderr, so a successful push must not come out red — only the exit code
     * says something went wrong.
     */
    @Test
    void stderrIsOnlyReddenedWhenTheCommandFailed() {
        List<Line> ok = CommandLogFormat.format(entry(0, "", "Enumerating objects: 5, done."));
        assertNull(ok.get(1).styleClass());

        List<Line> bad = CommandLogFormat.format(entry(1, "", "fatal: not a git repository"));
        assertEquals(CommandLogFormat.ERROR_STYLE, bad.get(1).styleClass());
        assertEquals(CommandLogFormat.ERROR_STYLE, bad.get(bad.size() - 1).styleClass());
    }

    @Test
    void emptyOutputIsJustTheEchoAndFooter() {
        assertEquals(List.of("$ git status", "exit 0 · 12 ms"), texts(CommandLogFormat.format(entry(0, "", ""))));
        assertEquals(List.of("$ git status", "exit 0 · 12 ms"), texts(CommandLogFormat.format(entry(0, null, null))));
    }

    /** A blank line inside the output is real content; only the trailing newline's empty tail is dropped. */
    @Test
    void interiorBlankLinesSurviveButTheTrailingNewlineDoesNot() {
        assertEquals(
                List.of("$ git status", "a", "", "b", "exit 0 · 12 ms"),
                texts(CommandLogFormat.format(entry(0, "a\n\nb\n", ""))));
    }

    @Test
    void crlfOutputLosesTheCarriageReturn() {
        assertEquals(
                List.of("$ git status", "a", "b", "exit 0 · 12 ms"),
                texts(CommandLogFormat.format(entry(0, "a\r\nb\r\n", ""))));
    }

    /** Truncation must be stated, not silent — a console that hides output without saying so misleads. */
    @Test
    void longOutputIsCappedAndTheDropIsReported() {
        StringBuilder sb = new StringBuilder();
        int total = CommandLogFormat.MAX_OUTPUT_LINES + 37;
        for (int i = 0; i < total; i++) {
            sb.append("line ").append(i).append('\n');
        }
        List<Line> lines = CommandLogFormat.format(entry(0, sb.toString(), ""));
        // echo + MAX output + the "… N more lines" note + footer
        assertEquals(CommandLogFormat.MAX_OUTPUT_LINES + 3, lines.size());
        assertEquals("… 37 more lines", lines.get(lines.size() - 2).text());
    }

    @Test
    void oneDroppedLineIsSingular() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= CommandLogFormat.MAX_OUTPUT_LINES; i++) {
            sb.append(i).append('\n');
        }
        List<Line> lines = CommandLogFormat.format(entry(0, sb.toString(), ""));
        assertEquals("… 1 more line", lines.get(lines.size() - 2).text());
    }

    @Test
    void durationsSwitchToSecondsPastOneThousandMillis() {
        assertEquals("0 ms", CommandLogFormat.duration(0));
        assertEquals("999 ms", CommandLogFormat.duration(999));
        assertEquals("1.0 s", CommandLogFormat.duration(1000));
        assertEquals("1.4 s", CommandLogFormat.duration(1449));
        assertEquals("62.5 s", CommandLogFormat.duration(62_500));
    }

    @Test
    void theNoOpSinkAcceptsAnything() {
        CommandLog.none().record(entry(0, "x", "y")); // must not throw
        assertTrue(true);
    }
}
