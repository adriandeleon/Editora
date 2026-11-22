package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.build.BuildTool;
import com.editora.process.OutputPump;
import com.editora.test.GoTestJsonParser;
import com.editora.test.TapParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0): a test run's output is parsed where it is read, not on the FX thread. The pump prepares each
 * line on its reader thread ({@link OutputPump.Sink#prepare}) and the FX thread runs what the line came to, in
 * order; a run's line stage ({@link TestLineStage}) parses {@code go test -json}, sniffs npm's TAP and finds the
 * console text there.
 */
@Tag("fx")
class TestOutputOffFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void thePumpPreparesEachLineOnItsReaderThreadAndRunsWhatItCameToOnFxInOrder() throws Exception {
        List<String> preparedOn = new CopyOnWriteArrayList<>();
        List<String> applied = new CopyOnWriteArrayList<>();
        OutputPump pump = new OutputPump("prep-test", OutputPump.Overflow.BLOCK, false);
        int gen = pump.begin();
        CountDownLatch done = new CountDownLatch(1);
        OutputPump.Feed feed = pump.start(
                new ByteArrayInputStream("a\nb\nc\n".getBytes(StandardCharsets.UTF_8)),
                false,
                gen,
                new OutputPump.Sink() {
                    @Override
                    public void line(String text, boolean stderr) {
                        throw new AssertionError("prepared lines are not handed over raw");
                    }

                    @Override
                    public Runnable prepare(String text, boolean stderr) {
                        preparedOn.add(
                                Platform.isFxApplicationThread()
                                        ? "fx"
                                        : Thread.currentThread().getName());
                        String upper = text.toUpperCase(java.util.Locale.ROOT);
                        return () -> {
                            assertTrue(Platform.isFxApplicationThread());
                            applied.add(upper);
                        };
                    }
                });
        pump.finish(feed);
        pump.post(gen, done::countDown);
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(List.of("A", "B", "C"), applied);
        assertEquals(List.of("prep-test-stdout", "prep-test-stdout", "prep-test-stdout"), preparedOn);
    }

    @Test
    void aGoRunsJsonIsParsedByTheStageAndTheConsoleTextComesWithIt() {
        TestLineStage stage = new TestLineStage(BuildTool.GO, new GoTestJsonParser(), false, false, 200);
        TestLineStage.Line output = stage.take(
                "{\"Action\":\"output\",\"Package\":\"ex/p\",\"Test\":\"TestA\",\"Output\":\"=== RUN   TestA\\n\"}",
                false);
        assertEquals("=== RUN   TestA", output.shown());
        TestLineStage.Line pass =
                stage.take("{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Test\":\"TestA\",\"Elapsed\":0.02}", false);
        assertNull(pass.shown(), "a bookkeeping event shows nothing");
        assertFalse(pass.suites().isEmpty(), "the pass is a result for the tree");
    }

    @Test
    void npmsOutputIsSniffedForTapByTheStageAndTheParserPickedThere() {
        TestLineStage stage = new TestLineStage(BuildTool.NPM, new TapParser(), false, true, 200);
        TestLineStage.Line first = stage.take("TAP version 13", false);
        TestLineStage.Line second = stage.take("ok 1 - adds", false);
        assertTrue(first.tapDecided() || second.tapDecided(), "TAP recognized on the reading thread");
        assertTrue(
                second.suites().stream().anyMatch(s -> !s.tests().isEmpty())
                        || first.suites().stream().anyMatch(s -> !s.tests().isEmpty()),
                "the buffered lines parsed once TAP was recognized");
    }

    @Test
    void aGradleRunsTaskLinesAreFoundByTheStageAndNoResultsAreParsedFromItsConsole() {
        TestLineStage stage = new TestLineStage(BuildTool.GRADLE, null, true, false, 200);
        TestLineStage.Line line = stage.take("> Task :test UP-TO-DATE", false);
        assertTrue(line.gradleTaskLine());
        assertTrue(line.suites().isEmpty());
        assertEquals("> Task :test UP-TO-DATE", line.shown());
    }
}
