package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs <em>every</em> command of a real window through {@link CommandRegistry#run}, in several editor states,
 * and fails when one throws, leaves an uncaught exception behind, starts a program it should not, or reaches
 * for the network.
 *
 * <p>The feature code under most commands has its own tests, which call the coordinators directly. What those
 * do not cover is the binding from a command id to its action and the guard in front of it ({@code ifLsp},
 * {@code ifEnabled}, {@code withActiveDiff}, …) — the layer a key chord, a menu item, the palette and a macro
 * all go through. A command is covered here by default: a new one is run without anyone having to remember
 * this test. The only commands left out are the ones named in {@link #EXCLUDED}, each with its reason.
 *
 * <p>What keeps a run harmless on a developer's machine:
 *
 * <ul>
 *   <li>Printer jobs come from a supplier that says "no printer" ({@code ExportCoordinator.printJobs}), so no
 *       page is ever sent to a real printer.
 *   <li>The window has no {@code HostServices}, so nothing opens a browser.
 *   <li>Every external tool with a configurable path is pointed at a file that does not exist
 *       ({@link Sweep#withoutExternalTools}), so the window finds the same tools everywhere: none but
 *       {@code git}, which only ever runs in the test's own temporary directories.
 *   <li>A modal dialog is answered with its Cancel (or No) button — or acknowledged, when it has only one
 *       button — the moment it blocks, so a destructive confirmation is never accepted.
 *   <li>The Headless Glass platform refuses a native file chooser with an exception. A command that ends
 *       there is listed in {@link #NATIVE_CHOOSER}: it runs up to the chooser.
 *   <li>{@link Outside} blocks connections that would leave the machine, and records every program started
 *       and every byte sent to another host; anything not expected fails the sweep.
 * </ul>
 */
@Tag("fx")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommandSweepFxTest {

    /** The editor states the commands run in, so that a guard is exercised both ways. */
    enum State {
        /** A fresh window: the Welcome tab, no buffer, no project. */
        EMPTY,
        /** A new buffer that has text and a selection but has never been saved: dirty, and without a path. */
        UNTITLED,
        /** A plain-text file with a selection. */
        TEXT,
        /** A Markdown file with a selection. */
        MARKDOWN,
        /** A project window on a Git repository, with a modified tracked file open. */
        GIT_PROJECT
    }

    /**
     * Commands the sweep does not run, each with the reason. Everything else in the registry runs. An id
     * that is no longer registered fails {@link #theListsNameRegisteredCommands}, so this cannot rot.
     */
    private static final Map<String, String> EXCLUDED = excluded();

    private static Map<String, String> excluded() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app.quit", "closes every window and exits the toolkit the other FX tests share");
        m.put("file.revealInFileManager", "starts the desktop's file manager");
        m.put("file.openTerminal", "starts a terminal emulator");
        m.put("help.checkForUpdates", "asks the release server over the network");
        String installs = "downloads and installs tools on this machine (npm install -g …), without asking";
        m.put("install.javaSupport", installs);
        m.put("install.pythonSupport", installs);
        m.put("install.jsSupport", installs);
        m.put("install.mermaidSupport", installs);
        m.put("install.typstCli", installs);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Commands that end at a native file or folder chooser, which the Headless platform refuses with an
     * {@code UnsupportedOperationException}. They run up to that point — the guard in front of the chooser
     * is still exercised — and the refusal is the expected outcome. A command outside this list that reaches
     * a chooser fails the sweep, and so does a listed one that no longer reaches one in any state.
     */
    private static final Set<String> NATIVE_CHOOSER = Set.of(
            "diff.compareDirectories",
            "editor.exportPdf",
            "editor.exportSelectionPdf",
            "file.open",
            "file.save", // of a buffer that has no file yet
            "file.saveAsAdmin", // likewise: without a path it is Save As
            "git.applyPatch",
            "notes.export",
            "preview.exportDocx",
            "preview.exportHtml",
            "preview.exportOdt",
            "preview.exportPdf");

    private static final String TEXT = String.join(
            "\n",
            "Editora command sweep fixture.",
            "",
            "fooBar baz_qux, alpha beta (gamma [delta] {epsilon}).",
            "second   line  with   spaces\tand a tab",
            "TODO: a marker for the todo commands",
            "https://example.invalid/page",
            "",
            "duplicate line",
            "duplicate line",
            "    indented line",
            "the last line",
            "");

    private static final String MARKDOWN = String.join(
            "\n",
            "# Sweep fixture",
            "",
            "Some *prose* with a [link](https://example.invalid) and `code`.",
            "",
            "## Section",
            "",
            "- item one",
            "- item two",
            "",
            "| a | b |",
            "|---|---|",
            "| 1 | 2 |",
            "",
            "```java",
            "int x = 1;",
            "```",
            "");

    /** The printer-job supplier every window of the sweep gets: there is no printer, so there is no job. */
    private static final Supplier<PrintPreview.Job> NO_PRINTER = () -> null;

    /** What each state's sweep saw, for the cross-state checks of {@link #theListsNameRegisteredCommands}. */
    private static final Map<State, Sweep> RESULTS = new EnumMap<>(State.class);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @ParameterizedTest
    @EnumSource(State.class)
    @Order(1)
    void everyCommandRunsWithoutThrowing(State state, @TempDir Path dir) throws Exception {
        Sweep sweep = new Sweep(state, dir);
        Set<Long> before =
                ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        try (Outside outside = new Outside()) {
            sweep.run();
            sweep.problems.addAll(outside.violations(sweep));
        }
        sweep.problems.addAll(stillRunning(before));
        RESULTS.put(state, sweep);
        assertEquals(
                sweep.registered - EXCLUDED.size(),
                sweep.ran,
                "every registered command runs, but for the " + EXCLUDED.size() + " excluded ones");
        assertEquals(
                List.of(),
                sweep.problems,
                () -> sweep.problems.size() + " problem(s) in state " + state + ":\n"
                        + String.join("\n", sweep.problems));
    }

    @Test
    @Order(2)
    void theListsNameRegisteredCommands() throws Exception {
        Set<String> registered;
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registered = Set.copyOf(ids(registry));
        }
        List<String> stale = new ArrayList<>();
        for (String id : EXCLUDED.keySet()) {
            if (!registered.contains(id)) {
                stale.add(id + " is excluded but is not a registered command");
            }
            if (NATIVE_CHOOSER.contains(id)) {
                stale.add(id + " is both excluded and listed as reaching a native chooser");
            }
        }
        for (String id : NATIVE_CHOOSER) {
            if (!registered.contains(id)) {
                stale.add(id + " is listed as reaching a native chooser but is not a registered command");
            }
        }
        assertEquals(List.of(), stale);

        // The second half needs every state's sweep; run alone, this method has none to look at.
        assumeTrue(RESULTS.keySet().containsAll(List.of(State.values())), "the sweeps did not all run");
        Set<String> reached = new LinkedHashSet<>();
        RESULTS.values().forEach(sweep -> reached.addAll(sweep.reachedChooser));
        List<String> never = NATIVE_CHOOSER.stream()
                .filter(id -> !reached.contains(id))
                .sorted()
                .toList();
        assertEquals(List.of(), never, "listed as reaching a native chooser, but none did in any state");
    }

    /**
     * A chord or a menu entry that names an id no window registers does nothing when used, and says nothing.
     * {@code KeymapsTest} and {@code MenuBarModelTest} check those ids against the {@code command.<id>}
     * message keys, which needs no toolkit but trusts that every such key has a command behind it. This
     * checks them against the registry of a real window.
     */
    @Test
    @Order(3)
    void everyKeymapChordAndMenuEntryNamesARegisteredCommand() throws Exception {
        Set<String> registered;
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registered = Set.copyOf(ids(registry));
        }
        List<String> unbound = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String keymap : KeymapManager.AVAILABLE.keySet()) {
            for (String resource : List.of(keymap + ".json", keymap + ".mac.json")) {
                try (InputStream in = KeymapManager.class.getResourceAsStream("/com/editora/keymaps/" + resource)) {
                    if (in == null) {
                        assertTrue(resource.endsWith(".mac.json"), "missing keymap " + resource);
                        continue; // no macOS variant: the base file serves both
                    }
                    Map<String, String> chords = mapper.readValue(in, new TypeReference<Map<String, String>>() {});
                    chords.forEach((chord, id) -> {
                        if (!registered.contains(id)) {
                            unbound.add(resource + ": " + chord + " is bound to " + id);
                        }
                    });
                }
            }
        }
        for (String id : MenuBarModel.allCommandIds()) {
            if (!registered.contains(id)) {
                unbound.add("menu bar: an entry runs " + id);
            }
        }
        assertEquals(List.of(), unbound, "bound to ids that are not registered commands");
    }

    private static List<String> ids(CommandRegistry registry) throws Exception {
        return FxTestSupport.callOnFx(
                () -> registry.all().stream().map(Command::id).toList());
    }

    /** A command whose last id segment says it flips something: run twice, it exercises both directions. */
    static boolean isToggle(String id) {
        return id.substring(id.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).contains("toggle");
    }

    /** Programs the sweep started that outlived its window (given a moment to finish). */
    private static List<String> stillRunning(Set<Long> before) throws InterruptedException {
        List<ProcessHandle> left = List.of();
        for (int i = 0; i < 100; i++) {
            left = ProcessHandle.current()
                    .descendants()
                    .filter(p -> !before.contains(p.pid()) && p.isAlive())
                    .toList();
            if (left.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        return left.stream()
                .map(p -> "still running after the window closed: "
                        + p.info().commandLine().orElse("pid " + p.pid()))
                .toList();
    }

    /** One state's run over the whole registry. */
    private static final class Sweep {

        private final State state;
        private final Path dir;
        final List<String> problems = new ArrayList<>();
        final Set<String> reachedChooser = new LinkedHashSet<>();
        int registered;
        int ran;

        private FxWindowFixture fx;
        private MainController controller;
        private CommandRegistry registry;
        private EditorArea editorArea;
        private Path file;
        private String text;
        private int selectionStart;
        private int selectionEnd;
        private EditorBuffer buffer;
        private Set<Window> baseline;
        /** The command on the FX thread right now; a window that shows while it is set is blocking it. */
        private volatile String running;

        /** When each command started, to name the one behind a program start or a connection. */
        private final List<Map.Entry<Instant, String>> marks = Collections.synchronizedList(new ArrayList<>());

        private final ConcurrentLinkedQueue<Throwable> offThread = new ConcurrentLinkedQueue<>();

        Sweep(State state, Path dir) {
            this.state = state;
            this.dir = dir;
        }

        /**
         * The command that was running, or had most recently started, at {@code when}. Work a command hands
         * to a worker thread can start a little later, so this names the likely culprit, not a certain one.
         */
        String commandAt(Instant when) {
            String id = "window start-up";
            synchronized (marks) {
                for (Map.Entry<Instant, String> mark : marks) {
                    if (mark.getKey().isAfter(when)) {
                        break;
                    }
                    id = mark.getValue();
                }
            }
            return id;
        }

        void run() throws Exception {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> offThread.add(failure));
            ListChangeListener<Window> blockers = change -> {
                while (change.next()) {
                    for (Window added : change.getAddedSubList()) {
                        // Runs inside the nested event loop of a showAndWait, if that is what the command is
                        // in; once the command has returned, `running` is null and the window is left alone.
                        Platform.runLater(() -> {
                            if (running != null && added.isShowing()) {
                                dismiss(added);
                            }
                        });
                    }
                }
            };
            try (AsyncTestScope async = new AsyncTestScope()) {
                fx = async.own(FxWindowFixture.create(
                        Files.createTempDirectory("editora-fx-test"),
                        shared -> withoutExternalTools(shared.getSettings())));
                setUp();
                FxTestSupport.runOnFx(() -> Window.getWindows().addListener(blockers));
                async.onClose(
                        () -> FxTestSupport.runOnFx(() -> Window.getWindows().removeListener(blockers)));
                List<String> all = ids(registry);
                registered = all.size();
                for (String id : all) {
                    if (EXCLUDED.containsKey(id)) {
                        continue;
                    }
                    ran++;
                    marks.add(Map.entry(Instant.now(), id));
                    for (int round = isToggle(id) ? 2 : 1; round > 0; round--) {
                        runOne(async, id);
                    }
                    try {
                        FxTestSupport.runOnFx(this::restore);
                        async.awaitFx();
                    } catch (Throwable failure) {
                        problems.add(id + ": the window could not be put back afterwards: " + describe(failure));
                    }
                }
                settle(async);
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
            for (Throwable late; (late = offThread.poll()) != null; ) {
                problems.add("uncaught on a worker thread after the sweep: " + describe(late));
            }
        }

        private void runOne(AsyncTestScope async, String id) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            try {
                FxTestSupport.runOnFx(() -> {
                    if (registry.get(id).isEmpty()) {
                        return; // a command another one removed (a saved macro's, say)
                    }
                    if (controller.exports.printJobs != NO_PRINTER) {
                        throw new IllegalStateException("refusing to run " + id + ": printing is not neutralised");
                    }
                    running = id;
                    try {
                        registry.run(id);
                    } catch (Throwable failure) {
                        thrown.set(failure);
                    } finally {
                        running = null;
                    }
                });
                async.awaitFx(); // rethrows what an FX callback of this command left uncaught
            } catch (Throwable failure) {
                thrown.compareAndSet(null, failure);
            }
            Throwable failure = thrown.get();
            if (failure != null && refusedChooser(failure)) {
                reachedChooser.add(id);
                if (!NATIVE_CHOOSER.contains(id)) {
                    problems.add(id + ": reached a native file chooser, and is not listed in NATIVE_CHOOSER");
                }
            } else if (failure != null) {
                problems.add(id + ": " + describe(failure));
            }
            for (Throwable late; (late = offThread.poll()) != null; ) {
                problems.add(id + " (or a command shortly before it), on a worker thread: " + describe(late));
            }
        }

        /** Whether {@code failure} is the Headless platform refusing to show a native chooser. */
        private static boolean refusedChooser(Throwable failure) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                StackTraceElement[] stack = t.getStackTrace();
                if (t instanceof UnsupportedOperationException
                        && stack.length > 0
                        && stack[0].getClassName().equals("com.sun.glass.ui.headless.HeadlessApplication")
                        && stack[0].getMethodName().startsWith("staticCommonDialogs_")) {
                    return true;
                }
            }
            return false;
        }

        /** The failure with the top of its stack, without the wrapper {@code FxTestSupport.runOnFx} adds. */
        private static String describe(Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null
                    && root.getClass() == RuntimeException.class
                    && root.getCause().toString().equals(root.getMessage())) {
                root = root.getCause();
            }
            StringWriter out = new StringWriter();
            root.printStackTrace(new PrintWriter(out));
            return out.toString().lines().limit(12).collect(Collectors.joining("\n      "));
        }

        // --- the window and what it shows -----------------------------------------------------------------

        /**
         * Points every external tool the editor can be told the path of at a file that does not exist, so a
         * run finds the same tools on a developer's machine as on a bare CI runner: none but {@code git}.
         * Left alone, the window probes {@code gh}, {@code rg}, {@code typst}, {@code dot}, … at start-up and
         * after most setting changes, and the GitHub commands would talk to GitHub.
         */
        private static void withoutExternalTools(Settings settings) {
            String missing = Path.of(System.getProperty("java.io.tmpdir"), "editora-sweep-no-such-tool")
                    .toString();
            settings.setGhPath(missing);
            settings.setMmdcPath(missing);
            settings.setMaidPath(missing);
            settings.setDotPath(missing);
            settings.setPlantumlPath(missing);
            settings.setTypstPath(missing);
            settings.setRipgrepCommand(missing);
            settings.setMavenCommand(missing);
            settings.setGradleCommand(missing);
            settings.setNpmCommand(missing);
            settings.setCargoCommand(missing);
            settings.setGoCommand(missing);
            settings.setAgentCommand(missing);
            settings.setPythonDebugCommand(missing);
            settings.setHtmlPreviewBrowser(missing);
            settings.setUpdateCheck(false); // the start-up check asks the release server
            // Off by default today. Said here so that a changed default cannot make the sweep start an agent,
            // a language server or an elevation prompt; the view.toggle* commands still switch each on and off.
            settings.setAdminSave(false);
            settings.setAgentSupport(false);
            settings.setAiEnabled(false);
            settings.setMcpSupport(false);
            settings.setPluginSupport(false);
            settings.setLspSupport(false);
            settings.setDebugSupport(false);
            settings.setSyncEnabled(false);
        }

        private void setUp() throws Exception {
            controller = fx.controller;
            switch (state) {
                case EMPTY -> {}
                case UNTITLED -> {
                    text = TEXT;
                    select("fooBar");
                }
                case TEXT -> {
                    file = Files.writeString(dir.resolve("notes.txt"), TEXT);
                    text = TEXT;
                    select("fooBar");
                }
                case MARKDOWN -> {
                    file = Files.writeString(dir.resolve("guide.md"), MARKDOWN);
                    text = MARKDOWN;
                    select("prose");
                }
                case GIT_PROJECT -> {
                    GitTestRepo repo = GitTestRepo.init(dir);
                    repo.write("work.txt", TEXT.replace("duplicate line\nduplicate line\n", "one line\n"));
                    repo.commitAll("first");
                    file = repo.write("work.txt", TEXT);
                    text = TEXT;
                    select("fooBar");
                    Project project = fx.shared.projects().createOrGet("sweep", repo.root);
                    FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(project));
                    FxTestSupport.drainFx();
                    controller = controllerFor(project.id());
                }
            }
            registry = FxTestSupport.field(controller, "registry");
            editorArea = FxTestSupport.field(controller, "editorArea");
            FxTestSupport.runOnFx(() -> {
                neutralisePrinting();
                if (text != null) {
                    open();
                }
                baseline = Set.copyOf(Window.getWindows());
            });
            FxTestSupport.drainFx();
        }

        private void select(String word) {
            selectionStart = text.indexOf(word);
            selectionEnd = selectionStart + word.length();
        }

        private MainController controllerFor(String key) {
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            for (Object holder : holders) {
                if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                    return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                }
            }
            throw new IllegalStateException("no window for project " + key);
        }

        /** No window of this fixture may reach a real printer: every one answers "there is no printer". */
        private void neutralisePrinting() {
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            for (Object holder : holders) {
                MainController owned = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                owned.exports.printJobs = NO_PRINTER;
                owned.exports.noPrinterPrompt = alert -> Optional.empty();
            }
        }

        private void open() {
            try {
                buffer = new EditorBuffer();
                if (file != null) {
                    Files.writeString(file, text);
                    buffer.setPath(file);
                    buffer.setContent(text);
                    buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                }
                FxTestSupport.call(
                        controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
                if (file == null) {
                    buffer.getArea().replaceText(text); // typed, not loaded: the buffer has unsaved changes
                }
                buffer.getArea().selectRange(selectionStart, selectionEnd);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Puts the window back to the state's starting point, so one command does not decide the next one's. */
        private void restore() {
            neutralisePrinting(); // a window a command opened is a window too
            for (Window window : List.copyOf(Window.getWindows())) {
                if (!baseline.contains(window) && window.isShowing()) {
                    dismiss(window);
                }
            }
            OverlayHost overlay = FxTestSupport.field(controller, "overlayHost");
            if (overlay.isShowing()) {
                overlay.hide();
            }
            for (int i = 0; i < 4; i++) {
                FxTestSupport.invoke(controller, "cancel"); // completion, palette, find bar, macro recording
            }
            List<Tab> tabs = editorArea.tabs();
            if (text == null) {
                if (tabs.size() != 1 || tabs.get(0).getUserData() instanceof EditorBuffer) {
                    closeEveryTab();
                    FxTestSupport.invoke(controller, "showWelcome");
                }
                return;
            }
            boolean intact = tabs.size() == 1
                    && tabs.get(0).getUserData() == buffer
                    && buffer.isDirty() == (file == null)
                    && !buffer.isNarrowed()
                    && !buffer.isReadOnly()
                    && text.equals(buffer.getContent());
            if (intact) {
                buffer.getArea().selectRange(selectionStart, selectionEnd);
            } else {
                closeEveryTab();
                open();
            }
        }

        private void closeEveryTab() {
            FxTestSupport.invoke(controller, "unsplitEditorGroups");
            for (Tab tab : List.copyOf(editorArea.tabs())) {
                if (tab.getUserData() instanceof EditorBuffer open) {
                    open.markClean(); // so that closing it asks nothing
                }
            }
            FxTestSupport.invoke(controller, "closeAllTabs");
            for (Tab tab : List.copyOf(editorArea.tabs())) {
                editorArea.remove(tab); // a pinned tab, which Close All leaves
            }
        }

        /** Answers a dialog with the choice that changes nothing, and hides any other kind of window. */
        private static void dismiss(Window window) {
            if (window instanceof Stage dialog
                    && dialog.getScene() != null
                    && dialog.getScene().getRoot() instanceof DialogPane pane) {
                List<ButtonType> types = pane.getButtonTypes();
                ButtonType choice = types.stream()
                        .filter(type -> type.getButtonData().isCancelButton())
                        .findFirst()
                        .or(() -> types.stream()
                                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.NO)
                                .findFirst())
                        .orElse(types.size() == 1 ? types.get(0) : null);
                if (choice != null && pane.lookupButton(choice) instanceof Button button) {
                    button.fire();
                    return;
                }
            }
            window.hide();
        }

        /** Lets the work the commands started on worker threads finish, so its failures are counted here. */
        private void settle(AsyncTestScope async) {
            try {
                if (state == State.GIT_PROJECT) {
                    GitCoordinator git = FxTestSupport.field(controller, "git");
                    async.awaitWorker(FxTestSupport.field(git.service(), "exec"));
                }
                async.awaitFx();
                FxTestSupport.runOnFx(this::restore);
                async.awaitFx();
            } catch (Throwable failure) {
                problems.add("after the sweep: " + describe(failure));
            }
        }
    }

    /**
     * What the sweep did outside its own JVM and temporary directories.
     *
     * <p>Connections: while this is open the JVM's default {@link ProxySelector} sends everything that is
     * not bound for this machine to a proxy nobody listens on, and notes the address. JDK Flight Recorder
     * also reports bytes read from or written to another host, which covers a client that was built before
     * this selector was in place.
     *
     * <p>Programs: Flight Recorder reports every process the JVM starts, with its command line and working
     * directory, however the application starts it.
     */
    private static final class Outside extends ProxySelector implements AutoCloseable {

        private record Seen(Instant when, String what) {}

        private final ProxySelector previous = ProxySelector.getDefault();
        private final RecordingStream stream = new RecordingStream();
        private final List<Seen> programs = Collections.synchronizedList(new ArrayList<>());
        private final List<Seen> connections = Collections.synchronizedList(new ArrayList<>());
        private final Path sourceTree = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        Outside() {
            ProxySelector.setDefault(this);
            stream.enable("jdk.ProcessStart");
            stream.onEvent("jdk.ProcessStart", this::program);
            for (String traffic : List.of("jdk.SocketRead", "jdk.SocketWrite")) {
                stream.enable(traffic).withoutThreshold();
                stream.onEvent(traffic, this::traffic);
            }
            stream.startAsync();
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host == null || local(host)) {
                return List.of(Proxy.NO_PROXY);
            }
            connections.add(new Seen(Instant.now(), "tried to connect to " + uri));
            InetSocketAddress nobody = new InetSocketAddress(InetAddress.getLoopbackAddress(), 9);
            boolean rawSocket = "socket".equalsIgnoreCase(uri.getScheme());
            return List.of(new Proxy(rawSocket ? Proxy.Type.SOCKS : Proxy.Type.HTTP, nobody));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // expected: nothing listens on the stand-in proxy
        }

        private static boolean local(String host) {
            String h = host.toLowerCase(Locale.ROOT);
            return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]");
        }

        private void program(RecordedEvent event) {
            String directory = event.getString("directory");
            String command = event.getString("command");
            String problem = programProblem(command, directory);
            if (problem != null) {
                programs.add(new Seen(event.getStartTime(), problem + ": " + command + " [in " + directory + "]"));
            }
        }

        /**
         * Why this program should not have been started, or null when it is one the sweep expects: {@code git}
         * in a directory outside the source tree, a version probe of a tool the editor cannot be given the
         * path of ({@code git --version}, {@code pkexec --version}, {@code java -version}), and the one
         * login-shell {@code PATH} query.
         */
        private String programProblem(String command, String directory) {
            if (command == null) {
                return "started an unknown program";
            }
            if (command.endsWith(" --version") || command.endsWith(" -version")) {
                return null;
            }
            if (command.contains("__EDITORA_PATH_BEGIN__")) {
                return null;
            }
            String program = command.strip().split("\\s+", 2)[0];
            String name = program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1);
            if (!name.equals("git") && !name.equals("git.exe")) {
                return "started a program";
            }
            if (directory == null || Path.of(directory).toAbsolutePath().startsWith(sourceTree)) {
                return "ran git outside the test's own repository";
            }
            return null;
        }

        private void traffic(RecordedEvent event) {
            String address = event.getString("address");
            String host = event.getString("host");
            if ((address == null || !local(address)) && (host == null || !local(host))) {
                connections.add(new Seen(
                        event.getStartTime(),
                        "exchanged data with " + host + " " + address + ":" + event.getInt("port")));
            }
        }

        /** Stops recording and names what should not have happened, each with the command probably behind it. */
        List<String> violations(Sweep sweep) {
            stream.stop(); // returns once every event up to now has been delivered
            Set<String> out = new LinkedHashSet<>();
            for (List<Seen> seen : List.of(programs, connections)) {
                synchronized (seen) {
                    for (Seen s : seen) {
                        out.add(sweep.commandAt(s.when()) + " (or a command shortly before it) " + s.what());
                    }
                }
            }
            return List.copyOf(out);
        }

        @Override
        public void close() {
            ProxySelector.setDefault(previous);
            stream.close();
        }
    }
}
