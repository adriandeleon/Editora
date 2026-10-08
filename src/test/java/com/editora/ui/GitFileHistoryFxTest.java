package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitLog;
import com.editora.git.GitService.CommitFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One file's Git history in the Git Log, against real repositories and through the window's real Git
 * refresh (nothing here hands the engine a repository state): paging past commits that do not touch the
 * file, searching across a rename, what a row opens, the repository a history is listed from, and the
 * states with nothing to list.
 *
 * <p>Before this a file history stopped at its first page whenever other files had commits in between
 * ({@code --skip} counts walked commits under {@code --follow}), a search lost every commit older than the
 * last rename, opening a diff in a window without a project closed the log, and a tab of another
 * repository was listed in the active one.
 */
@Tag("fx")
class GitFileHistoryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A window with no project, and everything a test drives in it. */
    private record Window(
            FxWindowFixture fx,
            MainController controller,
            GitCoordinator git,
            GitWindowCoordinator windows,
            GitLogPanel panel,
            AsyncTestScope async) {

        static Window open(AsyncTestScope async) throws Exception {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            FxTestSupport.runOnFx(() -> panel.loadAheadRows = 0);
            return new Window(fx, fx.controller, git, windows, panel, async);
        }

        /** Waits until every queued git read and mutation, and the FX work they posted, has run. */
        void settle() throws Exception {
            for (int round = 0; round < 4; round++) {
                for (String lane : List.of("exec", "networkExec", "historyExec")) {
                    ExecutorService worker = FxTestSupport.field(git.service(), lane);
                    async.awaitWorker(worker);
                    async.awaitFx();
                }
            }
        }

        void run(String command) {
            com.editora.command.CommandRegistry registry = FxTestSupport.field(controller, "registry");
            assertTrue(registry.run(command), command);
        }

        void onFx(Runnable action) throws Exception {
            FxTestSupport.runOnFx(action);
            settle();
        }

        /** Opens {@code file} in a tab and waits for the Git refresh the tab switch starts. */
        void openFile(Path file) throws Exception {
            GitLogReloadFxTest.open(controller, file);
            settle();
        }

        /** Opens {@code file} and its Git history, as the {@code git.fileHistory} command does. */
        void fileHistory(Path file) throws Exception {
            openFile(file);
            onFx(windows::showFileHistory);
        }

        List<String> subjects() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                return rows.stream().map(GitLog.Entry::subject).toList();
            });
        }

        void select(String subject) throws Exception {
            onFx(() -> {
                List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                GitLog.Entry row = rows.stream()
                        .filter(c -> c.subject().equals(subject))
                        .findFirst()
                        .orElseThrow();
                assertTrue(panel.selectCommit(row.hash()));
            });
        }

        String header() throws Exception {
            return FxTestSupport.callOnFx(() -> ((Label) FxTestSupport.field(panel, "filterLabel")).getText());
        }

        String placeholder() throws Exception {
            return FxTestSupport.callOnFx(() -> ((Label) FxTestSupport.field(panel, "placeholder")).getText());
        }

        String echo() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(controller, "statusBar");
                return ((Label) FxTestSupport.field(statusBar, "echo")).getText();
            });
        }

        boolean logOpen() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                ToolWindowManager toolWindows = FxTestSupport.field(controller, "toolWindows");
                ToolWindow log = FxTestSupport.field(controller, "gitLogToolWindow");
                return toolWindows.isOpen(log);
            });
        }

        /** Presses {@code code} on the commit list or the file list, through the scene's own dispatch. */
        void press(String list, KeyCode code) throws Exception {
            onFx(() -> {
                Node node = FxTestSupport.field(panel, list);
                node.requestFocus();
                javafx.event.Event.fireEvent(
                        node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            });
        }

        /** The content of the selected tab once it is a {@code type}, or null. */
        Object awaitTab(Class<?> type) throws Exception {
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            for (int i = 0; i < 120; i++) {
                Object data = FxTestSupport.callOnFx(() -> {
                    Tab selected = area.selectedTab();
                    return selected == null ? null : selected.getUserData();
                });
                if (type.isInstance(data)) {
                    settle();
                    return data;
                }
                Thread.sleep(50);
            }
            return null;
        }
    }

    /** {@code work.txt} and {@code noise.txt} committed in turn: every other commit does not touch the file. */
    private static GitTestRepo interleavedRepo(Path dir, int commits) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        for (int i = 1; i <= commits; i++) {
            repo.write("work.txt", "line " + i + "\n");
            repo.commitAll("work " + i);
            repo.write("noise.txt", "noise " + i + "\n");
            repo.commitAll("noise " + i);
        }
        return repo;
    }

    /** A commit of everything in the work tree, made by {@code author} at {@code date} (ISO, UTC). */
    private static void commitAt(GitTestRepo repo, String message, String author, String date) throws Exception {
        repo.git("add", "-A");
        ProcessBuilder builder = new ProcessBuilder(
                        "git", "-c", "user.name=" + author, "commit", "-q", "--no-verify", "-m", message)
                .directory(repo.root.toFile());
        builder.environment().put("GIT_AUTHOR_DATE", date + "T12:00:00Z");
        builder.environment().put("GIT_COMMITTER_DATE", date + "T12:00:00Z");
        Process process = builder.start();
        process.getOutputStream().close();
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), err);
    }

    /** {@code notes.txt} with two commits, renamed to {@code journal.txt}, then edited once more. */
    private static GitTestRepo renamedRepo(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\ntwo\nthree\n");
        repo.write("other.txt", "unrelated\n");
        commitAt(repo, "add the notes", "Ada Byron", "2020-01-10");
        repo.write("notes.txt", "one\ntwo\nthree\nfour\n");
        commitAt(repo, "notes: add four", "Bob Stone", "2020-02-10");
        repo.git("mv", "notes.txt", "journal.txt");
        commitAt(repo, "rename to journal", "Ada Byron", "2021-03-10");
        repo.write("other.txt", "unrelated, changed\n");
        commitAt(repo, "touch the other file", "Bob Stone", "2021-04-10");
        repo.write("journal.txt", "one\ntwo\nthree\nfour\nfive\n");
        commitAt(repo, "journal: add five", "Ada Byron", "2022-05-10");
        return repo;
    }

    private static List<String> work(int newest, int oldest) {
        List<String> subjects = new ArrayList<>();
        for (int i = newest; i >= oldest; i--) {
            subjects.add("work " + i);
        }
        return subjects;
    }

    @Test
    void aFileHistoryLoadsPastTheCommitsThatDoNotTouchTheFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = interleavedRepo(dir, 10);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            FxTestSupport.runOnFx(() -> window.windows.logPageSize = 3);
            window.fileHistory(repo.root.resolve("work.txt"));

            assertEquals(work(10, 8), window.subjects(), "the first page");
            assertTrue(FxTestSupport.callOnFx(window.panel::hasMore));

            window.onFx(window.windows::loadMoreGitLog);
            assertEquals(work(10, 5), window.subjects(), "the second page continues where the first ended");

            window.select("work 6");
            window.onFx(() -> ((Button) FxTestSupport.field(window.panel, "loadMoreButton")).fire());
            assertEquals(work(10, 2), window.subjects(), "and the footer button loads the third");
            String selected = FxTestSupport.callOnFx(window.panel::selectedHash);
            assertEquals(repo.git("rev-parse", "HEAD~9").text().strip(), selected, "the selection stays on \"work 6\"");

            window.onFx(window.windows::loadMoreGitLog);
            assertEquals(work(10, 1), window.subjects(), "down to the file's first commit");
            assertFalse(FxTestSupport.callOnFx(window.panel::hasMore), "and then nothing is left to load");
        }
    }

    @Test
    void aFileHistoryReloadsWhenTheHistoryMovedUnderTheLoadedRows(@TempDir Path dir) throws Exception {
        GitTestRepo repo = interleavedRepo(dir, 6);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            FxTestSupport.runOnFx(() -> window.windows.logPageSize = 3);
            window.fileHistory(repo.root.resolve("work.txt"));
            assertEquals(work(6, 4), window.subjects());

            repo.write("work.txt", "line 7\n");
            repo.commitAll("work 7"); // in a terminal: Editora has not heard of it
            window.onFx(window.windows::loadMoreGitLog);

            assertEquals(work(7, 5), window.subjects(), "reloaded to the depth on screen, no row repeated or skipped");
            window.onFx(window.windows::loadMoreGitLog);
            assertEquals(work(7, 2), window.subjects());
        }
    }

    @Test
    void searchingAFileHistoryFindsTheCommitsFromBeforeARename(@TempDir Path dir) throws Exception {
        GitTestRepo repo = renamedRepo(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(repo.root.resolve("journal.txt"));
            assertEquals(
                    List.of("journal: add five", "rename to journal", "notes: add four", "add the notes"),
                    window.subjects());

            // The rename commit's message does not hold "four": git --follow --grep lost this commit.
            window.onFx(() -> window.windows.searchGitLog("four"));
            assertEquals(List.of("notes: add four"), window.subjects(), "a message term");
            assertEquals(tr("gitlog.history", "journal.txt"), window.header(), "still the file's history");
            String old = FxTestSupport.callOnFx(() -> {
                List<GitLog.Entry> rows = FxTestSupport.field(window.panel, "allCommits");
                return window.panel.followedPath(rows.get(0).hash());
            });
            assertEquals("notes.txt", old, "and the file's name in that commit is still known");

            window.onFx(() -> window.windows.searchGitLog("author:Bob"));
            assertEquals(
                    List.of("notes: add four"),
                    window.subjects(),
                    "an author term — and not Bob's commit to the other file");

            window.onFx(() -> window.windows.searchGitLog("until:2020-12-31"));
            assertEquals(
                    List.of("notes: add four", "add the notes"),
                    window.subjects(),
                    "a date limit that leaves the rename commit out");

            window.onFx(() -> window.windows.searchGitLog("since:2021-01-01"));
            assertEquals(List.of("journal: add five", "rename to journal"), window.subjects());

            window.onFx(() -> window.windows.searchGitLog("content:four"));
            assertEquals(List.of("notes: add four"), window.subjects(), "the pickaxe");

            window.onFx(() -> window.windows.searchGitLog("author:Ada until:2020-12-31 notes"));
            assertEquals(List.of("add the notes"), window.subjects(), "the terms together");

            window.onFx(() -> window.windows.searchGitLog("nothing-says-this"));
            assertEquals(List.of(), window.subjects());
            assertEquals(tr("gitlog.noMatches"), window.placeholder());

            window.onFx(() -> window.windows.searchGitLog(""));
            assertEquals(4, window.subjects().size(), "the search is over: the whole file history again");
        }
    }

    @Test
    void aPathTermIsNotShownAsSearchedInAFileHistory(@TempDir Path dir) throws Exception {
        GitTestRepo repo = renamedRepo(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(repo.root.resolve("journal.txt"));

            window.onFx(() -> window.windows.searchGitLog("path:other.txt"));

            assertEquals(4, window.subjects().size(), "the term cannot narrow a one-path history");
            Label chip = FxTestSupport.callOnFx(() -> FxTestSupport.field(window.panel, "searchLabel"));
            assertFalse(
                    FxTestSupport.callOnFx(() -> chip.getParent().isVisible()),
                    "so no \"Search: path:…\" chip claims it did");
            assertEquals(tr("status.git.log.pathIgnored"), window.echo());

            window.onFx(() -> window.windows.searchGitLog("path:other.txt four"));
            assertEquals(List.of("notes: add four"), window.subjects());
            assertEquals(tr("gitlog.searching", "four"), FxTestSupport.callOnFx(chip::getText));

            // In the branch's log the same term is a search like any other.
            window.onFx(() -> window.windows.loadGitLog(null));
            window.onFx(() -> window.windows.searchGitLog("path:other.txt"));
            assertEquals(List.of("touch the other file", "add the notes"), window.subjects());
            assertEquals(tr("gitlog.searching", "path:other.txt"), FxTestSupport.callOnFx(chip::getText));
        }
    }

    @Test
    void openingADiffFromAFileHistoryKeepsTheLogInAWindowWithoutAProject(@TempDir Path dir) throws Exception {
        GitTestRepo repo = renamedRepo(dir);
        Path file = repo.root.resolve("journal.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(file);
            assertEquals(
                    repo.root, FxTestSupport.callOnFx(window.git::repoRoot), "the real refresh found the repository");
            window.select("rename to journal");

            // The lower list: this revision against the working file. The tab it opens has no file of its own.
            window.press("files", KeyCode.ENTER);
            assertNotNull(window.awaitTab(DiffViewerPane.class), "the compare opened (status: " + window.echo() + ")");
            window.onFx(window.git::refresh); // what every tab switch runs

            assertEquals(
                    repo.root, FxTestSupport.callOnFx(window.git::repoRoot), "the diff tab stays in the repository");
            assertTrue(window.logOpen(), "so the log that opened it is still there");
            assertEquals(file, FxTestSupport.callOnFx(() -> window.windows.gitLogFilter), "with the file history");
            assertEquals(tr("gitlog.history", "journal.txt"), window.header());
            assertEquals(4, window.subjects().size());

            // The commit list: the whole-commit review from the row menu's command, another file-less tab.
            String hash = FxTestSupport.callOnFx(window.panel::selectedHash);
            window.onFx(() -> window.windows.reviewCommitIn(repo.root, hash));
            assertNotNull(window.awaitTab(DirectoryReviewPane.class));
            window.onFx(window.git::refresh);
            assertEquals(repo.root, FxTestSupport.callOnFx(window.git::repoRoot));
            assertTrue(window.logOpen());

            // A tab with no Git context at all still has none.
            window.onFx(() -> window.run("file.new"));
            assertNull(FxTestSupport.callOnFx(window.git::repoRoot), "an untitled buffer is in no repository");
        }
        assertFalse(GitWindowGate.showsGitView(null));
        assertFalse(GitWindowGate.localGitView("a string"));
    }

    @Test
    void aRowOfAFileHistoryOpensWhatTheCommitChangedInThatFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = renamedRepo(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(repo.root.resolve("journal.txt"));
            window.select("rename to journal");

            ContextMenu menu = FxTestSupport.callOnFx(() -> {
                ListView<GitLog.Entry> commits = FxTestSupport.field(window.panel, "commits");
                GitLog.Entry row = commits.getSelectionModel().getSelectedItem();
                commits.applyCss();
                commits.layout();
                for (Node node : commits.lookupAll(".list-cell")) {
                    // Any cell builds the menu of the row it is asked about.
                    if (node instanceof ListCell<?> cell
                            && cell.getClass().getSimpleName().equals("CommitCell")) {
                        return (ContextMenu)
                                FxTestSupport.call(cell, "buildMenu", new Class<?>[] {GitLog.Entry.class}, row);
                    }
                }
                return null;
            });
            assertNotNull(menu, "the selected row has a cell");
            List<String> labels =
                    menu.getItems().stream().map(MenuItem::getText).toList();
            assertEquals(
                    List.of(tr("gitlog.menu.showDiff"), tr("gitlog.menu.compareWorking")),
                    labels.subList(0, 2),
                    "the file's actions come first");
            assertTrue(labels.contains(tr("gitlog.menu.review")), "the whole commit is still one item away");

            window.press("commits", KeyCode.ENTER);
            Object tab = window.awaitTab(DiffViewerPane.class);
            assertNotNull(tab, "Enter opened the file's diff, not a review of the whole commit: " + window.echo());

            // The menu's first item is the same diff; its second the compare with the working file.
            window.onFx(() -> menu.getItems().get(1).fire());
            assertNotNull(window.awaitTab(DiffViewerPane.class));

            // The branch's log is unchanged: Enter reviews the whole commit.
            window.onFx(() -> window.windows.loadGitLog(null));
            window.select("touch the other file");
            window.press("commits", KeyCode.ENTER);
            assertNotNull(window.awaitTab(DirectoryReviewPane.class));
        }
    }

    @Test
    void aTabsFileHistoryIsListedInThatFilesRepository(@TempDir Path dir) throws Exception {
        GitTestRepo outer = GitTestRepo.init(Files.createDirectory(dir.resolve("a")));
        outer.write("near.txt", "one\n");
        outer.commitAll("near one");
        GitTestRepo other = GitTestRepo.init(Files.createDirectory(dir.resolve("b")));
        Path far = other.write("far.txt", "one\n");
        other.commitAll("far one");
        other.write("far.txt", "two\n");
        other.commitAll("far two");
        // A repository inside the outer one: its files lie under the outer root and are not part of it.
        Path nestedDir = Files.createDirectory(outer.root.resolve("nested"));
        GitTestRepo nested = GitTestRepo.init(nestedDir);
        Path inner = nested.write("inner.txt", "one\n");
        nested.commitAll("inner one");

        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.openFile(far);
            window.openFile(inner);
            window.openFile(outer.root.resolve("near.txt"));
            assertEquals(outer.root, FxTestSupport.callOnFx(window.git::repoRoot));

            fireTabHistory(window, far);
            assertEquals(List.of("far two", "far one"), window.subjects(), "not \"outside repository\"");
            assertEquals(tr("gitlog.history", "far.txt"), window.header());
            assertEquals(other.root, FxTestSupport.callOnFx(window.git::repoRoot));

            fireTabHistory(window, inner);
            assertEquals(List.of("inner one"), window.subjects(), "not the outer repository's \"No commits\"");
            assertEquals(nested.root, FxTestSupport.callOnFx(window.git::repoRoot));

            // Back on a file of the outer repository the nested file's history is not listed there.
            window.openFile(outer.root.resolve("near.txt"));
            window.onFx(window.git::refresh);
            assertEquals(outer.root, FxTestSupport.callOnFx(window.git::repoRoot));
            assertNull(FxTestSupport.callOnFx(() -> window.windows.gitLogFilter));
            assertEquals(List.of("near one"), window.subjects());
        }
    }

    /** Git ▸ Show File History of the tab holding {@code file}, which need not be the selected one. */
    private static void fireTabHistory(Window window, Path file) throws Exception {
        window.onFx(() -> {
            EditorBuffer buffer = (EditorBuffer)
                    FxTestSupport.call(window.controller, "openBufferFor", new Class<?>[] {Path.class}, file);
            assertNotNull(buffer, "the file is open: " + file);
            ContextMenu menu = new ContextMenu();
            TabContextMenu.build(window.controller, new Tab(), buffer, menu);
            MenuItem history = find(menu.getItems(), tr("project.menu.git.fileHistory"));
            assertNotNull(history);
            history.fire();
        });
        window.settle();
    }

    private static MenuItem find(List<MenuItem> items, String label) {
        for (MenuItem item : items) {
            if (label.equals(item.getText())) {
                return item;
            }
            if (item instanceof Menu menu) {
                MenuItem found = find(menu.getItems(), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void theHistoryOfADeletedFileShowsWhatItsCommitsDid(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path kept = repo.write("kept.txt", "one\n");
        repo.write("gone.txt", "soon gone\n");
        repo.commitAll("add both");
        repo.git("rm", "-q", "gone.txt");
        repo.commitAll("delete gone");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.openFile(kept);
            window.onFx(() -> window.windows.gitFileHistoryForPath(repo.root.resolve("gone.txt")));
            assertEquals(List.of("delete gone", "add both"), window.subjects());

            window.select("add both");
            ListView<CommitFile> files = FxTestSupport.callOnFx(() -> FxTestSupport.field(window.panel, "files"));
            assertEquals(
                    "gone.txt",
                    FxTestSupport.callOnFx(
                            () -> files.getSelectionModel().getSelectedItem().path()));
            window.press("files", KeyCode.ENTER);

            assertNotNull(window.awaitTab(DiffViewerPane.class), "it used to answer only that the file is gone");
            assertEquals(tr("status.git.history.noWorkingCopy", "gone.txt"), window.echo());
        }
    }

    @Test
    void aFileWithNoCommitsABufferWithNoFileAndAFolderEachSaySo(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "one\n");
        repo.commitAll("add tracked");
        Path scratch = repo.write("scratch.txt", "draft\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(scratch);
            assertEquals(List.of(), window.subjects());
            assertEquals(tr("gitlog.noFileCommits", "scratch.txt"), window.placeholder());
            assertTrue(window.placeholder().contains("scratch.txt"));

            window.onFx(() -> window.windows.gitFileHistoryForPath(repo.root));
            assertEquals(tr("status.git.history.noFile"), window.echo(), "a folder");

            window.onFx(() -> window.run("file.new"));
            window.onFx(() -> window.run("git.fileHistory"));
            assertEquals(tr("status.git.history.noFile"), window.echo(), "an untitled buffer");
        }
        GitLog.Page failed = GitLog.Page.failed("git said no");
        assertTrue(
                GitWindowCoordinator.searchedHistory(GitLog.Page.EMPTY, failed) == failed,
                "a search that failed is not \"no matches\"");
        GitLogPanel.View branch = new GitLogPanel.View(null, "main", false, "", true);
        GitLogPanel.View searched = new GitLogPanel.View("a.txt", "main", false, "fix", false);
        assertEquals(tr("gitlog.noCommits"), GitLogPanel.placeholderText("", branch));
        assertEquals(tr("gitlog.noMatches"), GitLogPanel.placeholderText("", searched));
        assertEquals(tr("gitlog.failed", "boom"), GitLogPanel.placeholderText("boom", searched));
    }

    @Test
    void aShallowCloneSaysWhereItsHistoryWasCut(@TempDir Path dir) throws Exception {
        GitTestRepo origin = interleavedRepo(Files.createDirectory(dir.resolve("origin")), 3);
        Path clone = dir.resolve("clone");
        origin.git("clone", "-q", "--depth", "2", origin.root.toUri().toString(), clone.toString());
        Path shallow = clone.toRealPath();
        assertTrue(GitWindowCoordinator.shallow(shallow));
        assertFalse(GitWindowCoordinator.shallow(origin.root));
        assertFalse(GitWindowCoordinator.shallow(null));
        assertFalse(GitWindowCoordinator.shallow(dir.resolve("missing")));

        // A worktree's .git is a file naming its git directory, which names the shared one.
        Path worktree = Files.createDirectory(dir.resolve("worktree"));
        Path gitDir = Files.createDirectories(dir.resolve("main/.git/worktrees/w"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");
        Files.writeString(gitDir.resolve("commondir"), "../..\n");
        assertFalse(GitWindowCoordinator.shallow(worktree));
        Files.writeString(dir.resolve("main/.git/shallow"), "0000\n");
        assertTrue(GitWindowCoordinator.shallow(worktree));
        Files.writeString(worktree.resolve(".git"), "not a pointer\n");
        assertFalse(GitWindowCoordinator.shallow(worktree));

        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(shallow.resolve("work.txt"));
            assertEquals(1, window.subjects().size(), "depth 2 holds one commit of the file");
            Label footer = FxTestSupport.callOnFx(() -> FxTestSupport.field(window.panel, "truncatedLabel"));
            assertEquals(tr("gitlog.shallow"), FxTestSupport.callOnFx(footer::getText));
            assertTrue(FxTestSupport.callOnFx(
                    () -> footer.isVisible() && footer.getParent().isVisible()));
            Button more = FxTestSupport.callOnFx(() -> FxTestSupport.field(window.panel, "loadMoreButton"));
            assertFalse(FxTestSupport.callOnFx(more::isVisible), "there is nothing to load");

            window.openFile(origin.root.resolve("work.txt"));
            window.onFx(window.windows::showFileHistory);
            assertEquals("", FxTestSupport.callOnFx(footer::getText), "a full clone has no such line");
        }
    }

    @Test
    void theHeaderChipAndEscapeLeaveAFileHistoryAndTheToolbarTakesFocus(@TempDir Path dir) throws Exception {
        GitTestRepo repo = renamedRepo(dir);
        Path file = repo.root.resolve("journal.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = Window.open(async);
            window.fileHistory(file);
            Button back = FxTestSupport.callOnFx(() -> FxTestSupport.field(window.panel, "showAllButton"));
            assertTrue(FxTestSupport.callOnFx(back::isVisible), "the ✕ of the header chip");
            assertEquals(tr("gitlog.showAllTip"), FxTestSupport.callOnFx(back::getAccessibleText));
            assertTrue(FxTestSupport.callOnFx(
                    () -> back.getParent() == ((Label) FxTestSupport.field(window.panel, "filterLabel")).getParent()));

            window.onFx(back::fire);
            assertEquals(tr("gitlog.branch", "main"), window.header());
            assertEquals(5, window.subjects().size(), "the branch's log");
            assertFalse(FxTestSupport.callOnFx(back::isVisible));

            window.onFx(() -> window.windows.gitFileHistoryForPath(file));
            assertEquals(tr("gitlog.history", "journal.txt"), window.header());
            window.press("commits", KeyCode.ESCAPE);
            assertEquals(tr("gitlog.branch", "main"), window.header(), "Escape in the commit list does the same");
            assertNull(FxTestSupport.callOnFx(() -> window.windows.gitLogFilter));

            List<Boolean> stops = FxTestSupport.callOnFx(() -> {
                List<Boolean> traversable = new ArrayList<>();
                Node toolbar = window.panel.lookup(".git-toolbar");
                for (Node node : toolbar.lookupAll(".button")) {
                    traversable.add(node.isFocusTraversable());
                }
                for (Node node : toolbar.lookupAll(".toggle-button")) {
                    traversable.add(node.isFocusTraversable());
                }
                traversable.add(back.isFocusTraversable());
                return traversable;
            });
            assertTrue(stops.size() >= 3, "all branches, refresh and the chip's ✕: " + stops);
            assertFalse(stops.contains(false), "every toolbar control is a Tab stop");
        }
    }
}
