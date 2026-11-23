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

    /** Gives {@code view} its margin, its Toggle Breakpoint row and its Edit Breakpoint… row. */
    void install(DebugDisassemblyView view) {
        view.setOnToggleBreakpoint(this::toggle, () -> d.dap().supportsInstructionBreakpoints());
        view.setOnEditBreakpoint(i -> edit(i.address()));
    }

    /**
     * Asks for the breakpoint at {@code address}'s condition, then its hit count — each only where the adapter takes
     * it — and sends them; the dot turns the conditional colour. An empty answer asks nothing.
     */
    void edit(String address) {
        DapManager dap = d.dap();
        boolean[] takes = dap.instructionConditionSupport();
        if (dap.state() != DapManager.State.SUSPENDED || !dap.supportsInstructionBreakpoints()) {
            d.host.setStatus(tr("status.debug.noInstructionBreakpoints"));
            return;
        }
        if (!takes[0] && !takes[1]) {
            d.host.setStatus(tr("status.debug.instructionConditionsUnsupported"));
            return;
        }
        DapModels.InstructionConditions was =
                dap.instructionConditions().getOrDefault(address, DapModels.InstructionConditions.NONE);
        java.util.function.Consumer<String> askHits = condition -> {
            if (!takes[1]) {
                apply(address, new DapModels.InstructionConditions(condition, ""));
                return;
            }
            d.host.promptText(
                    tr("debug.instructionBreakpoint.hitTitle"),
                    tr("debug.instructionBreakpoint.hitLabel", address),
                    was.hitCondition(),
                    hits -> {
                        if (hits != null) {
                            apply(address, new DapModels.InstructionConditions(condition, hits));
                        }
                    });
        };
        if (!takes[0]) {
            askHits.accept("");
            return;
        }
        d.host.promptText(
                tr("debug.instructionBreakpoint.conditionTitle"),
                tr("debug.instructionBreakpoint.conditionLabel", address),
                was.condition(),
                condition -> {
                    if (condition != null) {
                        askHits.accept(condition);
                    }
                });
    }

    /** Sends the breakpoints again with {@code conditions} at {@code address}. */
    private void apply(String address, DapModels.InstructionConditions conditions) {
        DapManager dap = d.dap();
        if (!dap.instructionBreakpoints().contains(address)) {
            return; // taken off while the prompt was open
        }
        java.util.Map<String, DapModels.InstructionConditions> all =
                new java.util.LinkedHashMap<>(dap.instructionConditions());
        all.put(address, conditions);
        dap.setInstructionBreakpoints(
                dap.instructionBreakpoints(),
                all,
                results -> {
                    notSet = unset(results);
                    show();
                    if (notSet.contains(address)) {
                        d.host.setStatus(
                                tr("status.debug.instructionBreakpointNotSet", address, messageOf(results, address)));
                    }
                },
                why -> d.host.setStatus(tr("status.debug.instructionBreakpointFailed", why)));
    }

    /** The shown session's instruction breakpoints with their conditions, for the Breakpoints list. */
    java.util.Map<String, DapModels.InstructionConditions> listed() {
        java.util.Map<String, DapModels.InstructionConditions> out = new java.util.LinkedHashMap<>();
        java.util.Map<String, DapModels.InstructionConditions> conditions =
                d.dap().instructionConditions();
        for (String a : d.dap().instructionBreakpoints()) {
            out.put(a, conditions.getOrDefault(a, DapModels.InstructionConditions.NONE));
        }
        return out;
    }

    /** Takes the breakpoint at {@code address} off (the Breakpoints list's Remove). */
    void remove(String address) {
        DapManager dap = d.dap();
        if (!dap.instructionBreakpoints().contains(address)) {
            return;
        }
        dap.setInstructionBreakpoints(
                toggled(dap.instructionBreakpoints(), address),
                results -> {
                    notSet = unset(results);
                    show();
                },
                why -> d.host.setStatus(tr("status.debug.instructionBreakpointFailed", why)));
    }

    /** Shows the Disassembly view, at the instruction of {@code address} when it is listed there. */
    void reveal(String address) {
        d.showLowLevelView(DebugPanel.AREA_DISASSEMBLY);
        DebugDisassemblyView view = d.s().panel == null ? null : d.s().panel.disassemblyView();
        if (view != null) {
            view.selectAddress(address);
        }
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
            view.setBreakpoints(
                    set,
                    notSet.stream().filter(set::contains).toList(),
                    d.dap().instructionConditions().keySet());
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
