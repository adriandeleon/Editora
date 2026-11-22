package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the searches handlers made as the user types or presses a key: each test drives on the FX
 * thread, the way an event does, what used to search or parse there — Search Everywhere typed into (the commands, the
 * project's files and symbols), the command palette and a picker filtered, the Structure outline built and filtered,
 * the Settings search, the Git log's filter, Go to Definition without a language server, Go to Symbol, and a save
 * (the saved file's declarations scanned again) — and listens to {@link BlockingSites}: every named place is heard off
 * the FX thread, none on it. Then the answers: the latest query's wins, Enter pressed before it lands acts on it, and
 * a Go to Definition whose caret moved meanwhile does not jump.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every loop); the
 * Map's {@code @name} search ({@code ProjectMapSymbolSearch.SEARCH_SITE}, the same corpus search driven through the
 * Project panel), Related File and the shortcut list's filter (named the same way, driven elsewhere:
 * {@code NavigationCoordinatorFxTest}, {@code SettingsShortcutsFxTest}); and the building of the rows on the FX thread
 * from an answer (a tree item or a list row per match, which is rendering, not searching).
 */
@Tag("fx")
class HandlersIndexOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site and whether it was the FX thread. */
    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private FxWindowFixture fx;

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest((site, thread) -> heard.add(new Heard(site, Platform.isFxApplicationThread())));
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    private void assertNothingOnFx() throws Exception {
        FxTestSupport.drainFx();
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "searched on the FX thread; heard: " + heard);
    }

    @Test
    void searchEverywhereThePaletteSettingsAndTheGitLogSearchOffTheFxThread() throws Exception {
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        listen();

        // Search Everywhere typed into: the commands, files and symbols are searched on a picker thread.
        SearchEverywherePopup popup =
                FxTestSupport.field(FxTestSupport.field(fx.controller, "chrome"), "searchEverywherePopup");
        FxTestSupport.runOnFx(() -> {
            popup.show("");
            FxTestSupport.<TextField>field(popup, "input").setText("save");
            FxTestSupport.invoke(popup, "refresh"); // what the query debounce does
        });
        awaitHeard(SearchEverywherePopup.SEARCH_SITE);
        awaitHeard(IndexCoordinator.SEARCH_SITE);
        SettingsRig.awaitFx("rows for the query", () -> !popup.searching());
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost").hide());

        // The command palette filtered.
        CommandPalette palette = FxTestSupport.field(fx.controller, "palette");
        FxTestSupport.runOnFx(() -> {
            palette.ensureBuilt();
            FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, "zoom");
        });
        awaitHeard(CommandPalette.FILTER_SITE);

        // The Git log's filter box over its loaded commits.
        GitLogPanel log = FxTestSupport.callOnFx(() ->
                new GitLogPanel((GitLogPanel.Actions) java.lang.reflect.Proxy.newProxyInstance(
                        GitLogPanel.Actions.class.getClassLoader(),
                        new Class<?>[] {GitLogPanel.Actions.class},
                        (p, m, a) -> null)));
        FxTestSupport.runOnFx(() -> {
            log.setLog(
                    List.of(new com.editora.git.GitLog.Entry(
                            "0123456789abcdef", "0123456", "fix the parser", "Ada", 0L, "2026-01-01", List.of())),
                    null);
            FxTestSupport.<TextField>field(log, "filterField").setText("parser");
        });
        awaitHeard(GitLogPanel.FILTER_SITE);
        SettingsRig.awaitFx("the log filtered", () -> !log.filterPending());

        assertNothingOnFx();
    }

    @Test
    void pickersAndTheStructureOutlineFilterOffTheFxThread() throws Exception {
        listen();
        // A picker over a large list (Loaded Sources can be thousands).
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            many.add("Source" + i + ".java");
        }
        List<String> chosen = new CopyOnWriteArrayList<>();
        QuickOpen<String> picker = FxTestSupport.callOnFx(() -> shownPicker(many, chosen));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<TextField>field(picker, "input").setText("Source4999"));
        awaitHeard(QuickOpen.FILTER_SITE);
        SettingsRig.awaitFx("the picker filtered", () -> !picker.filtering());

        // The Structure outline: built off the FX thread, then filtered there too.
        StructurePanel panel = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 300, 400);
            return p;
        });
        EditorBuffer markdown = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("markdown");
            b.setContent("# Alpha\n\ntext\n\n## Beta\n\n## Gamma\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> panel.attach(markdown));
        awaitHeard(StructurePanel.OUTLINE_SITE);
        SettingsRig.awaitFx("the outline shown", () -> panel.outline().size() == 3);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<TextField>field(panel, "filterField").setText("gam"));
        awaitHeard(StructurePanel.FILTER_SITE);
        SettingsRig.awaitFx("the outline filtered", () -> !panel.filtering());

        assertNothingOnFx();
        FxTestSupport.runOnFx(markdown::dispose);
    }

    @Test
    void goToDefinitionGoToSymbolAndASaveScanOffTheFxThread(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("lib.sh"), "#!/bin/sh\n\nhelper() {\n    :\n}\n");
        String main = "#!/bin/sh\n. ./lib.sh\nlocal_fn() {\n    :\n}\nhelper\nlocal_fn\n";
        Path mainFile = Files.writeString(project.resolve("main.sh"), main);
        Rig rig = new Rig(project, mainFile, main);
        try {
            // A name declared in the same file: the buffer is scanned off the FX thread.
            listen();
            FxTestSupport.runOnFx(() -> {
                rig.buffer.getArea().moveTo(main.lastIndexOf("local_fn") + 2);
                rig.index.gotoDefinitionAtCaret();
            });
            awaitHeard(IndexCoordinator.DEFINITION_SITE);
            SettingsRig.awaitFx("jumped within the file", () -> rig.lines.contains(2));

            // Another file's: the project walk, then the index searched off the FX thread.
            FxTestSupport.runOnFx(() -> {
                rig.buffer.getArea().moveTo(main.lastIndexOf("helper") + 2);
                rig.index.gotoDefinitionAtCaret();
            });
            SettingsRig.awaitFx("jumped to lib.sh", () -> rig.opened.contains(project.resolve("lib.sh")));
            awaitHeard(IndexCoordinator.SEARCH_SITE);

            // A save: the saved text's declarations are scanned on the index's thread, then searchable.
            FxTestSupport.runOnFx(() -> {
                rig.buffer.getArea().appendText("\nadded_later() {\n    :\n}\n");
                rig.index.onBufferSaved(rig.buffer);
            });
            awaitHeard(IndexCoordinator.SAVE_SCAN_SITE);
            SettingsRig.awaitFx(
                    "the saved declaration indexed",
                    () -> rig.index.corpus().symbols().search("added_later", 5).size() == 1);
            assertNothingOnFx();
        } finally {
            rig.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T noop(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (p, m, a) -> m.getReturnType() == boolean.class ? false : null);
    }

    /**
     * The tool windows' filters (bookmarks, notes, build tasks), the status bar's word count, and the Structure outline
     * of a Python file (its decorators read for each row's marker) all work off the FX thread.
     */
    @Test
    void toolWindowFiltersTheWordCountAndAPythonOutlineWorkOffTheFxThread() throws Exception {
        listen();
        BookmarksPanel bookmarks = FxTestSupport.callOnFx(() -> new BookmarksPanel(
                () -> new BookmarksPanel.Scope(java.util.Map.of(), "", k -> "General"),
                noop(BookmarksPanel.Actions.class)));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<TextField>field(bookmarks, "filterField").setText("todo"));
        awaitHeard(BookmarksPanel.FILTER_SITE);
        NotesPanel notes = FxTestSupport.callOnFx(() -> new NotesPanel(
                () -> new NotesPanel.Scope(java.util.Map.of(), "", k -> "General"), noop(NotesPanel.Actions.class)));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<TextField>field(notes, "filterField").setText("todo"));
        awaitHeard(NotesPanel.FILTER_SITE);
        BuildActionsTree tasks = FxTestSupport.callOnFx(() -> {
            BuildActionsTree t = new BuildActionsTree();
            t.setProvider(new com.editora.build.BuildActionsProvider() {
                @Override
                public List<com.editora.build.BuildAction.Section> sections(java.util.Set<String> active) {
                    return List.of(new com.editora.build.BuildAction.Section(
                            "Lifecycle",
                            List.of(new com.editora.build.BuildAction.Task("package", List.of("package")))));
                }

                @Override
                public List<String> toggleArgs(java.util.Set<String> active) {
                    return List.of();
                }
            });
            return t;
        });
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<TextField>field(tasks, "filterField").setText("pack"));
        awaitHeard(BuildActionsTree.FILTER_SITE);
        SettingsRig.awaitFx("the tasks filtered", () -> !tasks.filtering());

        // The word count of a small Markdown buffer: counted off the FX thread however small.
        EditorBuffer prose = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(dir.resolve("words.md"));
            b.setContent("one two three\n");
            return b;
        });
        StatusBar bar = FxTestSupport.callOnFx(() -> new StatusBar(
                () -> prose, new com.editora.command.CommandRegistry(), com.editora.config.Settings::new));
        FxTestSupport.runOnFx(() -> bar.attach(prose));
        awaitHeard(StatusWordCount.COUNT_SITE);
        SettingsRig.awaitFx(
                "three words", () -> "3 words".equals(bar.wordCount.label().getText()));

        // A Python outline: built, its docs and decorator markers read, off the FX thread.
        StructurePanel panel = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 300, 400);
            return p;
        });
        EditorBuffer python = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("python");
            b.setContent("class A:\n    @property\n    def size(self):\n        return 1\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> panel.attach(python));
        awaitHeard(StructurePanel.OUTLINE_SITE);
        assertNothingOnFx();
        FxTestSupport.runOnFx(() -> {
            prose.dispose();
            python.dispose();
        });
    }

    /** The latest query's answer is shown, an older one landing late is dropped, and Enter waits for the latest. */
    @Test
    void theLatestAnswerWinsAndEnterWaitsForIt() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest(holding(PickerSearch.THREAD, held));
        List<String> chosen = new CopyOnWriteArrayList<>();
        QuickOpen<String> picker = FxTestSupport.callOnFx(() -> shownPicker(List.of("alpha", "beta", "gamma"), chosen));
        ListView<String> list = FxTestSupport.field(picker, "list");
        TextField input = FxTestSupport.field(picker, "input");
        FxTestSupport.runOnFx(() -> input.setText("a")); // alpha, beta, gamma
        FxTestSupport.runOnFx(() -> input.setText("gam")); // gamma
        assertEquals(2, held.size());
        FxTestSupport.runOnFx(() -> held.get(1).run()); // the latest lands first
        FxTestSupport.drainFx();
        assertEquals(List.of("gamma"), FxTestSupport.callOnFx(() -> List.copyOf(list.getItems())));
        FxTestSupport.runOnFx(() -> held.get(0).run()); // the superseded one lands late: dropped
        FxTestSupport.drainFx();
        assertEquals(List.of("gamma"), FxTestSupport.callOnFx(() -> List.copyOf(list.getItems())));

        // Enter pressed before the latest answer lands chooses from that answer, not from the rows shown.
        held.clear();
        FxTestSupport.runOnFx(() -> {
            input.setText("bet");
            input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER, false, false, false, false));
        });
        assertTrue(chosen.isEmpty(), "nothing chosen from rows that answer an older query");
        FxTestSupport.runOnFx(() -> held.get(0).run());
        FxTestSupport.drainFx();
        assertEquals(List.of("beta"), chosen);
    }

    /** A Go to Definition whose answer lands after the caret moved does not pull the user back. */
    @Test
    void goToDefinitionDropsAnAnswerForACaretThatMoved(@TempDir Path project) throws Exception {
        String main = "#!/bin/sh\nlocal_fn() {\n    :\n}\nlocal_fn\n";
        Path mainFile = Files.writeString(project.resolve("main.sh"), main);
        Rig rig = new Rig(project, mainFile, main);
        try {
            List<Runnable> held = new CopyOnWriteArrayList<>();
            OffFx.runnerForTest(holding(IndexCoordinator.DEFINITION_SITE, held));
            FxTestSupport.runOnFx(() -> {
                rig.buffer.getArea().moveTo(main.lastIndexOf("local_fn") + 2);
                rig.index.gotoDefinitionAtCaret();
                rig.index.gotoDefinitionAtCaret(); // a second press while the first is out starts nothing
            });
            assertEquals(1, held.size(), "one scan, however often the command is pressed");
            FxTestSupport.runOnFx(() -> rig.buffer.getArea().moveTo(0)); // the user moved on
            FxTestSupport.runOnFx(() -> held.get(0).run());
            FxTestSupport.drainFx();
            assertEquals(List.of(), rig.lines, "no jump for a caret that moved");

            // Asked again where it stands, it answers.
            OffFx.runnerForTest((name, task) -> task.run());
            FxTestSupport.runOnFx(() -> {
                rig.buffer.getArea().moveTo(main.lastIndexOf("local_fn") + 2);
                rig.index.gotoDefinitionAtCaret();
            });
            FxTestSupport.drainFx();
            assertEquals(List.of(1), rig.lines);
        } finally {
            rig.dispose();
        }
    }

    /** Holds the work run under {@code name} in {@code held}; runs any other in place. */
    private static java.util.function.BiConsumer<String, Runnable> holding(String name, List<Runnable> held) {
        return (n, task) -> {
            if (name.equals(n)) {
                held.add(task);
            } else {
                task.run();
            }
        };
    }

    private static QuickOpen<String> shownPicker(List<String> source, List<String> chosen) {
        QuickOpen<String> picker = new QuickOpen<>("Title", "Prompt", () -> source, s -> s, s -> "", chosen::add);
        OverlayHost overlay = new OverlayHost();
        StackPane root = new StackPane();
        overlay.install(root);
        new Scene(root, 600, 400);
        picker.setOverlayHost(overlay);
        picker.show(null);
        return picker;
    }

    /** An index over {@code project} with a shell buffer of {@code main} active, and what it opened. */
    private static final class Rig {
        final List<Path> opened = new CopyOnWriteArrayList<>();
        final List<Integer> lines = new CopyOnWriteArrayList<>();
        final EditorBuffer buffer;
        final IndexCoordinator index;

        Rig(Path project, Path mainFile, String main) throws Exception {
            buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setLanguageOverride("shell");
                b.setContent(main);
                b.setPath(mainFile);
                b.getNode();
                return b;
            });
            com.editora.config.Settings settings = new com.editora.config.Settings();
            CoordinatorHostStub host = new CoordinatorHostStub() {
                @Override
                public com.editora.config.Settings settings() {
                    return settings;
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return buffer;
                }
            };
            index = FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
                @Override
                public Path projectRoot() {
                    return project;
                }

                @Override
                public void openAndGoto(Path file, int line, int column) {
                    opened.add(file);
                    lines.add(line);
                }

                @Override
                public boolean respectGitignore() {
                    return false;
                }
            }));
        }

        void dispose() throws Exception {
            FxTestSupport.runOnFx(() -> {
                index.dispose();
                buffer.dispose();
            });
        }
    }
}
