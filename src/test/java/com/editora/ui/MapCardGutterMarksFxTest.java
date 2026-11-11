package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.git.ChangeType;
import com.editora.git.GitService;
import com.editora.run.MainMethodScanner;
import com.editora.test.JavaTestScanner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Map gaps round 3, item 1: what a card's gutter is told of a window's breakpoints, bookmarks, ▶ and change bars. */
@Tag("fx")
class MapCardGutterMarksFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A window whose answers the test sets, recording what it is asked to do. */
    private static final class Stub implements MapCardGutterMarks.Window {
        boolean breakpointsOn;
        boolean runs;
        boolean testRunner;
        boolean gitEnabled;

        @Nullable
        EditorBuffer open;

        GitService.@Nullable GitDiff diff;
        final List<String> did = new ArrayList<>();

        @Override
        public boolean breakpointsOn(Path file) {
            return breakpointsOn;
        }

        @Override
        public Map<Integer, MapCardGutter.Breakpoint> breakpoints(Path file) {
            return Map.of(3, new MapCardGutter.Breakpoint(null, ""));
        }

        @Override
        public void toggleBreakpoint(Path file, int line, String text) {
            did.add("toggle " + line);
        }

        @Override
        public Set<Integer> bookmarks(Path file) {
            return Set.of(1);
        }

        @Override
        public boolean runs(Path file) {
            return runs;
        }

        @Override
        public boolean testRunner() {
            return testRunner;
        }

        @Override
        public @Nullable EditorBuffer openBuffer(Path file) {
            return open;
        }

        @Override
        public boolean gitEnabled() {
            return gitEnabled;
        }

        @Override
        public void gitChanges(Path file, Consumer<GitService.@Nullable GitDiff> found) {
            did.add("git " + file.getFileName());
            found.accept(diff);
        }

        @Override
        public void runMain(String fqn) {
            did.add("main " + fqn);
        }

        @Override
        public void runTest(JavaTestScanner.TestTarget target) {
            did.add("test " + target.methodName());
        }
    }

    @Test
    void theMarksFollowTheWindowsGates() {
        Stub window = new Stub();
        MapCardGutterMarks marks = new MapCardGutterMarks(window);
        Path file = dir.resolve("A.java");

        MapCardGutter.Marks none = marks.marks(file);
        assertFalse(none.breakpointsOn());
        assertEquals(Map.of(), none.breakpoints(), "no strip: no breakpoints read");
        assertEquals(Set.of(1), none.bookmarks(), "bookmarks are always shown");
        assertFalse(none.tests());
        assertFalse(none.mains());
        marks.toggleBreakpoint(file, 2, "x");
        assertEquals(List.of(), window.did, "no strip: a click does nothing");

        window.breakpointsOn = true;
        window.runs = true;
        MapCardGutter.Marks some = marks.marks(file);
        assertEquals(Set.of(3), some.breakpoints().keySet());
        assertTrue(some.mains());
        assertFalse(some.tests(), "the Test Runner is off");
        window.testRunner = true;
        assertTrue(marks.marks(file).tests());
        marks.toggleBreakpoint(file, 2, "x");
        assertEquals(List.of("toggle 2"), window.did);

        window.did.clear();
        marks.run(file, new MapCardGutter.Run(null, new MainMethodScanner.MainMethod(0, "demo.App")));
        marks.run(file, new MapCardGutter.Run(new JavaTestScanner.TestTarget(1, "demo.AppTest", "adds", false), null));
        marks.run(file, new MapCardGutter.Run(null, null));
        assertEquals(List.of("main demo.App", "test adds"), window.did);
    }

    @Test
    void theBarsComeFromAnOpenTabElseFromGit() throws Exception {
        Stub window = new Stub();
        MapCardGutterMarks marks = new MapCardGutterMarks(window);
        Path file = dir.resolve("A.java");
        List<MapCardGutter.@Nullable Changes> found = new ArrayList<>();

        marks.changes(file, found::add);
        assertNull(found.getLast(), "Git is off");
        assertEquals(List.of(), window.did);

        window.gitEnabled = true;
        marks.changes(file, found::add);
        assertNull(found.getLast(), "Git knows nothing of it");
        window.diff = new GitService.GitDiff(Map.of(), Map.of());
        marks.changes(file, found::add);
        assertNull(found.getLast(), "nothing changed");
        window.diff = new GitService.GitDiff(Map.of(2, ChangeType.ADDED), Map.of(2, "hunk"));
        marks.changes(file, found::add);
        assertEquals(Map.of(2, "git-added"), found.getLast().bars());
        assertEquals(Map.of(2, "hunk"), found.getLast().tooltips());
        assertEquals(List.of("git A.java", "git A.java", "git A.java"), window.did);

        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            try {
                b.setContent("a\nb\n");
                b.setChangeBars(Map.of(0, "git-modified"));
                window.open = b;
                marks.changes(file, found::add);
                assertEquals(Map.of(0, "git-modified"), found.getLast().bars(), "the tab's own bars");
                assertEquals(3, window.did.size(), "Git is not asked");
            } finally {
                b.dispose();
            }
        });
    }

    @Test
    void gitReadsAFilesBarsOffTheFxThread() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        Path file = Files.writeString(repo.resolve("A.txt"), "one\ntwo\n");
        git(repo, "add", "A.txt");
        git(repo, "-c", "user.name=Ada", "-c", "user.email=ada@example.com", "commit", "-q", "-m", "start");
        Files.writeString(file, "one\nTWO\n");
        GitService service = new GitService();
        try {
            assertEquals(Map.of(1, ChangeType.MODIFIED), ask(service, file).changes());
            assertNull(ask(service, Path.of("/").getRoot()), "no folder to read it in");
            service.shutdown();
            assertNull(ask(service, file), "a stopped service answers nothing");
        } finally {
            service.shutdown();
        }
    }

    private static GitService.@Nullable GitDiff ask(GitService service, Path file) throws Exception {
        CompletableFuture<GitService.@Nullable GitDiff> answer = new CompletableFuture<>();
        service.fileChanges(file, answer::complete);
        return answer.get(30, TimeUnit.SECONDS);
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", command));
    }
}
