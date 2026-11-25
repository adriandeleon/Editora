package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javafx.application.Platform;

import com.editora.config.PathKeys;
import com.editora.dap.DapServerRegistry;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug's attaches: Attach (a JVM's or a Node.js inspector's address, remembered per project), Reattach, Attach to
 * Process (a Java, Node.js, Python, Go or native program on this machine), a Debug Test run's suspended JVM, and Open
 * Core Dump. Also the pure helpers they use: which open file routes an attach, the remembered addresses, a process
 * row's text. Part of {@link DebugCoordinator}.
 */
final class DebugAttach {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    DebugAttach(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Attaches to an already-suspended JVM on {@code host:port} without prompting — used by the Test Results
     * "Debug Test" action, where Surefire/Gradle forked the test JVM suspended and printed its JDWP port.
     * {@code anchorFile} names the debug session and anchors breakpoint resolution.
     */
    void attachToPort(Path anchorFile, String attachHost, int port) {
        attachToPort(anchorFile, attachHost, port, null);
    }

    /** The session waiting for a test run's JVMs is no longer: the attach was refused there. */
    private void releaseAttachWaiter() {
        if (d.attachWaiter == d.s()) {
            d.attachWaiter = null;
        }
    }

    /**
     * A Debug Test run's JVM: attached in the session waiting for that run ({@link DebugCoordinator#attachWaiter}),
     * whichever is focused. Only the test run's own: a run configuration's or Run's JVM attaches where it is started.
     */
    void attachTestJvm(Path anchorFile, String attachHost, int port, @Nullable Runnable stopOwner) {
        DebugSession waiter = d.attachWaiter;
        if (waiter != null && d.sessions().all().contains(waiter)) {
            d.inSession(waiter, () -> attachToPort(anchorFile, attachHost, port, stopOwner));
        } else {
            attachToPort(anchorFile, attachHost, port, stopOwner);
        }
    }

    /**
     * As above; {@code stopOwner} ends the test or build run that started the JVM: Stop on this session runs
     * it after detaching, so Stop ends the program as it does every other session's — detaching alone left
     * the test or app running on (review A4).
     */
    void attachToPort(Path anchorFile, String attachHost, int port, @Nullable Runnable stopOwner) {
        if (anchorFile != null
                && !d.factsKnown( // the anchor's project routes the attach: looked at off the FX thread (PERF-01)
                        "debug-attach-look",
                        () -> JavaProjectRoot.find(anchorFile),
                        () -> attachToPort(anchorFile, attachHost, port, stopOwner))) {
            return;
        }
        if (d.s().relaunch == DebugCoordinator.ONE_SHOT_ATTACH && d.dap().isActive()) {
            d.s().queuedAttaches.add(() -> attachToPort(anchorFile, attachHost, port, stopOwner));
            d.host.setStatus(tr("status.debug.attachQueued", port));
            return;
        }
        if (d.s().pendingAttachStop != null) {
            DebugSession run = d.attachWaiter; // the run's next forks still attach here, until the run ends
            d.setPendingAttach(null); // the wait is over: from here Stop goes through the session (ownerStop)
            d.attachWaiter = run;
        }
        d.s().ownerStop = stopOwner;
        // "Attaching the debugger on port …" would outlive it (review 2, A105)
        d.s().announceStart = stopOwner != null;
        String jvmLanguage = DebugCoordinator.jvmAdapterLanguage(anchorFile);
        if (jvmLanguage != null) {
            // A Kotlin (or Groovy) program of a build: Editora's JVM adapter, which binds by source name.
            if (!d.debugEffectiveFor(jvmLanguage, anchorFile)) {
                d.host.setStatus(d.unavailableMessage(jvmLanguage, anchorFile));
                releaseAttachWaiter(); // refused: the run attaches nothing here
                return;
            }
            d.ops.openToolWindow();
            d.nameSession(anchorFile.getFileName().toString());
            d.s().relaunch = DebugCoordinator.ONE_SHOT_ATTACH;
            d.withClosedBreakpoints(() -> d.dap().startJvmAttach(anchorFile, jvmLanguage, attachHost, port));
            return;
        }
        // On a remote anchor's host the adapter attaches from there: "localhost" is the host itself.
        if (!d.debugEffectiveFor("java", anchorFile)) {
            d.host.setStatus(d.unavailableMessage("java", anchorFile));
            releaseAttachWaiter(); // refused: the run attaches nothing here
            return;
        }
        d.ops.openToolWindow();
        d.nameSession(anchorFile == null ? "" : anchorFile.getFileName().toString());
        // The anchor names the session, but the adapter is started through jdtls, which only answers for a
        // document it has open: a test class found on disk (Debug Test with no tab for it) has no session.
        Path routing = attachRouting(anchorFile);
        d.lsp.ensureManaged(routing); // an open tab whose server start was deferred
        d.s().relaunch = DebugCoordinator.ONE_SHOT_ATTACH; // the run that owns this JVM has to be started again instead
        d.withClosedBreakpoints(() -> d.dap().startAttach(routing, attachHost, port));
    }

    /** The open Java file an attach anchored at {@code anchor} is routed through — see {@link #attachRouting}. */
    private @Nullable Path attachRouting(Path anchor) {
        List<Path> openJava = new ArrayList<>();
        d.host.forEachBuffer(b -> {
            // Java files whose tools run where the anchor's do: the attach goes through that machine's jdtls.
            if (b.getPath() != null
                    && "java".equals(b.getLanguage())
                    && (anchor == null
                            ? d.host.isLocalBuffer(b) && com.editora.process.ExecTargets.runsHere(b.getPath())
                            : com.editora.process.ExecTargets.forPath(b.getPath())
                                    .id()
                                    .equals(com.editora.process.ExecTargets.forPath(anchor)
                                            .id()))) {
                openJava.add(b.getPath());
            }
        });
        return DebugAttach.attachRouting(anchor, openJava, JavaProjectRoot.find(anchor), d.host::isLspManaged);
    }

    /** What Attach offers when this project has attached nowhere yet: the JDWP default. */
    private static final String DEFAULT_ATTACH_ADDRESS = "localhost:5005";

    /** The JVM adapter language of the active file Attach starts from, or null when it cannot (it says why). */
    private @Nullable String attachLanguage(@Nullable EditorBuffer b) {
        if (b == null || b.getPath() == null) {
            d.host.setStatus(tr("status.debug.saveFirst"));
            return null;
        }
        String jvmLanguage = DebugCoordinator.jvmAdapterLanguage(b.getPath());
        if (jvmLanguage == null && !"java".equals(b.getLanguage())) {
            d.host.setStatus(tr("status.debug.attachLanguages"));
            return null;
        }
        String adapterLanguage = jvmLanguage != null ? jvmLanguage : "java";
        if (!d.debugEffectiveFor(adapterLanguage, b.getPath())) { // a remote file attaches from its host (review A9)
            d.host.setStatus(d.unavailableMessage(adapterLanguage, b.getPath()));
            return null;
        }
        return adapterLanguage;
    }

    /**
     * Debug ▸ Attach: picks an address this project attached to before (most recent first), or asks for another,
     * pre-filled with the most recent one.
     */
    void debugAttach() {
        EditorBuffer b = d.host.activeBuffer();
        if (b != null && b.isHtml()) {
            d.browser.debugAttachToBrowser(); // a page's scripts run in a browser, not in Node.js
            return;
        }
        if (b != null && "javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))) {
            attachToNode(b); // review D7: a Node.js inspector, not a JVM
            return;
        }
        String language = attachLanguage(b);
        if (b == null || language == null) {
            return;
        }
        List<String> earlier = d.ops.debugAttachTargets();
        if (earlier.isEmpty()) {
            promptAttachAddress(b, language, DEFAULT_ATTACH_ADDRESS);
            return;
        }
        List<String> rows = new ArrayList<>(earlier);
        rows.add(""); // "Another address…"
        rows.add(PROCESS_ROW); // "A program on this machine…"
        QuickOpen<String> picker = new QuickOpen<>(
                tr("dialog.debug.attachTitle"),
                tr("debug.attach.pickPrompt"),
                () -> rows,
                a -> a.isEmpty() ? tr("debug.attach.another") : PROCESS_ROW.equals(a) ? tr("debug.attach.process") : a,
                a -> "",
                a -> {
                    if (a == null) {
                        return;
                    }
                    if (a.isEmpty()) {
                        promptAttachAddress(b, language, earlier.get(0));
                    } else if (PROCESS_ROW.equals(a)) {
                        debugAttachToProcess();
                    } else {
                        attachTo(b, language, a);
                    }
                });
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /** The Attach picker's row that opens Attach to Process (no address can look like it). */
    private static final String PROCESS_ROW = "\u0000process";

    /**
     * Debug: Attach to Process — the Java, Node.js and Python programs running on this machine, those listening for
     * a debugger first (a JVM's JDWP agent, Node's inspector, debugpy's {@code --listen}); picking one attaches the
     * right debugger to it. One started without debugging says what to start it with.
     */
    void debugAttachToProcess() {
        d.host.setStatus(tr("status.debug.scanningProcesses"));
        Thread.ofPlatform().daemon().name("editora-process-scan").start(() -> {
            List<com.editora.dap.LocalProcesses.Target> found;
            try {
                found = com.editora.dap.LocalProcesses.scan();
            } catch (RuntimeException e) {
                found = List.of();
            }
            List<com.editora.dap.LocalProcesses.Target> targets = found;
            Platform.runLater(() -> showProcessPicker(targets)); // for the session shown when the scan ends
        });
    }

    private void showProcessPicker(List<com.editora.dap.LocalProcesses.Target> targets) {
        d.host.setStatus(null);
        QuickOpen<com.editora.dap.LocalProcesses.Target> picker = new QuickOpen<>(
                tr("debug.attachProcess.title"),
                tr("debug.attachProcess.prompt"),
                () -> targets,
                t -> tr("debug.attachProcess.row", t.name(), t.pid()),
                DebugAttach::processDetail,
                t -> {
                    if (t != null) {
                        attachToProcess(t);
                    }
                });
        if (targets.isEmpty()) {
            picker.setPlaceholder(tr("debug.attachProcess.none"));
        }
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /** Attaches the debugger of {@code t}'s kind to it, or says what to start it with when it is not listening. */
    void attachToProcess(com.editora.dap.LocalProcesses.Target t) {
        if (!t.attachable()) {
            d.host.setStatus(tr("status.debug.processNotListening", t.name(), DebugAttach.startFlag(t.kind())));
            return;
        }
        switch (t.kind()) {
            case JAVA -> {
                EditorBuffer anchor = jvmAnchor();
                if (anchor == null) {
                    d.host.setStatus(tr("status.debug.attachNeedsJavaFile"));
                    return;
                }
                String language = attachLanguage(anchor);
                if (language != null) {
                    attachTo(anchor, language, t.address());
                }
            }
            case NODE -> {
                if (!d.debugEffectiveFor("javascript")) {
                    d.host.setStatus(d.unavailableMessage("javascript", null));
                    return;
                }
                EditorBuffer b = d.host.activeBuffer();
                Path dir = b != null && b.getPath() != null ? b.getPath().getParent() : d.ops.projectRoot();
                String label = t.name() + " (" + t.pid() + ")";
                d.ops.openToolWindow();
                d.nameSession(label);
                d.s().relaunch = null; // an attach is re-attached as it was
                d.withClosedBreakpoints(() -> d.dap()
                        .startJsAttach(
                                t.host(),
                                t.port(),
                                label,
                                dir,
                                List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                false));
            }
            case PYTHON -> {
                if (!d.debugSupportEnabled()) {
                    d.host.setStatus(tr("statusbar.tip.debugDisabled"));
                    return; // the program brings its own debugpy: none is needed here
                }
                EditorBuffer b = d.host.activeBuffer();
                Path active = b != null && "python".equals(b.getLanguage()) ? b.getPath() : null;
                Path root = d.ops.projectRoot();
                Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
                d.ops.openToolWindow();
                d.nameSession(t.name() + " (" + t.pid() + ")");
                d.s().relaunch = null;
                d.withClosedBreakpoints(() -> d.dap().startPythonAttach(anchor, t.host(), t.port()));
            }
            case GO -> attachToGo(t.pid(), t.name());
            case RUST -> attachToNative(t.pid(), t.name(), com.editora.dap.DebugLanguages.RUST);
            case NATIVE -> attachToNative(t.pid(), t.name(), com.editora.dap.DebugLanguages.CPP);
        }
    }

    /**
     * Attaches CodeLLDB to native program {@code pid} of this machine (Rust's or C and C++'s, {@code language}): Stop
     * detaches and leaves it running. The session is anchored at the active file of that language, else the project.
     */
    private void attachToNative(long pid, String name, com.editora.dap.DebugLanguage language) {
        if (!d.debugEffectiveFor(language.id())) {
            d.host.setStatus(d.unavailableMessage(language.id(), null));
            return;
        }
        EditorBuffer b = d.host.activeBuffer();
        Path active = b != null && language.editorLanguages().contains(b.getLanguage()) ? b.getPath() : null;
        Path root = d.ops.projectRoot();
        Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
        java.util.Map<String, Object> attach = com.editora.dap.NativeBuild.attach(pid, language.id());
        d.ops.openToolWindow();
        d.nameSession(name + " (" + pid + ")");
        Runnable start = () -> d.withClosedBreakpoints(() -> d.dap().startExternal(anchor, language, attach));
        d.s().relaunch = start; // attaches again
        start.run();
    }

    /** The file a core dump or its program is chosen with: the open dialog, or what a test answers. */
    java.util.function.@Nullable Function<javafx.stage.FileChooser, java.io.@Nullable File> chooseFile;

    private @Nullable Path chooseFile(String title, @Nullable Path folder) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(title);
        ChooserStart.set(chooser, folder); // no look at the disk on the FX thread first (PERF-01, Pauta 6.2.0)
        java.io.File chosen = ChooserStart.show(
                chooser, c -> chooseFile != null ? chooseFile.apply(c) : c.showOpenDialog(d.host.window()));
        return chosen == null ? null : chosen.toPath();
    }

    /**
     * Debug: Open Core Dump… — asks for a core dump, finds the program it is the memory of (a Linux core names it;
     * otherwise, or when that file is gone, asks for it too), and opens the core in the program's debugger: Delve
     * for Go, CodeLLDB for C, C++ and Rust. The session shows the crashed thread's stack, the other threads and
     * their variables; nothing runs ({@link com.editora.dap.CoreDumps}).
     */
    void debugOpenCoreDump() {
        Path root = d.ops.projectRoot();
        Path core = chooseFile(tr("debug.core.chooseCore"), root);
        if (core == null) {
            return;
        }
        d.host.setStatus(tr("status.debug.core.reading", String.valueOf(core.getFileName())));
        Thread.ofPlatform().daemon().name("editora-core-dump").start(() -> {
            Path named = com.editora.dap.CoreDumps.programOf(core);
            boolean there = named != null && Files.isRegularFile(named); // here, off the FX thread (PERF-01)
            Platform.runLater(() -> {
                d.host.setStatus(null);
                Path program = there
                        ? named
                        : chooseFile(
                                tr("debug.core.chooseProgram", String.valueOf(core.getFileName())), core.getParent());
                if (program != null) {
                    openCoreDump(core, program);
                }
            });
        });
    }

    /** Opens {@code core}, the memory of {@code program}, in the debugger of the language {@code program} is in. */
    void openCoreDump(Path core, Path program) {
        Thread.ofPlatform().daemon().name("editora-core-dump").start(() -> {
            com.editora.dap.DebugLanguage language = com.editora.dap.CoreDumps.languageOf(program);
            Platform.runLater(() -> openCoreDump(core, program, language));
        });
    }

    private void openCoreDump(Path core, Path program, com.editora.dap.DebugLanguage language) {
        if (!d.debugEffectiveFor(language.id())) {
            d.host.setStatus(d.unavailableMessage(language.id(), null));
            return;
        }
        EditorBuffer b = d.host.activeBuffer();
        Path active = b != null && language.editorLanguages().contains(b.getLanguage()) ? b.getPath() : null;
        Path root = d.ops.projectRoot();
        Path anchor = active != null ? active : root != null ? root : program;
        java.util.Map<String, Object> launch = com.editora.dap.CoreDumps.launch(language, program, core);
        d.ops.openToolWindow();
        d.nameSession(
                tr("debug.core.session", String.valueOf(core.getFileName()), String.valueOf(program.getFileName())));
        d.s().coreProgram = program;
        Runnable start = () -> d.withClosedBreakpoints(() -> d.dap().startExternal(anchor, language, launch));
        d.s().relaunch = start; // opens the core again
        start.run();
    }

    /**
     * Attaches Delve to Go program {@code pid} of this machine ({@code dlv attach}): Stop detaches and leaves it running.
     * The session is anchored at the active Go file, else the project; breakpoints are its Go files'.
     */
    private void attachToGo(long pid, String name) {
        if (!d.debugEffectiveFor("go")) {
            d.host.setStatus(d.unavailableMessage("go", null));
            return;
        }
        EditorBuffer b = d.host.activeBuffer();
        Path active = b != null && "go".equals(b.getLanguage()) ? b.getPath() : null;
        Path root = d.ops.projectRoot();
        Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
        java.util.Map<String, Object> attach = com.editora.dap.GoLaunch.attach(pid);
        d.ops.openToolWindow();
        d.nameSession(name + " (" + pid + ")");
        Runnable start = () ->
                d.withClosedBreakpoints(() -> d.dap().startExternal(anchor, com.editora.dap.DebugLanguages.GO, attach));
        d.s().relaunch = start; // attaches again
        start.run();
    }

    /**
     * The file a JVM attach is made from: the active one when it is Java (or another JVM language), else the first
     * such tab — java-debug resolves the attached program's classes through that file's project.
     */
    private @Nullable EditorBuffer jvmAnchor() {
        EditorBuffer active = d.host.activeBuffer();
        if (isJvmFile(active)) {
            return active;
        }
        EditorBuffer[] found = new EditorBuffer[1];
        d.host.forEachBuffer(b -> {
            if (found[0] == null && isJvmFile(b)) {
                found[0] = b;
            }
        });
        return found[0];
    }

    private static boolean isJvmFile(@Nullable EditorBuffer b) {
        return b != null
                && b.getPath() != null
                && ("java".equals(b.getLanguage()) || DebugCoordinator.jvmAdapterLanguage(b.getPath()) != null);
    }

    /**
     * Debug: Reattach — attaches again to the address this project attached to last (Visual Studio's Reattach to
     * Process), without asking.
     */
    void debugReattach() {
        EditorBuffer b = d.host.activeBuffer();
        if (b != null && "javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))) {
            attachToNode(b);
            return;
        }
        String language = attachLanguage(b);
        if (b == null || language == null) {
            return;
        }
        List<String> earlier = d.ops.debugAttachTargets();
        if (earlier.isEmpty()) {
            d.host.setStatus(tr("status.debug.noEarlierAttach"));
            return;
        }
        attachTo(b, language, earlier.get(0));
    }

    private void promptAttachAddress(EditorBuffer b, String language, String initial) {
        d.host.promptText(
                tr("dialog.debug.attachTitle"),
                tr("dialog.debug.attachContent"),
                initial,
                input -> attachTo(b, language, input == null ? "" : input));
    }

    /** Attaches the {@code language} adapter to {@code address} ({@code host:port}, or a port on localhost). */
    private void attachTo(EditorBuffer b, String language, String address) {
        String jvmLanguage = "java".equals(language) ? null : language;
        String text = address.trim();
        String hostName = "localhost";
        String portStr = text;
        int colon = text.lastIndexOf(':');
        if (colon >= 0) {
            hostName = text.substring(0, colon);
            portStr = text.substring(colon + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            d.host.setStatus(tr("status.debug.badAddress", text));
            return;
        }
        Path file = b.getPath(); // checked by attachLanguage; a saved buffer keeps a path
        if (file == null) {
            return;
        }
        // Offered first next time, in this project (review 2, A109: it was only kept for the window).
        d.ops.persistDebugAttachTargets(DebugAttach.rememberAttachTarget(d.ops.debugAttachTargets(), text));
        d.ops.openToolWindow();
        d.nameSession(file.getFileName().toString());
        String attachHost = hostName;
        d.s().relaunch = null; // an attach is re-attached as it was
        if (jvmLanguage != null) {
            d.withClosedBreakpoints(() -> d.dap().startJvmAttach(file, jvmLanguage, attachHost, port));
        } else {
            d.withClosedBreakpoints(() -> d.dap().startAttach(file, attachHost, port));
        }
    }

    /**
     * Debug ▸ Attach on a JavaScript or TypeScript file: asks for the inspector's {@code host:port} (a Node.js started
     * with {@code --inspect} or {@code --inspect-brk}) and attaches vscode-js-debug to it, with every breakpoint.
     * Restart attaches again; Stop detaches.
     */
    private void attachToNode(EditorBuffer b) {
        if (!d.debugEffectiveFor("javascript")) {
            d.host.setStatus(d.unavailableMessage("javascript", null));
            return;
        }
        String defaultAddress = "localhost:" + com.editora.dap.JsLaunch.DEFAULT_INSPECTOR_PORT;
        d.host.promptText(
                tr("dialog.debug.attachNodeTitle"), tr("dialog.debug.attachNodeContent"), defaultAddress, input -> {
                    if (input == null) {
                        return; // cancelled
                    }
                    var address = com.editora.dap.JsLaunch.parseAddress(input);
                    if (address.isEmpty()) {
                        d.host.setStatus(tr("status.debug.badAddress", input.strip()));
                        return;
                    }
                    String label = address.get().host() + ":" + address.get().port();
                    Path dir = b.getPath() != null ? b.getPath().getParent() : d.ops.projectRoot();
                    d.ops.openToolWindow();
                    d.nameSession(label);
                    d.s().relaunch = null; // an attach is re-attached as it was
                    d.withClosedBreakpoints(() -> d.dap()
                            .startJsAttach(
                                    address.get().host(),
                                    address.get().port(),
                                    label,
                                    dir,
                                    List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                    false));
                });
    }

    /**
     * Pure: the file whose jdtls session starts the debug adapter for an attach. The anchor itself when it is
     * open in a tab; otherwise an open Java file of the anchor's project (one already on the server first),
     * else any open Java file already on the server, else the anchor — which then fails with the precise
     * "no language server for file" rather than attaching through an unrelated project.
     */
    static @Nullable Path attachRouting(
            Path anchor, List<Path> openJavaFiles, @Nullable Path projectRoot, Predicate<Path> managed) {
        if (anchor == null) {
            return openJavaFiles.stream().filter(managed).findFirst().orElse(null);
        }
        Path sameProject = null;
        Path anyManaged = null;
        for (Path open : openJavaFiles) {
            if (PathKeys.sameNormalized(open, anchor)) {
                return anchor;
            }
            boolean onServer = managed.test(open);
            if (projectRoot != null && open.toAbsolutePath().normalize().startsWith(projectRoot)) {
                if (sameProject == null || (onServer && !managed.test(sameProject))) {
                    sameProject = open;
                }
            } else if (onServer && anyManaged == null) {
                anyManaged = open;
            }
        }
        return sameProject != null ? sameProject : anyManaged != null ? anyManaged : anchor;
    }

    /** The most addresses Attach remembers per project. */
    static final int MAX_ATTACH_TARGETS = 8;

    /** Pure: {@code address} first, then the earlier ones without it, at most {@link #MAX_ATTACH_TARGETS}. */
    static List<String> rememberAttachTarget(List<String> earlier, String address) {
        List<String> out = new ArrayList<>();
        out.add(address);
        for (String a : earlier) {
            if (out.size() >= MAX_ATTACH_TARGETS) {
                break;
            }
            if (a != null && !a.isBlank() && !a.equalsIgnoreCase(address)) {
                out.add(a);
            }
        }
        return out;
    }

    /** A row's second line: which debugger, and where it listens — or what to start the program with. Pure. */
    static String processDetail(com.editora.dap.LocalProcesses.Target t) {
        String kind = tr("debug.attachProcess.kind." + t.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (t.kind().byPid()) {
            return tr(
                    t.kind() == com.editora.dap.LocalProcesses.Kind.GO
                            ? "debug.attachProcess.byPid"
                            : "debug.attachProcess.byPidLldb",
                    kind);
        }
        return t.attachable()
                ? tr("debug.attachProcess.listening", kind, t.address())
                : tr("debug.attachProcess.notListening", kind, startFlag(t.kind()));
    }

    /** What a program of {@code kind} is started with so a debugger can attach. Pure. */
    static String startFlag(com.editora.dap.LocalProcesses.Kind kind) {
        return switch (kind) {
            case JAVA -> "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005";
            case NODE -> "--inspect";
            case PYTHON -> "-m debugpy --listen 5678";
            case GO, RUST, NATIVE -> "";
        };
    }
}
