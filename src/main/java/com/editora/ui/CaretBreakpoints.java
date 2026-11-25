package com.editora.ui;

import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.dap.CodeLessLines;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The breakpoint commands on the caret line: toggle, edit, add a triggered breakpoint or a logpoint, with the checks
 * they share (the active buffer takes breakpoints, the line has code to stop on). Part of {@link DebugCoordinator}.
 */
final class CaretBreakpoints {

    private final DebugCoordinator d;

    CaretBreakpoints(DebugCoordinator d) {
        this.d = d;
    }

    /** Toggles a breakpoint on the active buffer's caret line. */
    void toggleBreakpointAtCaret() {
        if (d.s().panel != null && d.s().panel.disassemblyView().hasFocus()) {
            d.s().panel.disassemblyView().toggleSelected(); // the selected instruction's (InstructionBreakpoints)
            return;
        }
        EditorBuffer b = breakpointBuffer();
        if (b != null) {
            toggleBreakpointOn(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /** Toggles a breakpoint on {@code line}, the caret's when pressed: a check landing later acts on that line. */
    private void toggleBreakpointOn(EditorBuffer b, int line) {
        if (!b.isDisposed() && breakpointAllowed(b, line, () -> toggleBreakpointOn(b, line))) {
            b.toggleBreakpoint(line);
        }
    }

    /** {@code command} asked again once a check lands, only while the caret is still on {@code line}. */
    Runnable onSameLine(EditorBuffer b, int line, Runnable command) {
        return () -> { // the command reads the active tab: still the one it was given, its caret on the same line
            if (!b.isDisposed() && breakpointBuffer() == b && b.getFocusedArea().getCurrentParagraph() == line) {
                command.run();
            }
        };
    }

    /**
     * Whether a breakpoint may go on {@code line} of {@code b}: anywhere a debugger can stop, which in a {@code .http}
     * file is only a script line (adl, 2026-10-16) — else says so. Removing one is always allowed.
     */
    boolean breakpointAllowed(EditorBuffer b, int line, Runnable retry) {
        if (b.getBreakpointManager().isBreakpoint(line)) {
            return true;
        }
        if (b.isHttpFile()) {
            com.editora.http.HttpScriptLines.Map scripts = b.httpScriptMap();
            if (scripts == null) {
                b.whenHttpScriptMap(retry); // its script lines are parsed off FX: the command goes on when they land
                return false;
            }
            if (scripts.isScriptLine(line)) {
                return true;
            }
            d.host.setStatus(tr("status.debug.httpScriptLinesOnly"));
            return false;
        }
        // B8: a blank or comment-only line has no code to stop on. java-debug even called such a breakpoint
        // verified, and it never stopped.
        String language = b.getLanguage(); // the text is scanned off the FX thread (PERF-01): asked again once known
        var codeLess = d.scan(b, "codeLess:" + line, t -> CodeLessLines.isCodeLess(t, line, language), retry);
        if (codeLess == null) {
            return false;
        }
        if (Boolean.TRUE.equals(codeLess.value())) {
            d.host.setStatus(tr("status.debug.noCodeOnLine"));
            return false;
        }
        return true;
    }

    /**
     * The active buffer if breakpoints can be set in it, else {@code null} after saying why. A file with no
     * debug adapter has no breakpoint gutter: a breakpoint toggled there by key was invisible, could only be
     * removed by pressing the key again on the same line, and was still sent to the adapter in every session.
     */
    @Nullable
    EditorBuffer breakpointBuffer() {
        EditorBuffer b = d.host.activeBuffer();
        if (b == null) {
            return null;
        }
        if (!d.debugSupportEnabled()) {
            d.host.setStatus(tr("statusbar.tip.debugDisabled"));
            return null;
        }
        if (!d.isDebuggableBuffer(b)) {
            d.host.setStatus(tr("status.debug.noBreakpointsHere"));
            return null;
        }
        return b;
    }

    /**
     * Edits the caret line's breakpoint (creating one if absent): its condition, its log message — which
     * makes it a logpoint: the adapter logs and does not suspend — and whether it is enabled. All three
     * already persist, re-anchor and reach the adapter; this form is what reaches them.
     */
    void editBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line, onSameLine(b, line, this::editBreakpointAtCaret))) {
            return;
        }
        BreakpointEditForm.show(
                d.host.overlayHost(),
                b,
                line,
                d.completionSource(false),
                d.completionSource(true),
                d.groups,
                d.triggered,
                d.s().marks.labels());
    }

    /**
     * Debug: Add Triggered Breakpoint — a breakpoint on the caret line that waits, in each debug session, until the
     * breakpoint chosen from a list is hit (VS Code's Add Triggered Breakpoint, IntelliJ's "Disable until hitting the
     * following breakpoint"). On a line with a breakpoint it makes that one wait; choosing the one it waits for
     * already makes it wait for none.
     */
    void addTriggeredBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null || b.getPath() == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line, onSameLine(b, line, this::addTriggeredBreakpointAtCaret))) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint existing = mgr.get(line);
        String selfId = existing == null ? "" : existing.id();
        String waitsFor = existing == null ? "" : existing.triggeredBy();
        List<BreakpointsPicker.Entry> choices = TriggeredBreakpoints.choices(
                d.key(b.getPath()), line + mgr.regionFirstLine(), selfId, d.triggered.entries());
        if (choices.isEmpty()) {
            d.host.setStatus(tr("status.debug.noTriggerChoices"));
            return;
        }
        QuickOpen<BreakpointsPicker.Entry> picker = new QuickOpen<>(
                tr("command.debug.addTriggeredBreakpoint"),
                tr("debug.triggered.prompt", line + 1),
                () -> choices,
                e -> (!waitsFor.isEmpty() && waitsFor.equals(e.breakpoint().id()) ? "✓ " : "    ")
                        + BreakpointsPicker.label(e),
                e -> String.join("  ·  ", com.editora.editor.debug.BreakpointDescription.lines(e.breakpoint())),
                e -> {
                    if (e != null) {
                        linkTriggered(b, line, e);
                    }
                });
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /**
     * Makes the breakpoint on {@code line} of {@code b} (setting one when there is none) wait for {@code trigger}'s,
     * or — when it waits for that one already — for none; says which.
     */
    void linkTriggered(EditorBuffer b, int line, BreakpointsPicker.Entry trigger) {
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            b.toggleBreakpoint(line);
        }
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            return;
        }
        if (bp.isTriggered() && bp.triggeredBy().equals(trigger.breakpoint().id())) {
            d.triggered.link(b, line, null);
            d.host.setStatus(tr("status.debug.triggeredRemoved", line + 1));
            return;
        }
        d.triggered.link(b, line, trigger);
        d.host.setStatus(tr("status.debug.triggeredAdded", line + 1, TriggeredBreakpoints.place(trigger)));
    }

    /**
     * Debug: Add Logpoint — asks for the message to log on the caret line and makes that line a logpoint: the
     * adapter logs the message each time the line runs and the program never suspends (VS Code's Add Logpoint,
     * IntelliJ's Shift+click in the gutter). On a line that already has a breakpoint it edits that one's message,
     * keeping its condition and hit count.
     */
    void addLogpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b != null) {
            addLogpoint(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /** {@link #addLogpointAtCaret} for {@code line} of {@code b} — also what a Shift+click in the gutter runs. */
    void addLogpoint(EditorBuffer b, int line) {
        if (!breakpointAllowed(b, line, () -> addLogpoint(b, line))) {
            return;
        }
        BreakpointEditForm.showLogpoint(d.host.overlayHost(), b, line, d.completionSource(true), d.host::setStatus);
    }
}
