package com.editora.ui;

import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionBreakpointsTest {

    private static DapModels.StackFrameInfo frameAt(String ip) {
        return new DapModels.StackFrameInfo(1, "main", null, 3, 0, null, "", ip, null);
    }

    @Test
    void toggleAddsAnAddressOrTakesOffTheOneThereHoweverItIsWritten() {
        assertEquals(List.of("0x10"), InstructionBreakpoints.toggled(List.of(), "0x10"));
        assertEquals(List.of("0x10", "0x14"), InstructionBreakpoints.toggled(List.of("0x10"), "0x14"));
        assertEquals(List.of("0x14"), InstructionBreakpoints.toggled(List.of("0x10", "0x14"), "0x0000000010"));
        assertEquals(List.of(), InstructionBreakpoints.toggled(List.of("main+4"), "main+4"), "not a number: as text");
    }

    @Test
    void theAddressesNotSetAreThoseTheAdapterDidNotVerify() {
        assertEquals(
                List.of("0x14"),
                InstructionBreakpoints.unset(List.of(
                        new DapModels.InstructionBreakpoint("0x10", true, ""),
                        new DapModels.InstructionBreakpoint("0x14", false, "no code"))));
    }

    @Test
    void aStopIsAtAnInstructionBreakpointByItsReasonOrByABreakpointStopAtItsAddress() {
        List<String> set = List.of("0x0000000000001004");
        assertTrue(InstructionBreakpoints.atInstructionBreakpoint("instruction breakpoint", List.of(), List.of()));
        assertTrue(InstructionBreakpoints.atInstructionBreakpoint("breakpoint", List.of(frameAt("0x1004")), set));
        assertFalse(InstructionBreakpoints.atInstructionBreakpoint("breakpoint", List.of(frameAt("0x1008")), set));
        assertFalse(InstructionBreakpoints.atInstructionBreakpoint("step", List.of(frameAt("0x1004")), set), "a step");
        assertFalse(InstructionBreakpoints.atInstructionBreakpoint("breakpoint", List.of(frameAt(null)), set));
    }
}
