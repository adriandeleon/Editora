package com.editora.ui;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-pull-request performance gate: budgets for the hot paths that the November 2026 review found had
 * regressed unnoticed (rendered frames per keystroke +88 %, heap per tab ×2). {@code scripts/perf-ab.sh} measures
 * time, which is too noisy for a shared CI runner; everything asserted here is a <em>count</em> — frames rendered,
 * bytes the FX thread allocated, live heap and scene nodes per tab, buffers still reachable, timers still
 * registered — so it holds on any machine and fails the pull request that adds the work rather than the release
 * that ships it. Each value is printed as a {@code PERF-BUDGET} line; {@code -Dperf.budget.jfr=<directory>} also
 * records the typing window with JFR to find what a change added.
 *
 * <p>The suite runs many test classes in one JVM, and a window one of them leaves open keeps laying out and
 * rendering on the same FX thread. Each test here first hides every window that is not its own
 * ({@link #hideOtherWindows}), so the gate measures this window's work and not what an earlier class left behind.
 *
 * <p>Each budget is the value measured on master when it was set, plus headroom for run-to-run variation (noted
 * beside it). A change that needs a higher budget raises it in the same pull request and says why there, with the
 * {@code perf-ab.sh} table for the affected mode ({@code docs/performance.md}, "Per-change check"). Lowering a budget
 * after an improvement is welcome: it locks the gain in.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfBudgetFxTest {

    // ---------------------------------------------------------------- budgets (see the class comment)

    // Measured on master at 2026-11-24 (dcbd393f4), Linux, headless, two runs each: the ranges in the comments.

    /** Rendered frames per paced keystroke in a 2,000-line Java file (measured 1.020–1.025). */
    static final double TYPING_FRAMES_PER_KEY = 1.25;
    /**
     * Kilobytes the FX thread allocates per paced keystroke, warm JIT (measured 2,362–2,366; 6,760–7,230 before the
     * Flowless fork 0.7.4-editora.1 stopped re-splicing the size tree of every dirty-but-unchanged cell on each
     * layout pass). About 1.8 MB of what is left is spent inside the key event itself, rebuilding the edited line's
     * cell and applying CSS to its nodes among it.
     */
    static final double TYPING_FX_ALLOC_KB_PER_KEY = 2_700;
    /** Frames a focused, untouched editor renders in 10 s: the caret blink, and nothing else (measured 20–21). */
    static final double IDLE_FRAMES_PER_10S = 24;
    /** Live heap per open tab of a 20–60 KB Java source, in MB (measured 3.26–3.50). */
    static final double TABS_HEAP_MB_PER_TAB = 4.0;
    /** Scene-graph nodes per open tab (measured 319.75). */
    static final double TABS_SCENE_NODES_PER_TAB = 355;

    /** The idle window: 600 pulses, ten seconds at 60 a second. */
    private static final int IDLE_PULSES = 600;

    private static final Path SRC = Path.of("src/main/java/com/editora");

    private FxWindowFixture fx;
    private Path work;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        work = Files.createTempDirectory("editora-perf-budget");
        // Mermaid's tool probe and the Markdown linter answer on their own schedule; neither is a hot path here.
        fx = FxWindowFixture.create(Files.createTempDirectory("editora-fx-test"), shared -> {
            shared.getSettings().setMermaidSupport(false);
            shared.getSettings().setMarkdownLint(false);
        });
    }

    @BeforeEach
    void measureOnlyThisWindow() throws Exception {
        report("other_windows_hidden", hideOtherWindows(), Double.NaN);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // ---------------------------------------------------------------- typing

    @Test
    void typingStaysWithinItsFrameAndAllocationBudget() throws Exception {
        EditorBuffer b = openJava(2000);
        FxTestSupport.runOnFx(() -> {
            CodeArea a = b.getFocusedArea();
            a.requestFocus();
            int par = a.getParagraphs().size() / 2;
            a.moveTo(par, a.getParagraphLength(par));
            a.requestFollowCaret();
        });
        settle(1000);
        // Warm-up: the JIT, the first highlight of the edited region, the first completion probe.
        for (int i = 0; i < 300; i++) {
            char ch = (char) ('a' + (i % 26));
            FxTestSupport.runOnFx(() -> fireRaw(b.getFocusedArea(), ch));
        }
        settle(3000);

        int keys = 200;
        AtomicInteger pulses = pulseCounter();
        AtomicInteger frames = frameCounter();
        long alloc0 = fxAllocated();
        jdk.jfr.Recording jfr = startJfr();
        for (int i = 0; i < keys; i++) {
            char ch = (i % 9 == 8) ? ' ' : (char) ('a' + (i % 26));
            FxTestSupport.runOnFx(() -> fireRaw(b.getFocusedArea(), ch));
            // Four pulses, ~60 ms: ~16 keys/s, a fast typist. Pacing by pulses, not by the clock, keeps each key
            // laid out and rendered before the next one on a runner of any speed.
            OnFx.pulses(4);
        }
        OnFx.pulses(90); // ~1.5 s: the debounced work of the last keys lands inside the window
        double framesPerKey = frames.get() / (double) keys;
        double pulsesPerKey = pulses.get() / (double) keys;
        double allocKbPerKey = (fxAllocated() - alloc0) / 1024.0 / keys;
        stopJfr(jfr, "typing");
        FxTestSupport.runOnFx(b::markClean); // so closing it later asks nothing
        report("typing.frames_per_key", framesPerKey, TYPING_FRAMES_PER_KEY);
        // Pulses are reported, not budgeted: the caret blink keeps a focused editor pulsing at the display rate.
        report("typing.pulses_per_key", pulsesPerKey, Double.NaN);
        report("typing.fx_alloc_kb_per_key", allocKbPerKey, TYPING_FX_ALLOC_KB_PER_KEY);
        assertWithin("rendered frames per keystroke", framesPerKey, TYPING_FRAMES_PER_KEY);
        assertWithin("KB the FX thread allocates per keystroke", allocKbPerKey, TYPING_FX_ALLOC_KB_PER_KEY);
    }

    // ---------------------------------------------------------------- idle

    @Test
    void anIdleEditorDoesAlmostNothing() throws Exception {
        EditorBuffer b = openJava(2000);
        FxTestSupport.runOnFx(() -> b.getFocusedArea().requestFocus());
        settle(5000);
        AtomicInteger pulses = pulseCounter();
        AtomicInteger frames = frameCounter();
        long alloc0 = fxAllocated();
        long t0 = System.nanoTime();
        OnFx.pulses(IDLE_PULSES);
        // Per 10 s of the clock, not of pulses: the caret blinks on the clock, and a slow runner's pulses run late.
        double per10s = 10e9 / (System.nanoTime() - t0);
        double framesPer10s = frames.get() * per10s;
        report("idle.fx_alloc_mb_per_10s", (fxAllocated() - alloc0) / 1048576.0 * per10s, Double.NaN);
        report("idle.frames_per_10s", framesPer10s, IDLE_FRAMES_PER_10S);
        // Pulses are reported, not budgeted: RichTextFX's caret blink keeps a focused CodeArea pulsing at the
        // display rate (a bare one does ~62 a second), so extra work would not show as extra pulses. Frames do.
        report("idle.pulses_per_10s", pulses.get() * per10s, Double.NaN);
        assertWithin("frames an idle editor renders in 10 s", framesPer10s, IDLE_FRAMES_PER_10S);
    }

    // ---------------------------------------------------------------- tabs

    @Test
    void tabsKeepToTheirMemoryBudgetAndLeaveNothingBehindWhenClosed() throws Exception {
        // Open and close one file first, so what every Java tab shares (the grammar, caches, pools) is in the
        // baseline whichever test ran before this one.
        openJava(200);
        runCommand("buffer.closeAll");
        settle(2000);
        int receivers0 = pulseReceiverCount();
        double heap0 = liveMb();
        int nodes0 =
                FxTestSupport.callOnFx(() -> countNodes(tabPane().getScene().getRoot()));
        List<Path> files = new ArrayList<>();
        Path dir = Files.createTempDirectory(work, "tabs");
        for (Path p : mediumSources(8)) {
            Path dst = dir.resolve(p.getFileName());
            Files.copy(p, dst);
            files.add(dst);
        }
        List<WeakReference<EditorBuffer>> refs = new ArrayList<>();
        for (Path f : files) {
            openPath(f);
            waitLoaded();
            refs.add(new WeakReference<>(active()));
        }
        settle(3000);
        int tabs = files.size();
        double heapPerTab = (liveMb() - heap0) / tabs;
        double nodesPerTab =
                (FxTestSupport.callOnFx(() -> countNodes(tabPane().getScene().getRoot())) - nodes0) / (double) tabs;
        report("tabs.heap_mb_per_tab", heapPerTab, TABS_HEAP_MB_PER_TAB);
        report("tabs.scene_nodes_per_tab", nodesPerTab, TABS_SCENE_NODES_PER_TAB);
        runCommand("buffer.closeAll");
        settle(3000);
        liveMb();
        long alive = refs.stream().filter(r -> r.get() != null).count();
        int receivers = pulseReceiverCount();
        report("tabs.buffers_reachable_after_close", alive, 0);
        report("tabs.pulse_receivers_added", receivers - receivers0, 0);
        assertEquals(
                0,
                alive,
                "a closed tab's EditorBuffer is still reachable: something (a listener, a static cache, a timer)"
                        + " holds it, and every tab opened leaks its document and scene graph");
        assertWithin("MB of live heap per open tab", heapPerTab, TABS_HEAP_MB_PER_TAB);
        assertWithin("scene nodes per open tab", nodesPerTab, TABS_SCENE_NODES_PER_TAB);
        if (receivers0 >= 0) {
            assertTrue(
                    receivers <= receivers0,
                    "closing every tab left " + (receivers - receivers0) + " more AnimationTimer/pulse receivers"
                            + " registered than before they opened: each one runs on every pulse for good");
        }
    }

    // ---------------------------------------------------------------- isolation

    /**
     * A window another test class left open is not charged to this one. In CI's Linux shard, three main windows left
     * by earlier classes, one laying out a Settings page on every pulse, made the FX thread allocate 10–72 MB per
     * keystroke instead of 7 and failed the typing budget on every pull request whose shard put it after them.
     */
    @Test
    void aWindowAnotherTestLeftOpenIsNotCharged() throws Exception {
        Stage[] other = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            other[0] = new Stage();
            other[0].setScene(new Scene(new StackPane(new ChurningRegion()), 200, 100));
            other[0].show();
        });
        try {
            OnFx.pulses(5);
            long a0 = fxAllocated();
            OnFx.pulses(30);
            double before = (fxAllocated() - a0) / 1048576.0;
            assertTrue(
                    before >= 15,
                    "the left-open window allocates a megabyte per pulse, so it would be charged: " + before + " MB");

            assertTrue(hideOtherWindows() >= 1, "the left-open window is hidden");
            OnFx.pulses(5);
            a0 = fxAllocated();
            OnFx.pulses(30);
            double after = (fxAllocated() - a0) / 1048576.0;
            report("isolation.other_window_mb_per_30_pulses", after, 3);
            assertTrue(after < 3, "a hidden window still costs the FX thread " + after + " MB in 30 pulses");
        } finally {
            FxTestSupport.runOnFx(() -> other[0].hide());
        }
    }

    /** Lays itself out again on every pulse and allocates a megabyte each time: a window that never settles. */
    private static final class ChurningRegion extends Region {
        @SuppressWarnings("unused")
        private byte[] sink = new byte[0];

        @Override
        protected void layoutChildren() {
            sink = new byte[1 << 20];
            Platform.runLater(this::requestLayout);
        }
    }

    /**
     * Hides every showing window that is neither this test's window nor owned by it (its popups stay), and says how
     * many it hid. A hidden window's scene leaves the pulse, so its layout and rendering stop costing the FX thread.
     */
    private int hideOtherWindows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Window mine = tabPane().getScene().getWindow();
            List<Window> others = Window.getWindows().stream()
                    .filter(w -> w.isShowing() && !ownedBy(w, mine))
                    .toList();
            others.forEach(Window::hide);
            return others.size();
        });
    }

    private static boolean ownedBy(@org.jspecify.annotations.Nullable Window w, Window mine) {
        while (w != null) {
            if (w == mine) {
                return true;
            }
            w = w instanceof Stage s ? s.getOwner() : w instanceof PopupWindow p ? p.getOwnerWindow() : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers (as in src/perf's PerfProbe)

    /**
     * With {@code -Dperf.budget.jfr=<directory>}, records the measured window with JFR's "profile" settings (CPU and
     * allocation samples), to find what a change added: {@code jfr print --events jdk.ObjectAllocationSample}.
     */
    private static jdk.jfr.@org.jspecify.annotations.Nullable Recording startJfr() throws Exception {
        if (System.getProperty("perf.budget.jfr") == null) {
            return null;
        }
        jdk.jfr.Recording r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
        r.start();
        return r;
    }

    private static void stopJfr(jdk.jfr.@org.jspecify.annotations.Nullable Recording r, String name) throws Exception {
        if (r == null) {
            return;
        }
        r.stop();
        Path dir = Path.of(System.getProperty("perf.budget.jfr"));
        Files.createDirectories(dir);
        Path out = dir.resolve(name + ".jfr");
        r.dump(out);
        r.close();
        System.out.println("PERF-BUDGET JFR recording: " + out.toAbsolutePath());
    }

    /** Live heap after a full GC, in MB. */
    private static double liveMb() throws Exception {
        for (int i = 0; i < 5; i++) {
            System.gc();
            OnFx.pulses(10);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
    }

    private static int countNodes(javafx.scene.Node n) {
        int c = 1;
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node k : p.getChildrenUnmodifiable()) {
                c += countNodes(k);
            }
        }
        return c;
    }

    private static void report(String key, double value, double budget) {
        System.out.printf("PERF-BUDGET %s=%.3f budget=%.3f%n", key, value, budget);
    }

    private static void assertWithin(String what, double value, double budget) {
        assertTrue(
                value <= budget,
                String.format(
                        "%s: %.3f, over its budget of %.3f. Find what the change added to this path (docs/performance.md,"
                                + " \"Per-change check\"); raise the budget in PerfBudgetFxTest only with the reason and"
                                + " the perf-ab.sh table in the pull request.",
                        what, value, budget));
    }

    private EditorBuffer openJava(int lines) throws Exception {
        List<String> unit = Files.readAllLines(SRC.resolve("editor/EditorBuffer.java"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(unit.get(i % unit.size())).append('\n');
        }
        Path f = Files.createTempDirectory(work, "doc").resolve("Sample" + lines + ".java");
        Files.writeString(f, sb.toString());
        openPath(f);
        waitLoaded();
        EditorBuffer b = active();
        OnFx.untilHighlightAndFoldsSettled(b);
        settle(2000);
        return b;
    }

    private static List<Path> mediumSources(int n) throws Exception {
        try (var st = Files.walk(SRC)) {
            return st.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toFile().length() > 20_000 && p.toFile().length() < 60_000)
                    .sorted()
                    .limit(n)
                    .toList();
        }
    }

    private void openPath(Path f) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, f));
    }

    private void runCommand(String id) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "registry"), "run", new Class<?>[] {String.class}, id));
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    private TabPane tabPane() {
        return FxTestSupport.field(fx.controller, "tabPane");
    }

    private void waitLoaded() throws Exception {
        FxTestSupport.awaitFxCondition(
                () -> {
                    EditorBuffer b =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    return b != null && !b.isLoading() && b.getFocusedArea().getLength() > 0;
                },
                "the opened file to load");
    }

    /** Lets roughly {@code ms} pass in FX pulses (60 a second), so what was debounced has run. */
    private static void settle(long ms) throws Exception {
        OnFx.pulses((int) (ms * 60 / 1000));
    }

    private static void fireRaw(CodeArea area, char ch) {
        javafx.event.Event.fireEvent(
                area, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(ch), "", null, false, false, false, false));
    }

    private AtomicInteger pulseCounter() throws Exception {
        AtomicInteger n = new AtomicInteger();
        FxTestSupport.runOnFx(() -> tabPane().getScene().addPostLayoutPulseListener(n::incrementAndGet));
        return n;
    }

    /** Frames the scene actually rendered (the render pass, not just pulses), via JavaFX's PerformanceTracker. */
    private AtomicInteger frameCounter() throws Exception {
        AtomicInteger n = new AtomicInteger();
        // By reflection: the test compile sees javafx.graphics as a module that does not export com.sun.javafx.perf;
        // the tests run on the classpath, where it is reachable.
        FxTestSupport.runOnFx(() -> {
            try {
                Class<?> c = Class.forName("com.sun.javafx.perf.PerformanceTracker");
                Object tracker = c.getMethod("getSceneTracker", javafx.scene.Scene.class)
                        .invoke(null, tabPane().getScene());
                Runnable count = n::incrementAndGet;
                c.getMethod("setOnRenderedFrameTask", Runnable.class).invoke(tracker, count);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot count rendered frames on this JavaFX", e);
            }
        });
        return n;
    }

    private static long fxAllocated() throws Exception {
        long id = FxTestSupport.callOnFx(() -> Thread.currentThread().threadId());
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(id);
    }

    /** AnimationTimers and other pulse receivers registered with the toolkit, or -1 where this JavaFX hides them. */
    private static int pulseReceiverCount() {
        try {
            Object tk = Class.forName("com.sun.javafx.tk.Toolkit")
                    .getMethod("getToolkit")
                    .invoke(null);
            Object primary = tk.getClass().getMethod("getPrimaryTimer").invoke(tk);
            Class<?> c = primary.getClass();
            while (c != null && !c.getName().endsWith("AbstractPrimaryTimer")) {
                c = c.getSuperclass();
            }
            if (c == null) {
                return -1;
            }
            java.lang.reflect.Field n = c.getDeclaredField("receiversLength");
            n.setAccessible(true);
            return (int) n.get(primary);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
