package com.editora.git;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history search typed into the Git Log: its terms, and the {@code git log} argv they become. The argv
 * matters twice — it must find what was asked for, and no user text may be read by git as an option.
 */
class GitLogQueryTest {

    @Test
    void plainWordsAreMessageTextAndEveryOneMustMatch() {
        GitLogQuery q = GitLogQuery.parse("  fix   gutter ");

        assertEquals(List.of("fix", "gutter"), q.message());
        assertEquals(
                List.of("--regexp-ignore-case", "--fixed-strings", "--grep=fix", "--grep=gutter", "--all-match"),
                q.options());
        assertTrue(q.pathspecs().isEmpty());
        assertFalse(q.readsContent());
    }

    @Test
    void keysSelectAuthorContentDatesAndPaths() {
        GitLogQuery q = GitLogQuery.parse(
                "author:ada AUTHOR:grace content:TODO since:2026-01-01 until:yesterday path:src/*.java file:README.md");

        assertEquals(List.of("ada", "grace"), q.authors());
        assertEquals("TODO", q.content());
        assertEquals("2026-01-01", q.since());
        assertEquals("yesterday", q.until());
        assertEquals(List.of("src/*.java", "README.md"), q.paths());
        assertTrue(q.message().isEmpty());
        assertEquals(
                List.of(
                        "--regexp-ignore-case",
                        "--fixed-strings",
                        "--author=ada",
                        "--author=grace",
                        "-STODO",
                        "--since=2026-01-01",
                        "--until=yesterday"),
                q.options());
        assertEquals(List.of(":(top)src/*.java", ":(top)README.md"), q.pathspecs());
        assertTrue(q.readsContent());
    }

    @Test
    void quotesKeepBlanksInsideOneTerm() {
        GitLogQuery q = GitLogQuery.parse("author:\"Ada Lovelace\" \"exact phrase\" since:\"2 weeks ago\" tail");

        assertEquals(List.of("Ada Lovelace"), q.authors());
        assertEquals(List.of("exact phrase", "tail"), q.message());
        assertEquals("2 weeks ago", q.since());
        assertEquals(
                List.of("unclosed quote runs on"),
                GitLogQuery.parse("\"unclosed quote runs on").message());
    }

    @Test
    void thePickaxeHasTwoSpellingsAndTheLastOneWins() {
        assertEquals("needle", GitLogQuery.parse("-Sneedle").content());
        assertEquals("needle", GitLogQuery.parse("-S needle").content());
        assertEquals("two words", GitLogQuery.parse("-S \"two words\"").content());
        assertEquals("last", GitLogQuery.parse("content:first -Slast").content());
        assertEquals(List.of("-S"), GitLogQuery.parse("-S").message(), "a bare -S searches for the text -S");
    }

    @Test
    void unknownAndEmptyKeysAreMessageText() {
        GitLogQuery q = GitLogQuery.parse("fix(log): author: https://example.org x:y after:2026 before:2027");

        assertEquals(List.of("fix(log):", "author:", "https://example.org", "x:y"), q.message());
        assertTrue(q.authors().isEmpty());
        assertEquals("2026", q.since());
        assertEquals("2027", q.until());
    }

    @Test
    void anEmptyQueryAddsNothing() {
        for (String text : new String[] {null, "", "   ", "\"\""}) {
            GitLogQuery q = GitLogQuery.parse(text);
            assertTrue(q.isEmpty(), "empty: " + text);
            assertTrue(q.options().isEmpty());
        }
        assertTrue(GitLogQuery.NONE.isEmpty());
    }

    @Test
    void noUserTextCanBecomeAnOption() {
        // Each of these would be an option, or pathspec magic, if it reached git on its own.
        GitLogQuery q = GitLogQuery.parse(
                "--output=/tmp/x -p author:--all content:--exec=rm since:--follow path:--stdin path::!secret"
                        + " path::(exclude)x \"--grep=a --all\" a\u0000b\tc");

        List<String> args = GitLog.logArgs(new GitLog.Request(false, null, q, 0, 10));

        int separator = args.indexOf("--");
        assertTrue(separator > 0, "the path patterns come after --: " + args);
        for (String option : args.subList(0, separator)) {
            boolean ours = option.equals(String.valueOf(11))
                    || List.of(
                                    "log",
                                    "--no-color",
                                    "--decorate=full",
                                    "--date=short",
                                    "--date-order",
                                    GitLog.FORMAT,
                                    "-n",
                                    "--regexp-ignore-case",
                                    "--fixed-strings",
                                    "--all-match")
                            .contains(option);
            boolean attached = option.startsWith("--grep=")
                    || option.startsWith("--author=")
                    || option.startsWith("--since=")
                    || option.startsWith("-S");
            assertTrue(ours || attached, "user text stands alone as an argument: " + option);
        }
        assertTrue(args.contains("--grep=--output=/tmp/x"));
        assertTrue(args.contains("--grep=-p"));
        assertTrue(args.contains("--grep=--grep=a --all"));
        assertTrue(args.contains("--author=--all"));
        assertTrue(args.contains("-S--exec=rm"));
        assertTrue(args.contains("--since=--follow"));
        assertFalse(args.contains("--all"), "…so the search did not widen to every branch");
        assertEquals(
                List.of(":(top)--stdin", ":(top):!secret", ":(top):(exclude)x"),
                args.subList(separator + 1, args.size()),
                "a pattern is anchored with :(top), after which nothing in it is magic");
        for (String arg : args) {
            assertFalse(arg.indexOf('\0') >= 0 || arg.indexOf('\t') >= 0 && !arg.equals(GitLog.FORMAT), arg);
        }
        assertTrue(args.contains("--grep=a"), "a control character separates terms: " + args);
        assertTrue(args.contains("--grep=b"));
        assertTrue(args.contains("--grep=c"));
    }

    @Test
    void theRequestDecidesBranchesPagingAndFollow() {
        List<String> plain = GitLog.logArgs(new GitLog.Request(false, null, GitLogQuery.NONE, 0, 200));
        assertEquals(
                List.of(
                        "log",
                        "--no-color",
                        "--decorate=full",
                        "--date=short",
                        "--date-order",
                        GitLog.FORMAT,
                        "-n",
                        "201"),
                plain,
                "one row more than the page proves there is more");

        List<String> next = GitLog.logArgs(new GitLog.Request(true, null, GitLogQuery.NONE, 199, 201));
        assertTrue(next.contains("--skip=199"));
        assertTrue(next.contains("--all"));
        assertEquals("202", next.get(next.indexOf("-n") + 1));

        Path file = Path.of("/repo/docs/[draft] notes.md").toAbsolutePath();
        List<String> history = GitLog.logArgs(new GitLog.Request(false, file, GitLogQuery.parse("path:other"), 0, 50));
        assertEquals(GitSafety.LITERAL_PATHSPECS, history.get(0), "a file name is never a glob");
        assertEquals("log", history.get(1));
        assertTrue(history.containsAll(List.of("--follow", "--name-status", "-z", GitLog.FOLLOW_FORMAT)));
        assertEquals(
                List.of("--", file.toString()),
                history.subList(history.size() - 2, history.size()),
                "--follow takes exactly one path: a path: term does not add a second");
    }

    @Test
    void onlyAPlainWalkOfTheHistoryHasAGraph() {
        assertTrue(new GitLog.Request(true, null, null, 0, 10).graphable());
        assertFalse(new GitLog.Request(false, Path.of("a"), null, 0, 10).graphable(), "a file history is a subset");
        assertFalse(new GitLog.Request(false, null, GitLogQuery.parse("fix"), 0, 10).graphable(), "so is a search");
    }
}
