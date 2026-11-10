package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * What every debug session of a window shares: the Debugging settings, where detection found each adapter, the
 * exception filters chosen per language, the window's breakpoints. One {@link DapManager} debugs one session; the
 * window's managers share one of these ({@link DapManager#newSession}), so a session started while another runs
 * starts with the same settings, and a setting changed reaches every one's next session. Mutable, package-private:
 * {@link DapManager} reads and writes it; fields are FX-thread unless volatile.
 */
final class DapSettings {

    boolean enabled;
    String pluginPath = "";
    List<String> bundlePaths = List.of();

    // Python (debugpy, stdio) + JavaScript (vscode-js-debug, socket) — the standalone adapters.
    boolean pythonEnabled;
    /** The interpreter ("" ⇒ python3 on the PATH). */
    String pythonCommand = "";
    /** The resolved PYTHONPATH folder for debugpy (null ⇒ rely on the interpreter). */
    @Nullable
    Path debugpyDir;

    boolean pythonAvailable;
    boolean jsEnabled;
    /** The configured dapDebugServer.js path or folder ("" ⇒ found by itself). */
    String jsPath = "";
    /** The resolved dapDebugServer.js (null ⇒ not found). */
    @Nullable
    Path jsServer;

    boolean jsAvailable;

    /**
     * The external adapters detection found, by adapter language: the executable each is started with (a configured
     * path, or the command found on the PATH). See {@link DapManager#configureExternal}.
     */
    final Map<String, String> externalLocated = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Folders put first on an external adapter's PATH, by adapter language: the Go toolchain Settings names, which
     * Delve builds the program with. Empty: the adapter finds its tools on the PATH Editora's processes get.
     */
    final Map<String, List<String>> externalPathDirs = new java.util.concurrent.ConcurrentHashMap<>();

    /** The Debugging setting's Node.js ("" = automatic), see {@link com.editora.run.NodeRuntimes}. */
    volatile String nodePath = "";

    /** Whether a Java launch asks the adapter to let Editora start the program (see {@link DapManager#setProgramConsole}). */
    boolean programConsole;

    /** Where a program Editora starts runs instead of the Debug console (a Terminal tab); null for the console. */
    @Nullable
    ProgramTerminal programTerminal;

    /** The window's breakpoints in open and closed files, sent to the adapter on the {@code initialized} event. */
    Supplier<List<DapModels.FileBreakpoints>> breakpointsSupplier = List::of;

    /** The exception filters chosen per adapter language; a language with none starts with the adapter's defaults. */
    final Map<String, List<String>> exceptionChoices = new java.util.HashMap<>();

    /** Mute Breakpoints also mutes the exception stops (review 2, B109), as IntelliJ's Mute does. */
    boolean exceptionsMuted;

    /**
     * Set when the running jdtls already advertises the java-debug commands — some distributions (e.g. Homebrew's
     * jdtls) ship and auto-load the plugin, so there is no jar to find and nothing to inject, yet debugging works.
     * Pushed by the controller from the LSP capabilities (#711).
     */
    volatile boolean serverProvidesJavaDebug;

    /** What java-debug is told before each Java session (step filters, statics, exception types). */
    JavaDebugSettings javaDebugSettings = JavaDebugSettings.defaults();

    /** Numbers are shown in hexadecimal. */
    volatile boolean hexValues;

    /** The method breakpoints and field watchpoints in force; empty while breakpoints are muted. */
    volatile List<DapModels.MemberBreakpoint> memberBreakpoints = List.of();
}
