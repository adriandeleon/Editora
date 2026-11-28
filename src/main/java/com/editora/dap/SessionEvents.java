package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The manager's side of the adapter's events: a stop (and the stops passed over without showing them), a
 * thread continuing, output and its trailing end, breakpoint and notice reports, the adapter's process, a
 * transport that closed, and the end of a session.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class SessionEvents {
    private final DapManager dap;

    SessionEvents(DapManager dap) {
        this.dap = dap;
    }

    /**
     * The {@code launch} or {@code attach} a socket adapter has not answered yet: the session it was sent for, when,
     * and whether it opens a browser. The session is RUNNING from the moment the adapter is connected, so this is what
     * tells a session that never started its program from one that ran and ended.
     */
    private record UnansweredLaunch(long epoch, long sentNanos, boolean browser) {}

    private volatile @Nullable UnansweredLaunch unansweredLaunch;

    /** How long a program may take to exit once the adapter has reported the session over, before it is killed. */
    private static final long DEBUGGEE_EXIT_GRACE_MILLIS = 3_000;

    /**
     * The program java-debug launched itself (program console off), from its {@code processid} event; null when
     * Editora started the program ({@link DapManager#debuggee}), for an attach, or on another machine. Ended with the
     * session: when the adapter is still there its {@code disconnect} has already done so and this finds
     * nothing alive, but when jdtls crashed the program was left running with no way to stop it.
     */
    private @Nullable ProcessHandle adapterProgram;

    /** Records the program the adapter of session {@code epoch} launched (FX thread). */
    void onProcessId(long epoch, long pid) {
        if (!dap.isCurrent(epoch)
                || dap.debugFile == null
                || !com.editora.process.ExecTargets.runsHere(dap.debugFile)) {
            return; // a pid on another machine names some other process here
        }
        adapterProgram = ProcessHandle.of(pid).orElse(null);
    }

    /** Ends the program the adapter launched, with its children, if it is still running (FX thread). */
    void killAdapterProgram() {
        ProcessHandle program = adapterProgram;
        adapterProgram = null;
        if (program != null && program.isAlive()) { // isAlive also checks the start time: never a reused pid
            program.descendants().forEach(ProcessHandle::destroyForcibly);
            program.destroyForcibly();
        }
    }

    /**
     * The adapter's transport closed. After a {@code terminated} event that is how a session ends. Without one,
     * the adapter is gone while its program may still run — jdtls crashed: the program resumed the moment its
     * debugger connection dropped, and used to run on for the 3 s grace an ordinary end gets (or for good, when
     * the adapter had launched it). End it at once and say why the session ended.
     */
    void onTransportClosed(long epoch) {
        awaitWhyLaunchWasDropped(epoch);
        dap.fx(() -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            if (dap.terminatedEpoch == epoch) {
                return; // the terminated event is ending it (or has ended it) already
            }
            UnansweredLaunch pending = unansweredLaunch;
            UnansweredLaunch unanswered = pending != null && pending.epoch() == epoch ? pending : null;
            Debuggee program = dap.debuggee;
            ProcessHandle launched = adapterProgram;
            boolean programRunning =
                    (program != null && program.isRunning()) || (launched != null && launched.isAlive());
            dap.debuggees.killDebuggee();
            killAdapterProgram();
            if (programRunning) {
                dap.outputPump.flush();
                dap.listener.onAdapterLost();
            } else if (dap.state == DapManager.State.STARTING || unanswered != null) {
                // The adapter died before the program ran: ending quietly showed "Starting…" then "Not debugging"
                // and nothing else (review D4) — say so, with what it wrote to stderr (`gdb -i dap` before GDB 14:
                // "Interpreter `dap' unrecognized"). The connect or launch failure that would say it comes after
                // this has ended the session, and is dropped. So does a launch it never answered: js-debug 1.117
                // closes the connection, with no error, when the browser it started has exited or is not ready
                // within its "timeout" — the session ended with nothing said at all.
                dap.listener.onError(adapterDiedText(unanswered) + AdapterStderr.suffix(dap.adapterStderr(epoch)));
            }
            endSession(epoch);
        });
    }

    /** What the user is told when the adapter of the starting session ended (FX thread). */
    private String adapterDiedText(@Nullable UnansweredLaunch unanswered) {
        if (dap.jsSession) {
            return unanswered != null && unanswered.browser()
                    ? tr(
                            "debug.error.browserNotReady",
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - unanswered.sentNanos()))
                    : tr("debug.error.jsAdapterDied");
        }
        DebugLanguage language = DebugLanguages.byId(dap.sessionLanguage);
        return tr("debug.error.adapterDied", language == null ? dap.sessionLanguage : language.displayName());
    }

    /**
     * Sends a session's {@code launch} or {@code attach} ({@code send}), remembered as {@link #unansweredLaunch} until
     * the adapter answers: an adapter that closes the connection before it does has not started the program.
     */
    CompletableFuture<Void> trackLaunch(long epoch, boolean browser, Supplier<CompletableFuture<Void>> send) {
        UnansweredLaunch sent = new UnansweredLaunch(epoch, System.nanoTime(), browser);
        unansweredLaunch = sent;
        return send.get().whenComplete((v, e) -> {
            if (unansweredLaunch == sent) {
                unansweredLaunch = null; // answered, or refused: the refusal is reported by the caller
            }
        });
    }

    /** How long a dropped launch waits for the adapter's reason to arrive on its standard error. */
    private static final long DROPPED_LAUNCH_STDERR_MILLIS = 300;

    /**
     * An adapter writes why it gave up ("Unable to launch browser: …", "Interpreter `dap' unrecognized") to its
     * standard error as it closes the connection, and nothing orders the two: gives those lines a moment to arrive, so
     * the message has them. Only for a session that did not end normally ({@code terminated}) and whose adapter's
     * stderr is drained, and never on the FX thread.
     */
    private void awaitWhyLaunchWasDropped(long epoch) {
        AdapterStderr stderr = dap.adapterStderr(epoch);
        if (dap.terminatedEpoch == epoch || stderr == null || Platform.isFxApplicationThread()) {
            return;
        }
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DROPPED_LAUNCH_STDERR_MILLIS);
        try {
            while (stderr.text().isEmpty() && dap.isCurrent(epoch) && System.nanoTime() < end) {
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void onStopped(long epoch, int threadId, String reason, boolean allThreadsStopped) {
        DapClient c = dap.client;
        if (c == null || !dap.isCurrent(epoch, c)) {
            return;
        }
        presentStop(epoch, c, threadId, reason, true, allThreadsStopped, resumesOf(threadId));
    }

    /**
     * The {@code continued} events each thread has had, by id ({@link #ALL_THREADS}: those for every thread), counted
     * as they arrive (on the adapter's reader thread, before the stop they follow has its frames).
     */
    private final Map<Integer, Long> resumes = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int ALL_THREADS = Integer.MIN_VALUE;

    private long resumesOf(int threadId) {
        return resumes.getOrDefault(threadId, 0L) + resumes.getOrDefault(ALL_THREADS, 0L);
    }

    /**
     * Shows {@code threadId} as the stopped thread. {@code event} is a stop the adapter just reported;
     * otherwise it is a thread that was already stopped and is being brought forward because the one on
     * screen was resumed — which is dropped if a real stop arrives first, and must not consume a
     * run-to-cursor temp breakpoint that the resumed thread has yet to reach.
     */
    void presentStop(
            long epoch,
            DapClient c,
            int threadId,
            String reason,
            boolean event,
            boolean allThreadsStopped,
            long resumed) {
        c.stackPage(threadId, true, 0, DapClient.FIRST_FRAMES).whenComplete((page, e) -> {
            List<DapModels.StackFrameInfo> frames = page == null ? null : page.frames();
            dap.fxAfterLooks(frames, () -> {
                if (!dap.isCurrent(epoch, c)) {
                    return;
                }
                if (event && (resumesOf(threadId) != resumed || passesDartPause(reason))) {
                    // Resumed before its frames came back — Dart's adapter resumes its own entry stop at once:
                    // shown now, it would leave a running program looking paused. Or a Dart pause of its own.
                    return;
                }
                if (e != null) {
                    DapManager.LOG.log(Level.WARNING, "stackTrace failed for thread " + threadId, e);
                }
                if (event && passesBashEntry(c, threadId, frames)) {
                    return; // bashdb's own stop at the script's first line, where no breakpoint is
                }
                if (event && passesRemoteEntry(c, threadId, reason, frames)) {
                    return; // a debug server's hold on the program before its first instruction
                }
                if (event) {
                    if (allThreadsStopped) {
                        dap.stoppedThreads.clear(); // one stop stands for all of them, and so will one continue
                        dap.allSuspended = true;
                        dap.resumedSinceSuspendAll.clear();
                    }
                    dap.resumedSinceSuspendAll.remove(threadId);
                    dap.stoppedThreads.put(threadId, reason == null ? "" : reason);
                    settleTempBreakpoint(frames);
                } else if (dap.state != DapManager.State.RUNNING || !dap.stoppedThreads.containsKey(threadId)) {
                    return;
                }
                dap.stopCount++;
                dap.stepInFlight = false;
                dap.currentThreadId = threadId;
                dap.lazyValues.newStop("pause".equals(dap.stoppedThreads.get(threadId))); // B202
                dap.shownStack = page == null ? DapModels.StackPage.EMPTY : page;
                dap.state = DapManager.State.SUSPENDED;
                dap.listener.onState(DapManager.State.SUSPENDED);
                dap.listener.onStopped(threadId, reason, dap.javaLaunch.originalFrames(frames));
                if ("exception".equals(reason)) {
                    reportException(epoch, c, threadId, dap.stopCount);
                }
            });
        });
    }

    /**
     * A stop while a Run to Cursor temp breakpoint is planted (FX thread). At the cursor line, or reached by a step, it
     * has done its job and goes; elsewhere after a resume it stays until the program is resumed from this stop or the
     * user acts otherwise ({@link DapManager#tempTripResumed}): the window may be about to resume it without showing it.
     */
    private void settleTempBreakpoint(@Nullable List<DapModels.StackFrameInfo> frames) {
        Path f = dap.tempBreakpointFile;
        if (f == null && dap.forceRunMuted.isEmpty() && !dap.forceRunMutedMembers) {
            return;
        }
        if (dap.tempTripResumed && f != null && !atLine(frames, f, dap.tempBreakpointLine)) {
            dap.tempTripResumed = false; // kept: a resume from this stop carries the trip on, anything else ends it
            return;
        }
        dap.execution.clearTempBreakpoint();
    }

    /** Whether {@code frames}' top frame is on 0-based {@code line} of {@code file}, as the adapter or the user names it. */
    private boolean atLine(@Nullable List<DapModels.StackFrameInfo> frames, Path file, int line) {
        if (frames == null || frames.isEmpty()) {
            return false;
        }
        for (DapModels.StackFrameInfo top :
                List.of(frames.getFirst(), dap.javaLaunch.originalFrames(frames).getFirst())) {
            if (top.line() == line && top.file() != null && com.editora.config.PathKeys.samePath(top.file(), file)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code reason} is a pause Dart's adapter takes and ends itself: the isolate paused as it starts
     * ({@code entry}: the breakpoints are set, then it resumes) or as it exits ({@code exit}). In a Dart (or Flutter)
     * session such a stop is not shown: the program runs on, as under any other debugger.
     */
    private boolean passesDartPause(@Nullable String reason) {
        return DebugLanguages.DART.id().equals(dap.sessionLanguage)
                && ("entry".equals(reason) || "exit".equals(reason));
    }

    /**
     * Whether a stop of a remote attach is the debug server's hold before the program's first instruction: while no
     * stop has reached code with a source yet, one at a frame without a file (the loader), for no breakpoint. Resumed,
     * and true; the first stop in code with a source ends that.
     */
    private boolean passesRemoteEntry(
            DapClient c, int threadId, @Nullable String reason, @Nullable List<DapModels.StackFrameInfo> frames) {
        if (!dap.remoteEntryPending) {
            return false;
        }
        DapModels.StackFrameInfo top = frames == null || frames.isEmpty() ? null : frames.getFirst();
        if ("breakpoint".equals(reason) || (top != null && top.file() != null)) {
            dap.remoteEntryPending = false; // the program's own code: shown from now on
            return false;
        }
        c.resume(threadId);
        return true;
    }

    /**
     * bashdb stops at the script's first command, and vscode-bash-debug reports it as any other stop: the first stop
     * of a Bash session, on a line with no breakpoint, is not shown and the script goes on (as a program started under
     * any other debugger does). True when that is so.
     */
    private boolean passesBashEntry(DapClient c, int threadId, @Nullable List<DapModels.StackFrameInfo> frames) {
        if (dap.bashEntryPassed || !DebugLanguages.BASH.id().equals(dap.sessionLanguage)) {
            return false;
        }
        dap.bashEntryPassed = true;
        DapModels.StackFrameInfo top = frames == null || frames.isEmpty() ? null : frames.getFirst();
        if (top == null
                || top.file() == null
                || plainBreakpointAt(dap.shared.breakpointsSupplier.get(), top.file(), top.line())) {
            return false;
        }
        c.resume(threadId);
        return true;
    }

    /**
     * Whether a plain breakpoint — no condition, hit count, log message, filters or recording — is set on 0-based
     * {@code line} of {@code file}: the one kind for which bashdb's entry stop is the stop the user asked for. Any other
     * kind would have the entry stop shown as a pause the user never asked for: bashdb stops at the first command
     * before it evaluates breakpoints, so the stop says nothing about the condition, and a log message is not a stop.
     * Such a breakpoint on the script's very first command is passed over with the entry stop (bashdb does not check
     * the line it continues from again). Pure.
     */
    static boolean plainBreakpointAt(List<DapModels.FileBreakpoints> all, Path file, int line) {
        for (DapModels.FileBreakpoints fb : all) {
            if (fb.file() != null && com.editora.config.PathKeys.samePath(normalized(fb.file()), normalized(file))) {
                for (DapModels.LineBreakpoint b : fb.breakpoints()) {
                    if (b.line() == line
                            && blank(b.condition())
                            && blank(b.logMessage())
                            && blank(b.hitCondition())
                            && !b.recording()
                            && b.filters().none()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** {@code p} absolute and normalized (bashdb names a script as it was started), or as it is when it cannot be. */
    private static Path normalized(Path p) {
        try {
            return p.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return p;
        }
    }

    /** Asks which exception {@code threadId} stopped on and reports it, unless the stop has ended meanwhile. */
    private void reportException(long epoch, DapClient c, int threadId, int stop) {
        c.exceptionInfo(threadId)
                .whenComplete((info, e) -> dap.fx(() -> {
                    if (info != null
                            && !info.isEmpty()
                            && dap.isCurrent(epoch, c)
                            && dap.state == DapManager.State.SUSPENDED
                            && dap.stopCount == stop
                            && dap.currentThreadId == threadId) {
                        dap.listener.onExceptionInfo(threadId, info);
                    }
                }));
    }

    void onBreakpointStatus(long epoch, Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
        dap.fx(() -> {
            if (!dap.isCurrent(epoch) || file == null) {
                return;
            }
            DapManager.SourceAlias alias =
                    dap.javaLaunch.activeSourceAlias(); // a compiled copy stands in for the file the user sees
            dap.listener.onBreakpointStatus(
                    alias != null && alias.compiled().equals(file) ? alias.original() : file, statuses, whole);
        });
    }

    void onNotice(long epoch, String message, boolean error) {
        dap.fx(() -> {
            if (dap.isCurrent(epoch)) {
                dap.outputPump.flush(); // keep it after the program output that preceded it
                dap.listener.onNotice(message, error);
            }
        });
    }

    void onContinued(long epoch, int threadId, boolean allThreads) {
        resumes.merge(allThreads ? ALL_THREADS : threadId, 1L, Long::sum); // here, before the fx hop (presentStop)
        dap.fx(() -> {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            if (allThreads) {
                dap.stoppedThreads.clear();
                dap.allSuspended = false;
                dap.resumedSinceSuspendAll.clear();
            } else {
                dap.stoppedThreads.remove(threadId);
                if (dap.state == DapManager.State.SUSPENDED && threadId != dap.currentThreadId) {
                    return; // another thread resumed; the one on screen is still stopped
                }
            }
            dap.setState(DapManager.State.RUNNING);
        });
    }

    void onOutput(long epoch, String text, String category) {
        if (dap.jsSession) {
            text = jsOutput(epoch, text, category);
            if (text == null || text.isEmpty()) {
                return;
            }
        }
        String original = text;
        String shown = JavaLaunchSupport.withoutJvmVersionWarning(text);
        if (shown != text) {
            DapManager.LOG.fine(() -> "java-debug: " + original.strip());
        }
        if (!shown.isEmpty()) {
            dap.lastOutput = new DapManager.OutputStamp(epoch, System.nanoTime());
            dap.outputPump.offer(epoch, shown, category);
        }
    }

    /** A program java-debug started itself (the program console off): its output trails its exit (E104). */
    private boolean outputTrails(long epoch) {
        return dap.javaAdapterEpoch == epoch && dap.programStartedEpoch != epoch;
    }

    /**
     * Runs {@code then} on the FX thread once the session's output has gone quiet ({@link OutputTail}), so the
     * program's last lines are shown before the session ends; dropped if the session ends meanwhile (Stop).
     */
    void whenOutputQuiet(long epoch, long doneNanos, Runnable then) {
        DapManager.OutputStamp last = dap.lastOutput;
        long wait = OutputTail.waitMillis(System.nanoTime(), last.epoch() == epoch ? last.nanos() : 0, doneNanos);
        if (wait <= 0) {
            then.run();
            return;
        }
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)
                .execute(() -> dap.fx(() -> {
                    if (dap.isCurrent(epoch)) {
                        whenOutputQuiet(epoch, doneNanos, then);
                    }
                }));
    }

    /** The session whose program ended while its output still arrives; -1 for none (see {@link #outputTrailing}). */
    private long trailingEpoch = -1;

    /**
     * The program of {@code epoch} ended and its output is still arriving: if the session still waits a moment
     * later, the listener is told once, so the state stops saying Running (review 3, choice 27). FX thread.
     */
    void outputTrailing(long epoch) {
        if (trailingEpoch == epoch) {
            return;
        }
        trailingEpoch = epoch;
        CompletableFuture.delayedExecutor(DapManager.TRAILING_NOTICE_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> dap.fx(() -> {
                    if (dap.isCurrent(epoch) && dap.state != DapManager.State.INACTIVE) {
                        dap.outputPump.flush(); // after the output that came before it
                        dap.listener.onOutputTrailing();
                    }
                }));
    }

    /** Reports the exit of {@code epoch} if it is still waiting for the output before it (FX thread). */
    void reportAwaitedExit(long epoch) {
        if (dap.exitAwaitingOutput == epoch) {
            dap.exitAwaitingOutput = -1;
            dap.outputPump.flush();
            dap.debuggees.reportJavaExit(epoch);
        }
    }

    /**
     * What js-debug sends as program output, as the console should show it: Node's inspector lines
     * ("Debugger attached.") removed, and a breakpoint condition it could not compile turned into a notice that
     * marks that breakpoint rejected — once, though js-debug repeats it for every script it loads (review D12).
     */
    private @Nullable String jsOutput(long epoch, String text, String category) {
        var error = JsLaunch.conditionError(text);
        if (error.isPresent()) {
            JsLaunch.ConditionError ce = error.get();
            if (dap.reportedConditionErrors.add(ce.condition() + "\u0000" + ce.line())) {
                onNotice(epoch, tr("debug.notice.jsCondition", ce.condition(), ce.line(), ce.reason()), true);
            }
            return null;
        }
        if (dap.jsClientConsole && ("stdout".equals(category) || "stderr".equals(category))) {
            return null; // the program's own pipes carry it (startDebuggee); this is js-debug's copy
        }
        return dap.jsNoise.filter(text);
    }

    void onTerminated(long epoch) {
        DapManager.ProgramAdapter self = dap.programAdapter;
        if (self != null && self.epoch() == epoch && self.process().isAlive()) {
            // rdbg says terminated before Ruby has flushed its standard output and exited: the session ends once
            // the program has (bounded), and its last output is in, rather than killing it with that output unread.
            self.process()
                    .onExit()
                    .completeOnTimeout(
                            self.process(), DapManager.PROGRAM_ADAPTER_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                    .thenRun(() -> dap.fx(() -> {
                        if (dap.isCurrent(epoch)) {
                            whenOutputQuiet(epoch, System.nanoTime(), () -> endSession(epoch));
                        }
                    }));
            return;
        }
        dap.fx(() -> {
            Debuggee program = dap.debuggee;
            if (dap.isCurrent(epoch) && program != null && program.isRunning()) {
                // The adapter is done with a program Editora started. Its last output is still in our pipe
                // and the process may not be gone yet: the session ends when its exit is delivered
                // (startDebuggee's sink), and a program that outlives its debugger is ended, not left running.
                CompletableFuture.delayedExecutor(DEBUGGEE_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> dap.fx(() -> {
                            if (dap.debuggee == program) {
                                program.terminate();
                            }
                        }));
                return;
            }
            if (dap.isCurrent(epoch) && outputTrails(epoch)) {
                outputTrailing(epoch);
                whenOutputQuiet(epoch, System.nanoTime(), () -> {
                    reportAwaitedExit(epoch);
                    endSession(epoch);
                });
                return;
            }
            endSession(epoch);
        });
    }

    /** Ends the session of {@code epoch}, if it is still the current one (FX thread). */
    void endSession(long epoch) {
        if (!dap.isCurrent(epoch)) {
            return;
        }
        dap.outputPump.flush(); // the program's last lines arrived before this event — show them first
        dap.tempBreakpointFile = null; // session over — nothing to restore
        dap.forceRunMuted = List.of();
        dap.forceRunMutedMembers = false;
        DapClient c;
        synchronized (dap.sessionLock) {
            if (!dap.isCurrent(epoch)) {
                return;
            }
            dap.sessionEpoch++;
            c = dap.client;
            dap.client = null;
        }
        dap.debuggees.killDebuggee();
        if (c != null) {
            c.disposeInBackground(); // as in stop(): never hold the FX thread on the adapter
        }
        killAdapterProgram();
        dap.javaLaunch.releaseCompilationDirectory();
        dap.debuggees.releaseExitWrapper(); // no exited event came for it
        dap.debuggees.releaseGoDebugBinary();
        dap.setState(DapManager.State.INACTIVE);
    }

    void onError(long epoch, String message) {
        dap.fx(() -> {
            if (dap.isCurrent(epoch)) {
                dap.listener.onError(message);
            }
        });
    }
}
