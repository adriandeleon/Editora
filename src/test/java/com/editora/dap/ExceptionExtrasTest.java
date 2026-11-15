package com.editora.dap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExceptionExtrasTest {

    private static final List<DapModels.ExceptionFilterOption> PYTHON = List.of(
            new DapModels.ExceptionFilterOption("raised", "Raised Exceptions", false),
            new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", true),
            new DapModels.ExceptionFilterOption("userUnhandled", "User Uncaught Exceptions", false));

    @Test
    void theFiltersExceptionBreakpointsNeedAreSentBesidesTheChosenOnes() {
        assertEquals(
                List.of("raised", "uncaught"),
                DapClient.withExtras(List.of("uncaught"), List.of("raised"), PYTHON),
                "in the adapter's order");
        List<String> base = List.of("raised", "uncaught");
        assertSame(base, DapClient.withExtras(base, List.of("raised"), PYTHON), "already on");
        assertSame(base, DapClient.withExtras(base, List.of(), PYTHON));
        assertEquals(
                List.of("raised"),
                DapClient.withExtras(List.of(), List.of("raised"), List.of()),
                "before the adapter listed its filters");
    }
}
