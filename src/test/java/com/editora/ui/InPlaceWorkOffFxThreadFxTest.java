package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.TextField;

import com.editora.config.PathKeys;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.io.DocumentWriteSequencer;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import com.editora.search.SearchQuery;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the work done in place on a document: each test drives on the FX thread, the way an event
 * does, what used to search, hash or parse the user's text there — a typed edit and its undo back to the saved text
 * (the dirty check's hash), the find bar typed into and its Replace and Replace All, a caret beside a bracket whose
 * partner is far off in a large file, shell completion's scan of the script, the Run scan after an open, Replace in
 * Files on an open buffer, a rename that rewrites an open Markdown buffer's links, Gradle Debug Test before its init
 * script's warm-up — and listens to {@link BlockingSites}: every named place is heard off the FX thread, none on it.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every loop);
 * an HTTP run's fingerprints and a Clojure Run with its tool missing (no server or missing tool is staged here:
 * {@code HttpClientCoordinator.finishRun} names {@code HttpRerunTarget.fingerprint} through {@code OffFx}, and the
 * Clojure message reads the plan's answer); the second window's frame (it parses nothing: {@code MainViewFxTest}
 * keeps it equal to {@code main.fxml}); the start-up fallback ({@code AppFallbackBootstrapTest}); and the
 * remaining waits this part leaves, listed in {@code docs/performance.md}.
 */
@Tag("fx")
class InPlaceWorkOffFxThreadFxTest {

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
    void editingFindingAndMatchingBracketsInALargeDocumentWorkOffTheFxThread() throws Exception {
        // A few MiB: an opening bracket at the top whose partner is 30,000 characters on, past the near window
        // (the screen and one screen each way) and inside the matcher's 50,000.
        StringBuilder text = new StringBuilder("{\n");
        String line = "needle in a line of hay, long enough to fill a screen and more\n";
        while (text.length() < 30_000) {
            text.append(line);
        }
        text.append("}\n");
        while (text.length() < 3 * 1024 * 1024) {
            text.append(line);
        }
        Path big = Files.writeString(dir.resolve("big.txt"), text);
        // The window's own file work off the FX thread, as in the app (the plain fixture runs it in place).
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        EditorBuffer buffer = open(big);
        CodeArea area = FxTestSupport.callOnFx(buffer::getArea);
        listen();

        // A typed edit, settled (the baseline lets its text go), then undone back to the saved text.
        FxTestSupport.runOnFx(() -> area.insertText(2, "x"));
        FxTestSupport.awaitSettled(buffer); // past the 150 ms settled-edit milestone
        FxTestSupport.runOnFx(() -> area.deleteText(2, 3));
        SettingsRig.awaitFx("clean again once the hash lands", () -> !buffer.isDirty());

        // The find bar typed into, then Replace and Replace All.
        FindReplaceBar bar = fx.controller.findBar;
        FxTestSupport.runOnFx(() -> {
            bar.show(false);
            FxTestSupport.<TextField>field(bar, "findField").setText("needle");
            FxTestSupport.<TextField>field(bar, "replaceField").setText("pin");
            FxTestSupport.invoke(bar, "recompute"); // what the query debounce does
        });
        awaitHeard(FindReplaceBar.SEARCH_SITE);
        SettingsRig.awaitFx("a match selected", () -> area.getSelection().getLength() == "needle".length());
        FxTestSupport.runOnFx(bar::replaceCurrentMatch);
        awaitHeard(FindReplaceBar.REPLACE_SITE);
        SettingsRig.awaitFx("one replaced", () -> area.getText(0, 200).contains("pin in a line"));
        FxTestSupport.runOnFx(bar::replaceAllMatches);
        awaitHeard(FindReplaceBar.REPLACE_ALL_SITE);
        SettingsRig.awaitFx(
                "all replaced",
                () -> !area.getText(area.getLength() - 200, area.getLength()).contains("needle"));
        FxTestSupport.runOnFx(bar::hideBar);

        // The caret beside the far bracket: looked for near the caret on FX, the rest of the window off it.
        FxTestSupport.runOnFx(() -> area.moveTo(1));
        awaitHeard("EditorBuffer.braceMatch");
        SettingsRig.awaitFx("the far partner matched", () -> {
            Object completion = FxTestSupport.field(buffer, "completionActions");
            int[] pair = ((com.editora.editor.completion.BufferCompletion) completion).braceMatch();
            return pair != null && pair[0] == 0 && "}".equals(area.getText(pair[1], pair[1] + 1)) && pair[1] > 20_000;
        });

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void completionTheRunScanAndGradleDebugInitWorkOffTheFxThread() throws Exception {
        Path script =
                Files.writeString(dir.resolve("tool.sh"), "#!/bin/bash\ngreeting_name=x\nprint_greeting() { :; }\npri");
        Path http = Files.writeString(dir.resolve("api.http"), "GET https://example.org/\n\n###\nGET https://e.org/\n");
        // The window's own file work off the FX thread, as in the app (the plain fixture runs it in place).
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        // Opened first: a window's own set-up for a new kind of file (its loose language-server root, which the app's
        // config-prefetch thread makes before any window) is not what this test is about.
        EditorBuffer shell = open(script);
        EditorBuffer requests = open(http);
        listen();

        // A shell buffer with no server: the script's own names are scanned off FX, then offered.
        FxTestSupport.runOnFx(() -> {
            fx.controller.openAndNavigate(script, 0);
            shell.getArea().moveTo(shell.getArea().getLength());
            shell.triggerCompletion();
        });
        awaitHeard(com.editora.editor.completion.BufferCompletion.SHELL_NAMES_SITE);

        // A request file edited: its Run scan (the ▶ of each request) runs off FX, and a Run asked meanwhile waits.
        boolean[] ran = {false};
        FxTestSupport.runOnFx(() -> {
            requests.getArea().appendText("\n###\nGET https://e.org/more\n");
            requests.whenRunScanned(() -> ran[0] = true);
        });
        awaitHeard("BufferRunGutter.runScan");
        SettingsRig.awaitFx("the scan landed", () -> ran[0]);

        // Debug Test before the init script's warm-up: the command waits for the write, made off FX.
        CompletableFuture<Path> init = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> com.editora.build.GradleDebugInit.whenPrepared(
                List.of("perfGuardTask" + System.nanoTime()), init::complete));
        assertTrue(init.get(20, TimeUnit.SECONDS) != null, "the init script was written");
        awaitHeard("GradleDebugInit.write");

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void replaceInFilesOnAnOpenBufferWorksItsEditsOutOffTheFxThread() throws Exception {
        Path file = Files.writeString(dir.resolve("open.txt"), "old one old\n");
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent("old one old\n");
            return b;
        });
        Map<String, EditorBuffer> open = new HashMap<>();
        open.put(PathKeys.key(file), buffer);
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        SearchCoordinator.Ops ops = SearchCoordinator.ops(
                new SearchCoordinator.Navigation(() -> null, (f, l, c, focus) -> {}, () -> false, () -> {}, () -> {}),
                new SearchCoordinator.ReplaceSupport(
                        f -> open.get(PathKeys.key(f)),
                        b -> false,
                        (f, content, done) -> done.accept(true),
                        sequencer::begin),
                new SearchCoordinator.Persistence(q -> {}, FXCollections::observableArrayList, found -> {}));
        SearchCoordinator coordinator = FxTestSupport.callOnFx(() ->
                new SearchCoordinator(new CoordinatorHostStub(), ops, Executors.newSingleThreadExecutor(), n -> true));
        try {
            listen();
            SearchCoordinator.ReplaceResult result = FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(
                            new SearchQuery("old", true, false, false), "new", List.of(file)))
                    .get(20, TimeUnit.SECONDS);
            assertEquals(2, result.count());
            assertEquals("new one new\n", FxTestSupport.callOnFx(buffer::getContent));
            awaitHeard(SearchCoordinator.REPLACEMENT_CHECK_SITE);
            awaitHeard(SearchCoordinator.OPEN_REPLACE_SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            coordinator.shutdown();
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void aRenameRewritesAnOpenBuffersLinksOffTheFxThread() throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path old = Files.writeString(root.resolve("old.md"), "# Old\n");
        Path openFile = Files.writeString(root.resolve("open.md"), "[o](old.md)\n");
        LinkHost host = new LinkHost();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(openFile);
            b.setContent("[o](old.md)\n");
            host.buffers.add(b);
            return b;
        });
        host.settings.setMarkdownUpdateLinksOnRename("always");
        MarkdownLinkGraph graph = new MarkdownLinkGraph(host, () -> root, () -> true);
        MarkdownLinkUpdater updater = new MarkdownLinkUpdater(host, graph, (f, text, done) -> done.accept(true));
        try {
            Path renamed = root.resolve("new.md");
            Files.move(old, renamed);
            listen();
            FxTestSupport.runOnFx(() -> updater.onRenamed(old, renamed));
            MarkdownLinkUpdater.Outcome outcome =
                    FxTestSupport.callOnFx(() -> updater.lastRun).get(20, TimeUnit.SECONDS);
            assertEquals(1, outcome.links());
            assertEquals("[o](new.md)\n", FxTestSupport.callOnFx(buffer::getContent));
            awaitHeard(MarkdownLinkUpdater.OPEN_REWRITE_SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            graph.dispose();
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    /**
     * The close question's Save: the write runs off the FX thread and the question answers "saving"; the tab closes
     * when the close is asked for again, once the file is on disk. No nested event loop waits for it.
     */
    @Test
    void savingFromTheCloseQuestionClosesOnceTheWriteHasLanded() throws Exception {
        Path file = Files.writeString(dir.resolve("draft.txt"), "first\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {});
        EditorBuffer buffer = open(file);
        FxTestSupport.runOnFx(() -> buffer.getArea().appendText("more\n"));
        try (DialogAnswers answers = DialogAnswers.pressing(com.editora.i18n.Messages.tr("dialog.save"))) {
            boolean[] again = {false};
            CloseCoordinator.Answer answer =
                    FxTestSupport.callOnFx(() -> fx.controller.closes.askCloseIfDirty(buffer, () -> again[0] = true));
            assertEquals(CloseCoordinator.Answer.SAVING, answer, "the tab stays while the write runs");
            SettingsRig.awaitFx("the close asked again once saved", () -> again[0]);
            assertEquals("first\nmore\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    /** A file's personal notes show at their stored offsets at once and are found in the text off the FX thread. */
    @Test
    void personalNotesAreRelocatedOffTheFxThread() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("intro\nthe noted words here\n");
            return b;
        });
        try {
            // Saved when the words were on line 0: the text has moved on by a line since.
            com.editora.config.PersonalNote note = com.editora.config.PersonalNote.create(
                    null,
                    com.editora.config.NoteScope.WORD,
                    new com.editora.config.TextAnchor(0, 4, 0, 9, "noted", "the ", " words"),
                    "a note",
                    List.of());
            listen();
            boolean[] moved = {false};
            boolean[] landed = {false};
            FxTestSupport.runOnFx(() -> buffer.applyNotes(List.of(note), m -> {
                moved[0] = m;
                landed[0] = true;
            }));
            SettingsRig.awaitFx("the relocation landed", () -> landed[0]);
            assertTrue(moved[0], "found a line down from where it was saved");
            awaitHeard(com.editora.editor.notes.NoteManager.RELOCATE_SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    /** A host with settings and some open buffers, for the link rewrite. */
    private static final class LinkHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(java.util.function.Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }
    }

    /** A Replace All while text is typed in between: worked out again on the new text, applied once, one undo step. */
    @Test
    void replaceAllWithTypingInBetweenAppliesToTheTextAsItIsThen() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("a a a");
            return b;
        });
        List<Runnable> held = new CopyOnWriteArrayList<>();
        List<String> status = new CopyOnWriteArrayList<>();
        FindReplaceBar bar = FxTestSupport.callOnFx(() -> {
            FindReplaceBar b = new FindReplaceBar(() -> buffer, status::add);
            new javafx.scene.Scene(new javafx.scene.layout.VBox(b, buffer.getNode()), 600, 400);
            b.searchOn(held::add, Platform::runLater, 100);
            b.show(false);
            FxTestSupport.<TextField>field(b, "findField").setText("a");
            FxTestSupport.<TextField>field(b, "replaceField").setText("b");
            return b;
        });
        try {
            FxTestSupport.runOnFx(bar::replaceAllMatches);
            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText(" a"); // typed before the edits land
                buffer.getArea().getUndoManager().preventMerge(); // its own undo step, apart from the replace
            });
            runHeld(held); // the first answer is for the old text: worked out again
            assertEquals("a a a a", FxTestSupport.callOnFx(buffer::getContent), "not applied to the changed text");
            runHeld(held);
            assertEquals("b b b b", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(() -> buffer.getArea().undo());
            assertEquals("a a a a", FxTestSupport.callOnFx(buffer::getContent), "one undo step");
            assertFalse(status.isEmpty());
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    private static void runHeld(List<Runnable> held) throws Exception {
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        now.forEach(Runnable::run);
        FxTestSupport.drainFx();
    }
}
