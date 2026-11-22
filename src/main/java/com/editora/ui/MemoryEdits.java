package com.editora.ui;

import java.util.Objects;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;

import static com.editora.i18n.Messages.tr;

/**
 * Edit Bytes… of {@link DebugCoordinator}'s Memory view: a row's bytes changed in the program's memory (DAP's
 * {@code writeMemory}; CodeLLDB, GDB and Delve take it), as Visual Studio's Memory window and VS Code's hex editor
 * change them. The row's bytes are asked for in hex (or as quoted text), only the ones that differ are written, and the
 * page and the Variables view are read again: a variable there may be what changed.
 */
final class MemoryEdits {

    private final DebugCoordinator d;

    MemoryEdits(DebugCoordinator d) {
        this.d = d;
    }

    /** Gives {@code view} its Edit Bytes…. */
    void install(DebugMemoryView view) {
        view.setOnEdit(row -> edit(view, row), () -> d.dap().supportsWriteMemory());
    }

    /** Asks for row {@code row}'s new bytes, then writes the ones that changed. */
    void edit(DebugMemoryView view, int row) {
        DapManager dap = d.dap();
        DapModels.MemoryBlock block = view.block();
        String ref = d.s().memoryReference;
        if (dap.state() != DapManager.State.SUSPENDED || !dap.supportsWriteMemory() || block == null || ref == null) {
            d.host.setStatus(tr("status.debug.noMemoryWrite"));
            return;
        }
        int from = row * DebugMemoryView.ROW;
        long offset = d.s().memoryOffset;
        int stop = d.s().stopSerial;
        String where = DebugMemoryView.addressOf(block, from);
        d.host.promptText(
                tr("debugpanel.memory.editTitle"),
                tr("debugpanel.memory.editLabel", where),
                DebugMemoryView.rowHex(block.data(), row),
                text -> {
                    if (text == null) {
                        return;
                    }
                    if (d.s().stopSerial != stop
                            || !Objects.equals(d.s().memoryReference, ref)
                            || d.s().memoryOffset != offset
                            || view.block() != block) {
                        d.host.setStatus(tr("status.debug.memoryMovedOn")); // these bytes are no longer the shown ones
                        return;
                    }
                    write(block, from, ref, offset, text);
                });
    }

    private void write(DapModels.MemoryBlock block, int from, String ref, long offset, String text) {
        byte[] typed = DebugMemoryView.parseBytes(text);
        if (typed == null) {
            d.host.setStatus(tr("status.debug.memoryBadBytes", text.strip()));
            return;
        }
        int room = block.data().length - from;
        if (typed.length > room) {
            d.host.setStatus(
                    tr("status.debug.memoryTooLong", typed.length, room, DebugMemoryView.addressOf(block, from)));
            return;
        }
        DebugMemoryView.Edit edit = DebugMemoryView.edit(block.data(), from, typed);
        if (edit == null) {
            return; // nothing changed
        }
        String at = DebugMemoryView.addressOf(block, edit.at());
        int count = edit.bytes().length;
        d.dap()
                .writeMemory(
                        ref,
                        offset + edit.at(),
                        edit.bytes(),
                        written -> {
                            d.host.setStatus(
                                    written < count
                                            ? tr("status.debug.memoryWrittenPartly", written, count, at)
                                            : tr("status.debug.memoryWritten", count, at));
                            reread();
                        },
                        why -> d.host.setStatus(tr("status.debug.memoryWriteFailed", at, why)));
    }

    /** The page again, and the frame's variables: either may show the bytes that changed. */
    private void reread() {
        d.readMemory();
        DapModels.StackFrameInfo frame = d.s().shownFrame;
        DebugPanel panel = d.s().panel;
        if (frame != null && panel != null) {
            d.dap().scopes(frame.id(), scopes -> {
                if (d.s().shownFrame == frame) {
                    panel.setScopes(scopes);
                }
            });
        }
    }
}
