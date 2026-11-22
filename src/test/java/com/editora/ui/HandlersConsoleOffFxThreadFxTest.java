package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.config.SettingsFileCheck;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the console, Settings and grammar handlers: each test drives on the FX thread, the way an
 * event does, what used to search, tokenize or load there — a console find typed into and stepped, a body typed into
 * in the snippet editor, a diff viewer opened on a file type whose grammar nobody loaded, the Settings window opened
 * and changed — and listens to {@link BlockingSites}: every named place is heard off the FX thread, none on it.
 *
 * <p>What it cannot see: work at a place that does not name itself. The Settings window's "which rows are modified"
 * makes no JSON any more (it compares property values through their accessors); that is checked here by answering
 * as the JSON comparison did, not by listening. The terminal's lock is only tried on the FX thread now
 * ({@code TerminalView.underLockSoon}); a contended lock is not staged here.
 */
@Tag("fx")
class HandlersConsoleOffFxThreadFxTest {

    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        ConsoleFindBar.typingPauseForTest(ConsoleFindBar.TYPING_PAUSE_MS);
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

    @Test
    void theConsoleFindSearchesOffTheFxThreadAndTypingWaitsForAPause() throws Exception {
        StringBuilder text = new StringBuilder();
        while (text.length() < 500_000) { // past MAX_MATCHES lines, each with one match
            text.append("a line of build output with a warn in it\n");
        }
        Object[] kept = new Object[2];
        FxTestSupport.runOnFx(() -> {
            CodeArea console = new CodeArea(text.toString());
            ConsoleFindBar bar = ConsoleFindBar.install(console);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(bar, ConsoleFindBar.withMarks(console, console)), 500, 300));
            stage.show();
            kept[0] = stage;
            kept[1] = bar;
        });
        Stage stage = (Stage) kept[0];
        ConsoleFindBar bar = (ConsoleFindBar) kept[1];
        try {
            listen();
            FxTestSupport.runOnFx(() -> {
                bar.open(null);
                bar.field().setText("w");
                bar.field().setText("wa");
                bar.field().setText("warn");
                assertEquals("", bar.countText(), "typing waits for a pause before it searches");
            });
            String total = ConsoleFindBar.MAX_MATCHES + "+";
            SettingsRig.awaitFx(
                    "the count once typing paused", () -> bar.countText().equals(tr("find.count", 1, total)));
            FxTestSupport.runOnFx(() -> bar.field()
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ENTER,
                            false,
                            false,
                            false,
                            false)));
            SettingsRig.awaitFx("the next match", () -> bar.countText().equals(tr("find.count", 2, total)));
            awaitHeard(ConsoleFindBar.SEARCH_SITE);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** An answer for text that changed meanwhile is worked out again; one a later ask superseded is dropped. */
    @Test
    void theConsoleFindAppliesOnlyTheLatestAnswerToTheTextItWasFoundIn() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        ConsoleFindBar.typingPauseForTest(0);
        Object[] kept = new Object[2];
        FxTestSupport.runOnFx(() -> {
            CodeArea console = new CodeArea("one warn\n");
            ConsoleFindBar bar = ConsoleFindBar.install(console);
            new Scene(new VBox(bar, console), 400, 300);
            bar.open(null);
            bar.field().setText("warn");
            console.appendText("two warn\n"); // output arrives before the answer
            kept[0] = console;
            kept[1] = bar;
        });
        ConsoleFindBar bar = (ConsoleFindBar) kept[1];
        runHeld(held); // the answer for "one warn\n" alone: the text changed, so it is searched again
        assertEquals("", FxTestSupport.callOnFx(bar::countText), "not applied to text that changed");
        runHeld(held);
        assertEquals(tr("find.count", 1, "2"), FxTestSupport.callOnFx(bar::countText));

        // Two asks in a row: the first answer is dropped, the second applied.
        FxTestSupport.runOnFx(() -> {
            bar.field().setText("one");
            bar.field().setText("two");
        });
        List<Runnable> both = new ArrayList<>(held);
        held.clear();
        assertEquals(2, both.size());
        both.get(0).run();
        FxTestSupport.drainFx();
        assertEquals(tr("find.count", 1, "2"), FxTestSupport.callOnFx(bar::countText), "the superseded answer");
        both.get(1).run();
        FxTestSupport.drainFx();
        runHeld(held); // a repaint the change queued, if any
        assertEquals(tr("find.count", 1, "1"), FxTestSupport.callOnFx(bar::countText));
        assertEquals("two", FxTestSupport.callOnFx(() -> ((CodeArea) kept[0]).getSelectedText()), "the latest query");
    }

    private static void runHeld(List<Runnable> held) throws Exception {
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        now.forEach(Runnable::run);
        FxTestSupport.drainFx();
    }

    @Test
    void aSnippetBodyTypedIntoIsTokenizedOffTheFxThreadOncePerPause() throws Exception {
        CodeArea body = FxTestSupport.callOnFx(() -> new CodeArea(""));
        listen();
        FxTestSupport.runOnFx(() -> {
            for (String typed : new String[] {"c", "l", "ass A { int x = 1; }"}) {
                body.appendText(typed);
                SettingsSnippetPages.highlightSnippetBodySoon(body, "java");
            }
            assertTrue(heard.isEmpty(), "nothing tokenized while typing goes on");
        });
        SettingsRig.awaitFx(
                "styled once typing paused",
                () -> body.getStyleSpans(0, body.getLength()).stream()
                        .anyMatch(span -> !span.getStyle().isEmpty()));
        awaitHeard(SettingsSnippetPages.SNIPPET_HIGHLIGHT_SITE);
        // Three keystrokes, one pause (another window's snippet body, still open from an earlier test, may add one).
        assertTrue(
                heard.stream()
                                .filter(h -> h.site().equals(SettingsSnippetPages.SNIPPET_HIGHLIGHT_SITE))
                                .count()
                        < 3,
                "one tokenizing for the pause, not one per keystroke: " + heard);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void aDiffViewerOnAColdGrammarLoadsItOffTheFxThread() throws Exception {
        String left = "Write-Host 'one'\n";
        String right = "Write-Host 'two'\n";
        DiffModel model = DiffEngine.compute(List.of(left.strip()), List.of(right.strip()));
        // Laid out off FX, as DiffService does where it makes a model (DiffLayout.SITE is heard here, not on FX).
        com.editora.diff.DiffLayout.warm(model, n -> "…");
        listen();
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "t", null, null, "a.ps1", "b.ps1", left, right, model, "Monospaced", 13, true, null));
        CodeArea rightArea = FxTestSupport.field(pane, "rightArea");
        SettingsRig.awaitFx(
                "highlighted",
                () -> rightArea.getLength() > 0
                        && rightArea.getStyleSpans(0, rightArea.getLength()).stream()
                                .anyMatch(span -> span.getStyle().stream().anyMatch(s -> !s.startsWith("diff"))));
        FxTestSupport.drainFx();
        assertNothingOnFx(); // GrammarRegistry.LOAD_SITE, when the grammar was cold, was heard off FX
    }

    @Test
    void aSettingsChangeMarksItsRowWithoutJsonAndThePreviewIsTokenizedOffTheFxThread(@TempDir Path dir)
            throws Exception {
        // The window's own file work off the FX thread, as in the app (the plain fixture runs it in place).
        FxWindowFixture fx = FxWindowFixture.createWithFileWorkOffThread(dir, shared -> {});
        try {
            listen();
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            awaitHeard(SettingsWindow.PREVIEW_HIGHLIGHT_SITE);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.CheckBox check = SettingsRig.control(window, "minimapCheck");
                check.fire();
                assertTrue(
                        SettingsModified.modifiedKeys(fx.controller.config.getSettings())
                                .contains("showMinimap"),
                        "the change is modified at once");
                check.fire();
            });
            FxTestSupport.drainFx();
            assertFalse(heard.stream().anyMatch(h -> h.site().equals(GrammarRegistry.LOAD_SITE) && h.fx()));
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller.settingsWindow, "stage");
                if (stage != null) {
                    stage.close();
                }
            });
            fx.dispose();
        }
    }

    /** Which keys are modified, compared through the accessors, answers as the JSON comparison did. */
    @Test
    void modifiedKeysAnswerAsTheJsonComparisonDid() {
        Settings settings = new Settings();
        assertEquals(jsonModified(settings), SettingsModified.modifiedKeys(settings), "a fresh one");
        settings.setTabSize(settings.getTabSize() + 2);
        settings.setShowMinimap(!settings.isShowMinimap());
        settings.setKeymap("cua".equals(settings.getKeymap()) ? "emacs" : "cua");
        settings.setEditorTheme("some-theme");
        settings.setEditorThemeUserSet(true);
        settings.setRulers(List.of(72, 100));
        assertEquals(jsonModified(settings), SettingsModified.modifiedKeys(settings));
        assertTrue(SettingsModified.modifiedKeys(settings).containsAll(Set.of("tabSize", "showMinimap", "rulers")));

        Set<String> keys = Set.of("tabSize", "rulers", "editorTheme");
        var before = SettingsModified.values(settings, keys);
        SettingsModified.reset(settings, keys);
        assertFalse(SettingsModified.modifiedKeys(settings).stream().anyMatch(keys::contains), "reset");
        assertEquals(jsonModified(settings), SettingsModified.modifiedKeys(settings));
        SettingsModified.restore(settings, before);
        assertTrue(SettingsModified.modifiedKeys(settings).containsAll(keys), "restored");
        assertEquals(List.of(72, 100), settings.getRulers());

        Settings copy = com.editora.config.Snapshots.copy(settings);
        assertEquals(Set.of(), SettingsModified.changedKeys(settings, copy));
        copy.setTabSize(copy.getTabSize() + 1);
        assertEquals(Set.of("tabSize"), SettingsModified.changedKeys(settings, copy));
    }

    /** The comparison {@code SettingsModified} made before: every property as JSON, against the defaults. */
    private static Set<String> jsonModified(Settings settings) {
        ObjectMapper json = new ObjectMapper();
        JsonNode now = json.valueToTree(settings);
        JsonNode defaults = json.valueToTree(new Settings());
        Set<String> out = new LinkedHashSet<>();
        for (Iterator<String> it = now.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (SettingsModified.marked(key) && !SettingsFileCheck.sameValue(now.get(key), defaults.path(key))) {
                out.add(key);
            }
        }
        if (!settings.isEditorThemeUserSet()) {
            out.remove("editorTheme");
        }
        out.remove("editorThemeUserSet");
        return out;
    }
}
