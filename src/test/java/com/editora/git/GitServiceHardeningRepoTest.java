package com.editora.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git review 2 against real repositories: a background diff runs no textconv program and reads the same
 * whatever the repository's diff configuration (C1, C4), a ref that is also a file name is still counted (C6),
 * and an index path that looks like a stage number is still the stage-0 entry (C9).
 */
class GitServiceHardeningRepoTest {

    @TempDir
    Path temp;

    private final GitService service = new GitService();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private Path repo(String name) throws Exception {
        Path repo = Files.createDirectories(temp.resolve(name));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        return repo;
    }

    /** C1: the Changes card's {@code -c core.quotePath=false diff} ran the repository's textconv program. */
    @Test
    @DisabledOnOs(OS.WINDOWS) // the textconv command is a POSIX shell line
    void theChangesCardRunsNoTextconvProgram() throws Exception {
        Path repo = repo("textconv");
        Path marker = temp.resolve("textconv-ran");
        Files.writeString(repo.resolve(".gitattributes"), "*.txt diff=evil\n");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        GitServiceRecentChangesTest.git(repo, null, "config", "diff.evil.textconv", "touch '" + marker + "' && cat");
        Files.writeString(repo.resolve("a.txt"), "two\n");

        GitChanges.Result found = service.workingChangesNow(repo);
        assertTrue(found.ok(), found.error());
        assertEquals(
                List.of("a.txt"),
                found.files().stream().map(GitChanges.FileDiff::path).toList());
        assertFalse(Files.exists(marker), "the repository's textconv program ran");
    }

    /** C4: colour, no prefix or mnemonic prefixes in the repository's config do not change what is parsed. */
    @Test
    void aParsedDiffIgnoresTheRepositorysDiffConfiguration() throws Exception {
        Path repo = repo("config");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        GitServiceRecentChangesTest.git(repo, null, "config", "color.diff", "always");
        GitServiceRecentChangesTest.git(repo, null, "config", "color.ui", "always");
        GitServiceRecentChangesTest.git(repo, null, "config", "diff.noprefix", "true");
        Files.writeString(repo.resolve("a.txt"), "two\n");
        GitServiceRecentChangesTest.git(repo, null, "add", "a.txt");

        ProcessRunner.Result staged = ProcessRunner.run(
                repo, GitService.QUICK, GitService.backgroundArgv(repo, "diff", "--cached"), GitSafety.BACKGROUND_ENV);
        assertTrue(staged.ok(), staged.err());
        assertFalse(staged.out().contains("\u001b["), "no colour codes");
        assertTrue(staged.out().contains("--- a/a.txt\n+++ b/a.txt\n"), staged.out());

        GitServiceRecentChangesTest.git(repo, null, "config", "diff.noprefix", "false");
        GitServiceRecentChangesTest.git(repo, null, "config", "diff.mnemonicPrefix", "true");
        ProcessRunner.Result mnemonic = ProcessRunner.run(
                repo, GitService.QUICK, GitService.backgroundArgv(repo, "diff", "--cached"), GitSafety.BACKGROUND_ENV);
        assertTrue(mnemonic.out().contains("--- a/a.txt\n+++ b/a.txt\n"), mnemonic.out());

        Files.writeString(repo.resolve("a.txt"), "three\n");
        GitChanges.Result found = service.workingChangesNow(repo);
        assertTrue(found.ok(), found.error());
        assertEquals('M', found.file("a.txt").status());
        assertEquals(1, found.file("a.txt").added());
    }

    /** C6: {@code rev-list} without {@code --} failed on a ref that is also a file, and that read as "no risk". */
    @Test
    void aTargetThatIsAlsoAFileNameIsStillCounted() throws Exception {
        Path repo = repo("ambiguous");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        GitServiceRecentChangesTest.commit(repo, 2, "Ada", "start");
        GitServiceRecentChangesTest.git(repo, null, "branch", "topic");
        Files.writeString(repo.resolve("topic"), "a file named like the branch\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "on main");

        GitService.LeftBehind left = GitService.leftBehindNow(repo, "topic", true);
        assertTrue(left.known(), "git answered");
        assertEquals(1, left.leaving());
    }

    /** C9: {@code ":" + path} reads a path that starts with {@code 1:} as stage 1 of the rest. */
    @Test
    @DisabledOnOs(OS.WINDOWS) // ':' cannot be in a Windows file name
    void anIndexPathThatLooksLikeAStageIsTheStageZeroEntry() throws Exception {
        Path repo = repo("stage");
        Files.writeString(repo.resolve("1:x.txt"), "staged text\n");
        Files.writeString(repo.resolve("x.txt"), "other\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");

        GitService.BlobResult blob = GitService.indexBlobNow(repo, "1:x.txt");
        assertTrue(blob.found());
        assertArrayEquals("staged text\n".getBytes(StandardCharsets.UTF_8), blob.bytes());
    }

    /** C10: the global config copy a blame needs is written once per content, readable by its owner only. */
    @Test
    void theGlobalConfigCopyIsReusedAndPrivate() throws Exception {
        String text = "[user]\n\tname = Test " + System.nanoTime() + "\n";
        Path first = GitService.localGlobalConfigCopy(text);
        assertSame(first, GitService.localGlobalConfigCopy(text));
        assertEquals(text, Files.readString(first));
        assertNotEquals(first, GitService.localGlobalConfigCopy(text + "# more\n"));
        if (first.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first)));
        }
    }

    /** C5: where {@code setsid} is available a user command starts in a session of its own. */
    @Test
    void aUserCommandStartsWithoutATerminalWhereItCan() {
        List<String> argv = List.of("git", "push");
        assertSame(argv, GitService.inNewSession(null, argv));
        assertEquals(List.of("/usr/bin/setsid", "git", "push"), GitService.inNewSession("/usr/bin/setsid", argv));
    }
}
