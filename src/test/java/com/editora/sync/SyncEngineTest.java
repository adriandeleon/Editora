package com.editora.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.editora.git.QuietGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Two config directories ("machines") syncing through one bare repository, with the real git. */
class SyncEngineTest {

    private static final Set<SyncCategory> ALL = EnumSet.allOf(SyncCategory.class);

    @TempDir
    Path tmp;

    private Path remote;
    private Machine a;
    private Machine b;

    /** One installation: its config directory and an engine bound to the shared remote. */
    private final class Machine {
        final Path config;
        final String name;

        Machine(String name) throws IOException {
            this.name = name;
            this.config = Files.createDirectories(tmp.resolve(name));
        }

        SyncReport sync() {
            return sync(ALL, false);
        }

        SyncReport sync(Set<SyncCategory> categories, boolean allowLargeRemoval) {
            return engine(remote.toString(), new FileSyncTarget(config)).run(categories, allowLargeRemoval);
        }

        SyncEngine engine(String url, SyncTarget target) {
            QuietGit git = new QuietGit(config.resolve("sync").resolve("repo"), false);
            return new SyncEngine(git, url, "main", target, name);
        }

        void write(String path, String text) throws IOException {
            Path file = config.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }

        String read(String path) throws IOException {
            Path file = config.resolve(path);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(QuietGit.available(), "git is not installed");
        remote = tmp.resolve("remote.git");
        git(tmp, "init", "-q", "--bare", remote.toString());
        git(remote, "symbolic-ref", "HEAD", "refs/heads/main");
        a = new Machine("a");
        b = new Machine("b");
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        return out;
    }

    private static String snippet(String name, String body) {
        return "{\n  \"" + name + "\": { \"prefix\": \"" + name + "\", \"body\": \"" + body + "\" }\n}\n";
    }

    private static void assertOk(SyncReport report) {
        assertEquals(SyncReport.Status.OK, report.status(), report.detail());
    }

    @Test
    void theFirstMachineFillsAnEmptyRepositoryAndTheSecondReceivesIt() throws Exception {
        a.write("dictionary.txt", "editora\nzim\n");
        a.write("snippets/java.json", snippet("main", "psvm"));
        a.write("templates/class.json", "{\"name\":\"Class\",\"body\":\"class {}\"}");
        a.write(
                "abbreviations.json",
                "{\"schemaVersion\":1,\"abbreviations\":[{\"abbreviation\":\"btw\",\"expansion\":\"by the way\"}]}");
        a.write("settings.json", "{\"aiApiKey\":\"secret\"}");

        SyncReport first = a.sync();
        assertOk(first);
        assertEquals(5, first.sent().size(), "two words, a snippet, a template, an abbreviation");

        String files = git(remote, "ls-tree", "-r", "--name-only", "main");
        assertTrue(files.contains("snippets/java.json") && files.contains("dictionary.txt"), files);
        assertTrue(files.contains("editora-sync.json") && files.contains(".gitattributes"), files);
        assertFalse(files.contains("settings.json"), "nothing but the four categories is ever pushed");

        SyncReport second = b.sync();
        assertOk(second);
        assertEquals(5, second.received().size());
        assertEquals("editora\nzim\n", b.read("dictionary.txt"));
        assertEquals(snippet("main", "psvm"), b.read("snippets/java.json"));
        assertTrue(b.read("abbreviations.json").contains("by the way"));
        assertTrue(b.read("templates/class.json").contains("class {}"));

        SyncReport again = a.sync();
        assertOk(again);
        assertFalse(again.changedAnything(), "nothing changed anywhere: a sync is a no-op");
    }

    @Test
    void editsOnBothMachinesMergeAndADeletionTravels() throws Exception {
        a.write("dictionary.txt", "one\ntwo\n");
        a.write("templates/t.json", "{\"name\":\"T\",\"body\":\"x\"}");
        assertOk(a.sync());
        assertOk(b.sync());

        a.write("dictionary.txt", "one\ntwo\nfrom-a\n");
        b.write("dictionary.txt", "one\nfrom-b\n"); // b removed "two"
        Files.delete(b.config.resolve("templates/t.json"));
        assertOk(a.sync());
        SyncReport atB = b.sync();
        assertOk(atB);
        assertEquals("from-a\nfrom-b\none\n", b.read("dictionary.txt"));

        SyncReport atA = a.sync();
        assertOk(atA);
        assertEquals("from-a\nfrom-b\none\n", a.read("dictionary.txt"));
        assertEquals(null, a.read("templates/t.json"), "the template b deleted is gone on a too");
        try (Stream<Path> backups = Files.walk(new FileSyncTarget(a.config).backupsDir())) {
            assertTrue(
                    backups.anyMatch(p -> p.endsWith(Path.of("templates", "t.json"))),
                    "what a sync removes locally is copied to the backups folder first");
        }
    }

    @Test
    void aConflictKeepsEachMachinesOwnVersionUntilTheNextSyncThenConverges() throws Exception {
        a.write("snippets/java.json", snippet("main", "base"));
        assertOk(a.sync());
        assertOk(b.sync());

        a.write("snippets/java.json", snippet("main", "from a"));
        b.write("snippets/java.json", snippet("main", "from b"));
        assertOk(a.sync());
        SyncReport atB = b.sync();
        assertOk(atB);
        assertEquals(List.of(new SyncReport.Change(SyncCategory.SNIPPETS, "java: main")), atB.conflicts());
        assertTrue(b.read("snippets/java.json").contains("from b"), "this machine wins");

        assertOk(a.sync());
        assertTrue(a.read("snippets/java.json").contains("from b"), "and the other one follows");
        assertTrue(
                git(remote, "log", "-p", "main", "--", "snippets/java.json").contains("from a"),
                "the version that lost is still in the repository's history");
    }

    @Test
    void anUnreachableRepositoryChangesNothing() throws Exception {
        a.write("dictionary.txt", "word\n");
        SyncReport report = a.engine(tmp.resolve("missing.git").toString(), new FileSyncTarget(a.config))
                .run(ALL, false);
        assertEquals(SyncReport.Status.FETCH_FAILED, report.status());
        assertFalse(report.detail().isBlank());
        assertEquals("word\n", a.read("dictionary.txt"));
    }

    @Test
    void aFailedPushIsRepeatedByTheNextSync() throws Exception {
        a.write("dictionary.txt", "one\n");
        assertOk(a.sync());
        a.write("dictionary.txt", "one\ntwo\n");
        // The remote refuses every push.
        Path hook = remote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        assumeTrue(hook.toFile().setExecutable(true));
        assertEquals(SyncReport.Status.PUSH_FAILED, a.sync().status());

        Files.delete(hook);
        SyncReport retry = a.sync();
        assertOk(retry);
        assertEquals(1, retry.sent().size(), "the word is still unsent: the base did not move");
        assertOk(b.sync());
        assertEquals("one\ntwo\n", b.read("dictionary.txt"));
    }

    @Test
    void aPushThatLosesTheRaceMergesTheOtherMachinesCommitAndTriesAgain() throws Exception {
        a.write("dictionary.txt", "one\n");
        assertOk(a.sync());
        assertOk(b.sync());
        a.write("dictionary.txt", "one\nfrom-a\n");
        b.write("dictionary.txt", "one\nfrom-b\n");
        // b syncs in the middle of a's sync: after a has read its own files, before a pushes.
        FileSyncTarget racing = new FileSyncTarget(a.config) {
            private boolean raced;

            @Override
            public Map<String, String> read(Set<SyncCategory> categories) throws IOException {
                Map<String, String> files = super.read(categories);
                if (!raced) {
                    raced = true;
                    assertOk(b.sync());
                }
                return files;
            }
        };
        SyncReport report = a.engine(remote.toString(), racing).run(ALL, false);
        assertOk(report);
        assertEquals("from-a\nfrom-b\none\n", a.read("dictionary.txt"));
        assertEquals(1, report.received().size());
    }

    @Test
    void localFilesEditedDuringTheSyncAreNotOverwritten() throws Exception {
        a.write("dictionary.txt", "one\n");
        assertOk(a.sync());
        assertOk(b.sync());
        a.write("dictionary.txt", "one\ntwo\n");
        assertOk(a.sync());
        FileSyncTarget edited = new FileSyncTarget(b.config) {
            @Override
            public boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException {
                b.write("dictionary.txt", "one\njust-typed\n"); // the user adds a word right now
                return super.apply(expected, changes);
            }
        };
        assertEquals(
                SyncReport.Status.LOCAL_BUSY,
                b.engine(remote.toString(), edited).run(ALL, false).status());
        assertEquals("one\njust-typed\n", b.read("dictionary.txt"));
        assertOk(b.sync());
        assertEquals("just-typed\none\ntwo\n", b.read("dictionary.txt"));
    }

    @Test
    void aRepositoryFromANewerEditoraIsLeftAlone() throws Exception {
        a.write("dictionary.txt", "one\n");
        assertOk(a.sync());
        Path work = tmp.resolve("work");
        git(tmp, "clone", "-q", remote.toString(), work.toString());
        Files.writeString(work.resolve("editora-sync.json"), "{\"formatVersion\": 2}");
        git(work, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "newer");
        git(work, "push", "-q", "origin", "HEAD:main");

        a.write("dictionary.txt", "one\ntwo\n");
        assertEquals(SyncReport.Status.NEWER_FORMAT, a.sync().status());
        assertFalse(git(remote, "show", "main:dictionary.txt").contains("two"));
    }

    @Test
    void anUnreadableRemoteFileIsNeitherAppliedNorOverwritten() throws Exception {
        a.write("abbreviations.json", "{\"schemaVersion\":1,\"abbreviations\":[]}");
        a.write("dictionary.txt", "one\n");
        assertOk(a.sync());
        Path work = tmp.resolve("work");
        git(tmp, "clone", "-q", remote.toString(), work.toString());
        Files.writeString(work.resolve("abbreviations.json"), "<<<<<<< not json");
        git(work, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "damage");
        git(work, "push", "-q", "origin", "HEAD:main");

        a.write("dictionary.txt", "one\ntwo\n");
        SyncReport report = a.sync();
        assertOk(report);
        assertEquals(
                List.of(new SyncReport.Skipped("abbreviations.json", SyncMerge.Skip.REMOTE_UNREADABLE)),
                report.skipped());
        assertTrue(a.read("abbreviations.json").contains("schemaVersion"));
        assertTrue(git(remote, "show", "main:abbreviations.json").contains("<<<<<<<"));
        assertTrue(git(remote, "show", "main:dictionary.txt").contains("two"), "the other files still sync");
    }

    @Test
    void losingMostOfACategoryNeedsConfirmation() throws Exception {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            words.append("word").append(i).append('\n');
        }
        a.write("dictionary.txt", words.toString());
        assertOk(a.sync());
        assertOk(b.sync());

        b.write("dictionary.txt", "word0\n"); // truncated: a crash, a bad hand edit
        SyncReport guarded = b.sync();
        assertEquals(SyncReport.Status.NEEDS_CONFIRMATION, guarded.status());
        assertEquals("DICTIONARY", guarded.detail());
        assertTrue(git(remote, "show", "main:dictionary.txt").contains("word11"));

        assertOk(b.sync(ALL, true));
        assertEquals("word0\n", git(remote, "show", "main:dictionary.txt"));
    }

    @Test
    void aCategoryThatIsSwitchedOffIsNotTouchedOnEitherSide() throws Exception {
        a.write("dictionary.txt", "one\n");
        a.write("snippets/java.json", snippet("main", "psvm"));
        assertOk(a.sync());
        b.write("dictionary.txt", "private\n");
        assertOk(b.sync(EnumSet.of(SyncCategory.SNIPPETS), false));
        assertEquals("private\n", b.read("dictionary.txt"));
        assertEquals("one\n", git(remote, "show", "main:dictionary.txt"));
        assertEquals(snippet("main", "psvm"), b.read("snippets/java.json"));
    }

    @Test
    void windowsLineEndsAndAutocrlfProduceNoChange() throws Exception {
        a.write("dictionary.txt", "one\ntwo\n");
        a.write("snippets/java.json", snippet("main", "psvm"));
        assertOk(a.sync());
        git(Files.createDirectories(b.config.resolve("sync/repo")), "init", "-q");
        git(b.config.resolve("sync/repo"), "config", "core.autocrlf", "true");
        b.write("dictionary.txt", "one\r\ntwo\r\n");
        b.write("snippets/java.json", snippet("main", "psvm").replace("\n", "\r\n"));
        SyncReport report = b.sync();
        assertOk(report);
        assertFalse(report.changedAnything());
        assertEquals("one\r\ntwo\r\n", b.read("dictionary.txt"), "an equal file is not rewritten");
        assertEquals("1\n", git(remote, "rev-list", "--count", "main"), "and nothing was committed");
    }

    @Test
    void pointingAtAnotherRepositoryStartsOverWithoutRemovingAnything() throws Exception {
        a.write("dictionary.txt", "one\ntwo\n");
        assertOk(a.sync());
        Path other = tmp.resolve("other.git");
        git(tmp, "init", "-q", "--bare", other.toString());
        Machine c = new Machine("c");
        c.write("dictionary.txt", "three\n");
        assertOk(c.engine(other.toString(), new FileSyncTarget(c.config)).run(ALL, false));

        // a moves to the other repository: its old base must not read as "the remote deleted one and two".
        assertOk(a.engine(other.toString(), new FileSyncTarget(a.config)).run(ALL, false));
        assertEquals("one\nthree\ntwo\n", a.read("dictionary.txt"));
    }

    @Test
    void optionLikeUrlsAndBranchesAreRefused() {
        assertFalse(SyncEngine.isUsableUrl("--upload-pack=touch /tmp/x"));
        assertFalse(SyncEngine.isUsableUrl(" "));
        assertTrue(SyncEngine.isUsableUrl("git@github.com:me/editora-sync.git"));
        assertTrue(SyncEngine.isUsableUrl("https://example.com/me/sync.git"));
        assertFalse(SyncEngine.isUsableBranch("-main"));
        assertFalse(SyncEngine.isUsableBranch("a..b"));
        assertFalse(SyncEngine.isUsableBranch("a b"));
        assertTrue(SyncEngine.isUsableBranch("sync/laptop"));
    }
}
