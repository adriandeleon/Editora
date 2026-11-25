package com.editora.ui;

import java.util.List;

import javafx.scene.Node;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The execution line in the editor: the stopped frame's file opened and its line marked, and the exception card under
 * the line that threw. Part of {@link DebugCoordinator}.
 */
final class ExecutionLine {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    ExecutionLine(DebugCoordinator d) {
        this.d = d;
    }

    /** Opens the frame's file and paints the execution-line highlight there. */
    void highlightFrame(DapModels.StackFrameInfo frame) {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        clearExecHighlight();
        if (frame == null) {
            d.mapFollow.mapStop(MapDebugStop.RUNNING);
            return;
        }
        DebugPanel stackPanel = d.s().panel;
        d.mapFollow.mapStop(new MapDebugStop(
                frame, stackPanel == null ? List.of() : stackPanel.callStack(), frame.id() == d.s().exceptionFrameId));
        if (!d.hasSource(frame)) {
            if (frame.sourceUri() != null && d.dap().debugFile() != null) {
                // B5: a JDK or library class jdtls can supply the text of (java/classFileContents) — shown in its
                // read-only library tab, as go-to-definition and the console's stack-trace links already do.
                d.ops.openLibraryFrame(d.dap().debugFile(), frame.sourceUri(), frame.line(), lib -> {
                    if (d.s().shownFrame == frame && d.dap().state() == DapManager.State.SUSPENDED) {
                        clearExecHighlight();
                        paintExecutionLine(lib, frame);
                    }
                });
                return;
            }
            if (frame.reference() != null) {
                // Code the debugger has only in memory (Node's internals, eval'd code): its text, read-only, with the
                // execution line, as VS Code shows it.
                d.openReferenceSource(frame.reference(), doc -> {
                    if (d.s().shownFrame == frame && d.dap().state() == DapManager.State.SUSPENDED) {
                        clearExecHighlight();
                        paintExecutionLine(doc, frame);
                    }
                });
                return;
            }
            // Asking for it anyway opened a loading tab, failed, removed the tab again and reported "Failed to
            // open" as an error — on every stop and step inside library code. Say what is the case instead;
            // the frame stays selected with its variables, and the other frames stay a click away.
            d.host.setStatus(tr("status.debug.noSource", frame.name()));
            return;
        }
        // Revealing the line focuses the editor (openPath calls requestFocus on the buffer). When the user
        // is driving the session from the Debug panel — the single-key step shortcuts — that yanks focus to
        // the code after every step, so the next key press is lost and they must re-focus the panel each
        // time. Preserve the panel's focus across the reveal in that case only; a fresh breakpoint hit while
        // editing (focus not in the panel) still lands in the code at the stopped line, as before.
        Node keepFocus = debugPanelFocusOwner();
        d.ops.openPath(frame.file()); // opens or focuses the tab
        // Take the frame's OWN buffer, not whatever is active — mirrors applyInlineValues.
        EditorBuffer b = d.ops.bufferForPath(frame.file());
        if (b != null) {
            paintExecutionLine(b, frame);
        }
        if (keepFocus != null) {
            d.later(keepFocus::requestFocus); // after openPath's own requestFocus, so the panel wins
        }
    }

    /** The frame whose line the editor marks as the execution line, or null. */
    private DapModels.@Nullable StackFrameInfo paintedFrame;

    private @Nullable DebugExceptionCard exceptionCard;

    /**
     * Opens the exception card under the line that threw, when that frame is the one shown and the adapter has said
     * which exception it is. By itself ({@code asked} false) only once per stop: closing it keeps it closed.
     */
    private void showExceptionCard(boolean asked) {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        DapModels.ExceptionInfo info = d.s().stopException;
        DapModels.StackFrameInfo frame = paintedFrame;
        EditorBuffer b = d.execHighlightBuffer;
        if (info == null
                || frame == null
                || b == null
                || frame.id() != d.s().exceptionFrameId
                || d.dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!asked && d.s().exceptionCardStop == d.s().stopSerial) {
            return;
        }
        d.s().exceptionCardStop = d.s().stopSerial;
        if (exceptionCard == null) {
            exceptionCard = new DebugExceptionCard(new DebugExceptionCard.Actions(
                    () -> {
                        if (d.exceptionToggleOn(true)) {
                            d.toggleExceptionKind(true); // says so on the status line
                        }
                    },
                    () -> d.host.setStatus(tr("status.debug.exceptionCopied"))));
        }
        int line = frame.line() - b.getBreakpointManager().regionFirstLine();
        exceptionCard.show(b.getFocusedArea(), line, info, info.caught() && d.exceptionToggleOn(true));
    }

    void hideExceptionCard() {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        if (exceptionCard != null) {
            exceptionCard.hide();
        }
    }

    /** The exception card (test read; null until an exception stop opened it). */
    @Nullable
    DebugExceptionCard exceptionCard() {
        return exceptionCard;
    }

    /**
     * Debug: Show Exception — opens the card of the exception the program stopped on again, going back to the frame
     * that threw when another one is shown.
     */
    void showException() {
        if (d.s().stopException == null || d.dap().state() != DapManager.State.SUSPENDED) {
            d.host.setStatus(tr("status.debug.noException"));
            return;
        }
        DapModels.StackFrameInfo frame = paintedFrame;
        if (frame == null || frame.id() != d.s().exceptionFrameId) {
            if (!d.panel().selectFrameById(d.s().exceptionFrameId)) {
                d.host.setStatus(tr("status.debug.noException"));
                return;
            }
            d.s().exceptionCardStop = -1; // the frame's paint opens it, once laid out
            return;
        }
        showExceptionCard(true);
    }

    void paintExecutionLine(EditorBuffer b, DapModels.StackFrameInfo frame) {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        d.execHighlightBuffer = b;
        d.ops.afterBufferLoad(b, () -> {
            // The user may resume or select another frame while this file is still loading. Never let
            // that obsolete completion repaint an execution marker that clearExecHighlight removed.
            if (d.execHighlightBuffer == b) {
                // A narrowed buffer shows only its region: the frame's file line is region-relative
                // there (a stop outside the region has no line to show and paints nothing).
                b.setExecutionLine(frame.line() - b.getBreakpointManager().regionFirstLine());
                b.setExecutionLineException(frame.id() == d.s().exceptionFrameId);
                if (frame.id() == d.s().stepTimeFrameId && d.s().stepTime != null) {
                    b.setStepTime(frame.line() - b.getBreakpointManager().regionFirstLine(), d.s().stepTime);
                }
                paintedFrame = frame;
                if (frame.id() == d.s().exceptionFrameId) {
                    d.later(() -> showExceptionCard(false)); // once the line has been laid out
                } else {
                    hideExceptionCard(); // another frame's line: the card belongs to the one that threw
                }
            }
        });
    }

    /** The Debug-panel descendant that currently owns keyboard focus (so it can be restored across an
     *  editor-focusing reveal), or {@code null} when focus is elsewhere. */
    @Nullable
    Node debugPanelFocusOwner() {
        var window = d.host.window();
        var scene = window == null ? null : window.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (d.s().panel != null && n == d.s().panel) {
                return owner;
            }
        }
        return null;
    }

    void clearExecHighlight() {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        paintedFrame = null;
        hideExceptionCard();
        if (d.execHighlightBuffer != null) {
            d.execHighlightBuffer.clearExecutionLine();
            d.execHighlightBuffer = null;
        }
    }

    /** Says which exception the stop is on: the Debug window's state line, the exception card and the status bar. */
    void showExceptionInfo(DapModels.ExceptionInfo info) {
        // Which exception: the one thing an exception stop has to say, and the stop event does not.
        d.panel().setStoppedException(DebugFeedback.exceptionSummary(info, true));
        d.panel().setExceptionInfo(info);
        d.s().stopException = info;
        showExceptionCard(false); // under the line that threw, when it is the one shown
        d.host.setStatus(tr("status.debug.stoppedOnException", DebugFeedback.exceptionSummary(info, false)));
    }
}
