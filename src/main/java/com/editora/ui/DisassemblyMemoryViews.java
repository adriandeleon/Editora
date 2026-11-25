package com.editora.ui;

import java.nio.file.Path;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * The Debug window's Disassembly and Memory views: filling them for the frame shown, going to an address in Memory,
 * and the steps of one machine instruction (Step Over and Step Into while the Disassembly view has the keyboard).
 * Part of {@link DebugCoordinator}.
 */
final class DisassemblyMemoryViews {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    DisassemblyMemoryViews(DebugCoordinator d) {
        this.d = d;
    }

    /** Instructions shown before and after the instruction pointer. */
    static final int DISASSEMBLY_BEFORE = 40;

    static final int DISASSEMBLY_COUNT = 120;

    /**
     * Step Over (F10, the toolbar): one machine instruction while the Disassembly view has the keyboard, as in VS
     * Code; one line otherwise.
     */
    void stepOver() {
        if (d.s().panel != null
                && d.s().panel.disassemblyView().hasFocus()
                && d.dap().supportsInstructionStepping()) {
            d.dap().stepOverInstruction();
        } else {
            d.dap().stepOver();
        }
    }

    /** Step Into: as {@link #stepOver}, one instruction (into a call) while the Disassembly view has the keyboard. */
    void stepInto() {
        if (d.s().panel != null
                && d.s().panel.disassemblyView().hasFocus()
                && d.dap().supportsInstructionStepping()) {
            d.dap().stepIntoInstruction();
        } else {
            d.dap().stepInto();
        }
    }

    /** Debug: Step Over Instruction (and Into): one machine instruction, wherever the keyboard is. */
    void stepInstruction(boolean into) {
        if (!d.dap().supportsInstructionStepping()) {
            d.host.setStatus(tr("status.debug.noInstructionStepping"));
            return;
        }
        if (into) {
            d.dap().stepIntoInstruction();
        } else {
            d.dap().stepOverInstruction();
        }
    }

    /** Debug: Show Disassembly / Show Memory: the Debug window's view, filled for the frame shown. */
    void showLowLevelView(String area) {
        d.ops.openToolWindow();
        d.panel().showArea(area);
    }

    /** Fills the Disassembly or Memory view, when shown, for the stop and frame shown. */
    void refreshLowLevelViews() {
        if (d.s().panel == null) {
            return;
        }
        DebugPanel p = d.s().panel;
        String area = p.shownArea();
        if (d.dap().state() != DapManager.State.SUSPENDED) {
            if (DebugPanel.AREA_DISASSEMBLY.equals(area)) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.idle"));
            }
            return;
        }
        if (DebugPanel.AREA_DISASSEMBLY.equals(area)) {
            DapModels.StackFrameInfo frame = d.s().shownFrame;
            String ip = frame == null ? null : frame.instructionPointer();
            if (!d.dap().supportsDisassemble()) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.unsupported"));
            } else if (ip == null) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.noPointer"));
            } else {
                d.dap().disassemble(ip, -DISASSEMBLY_BEFORE, DISASSEMBLY_COUNT, list -> {
                    if (d.s().shownFrame == frame) {
                        p.disassemblyView().show(list, ip);
                        d.instructionBreakpoints.show();
                    }
                });
            }
        } else if (DebugPanel.AREA_MEMORY.equals(area)) {
            if (!d.dap().supportsReadMemory()) {
                p.memoryView().setMessage(tr("debugpanel.memory.unsupported"));
            } else if (d.s().memoryReference != null) {
                readMemory();
            }
        }
    }

    /** The Memory view at {@code text}: an address as typed, else the address of the expression's value. */
    void memoryGo(String text) {
        DebugMemoryView view = d.panel().memoryView();
        if (text == null || text.isBlank()) {
            return;
        }
        if (d.dap().state() != DapManager.State.SUSPENDED) {
            view.setMessage(tr("debugpanel.memory.idle"));
            return;
        }
        if (!d.dap().supportsReadMemory()) {
            view.setMessage(tr("debugpanel.memory.unsupported"));
            return;
        }
        String literal = DebugMemoryView.literalAddress(text);
        if (literal != null) {
            d.s().memoryReference = literal;
            d.s().memoryOffset = 0;
            readMemory();
            return;
        }
        d.dap().memoryReferenceOf(text, d.s().debugFrameId, ref -> {
            if (ref == null) {
                view.setMessage(tr("debugpanel.memory.noAddress", text));
                return;
            }
            d.s().memoryReference = ref;
            d.s().memoryOffset = 0;
            readMemory();
        });
    }

    /** Reads a page at the Memory view's reference and offset into it. */
    void readMemory() {
        String ref = d.s().memoryReference;
        if (ref == null || d.s().panel == null) {
            return;
        }
        DebugMemoryView view = d.s().panel.memoryView();
        d.dap()
                .readMemory(
                        ref,
                        d.s().memoryOffset,
                        DebugMemoryView.PAGE,
                        view::show,
                        why -> view.setMessage(tr("debugpanel.memory.failed", ref, why)));
    }

    /** Opens {@code file} at 0-based {@code line}: a double-clicked instruction's source line. */
    void openSourceLine(Path file, int line) {
        d.ops.openPath(file);
        EditorBuffer b = d.ops.bufferForPath(file);
        if (b != null && line >= 0) {
            int first = b.getBreakpointManager().regionFirstLine();
            b.getFocusedArea().moveTo(Math.max(0, line - first), 0);
            b.getFocusedArea().requestFollowCaret();
        }
    }
}
