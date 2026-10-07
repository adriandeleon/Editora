package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blame's attribution options, the ignore-revs file and "annotate previous revision" — against real git. */
@Tag("fx")
class GitBlameOptionsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String head(GitTestRepo repo) throws Exception {
        return repo.git("rev-parse", "HEAD").text().strip();
    }

    private static void awaitBlame(EditorBuffer buffer, int line, String hash) throws Exception {
        GitFeatureFx.await(
                "line " + line + " blamed on " + GitFormat.shortHash(hash),
                () -> hash.equals(buffer.blameHashAt(line)));
    }

    /** {@code -w}: a commit that only re-indented a line does not take the credit for it. */
    @Test
    void ignoreWhitespaceLooksThroughAReindent(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("code.txt", "if (x) {\nreturn value;\n}\n");
        repo.commitAll("write");
        String wrote = head(repo);
        repo.write("code.txt", "if (x) {\n        return value;\n}\n");
        repo.commitAll("reindent");
        String reindented = head(repo);

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            awaitBlame(buffer, 1, reindented);

            FxTestSupport.runOnFx(() -> w.git.blame().toggleIgnoreWhitespace());
            awaitBlame(buffer, 1, wrote);
            assertTrue(
                    FxTestSupport.callOnFx(() -> w.git.service().blameOptions().ignoreWhitespace()));

            // Off again: the answer for the other option set is not served from the cache of this one.
            FxTestSupport.runOnFx(() -> w.git.blame().toggleIgnoreWhitespace());
            awaitBlame(buffer, 1, reindented);
        }
    }

    /** {@code -M -C}: lines moved to another file in one commit stay blamed on the commit that wrote them. */
    @Test
    void detectMovesFollowsLinesMovedToAnotherFile(@TempDir Path dir) throws Exception {
        String block = "public int computeTheAnswerToEverything() {\n"
                + "    int theAnswerToLifeTheUniverse = 6 * 7;\n"
                + "    return theAnswerToLifeTheUniverse + 0;\n"
                + "}\n";
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("Old.java", "// the old home of the function\n" + block);
        repo.commitAll("write");
        String wrote = head(repo);
        repo.write("Old.java", "// the old home of the function\n");
        Path moved = repo.write("New.java", "// its new home\n" + block);
        repo.commitAll("move");
        String movedIn = head(repo);

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(moved);
            awaitBlame(buffer, 2, movedIn);

            FxTestSupport.runOnFx(() -> w.git.blame().toggleDetectMoves());
            awaitBlame(buffer, 2, wrote);
            assertEquals(movedIn, FxTestSupport.callOnFx(() -> buffer.blameHashAt(0)), "the new line is the move's");
        }
    }

    /**
     * A {@code .git-blame-ignore-revs} at the repository root is used without any configuration, and a
     * broken one costs only the "ignore": the annotations still appear, and the user is told why.
     */
    @Test
    void theIgnoreRevsFileIsUsedAndABrokenOneDoesNotBreakBlame(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("code.txt", "alpha\nbeta\ngamma\n");
        repo.commitAll("write");
        String wrote = head(repo);
        repo.write("code.txt", "alpha\nBETA\ngamma\n");
        repo.commitAll("the big reformat");
        String reformat = head(repo);
        Path ignore = repo.write(".git-blame-ignore-revs", "# reformatting\n" + reformat + "\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            awaitBlame(buffer, 1, wrote); // the reformat is looked through

            // Not object names: git blame dies on such a file ("invalid object name").
            CountDownLatch told = w.watchStatus(s -> s.startsWith(tr("status.git.blameIgnoreRevsFailed", "")));
            Files.writeString(ignore, "this is not a list of commits\n");
            FxTestSupport.runOnFx(w.git::refresh);
            awaitBlame(buffer, 1, reformat); // blamed without the file rather than not at all
            async.await(told, "the reason the file was not used");

            Files.delete(ignore);
            Files.writeString(file, "alpha\nBETA\ngamma\ndelta\n"); // a new blame: the file changed
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await("the uncommitted line", () -> "".equals(buffer.blameHashAt(3)));
            assertEquals(reformat, FxTestSupport.callOnFx(() -> buffer.blameHashAt(1)));
        }
    }

    /**
     * A {@code blame.ignoreRevsFile} in the repository's own config that names a missing file makes git
     * itself refuse to blame, and nothing on the command line can take the entry back out. The column stays
     * empty, as in a terminal — but with git's reason in the status bar instead of silence.
     */
    @Test
    void aMissingFileConfiguredInTheRepositoryIsReported(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("code.txt", "alpha\nbeta\n");
        repo.commitAll("write");
        repo.git("config", "blame.ignoreRevsFile", "no-such-file.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            CountDownLatch told = w.watchStatus(s -> s.contains("no-such-file.txt"));
            EditorBuffer buffer = w.open(file);
            async.await(told, "git's reason");
            assertNull(FxTestSupport.callOnFx(() -> buffer.blameHashAt(0)));

            // The file appears (the usual fix): blame works again once the configuration is re-read.
            repo.write("no-such-file.txt", "");
            FxTestSupport.runOnFx(() -> {
                w.git.invalidateCaches();
                w.git.refresh();
            });
            awaitBlame(buffer, 0, head(repo));
        }
    }

    /**
     * Annotate previous revision walks a line back: each step opens the file as of the commit before the one
     * the line is blamed on — under the name it had then — read-only and annotated in turn.
     */
    @Test
    void annotatePreviousRevisionWalksALineBackThroughARename(@TempDir Path dir) throws Exception {
        String first = "one\ntwo\nthree\nfour\nfive\n";
        String second = "one\ntwo, renamed\nthree\nfour\nfive\n";
        String third = "one\ntwo, edited again\nthree\nfour\nfive\n";
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("old-name.txt", first);
        repo.commitAll("c1 create");
        String c1 = head(repo);
        repo.git("mv", "old-name.txt", "story.txt");
        Path file = repo.write("story.txt", second);
        repo.commitAll("c2 rename and edit");
        String c2 = head(repo);
        repo.write("story.txt", third);
        repo.commitAll("c3 edit");
        String c3 = head(repo);

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer working = w.open(file);
            awaitBlame(working, 1, c3);
            FxTestSupport.runOnFx(() -> working.jumpToLine(1));

            // c3 → the file as of c2.
            FxTestSupport.runOnFx(() -> w.git.blame().annotatePreviousRevision());
            GitFeatureFx.await("the c2 tab", () -> w.active() != null && w.active() != working);
            EditorBuffer atC2 = FxTestSupport.callOnFx(w::active);
            assertEquals(second, FxTestSupport.callOnFx(atC2::getContent));
            assertEquals(
                    tr("blame.revisionTitle", "story.txt", GitFormat.shortHash(c2)),
                    FxTestSupport.callOnFx(atC2::getTitle));
            assertTrue(FxTestSupport.callOnFx(atC2::isViewMode), "a revision is for reading");
            assertNull(FxTestSupport.callOnFx(atC2::getPath));
            awaitBlame(atC2, 1, c2); // annotated in turn, as of that commit
            assertEquals(c1, FxTestSupport.callOnFx(() -> atC2.blameHashAt(0)));
            assertEquals(1, FxTestSupport.callOnFx(() -> atC2.getArea().getCurrentParagraph()), "same line");

            // c2 → the file as of c1, where it had another name.
            FxTestSupport.runOnFx(() -> w.git.blame().annotatePreviousRevision());
            GitFeatureFx.await("the c1 tab", () -> w.active() != null && w.active() != atC2 && w.active() != working);
            EditorBuffer atC1 = FxTestSupport.callOnFx(w::active);
            assertEquals(first, FxTestSupport.callOnFx(atC1::getContent));
            assertEquals(
                    tr("blame.revisionTitle", "old-name.txt", GitFormat.shortHash(c1)),
                    FxTestSupport.callOnFx(atC1::getTitle));
            awaitBlame(atC1, 1, c1);

            // c1 added the file: there is nothing before it, and no further tab.
            CountDownLatch end = w.watchStatus(tr("status.git.noPreviousRevision", GitFormat.shortHash(c1))::equals);
            FxTestSupport.runOnFx(() -> w.git.blame().annotatePreviousRevision());
            async.await(end, "the end of the line's history");
            assertEquals(atC1, FxTestSupport.callOnFx(w::active));

            // Back on the earlier revision tab its annotations are still its own, not the working file's.
            FxTestSupport.runOnFx(() -> w.area.select(tabOf(w, atC2)));
            awaitBlame(atC2, 1, c2);
            assertNotEquals(c3, FxTestSupport.callOnFx(() -> atC2.blameHashAt(1)));
        }
    }

    /**
     * The stand-in for the user's global config (used when it names a missing ignore-revs file) holds every
     * other entry exactly as git reads it — quotes, backslashes, subsections, bare booleans — and blame
     * works under it where it died under the original.
     */
    @Test
    void theGlobalConfigCopyKeepsEverythingButTheIgnoreRevsFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("code.txt", "alpha\n");
        repo.commitAll("write");
        Path global = dir.resolve("user-global.config");
        Files.writeString(
                global,
                "[blame]\n\tignoreRevsFile = .git-blame-ignore-revs\n"
                        + "[core]\n\tautocrlf = false\n\tbare-flag\n"
                        + "[alias]\n\tquoted = \"log --format=\\\"%h %s\\\" # not a comment\"\n"
                        + "[url \"ssh://git@example.invalid/\"]\n\tinsteadOf = \"https://example.invalid/\"\n"
                        + "[section \"sub.with \\\"quote\\\" and \\\\\"]\n\tkey = \"  spaced\\tand tabbed  \"\n");
        String listed =
                repo.git("config", "--file", global.toString(), "-z", "--list").text();

        Path copy = dir.resolve("copy.config");
        Files.writeString(
                copy, com.editora.git.BlameIgnoreRevs.globalConfigWithoutIgnoreRevs(listed, repo.root.toString()));

        String expected = listed.replace("blame.ignorerevsfile\n.git-blame-ignore-revs\u0000", "") + "safe.directory\n"
                + repo.root + "\u0000";
        assertEquals(
                expected,
                repo.git("config", "--file", copy.toString(), "-z", "--list").text());

        // And the point of it: blame dies under the original, works under the copy.
        assertNotEquals(0, blameUnder(repo, global).exit());
        assertEquals(0, blameUnder(repo, copy).exit());
    }

    private static GitTestRepo.Output blameUnder(GitTestRepo repo, Path globalConfig) throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder("git", "blame", "--porcelain", "--", "code.txt").directory(repo.root.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", globalConfig.toString());
        Process process = builder.start();
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        String err = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new GitTestRepo.Output(process.waitFor(), out, err);
    }

    private static javafx.scene.control.Tab tabOf(GitFeatureFx w, EditorBuffer buffer) {
        for (javafx.scene.control.Tab tab : w.area.tabs()) {
            if (tab.getUserData() == buffer) {
                return tab;
            }
        }
        throw new AssertionError("no tab for the buffer");
    }
}
