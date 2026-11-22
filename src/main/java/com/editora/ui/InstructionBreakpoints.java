package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;

import static com.editora.i18n.Messages.tr;

/**
 * Instruction breakpoints of {@link DebugCoordinator}: a breakpoint on one machine instruction of the Disassembly view
 * (DAP's {@code setInstructionBreakpoints}; CodeLLDB, GDB and Delve take them), as VS Code's Disassembly view and
 * IntelliJ's have. They belong to the session that is shown: an address is that process's, so they are not kept for
 * the next run. A stop on one opens the Disassembly view at it.
 */
final class InstructionBreakpoints {

    /** The stop reason adapters give for a stop on an instruction breakpoint (DAP's own word for it). */
    static final String STOP_REASON = "instruction breakpoint";

    private final DebugCoordinator d;

    /** The addresses the adapter could not set, of the last answer. */
    private List<String> notSet = List.of();

    InstructionBreakpoints(DebugCoordinator d) {
        this.d = d;
    }

    /** Gives {@code view} its margin and its Toggle Breakpoint row. */
    void install(DebugDisassemblyView view) {
        view.setOnToggleBreakpoint(this::toggle, () -> d.dap().supportsInstructionBreakpoints());
    }

    /** Puts a breakpoint on {@code instruction}, or takes the one there off. */
    void toggle(DapModels.Instruction instruction) {
        DapManager dap = d.dap();
        if (dap.state() != DapManager.State.SUSPENDED || !dap.supportsInstructionBreakpoints()) {
            d.host.setStatus(tr("status.debug.noInstructionBreakpoints"));
            return;
        }
        List<String> now = toggled(dap.instructionBreakpoints(), instruction.address());
        boolean adding = now.size() > dap.instructionBreakpoints().size();
        dap.setInstructionBreakpoints(
                now,
                results -> {
                    notSet = unset(results);
                    show();
                    if (adding && notSet.contains(instruction.address())) {
                        d.host.setStatus(tr(
                                "status.debug.instructionBreakpointNotSet",
                                instruction.address(),
                                messageOf(results, instruction.address())));
                    }
                },
                why -> d.host.setStatus(tr("status.debug.instructionBreakpointFailed", why)));
    }

    /** Marks the shown session's instruction breakpoints in the Disassembly view. */
    void show() {
        DebugDisassemblyView view = d.s().panel == null ? null : d.s().panel.disassemblyView();
        if (view != null) {
            List<String> set = d.dap().instructionBreakpoints();
            view.setBreakpoints(set, notSet.stream().filter(set::contains).toList());
        }
    }

    /**
     * After a stop for {@code reason} at {@code frames}: one on an instruction breakpoint shows the Disassembly view,
     * at it.
     */
    void stopped(String reason, List<DapModels.StackFrameInfo> frames) {
        if (atInstructionBreakpoint(reason, frames, d.dap().instructionBreakpoints())) {
            d.showLowLevelView(DebugPanel.AREA_DISASSEMBLY);
        }
    }

    /**
     * Whether a stop for {@code reason} at {@code frames} is on one of the instruction breakpoints at {@code set}:
     * DAP's reason says so, or (as CodeLLDB and GDB say {@code breakpoint}) a breakpoint stopped the program at one of
     * their addresses. Pure.
     */
    static boolean atInstructionBreakpoint(String reason, List<DapModels.StackFrameInfo> frames, List<String> set) {
        if (STOP_REASON.equals(reason)) {
            return true;
        }
        if (!"breakpoint".equals(reason) || frames.isEmpty() || set.isEmpty()) {
            return false;
        }
        Long ip = DebugDisassemblyView.parseAddress(frames.getFirst().instructionPointer());
        return ip != null
                && set.stream().map(DebugDisassemblyView::parseAddress).anyMatch(ip::equals);
    }

    /**
     * {@code current} with a breakpoint at {@code address} added, or the one there (the same address, however it is
     * written) taken off. Pure.
     */
    static List<String> toggled(List<String> current, String address) {
        Long wanted = DebugDisassemblyView.parseAddress(address);
        List<String> out = new ArrayList<>();
        boolean removed = false;
        for (String a : current) {
            Long n = DebugDisassemblyView.parseAddress(a);
            if (wanted != null ? wanted.equals(n) : a.equals(address)) {
                removed = true;
            } else {
                out.add(a);
            }
        }
        if (!removed) {
            out.add(address);
        }
        return out;
    }

    /** The addresses of the breakpoints in {@code results} the adapter did not set. Pure. */
    static List<String> unset(List<DapModels.InstructionBreakpoint> results) {
        return results.stream()
                .filter(b -> !b.verified())
                .map(DapModels.InstructionBreakpoint::address)
                .toList();
    }

    private static String messageOf(List<DapModels.InstructionBreakpoint> results, String address) {
        return results.stream()
                .filter(b -> b.address().equals(address))
                .map(DapModels.InstructionBreakpoint::message)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }
}
