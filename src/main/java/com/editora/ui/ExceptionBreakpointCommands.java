package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Exception breakpoints in the window ({@link ExceptionBreakpoints} decides, this acts): the check made at each
 * exception stop, the exception filters every session is told to add for them, and their rows in Debug: Exception
 * Breakpoints… with the form that adds, edits and removes one. Part of {@link DebugCoordinator}, whose session and
 * status it uses.
 */
final class ExceptionBreakpointCommands {

    private final DebugCoordinator d;
    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    ExceptionBreakpointCommands(DebugCoordinator d, CoordinatorHost host, DebugCoordinator.Ops ops) {
        this.d = d;
        this.host = host;
        this.ops = ops;
    }

    /**
     * An exception stop in a session whose language has exception breakpoints, while the user does not stop on every
     * thrown exception: Editora asks which exception it is and shows the stop only when an exception breakpoint
     * names its type and its condition holds ({@link ExceptionBreakpoints}); else the program goes on without it being
     * shown. An uncaught exception's stop is the user's own choice and is shown. True when the check is under way
     * (the stop held quiet meanwhile).
     */
    boolean stop(int threadId, String reason, List<DapModels.StackFrameInfo> frames, Runnable again) {
        if (!"exception".equals(reason) || d.breakpointsMuted()) {
            return false;
        }
        DebugSession session = d.s();
        String language = session.dap.sessionLanguage();
        List<com.editora.config.ExceptionBreakpoint> mine =
                ExceptionBreakpoints.enabled(ops.exceptionBreakpoints(), language);
        String caught = com.editora.dap.ExceptionFilterCatalog.caughtFilter(language);
        if (mine.isEmpty()
                || (caught != null && session.dap.exceptionFiltersFor(language).contains(caught))) {
            return false; // none, or the user stops on every thrown exception anyway
        }
        session.quietStop = session.stopSerial;
        int serial = session.stopSerial;
        int depth = Math.max(session.dap.shownStack().depth(), frames.size());
        session.dap.exceptionInfo(
                threadId,
                info -> d.inSession(session, () -> {
                    if (serial == session.stopSerial && session.dap.state() == DapManager.State.SUSPENDED) {
                        decideException(session, language, info, mine, frames, depth, again);
                    }
                }));
        return true;
    }

    private void decideException(
            DebugSession session,
            String language,
            DapModels.@Nullable ExceptionInfo info,
            List<com.editora.config.ExceptionBreakpoint> mine,
            List<DapModels.StackFrameInfo> frames,
            int depth,
            Runnable again) {
        String type = info == null ? "" : info.type();
        Runnable show = () -> {
            session.lastExceptionType = type;
            session.lastExceptionFrames = frames;
            session.lastExceptionDepth = depth;
            session.filtersPassed = true;
            again.run();
            if (info != null) {
                d.showExceptionInfo(info);
            }
        };
        if (info != null && ExceptionBreakpoints.uncaught(info.breakMode())) {
            show.run(); // the user's Uncaught choice stopped it, not an exception breakpoint
            return;
        }
        List<com.editora.config.ExceptionBreakpoint> hits = ExceptionBreakpoints.matching(mine, language, type);
        boolean passingUp = type.equals(session.lastExceptionType)
                && ExceptionBreakpoints.propagating(
                        session.lastExceptionFrames, session.lastExceptionDepth, frames, depth);
        if (passingUp) {
            session.lastExceptionFrames = frames; // and further out, still the same one
            session.lastExceptionDepth = depth;
        }
        if (hits.isEmpty() || passingUp) {
            session.dap.resume();
            return;
        }
        if (hits.stream().anyMatch(b -> b.condition().isEmpty()) || frames.isEmpty()) {
            show.run();
            return;
        }
        checkConditions(session, hits, 0, frames.getFirst().id(), session.stopSerial, show);
    }

    /** Evaluates the matching breakpoints' conditions in turn in the frame that threw: one that holds shows the stop. */
    private void checkConditions(
            DebugSession session,
            List<com.editora.config.ExceptionBreakpoint> hits,
            int at,
            int frameId,
            int serial,
            Runnable show) {
        if (at >= hits.size()) {
            session.dap.resume(); // none holds
            return;
        }
        String condition = hits.get(at).condition();
        session.dap.evaluate(
                condition,
                frameId,
                "watch",
                ev -> d.inSession(session, () -> {
                    if (serial != session.stopSerial || session.dap.state() != DapManager.State.SUSPENDED) {
                        return;
                    }
                    if (ev == null || ev.failed()) {
                        // As a conditional breakpoint whose condition cannot be evaluated: stop, and say why.
                        show.run();
                        host.setStatus(
                                tr("status.debug.exceptionConditionFailed", condition, ev == null ? "" : ev.text()));
                    } else if (ExceptionBreakpoints.holds(ev.text())) {
                        show.run();
                    } else {
                        checkConditions(session, hits, at + 1, frameId, serial, show);
                    }
                }));
    }

    /**
     * Tells every session the exception filters this project's exception breakpoints need (none while breakpoints are
     * muted): its adapter then stops on thrown exceptions of those languages for {@link #stop} to check.
     */
    void push() {
        Map<String, List<String>> extras =
                d.breakpointsMuted() ? Map.of() : ExceptionBreakpoints.extras(ops.exceptionBreakpoints());
        Map<String, List<String>> types =
                d.breakpointsMuted() ? Map.of() : ExceptionBreakpoints.adapterTypes(ops.exceptionBreakpoints());
        d.forEachSession(() -> d.dap().setExceptionExtras(extras, types));
    }

    /**
     * Debug: Exception Breakpoints — a picker over the filters the adapter offers (the live session's
     * {@code exceptionBreakpointFilters}, else those that language's adapter is known to offer), each marked
     * when on; choosing one turns it on or off. A language starts with its adapter's defaults (debugpy: uncaught
     * exceptions), and a choice holds for that language's sessions from then on.
     */
    void pick() {
        String language = d.exceptionLanguage();
        if (language == null) {
            host.setStatus(tr("status.debug.exceptionsNoLanguage"));
            return;
        }
        List<DapModels.ExceptionFilterOption> options = d.dap().exceptionFilterOptions(language);
        QuickOpen<ExceptionRow> picker = new QuickOpen<>(
                tr("command.debug.toggleExceptionBreakpoints"),
                tr("debug.exceptions.prompt", DebugCoordinator.debugAdapterLabel(language)),
                () -> exceptionRows(language, options),
                row -> exceptionRowText(language, row),
                row -> switch (row) {
                    case ExceptionRow.Filter f -> f.option().filter();
                    case ExceptionRow.Typed t -> tr("exceptionbp.rowDetail");
                    case ExceptionRow.Add a -> tr("exceptionbp.addDetail");
                },
                row -> {
                    switch (row) {
                        case null -> {}
                        case ExceptionRow.Filter f ->
                            d.toggleExceptionFilter(language, f.option().filter());
                        case ExceptionRow.Typed t -> editExceptionBreakpoint(t.index(), language);
                        case ExceptionRow.Add a -> editExceptionBreakpoint(-1, language);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** A row of the Exception Breakpoints picker: an adapter filter, an exception breakpoint, or Add. */
    sealed interface ExceptionRow {
        record Filter(DapModels.ExceptionFilterOption option) implements ExceptionRow {}

        /** The project's exception breakpoint at {@code index}. */
        record Typed(int index, com.editora.config.ExceptionBreakpoint breakpoint) implements ExceptionRow {}

        record Add() implements ExceptionRow {}
    }

    /** The picker's rows: {@code language}'s filters, its exception breakpoints, then Add Exception Breakpoint. */
    List<ExceptionRow> exceptionRows(String language, List<DapModels.ExceptionFilterOption> options) {
        List<ExceptionRow> rows = new ArrayList<>();
        options.forEach(o -> rows.add(new ExceptionRow.Filter(o)));
        List<com.editora.config.ExceptionBreakpoint> all = ops.exceptionBreakpoints();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).language().equals(language)) {
                rows.add(new ExceptionRow.Typed(i, all.get(i)));
            }
        }
        rows.add(new ExceptionRow.Add());
        return rows;
    }

    private String exceptionRowText(String language, ExceptionRow row) {
        return switch (row) {
            case ExceptionRow.Filter f -> d.exceptionRow(language, f.option());
            case ExceptionRow.Typed t ->
                (t.breakpoint().enabled() ? "✓ " : "    ")
                        + tr("exceptionbp.row", ExceptionBreakpoints.label(t.breakpoint(), tr("exceptionbp.anyType")));
            case ExceptionRow.Add a -> "＋ " + tr("command.debug.addExceptionBreakpoint");
        };
    }

    /**
     * Debug: Add Exception Breakpoint ({@code debug.addExceptionBreakpoint}): the form for a new one, its type the
     * exception of the stop shown (when it is one), its language the session's or the active file's.
     */
    void add() {
        String language = d.exceptionLanguage();
        editExceptionBreakpoint(-1, language == null ? "java" : language);
    }

    /** The form for the project's exception breakpoint at {@code index} (-1: a new one of {@code language}). */
    void editExceptionBreakpoint(int index, String language) {
        d.restoreExceptionFilters();
        List<com.editora.config.ExceptionBreakpoint> all = new ArrayList<>(ops.exceptionBreakpoints());
        boolean isNew = index < 0 || index >= all.size();
        DapModels.ExceptionInfo shown = d.dap().state() == DapManager.State.SUSPENDED ? d.s().stopException : null;
        com.editora.config.ExceptionBreakpoint initial = isNew
                ? new com.editora.config.ExceptionBreakpoint(language, shown == null ? "" : shown.type(), "", true)
                : all.get(index);
        exceptionBreakpointEditor().edit(initial, isNew).ifPresent(result -> {
            if (result.remove()) {
                removed(all.remove(index), index);
            } else if (result.breakpoint() != null) {
                if (isNew) {
                    all.add(result.breakpoint());
                } else {
                    all.set(index, result.breakpoint());
                }
                host.setStatus(tr(
                        "status.debug.exceptionBreakpointSaved",
                        ExceptionBreakpoints.label(result.breakpoint(), tr("exceptionbp.anyType"))));
            }
            ops.persistExceptionBreakpoints(all);
            push();
        });
    }

    /**
     * The form's Remove took {@code was} out of the list at {@code index}: it has a typed type, and often a condition,
     * so it records how to come back there, and the status line says so.
     */
    private void removed(com.editora.config.ExceptionBreakpoint was, int index) {
        String label = ExceptionBreakpoints.label(was, tr("exceptionbp.anyType"));
        host.removalUndo()
                .removed(
                        tr("status.debug.exceptionBreakpointRemoved", label),
                        tr("status.debug.exceptionBreakpointRestored", label),
                        () -> restore(was, index));
    }

    /**
     * Puts {@code was} back at {@code index} of the project's exception breakpoints (the end, when the list is shorter
     * now). False, changing nothing, when the same one was added again since.
     */
    boolean restore(com.editora.config.ExceptionBreakpoint was, int index) {
        List<com.editora.config.ExceptionBreakpoint> all = new ArrayList<>(ops.exceptionBreakpoints());
        if (all.contains(was)) {
            return false;
        }
        all.add(Math.min(index, all.size()), was);
        ops.persistExceptionBreakpoints(all);
        push();
        return true;
    }

    /** How an exception breakpoint is edited: the form, or what a test answers. */
    interface ExceptionBreakpointEditor {
        java.util.Optional<ExceptionBreakpointDialog.Result> edit(
                com.editora.config.ExceptionBreakpoint initial, boolean isNew);
    }

    /** The form's stand-in, set by a test. */
    @Nullable
    ExceptionBreakpointEditor exceptionEditor;

    private ExceptionBreakpointEditor exceptionBreakpointEditor() {
        ExceptionBreakpointEditor e = exceptionEditor;
        return e != null ? e : (initial, isNew) -> ExceptionBreakpointDialog.edit(host.window(), initial, isNew);
    }
}
