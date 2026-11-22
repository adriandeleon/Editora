package com.editora.dap;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What an instruction breakpoint's answer means, and GDB's instructions before an address. */
class InstructionBreakpointWireTest {

    private static Breakpoint answered(boolean verified, String message) {
        Breakpoint b = new Breakpoint();
        b.setVerified(verified);
        b.setMessage(message);
        return b;
    }

    @Test
    void eachAddressGetsTheAnswerAtItsIndexAndNoAnswerMeansSet() {
        assertEquals(
                List.of(
                        new DapModels.InstructionBreakpoint("0x10", true, ""),
                        new DapModels.InstructionBreakpoint("0x14", false, "no code"),
                        new DapModels.InstructionBreakpoint("0x18", true, "")),
                DapClient.instructionResults(
                        List.of("0x10", "0x14", "0x18"),
                        new Breakpoint[] {answered(true, null), answered(false, "no code")}));
        assertEquals(
                List.of(new DapModels.InstructionBreakpoint("0x10", true, "")),
                DapClient.instructionResults(List.of("0x10"), null));
    }

    private static List<DapModels.Instruction> listing(long from, int n) {
        List<DapModels.Instruction> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new DapModels.Instruction("0x" + Long.toHexString(from + i * 2L), "", "nop", "", null, -1));
        }
        return out;
    }

    @Test
    void gdbsListingIsCutAroundTheAddressOrRefusedWhenItNeverStartsThere() {
        List<DapModels.Instruction> listing = listing(0x100, 50); // 0x100, 0x102, … 0x162
        List<DapModels.Instruction> around = DapManager.aroundAddress(listing, "0x0000000000000120", 5, 12);
        assertEquals(12, around.size());
        assertEquals("0x116", around.getFirst().address(), "five before 0x120");
        assertEquals("0x120", around.get(5).address());
        assertEquals(
                "0x100",
                DapManager.aroundAddress(listing, "0x104", 5, 12).getFirst().address(),
                "fewer before");
        assertNull(DapManager.aroundAddress(listing, "0x121", 5, 12), "decoded out of step: no instruction there");
        assertEquals(Long.valueOf(0x121), DapManager.parseAddress("0x121"));
        assertNull(DapManager.parseAddress("main+4"));
    }

    @Test
    void anExpressionWithoutAMemoryReferenceIsAskedForItsAddress() {
        assertEquals("&(word)", DapManager.addressOf(" word "), "GDB: a C array's value has none");
        assertNull(DapManager.addressOf("&word"), "already an address");
        assertNull(DapManager.addressOf(""));
    }
}
