package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Moving the program on: Continue and Pause (the whole program or one thread), the Threads view's list, the
 * steps, Run to Cursor and Force Run to Cursor with their temporary breakpoint, and Jump to Line.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class ExecutionControl {
    private final DapManager dap;

    ExecutionControl(DapManager dap) {
        this.dap = dap;
    }

    void resume() {
        DapClient c = dap.client;
        if (c == null) {
            return;
        }
        if (dap.refusePostMortem() || (dap.state == DapManager.State.SUSPENDED && dap.refuseWhileEvaluating())) {
            return;
        }
        long epoch = dap.sessionEpoch;
        int threadId = dap.currentThreadId;
        dap.stepInFlight = false;
        dap.tempTripResumed = dap.tempBreakpointFile != null; // a Run to Cursor trip goes on from here
        dap.stoppedThreads.remove(threadId);
        boolean wholeProgram = dap.allSuspended && dap.javaDebugSession();
        dap.allSuspended = false;
        dap.resumedSinceSuspendAll.clear();
        (wholeProgram ? c.resumeAll(threadId) : c.resume(threadId))
                .whenComplete((all, e) -> dap.fx(() -> {
                    if (e == null && dap.isCurrent(epoch, c)) {
                        afterThreadResumed(epoch, c, all == null || all);
                    }
                }));
        dap.setState(DapManager.State.RUNNING);
    }

    /**
     * One thread was resumed. When the adapter says the others were not ({@code allThreadsContinued=false})
     * and one of them is still stopped, show that one instead of reporting a running session: nothing else
     * would ever bring it back, and the program waits on it for good.
     */
    private void afterThreadResumed(long epoch, DapClient c, boolean allThreads) {
        if (allThreads) {
            dap.stoppedThreads.clear();
            return;
        }
        if (dap.state != DapManager.State.RUNNING || dap.stoppedThreads.isEmpty()) {
            return;
        }
        var next = dap.stoppedThreads.entrySet().iterator().next();
        dap.sessionEvents.presentStop(epoch, c, next.getKey(), next.getValue(), false, false, -1);
    }

    /** Pauses a running session: targets the current thread if the adapter reports it, else the first. A Java
     *  session suspends every thread (java-debug's {@code pauseAll}); debugpy pauses all threads by itself. The
     *  adapter answers with a {@code stopped(reason=pause)} event, which flows through onStopped. */
    void pause() {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.RUNNING || dap.postMortem) {
            return;
        }
        c.threads().whenComplete((list, e) -> {
            if (dap.client != c || e != null || list == null || list.isEmpty()) {
                return;
            }
            int id = list.get(0).id();
            for (DapModels.ThreadInfo t : list) {
                if (t.id() == dap.currentThreadId) {
                    id = t.id();
                    break;
                }
            }
            if (dap.javaDebugSession()) {
                c.pauseAll(id);
            } else {
                c.pause(id);
            }
        });
    }

    /**
     * The session's threads with whether each is suspended (callback on the FX thread): the Threads view. Empty with
     * no session.
     */
    void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
        threads(list -> cb.accept(threadStatuses(
                list,
                dap.stoppedThreads,
                dap.allSuspended,
                dap.resumedSinceSuspendAll,
                dap.state == DapManager.State.SUSPENDED ? dap.currentThreadId : -1)));
    }

    /**
     * Which of {@code threads} are suspended: those stopped since the last continue ({@code stopped}, with their
     * reason), the one shown ({@code shownId}, -1 when none is), and — the whole program suspended — every thread not
     * resumed on its own since. Pure.
     */
    static List<DapModels.ThreadStatus> threadStatuses(
            List<DapModels.ThreadInfo> threads,
            Map<Integer, String> stopped,
            boolean allSuspended,
            java.util.Set<Integer> resumedSinceSuspendAll,
            int shownId) {
        List<DapModels.ThreadStatus> out = new ArrayList<>();
        for (DapModels.ThreadInfo t : threads) {
            String stoppedFor = stopped.get(t.id());
            boolean paused = stoppedFor != null
                    || t.id() == shownId
                    || (allSuspended && !resumedSinceSuspendAll.contains(t.id()));
            String reason = stoppedFor != null ? stoppedFor : paused && t.id() != shownId ? "pause" : "";
            out.add(new DapModels.ThreadStatus(t.id(), t.name(), paused, reason, t.id() == shownId));
        }
        return out;
    }

    /**
     * Threads view → Pause Thread: suspends {@code threadId} alone (java-debug; debugpy suspends the whole program).
     * The adapter's {@code stopped} event then shows it like any stop.
     */
    void pauseThread(int threadId) {
        DapClient c = dap.client;
        if (c != null && dap.state != DapManager.State.INACTIVE && dap.state != DapManager.State.STARTING) {
            c.pause(threadId);
        }
    }

    /**
     * Threads view → Resume Thread: resumes {@code threadId} alone, the others staying as they are. When it is the
     * thread shown, the next still-suspended one is brought forward, as after Continue; when the adapter resumed the
     * whole program instead (debugpy), the session runs.
     */
    void resumeThread(int threadId) {
        DapClient c = dap.client;
        if (c == null || dap.state == DapManager.State.INACTIVE || dap.state == DapManager.State.STARTING) {
            return;
        }
        if (dap.refusePostMortem()
                || (dap.state == DapManager.State.SUSPENDED
                        && threadId == dap.currentThreadId
                        && dap.refuseWhileEvaluating())) {
            return;
        }
        long epoch = dap.sessionEpoch;
        boolean shown = dap.state == DapManager.State.SUSPENDED && threadId == dap.currentThreadId;
        dap.stoppedThreads.remove(threadId);
        if (dap.allSuspended) {
            dap.resumedSinceSuspendAll.add(threadId);
        }
        if (shown) {
            dap.stepInFlight = false;
            dap.setState(DapManager.State.RUNNING);
        }
        c.resume(threadId)
                .whenComplete((all, e) -> dap.fx(() -> {
                    if (e != null || !dap.isCurrent(epoch, c)) {
                        return;
                    }
                    if (all == null || all) {
                        dap.stoppedThreads.clear();
                        dap.allSuspended = false;
                        dap.resumedSinceSuspendAll.clear();
                        if (dap.state == DapManager.State.SUSPENDED) {
                            dap.setState(DapManager.State.RUNNING);
                        }
                    } else if (shown) {
                        afterThreadResumed(epoch, c, false);
                    }
                    dap.listener.onThreadsChanged();
                }));
    }

    /**
     * Threads view → Show Frames on a suspended thread while no thread is shown (the one on screen was resumed on
     * its own): shows it as the stopped thread. A thread already shown, or with the session suspended, is selected
     * as from the thread dropdown instead (the caller's part). False when nothing was done.
     */
    boolean showSuspendedThread(int threadId, String reason) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.RUNNING) {
            return false;
        }
        dap.stoppedThreads.put(threadId, reason == null ? "" : reason);
        dap.sessionEvents.presentStop(dap.sessionEpoch, c, threadId, reason == null ? "" : reason, false, false, -1);
        return true;
    }

    /** Lists the session's threads (callback on the FX thread). */
    void threads(Consumer<List<DapModels.ThreadInfo>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.threads()
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        if (list != null) {
                            dap.threadNames.clear();
                            list.forEach(t -> dap.threadNames.put(t.id(), t.name()));
                        }
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /**
     * The name of thread {@code threadId} (callback on the FX thread; "" when the adapter does not list it): from the
     * last listing when it named the thread and no thread started or ended since, else asked for. A token logpoint in a
     * loop asked for every thread at each hit (perf review 2026-11-23, D4).
     */
    void threadName(int threadId, Consumer<String> cb) {
        String known = dap.client == null ? null : dap.threadNames.get(threadId);
        if (known != null) {
            cb.accept(known);
            return;
        }
        threads(list -> cb.accept(list.stream()
                .filter(t -> t.id() == threadId)
                .map(DapModels.ThreadInfo::name)
                .findFirst()
                .orElse("")));
    }

    /** Resumes and stops at {@code line} (0-based) in {@code file} via a temporary breakpoint that is
     *  removed when the program stops there, stops elsewhere and is not resumed, or ends
     *  ({@link SessionEvents#settleTempBreakpoint}). Call on the FX thread while SUSPENDED. */
    void runToCursor(Path file, int line) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.SUSPENDED || file == null || dap.refusePostMortem()) {
            return;
        }
        clearTempBreakpoint(); // the last trip's, kept from a stop on the way
        dap.tempBreakpointFile = file;
        dap.tempBreakpointLine = line;
        c.sendSetBreakpoints(
                dap.javaLaunch.adapterBreakpoint(withTempLine(dap.shared.breakpointsSupplier.get(), file, line)));
        resume();
    }

    /**
     * Force Run to Cursor (IntelliJ, Visual Studio): runs to {@code line} (0-based) of {@code file} with every
     * breakpoint set aside, so none on the way stops the program. Exception breakpoints still stop it. The stop at the
     * cursor, or any stop the program is not resumed from, puts the breakpoints back ({@link SessionEvents#settleTempBreakpoint}).
     * FX thread, while SUSPENDED.
     */
    void forceRunToCursor(Path file, int line) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.SUSPENDED || file == null || dap.refusePostMortem()) {
            return;
        }
        clearTempBreakpoint(); // the last trip's, kept from a stop on the way
        List<Path> muted = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : dap.shared.breakpointsSupplier.get()) {
            if (fb.file() != null
                    && !fb.breakpoints().isEmpty()
                    && !com.editora.config.PathKeys.samePath(fb.file(), file)) {
                muted.add(fb.file());
                c.sendSetBreakpoints(
                        dap.javaLaunch.adapterBreakpoint(new DapModels.FileBreakpoints(fb.file(), List.of())));
            }
        }
        dap.forceRunMuted = List.copyOf(muted);
        if (!dap.shared.memberBreakpoints.isEmpty()) {
            dap.forceRunMutedMembers = true; // method entries and field writes on the way must not stop it either
            c.setMemberBreakpoints(List.of());
        }
        dap.tempBreakpointFile = file;
        dap.tempBreakpointLine = line;
        c.sendSetBreakpoints(dap.javaLaunch.adapterBreakpoint(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(line, null, null)))));
        resume();
    }

    /**
     * Jump to Line (the IntelliJ plugin's move-the-execution-pointer): teleports the suspended thread
     * to {@code line} (0-based) of {@code file} <em>without executing</em> the code in between. The
     * adapter's {@code stopped(reason=goto)} event then refreshes the UI like any stop. {@code onError}
     * is called on the FX thread with "" when the line has no valid target, else the failure message.
     */
    void jumpToLine(Path file, int line, Consumer<String> onError) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.SUSPENDED || file == null || dap.refusePostMortem()) {
            return;
        }
        int threadId = dap.currentThreadId;
        c.gotoTargets(file, line)
                .whenComplete((ids, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e != null) {
                        onError.accept(DapManager.msg(e));
                        return;
                    }
                    if (ids == null || ids.isEmpty()) {
                        onError.accept(""); // no jump target on that line
                        return;
                    }
                    c.gotoTarget(threadId, ids.get(0)).whenComplete((v, e2) -> {
                        if (e2 != null) {
                            dap.fx(() -> {
                                if (dap.client == c) {
                                    onError.accept(DapManager.msg(e2));
                                }
                            });
                        }
                    });
                }));
    }

    /**
     * The file's existing breakpoints plus a temporary plain one at {@code line}. A breakpoint already on that
     * line is made plain for the trip: left as it was, a condition that was false or a logpoint (which never
     * suspends) let the program run past the cursor. {@link #clearTempBreakpoint} restores it. Pure — tested.
     */
    static DapModels.FileBreakpoints withTempLine(List<DapModels.FileBreakpoints> all, Path file, int line) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : all == null ? List.<DapModels.FileBreakpoints>of() : all) {
            if (com.editora.config.PathKeys.samePath(fb.file(), file)) {
                for (DapModels.LineBreakpoint lb : fb.breakpoints()) {
                    if (lb.line() != line) {
                        lines.add(lb);
                    }
                }
            }
        }
        lines.add(new DapModels.LineBreakpoint(line, null, null));
        return new DapModels.FileBreakpoints(file, lines);
    }

    /**
     * The window showed the current stop to the user (not one it resumes by itself: a logpoint, a recording or
     * filtered breakpoint): a Run to Cursor trip ends there, as in VS Code and IntelliJ. FX thread.
     */
    void stopShown() {
        if (dap.tempBreakpointFile != null || !dap.forceRunMuted.isEmpty() || dap.forceRunMutedMembers) {
            clearTempBreakpoint();
        }
    }

    /** Restores the real breakpoint set after a run-to-cursor temp breakpoint did its job (FX thread). */
    void clearTempBreakpoint() {
        Path f = dap.tempBreakpointFile;
        dap.tempBreakpointFile = null;
        dap.tempBreakpointLine = -1;
        dap.tempTripResumed = false;
        List<Path> muted = dap.forceRunMuted;
        dap.forceRunMuted = List.of();
        DapClient c = dap.client;
        if (c != null && dap.forceRunMutedMembers) {
            c.setMemberBreakpoints(dap.shared.memberBreakpoints);
        }
        dap.forceRunMutedMembers = false;
        if (c != null && !muted.isEmpty()) {
            for (Path file : muted) {
                List<DapModels.LineBreakpoint> real = new ArrayList<>();
                for (DapModels.FileBreakpoints fb : dap.shared.breakpointsSupplier.get()) {
                    if (com.editora.config.PathKeys.samePath(fb.file(), file)) {
                        real.addAll(fb.breakpoints());
                    }
                }
                c.sendSetBreakpoints(dap.javaLaunch.adapterBreakpoint(new DapModels.FileBreakpoints(file, real)));
            }
        }
        if (f == null || c == null) {
            return;
        }
        List<DapModels.LineBreakpoint> real = new ArrayList<>();
        for (DapModels.FileBreakpoints fb : dap.shared.breakpointsSupplier.get()) {
            if (com.editora.config.PathKeys.samePath(fb.file(), f)) {
                real.addAll(fb.breakpoints());
            }
        }
        c.sendSetBreakpoints(dap.javaLaunch.adapterBreakpoint(new DapModels.FileBreakpoints(f, real)));
    }

    /**
     * Sends a step for the current thread. Once the adapter <em>acknowledges</em> it the thread is running
     * again until the next stop, exactly as after {@link #resume()}: leaving the state SUSPENDED kept the
     * execution line, inline values and frame list of the previous stop on screen — and offered
     * Step/Evaluate against frames that no longer exist — for as long as the step took, which for a step
     * over a blocking call is indefinitely. It also made {@link #pause()} a no-op during that time.
     *
     * <p>A step the adapter <em>refuses</em> (java-debug: "the thread is not suspended", when another thread
     * was picked in the selector) changes nothing: the debuggee is still where it was, so the session stays
     * SUSPENDED with its controls usable and the adapter's message is reported.
     */
    void step(java.util.function.BiFunction<DapClient, Integer, java.util.concurrent.CompletableFuture<Void>> request) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.SUSPENDED || dap.stepPendingEpoch == dap.sessionEpoch) {
            return;
        }
        if (dap.refusePostMortem() || dap.refuseWhileEvaluating()) {
            return;
        }
        clearTempBreakpoint(); // a Run to Cursor trip kept from a stop on the way ends with a step
        long epoch = dap.sessionEpoch;
        int threadId = dap.currentThreadId;
        int stops = dap.stopCount;
        dap.stepPendingEpoch = epoch;
        request.apply(c, threadId)
                .whenComplete((v, e) -> dap.fx(() -> {
                    if (dap.stepPendingEpoch == epoch) {
                        dap.stepPendingEpoch = -1;
                    }
                    if (!dap.isCurrent(epoch, c)) {
                        return;
                    }
                    if (e != null) {
                        dap.listener.onError(DapManager.msg(
                                e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                                        ? e.getCause()
                                        : e));
                        return;
                    }
                    if (dap.stopCount != stops || dap.state != DapManager.State.SUSPENDED) {
                        return; // the step has already ended in a new stop (or the session moved on)
                    }
                    dap.stoppedThreads.remove(threadId);
                    dap.stepInFlight = true;
                    dap.setState(DapManager.State.RUNNING);
                }));
    }
}
