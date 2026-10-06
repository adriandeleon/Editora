package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import javafx.application.Platform;

import com.editora.build.OutputStyle;
import com.editora.process.OutputBatch;
import com.editora.process.OutputPump;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drain of the output pump reaches a console as <em>one</em> rich-text edit, and drains are paced.
 *
 * <p>Each line used to be its own append, restyle and (at the cap) trim, 256 to a drain, with the next drain
 * queued the moment one ended: a program printing steadily kept the FX thread to itself. What a console ends
 * up showing must not depend on how its lines were grouped, so every batched result here is compared with
 * the same lines appended one at a time.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleBatchFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What a console shows: its text and the style classes of every character (line ends carry none). */
    private record Shown(String text, List<Collection<String>> styles) {
        static Shown of(CodeArea area) {
            String text = area.getText();
            List<Collection<String>> styles = new ArrayList<>(text.length());
            for (int i = 0; i < text.length(); i++) {
                styles.add(text.charAt(i) == '\n' ? List.of() : List.copyOf(area.getStyleOfChar(i)));
            }
            return new Shown(text, styles);
        }
    }

    private static int[] countEdits(CodeArea area) {
        int[] edits = {0};
        area.plainTextChanges().subscribe(change -> edits[0]++);
        return edits;
    }

    // --- one edit per drain, through the real pump ----------------------------------------------

    @Test
    void aDrainOfThePumpIsOneConsoleEditAndDrainsArePaced() throws Exception {
        int lines = 1_000; // four drains of at most 256 lines
        StringBuilder stream = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            String line = i % 100 == 0 ? "see https://example.com/" + i : "line " + i;
            stream.append(line).append('\n');
            expected.append(line).append('\n');
        }

        RunPanel panel = FxTestSupport.callOnFx(() -> new RunPanel(() -> {}));
        CodeArea output = FxTestSupport.field(panel, "output");
        int[] edits = FxTestSupport.callOnFx(() -> countEdits(output));
        List<Long> drainStarts = new ArrayList<>();
        boolean[] inDrain = {false};
        Runnable drainEnded = () -> inDrain[0] = false;
        String[] atExit = {null};
        int[] editsAtExit = {0};

        OutputPump pump = new OutputPump("test", OutputPump.Overflow.BLOCK, false);
        int gen = pump.begin();
        CountDownLatch releaseFx = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        // Hold the UI until everything is queued, so the drains are full ones whatever the machine's speed.
        Platform.runLater(() -> awaitQuietly(releaseFx));
        try {
            OutputPump.Feed feed = pump.start(
                    new ByteArrayInputStream(stream.toString().getBytes(StandardCharsets.UTF_8)),
                    false,
                    gen,
                    (text, stderr) -> {
                        if (!inDrain[0]) {
                            inDrain[0] = true;
                            drainStarts.add(System.nanoTime());
                        }
                        assertTrue(OutputBatch.defer(drainEnded), "lines are delivered inside a batch");
                        panel.appendOutput(text, stderr);
                    });
            pump.finish(feed); // the reader has queued every line
            pump.post(gen, () -> {
                atExit[0] = output.getText();
                editsAtExit[0] = edits[0];
                exited.countDown();
            });
        } finally {
            releaseFx.countDown();
        }
        assertTrue(exited.await(30, TimeUnit.SECONDS));

        assertEquals(expected.toString(), atExit[0], "the exit sees every line already in the console");
        assertEquals(4, drainStarts.size(), "1,000 lines at 256 a drain");
        assertEquals(4, editsAtExit[0], "one append per drain, not one per line");
        long least = TimeUnit.MILLISECONDS.toNanos(OutputPump.MIN_DRAIN_INTERVAL_MILLIS) / 2;
        for (int i = 1; i < drainStarts.size(); i++) {
            long gap = drainStarts.get(i) - drainStarts.get(i - 1);
            assertTrue(gap >= least, "drains are a frame apart, not back to back: " + gap + " ns");
        }
        Shown batched = FxTestSupport.callOnFx(() -> Shown.of(output));
        Shown perLine = FxTestSupport.callOnFx(() -> {
            RunPanel reference = new RunPanel(() -> {});
            expected.toString().lines().forEach(line -> reference.appendOutput(line, false));
            return Shown.of(FxTestSupport.field(reference, "output"));
        });
        assertEquals(perLine, batched);
    }

    /** Past the cap a drain costs an append and one trim — and the console still holds the newest whole lines. */
    @Test
    void pastTheCapADrainIsOneAppendAndOneTrim() throws Exception {
        int lines = 3_000; // 300,000 characters into a 200,000-character console
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            stream.append(String.format("%05d ", i)).append("x".repeat(93)).append('\n');
        }
        BuildToolPanel panel = FxTestSupport.callOnFx(BuildToolPanel::new);
        CodeArea output = FxTestSupport.field(panel, "output");
        int[] edits = FxTestSupport.callOnFx(() -> countEdits(output));
        int[] drains = {0};
        Runnable drainEnded = () -> drains[0]++;

        OutputPump pump = new OutputPump("test", OutputPump.Overflow.BLOCK, false);
        int gen = pump.begin();
        CountDownLatch exited = new CountDownLatch(1);
        OutputPump.Feed feed = pump.start(
                new ByteArrayInputStream(stream.toString().getBytes(StandardCharsets.UTF_8)),
                false,
                gen,
                (text, stderr) -> {
                    OutputBatch.defer(drainEnded);
                    panel.appendOutput(text, stderr);
                });
        pump.finish(feed);
        pump.post(gen, exited::countDown);
        assertTrue(exited.await(60, TimeUnit.SECONDS));

        FxTestSupport.runOnFx(() -> {
            assertTrue(drains[0] < lines / 10, "lines arrive in batches: " + drains[0] + " drains");
            assertTrue(edits[0] <= 2 * drains[0], edits[0] + " edits in " + drains[0] + " drains");
            String shown = output.getText();
            assertTrue(shown.length() <= 200_000, "trimmed to the cap");
            assertTrue(shown.length() > 190_000, "and no further than a line past it");
            assertTrue(stream.toString().endsWith(shown), "the newest output, nothing lost from its end");
            assertTrue(shown.matches("(?s)\\d{5} x.*"), "cut at a line end");
            assertEquals(shown.length(), output.getCaretPosition(), "still following the tail");
            assertFalse(output.getUndoManager().isUndoAvailable());
        });
    }

    // --- the same console, however the lines were grouped ----------------------------------------

    /** One output event: a whole line, or text flushed before its newline. */
    private record Out(String text, boolean stderr, boolean partial) {}

    private static List<Out> mixedOutput() {
        return List.of(
                new Out("plain stdout", false, false),
                new Out("Exception in thread \"main\" java.lang.IllegalStateException", true, false),
                new Out("\tat demo.Main.main(Main.java:12)", true, false),
                new Out("", false, false),
                new Out("2026-10-05 12:00:00 ERROR failed, see https://example.com/log?id=7 for more", false, false),
                new Out("2026-10-05 12:00:01 WARN  two links http://a.example/x and https://b.example/y", false, false),
                new Out("y".repeat(70_000), false, false),
                new Out("", true, false),
                new Out("Name: ", false, true),
                new Out("Ada", false, false),
                new Out("stderr prompt> ", true, true),
                new Out("https://example.com/only-a-link", true, false),
                new Out("mañana — ünïcödé 日本語", false, false),
                new Out("trailing prompt: ", false, true));
    }

    private static <P> void assertSameBatchedAsPerLine(
            java.util.function.Supplier<P> newPanel, String areaField, BiConsumer<P, Out> append) throws Exception {
        FxTestSupport.runOnFx(() -> {
            P perLine = newPanel.get();
            mixedOutput().forEach(out -> append.accept(perLine, out));

            P batched = newPanel.get();
            CodeArea batchedArea = FxTestSupport.field(batched, areaField);
            int[] edits = countEdits(batchedArea);
            OutputBatch.begin();
            mixedOutput().forEach(out -> append.accept(batched, out));
            assertEquals(0, edits[0], "nothing is applied while the drain is still delivering");
            OutputBatch.end();

            assertEquals(1, edits[0], "the whole drain is one edit");
            CodeArea perLineArea = FxTestSupport.field(perLine, areaField);
            assertEquals(Shown.of(perLineArea), Shown.of(batchedArea));
            assertEquals(perLineArea.getCaretPosition(), batchedArea.getCaretPosition());
        });
    }

    @Test
    void theRunConsoleShowsTheSameBatchedAsLineByLine() throws Exception {
        assertSameBatchedAsPerLine(() -> new RunPanel(() -> {}), "output", (panel, out) -> {
            if (out.partial()) {
                panel.appendPartialOutput(out.text(), out.stderr());
            } else {
                panel.appendOutput(out.text(), out.stderr());
            }
        });
        // The comparison is of something: stderr is tinted and links are marked in the batched console.
        FxTestSupport.runOnFx(() -> {
            RunPanel panel = new RunPanel(() -> {});
            CodeArea output = FxTestSupport.field(panel, "output");
            OutputBatch.begin();
            panel.appendOutput("out", false);
            panel.appendOutput("err https://example.com/x", true);
            OutputBatch.end();
            assertEquals("out\nerr https://example.com/x\n", output.getText());
            assertEquals(List.of(), List.copyOf(output.getStyleOfChar(0)));
            assertEquals(List.of("run-stderr"), List.copyOf(output.getStyleOfChar(4)));
            assertEquals(List.of("run-stderr", "console-url"), List.copyOf(output.getStyleOfChar(8)));
        });
    }

    @Test
    void theBuildConsoleShowsTheSameBatchedAsLineByLine() throws Exception {
        assertSameBatchedAsPerLine(
                () -> {
                    BuildToolPanel panel = new BuildToolPanel();
                    panel.started("mvn verify", OutputStyle.console(), () -> {});
                    return panel;
                },
                "output",
                (panel, out) -> {
                    if (out.partial()) {
                        panel.appendStyled(out.text(), "git-command"); // the command log's caller-styled lines
                    } else {
                        panel.appendOutput(out.text(), out.stderr());
                    }
                });
    }

    /** The Java debuggee's console: the Run pump's lines, appended to the Debug panel. */
    @Test
    void theDebugConsoleShowsTheSameBatchedAsLineByLine() throws Exception {
        assertSameBatchedAsPerLine(
                () -> new DebugPanel(noopActions()),
                "console",
                (panel, out) -> panel.appendOutput(
                        "\u001b[31m" + out.text() + "\u001b[0m" + (out.partial() ? "" : "\n"),
                        out.stderr() ? "stderr" : "stdout"));
    }

    /** A notice decides its leading line break from the console's last character — the pending one included. */
    @Test
    void aDebugNoticeInsideADrainStillStartsOnItsOwnLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noopActions());
            CodeArea console = FxTestSupport.field(panel, "console");
            OutputBatch.begin();
            panel.appendOutput("Name: ", "stdout");
            panel.appendNotice("[input ended]");
            OutputBatch.end();
            assertEquals("Name: \n[input ended]\n", console.getText());
        });
    }

    // --- follow, clear --------------------------------------------------------------------------

    @Test
    void aBatchLeavesAScrolledBackConsoleWhereItWasAndFollowsOneAtTheTail() throws Exception {
        FxTestSupport.runOnFx(() -> {
            RunPanel panel = new RunPanel(() -> {});
            CodeArea output = FxTestSupport.field(panel, "output");
            panel.appendOutput("first", false);
            panel.appendOutput("second", false);

            output.moveTo(2); // the user went back to read
            batch(() -> {
                panel.appendOutput("third", false);
                panel.appendOutput("fourth", true);
            });
            assertEquals(2, output.getCaretPosition(), "not yanked to the tail");

            output.moveTo(output.getLength()); // back at the end: following again
            batch(() -> panel.appendOutput("fifth", false));
            assertEquals(output.getLength(), output.getCaretPosition());
            assertEquals("first\nsecond\nthird\nfourth\nfifth\n", output.getText());
        });
    }

    /** Lines still waiting when the console is cleared came before the clear: they must not follow it. */
    @Test
    void clearingInsideADrainDropsTheLinesNotYetShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            RunPanel run = new RunPanel(() -> {});
            BuildToolPanel build = new BuildToolPanel();
            DebugPanel debug = new DebugPanel(noopActions());
            batch(() -> {
                run.appendOutput("old run", false);
                run.started("java Next.java");
                run.appendOutput("new run", false);
                build.appendOutput("old build", false);
                build.started("mvn test", OutputStyle.console(), () -> {});
                debug.appendOutput("old session\n", "stdout");
                debug.clearConsole();
            });
            assertEquals(
                    "new run\n", FxTestSupport.<CodeArea>field(run, "output").getText());
            assertEquals("", FxTestSupport.<CodeArea>field(build, "output").getText());
            assertEquals("", FxTestSupport.<CodeArea>field(debug, "console").getText());
        });
    }

    private static void batch(Runnable appends) {
        OutputBatch.begin();
        try {
            appends.run();
        } finally {
            OutputBatch.end();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DebugPanel.Actions noopActions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
