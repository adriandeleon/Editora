package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

import com.editora.diff.ConflictParser;
import com.editora.diff.DiffEngine;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitChanges;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the debugger's, the diff viewer's and the Map cards' handlers: each test drives on the FX
 * thread, the way an event does, what used to diff, parse or scan a document there — a breakpoint's line remapped
 * against the launched text after an edit, a hand-edited merge Result saved, Resolve Conflicts and Open in Diff
 * Viewer on a patch tab, a diff's hunk applied, a Map Changes card's file picked, a History card shown, a breakpoint
 * toggled on a line whose code-ness is a scan — and listens to {@link BlockingSites}: each named place is heard off
 * the FX thread, none on it. Stale answers are dropped or worked out again.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every loop);
 * the debug commands that wait on the same {@link DebugTextScans} (Debug Test at Caret's Rust, JavaScript and pytest
 * scans, the unittest fallback) are covered by the one scan shown here and by their window tests; the Go and Rust
 * launch plans now made in {@code debug-go-plan} and {@code debug-rust-plan} need the tools, and are covered where
 * those run ({@code RustDebugWindowFxTest}, the Go debug tests); Apply Patch's parse of the active tab and of the
 * touched paths ({@code GitPatchCoordinator.PATCH_CHECK_SITE}) needs a repository, and is driven by
 * {@code GitPatchFeaturesFxTest}; the Map card's other uses of a name ({@code MapCardMoves.USES_SITE}) need a focused
 * card at rest.
 */
@Tag("fx")
class HandlersDebugOffFxThreadFxTest {

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

    private void assertNothingOnFx() {
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "work done in place on the FX thread; heard: " + heard);
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    private EditorBuffer buffer(Path file, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(text);
            return b;
        });
    }

    /** Runs the work held so far, each on this (test) thread, and lets its answer land. */
    private static void runHeld(List<Runnable> held) throws Exception {
        FxTestSupport.drainFx();
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        now.forEach(Runnable::run);
        FxTestSupport.drainFx();
    }

    @Test
    void aBreakpointLineIsRemappedOffTheFxThreadForTheTextAsItIsNow() throws Exception {
        Path file = dir.resolve("app.py");
        EditorBuffer b = buffer(file, "a()\nb()\nc()\n");
        LaunchedLines lines = new LaunchedLines();
        try {
            FxTestSupport.runOnFx(() -> lines.snapshotIfAbsent(file, b.getContent()));
            listen();
            boolean[] known = {false};
            FxTestSupport.runOnFx(() -> {
                b.getArea().insertText(0, "added()\n"); // one line typed above the breakpoints
                assertNull(lines.knownOr(b, "t", () -> known[0] = true), "not worked out on the FX thread");
            });
            SettingsRig.awaitFx("the mapping landed", () -> known[0]);
            LaunchedLines.Mapping map = FxTestSupport.callOnFx(() -> lines.known(b));
            assertNotNull(map);
            assertEquals(2, map.toCurrent(1), "b() is the document's line 2 now");
            assertEquals(-1, map.toLaunched(0), "the added line has no code in the running program");
            awaitHeard(LaunchedLines.SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(b::dispose);
        }
    }

    @Test
    void aMappingForTextThatMovedOnIsWorkedOutAgainBeforeAnyoneActsOnIt() throws Exception {
        Path file = dir.resolve("app.py");
        EditorBuffer b = buffer(file, "a()\nb()\n");
        LaunchedLines lines = new LaunchedLines();
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        try {
            int[] ran = {0};
            FxTestSupport.runOnFx(() -> {
                lines.snapshotIfAbsent(file, b.getContent());
                b.getArea().insertText(0, "one()\n");
                assertNull(lines.knownOr(b, "t", () -> ran[0]++));
            });
            FxTestSupport.drainFx(); // the snapshot is taken after the edit: its work is held now
            assertEquals(1, held.size());
            FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "two()\n")); // typed before it lands
            runHeld(held); // the answer is for the old text: worked out again, nobody told yet
            assertEquals(0, ran[0], "an answer for text that moved on is not acted on");
            runHeld(held);
            assertEquals(1, ran[0]);
            LaunchedLines.Mapping map = FxTestSupport.callOnFx(() -> lines.known(b));
            assertNotNull(map);
            assertEquals(2, map.toCurrent(0), "a() is below both added lines");
        } finally {
            FxTestSupport.runOnFx(b::dispose);
        }
    }

    @Test
    void aHandEditedMergeResultIsWorkedOutOffTheFxThreadAndASecondPressWaits() throws Exception {
        ConflictParser.ConflictFile file = ConflictParser.parse(
                List.of("top", "<<<<<<< ours", "mine", "=======", "theirs", ">>>>>>> other", "end"));
        List<String> saved = new CopyOnWriteArrayList<>();
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", file, "Monospaced", 13, "\r\n", true, (text, done) -> {
                    saved.add(text);
                    done.accept(true);
                }));
        SettingsRig.awaitFx("the Result put together", () -> !(Boolean) FxTestSupport.field(pane, "composing"));
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        listen();
        FxTestSupport.runOnFx(() -> {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            result.setText("top\nby hand\nend\n");
            FxTestSupport.call(pane, "saveResult", new Class<?>[] {Runnable.class}, new Object[] {null});
            FxTestSupport.call(pane, "saveResult", new Class<?>[] {Runnable.class}, new Object[] {null});
            assertTrue(pane.saving(), "busy while the text is worked out");
        });
        assertEquals(1, held.size(), "a second press while the first is worked out does nothing");
        runHeld(held);
        assertEquals(List.of("top\r\nby hand\r\nend\r\n"), saved, "the document's separator restored");
        assertFalse(FxTestSupport.callOnFx(pane::saving));
        assertFalse(FxTestSupport.callOnFx(pane::hasUnsavedChanges));
        assertTrue(heard.stream().anyMatch(h -> h.site().equals(MergeViewerPane.SAVE_SITE) && !h.fx()));
        assertNothingOnFx();
    }

    @Test
    void resolveConflictsAndAPatchTabAreParsedOffTheFxThread() throws Exception {
        Path conflicted = Files.writeString(
                dir.resolve("c.txt"), "top\n<<<<<<< ours\nmine\n=======\ntheirs\n>>>>>>> other\nend\n");
        Path patch = Files.writeString(
                dir.resolve("change.patch"), "--- a/n.txt\n+++ b/n.txt\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        EditorBuffer conflicts = open(conflicted);
        listen();
        int tabs = FxTestSupport.callOnFx(() -> fx.controller.tabPane.getTabs().size());
        FxTestSupport.runOnFx(diff::resolveConflicts);
        awaitHeard(DiffCoordinator.MERGE_PARSE_SITE);
        SettingsRig.awaitFx(
                "the resolver opened",
                () -> fx.controller.tabPane.getTabs().stream()
                        .anyMatch(t -> t.getUserData() instanceof MergeViewerPane));
        EditorBuffer patchTab = open(patch);
        FxTestSupport.runOnFx(() -> diff.openPatchFile(patchTab));
        awaitHeard(DiffCoordinator.PATCH_PARSE_SITE);
        SettingsRig.awaitFx(
                "the patch's diff opened",
                () -> fx.controller.tabPane.getTabs().stream()
                        .anyMatch(t -> t.getUserData() instanceof DiffViewerPane));
        awaitHeard(DiffCoordinator.RENUMBER_SITE); // numbered as the patch numbers it, on the diff's thread
        awaitHeard(com.editora.diff.DiffLayout.SITE); // the panes' texts, laid out where the model was made
        awaitHeard(MergeViewerPane.RESULT_SITE); // the resolver's Result, put together from the choices
        // A pull request's file opened as a patch: its sides joined, diffed and numbered off FX.
        com.editora.diff.PatchParser.FilePatch fp = com.editora.diff.PatchParser.parse(
                        "--- a/p.txt\n+++ b/p.txt\n@@ -40,2 +40,2 @@\n keep\n-before\n+after\n")
                .getFirst();
        FxTestSupport.runOnFx(() -> diff.openPatchFileDiff("pr", "base", "head", "p.txt", fp));
        SettingsRig.awaitFx(
                "the pull request file's diff opened",
                () -> fx.controller.tabPane.getTabs().stream()
                        .anyMatch(t -> t.getUserData() instanceof DiffViewerPane p && "pr".equals(p.title())));
        // Folding unchanged context and the unified view: laid out already, only put into the panes on FX.
        FxTestSupport.runOnFx(() -> {
            for (var t : fx.controller.tabPane.getTabs()) {
                if (t.getUserData() instanceof DiffViewerPane p) {
                    FxTestSupport.invoke(p, "toggleView");
                    FxTestSupport.invoke(p, "toggleView");
                }
            }
        });
        assertTrue(FxTestSupport.callOnFx(() -> fx.controller.tabPane.getTabs().size()) > tabs);
        assertFalse(FxTestSupport.callOnFx(conflicts::isDisposed));
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    private EditorBuffer open(Path file) throws Exception {
        MainController mc = fx.controller;
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(file, 0));
        SettingsRig.awaitFx("the tab of " + file.getFileName(), () -> {
            EditorBuffer active = mc.activeBuffer();
            return active != null && file.equals(active.getPath()) && !active.isLoading();
        });
        return FxTestSupport.callOnFx(mc::activeBuffer);
    }

    @Test
    void aHunkAppliedInTheDiffViewerIsWorkedOutOffTheFxThread() throws Exception {
        String left = "a\nB\nc\n";
        String right = "a\nb\nc\n";
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        List<String> applied = new CopyOnWriteArrayList<>();
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> {
            DiffViewerPane p = new DiffViewerPane(
                    "diff", "left", "right", "x.txt", "x.txt", left, right, model, "Monospaced", 13, true, "x.txt");
            p.setEditableAsync(
                    DiffViewerPane.EditableSide.RIGHT,
                    (text, done) -> {
                        applied.add(text);
                        done.accept(true);
                    },
                    () -> {},
                    () -> {});
            return p;
        });
        listen();
        int block = FxTestSupport.callOnFx(() -> model.changeBlockStarts().get(0));
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(pane, "applyBlock", int.class, block));
        SettingsRig.awaitFx("the hunk applied", () -> !applied.isEmpty());
        assertEquals("a\nB\nc\n", applied.getFirst());
        awaitHeard(DiffViewerPane.APPLY_SITE);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    /**
     * A diff viewer whose model's kept layouts were dropped lays its view out off the FX thread and shows it when it
     * lands; its application mark is never read on the FX thread, and the warm-up reads it off it.
     */
    @Test
    void aDiffViewerWithoutKeptLayoutsLaysOutOffTheFxThreadAndShowsWhenTheyLand() throws Exception {
        String left = "a\nB\nc\n";
        String right = "a\nb\nc\n";
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT); // never laid out: as if evicted
        Icons.forgetEditoraForTest();
        listen();
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "diff", "left", "right", "x.txt", "x.txt", left, right, model, "Monospaced", 13, true, "x.txt"));
        SettingsRig.awaitFx("the view shown once laid out", () -> FxTestSupport.field(pane, "sideBySideNode") != null);
        awaitHeard(DiffViewerPane.LAYOUT_SITE);
        awaitHeard(com.editora.diff.DiffLayout.SITE);
        Icons.forgetEditoraForTest(); // the viewer's background load may have kept it already
        Thread warm = Thread.ofVirtual().start(Icons::warmEditora); // what bundled-warm does after the first window
        warm.join();
        awaitHeard(Icons.EDITORA_ICON_SITE);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    /** The debug expression field tokenizes off the FX thread once typing pauses; only the latest ask is applied. */
    @Test
    void theDebugExpressionFieldIsHighlightedOffTheFxThread() throws Exception {
        DebugExpressionField field = FxTestSupport.callOnFx(() -> new DebugExpressionField(
                new DebugExpressionField.Owner() {
                    @Override
                    public boolean paused() {
                        return true;
                    }

                    @Override
                    public void complete(
                            String text,
                            int caret,
                            java.util.function.Consumer<List<com.editora.dap.DapModels.CompletionTarget>> cb) {
                        cb.accept(List.of());
                    }

                    @Override
                    public void submit(String expression) {}
                },
                new DebugPanelText.History(),
                () -> null));
        FxTestSupport.runOnFx(() -> field.setLanguageFile("Main.java"));
        SettingsRig.awaitFx("the grammar loaded", () -> !"".equals(field.languageFile()));
        listen();
        FxTestSupport.runOnFx(() -> {
            for (char c : "return 1;".toCharArray()) {
                field.area().appendText(String.valueOf(c)); // typed: one highlight once the typing pauses
            }
        });
        SettingsRig.awaitFx(
                "`return` styled as a keyword",
                () -> field.area().getStyleOfChar(1).contains("keyword"));
        awaitHeard(DebugExpressionField.HIGHLIGHT_SITE);
        long asks = heard.stream()
                .filter(h -> h.site().equals(DebugExpressionField.HIGHLIGHT_SITE))
                .count();
        assertTrue(asks < "return 1;".length(), "tokenized once typing paused, not per key: " + asks);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    /** Two saved Map views are compared property by property, with no JSON. */
    @Test
    void savedMapViewsCompareByTheirProperties() {
        com.editora.config.ProjectMapState a = new com.editora.config.ProjectMapState();
        com.editora.config.ProjectMapState b = new com.editora.config.ProjectMapState();
        assertTrue(ProjectMapWorkingSets.sameView(a, b));
        a.setSelected("src/A.java");
        assertFalse(ProjectMapWorkingSets.sameView(a, b));
        b.setSelected("src/A.java");
        assertTrue(ProjectMapWorkingSets.sameView(a, b));
        a.setExpanded(List.of("src"));
        assertFalse(ProjectMapWorkingSets.sameView(a, b), "a list only one of them has");
        assertFalse(ProjectMapWorkingSets.sameView(b, a));
    }

    @Test
    void aMapChangesCardsWordsAreWorkedOutOffTheFxThreadAndTheLatestPickWins() throws Exception {
        List<GitChanges.FileDiff> files = GitChanges.parse("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n"
                + "@@ -1 +1 @@\n-int a = 1;\n+int a = 10;\n"
                + "diff --git a/b.txt b/b.txt\n--- a/b.txt\n+++ b/b.txt\n"
                + "@@ -1 +1 @@\n-old words here\n+new words here\n");
        assertEquals(2, files.size());
        ProjectMapChangesCard card = FxTestSupport.callOnFx(ProjectMapChangesCard::new);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        listen();
        FxTestSupport.runOnFx(() -> {
            card.show(dir, null);
            card.setChanges(new GitChanges.Result(files, false, "", ""));
            card.pickFile(0);
            card.pickFile(1); // picked again before the first file's words land
        });
        runHeld(held);
        var words = FxTestSupport.callOnFx(card::words);
        assertEquals(java.util.Set.of(1, 2), words.keySet(), "the second file's lines");
        assertEquals(0, words.get(1)[0][0], "old → new: the first word of b.txt's lines, not a.txt's 10");
        assertTrue(heard.stream().anyMatch(h -> h.site().equals(ProjectMapChangesCard.WORDS_SITE) && !h.fx()));
        assertNothingOnFx();
    }

    @Test
    void aHistoryCardsLinesAreCutOffTheFxThread() throws Exception {
        ProjectMapHistoryCard card = FxTestSupport.callOnFx(ProjectMapHistoryCard::new);
        listen();
        FxTestSupport.runOnFx(
                () -> card.show(dir.resolve("A.java"), "run", 1, 2, "class A {\n  void run() {\n  }\n}\n"));
        awaitHeard(ProjectMapHistoryCard.LINES_SITE);
        SettingsRig.awaitFx("the lines", () -> {
            List<String> lines = FxTestSupport.field(card, "lines");
            return lines.equals(List.of("  void run() {", "  }"));
        });
        assertNothingOnFx();
    }

    @Test
    void aDebugCommandsScanOfTheTextRunsOffTheFxThreadAndIsAskedAgainWhenItLands() throws Exception {
        EditorBuffer b = buffer(dir.resolve("A.java"), "class A {\n  // a comment\n  void run() {}\n}\n");
        DebugTextScans scans = new DebugTextScans();
        try {
            listen();
            int[] asked = {0};
            Runnable[] command = new Runnable[1];
            Boolean[] answer = new Boolean[1];
            command[0] = () -> {
                asked[0]++;
                var known = scans.scan(
                        b, "codeLess:1", t -> com.editora.dap.CodeLessLines.isCodeLess(t, 1, "java"), command[0]);
                if (known != null) {
                    answer[0] = known.value();
                }
            };
            FxTestSupport.runOnFx(command[0]);
            SettingsRig.awaitFx("asked again with the answer", () -> answer[0] != null);
            assertTrue(answer[0], "a comment-only line");
            assertEquals(2, asked[0], "asked once, then once more when the scan landed");
            awaitHeard(DebugTextScans.SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(b::dispose);
        }
    }

    @Test
    void aCodeCardsLinesAreCountedOffTheFxThreadBeforeItsAidsAreAskedFor() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project")).toRealPath();
        Path file = Files.writeString(project.resolve("A.java"), "class A {\n    void run() {}\n}\n");
        MapCardEditorAidsFxTest.Server server = new MapCardEditorAidsFxTest.Server();
        javafx.stage.Stage[] stage = new javafx.stage.Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            stage[0] = new javafx.stage.Stage();
            stage[0].setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(made), 1000, 700));
            stage[0].show();
            made.setRoot(project);
            made.setLinkSource(server);
            made.setCardAids(() -> new MapCardAids(true, true, "all", true));
            return made;
        });
        try {
            listen();
            FxTestSupport.runOnFx(() -> view.cards.openFileCard(file));
            awaitHeard(MapCards.CARD_LINES_SITE);
            SettingsRig.awaitFx("the tokens asked for", () -> server.asked.contains("tokens 4 0"));
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }
}
