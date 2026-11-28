package com.editora.dap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The machine-level requests: instruction breakpoints, disassembly (with GDB's way of reading backwards from
 * the instruction pointer), and reading and writing memory.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class LowLevelRequests {
    private final DapManager dap;

    LowLevelRequests(DapManager dap) {
        this.dap = dap;
    }

    /**
     * Sets the shown session's instruction breakpoints to {@code addresses}; what the adapter made of each goes to
     * {@code cb} on the FX thread (none when the session ended or the request failed, with why to {@code failed}).
     */
    void setInstructionBreakpoints(
            List<String> addresses, Consumer<List<DapModels.InstructionBreakpoint>> cb, Consumer<String> failed) {
        DapClient c = dap.client;
        setInstructionBreakpoints(addresses, c == null ? Map.of() : c.instructionConditions(), cb, failed);
    }

    /** As above, with each address's {@code conditions}. */
    void setInstructionBreakpoints(
            List<String> addresses,
            Map<String, DapModels.InstructionConditions> conditions,
            Consumer<List<DapModels.InstructionBreakpoint>> cb,
            Consumer<String> failed) {
        DapClient c = dap.client;
        if (c == null || !c.supportsInstructionBreakpoints()) {
            return;
        }
        c.setInstructionBreakpoints(addresses, conditions)
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e != null || list == null) {
                        failed.accept(e == null ? "" : DapManager.msg(e));
                    } else {
                        cb.accept(list);
                    }
                }));
    }

    /**
     * {@code count} instructions around {@code memoryReference} from {@code instructionOffset} on (to {@code cb} on the
     * FX thread; empty when the adapter could not, or the session ended).
     */
    void disassemble(
            String memoryReference, int instructionOffset, int count, Consumer<List<DapModels.Instruction>> cb) {
        DapClient c = dap.client;
        if (c == null || !c.supportsDisassemble()) {
            cb.accept(List.of());
            return;
        }
        if (instructionOffset < 0 && dap.isGdbSession()) {
            disassembleBackOnGdb(c, memoryReference, -instructionOffset, count, 0, cb);
            return;
        }
        c.disassemble(memoryReference, instructionOffset, count)
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(e != null || list == null ? List.of() : list);
                    }
                }));
    }

    /**
     * {@code count} instructions, {@code before} of them before {@code address}, from GDB: GDB (16) counts a negative
     * {@code instructionOffset} from the end of a listing forward from the address, so the instructions before it are
     * listed from bytes before it. x86 instructions vary in length: a start byte inside one decodes wrongly until the
     * listing falls into step, so a listing that never reaches the address exactly is tried again a byte further back;
     * after {@link DapManager#GDB_SYNC_TRIES}, the listing starts at the address.
     */
    private void disassembleBackOnGdb(
            DapClient c, String address, int before, int count, int attempt, Consumer<List<DapModels.Instruction>> cb) {
        if (attempt >= DapManager.GDB_SYNC_TRIES) {
            c.disassemble(address, 0, count - before)
                    .whenComplete((list, e) -> dap.fx(() -> {
                        if (dap.client == c) {
                            cb.accept(e != null || list == null ? List.of() : list);
                        }
                    }));
            return;
        }
        int back = before * DapManager.GDB_BYTES_PER_INSTRUCTION + attempt;
        // As many instructions as those bytes could hold (one byte each), then the rest after the address.
        c.disassemble(address, -back, 0, back + count - before)
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    List<DapModels.Instruction> around =
                            e != null || list == null ? null : aroundAddress(list, address, before, count);
                    if (around != null) {
                        cb.accept(around);
                    } else {
                        disassembleBackOnGdb(c, address, before, count, attempt + 1, cb);
                    }
                }));
    }

    /**
     * Of {@code listing}, the {@code count} instructions from {@code before} before the one at {@code address}; null
     * when no instruction of it starts at {@code address} (the listing decoded out of step). Pure.
     */
    static @Nullable List<DapModels.Instruction> aroundAddress(
            List<DapModels.Instruction> listing, String address, int before, int count) {
        Long wanted = parseAddress(address);
        int at = -1;
        for (int i = 0; i < listing.size() && wanted != null; i++) {
            if (wanted.equals(parseAddress(listing.get(i).address()))) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        int from = Math.max(0, at - before);
        return List.copyOf(listing.subList(from, Math.min(listing.size(), from + count)));
    }

    /** {@code 0x…} or decimal as a number; null when it is neither. Pure. */
    static @Nullable Long parseAddress(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.strip();
        try {
            return a.startsWith("0x") || a.startsWith("0X")
                    ? Long.parseUnsignedLong(a.substring(2), 16)
                    : Long.parseUnsignedLong(a);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code count} bytes at {@code memoryReference} + {@code offset} (to {@code cb} on the FX thread; null when they
     * could not be read, with why to {@code failed}).
     */
    void readMemory(
            String memoryReference,
            long offset,
            int count,
            Consumer<DapModels.MemoryBlock> cb,
            Consumer<String> failed) {
        DapClient c = dap.client;
        if (c == null || !c.supportsReadMemory()) {
            failed.accept("");
            return;
        }
        c.readMemory(memoryReference, offset, count)
                .whenComplete((block, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e != null || block == null) {
                        failed.accept(e == null ? "" : DapManager.msg(e));
                    } else {
                        cb.accept(block);
                    }
                }));
    }

    /**
     * Writes {@code data} at {@code memoryReference} + {@code offset} (how many bytes were written to {@code cb} on the
     * FX thread; why not, to {@code failed}).
     */
    void writeMemory(String memoryReference, long offset, byte[] data, Consumer<Integer> cb, Consumer<String> failed) {
        DapClient c = dap.client;
        if (c == null || !c.supportsWriteMemory()) {
            failed.accept("");
            return;
        }
        c.writeMemory(memoryReference, offset, data)
                .whenComplete((written, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e != null || written == null) {
                        failed.accept(e == null ? "" : DapManager.msg(e));
                    } else {
                        cb.accept(written);
                    }
                }));
    }

    /**
     * The memory reference of {@code expression}'s value in frame {@code frameId} (to {@code cb} on the FX thread; null
     * when it has none or could not be evaluated).
     */
    void memoryReferenceOf(String expression, int frameId, Consumer<@Nullable String> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        String address = DapManager.addressOf(expression);
        c.memoryReferenceOf(expression, frameId)
                .thenCompose(ref -> ref != null || address == null
                        ? CompletableFuture.completedFuture(ref)
                        : c.memoryReferenceOf(address, frameId))
                .whenComplete((ref, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(e != null ? null : ref);
                    }
                }));
    }
}
