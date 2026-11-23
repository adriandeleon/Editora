package com.editora.ui;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

/**
 * Headless performance probe: boots a real Editora window on the headless glass platform and measures one
 * scenario (typing, scrolling, idle, highlighting, opening a large file, memory per tab, Find, save).
 *
 * <p>Not a test and not built by Maven: {@code scripts/perf-ab.sh} compiles this one source against each build's
 * {@code target/classes} and {@code target/test-classes} (it uses the test fixtures {@link FxWindowFixture} and
 * {@link FxTestSupport}, hence the package), so the same measurement code runs against the release being cut and
 * the previous release tag. It lives outside {@code src/test/java} because it paces input with real sleeps, which
 * the tests may not (QUAL-07). {@code PerfProbeCompilesTest} keeps it compiling against the current tree.
 *
 * <p><b>Baselines.</b> Anything that changed between releases is reached by reflection (the {@code SearchMatcher}
 * package, the window fixture's {@code create} overloads, optional editor toggles), so the current source still
 * compiles against an older build. When a baseline lacks something this file needs at compile time, the script
 * falls back to the baseline's own copy of this file, if it has one.
 *
 * <p>Output: human-readable {@code PROBE ...} lines, and one {@code METRIC <key> <value>} line per number the A/B
 * script compares. Every metric is lower-is-better. Run by hand:
 *
 * <pre>
 * java -Dglass.platform=Headless -Dprism.order=sw -Djava.awt.headless=true -Dprobe.work=DIR -Dprobe.src=SRC_ROOT \
 *     -cp PROBE_CLASSES:target/classes:target/test-classes:DEPS com.editora.ui.PerfProbe typing 2000
 * </pre>
 *
 * Modes: {@code typing LINES | scroll LINES | idle | hl LINES | open MB | memory TABS | find MB | save | bare LINES}
 * and the diagnostics {@code histo TABS | canvases TABS | console}. {@code -Dprobe.jfr=FILE} dumps a running JFR
 * recording (start one with {@code -XX:StartFlightRecording}) before the JVM halts.
 */
public final class PerfProbe {

    static FxWindowFixture fx;
    static Thread fxThread;
    /** The {@code com/editora} source directory whose files are the probe's input text. */
    static Path src;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        src = Path.of(System.getProperty("probe.src", "src/main/java/com/editora"));
        FxTestSupport.bootToolkit();
        Path cfg = Files.createTempDirectory("editora-probe-cfg");
        Files.writeString(cfg.resolve("settings.json"), System.getProperty("probe.settings", defaultSettings()));
        long t0 = System.nanoTime();
        fx = createFixture(cfg);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        out("window build: %d ms", buildMs);
        metric("window.build_ms", buildMs);
        fxThread = FxTestSupport.callOnFx(Thread::currentThread);
        FxTestSupport.runOnFx(() -> {
            javafx.stage.Window w = tabPane().getScene().getWindow();
            if (w instanceof javafx.stage.Stage s) {
                s.setWidth(1600);
                s.setHeight(1000);
            }
            out("window showing=%s size=%.0fx%.0f", w.isShowing(), w.getWidth(), w.getHeight());
        });
        settle(1500);
        try {
            switch (mode) {
                case "typing" -> typing(Integer.parseInt(args[1]));
                case "scroll" -> scroll(Integer.parseInt(args[1]));
                case "idle" -> idle();
                case "hl" -> hl(Integer.parseInt(args[1]));
                case "console" -> console();
                case "find" -> find(Integer.parseInt(args[1]));
                case "save" -> save();
                case "histo" -> histo(Integer.parseInt(args[1]));
                case "canvases" -> canvases(Integer.parseInt(args[1]));
                case "bare" -> bare(Integer.parseInt(args[1]));
                case "memory" -> memory(Integer.parseInt(args[1]));
                case "open" -> open(Double.parseDouble(args[1]));
                default -> throw new IllegalArgumentException(mode);
            }
            out("done");
        } catch (Throwable e) {
            out("FAILED %s", e);
            e.printStackTrace(System.out);
            System.out.flush();
            Runtime.getRuntime().halt(1);
        } finally {
            System.out.flush();
            String jfrOut = System.getProperty("probe.jfr");
            if (jfrOut != null) {
                for (var r : jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) {
                    try {
                        r.dump(Path.of(jfrOut));
                        out("JFR recording dumped to %s", jfrOut);
                    } catch (Exception e) {
                        e.printStackTrace(System.out);
                    }
                }
            }
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
    }

    /** Settings at the build's own current schema, so neither side runs a migration: each gets its shipped defaults. */
    static String defaultSettings() {
        try {
            int v = Class.forName("com.editora.config.Settings").getField("SCHEMA_VERSION").getInt(null);
            return "{\"schemaVersion\":" + v + "}";
        } catch (ReflectiveOperationException e) {
            return "{}";
        }
    }

    /** {@code FxWindowFixture.create(dir, zen, expert, simple, targets[, noSession], onBuilt)}, whichever exists. */
    static FxWindowFixture createFixture(Path cfg) throws Exception {
        Consumer<Object> none = c -> {};
        for (Method m : FxWindowFixture.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("create") || p.length < 6 || p[0] != Path.class) {
                continue;
            }
            m.setAccessible(true);
            if (p.length == 7 && p[5] == boolean.class) {
                return (FxWindowFixture) m.invoke(null, cfg, false, false, false, List.of(), true, none);
            }
            if (p.length == 6 && p[4] == List.class) {
                return (FxWindowFixture) m.invoke(null, cfg, false, false, false, List.of(), none);
            }
        }
        throw new IllegalStateException("no FxWindowFixture.create(Path, ...) this probe knows");
    }

    // ---------------------------------------------------------------- scenarios

    static void typing(int lines) throws Exception {
        String k = "typing-" + lines;
        EditorBuffer b = openJava(lines);
        if (System.getProperty("probe.nolinehl") != null) {
            FxTestSupport.runOnFx(() -> invokeOptional(b, "setLineHighlightOn", false));
            out("[typing] line highlighter OFF");
        }
        if (System.getProperty("probe.noguides") != null) {
            FxTestSupport.runOnFx(() -> invokeOptional(b, "setIndentGuidesVisible", false));
            out("[typing] indent guides OFF");
        }
        FxTestSupport.runOnFx(() -> {
            CodeArea a = b.getFocusedArea();
            a.requestFocus();
            int par = a.getParagraphs().size() / 2;
            a.moveTo(par, a.getParagraphLength(par));
            a.requestFollowCaret();
        });
        settle(1000);
        out("[typing %d lines] large=%s chars=%d", lines, b.isLargeFile(),
                FxTestSupport.callOnFx(() -> b.getFocusedArea().getLength()));
        // A: unpaced dispatch micro-benchmark
        for (int i = 0; i < 300; i++) {
            fire(b, 'x');
        }
        List<Double> us = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            us.add(fire(b, (char) ('a' + (i % 26))));
        }
        metric(k + ".dispatch_us", dist("[typing " + lines + "] KEY_TYPED dispatch (µs)", us));
        settle(3000);
        // B: paced typing at ~16 keys/s with the FX thread sampled. Frames are counted from the scene's
        // PerformanceTracker: a regression can hide in the render pass, which FX busy time does not see.
        int keys = 300;
        AtomicInteger pulses = pulseCounter();
        AtomicInteger frames = frameCounter();
        Sampler s = new Sampler();
        long cpu0 = cpu();
        long alloc0 = fxAllocated();
        s.start();
        for (int i = 0; i < keys; i++) {
            char ch = (i % 9 == 8) ? ' ' : (char) ('a' + (i % 26));
            Platform.runLater(() -> fireRaw(b.getFocusedArea(), ch));
            Thread.sleep(60);
        }
        Thread.sleep(1500); // let debounced work land inside the window
        s.stop();
        double cpuMs = (cpu() - cpu0) / 1e6 / keys;
        double allocKb = (fxAllocated() - alloc0) / 1024.0 / keys;
        out("[typing %d] paced: %d keys, FX busy %.2f ms/key, process CPU %.2f ms/key, FX alloc %.0f KB/key, pulses=%d",
                lines, keys, s.busyMs() / keys, cpuMs, allocKb, pulses.get());
        out("[typing %d] rendered frames=%d (%.2f/key)", lines, frames.get(), frames.get() / (double) keys);
        metric(k + ".fx_ms_per_key", s.busyMs() / keys);
        metric(k + ".cpu_ms_per_key", cpuMs);
        metric(k + ".fx_alloc_kb_per_key", allocKb);
        metric(k + ".frames_per_key", frames.get() / (double) keys);
        metric(k + ".pulses_per_key", pulses.get() / (double) keys);
        metric(k + ".longest_stall_ms", s.longestMs());
        s.report("[typing " + lines + "]", keys);
        // C: Enter (auto-indent path)
        Sampler s2 = new Sampler();
        s2.start();
        for (int i = 0; i < 60; i++) {
            Platform.runLater(() -> b.getFocusedArea().replaceSelection("\n"));
            Thread.sleep(80);
        }
        Thread.sleep(1500);
        s2.stop();
        out("[typing %d] newline insert: FX busy %.2f ms/op", lines, s2.busyMs() / 60);
        metric(k + ".newline_fx_ms", s2.busyMs() / 60);
        s2.report("[newline " + lines + "]", 60);
    }

    static void scroll(int lines) throws Exception {
        String k = "scroll-" + lines;
        EditorBuffer b = openJava(lines);
        FxTestSupport.runOnFx(() -> b.getFocusedArea().requestFocus());
        settle(1000);
        AtomicInteger pulses = pulseCounter();
        AtomicInteger frames = frameCounter();
        for (int i = 0; i < 200; i++) { // warm-up
            Platform.runLater(() -> b.getFocusedArea().scrollYBy(90));
            Thread.sleep(16);
        }
        settle(1500);
        pulses.set(0);
        frames.set(0);
        int steps = 600;
        Sampler s = new Sampler();
        long cpu0 = cpu();
        long alloc0 = fxAllocated();
        s.start();
        for (int i = 0; i < steps; i++) {
            Platform.runLater(() -> b.getFocusedArea().scrollYBy(90)); // ~5 lines per frame
            Thread.sleep(16);
        }
        Thread.sleep(800);
        s.stop();
        double cpuMs = (cpu() - cpu0) / 1e6 / steps;
        double allocKb = (fxAllocated() - alloc0) / 1024.0 / steps;
        out("[scroll %d] smooth: %d steps, FX busy %.2f ms/step, CPU %.2f ms/step, FX alloc %.0f KB/step, pulses=%d, frames=%d",
                lines, steps, s.busyMs() / steps, cpuMs, allocKb, pulses.get(), frames.get());
        metric(k + ".fx_ms_per_step", s.busyMs() / steps);
        metric(k + ".cpu_ms_per_step", cpuMs);
        metric(k + ".fx_alloc_kb_per_step", allocKb);
        s.report("[scroll " + lines + "]", steps);
        int jumps = 40;
        java.util.Random rnd = new java.util.Random(7);
        int pars = FxTestSupport.callOnFx(() -> b.getFocusedArea().getParagraphs().size());
        Sampler s2 = new Sampler();
        s2.start();
        for (int i = 0; i < jumps; i++) {
            int p = rnd.nextInt(pars);
            Platform.runLater(() -> b.getFocusedArea().showParagraphAtTop(p));
            Thread.sleep(250);
        }
        s2.stop();
        out("[scroll %d] far jumps: FX busy %.2f ms/jump", lines, s2.busyMs() / jumps);
        metric(k + ".jump_fx_ms", s2.busyMs() / jumps);
        s2.report("[jump " + lines + "]", jumps);
    }

    /** Control: an unadorned RichTextFX CodeArea with the same text, window size and pacing (machine noise check). */
    static void bare(int lines) throws Exception {
        String text = javaText(lines);
        CodeArea a = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(text);
            javafx.stage.Stage st = new javafx.stage.Stage();
            st.setScene(new javafx.scene.Scene(
                    new javafx.scene.layout.StackPane(new org.fxmisc.flowless.VirtualizedScrollPane<>(area)), 1600, 1000));
            st.show();
            int par = area.getParagraphs().size() / 2;
            area.moveTo(par, area.getParagraphLength(par));
            area.requestFollowCaret();
            area.requestFocus();
            return area;
        });
        settle(2000);
        for (int i = 0; i < 300; i++) {
            FxTestSupport.runOnFx(() -> fireRaw(a, 'x'));
        }
        settle(1500);
        int keys = 300;
        Sampler s = new Sampler();
        s.start();
        for (int i = 0; i < keys; i++) {
            char ch = (i % 9 == 8) ? ' ' : (char) ('a' + (i % 26));
            Platform.runLater(() -> fireRaw(a, ch));
            Thread.sleep(60);
        }
        Thread.sleep(1500);
        s.stop();
        out("[bare %d] paced typing: FX busy %.2f ms/key", lines, s.busyMs() / keys);
        metric("bare-" + lines + ".control_fx_ms_per_key", s.busyMs() / keys);
        for (int i = 0; i < 200; i++) {
            Platform.runLater(() -> a.scrollYBy(90));
            Thread.sleep(16);
        }
        settle(1000);
        int steps = 600;
        Sampler s2 = new Sampler();
        s2.start();
        for (int i = 0; i < steps; i++) {
            Platform.runLater(() -> a.scrollYBy(90));
            Thread.sleep(16);
        }
        Thread.sleep(800);
        s2.stop();
        out("[bare %d] smooth scroll: FX busy %.2f ms/step", lines, s2.busyMs() / steps);
        metric("bare-" + lines + ".control_scroll_fx_ms_per_step", s2.busyMs() / steps);
    }

    /** Waits until the process stops burning CPU; returns {wallMs, cpuMs}. */
    static double[] quiet() throws Exception {
        long t0 = System.nanoTime();
        long c0 = cpu();
        long last = c0;
        int calm = 0;
        long lastBusy = t0;
        while (calm < 6 && System.nanoTime() - t0 < 180_000_000_000L) {
            Thread.sleep(100);
            long c = cpu();
            if (c - last < 12_000_000) {
                calm++;
            } else {
                calm = 0;
                lastBusy = System.nanoTime();
            }
            last = c;
        }
        return new double[] {(lastBusy - t0) / 1e6, (cpu() - c0) / 1e6};
    }

    static void hl(int lines) throws Exception {
        String k = "hl-" + lines;
        Path f = work("hl").resolve("Sample" + lines + ".java");
        Files.writeString(f, javaText(lines));
        openPath(f);
        Sampler s0 = new Sampler();
        s0.start();
        waitLoaded();
        double[] q = quiet();
        s0.stop();
        EditorBuffer b = active();
        out("[hl %d] after load: background settles after %.0f ms wall, %.0f ms CPU; FX busy %.0f ms, longest stall %.0f ms",
                lines, q[0], q[1], s0.busyMs(), s0.longestMs());
        metric(k + ".load_settle_ms", q[0]);
        metric(k + ".load_cpu_ms", q[1]);
        metric(k + ".load_fx_ms", s0.busyMs());
        for (String where : new String[] {"top", "top", "middle", "end"}) {
            Sampler s = new Sampler();
            s.start();
            FxTestSupport.runOnFx(() -> {
                CodeArea a = b.getFocusedArea();
                int pars = a.getParagraphs().size();
                int par = where.equals("top") ? 12 : where.equals("middle") ? pars / 2 : pars - 3;
                a.insertText(par, 0, "x");
            });
            q = quiet();
            s.stop();
            out("[hl %d] one edit at %s: settles after %.0f ms wall, %.0f ms CPU; FX busy %.0f ms, longest stall %.0f ms",
                    lines, where, q[0], q[1], s.busyMs(), s.longestMs());
            if (!where.equals("top")) {
                metric(k + ".edit_" + where + "_cpu_ms", q[1]);
            }
            if (where.equals("middle")) {
                s.report("[hl-edit " + lines + "]", 1);
            }
        }
        // sustained typing at the top: does each keystroke restart an O(document) pass?
        long c0 = cpu();
        Sampler s = new Sampler();
        s.start();
        for (int i = 0; i < 100; i++) {
            Platform.runLater(() -> b.getFocusedArea().insertText(12, 0, "y"));
            Thread.sleep(150);
        }
        q = quiet();
        s.stop();
        double cpuPerEdit = (cpu() - c0) / 1e6 / 100;
        out("[hl %d] 100 edits at top, 150 ms apart: %.0f ms CPU per edit; FX busy %.1f ms/edit; longest stall %.0f ms",
                lines, cpuPerEdit, s.busyMs() / 100, s.longestMs());
        metric(k + ".top_edits_cpu_ms_per_edit", cpuPerEdit);
        metric(k + ".top_edits_fx_ms_per_edit", s.busyMs() / 100);
        s.report("[hl-top " + lines + "]", 100);
    }

    static void find(int mb) throws Exception {
        String unit = Files.readString(src.resolve("editor/EditorBuffer.java"));
        StringBuilder sb = new StringBuilder();
        while (sb.length() < mb * 1048576L) {
            sb.append(unit);
        }
        String text = sb.toString();
        // com.editora.text.SearchMatcher today; com.editora.editor.SearchMatcher in older builds.
        Method search = searchMethod();
        for (String q : new String[] {"e", " ", "return", "EditorBuffer"}) {
            double base = liveMb();
            long t0 = System.nanoTime();
            Object r = search.invoke(null, text, q, false, false, false);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            double held = liveMb() - base;
            Object matches = null;
            for (var m : r.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && java.util.Collection.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    matches = m.invoke(r);
                }
            }
            out("[find %d MB] query '%s': %d ms, %s matches, result holds %.0f MB", mb, q, ms,
                    matches == null ? "?" : String.valueOf(((java.util.Collection<?>) matches).size()), held);
            String name = q.isBlank() ? "space" : q;
            metric("find-" + mb + "mb." + name + "_ms", ms);
            metric("find-" + mb + "mb." + name + "_held_mb", Math.max(0, held));
            java.lang.ref.Reference.reachabilityFence(r);
        }
    }

    static Method searchMethod() throws Exception {
        for (String cls : new String[] {"com.editora.text.SearchMatcher", "com.editora.editor.SearchMatcher"}) {
            try {
                Method m = Class.forName(cls)
                        .getDeclaredMethod("search", String.class, String.class, boolean.class, boolean.class, boolean.class);
                m.setAccessible(true);
                return m;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                // try the next home
            }
        }
        throw new IllegalStateException("no SearchMatcher.search(String, String, boolean, boolean, boolean)");
    }

    static void save() throws Exception {
        EditorBuffer b = openJava(2000);
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().requestFocus();
            b.getFocusedArea().insertText(0, 0, "// x\n");
        });
        settle(2500);
        List<Double> fxMs = new ArrayList<>();
        List<Double> cpuMs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Sampler s = new Sampler();
            long c0 = cpu();
            s.start();
            boolean ran = runCommand("file.save");
            Thread.sleep(2500);
            s.stop();
            double c = (cpu() - c0) / 1e6;
            out("[save] #%d ran=%s: FX busy %.1f ms in the 2.5 s after save; CPU %.0f ms; longest stall %.1f ms",
                    i, ran, s.busyMs(), c, s.longestMs());
            fxMs.add(s.busyMs());
            cpuMs.add(c);
            s.report("[save" + i + "]", 1);
            FxTestSupport.runOnFx(() -> b.getFocusedArea().insertText(0, 0, "// y\n"));
            settle(2500);
        }
        metric("save.fx_ms", median(fxMs));
        metric("save.cpu_ms", median(cpuMs));
    }

    static void idle() throws Exception {
        EditorBuffer b = openJava(9000);
        FxTestSupport.runOnFx(() -> b.getFocusedArea().requestFocus());
        settle(5000);
        AtomicInteger pulses = pulseCounter();
        AtomicInteger frames = frameCounter();
        int recv0 = pulseReceiverCount();
        long cpu0 = cpu();
        Sampler s = new Sampler();
        s.start();
        Thread.sleep(20000);
        s.stop();
        double cpuMs = (cpu() - cpu0) / 1e6;
        out("[idle] 20 s focused editor: FX busy %.1f ms total (%.2f%%), process CPU %.1f ms (%.2f%% of a core), pulses=%d, frames=%d, pulse receivers %d -> %d",
                s.busyMs(), s.busyMs() / 200.0, cpuMs, cpuMs / 200.0, pulses.get(), frames.get(), recv0, pulseReceiverCount());
        metric("idle.fx_ms_per_20s", s.busyMs());
        metric("idle.cpu_pct_of_core", cpuMs / 200.0);
        metric("idle.pulses_per_20s", pulses.get());
        metric("idle.frames_per_20s", frames.get());
        s.report("[idle]", 1);
        threads("[idle]");
    }

    static void memory(int tabs) throws Exception {
        double empty = liveMb();
        out("[memory] baseline (empty window): live heap %.1f MB, threads=%d", empty, Thread.activeCount());
        metric("memory.empty_window_heap_mb", empty);
        threads("[memory baseline]");
        Path dir = work("tabs");
        List<Path> files = new ArrayList<>();
        long bytes = 0;
        for (Path p : mediumSources(tabs)) {
            Path dst = dir.resolve(p.getFileName());
            Files.copy(p, dst);
            files.add(dst);
            bytes += Files.size(dst);
        }
        out("[memory] %d files, %.1f KB mean", files.size(), bytes / 1024.0 / files.size());
        double prevLive = empty;
        for (int cycle = 1; cycle <= 3; cycle++) {
            List<WeakReference<EditorBuffer>> refs = new ArrayList<>();
            Sampler s = new Sampler();
            s.start();
            long t0 = System.nanoTime();
            for (Path f : files) {
                openPath(f);
                waitLoaded();
                refs.add(new WeakReference<>(active()));
            }
            long openMs = (System.nanoTime() - t0) / 1_000_000;
            settle(4000);
            s.stop();
            double live = liveMb();
            int nodes = FxTestSupport.callOnFx(() -> countNodes(tabPane().getScene().getRoot()));
            out("[memory] cycle %d: %d tabs open in %d ms (FX busy %.0f ms, %.1f ms/tab); live heap %.1f MB (+%.2f MB/tab); threads=%d; nodes=%d",
                    cycle, files.size(), openMs, s.busyMs(), s.busyMs() / files.size(), live,
                    (live - prevLive) / files.size(), Thread.activeCount(), nodes);
            if (cycle == 1) {
                metric("memory.heap_mb_per_tab", (live - prevLive) / files.size());
                metric("memory.open_fx_ms_per_tab", s.busyMs() / files.size());
                metric("memory.open_wall_ms_per_tab", openMs / (double) files.size());
                metric("memory.threads_with_tabs", Thread.activeCount());
                metric("memory.scene_nodes_with_tabs", nodes);
                s.report("[open-tabs]", files.size());
                threads("[memory " + files.size() + " tabs]");
                Sampler sw = new Sampler();
                sw.start();
                int n = files.size();
                for (int i = 0; i < 2 * n; i++) {
                    int idx = i % n;
                    Platform.runLater(() -> tabPane().getSelectionModel().select(idx));
                    Thread.sleep(200);
                }
                sw.stop();
                out("[memory] tab switch: FX busy %.1f ms/switch", sw.busyMs() / (2 * n));
                metric("memory.tab_switch_fx_ms", sw.busyMs() / (2 * n));
                sw.report("[tab-switch]", 2 * n);
            }
            boolean ran = runCommand("buffer.closeAll");
            settle(3500);
            double after = liveMb();
            long alive = refs.stream().filter(r -> r.get() != null).count();
            out("[memory] cycle %d closed (ran=%s): tabs left=%d; live heap %.1f MB; EditorBuffers still reachable %d/%d; threads=%d; pulse receivers=%d",
                    cycle, ran, FxTestSupport.callOnFx(() -> tabPane().getTabs().size()), after, alive, refs.size(),
                    Thread.activeCount(), pulseReceiverCount());
            if (cycle == 3) {
                metric("memory.heap_after_3_cycles_mb", after);
                metric("memory.buffers_reachable_after_close", alive);
            }
            prevLive = after;
        }
        threads("[memory end]");
    }

    static void open(double mb) throws Exception {
        String k = "open-" + fmt(mb) + "mb";
        String unit = Files.readString(src.resolve("editor/EditorBuffer.java"));
        long target = (long) (mb * 1024 * 1024);
        Path f = work("open").resolve("Big.java");
        try (var w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            long n = 0;
            while (n < target) {
                String chunk = n + unit.length() <= target ? unit : unit.substring(0, (int) (target - n));
                w.write(chunk);
                n += chunk.length();
            }
        }
        double base = liveMb();
        Sampler s = new Sampler();
        long[] peak = {0};
        Thread peakT = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak[0] = Math.max(peak[0], ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        peakT.setDaemon(true);
        peakT.start();
        s.start();
        long t0 = System.nanoTime();
        openPath(f);
        waitLoaded();
        long loadedMs = (System.nanoTime() - t0) / 1_000_000;
        FxTestSupport.runOnFx(() -> {}); // FX thread reachable again
        long respMs = (System.nanoTime() - t0) / 1_000_000;
        long cpu0 = cpu();
        settle(6000);
        s.stop();
        peakT.interrupt();
        EditorBuffer b = active();
        double live = liveMb();
        boolean editable = FxTestSupport.callOnFx(() -> b.getFocusedArea().isEditable());
        double postCpu = (cpu() - cpu0) / 1e6;
        out("[open %.1f MB] loaded in %d ms (FX responsive at %d ms); FX busy %.0f ms; longest FX stall %.0f ms; peak heap used %.0f MB; live after %.0f MB (+%.0f MB = %.1fx file); large=%s editable=%s lines=%d; post-load CPU %.0f ms",
                mb, loadedMs, respMs, s.busyMs(), s.longestMs(), peak[0] / 1048576.0, live, live - base,
                (live - base) / mb, b.isLargeFile(), editable,
                FxTestSupport.callOnFx(() -> b.getFocusedArea().getParagraphs().size()), postCpu);
        metric(k + ".loaded_ms", loadedMs);
        metric(k + ".fx_busy_ms", s.busyMs());
        metric(k + ".longest_stall_ms", s.longestMs());
        metric(k + ".peak_heap_mb", peak[0] / 1048576.0);
        metric(k + ".retained_heap_mb", Math.max(0, live - base));
        metric(k + ".post_load_cpu_ms", postCpu);
        s.report("[open " + mb + "]", 1);
        if (editable) { // a few keystrokes in the middle of the big file
            FxTestSupport.runOnFx(() -> {
                CodeArea a = b.getFocusedArea();
                a.requestFocus();
                a.moveTo(a.getParagraphs().size() / 2, 0);
                a.requestFollowCaret();
            });
            settle(1500);
            Sampler ks = new Sampler();
            ks.start();
            for (int i = 0; i < 40; i++) {
                Platform.runLater(() -> fireRaw(b.getFocusedArea(), 'q'));
                Thread.sleep(100);
            }
            Thread.sleep(1500);
            ks.stop();
            out("[open %.1f MB] typing: FX busy %.2f ms/key, longest stall %.0f ms", mb, ks.busyMs() / 40, ks.longestMs());
            metric(k + ".typing_fx_ms_per_key", ks.busyMs() / 40);
            ks.report("[bigtype " + mb + "]", 40);
        }
        Files.deleteIfExists(f);
    }

    // ---------------------------------------------------------------- diagnostics (not part of the A/B)

    static Map<String, long[]> histogram() throws Exception {
        String txt = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {null}, new String[] {String[].class.getName()});
        Map<String, long[]> m = new HashMap<>();
        for (String line : txt.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 4 && f[0].endsWith(":")) {
                m.put(f[3], new long[] {Long.parseLong(f[1]), Long.parseLong(f[2])});
            }
        }
        return m;
    }

    /** Which classes each open tab's heap goes to: opens TABS files twice, diffing a class histogram on round 2. */
    static void histo(int tabs) throws Exception {
        List<Path> sources = mediumSources(tabs);
        Path dir = work("histo");
        for (int round = 0; round < 2; round++) { // round 0 warms one-off caches into the baseline
            Map<String, long[]> before = round == 1 ? histogram() : Map.of();
            for (Path p : sources) {
                Path dst = dir.resolve(p.getFileName());
                if (!Files.exists(dst)) {
                    Files.copy(p, dst);
                }
                openPath(dst);
                waitLoaded();
            }
            settle(5000);
            if (round == 1) {
                Map<String, long[]> after = histogram();
                List<Object[]> rows = new ArrayList<>();
                long total = 0;
                for (var e : after.entrySet()) {
                    long[] b = before.getOrDefault(e.getKey(), new long[] {0, 0});
                    long db = e.getValue()[1] - b[1];
                    total += db;
                    rows.add(new Object[] {e.getKey(), e.getValue()[0] - b[0], db});
                }
                rows.sort((x, y) -> Long.compare((Long) y[2], (Long) x[2]));
                out("[histo] %d tabs: +%.1f MB total, %.2f MB/tab", tabs, total / 1048576.0, total / 1048576.0 / tabs);
                for (int i = 0; i < Math.min(45, rows.size()); i++) {
                    Object[] r = rows.get(i);
                    out("    %9.1f KB/tab  %8d inst/tab  %s", (Long) r[2] / 1024.0 / tabs, (Long) r[1] / tabs, r[0]);
                }
            } else {
                runCommand("buffer.closeAll");
                settle(3000);
            }
        }
    }

    /** Every Canvas in the window with its retained texture size (canvases hold GPU textures; see performance.md). */
    static void canvases(int tabs) throws Exception {
        Path dir = work("canv");
        for (Path p : mediumSources(tabs)) {
            Path dst = dir.resolve(p.getFileName());
            if (!Files.exists(dst)) {
                Files.copy(p, dst);
            }
            openPath(dst);
            waitLoaded();
        }
        settle(5000);
        FxTestSupport.runOnFx(() -> {
            Map<String, long[]> agg = new java.util.TreeMap<>();
            java.util.ArrayDeque<javafx.scene.Node> q = new java.util.ArrayDeque<>();
            q.add(tabPane().getScene().getRoot());
            for (Tab t : tabPane().getTabs()) {
                if (t.getContent() != null) {
                    q.add(t.getContent());
                }
            }
            java.util.Set<javafx.scene.Node> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while (!q.isEmpty()) {
                javafx.scene.Node n = q.poll();
                if (!seen.add(n)) {
                    continue;
                }
                if (n instanceof javafx.scene.Parent par) {
                    q.addAll(par.getChildrenUnmodifiable());
                }
                if (n instanceof javafx.scene.canvas.Canvas c) {
                    long texPx = canvasTexturePixels(c);
                    StringBuilder path = new StringBuilder();
                    javafx.scene.Node a = c;
                    for (int d = 0; a != null && d < 3; d++, a = a.getParent()) {
                        path.append(a.getClass().getSimpleName()).append('<');
                    }
                    String key = path + String.format(" size=%.0fx%.0f vis=%b", c.getWidth(), c.getHeight(), c.isVisible())
                            + (texPx > 0 ? " TEX" : "");
                    long[] v = agg.computeIfAbsent(key, x -> new long[2]);
                    v[0]++;
                    v[1] += texPx * 4;
                }
            }
            agg.forEach((key, v) -> out("[canv] %4dx  tex %8.1f MB  %s", v[0], v[1] / 1048576.0, key));
        });
    }

    static long canvasTexturePixels(javafx.scene.canvas.Canvas c) {
        try {
            java.lang.reflect.Field pf = javafx.scene.Node.class.getDeclaredField("peer");
            pf.setAccessible(true);
            Object ng = pf.get(c);
            if (ng == null) {
                return 0;
            }
            java.lang.reflect.Field cvf = ng.getClass().getDeclaredField("cv");
            cvf.setAccessible(true);
            Object rb = cvf.get(ng);
            if (rb == null) {
                return 0;
            }
            java.lang.reflect.Field tf = rb.getClass().getDeclaredField("tex");
            tf.setAccessible(true);
            Object tex = tf.get(rb);
            if (tex == null) {
                return 0;
            }
            int w = (int) tex.getClass().getMethod("getPhysicalWidth").invoke(tex);
            int h = (int) tex.getClass().getMethod("getPhysicalHeight").invoke(tex);
            return (long) w * h;
        } catch (Throwable e) {
            out("[canv] reflect fail %s", e);
            return 0;
        }
    }

    /** A console built the way RunPanel builds its output area: a plain CodeArea, append + head trim. */
    static void console() throws Exception {
        CodeArea a = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            area.setEditable(false);
            return area;
        });
        double base = liveMb();
        String line = "2026-10-05 21:00:00.000 INFO  [worker-1] com.example.Service - handled request id=123456 in 12 ms\n";
        int total = 0;
        for (int round = 1; round <= 4; round++) {
            for (int k = 0; k < 100; k++) {
                FxTestSupport.runOnFx(() -> {
                    for (int i = 0; i < 500; i++) {
                        a.appendText(line);
                        int over = a.getLength() - 200_000;
                        if (over > 0) {
                            a.deleteText(0, over);
                        }
                    }
                });
            }
            total += 50_000;
            out("[console] %d lines appended (%.1f MB of text): area holds %d chars; live heap +%.1f MB; undo available=%s",
                    total, total * (double) line.length() / 1048576, FxTestSupport.callOnFx(a::getLength),
                    liveMb() - base, FxTestSupport.callOnFx(a::isUndoAvailable));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** {@code lines} lines of real Java, repeating EditorBuffer.java (the same text on both sides of an A/B). */
    static String javaText(int lines) throws Exception {
        List<String> unit = Files.readAllLines(src.resolve("editor/EditorBuffer.java"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(unit.get(i % unit.size())).append('\n');
        }
        return sb.toString();
    }

    /** The first {@code n} (sorted) source files of 20–60 KB under {@link #src}. */
    static List<Path> mediumSources(int n) throws Exception {
        try (var st = Files.walk(src)) {
            return st.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toFile().length() > 20_000 && p.toFile().length() < 60_000)
                    .sorted()
                    .limit(n)
                    .toList();
        }
    }

    static EditorBuffer openJava(int lines) throws Exception {
        String text = javaText(lines);
        Path f = work("doc").resolve("Sample" + lines + ".java");
        Files.writeString(f, text);
        long t0 = System.nanoTime();
        openPath(f);
        waitLoaded();
        out("opened %d lines (%d KB) in %d ms", lines, text.length() / 1024, (System.nanoTime() - t0) / 1_000_000);
        settle(4000);
        return active();
    }

    /** Opens a file as File ▸ Open does: {@code fileWorkflows.openPath}, or the controller's own in older builds. */
    static void openPath(Path f) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Object target = fx.controller;
            try {
                Object fw = FxTestSupport.field(fx.controller, "fileWorkflows");
                if (fw != null) {
                    target = fw;
                }
            } catch (RuntimeException e) {
                // no FileWorkflows in this build
            }
            FxTestSupport.call(target, "openPath", new Class<?>[] {Path.class}, f);
        });
    }

    static boolean runCommand(String id) throws Exception {
        return FxTestSupport.callOnFx(() -> (Boolean) FxTestSupport.call(
                FxTestSupport.field(fx.controller, "registry"), "run", new Class<?>[] {String.class}, id));
    }

    /** Calls a one-boolean setter if this build has it; a toggle that is missing is reported, not fatal. */
    static void invokeOptional(Object target, String method, boolean value) {
        try {
            target.getClass().getMethod(method, boolean.class).invoke(target, value);
        } catch (ReflectiveOperationException e) {
            out("(%s not in this build: %s)", method, e);
        }
    }

    static Path workRoot() throws Exception {
        return Files.createDirectories(Path.of(System.getProperty("probe.work", System.getProperty("java.io.tmpdir"))));
    }

    static Path work(String name) throws Exception {
        return Files.createTempDirectory(workRoot(), name);
    }

    static EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    static TabPane tabPane() {
        return FxTestSupport.field(fx.controller, "tabPane");
    }

    static void waitLoaded() throws Exception {
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            boolean done = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                return b != null && !b.isLoading() && b.getFocusedArea().getLength() > 0;
            });
            if (done) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("load timed out");
    }

    static void settle(long ms) throws Exception {
        Thread.sleep(ms);
        FxTestSupport.runOnFx(() -> {});
    }

    static double fire(EditorBuffer b, char ch) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            long t0 = System.nanoTime();
            fireRaw(b.getFocusedArea(), ch);
            return (System.nanoTime() - t0) / 1000.0;
        });
    }

    static void fireRaw(CodeArea area, char ch) {
        javafx.event.Event.fireEvent(
                area, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(ch), "", null, false, false, false, false));
    }

    static AtomicInteger pulseCounter() throws Exception {
        AtomicInteger n = new AtomicInteger();
        FxTestSupport.runOnFx(() -> tabPane().getScene().addPostLayoutPulseListener(n::incrementAndGet));
        return n;
    }

    /** Frames the scene actually rendered (the render pass, not just pulses), via JavaFX's PerformanceTracker. */
    static AtomicInteger frameCounter() throws Exception {
        AtomicInteger n = new AtomicInteger();
        FxTestSupport.runOnFx(() -> com.sun.javafx.perf.PerformanceTracker.getSceneTracker(tabPane().getScene())
                .setOnRenderedFrameTask(n::incrementAndGet));
        return n;
    }

    static int countNodes(javafx.scene.Node n) {
        int c = 1;
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node k : p.getChildrenUnmodifiable()) {
                c += countNodes(k);
            }
        }
        return c;
    }

    static long cpu() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    static long fxAllocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(fxThread.threadId());
    }

    static double liveMb() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(150);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
    }

    static void threads(String label) {
        Map<String, Integer> byName = new HashMap<>();
        int platform = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            platform++;
            byName.merge(t.getName().replaceAll("[-#@ ]?\\d+$", "").replaceAll("\\d+", "N"), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .forEach(e -> sb.append(e.getKey()).append('×').append(e.getValue()).append("  "));
        out("%s platform threads=%d: %s", label, platform, sb);
    }

    static int pulseReceiverCount() {
        try {
            Object tk = Class.forName("com.sun.javafx.tk.Toolkit").getMethod("getToolkit").invoke(null);
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
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Prints a distribution and returns its median. */
    static double dist(String label, List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compareTo);
        out("%s n=%d median=%.0f p90=%.0f p99=%.0f max=%.0f", label, s.size(), s.get(s.size() / 2),
                s.get((int) (s.size() * 0.90)), s.get((int) (s.size() * 0.99)), s.get(s.size() - 1));
        return s.get(s.size() / 2);
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compareTo);
        return s.get(s.size() / 2);
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    static void out(String fmt, Object... a) {
        System.out.println("PROBE " + String.format(fmt, a));
    }

    /** One number for scripts/perf-ab.sh to compare; lower is better for every metric. */
    static void metric(String key, double value) {
        System.out.println("METRIC " + key + " " + String.format(java.util.Locale.ROOT, "%.4f", value));
    }

    /** Samples the FX thread's stack on a fixed cadence and attributes busy time. */
    static final class Sampler {
        private final Map<String, Integer> deepest = new HashMap<>();
        private final Map<String, Integer> outer = new HashMap<>();
        private final Map<String, Integer> folded = new HashMap<>();
        private volatile boolean run = true;
        private Thread t;
        private long busyNanos;
        private long longestNanos;
        private int over16;
        private int over50;
        private int over100;

        void start() {
            t = new Thread(() -> {
                long last = System.nanoTime();
                long stretch = 0;
                while (run) {
                    StackTraceElement[] st = fxThread.getStackTrace();
                    Thread.State state = fxThread.getState();
                    long now = System.nanoTime();
                    long dt = now - last;
                    last = now;
                    if (busy(st, state)) {
                        busyNanos += dt;
                        stretch += dt;
                        String[] k = attribute(st);
                        deepest.merge(k[0], (int) (dt / 1000), Integer::sum);
                        outer.merge(k[1], (int) (dt / 1000), Integer::sum);
                        folded.merge(fold(st), (int) (dt / 1000), Integer::sum);
                    } else {
                        closeStretch(stretch);
                        stretch = 0;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(500_000);
                }
                closeStretch(stretch);
            }, "probe-sampler");
            t.setDaemon(true);
            t.start();
        }

        private void closeStretch(long stretch) {
            if (stretch == 0) {
                return;
            }
            longestNanos = Math.max(longestNanos, stretch);
            if (stretch > 16_000_000) {
                over16++;
            }
            if (stretch > 50_000_000) {
                over50++;
            }
            if (stretch > 100_000_000) {
                over100++;
            }
        }

        void stop() throws InterruptedException {
            run = false;
            t.join();
        }

        double busyMs() {
            return busyNanos / 1e6;
        }

        double longestMs() {
            return longestNanos / 1e6;
        }

        private static boolean busy(StackTraceElement[] st, Thread.State state) {
            if (st.length == 0) {
                return false;
            }
            if (state == Thread.State.RUNNABLE) {
                // the idle event loop: parked in a native wait with no app/scene frames
                String top = st[0].getClassName() + "." + st[0].getMethodName();
                if (top.contains("Unsafe.park") || top.contains("Object.wait")) {
                    return false;
                }
                for (StackTraceElement e : st) {
                    String c = e.getClassName();
                    if (c.startsWith("com.editora.")
                            || c.startsWith("javafx.scene")
                            || c.startsWith("org.fxmisc")
                            || c.startsWith("org.reactfx")
                            || c.startsWith("com.sun.javafx.tk.quantum")
                            || c.startsWith("com.sun.javafx.css")
                            || c.startsWith("javafx.animation")
                            || c.startsWith("com.sun.scenario")
                            || c.startsWith("com.sun.javafx.scene")) {
                        return true;
                    }
                }
                return st.length > 12;
            }
            for (StackTraceElement e : st) {
                if (e.getClassName().startsWith("com.editora.") && !e.getClassName().contains("PerfProbe")) {
                    return true; // app code blocked on a lock / wait while holding the FX thread
                }
                if (e.getClassName().startsWith("com.sun.javafx.tk.quantum.QuantumToolkit")
                        && e.getMethodName().contains("pulse")) {
                    return true; // waiting on the render thread inside a pulse
                }
            }
            return false;
        }

        private static String[] attribute(StackTraceElement[] st) {
            String deep = null;
            String out = null;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("com.editora.") && !c.contains("PerfProbe") && !c.contains("FxTestSupport")) {
                    String name = c.substring("com.editora.".length()).replaceAll("\\$\\$Lambda.*", "\\$λ") + "."
                            + e.getMethodName();
                    if (deep == null) {
                        deep = name;
                    }
                    out = name;
                }
            }
            String cat = category(st);
            if (deep == null) {
                return new String[] {"(toolkit) " + cat, "(toolkit) " + cat};
            }
            return new String[] {deep + "  ‹" + cat + "›", out};
        }

        private static String category(StackTraceElement[] st) {
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                String m = e.getMethodName();
                if (c.startsWith("com.editora.")) {
                    return "app";
                }
                if (c.contains(".css.") || m.equals("applyCss") || m.equals("processCSS") || m.equals("doProcessCSS")) {
                    return "css";
                }
                if (c.contains("TextLayout") || c.contains("com.sun.javafx.text") || c.contains("javafx.scene.text")) {
                    return "text-layout";
                }
                if (c.startsWith("org.fxmisc.flowless")) {
                    return "flowless";
                }
                if (c.startsWith("org.fxmisc.richtext")) {
                    return "richtextfx";
                }
                if (c.startsWith("org.reactfx")) {
                    return "reactfx";
                }
                if (m.equals("layoutChildren") || m.equals("layout") || m.equals("doLayoutPass")) {
                    return "layout";
                }
                if (c.contains("quantum") || c.contains("prism") || m.contains("syncPeer") || m.contains("doSync")) {
                    return "render-sync";
                }
            }
            return st[0].getClassName().replaceAll(".*\\.", "") + "." + st[0].getMethodName();
        }

        /** Folded stacks (flame-graph input), written under probe.work as {@code <label>.folded}. */
        private static String fold(StackTraceElement[] st) {
            StringBuilder sb = new StringBuilder();
            for (int i = st.length - 1; i >= 0; i--) {
                String c = st[i].getClassName();
                if (c.contains("PerfProbe") || c.contains("FxTestSupport") || c.startsWith("java.") || c.startsWith("jdk.")
                        || c.startsWith("com.sun.glass")) {
                    continue;
                }
                String shortC = c.replaceAll("^(?:[a-z0-9_]+\\.)+", "").replaceAll("\\$\\$Lambda.*", "\\$L");
                String f = shortC + "." + st[i].getMethodName();
                if (sb.length() > 0 && sb.substring(Math.max(0, sb.length() - f.length() - 1)).equals(";" + f)) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : ";").append(f);
            }
            return sb.toString();
        }

        void report(String label, int ops) {
            try {
                StringBuilder sb = new StringBuilder();
                folded.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n'));
                Files.writeString(workRoot().resolve(label.replaceAll("[^A-Za-z0-9.]+", "_") + ".folded"), sb);
            } catch (Exception e) {
                out("dump failed %s", e);
            }
            out("%s FX stalls: longest %.1f ms; >16ms ×%d; >50ms ×%d; >100ms ×%d", label, longestMs(), over16, over50, over100);
            top(label + " by deepest app frame", deepest, ops, 22);
            top(label + " by outermost app frame", outer, ops, 12);
        }

        private void top(String title, Map<String, Integer> m, int ops, int limit) {
            out("%s (ms total | ms per op):", title);
            m.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .forEach(e -> out("    %8.1f | %7.3f  %s", e.getValue() / 1000.0, e.getValue() / 1000.0 / ops, e.getKey()));
        }
    }
}
