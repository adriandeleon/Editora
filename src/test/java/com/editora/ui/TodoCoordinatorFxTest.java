package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.todo.TodoComment;
import com.editora.todo.TodoGrouping;
import com.editora.todo.TodoMatch;
import com.editora.todo.TodoPattern;
import com.editora.todo.TodoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoCoordinator} against a recording window: the scan's scope (the project tree, or only the open
 * files), the edits the panel's menu asks for, the quick-add pattern prompts, and the persisted grouping.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoCoordinatorFxTest {

    @TempDir
    Path dir;

    private final Settings settings = new Settings();
    private final List<String> events = new ArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final Deque<String> promptAnswers = new ArrayDeque<>();
    private final List<String> promptInitials = new ArrayList<>();
    private EditorBuffer active;
    private Path projectRoot;
    private boolean toolWindowOpen;
    private int saves;
    private TodoCoordinator coordinator;
    private CommandRegistry registry;

    private final class Host extends CoordinatorHostStub {
        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            List.copyOf(buffers).forEach(action);
        }

        @Override
        public synchronized void setStatus(String message) {
            events.add("status: " + message);
            notifyAll();
        }

        @Override
        public void requestSave() {
            saves++;
        }

        @Override
        public void syncSettingsWindow() {
            events.add("syncSettings");
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            events.add("prompt " + label);
            promptInitials.add(initial);
            if (!promptAnswers.isEmpty()) {
                onAccept.accept(promptAnswers.poll());
            }
        }

        synchronized void await(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (events.stream().noneMatch(wanted)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("no such event; got " + events);
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
    }

    private final Host host = new Host();

    private final TodoCoordinator.Ops ops = new TodoCoordinator.Ops() {
        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public void openMatch(Path file, int line, int col) {
            events.add("open " + file.getFileName() + ":" + line + ":" + col);
        }

        @Override
        public boolean isToolWindowOpen() {
            return toolWindowOpen;
        }

        @Override
        public void toggleToolWindow() {
            toolWindowOpen = !toolWindowOpen;
            events.add("toolWindow " + toolWindowOpen);
        }

        @Override
        public String homeCollapsed(String absolutePath) {
            return "~" + Path.of(absolutePath).getFileName();
        }

        @Override
        public void applyLineEdit(Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
            events.add("edit " + file.getFileName() + ":" + line + " [" + expectedLine + "] -> [" + newLine + "]");
            afterApply.run();
        }
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void create() throws Exception {
        events.clear();
        promptAnswers.clear();
        promptInitials.clear();
        active = null;
        projectRoot = null;
        toolWindowOpen = false;
        saves = 0;
        settings.setTodoHighlight(true);
        settings.setTodoGroupBy("FILE");
        settings.setTodoPatterns(new Settings().getTodoPatterns());
        FxTestSupport.runOnFx(() -> {
            coordinator = new TodoCoordinator(host, ops);
            registry = new CommandRegistry();
            coordinator.registerCommands(registry);
            coordinator.applyHighlight();
        });
    }

    @AfterEach
    void shutdown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            coordinator.shutdown();
            buffers.forEach(EditorBuffer::dispose);
            buffers.clear();
        });
    }

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    private EditorBuffer buffer(Path path, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            if (path != null) {
                b.setPath(path);
            }
            b.setContent(text);
            buffers.add(b);
            coordinator.applyToBuffer(b);
            return b;
        });
    }

    private TodoPanel.Actions panelActions() {
        return FxTestSupport.field(coordinator.panel(), "actions");
    }

    private static TodoMatch match(int line, String text) {
        int start = text.indexOf("TODO");
        return new TodoMatch(
                start, start + 4, line, start + 1, text, "TODO", "#ffcc00", TodoComment.parse(text, start, start + 4));
    }

    private TodoService.Outcome scan() throws Exception {
        AtomicReference<TodoService.Outcome> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        fx(() -> coordinator.scanForMcp(outcome -> {
            result.set(outcome);
            done.countDown();
        }));
        assertTrue(done.await(60, TimeUnit.SECONDS), "the scan finished");
        return result.get();
    }

    private static List<String> files(TodoService.Outcome outcome) {
        return outcome.files().stream()
                .map(f -> f.file().getFileName().toString())
                .sorted()
                .toList();
    }

    // --- scanning ---------------------------------------------------------------------------------------

    @Test
    void withoutAProjectOnlyTheOpenSavedBuffersAreScanned() throws Exception {
        Files.writeString(dir.resolve("on-disk.txt"), "// TODO on disk, not open\n");
        buffer(dir.resolve("open.txt"), "// TODO in an open buffer\n");
        buffer(null, "// TODO in an untitled buffer\n");

        TodoService.Outcome outcome = scan();
        assertEquals(List.of("open.txt"), files(outcome));
        assertEquals(1, outcome.totalMatches());
    }

    @Test
    void withAProjectItsTreeIsScannedAndAnOpenBuffersTextWinsOverTheFile() throws Exception {
        Path project = Files.createDirectory(dir.resolve("proj"));
        Files.writeString(project.resolve("a.txt"), "// TODO saved\n");
        Files.writeString(project.resolve("b.txt"), "nothing here\n");
        projectRoot = project;
        buffer(project.resolve("b.txt"), "// TODO typed but not saved\n// TODO and another\n");

        TodoService.Outcome outcome = scan();
        assertEquals(List.of("a.txt", "b.txt"), files(outcome));
        assertEquals(3, outcome.totalMatches());
    }

    @Test
    void withHighlightingOffNothingIsScannedAndThePanelIsEmptied() throws Exception {
        buffer(dir.resolve("open.txt"), "// TODO x\n");
        settings.setTodoHighlight(false);
        assertEquals(0, scan().totalMatches());

        fx(coordinator::runScan);
        Label summary = FxTestSupport.field(coordinator.panel(), "summary");
        assertEquals(tr("todo.none"), FxTestSupport.callOnFx(summary::getText));
        assertFalse(events.contains("status: " + tr("todo.scanning")), "no scan was started: " + events);
    }

    @Test
    void refreshOpensTheToolWindowScansAndReportsTheCount() throws Exception {
        Path project = Files.createDirectory(dir.resolve("proj"));
        Files.writeString(project.resolve("a.txt"), "// TODO one\n// FIXME two\n");
        projectRoot = project;

        fx(() -> registry.run("todo.refresh"));
        host.await(("status: " + tr("todo.summary", 2, 1))::equals);
        assertEquals("toolWindow true", events.getFirst());
        assertEquals("status: " + tr("todo.scanning"), events.get(1));
        Label scope = FxTestSupport.field(coordinator.panel(), "scopeLabel");
        assertEquals("~proj", FxTestSupport.callOnFx(scope::getText));
        @SuppressWarnings("unchecked")
        TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(coordinator.panel(), "tree");
        assertEquals(
                1, FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().size()));

        events.clear();
        fx(() -> registry.run("todo.refresh")); // already open: scans again without toggling it shut
        host.await(("status: " + tr("todo.summary", 2, 1))::equals);
        assertFalse(events.contains("toolWindow false"), events.toString());
    }

    @Test
    void aScanThatFindsNothingSaysSoAndTheScopeLabelNamesTheOpenFiles() throws Exception {
        toolWindowOpen = true;
        fx(coordinator::refreshPanelIfOpen);
        host.await(("status: " + tr("todo.none"))::equals);
        Label scope = FxTestSupport.field(coordinator.panel(), "scopeLabel");
        assertEquals(tr("search.scopeOpenFiles"), FxTestSupport.callOnFx(scope::getText));

        events.clear();
        toolWindowOpen = false;
        fx(coordinator::refreshPanelIfOpen);
        assertEquals(List.of(), events, "a closed tool window is not refreshed");
    }

    // --- the panel's edits ------------------------------------------------------------------------------

    @Test
    void thePanelsEditsRewriteTheMatchsLineAndRescanOnlyWhileThePanelIsOpen() throws Exception {
        Path file = dir.resolve("a.txt");
        TodoMatch m = match(4, "  // TODO [ui] fix it");
        fx(() -> {
            panelActions().openMatch(file, 4, 6);
            panelActions().setPriority(file, m, "high");
            panelActions().setKeyword(file, m, "DONE");
            panelActions().setPriority(file, m, null); // it has none: nothing to change
            panelActions().setKeyword(file, m, "TODO"); // already that
        });
        assertEquals(
                List.of(
                        "open a.txt:4:6",
                        "edit a.txt:4 [  // TODO [ui] fix it] -> [  // TODO [ui] (high) fix it]",
                        "edit a.txt:4 [  // TODO [ui] fix it] -> [  // DONE [ui] fix it]"),
                events);

        events.clear();
        promptAnswers.add("fix it properly");
        fx(() -> panelActions().editDescription(file, m));
        assertEquals(List.of("fix it"), promptInitials, "the prompt starts from the current description");
        assertEquals("edit a.txt:4 [  // TODO [ui] fix it] -> [  // TODO [ui] fix it properly]", events.get(1));

        // With the panel open, an applied edit re-scans.
        toolWindowOpen = true;
        fx(() -> panelActions().setPriority(file, m, "low"));
        host.await(("status: " + tr("todo.none"))::equals);
    }

    @Test
    void thePanelsRefreshButtonRunsAScan() throws Exception {
        fx(() -> panelActions().refresh());
        host.await(("status: " + tr("todo.none"))::equals);
        assertEquals("status: " + tr("todo.scanning"), events.getFirst());
    }

    // --- commands ---------------------------------------------------------------------------------------

    @Test
    void addingAPatternAsksForANameThenARegexAndAppliesItToTheBuffers() throws Exception {
        EditorBuffer b = buffer(dir.resolve("a.txt"), "REVIEW this\nreview that\n");
        int before = settings.getTodoPatterns().size();

        fx(() -> registry.run("todo.addPattern")); // cancelled at the name
        promptAnswers.add("  ");
        fx(() -> registry.run("todo.addPattern")); // a blank name
        promptAnswers.add("REVIEW");
        fx(() -> registry.run("todo.addPattern")); // cancelled at the regex
        promptAnswers.add("REVIEW");
        promptAnswers.add(" ");
        fx(() -> registry.run("todo.addPattern")); // a blank regex
        assertEquals(before, settings.getTodoPatterns().size());
        assertEquals(0, saves);

        promptInitials.clear();
        promptAnswers.add(" REVIEW ");
        promptAnswers.add(" \\bREVIEW\\b ");
        fx(() -> registry.run("todo.addPattern"));
        assertEquals(List.of("", "\\b\\QREVIEW\\E\\b"), promptInitials, "the regex is suggested from the name");
        TodoPattern added = settings.getTodoPatterns().getLast();
        assertEquals("REVIEW", added.getName());
        assertEquals("\\bREVIEW\\b", added.getPattern());
        assertTrue(added.isCaseSensitive(), "like the built-ins: 'review' in prose is not a marker");
        assertEquals(1, saves);
        assertEquals("status: " + tr("status.todo.patternAdded", "REVIEW"), events.getLast());
        assertTrue(events.contains("syncSettings"));

        fx(() -> {
            active = b;
            b.getArea().moveTo(b.getArea().getLength());
            registry.run("todo.next");
        });
        assertEquals(
                0,
                FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()),
                "the new marker is found, wrapping");
    }

    @Test
    void jumpingBetweenMarkersSaysWhenThereAreNoneAndTheToolCommandTogglesTheWindow() throws Exception {
        fx(() -> {
            registry.run("todo.next");
            registry.run("todo.previous");
        });
        String none = "status: " + tr("status.todo.none");
        assertEquals(List.of(none, none), events, "no buffer");

        EditorBuffer b = buffer(dir.resolve("a.txt"), "plain\n// TODO first\nplain\n// FIXME second\n");
        active = b;
        fx(() -> {
            b.getArea().moveTo(0);
            registry.run("todo.next");
        });
        assertEquals(1, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        fx(() -> registry.run("todo.previous"));
        assertEquals(3, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "backwards wraps to the last");

        fx(() -> {
            settings.setTodoHighlight(false);
            coordinator.applyHighlight();
            coordinator.applyToBuffer(null); // a tab with no buffer
            events.clear();
            registry.run("todo.next");
        });
        assertEquals(List.of(none), events, "with highlighting off there is nothing to jump to");

        fx(() -> registry.run("tool.todo"));
        assertEquals("toolWindow true", events.getLast());
    }

    // --- grouping ---------------------------------------------------------------------------------------

    @Test
    void theSavedGroupingIsRestoredOnceAndAUsersChangeIsSaved() throws Exception {
        settings.setTodoGroupBy("PRIORITY");
        TodoCoordinator restored = FxTestSupport.callOnFx(() -> {
            TodoCoordinator c = new TodoCoordinator(host, ops);
            c.applyHighlight();
            return c;
        });
        try {
            ComboBox<TodoGrouping.GroupBy> groupBy = FxTestSupport.field(restored.panel(), "groupBy");
            assertEquals(TodoGrouping.GroupBy.PRIORITY, FxTestSupport.callOnFx(groupBy::getValue));
            assertEquals(0, saves, "restoring is not a change to save");

            fx(() -> groupBy.setValue(TodoGrouping.GroupBy.TAG));
            assertEquals("TAG", settings.getTodoGroupBy());
            assertEquals(1, saves);

            settings.setTodoGroupBy("KEYWORD");
            fx(restored::applyHighlight);
            assertEquals(
                    TodoGrouping.GroupBy.TAG, FxTestSupport.callOnFx(groupBy::getValue), "restored once, at start");
        } finally {
            fx(restored::shutdown);
        }
    }

    @Test
    void anUnknownSavedGroupingFallsBackToByFile() throws Exception {
        for (String saved : new String[] {"no-such-grouping", null}) {
            settings.setTodoGroupBy(saved);
            TodoCoordinator c = FxTestSupport.callOnFx(() -> {
                TodoCoordinator fresh = new TodoCoordinator(host, ops);
                fresh.applyHighlight();
                return fresh;
            });
            try {
                ComboBox<TodoGrouping.GroupBy> groupBy = FxTestSupport.field(c.panel(), "groupBy");
                assertEquals(
                        TodoGrouping.GroupBy.FILE, FxTestSupport.callOnFx(groupBy::getValue), String.valueOf(saved));
            } finally {
                fx(c::shutdown);
            }
        }
    }

    @Test
    void theActiveFileIsPassedOnToThePanel() throws Exception {
        Path a = dir.resolve("a.txt");
        fx(() -> coordinator.setActiveFile(a));
        assertEquals(a, FxTestSupport.<Path>field(coordinator.panel(), "activeFile"));
    }
}
