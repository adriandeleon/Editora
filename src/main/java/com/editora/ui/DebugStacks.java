package com.editora.ui;

import java.nio.file.Path;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Thread Dump and Parallel Stacks (IntelliJ's Get Thread Dump, Visual Studio's Parallel Stacks): every thread's stack
 * at one moment, taken from a paused program, or one paused for it that goes on after. Each window remembers the
 * session it shows: its frames open through that session's adapter and Take Again takes that session's stacks. Part
 * of {@link DebugCoordinator}.
 */
final class DebugStacks {

    private final DebugCoordinator d;
    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    DebugStacks(DebugCoordinator d) {
        this.d = d;
        this.host = d.host;
        this.ops = d.ops;
    }

    /** A session started or ended: the windows' Take Again follows it. */
    void sessionChanged() {
        DebugThreadDumpWindow dump = threadDumpWindow;
        if (dump != null) {
            dump.sessionChanged();
        }
        DebugParallelStacksWindow parallel = parallelStacksWindow;
        if (parallel != null) {
            parallel.sessionChanged();
        }
    }

    /** The Thread Dump window, built on the first dump; one for all sessions, showing the last dump taken. */
    @Nullable
    DebugThreadDumpWindow threadDumpWindow;

    /** Takes every thread's stack for Parallel Stacks ({@code parallel}) or Thread Dump. */
    void take(boolean parallel) {
        DebugSession session = d.s();
        session.dumpForParallel = parallel && session.dap.state() != DapManager.State.INACTIVE;
        switch (session.dap.state()) {
            case SUSPENDED -> takeThreadDump(session, false, null);
            case RUNNING -> {
                session.dumpPause = true;
                host.setStatus(tr("status.debug.threadDumpPausing"));
                session.dap.pause();
            }
            default -> host.setStatus(tr("status.debug.threadDumpNoSession"));
        }
    }

    /** The {@code pause} stop Thread Dump asked for: the dump is taken, then the program goes on. */
    void dumpAndContinue() {
        DebugSession session = d.s();
        int serial = session.stopSerial;
        takeThreadDump(session, true, () -> {
            // Unless the user acted first (a step, Stop) or it stopped again meanwhile.
            if (serial == session.stopSerial && session.dap.state() == DapManager.State.SUSPENDED) {
                session.dap.resume();
            }
        });
    }

    /**
     * Asks {@code session} for every thread's stack and shows the dump; {@code afterwards} runs once the stacks are in
     * (the program may go on then), before the window opens.
     */
    void takeThreadDump(DebugSession session, boolean pausedForIt, @Nullable Runnable afterwards) {
        String name = session.sessionLabel.isEmpty() ? dumpSessionName(session) : session.sessionLabel;
        boolean parallel = session.dumpForParallel;
        session.dumpForParallel = false;
        session.dap.threadStacks(stacks -> {
            if (afterwards != null) {
                afterwards.run();
            }
            if (stacks.isEmpty()) {
                host.setStatus(tr("status.debug.threadDumpEmpty"));
                return;
            }
            ThreadDump dump = new ThreadDump(name, java.time.LocalTime.now(), pausedForIt, stacks);
            if (parallel) {
                parallelStacksSession = session;
                parallelStacksWindow().show(host.window(), new ParallelStacks(dump));
                host.setStatus(tr("status.debug.parallelStacksTaken", stacks.size()));
                return;
            }
            threadDumpSession = session;
            threadDumpWindow().show(host.window(), dump);
            host.setStatus(tr("status.debug.threadDumpTaken", stacks.size()));
        });
    }

    /** A session without a label is named by the file it debugs, else plainly. */
    private static String dumpSessionName(DebugSession session) {
        Path file = session.dap.debugFile();
        return file != null && file.getFileName() != null ? file.getFileName().toString() : tr("threaddump.untitled");
    }

    /**
     * The sessions the Thread Dump and Parallel Stacks windows show stacks of: a frame opens through it (its source
     * references are its adapter's), and Take Again takes its stacks, whichever session is focused by then.
     */
    private @Nullable DebugSession threadDumpSession;

    private @Nullable DebugSession parallelStacksSession;

    /** Runs {@code r} as {@code session}'s while it is still open, else as the focused one's. */
    private void asSessionOf(@Nullable DebugSession session, Runnable r) {
        if (session != null && d.sessions().all().contains(session)) {
            d.inSession(session, r);
        } else {
            r.run();
        }
    }

    private DebugThreadDumpWindow threadDumpWindow() {
        DebugThreadDumpWindow window = threadDumpWindow;
        if (window == null) {
            window = new DebugThreadDumpWindow(new DebugThreadDumpWindow.Actions() {
                @Override
                public void openFrame(DapModels.StackFrameInfo frame) {
                    asSessionOf(threadDumpSession, () -> openDumpFrame(frame));
                }

                @Override
                public void openText(String title, String text) {
                    ops.openReadOnlyDoc(title, text, "text");
                }

                @Override
                public void takeAgain() {
                    asSessionOf(threadDumpSession, () -> take(false));
                }

                @Override
                public boolean canTakeAgain() {
                    return canTakeStacks(threadDumpSession);
                }
            });
            threadDumpWindow = window;
        }
        return window;
    }

    @Nullable
    DebugParallelStacksWindow parallelStacksWindow;

    private DebugParallelStacksWindow parallelStacksWindow() {
        DebugParallelStacksWindow window = parallelStacksWindow;
        if (window == null) {
            window = new DebugParallelStacksWindow(new DebugParallelStacksWindow.Actions() {
                @Override
                public void openFrame(DapModels.StackFrameInfo frame) {
                    asSessionOf(parallelStacksSession, () -> openDumpFrame(frame));
                }

                @Override
                public void takeAgain() {
                    asSessionOf(parallelStacksSession, () -> take(true));
                }

                @Override
                public boolean canTakeAgain() {
                    return canTakeStacks(parallelStacksSession);
                }
            });
            parallelStacksWindow = window;
        }
        return window;
    }

    /** Whether {@code session} (else the focused one) has a program to take stacks of. */
    private boolean canTakeStacks(@Nullable DebugSession session) {
        boolean[] can = {false};
        asSessionOf(session, () -> {
            DapManager.State state = d.dap().state();
            can[0] = state == DapManager.State.SUSPENDED || state == DapManager.State.RUNNING;
        });
        return can[0];
    }

    /**
     * Opens a dumped frame's code: its file at its line; a library class's source, as the call stack opens one; or the
     * debugger's own copy, while the session lasts. The frame is a snapshot's, so the execution line is not drawn.
     */
    private void openDumpFrame(DapModels.StackFrameInfo frame) {
        Path file = frame.file();
        if (file != null && d.hasSource(frame)) {
            d.openSourceLine(file, frame.line());
            return;
        }
        Path anchor = d.dap().debugFile();
        String uri = frame.sourceUri();
        if (uri != null && anchor != null) {
            ops.openLibraryFrame(anchor, uri, frame.line(), lib -> {});
            return;
        }
        DapModels.LoadedSource reference = frame.reference();
        if (reference != null && d.dap().state() != DapManager.State.INACTIVE) {
            d.openReferenceSource(reference, doc -> {
                int line = Math.max(0, frame.line());
                doc.getFocusedArea()
                        .moveTo(
                                Math.min(
                                        line,
                                        doc.getFocusedArea().getParagraphs().size() - 1),
                                0);
                doc.getFocusedArea().requestFollowCaret();
            });
            return;
        }
        host.setStatus(tr("status.debug.noSource", DebugPanelText.frameName(frame.name())));
    }
}
