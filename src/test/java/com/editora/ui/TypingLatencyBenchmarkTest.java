package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Parent;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the FX-thread cost of one keystroke: a real {@code KEY_TYPED} through the production filter
 * chain (auto-close, auto-indent, snippets, completion trigger, the {@code plainTextChanges} subscribers)
 * plus the CSS + layout pass that must complete before the frame can paint.
 *
 * <p>Not an assertion test — it prints a distribution. The startup path has {@code scripts/measure-startup.sh};
 * this is the equivalent for the typing path, which is the one CLAUDE.md treats as sacred.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypingLatencyBenchmarkTest {

    private static final int WARMUP = 300;
    private static final int MEASURE = 600;
    /** Enough to make a +2/keystroke leak unmistakable while staying ~1 s in the suite. */
    private static final int KEYSTROKES = 400;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /**
     * Control: the same KEY_TYPED into a <em>bare</em> {@link CodeArea} with no Editora wiring at all, in
     * its own scene. Whatever this costs is RichTextFX + the headless software pipeline, not Editora — so
     * only the difference between this and {@link #typingLatencyDistribution()} is attributable to us.
     */

    /**
     * Regression guard for the per-keystroke {@code Timeline} leak (the reason this class exists).
     *
     * <p>A zero-width {@code getCharacterBoundsOnScreen(x, x)} makes {@code GenericStyledArea} allocate a
     * throwaway {@code CaretNode}, whose 500 ms blink timer nothing ever stops — so the call permanently
     * registers a running {@code Timeline} as a JavaFX pulse receiver. When the 80-column ruler did this on
     * every edit it leaked <b>+2 per keystroke</b>, degrading typing from 5.6 ms to 28 ms over 2000
     * keystrokes and never recovering. The failure is invisible in normal use until the editor has been
     * typed in for a few minutes, and no other test would catch it, so it is asserted here.
     *
     * <p>Deliberately counts <em>receivers</em>, not latency: the count is exact and machine-independent,
     * whereas latency needs a warmed JIT and a quiet box.
     */
    @Test
    void typingDoesNotLeakPulseReceivers() throws Exception {
        Path file = Files.createTempFile("editora-leakguard-", ".java");
        Files.writeString(file, sample(400));
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            assertNotNull(b, "the file opened into a buffer");
            FxTestSupport.runOnFx(() -> b.getFocusedArea().requestFocus());
            for (int i = 0; i < 8; i++) {
                FxTestSupport.runOnFx(() -> {});
                Thread.sleep(20);
            }
            int before = pulseReceiverCount();
            assertTrue(before >= 0, "pulse-receiver introspection works on this JDK");
            for (int i = 0; i < KEYSTROKES; i++) {
                fireInto(b.getFocusedArea(), (char) ('a' + (i % 26)));
            }
            double perKeystroke = (pulseReceiverCount() - before) / (double) KEYSTROKES;
            assertTrue(
                    perKeystroke < 0.1,
                    "typing must not leak JavaFX pulse receivers, but leaked " + perKeystroke
                            + " per keystroke — most likely a zero-width getCharacterBoundsOnScreen(x, x)"
                            + " call was reintroduced (see EditorBuffer.caretBounds)");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The same guard for the <em>caret-anchored popups</em>, which measured the caret the leaking way.
     *
     * <p>{@code showCodeActions} and both completion-popup paths anchored themselves with the zero-width
     * {@code getCharacterBoundsOnScreen(caret, caret)} form, so each open leaked one blink {@code Timeline}.
     * Rarer than the ruler's every-keystroke leak, but permanent and cumulative in exactly the same way, and
     * the completion popup opens on the debounced trigger for as long as the session lasts. They now ask the
     * area for the real caret's screen bounds, which allocates nothing.
     *
     * <p>Driven through {@code showCodeActions} because it is synchronous and needs no server, dictionary or
     * debounce to open — the completion paths were fixed with it and share the same one-line measurement.
     */
    @Test
    void openingACaretAnchoredPopupDoesNotLeakPulseReceivers() throws Exception {
        final int OPENS = 60;
        Path file = Files.createTempFile("editora-popupleak-", ".java");
        Files.writeString(file, sample(200));
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            assertNotNull(b, "the file opened into a buffer");
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().requestFocus();
                b.getFocusedArea().moveTo(10);
            });
            for (int i = 0; i < 8; i++) {
                FxTestSupport.runOnFx(() -> {});
                Thread.sleep(20);
            }
            List<com.editora.editor.CodeAction> actions =
                    List.of(new com.editora.editor.CodeAction("Fix it", "quickfix", true, null));

            // Open once before the baseline: the first open builds the popup and its cells, whose own
            // one-off nodes would otherwise be counted as a leak.
            FxTestSupport.runOnFx(() -> {
                b.showCodeActions(actions, a -> {});
                b.hideCodeActions();
            });
            FxTestSupport.runOnFx(() -> {});

            int before = pulseReceiverCount();
            assertTrue(before >= 0, "pulse-receiver introspection works on this JDK");
            for (int i = 0; i < OPENS; i++) {
                FxTestSupport.runOnFx(() -> {
                    b.showCodeActions(actions, a -> {});
                    b.hideCodeActions();
                });
            }
            double perOpen = (pulseReceiverCount() - before) / (double) OPENS;
            assertTrue(
                    perOpen < 0.1,
                    "opening a caret-anchored popup must not leak JavaFX pulse receivers, but leaked " + perOpen
                            + " per open — most likely a zero-width getCharacterBoundsOnScreen(caret, caret)"
                            + " call was reintroduced; measure a one-character range instead"
                            + " (see EditorBuffer.caretAnchorBounds)");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#bareCodeAreaControl")
    void bareCodeAreaControl() throws Exception {
        for (int lines : new int[] {200, 20_000}) {
            String text = sample(lines);
            CodeArea bare = FxTestSupport.callOnFx(() -> {
                CodeArea a = new CodeArea();
                a.replaceText(text);
                javafx.stage.Stage st = new javafx.stage.Stage();
                st.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(a), 1200, 800));
                st.show();
                a.moveTo(a.getLength() / 2);
                return a;
            });
            for (int i = 0; i < WARMUP; i++) {
                fireInto(bare, 'x');
            }
            List<Double> us = new ArrayList<>(MEASURE);
            for (int i = 0; i < MEASURE; i++) {
                us.add(fireInto(bare, (char) ('a' + (i % 26))));
            }
            report("BARE " + lines + " lines", us);
            FxTestSupport.runOnFx(() -> ((javafx.stage.Stage) bare.getScene().getWindow()).close());
        }
    }

    /**
     * Decomposition: where does the per-keystroke cost live? Each step adds one layer over the bare
     * {@link CodeArea} control, in its own plain scene, so the deltas attribute the cost.
     *
     * <ul>
     *   <li>bare CodeArea — RichTextFX + the headless pipeline (the floor)
     *   <li>EditorBuffer, plain scene, no stylesheets — EditorBuffer's own listener/overlay wiring
     *   <li>EditorBuffer, plain scene, app.css + syntax.css — adds the real CSS weight
     * </ul>
     */
    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#layerDecomposition")
    void layerDecomposition() throws Exception {
        String text = sample(2_000);

        CodeArea bare = FxTestSupport.callOnFx(() -> {
            CodeArea a = new CodeArea();
            a.replaceText(text);
            showIn(new javafx.scene.layout.StackPane(a), false);
            a.moveTo(a.getLength() / 2);
            return a;
        });
        measure("1. bare CodeArea", () -> bare);

        for (boolean css : new boolean[] {false, true}) {
            EditorBuffer buf = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent(text);
                b.setLanguageOverride("java");
                showIn(new javafx.scene.layout.StackPane(b.getNode()), css);
                b.getArea().moveTo(b.getArea().getLength() / 2);
                return b;
            });
            measure("2. EditorBuffer " + (css ? "+app.css " : "plain    "), buf::getFocusedArea);
        }
    }

    private static void showIn(javafx.scene.Parent root, boolean css) {
        javafx.scene.Scene sc = new javafx.scene.Scene(root, 1200, 800);
        if (css) {
            sc.getStylesheets()
                    .addAll(
                            EditorBuffer.class
                                    .getResource("/com/editora/styles/app.css")
                                    .toExternalForm(),
                            EditorBuffer.class
                                    .getResource("/com/editora/styles/syntax.css")
                                    .toExternalForm());
        }
        javafx.stage.Stage st = new javafx.stage.Stage();
        st.setScene(sc);
        st.show();
    }

    private void measure(String label, java.util.function.Supplier<CodeArea> area) throws Exception {
        for (int i = 0; i < 10; i++) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(30);
        }
        for (int i = 0; i < 200; i++) {
            fireInto(area.get(), 'x');
        }
        List<Double> us = new ArrayList<>(400);
        for (int i = 0; i < 400; i++) {
            us.add(fireInto(area.get(), (char) ('a' + (i % 26))));
        }
        report(label, us);
    }

    /** Confirm the leak source: zero-width getCharacterBoundsOnScreen probes (column ruler + caret bounds). */
    @Test
    @Disabled("measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#leakVsSettings")
    void leakVsSettings() throws Exception {
        String[][] cases = {
            {"defaults", ""},
            {"columnRuler off", "\"showColumnRuler\":false"},
            {"ruler+minimap+ws off", "\"showColumnRuler\":false,\"showMinimap\":false,\"showWhitespace\":false"},
        };
        for (String[] c : cases) {
            Path dir = Files.createTempDirectory("editora-leaks");
            Files.writeString(dir.resolve("settings.json"), settingsJson(c[1]));
            Path file = Files.createTempFile("editora-leaks-", ".java");
            Files.writeString(file, sample(400));
            FxWindowFixture w = FxWindowFixture.create(dir, false, false, false, List.of(), true, x -> {});
            try {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.call(w.controller, "openPath", new Class[] {Path.class}, file));
                EditorBuffer b = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(w.controller, "activeBuffer", new Class[] {}));
                FxTestSupport.runOnFx(() -> b.getFocusedArea().requestFocus());
                for (int i = 0; i < 8; i++) {
                    FxTestSupport.runOnFx(() -> {});
                    Thread.sleep(30);
                }
                int before = pulseReceiverCount();
                List<Double> us = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    us.add(fireInto(b.getFocusedArea(), (char) ('a' + (i % 26))));
                }
                int after = pulseReceiverCount();
                System.out.printf(
                        "[typing] %-22s %+.2f receivers/keystroke   first50=%.0fus last50=%.0fus%n",
                        c[0], (after - before) / 500.0, median(us.subList(0, 50)), median(us.subList(450, 500)));
            } finally {
                Files.deleteIfExists(file);
                w.dispose();
            }
        }
    }

    /** Is the per-keystroke Timeline leak caused by the multi-caret fork? Run with -Dbench.case=on|off. */
    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#leakVsMultiCaret")
    void leakVsMultiCaret() throws Exception {
        for (String mc : new String[] {"true", "false"}) {
            Path dir = Files.createTempDirectory("editora-mc");
            Files.writeString(dir.resolve("settings.json"), settingsJson("\"multiCaret\":" + mc));
            Path file = Files.createTempFile("editora-mc-", ".java");
            Files.writeString(file, sample(400));
            FxWindowFixture w = FxWindowFixture.create(dir, false, false, false, List.of(), true, x -> {});
            try {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.call(w.controller, "openPath", new Class[] {Path.class}, file));
                EditorBuffer b = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(w.controller, "activeBuffer", new Class[] {}));
                for (int i = 0; i < 8; i++) {
                    FxTestSupport.runOnFx(() -> {});
                    Thread.sleep(30);
                }
                int before = pulseReceiverCount();
                List<Double> us = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    us.add(fireInto(b.getFocusedArea(), (char) ('a' + (i % 26))));
                }
                int after = pulseReceiverCount();
                List<Double> s2 = new ArrayList<>(us);
                s2.sort(Double::compareTo);
                System.out.printf(
                        "[typing] multiCaret=%-5s receivers %4d -> %4d (%+.2f/keystroke)  median=%.0fus  last50median=%.0fus%n",
                        mc,
                        before,
                        after,
                        (after - before) / 500.0,
                        s2.get(s2.size() / 2),
                        median(us.subList(450, 500)));
            } finally {
                Files.deleteIfExists(file);
                w.dispose();
            }
        }
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compareTo);
        return s.get(s.size() / 2);
    }

    /** Who owns the Timeline leak: RichTextFX (bare CodeArea), EditorBuffer, or the MainController window? */
    @Test
    @Disabled("measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#leakOwnership")
    void leakOwnership() throws Exception {
        String text = sample(400);

        CodeArea bare = FxTestSupport.callOnFx(() -> {
            CodeArea a = new CodeArea();
            a.replaceText(text);
            showIn(new javafx.scene.layout.StackPane(a), false);
            a.moveTo(a.getLength() / 2);
            return a;
        });
        leakRun("bare CodeArea       ", () -> bare);

        EditorBuffer standalone = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(text);
            b.setLanguageOverride("java");
            showIn(new javafx.scene.layout.StackPane(b.getNode()), true);
            b.getArea().moveTo(b.getArea().getLength() / 2);
            return b;
        });
        leakRun("EditorBuffer alone  ", standalone::getFocusedArea);

        Path file = Files.createTempFile("editora-own-", ".java");
        Files.writeString(file, text);
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer inApp = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            leakRun("EditorBuffer in app ", inApp::getFocusedArea);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void leakRun(String label, java.util.function.Supplier<CodeArea> area) throws Exception {
        // CRITICAL: an UNFOCUSED caret does not blink, so an unfocused control cannot leak blink timers.
        // Without this the comparison is meaningless — it would just be measuring which areas had focus.
        FxTestSupport.runOnFx(() -> area.get().requestFocus());
        for (int i = 0; i < 5; i++) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(30);
        }
        boolean focused = FxTestSupport.callOnFx(() -> area.get().isFocused());
        int before = pulseReceiverCount();
        for (int i = 0; i < 500; i++) {
            fireInto(area.get(), (char) ('a' + (i % 26)));
        }
        int after = pulseReceiverCount();
        System.out.printf(
                "[typing] %s  focused=%-5s receivers %5d -> %5d  (%+.2f per keystroke)%n",
                label, focused, before, after, (after - before) / 500.0);
    }

    /** ONE buffer, no tabs opened: does the pulse-receiver list grow purely with keystrokes typed? */
    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#pulseReceiverGrowthPerKeystroke")
    void pulseReceiverGrowthPerKeystroke() throws Exception {
        Path file = Files.createTempFile("editora-leak-", ".java");
        Files.writeString(file, sample(400));
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            for (int i = 0; i < 10; i++) {
                FxTestSupport.runOnFx(() -> {});
                Thread.sleep(30);
            }
            System.out.printf(
                    "[typing] keystrokes=%5d  pulseReceivers=%5d  latency=%s%n", 0, pulseReceiverCount(), "-");
            for (int round = 1; round <= 8; round++) {
                List<Double> us = new ArrayList<>();
                for (int i = 0; i < 250; i++) {
                    us.add(fireInto(b.getFocusedArea(), (char) ('a' + (i % 26))));
                }
                List<Double> s2 = new ArrayList<>(us);
                s2.sort(Double::compareTo);
                System.out.printf(
                        "[typing] keystrokes=%5d  pulseReceivers=%5d  median=%.0fus%n",
                        round * 250, pulseReceiverCount(), s2.get(s2.size() / 2));
                if (round == 8) {
                    System.out.println("              " + pulseReceiverClasses());
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Does typing get slower as more tabs are open? JFR says 6.4% of FX-thread self time is
     * {@code AbstractPrimaryTimer.removePulseReceiver}, a linear scan of JavaFX's global pulse-receiver
     * list, driven by ReactFX debounce timers restarting on every keystroke. If each buffer registers
     * timers, the cost of one keystroke in the ACTIVE buffer should grow with the number of open buffers,
     * even though they are idle and invisible.
     */
    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#typingCostVsOpenTabs")
    void typingCostVsOpenTabs() throws Exception {
        Path first = Files.createTempFile("editora-tabs-", ".java");
        Files.writeString(first, sample(400));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, first));
        EditorBuffer active = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        List<Path> extra = new ArrayList<>();
        try {
            int[] marks = {1, 5, 10, 20, 30};
            int opened = 1;
            for (int target : marks) {
                while (opened < target) {
                    Path p = Files.createTempFile("editora-tabs-" + opened + "-", ".java");
                    Files.writeString(p, sample(400));
                    extra.add(p);
                    FxTestSupport.runOnFx(
                            () -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, p));
                    opened++;
                }
                // Re-select the original buffer's TAB so we type into the visible, selected buffer.
                // requestFocus() alone is not enough: a node inside a non-selected tab cannot take focus,
                // so without this we would be measuring typing into a background buffer.
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, first));
                FxTestSupport.runOnFx(() -> active.getFocusedArea().requestFocus());
                EditorBuffer sel = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
                if (sel != active) {
                    System.out.println("           WARNING: active buffer is not the one under test");
                }
                for (int i = 0; i < 5; i++) {
                    FxTestSupport.runOnFx(() -> {});
                    Thread.sleep(30);
                }
                for (int i = 0; i < 150; i++) {
                    fireInto(active.getFocusedArea(), 'x');
                }
                long gc0 = gcCount();
                long gcms0 = gcMillis();
                List<Double> us = new ArrayList<>(300);
                for (int i = 0; i < 300; i++) {
                    us.add(fireInto(active.getFocusedArea(), (char) ('a' + (i % 26))));
                }
                report(opened + " tabs open", us);
                System.out.printf(
                        "           pulseReceivers=%d  gcCount=%d  gcMillis=%d  heapUsed=%dMB%n",
                        pulseReceiverCount(),
                        gcCount() - gc0,
                        gcMillis() - gcms0,
                        java.lang.management.ManagementFactory.getMemoryMXBean()
                                        .getHeapMemoryUsage()
                                        .getUsed()
                                / (1024 * 1024));
            }
        } finally {
            Files.deleteIfExists(first);
            for (Path p : extra) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Size of JavaFX's global pulse-receiver list. {@code AbstractPrimaryTimer.removePulseReceiver} is a
     * linear scan of it, and JFR puts 6.4% of FX-thread self time there — so if this grows with tab count,
     * every timer restart in the ACTIVE buffer pays for every OTHER open buffer.
     */
    private static long gcCount() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private static long gcMillis() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionTime)
                .sum();
    }

    /** Inspect the leaked Timelines: cycle count, status, and the class of each KeyFrame's handler. */
    private static String pulseReceiverClasses() {
        try {
            Object tk = Class.forName("com.sun.javafx.tk.Toolkit")
                    .getMethod("getToolkit")
                    .invoke(null);
            Object primary = tk.getClass().getMethod("getPrimaryTimer").invoke(tk);
            Class<?> c = primary.getClass();
            while (c != null && !c.getName().endsWith("AbstractPrimaryTimer")) {
                c = c.getSuperclass();
            }
            java.lang.reflect.Field f = c.getDeclaredField("receivers");
            f.setAccessible(true);
            java.lang.reflect.Field n = c.getDeclaredField("receiversLength");
            n.setAccessible(true);
            Object[] arr = (Object[]) f.get(primary);
            int len = (int) n.get(primary);
            java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
            for (int i = 0; i < len && i < arr.length; i++) {
                Object rec = arr[i];
                if (rec == null) {
                    continue;
                }
                String key = "?";
                for (java.lang.reflect.Field ff : rec.getClass().getDeclaredFields()) {
                    ff.setAccessible(true);
                    Object v = ff.get(rec);
                    if (v == null) {
                        continue;
                    }
                    for (java.lang.reflect.Field gf : v.getClass().getDeclaredFields()) {
                        gf.setAccessible(true);
                        Object w = gf.get(v);
                        if (w instanceof javafx.animation.Timeline tl) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Timeline cycles=")
                                    .append(tl.getCycleCount())
                                    .append(" status=")
                                    .append(tl.getStatus())
                                    .append(" frames=")
                                    .append(tl.getKeyFrames().size());
                            for (javafx.animation.KeyFrame kf : tl.getKeyFrames()) {
                                sb.append(" | ")
                                        .append(kf.getTime())
                                        .append(" onFinished=")
                                        .append(
                                                kf.getOnFinished() == null
                                                        ? "null"
                                                        : kf.getOnFinished()
                                                                .getClass()
                                                                .getName());
                            }
                            key = sb.toString();
                        }
                    }
                }
                hist.merge(key, 1, Integer::sum);
            }
            return hist.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .map(e -> e.getValue() + "x " + e.getKey())
                    .collect(java.util.stream.Collectors.joining("\n              "));
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }

    private static int pulseReceiverCount() {
        try {
            Object timer = Class.forName("com.sun.javafx.tk.Toolkit")
                    .getMethod("getToolkit")
                    .invoke(null);
            Object primary = timer.getClass().getMethod("getPrimaryTimer").invoke(timer);
            Class<?> c = primary.getClass();
            while (c != null && !c.getName().endsWith("AbstractPrimaryTimer")) {
                c = c.getSuperclass();
            }
            if (c == null) {
                return -1;
            }
            java.lang.reflect.Field f = c.getDeclaredField("receivers");
            f.setAccessible(true);
            Object arr = f.get(primary);
            java.lang.reflect.Field n = c.getDeclaredField("receiversLength");
            n.setAccessible(true);
            return (int) n.get(primary);
        } catch (Throwable t) {
            return -1;
        }
    }

    private double fireInto(CodeArea area, char ch) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            long t0 = System.nanoTime();
            javafx.event.Event.fireEvent(
                    area, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(ch), "", null, false, false, false, false));
            return (System.nanoTime() - t0) / 1000.0;
        });
    }

    /** One window, one buffer, sustained typing — a stable target for an external stack sampler. */
    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#sustainedTypingForProfiling")
    void sustainedTypingForProfiling() throws Exception {
        Path file = Files.createTempFile("editora-profile-", ".java");
        Files.writeString(file, sample(2_000));
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            for (int i = 0; i < 10; i++) {
                FxTestSupport.runOnFx(() -> {});
                Thread.sleep(30);
            }
            FxTestSupport.runOnFx(
                    () -> buffer.getFocusedArea().moveTo(buffer.getFocusedArea().getLength() / 2));
            System.out.println(
                    "[typing] PROFILE-START pid=" + ProcessHandle.current().pid());
            long deadline = System.nanoTime() + 45_000_000_000L;
            List<Double> us = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                us.add(typeOnce(buffer, 'q', false));
            }
            report("sustained", us);
            System.out.println("[typing] PROFILE-END");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Bisect the per-keystroke cost. Run ONE case per JVM via {@code -Dbench.case=<name>} — running several
     * in one JVM makes each successive window slower (measured: 4.8 -> 7.6 ms purely by position in the
     * loop), which reads exactly like a feature cost and is not one.
     */
    @Test
    @Disabled("measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#featureBisect")
    void featureBisect() throws Exception {
        String[][] all = {
            {"defaults", ""},
            {
                "all suspects off",
                "\"spellCheck\":false,\"todoHighlight\":false,\"autocomplete\":false,"
                        + "\"showMinimap\":false,\"notesSupport\":false,"
                        + "\"showNoteIndicators\":false,\"markdownLint\":false,"
                        + "\"editorConfigSupport\":false,\"multiCaret\":false"
            },
            {"spellCheck off", "\"spellCheck\":false"},
            {"todoHighlight off", "\"todoHighlight\":false"},
            {"autocomplete off", "\"autocomplete\":false"},
            {"minimap off", "\"showMinimap\":false"},
            {"notes off", "\"notesSupport\":false,\"showNoteIndicators\":false"},
            {"simple (no gutter)", "\"simpleMode\":true"},
            {
                "simple + all off",
                "\"simpleMode\":true,\"spellCheck\":false,\"todoHighlight\":false,"
                        + "\"autocomplete\":false,\"showMinimap\":false,"
                        + "\"notesSupport\":false,\"showNoteIndicators\":false"
            },
        };
        String only = System.getProperty("bench.case", "");
        String[][] cases = only.isEmpty()
                ? all
                : java.util.Arrays.stream(all).filter(c -> c[0].equals(only)).toArray(String[][]::new);
        String text = sample(2_000);
        for (String[] c : cases) {
            Path dir = Files.createTempDirectory("editora-bisect");
            Files.writeString(dir.resolve("settings.json"), settingsJson(c[1]));
            Path file = Files.createTempFile("editora-bisect-", ".java");
            Files.writeString(file, text);
            FxWindowFixture w = FxWindowFixture.create(dir, false, false, false, List.of(), true, x -> {});
            try {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.call(w.controller, "openPath", new Class[] {Path.class}, file));
                EditorBuffer buffer = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(w.controller, "activeBuffer", new Class[] {}));
                if (buffer == null) {
                    System.out.println("[typing] " + c[0] + ": no buffer, skipped");
                    continue;
                }
                for (int i = 0; i < 10; i++) {
                    FxTestSupport.runOnFx(() -> {});
                    Thread.sleep(30);
                }
                FxTestSupport.runOnFx(() ->
                        buffer.getFocusedArea().moveTo(buffer.getFocusedArea().getLength() / 2));
                for (int i = 0; i < 150; i++) {
                    typeOnce(buffer, 'x', false);
                }
                List<Double> us = new ArrayList<>(300);
                for (int i = 0; i < 300; i++) {
                    us.add(typeOnce(buffer, (char) ('a' + (i % 26)), false));
                }
                report(c[0], us);
            } finally {
                Files.deleteIfExists(file);
                w.dispose();
            }
        }
    }

    @Test
    @Disabled(
            "measurement harness, not a check; run explicitly with -Dtest=TypingLatencyBenchmarkTest#typingLatencyDistribution")
    void typingLatencyDistribution() throws Exception {
        for (int lines : new int[] {200, 2_000, 20_000}) {
            Path file = Files.createTempFile("editora-typing-" + lines + "-", ".java");
            Files.writeString(file, sample(lines));
            try {
                FxTestSupport.runOnFx(
                        () -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
                EditorBuffer buffer = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
                if (buffer == null) {
                    System.out.println("[typing] " + lines + " lines: buffer did not open, skipped");
                    continue;
                }
                // Let the initial highlight / fold / overlay passes settle before measuring.
                for (int i = 0; i < 10; i++) {
                    FxTestSupport.runOnFx(() -> {});
                    Thread.sleep(30);
                }
                // Park the caret mid-document so edits land in a realistic spot (not the empty tail).
                FxTestSupport.runOnFx(() ->
                        buffer.getFocusedArea().moveTo(buffer.getFocusedArea().getLength() / 2));

                for (int i = 0; i < WARMUP; i++) {
                    typeOnce(buffer, 'x', false);
                }
                List<Double> dispatch = new ArrayList<>(MEASURE);
                List<Double> withLayout = new ArrayList<>(MEASURE);
                for (int i = 0; i < MEASURE; i++) {
                    dispatch.add(typeOnce(buffer, (char) ('a' + (i % 26)), false));
                    withLayout.add(typeOnce(buffer, (char) ('a' + (i % 26)), true));
                }
                report(lines + " lines  dispatch", dispatch);
                report(lines + " lines  +layout ", withLayout);
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * One keystroke on the FX thread. {@code layout=false} times only the KEY_TYPED filter chain and the
     * synchronous {@code plainTextChanges} subscribers — i.e. Editora's own per-keystroke code. With
     * {@code layout=true} it adds a {@code layout()} pass; note that is an <em>upper bound</em>, since a
     * headless run has no pulse to clear dirty bits, so more re-layout happens here than in the real app.
     */
    private double typeOnce(EditorBuffer buffer, char ch, boolean layout) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = buffer.getFocusedArea();
            long t0 = System.nanoTime();
            javafx.event.Event.fireEvent(
                    area, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(ch), "", null, false, false, false, false));
            if (layout) {
                Parent root = area.getScene() != null ? area.getScene().getRoot() : null;
                if (root != null) {
                    root.layout();
                }
            }
            return (System.nanoTime() - t0) / 1000.0;
        });
    }

    private static void report(String label, List<Double> us) {
        List<Double> s = new ArrayList<>(us);
        s.sort(Double::compareTo);
        System.out.printf(
                "[typing] %-12s n=%d  median=%.0fµs  p90=%.0fµs  p99=%.0fµs  max=%.0fµs%n",
                label,
                s.size(),
                s.get(s.size() / 2),
                s.get((int) (s.size() * 0.90)),
                s.get((int) (s.size() * 0.99)),
                s.get(s.size() - 1));
    }

    private static String settingsJson(String fields) {
        return "{\"schemaVersion\":93" + (fields.isBlank() ? "" : "," + fields) + "}";
    }

    private static String sample(int lines) {
        StringBuilder sb = new StringBuilder(lines * 60);
        sb.append("package bench;\n\nimport java.util.List;\n\npublic class Sample {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    // field ").append(i).append(" holds a value used by the sample method below\n");
            sb.append("    private final String field")
                    .append(i)
                    .append(" = \"value ")
                    .append(i)
                    .append("\";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
