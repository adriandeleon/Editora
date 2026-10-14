package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.process.ExecTargets;
import com.editora.search.RemoteFiles;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching and listing a folder on a remote host is done by the host: its own {@code rg} (or {@code git}),
 * one command, instead of every file read over SFTP.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteSearchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private Path serverApp;
    private Path remoteApp;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        serverApp = Files.createDirectories(sftp.serverPath("app"));
        Files.createDirectories(serverApp.resolve("src"));
        Files.createDirectories(serverApp.resolve("build"));
        Files.writeString(serverApp.resolve("src/Main.java"), "class Main { String needle = \"x\"; }\n");
        Files.writeString(serverApp.resolve("src/Other.java"), "class Other {}\n");
        Files.writeString(serverApp.resolve("build/Gen.java"), "class Gen { String needle; }\n");
        Files.writeString(serverApp.resolve(".gitignore"), "build/\n");
        // A real repository, not just a .git folder: rg honours .gitignore only inside one, and a host without
        // rg lists the folder with `git ls-files`, which refuses an empty .git. The macOS runner has no rg, so
        // there Go to File found nothing at all.
        Process init = new ProcessBuilder("git", "init", "-q")
                .directory(serverApp.toFile())
                .redirectErrorStream(true)
                .start();
        Assumptions.assumeTrue(init.waitFor() == 0, "git is not installed on this machine, which plays the host");
        remoteApp = sftp.remotePath("app");
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
        ExecTargets.setRemoteTools(null);
    }

    private static boolean hostHasRipgrep() {
        try {
            return new ProcessBuilder("rg", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void findInFilesInARemoteFolderIsTheHostsRipgrep() throws Exception {
        Assumptions.assumeTrue(hostHasRipgrep(), "rg is not installed on this machine, which plays the host");
        SearchService service = new SearchService();
        service.setBackend(false, List.of("rg"), true, true); // no rg here, but the user wants ripgrep
        AtomicReference<SearchService.Outcome> outcome = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        service.search(new SearchQuery("needle", true, false, false), remoteApp, Map.of(), result -> {
            outcome.set(result);
            done.countDown();
        });

        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertEquals(
                List.of(remoteApp.resolve("src/Main.java")),
                outcome.get().files().stream().map(f -> f.file()).toList(),
                "one hit, on the remote filesystem, with the ignored build/ left out");
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("'rg'") && line.contains("'needle'")),
                "the search did not run on the host: " + sftp.commands());
    }

    @Test
    void anOpenRemoteBufferReplacesItsFileOnDisk() throws Exception {
        Assumptions.assumeTrue(hostHasRipgrep(), "rg is not installed on this machine, which plays the host");
        SearchService service = new SearchService();
        service.setBackend(false, List.of("rg"), true, true);
        Path main = remoteApp.resolve("src/Main.java");
        AtomicReference<SearchService.Outcome> outcome = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        service.search(
                new SearchQuery("needle", true, false, false),
                remoteApp,
                Map.of(main, "class Main { String needle; String needle2; }\n"),
                result -> {
                    outcome.set(result);
                    done.countDown();
                });

        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertEquals(1, outcome.get().fileCount(), "the open buffer stands in for its file: not listed twice");
        assertEquals(2, outcome.get().totalMatches(), "the buffer's text, not the file's");
    }

    @Test
    void goToFileListsARemoteProjectOnItsHost() throws Exception {
        IndexCoordinator.Walked walked = IndexCoordinator.remoteWalk(remoteApp, true, 100);

        List<Path> files = walked.files().stream().sorted().toList();
        assertTrue(files.contains(remoteApp.resolve("src/Main.java")), files.toString());
        assertTrue(files.contains(remoteApp.resolve("src/Other.java")), files.toString());
        assertFalse(files.contains(remoteApp.resolve("build/Gen.java")), "ignored by .gitignore");
        assertTrue(walked.scanned().isEmpty(), "no symbols: that would read every file over the network");
        assertFalse(walked.truncated());
    }

    @Test
    void aListingLongerThanTheCapSaysItWasCut() throws Exception {
        IndexCoordinator.Walked walked = IndexCoordinator.remoteWalk(remoteApp, true, 1);

        assertEquals(1, walked.files().size());
        assertTrue(walked.truncated());
    }

    @Test
    void aHostWithoutRipgrepListsTheProjectWithGit() throws Exception {
        ExecTargets.setRemoteTools((host, tool) -> "rg".equals(tool) ? "editora-test-no-such-rg" : null);

        IndexCoordinator.Walked walked = IndexCoordinator.remoteWalk(remoteApp, true, 100);

        List<Path> files = walked.files().stream().sorted().toList();
        assertTrue(files.contains(remoteApp.resolve("src/Main.java")), files.toString());
        assertFalse(files.contains(remoteApp.resolve("build/Gen.java")), "ignored by .gitignore");
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("'git'") && line.contains("'ls-files'")),
                "listed by the host's git: " + sftp.commands());
        assertEquals(1, IndexCoordinator.remoteWalk(remoteApp, true, 1).files().size(), "and cut at the cap");
    }

    /** A folder that is not a Git repository: what `git ls-files` refuses. */
    private Path plainFolder() throws Exception {
        Path server = Files.createDirectories(sftp.serverPath("plain"));
        Files.createDirectories(server.resolve("src"));
        Files.createDirectories(server.resolve("build"));
        Files.createDirectories(server.resolve("lib"));
        Files.createDirectories(server.resolve(".cache"));
        Files.writeString(server.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(server.resolve("build/Gen.java"), "class Gen {}\n");
        Files.writeString(server.resolve("lib/kept.java"), "\n");
        Files.writeString(server.resolve("lib/scratch.tmp"), "\n");
        Files.writeString(server.resolve(".cache/hidden.java"), "\n");
        Files.writeString(server.resolve(".env"), "SECRET=1\n");
        Files.writeString(server.resolve(".gitignore"), "build/\n");
        Files.writeString(server.resolve("lib/.gitignore"), "*.tmp\n");
        return sftp.remotePath("plain");
    }

    private static List<String> relative(Path root, IndexCoordinator.Walked walked) {
        return walked.files().stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    @Test
    void aFolderOutsideARepositoryOnAHostWithoutRipgrepIsListedByFind() throws Exception {
        ExecTargets.setRemoteTools((host, tool) -> "rg".equals(tool) ? "editora-test-no-such-rg" : null);
        Path plain = plainFolder();

        IndexCoordinator.Walked walked = IndexCoordinator.remoteWalk(plain, true, 100);

        assertEquals(
                List.of("lib/kept.java", "src/Main.java"),
                relative(plain, walked),
                "as a local project would be: no hidden files or folders, the root's and lib's .gitignore honoured");
        assertFalse(walked.truncated());
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("'find'")),
                "listed by the host's find: " + sftp.commands());

        IndexCoordinator.Walked all = IndexCoordinator.remoteWalk(plain, false, 100);
        assertEquals(
                List.of("build/Gen.java", "lib/kept.java", "lib/scratch.tmp", "src/Main.java"),
                relative(plain, all),
                "not honouring .gitignore: ignored files listed, hidden ones still not");

        IndexCoordinator.Walked one = IndexCoordinator.remoteWalk(plain, true, 1);
        assertEquals(1, one.files().size());
        assertTrue(one.truncated(), "cut at the cap, and said so");
    }

    @Test
    void aHostThatRunsNoCommandsIsWalkedOverSftp() throws Exception {
        Path plain = plainFolder();
        ExecTargets.setRemoteEnabled(() -> false);
        int before = sftp.commands().size();

        IndexCoordinator.Walked walked = IndexCoordinator.remoteWalk(plain, true, 100);

        assertEquals(List.of("lib/kept.java", "src/Main.java"), relative(plain, walked), "the same files, over SFTP");
        assertTrue(walked.scanned().isEmpty(), "names only: no file is read for symbols");
        assertEquals(before, sftp.commands().size(), "and no command was run: " + sftp.commands());
    }

    @Test
    void aHostThatCannotRunCommandsHasNoRipgrep() {
        ExecTargets.setRemoteEnabled(() -> false);

        assertFalse(RemoteFiles.hasRipgrep(remoteApp));
        assertNotNull(remoteApp);
    }
}
