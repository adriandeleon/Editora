package com.editora.ui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.*;

import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.completion.CompletionSession;
import com.editora.completion.CompletionSource;
import com.editora.config.Settings;
import com.editora.editor.*;
import com.editora.lsp.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in sustained real-project typing. Only disposable project copies are opened by the server. */
@Tag("probe")
class JavaTypingSoakProbeTest {
    private final Map<String, List<Double>> timings = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> ranks = new LinkedHashMap<>();
    private EditorBuffer buffer;
    private LspCoordinator coordinator;
    private LspManager manager;
    private Stage stage;
    private Path root;
    private String baseline;
    private int insertion;
    private String projectKind;
    private long scenarioStarted;
    private long lastKeyNanos;
    private int failures;
    private int cycles;
    private AnimationTimer pulses;
    private final ArrayDeque<String> requests = new ArrayDeque<>();
    private long requestSequence;

    public static void main(String[] args) {
        int status = 0;
        try {
            new JavaTypingSoakProbeTest().sustainedTypingInRealProjects();
        } catch (Throwable error) {
            error.printStackTrace();
            status = 1;
        } finally {
            javafx.application.Platform.exit();
        }
        System.exit(status);
    }

    @Test
    void sustainedTypingInRealProjects() throws Exception {
        String command = System.getProperty("lsp.java.probe.command");
        String maven = System.getProperty("lsp.java.soak.maven");
        String gradle = System.getProperty("lsp.java.soak.gradle");
        assumeTrue(
                command != null && maven != null && gradle != null,
                "set command and disposable Maven/Gradle project paths");
        int seconds = Integer.getInteger("lsp.java.soak.seconds", 1800);
        FxTestSupport.bootToolkit();
        Logger trace = Logger.getLogger("com.editora.completion.CompletionTrace");
        boolean previousLogging = trace.getUseParentHandlers();
        Handler samples = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String[] parts = record.getMessage().split(" ");
                if (parts.length == 4) sample("trace." + parts[1], Double.parseDouble(parts[2]));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        trace.setUseParentHandlers(false);
        trace.addHandler(samples);
        try {
            String[] projects =
                    System.getProperty("lsp.java.soak.projects", "maven,gradle").split(",");
            for (String kind : projects) {
                projectKind = kind;
                root = Path.of(kind.equals("maven") ? maven : gradle).toAbsolutePath();
                assertTrue(Files.exists(root.resolve(".editora-disposable-probe")), "use a marked disposable copy");
                long startup = System.nanoTime();
                setup(command, kind);
                try {
                    await(() -> manager.triggerCharacters(buffer.getPath()).contains('.'), 120, "server capabilities");
                    FxTestSupport.runOnFx(() -> FxTestSupport.invoke(coordinator, "refreshCapabilityGates"));
                    // A project-local type/member must resolve; java.lang availability is insufficient.
                    String code = kind.equals("maven") ? "new EditorBuffer().getAr" : "new CodeArea().getTe";
                    prepare(false);
                    type(code, false);
                    FxTestSupport.runOnFx(buffer::triggerCompletion);
                    awaitItem(kind.equals("maven") ? "getArea()" : "getText()", 180);
                    sample("startup.project-ready", (System.nanoTime() - startup) / 1e6);
                    System.out.printf(Locale.ROOT, "SOAK_READY %s %.1fms%n", kind, (System.nanoTime() - startup) / 1e6);
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds / projects.length));
                    long nextReport = System.nanoTime();
                    int round = 0;
                    while (System.nanoTime() < end) {
                        for (String scenario : List.of("println", "String", "ArrayList", "chain", "backspace")) {
                            try {
                                scenario(scenario, round % 4 == 3);
                            } catch (AssertionError | Exception error) {
                                failures++;
                                System.out.println("SOAK_FAILURE " + kind + " " + scenario + " " + error);
                                System.out.println("SOAK_STATE " + FxTestSupport.callOnFx(this::completionState));
                                FxTestSupport.runOnFx(
                                        () -> requests.forEach(event -> System.out.println("SOAK_REQUEST " + event)));
                                if (failures >= 8) throw error;
                            }
                            cycles++;
                            if (System.nanoTime() >= end) break;
                        }
                        round++;
                        if (System.nanoTime() >= nextReport) {
                            System.out.println("SOAK_PROGRESS " + kind + " cycles=" + cycles + " failures=" + failures);
                            nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        }
                    }
                } finally {
                    shutdown();
                }
            }
        } finally {
            trace.removeHandler(samples);
            trace.setUseParentHandlers(previousLogging);
            timings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                var values = new ArrayList<>(e.getValue());
                Collections.sort(values);
                System.out.printf(
                        Locale.ROOT,
                        "SOAK_METRIC %s n=%d median=%.3f p95=%.3f p99=%.3f max=%.3f ms%n",
                        e.getKey(),
                        values.size(),
                        percentile(values, .5),
                        percentile(values, .95),
                        percentile(values, .99),
                        values.getLast());
            });
            ranks.forEach((key, values) -> System.out.println("SOAK_RANK " + key + " n=" + values.size()
                    + " first=" + values.subList(0, Math.min(10, values.size()))
                    + " last=" + values.subList(Math.max(0, values.size() - 10), values.size())));
        }
        assertEquals(0, failures, "sustained typing failures");
    }

    private void setup(String command, String kind) throws Exception {
        Path file = root.resolve(
                kind.equals("maven")
                        ? "src/main/java/com/editora/editor/EditingTrial.java"
                        : "richtextfx-demos/src/main/java/org/fxmisc/richtext/demo/EditingTrial.java");
        Files.createDirectories(file.getParent());
        baseline = kind.equals("maven")
                ? "package com.editora.editor;\nclass EditingTrial {\n"
                : "package org.fxmisc.richtext.demo;\nimport org.fxmisc.richtext.CodeArea;\nclass EditingTrial {\n";
        Files.writeString(file, baseline + "}\n");
        manager = new LspManager((path, diagnostics) -> {}, (type, message) -> {});
        manager.configure(true, Map.of("java", command));
        manager.setJdtlsWorkspaceBase(root.getParent().resolve(kind + "-workspace"));
        if (kind.equals("gradle"))
            LspTestHooks.useLiveGradleServer(
                    manager,
                    command,
                    root.getParent().resolve("gradle-workspace"),
                    System.getProperty("lsp.java.soak.gradleJava"));
        FxTestSupport.runOnFx(() -> {
            buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(baseline + "}\n");
            var host = new CoordinatorHostStub() {
                private final Settings settings = new Settings();

                @Override
                public Settings settings() {
                    return settings;
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return buffer;
                }

                @Override
                public void forEachBuffer(java.util.function.Consumer<EditorBuffer> action) {
                    action.accept(buffer);
                }
            };
            coordinator = new LspCoordinator(host, manager, new LspOpsStub() {
                @Override
                public Path lspProjectRoot() {
                    return root;
                }
            });
            coordinator.setServerAvailableForTest("java", true);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 1000, 720));
            stage.show();
            buffer.getArea().requestFocus();
            coordinator.wireBuffer(buffer);
            Object actions = FxTestSupport.field(buffer, "completionActions");
            CompletionSource source = FxTestSupport.field(actions, "completionSource");
            buffer.setLspCompletionSource((line, column, triggerKind, character, callback) -> {
                long sequence = ++requestSequence;
                long start = System.nanoTime();
                requestEvent(sequence + " send version=" + buffer.docVersion() + " line=" + line + " col=" + column);
                Runnable cancel = source.request(line, column, triggerKind, character, result -> {
                    requestEvent(sequence + " result afterMs=" + ((System.nanoTime() - start) / 1_000_000)
                            + " count=" + result.items().size() + " incomplete=" + result.incomplete()
                            + " String=" + result.items().stream().anyMatch(item -> matches(item, "String")));
                    callback.accept(result);
                });
                return () -> {
                    requestEvent(sequence + " cancel afterMs=" + ((System.nanoTime() - start) / 1_000_000));
                    cancel.run();
                };
            });
            pulses = new AnimationTimer() {
                private long last;

                @Override
                public void handle(long now) {
                    if (last != 0 && scenarioStarted != 0) sample("fx.pulse-gap", (now - last) / 1e6);
                    last = now;
                }
            };
            pulses.start();
        });
    }

    private void prepare(boolean large) throws Exception {
        FxTestSupport.runOnFx(() -> {
            scenarioStarted = 0;
            buffer.cancelCompletion();
            FxTestSupport.call(coordinator, "hideSignaturePopup", new Class<?>[] {});
            String fields = "";
            if (large) {
                var generated = new StringBuilder();
                for (int i = 0; i < 6000; i++)
                    generated.append("  String field").append(i).append(" = \"value\";\n");
                fields = generated.toString();
            }
            String prefix = baseline + fields + "  void exercise() {\n    ";
            insertion = prefix.length();
            buffer.setContent(prefix + "\n  }\n}\n");
            buffer.getArea().moveTo(insertion);
            buffer.getArea().requestFocus();
            scenarioStarted = System.nanoTime();
        });
    }

    private void requestEvent(String event) {
        if (requests.size() == 24) requests.removeFirst();
        requests.addLast(event);
    }

    private String completionState() {
        Object actions = FxTestSupport.field(buffer, "completionActions");
        CompletionSession session = FxTestSupport.field(actions, "session");
        var area = buffer.getArea();
        return "version=" + buffer.docVersion() + " caret=" + area.getCaretPosition()
                + " focused=" + area.isFocused() + " showing=" + popup().isShowing()
                + " session="
                + (session == null
                        ? "null"
                        : session.prefix()
                                + " matches=" + session.matches(area.getCaretPosition(), buffer.docVersion())
                                + " result="
                                + (session.result() == null
                                        ? "pending"
                                        : session.result().items().size()))
                + " paragraph=" + area.getParagraph(area.getCurrentParagraph()).getText();
    }

    private void scenario(String name, boolean large) throws Exception {
        prepare(large);
        String expression =
                switch (name) {
                    case "println" -> "System.out.pr";
                    case "backspace" -> "System.out.prin";
                    case "String" -> "Str";
                    case "ArrayList" -> "new ArrayLi";
                    case "chain" -> "\"sample\".trim().sub";
                    default -> throw new IllegalArgumentException(name);
                };
        type(expression, true);
        if (name.equals("backspace")) {
            key("BACK_SPACE");
            key("BACK_SPACE");
        }
        String label =
                switch (name) {
                    case "println", "backspace" -> "println()";
                    case "String" -> "String";
                    case "ArrayList" -> "ArrayList()";
                    default -> "substring(int beginIndex)";
                };
        long finalKey = lastKeyNanos;
        awaitItem(label, 15);
        sample((large ? "large." : "small.") + name + ".key-to-popup", (System.nanoTime() - finalKey) / 1e6);
        FxTestSupport.runOnFx(() -> {
            var list = items();
            int selected = -1;
            for (int i = 0; i < list.size(); i++)
                if (matches(list.get(i), label)) {
                    selected = i;
                    break;
                }
            assertTrue(selected >= 0);
            ranks.computeIfAbsent(projectKind + "." + name, ignored -> new ArrayList<>())
                    .add(selected + 1);
            @SuppressWarnings("unchecked")
            ListView<Completion> view = FxTestSupport.field(popup(), "list");
            int current = view.getSelectionModel().getSelectedIndex();
            for (int i = current; i < selected; i++) buffer.pressKey("DOWN");
            for (int i = current; i > selected; i--) buffer.pressKey("UP");
            buffer.pressKey("ENTER");
            assertFalse(buffer.text().contains("${"), "snippet syntax must not leak into text");
        });
        if (name.equals("ArrayList"))
            await(() -> buffer.text().contains("import java.util.ArrayList;"), 15, "resolved import");
        if (name.equals("chain")) {
            await(
                    () -> {
                        javafx.stage.Popup signature = FxTestSupport.field(coordinator, "signaturePopup");
                        return signature != null && signature.isShowing();
                    },
                    15,
                    "signature help after substring completion");
            type("2", true);
            key("TAB");
        }
        if (name.equals("println") || name.equals("backspace")) {
            assertTrue(
                    FxTestSupport.callOnFx(buffer::text).contains("System.out.println()"),
                    "method must not duplicate parentheses");
        }
        key("ESCAPE");
        Thread.sleep(180);
    }

    private void type(String text, boolean paced) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            if (paced && i > 0) Thread.sleep(35 + ((i - 1) % 4) * 15L);
            String character = text.substring(i, i + 1);
            long queued = System.nanoTime();
            FxTestSupport.runOnFx(() -> {
                sample("fx.dispatch-delay", (System.nanoTime() - queued) / 1e6);
                long start = System.nanoTime();
                lastKeyNanos = start;
                if ("macro".equals(System.getProperty("lsp.java.soak.typingMode"))) buffer.typeString(character);
                else
                    buffer.getArea()
                            .fireEvent(new KeyEvent(
                                    KeyEvent.KEY_TYPED,
                                    character,
                                    character,
                                    KeyCode.UNDEFINED,
                                    false,
                                    false,
                                    false,
                                    false));
                sample("fx.key-handler", (System.nanoTime() - start) / 1e6);
            });
        }
    }

    private void key(String key) throws Exception {
        FxTestSupport.runOnFx(() -> {
            lastKeyNanos = System.nanoTime();
            buffer.pressKey(key);
        });
    }

    private CompletionPopup popup() {
        return (CompletionPopup) FxTestSupport.call(
                FxTestSupport.field(buffer, "completionActions"), "completionPopup", new Class<?>[0]);
    }

    private List<Completion> items() {
        @SuppressWarnings("unchecked")
        ListView<Completion> view = FxTestSupport.field(popup(), "list");
        return List.copyOf(view.getItems());
    }

    private static boolean matches(Completion item, String label) {
        return item.label().equals(label)
                || item.label().startsWith(label + " :")
                || item.label().startsWith(label + " -");
    }

    private void awaitItem(String label, int seconds) throws Exception {
        await(
                () -> popup().isShowing() && items().stream().anyMatch(item -> matches(item, label)),
                seconds,
                "completion " + label);
    }

    private void await(BooleanSupplier condition, int seconds, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timeout: " + description + " visible="
                + FxTestSupport.callOnFx(
                        () -> items().stream().map(Completion::label).toList()));
    }

    private void sample(String stage, double millis) {
        timings.computeIfAbsent(projectKind + "." + stage, ignored -> Collections.synchronizedList(new ArrayList<>()))
                .add(millis);
    }

    private static double percentile(List<Double> sorted, double quantile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
    }

    private void shutdown() throws Exception {
        var processes = ProcessHandle.current()
                .descendants()
                .filter(process -> Arrays.stream(process.info().arguments().orElse(new String[0]))
                        .anyMatch(
                                argument -> argument.startsWith(root.getParent().toString())))
                .toList();
        FxTestSupport.runOnFx(() -> {
            pulses.stop();
            buffer.dispose();
            manager.shutdownAll();
            stage.close();
        });
        for (var process : processes) process.onExit().get(30, TimeUnit.SECONDS);
    }
}
