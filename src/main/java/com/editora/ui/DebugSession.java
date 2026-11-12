package com.editora.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

/**
 * One debug session of a window, from the start of its launch (a before-launch build, the closed files' breakpoints
 * read) to its end: the {@link DapManager} that debugs it, and what {@link DebugCoordinator} keeps about it — the stop
 * shown, the breakpoints sent to it and what it said of them, its relaunch, its code replace. The window's sessions
 * are in {@link DebugSessions}. FX thread only.
 */
final class DebugSession {

    final DapManager dap;

    DebugSession(DapManager dap) {
        this.dap = dap;
    }

    /** This session's Debug panel: its console, call stack, variables and Debug History; null until built. */
    @Nullable
    DebugPanel panel;

    // --- the launch -------------------------------------------------------------------------------------

    /** The session whose program started this one's (a Python subprocess), or null for a session of its own. */
    @Nullable
    DebugSession parent;

    /** The compound whose launch this session runs, or null ({@link DebugCompound#holds} says whether it still does). */
    @Nullable
    DebugCompound compound;

    /** This session's before-launch steps and the service they run on; null until its first step. */
    @Nullable
    BeforeLaunchStep beforeLaunch;

    com.editora.run.@Nullable RunService beforeLaunchService;

    /** The text each file of the session was launched from (see {@link LaunchedLines}). */
    final LaunchedLines launchedLines = new LaunchedLines();

    /** Files the console already said were changed since the session started. */
    final Set<String> changedNoticed = new HashSet<>();

    /**
     * The session start waiting for {@code DebugCoordinator.withClosedBreakpoints}'s file reads, else null. Stop and a
     * newer start clear or replace it, and the start it stands for is then not made (review D104).
     */
    @Nullable
    Object closedBreakpointsRead;

    /** The session label of the launch in progress, re-applied once the session it replaces has ended. */
    String sessionLabel = "";

    /** Whether this session's start was said in the status bar (once, at its first RUNNING or SUSPENDED). */
    boolean sessionAnnounced = true;

    /**
     * Whether the next session's start is said in the status bar: one that a before-launch step or a build/test run
     * started, whose "Running the before-launch step…" / "Attaching the debugger…" would stay there otherwise.
     */
    boolean announceStart;

    /** The console already belongs to this launch (its before-launch build wrote to it): do not clear again. */
    boolean consoleStarted;

    /** Repeats the last coordinator-level start (save, before-launch build, closed-file breakpoints, launch). */
    @Nullable
    Runnable relaunch;

    /** What Debug ▸ Stop runs while a test run waits for its JVM to attach (no session yet). */
    @Nullable
    Runnable pendingAttachStop;

    /** The test file of {@link #pendingAttachStop}'s run: F5 there again says it is starting (review 3, A208). */
    @Nullable
    Path pendingAttachFile;

    /** Bumped when a session starts or ends, so a slow runtime probe cannot label the next one. */
    long runtimeGeneration;

    /** F5 is starting a test: its check has been made ({@code DebugCoordinator.clearToDebugTest}). */
    boolean startCleared;

    /** The build before a project launch in flight; replaced or cleared to drop its result (Stop). */
    @Nullable
    Object pendingBuild;

    /** The file {@link #pendingBuild}'s launch is for. */
    @Nullable
    Path pendingBuildFile;

    /** The test or build run that started the JVM of the attached session; Stop ends it too (review A4). */
    @Nullable
    Runnable ownerStop;

    // --- breakpoints ------------------------------------------------------------------------------------

    /** What each open file's breakpoints were when last sent to the live session (cleared between sessions). */
    final Map<Path, DapModels.FileBreakpoints> sentBreakpoints = new java.util.HashMap<>();

    /**
     * The ids of the breakpoints hit in this session that triggered breakpoints wait for: those that wait for them
     * are sent from then on (cleared between sessions; see {@link TriggeredBreakpoints}).
     */
    final java.util.Set<String> hitTriggers = new java.util.HashSet<>();

    /**
     * The read-only tabs opened for code the debugger has only by reference, by its {@code sourceReference}: asked for
     * again, the tab is shown rather than opened twice. A reference holds for one session only.
     */
    final Map<Integer, SourceDoc> sourceDocs = new java.util.HashMap<>();

    /** A read-only tab holding code the debugger has only by reference, and that code's source. */
    record SourceDoc(com.editora.dap.DapModels.LoadedSource source, com.editora.editor.EditorBuffer buffer) {}

    /** The objects marked in this session (Mark Object); cleared when it starts again. */
    final ObjectMarks marks = new ObjectMarks();

    /** What the live session said about each file's breakpoints, by document line (cleared between sessions). */
    final Map<Path, Map<Integer, DapModels.BreakpointStatus>> breakpointStatus = new java.util.HashMap<>();

    /** Evaluation failures of this session, by file and document line; one ends when its expression is edited. */
    final Map<Path, Map<Integer, DebugCoordinator.EvaluationFailure>> evaluationFailures = new java.util.HashMap<>();

    /** Rejections already reported in the status bar this session, so a re-sent file does not repeat them. */
    final Set<String> reportedRejections = new HashSet<>();

    // --- the stop shown ---------------------------------------------------------------------------------

    /** The selected stack frame's id (the hover evaluator's context); -1 while not suspended. */
    int debugFrameId = -1;

    /** The frame whose line is highlighted (re-applied when its file is reopened); null unless suspended. */
    DapModels.@Nullable StackFrameInfo shownFrame;

    /** Counts stops: the exception card opens by itself once per stop, and again only when asked. */
    int stopSerial;

    /** The stop the exception card last opened by itself for. */
    int exceptionCardStop = -1;

    /** The id of the frame an exception stop threw in (its line is drawn red), or -1. */
    int exceptionFrameId = -1;

    /** The exception of the stop shown, once the adapter has said which (else null). */
    DapModels.@Nullable ExceptionInfo stopException;

    /** The stop a recording breakpoint made, whose frame is not shown in the editor; -1 for none. */
    int quietStop = -1;

    /**
     * Thread Dump asked for a pause: the program was running, and the {@code pause} stop that comes is the dump's — the
     * dump is taken and the program goes on, without the editor being taken to it.
     */
    boolean dumpPause;

    /** Stops recorded by recording breakpoints this session. */
    int recordedStops;

    /** The state the session last reported: a run is timed from a stop only. */
    DapManager.@Nullable State lastState;

    /** When the program last went on from a stop the user saw ({@link System#nanoTime}); 0: not timed. */
    long runStartNanos;

    /** How long the run to this stop took, as shown after its line (Visual Studio's PerfTip); null: not shown. */
    @Nullable
    String stepTime;

    /** The frame whose line shows {@link #stepTime}: the one the stop showed first. */
    int stepTimeFrameId = -1;

    /** The Memory view's memory reference (an address, or one an expression's value has), or null before any. */
    @Nullable
    String memoryReference;

    /** The Memory view's offset from {@link #memoryReference}: its paging. */
    long memoryOffset;

    // --- Java code changes ------------------------------------------------------------------------------

    /** An Apply Code Changes is building or replacing; a save meanwhile is folded into one more round. */
    boolean codeReplaceRunning;

    /** Bumped when a session starts or ends: the answer of a replace begun before is not this session's. */
    long codeReplaceSession;

    /** Another save arrived during {@link #codeReplaceRunning}: apply once more when it ends. */
    @Nullable
    Path codeReplaceAgain;

    /** Java files saved since the running code last matched them (re-baselined by a successful replace). */
    final Set<Path> savedSinceReplace = new LinkedHashSet<>();

    /** java-debug's last ERROR notice, so a failed replace does not print the same reason twice. */
    String lastCodeReplaceError = "";

    /** The replace whose old-code hint waits for the next stack ({@code DebugCoordinator.explainOldCode}); 0: none. */
    long oldCodeHintPending;

    /** Counts the old-code hints asked for, so that each waits under its own number. */
    long oldCodeHintAsked;
}
