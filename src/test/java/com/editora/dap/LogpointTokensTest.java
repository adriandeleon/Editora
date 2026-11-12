package com.editora.dap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Visual Studio's tracepoint tokens in a logpoint's message, which Editora writes itself. */
class LogpointTokensTest {

    private static final LogpointTokens.Stop STOP = new LogpointTokens.Stop(
            "Shop.checkout", "Main.main", List.of("Shop.checkout(Shop.java:12)", "Main.main(Main.java:4)"), 1, "main");

    @Test
    void aMessageUsesTheTokensOnlyAsWholeWords() {
        assertTrue(LogpointTokens.uses("in $FUNCTION"));
        assertTrue(LogpointTokens.uses("$CALLSTACK"));
        assertTrue(LogpointTokens.uses("thread $TID/$TNAME"));
        assertFalse(LogpointTokens.uses("total = {total}"));
        assertFalse(LogpointTokens.uses("$FUNCTIONS and $X"));
        assertFalse(LogpointTokens.uses(null));
    }

    @Test
    void theExpressionsAreTheBracedPartsWithBracesOfTheirOwn() {
        assertEquals(List.of("x", "m.get(k)", "a + b"), LogpointTokens.expressions("{x} { m.get(k) } {a + b}"));
        assertEquals(List.of("new int[]{1}.length"), LogpointTokens.expressions("n={new int[]{1}.length}"));
        assertEquals(List.of(), LogpointTokens.expressions("an open { brace and {}"));
    }

    @Test
    void theMessageIsWrittenWithValuesAndWhatTheTokensStandFor() {
        assertEquals(
                "Shop.checkout from Main.main: total=val(total) on main (1)",
                LogpointTokens.expand(
                        "$FUNCTION from $CALLER: total={total} on $TNAME ($TID)", e -> "val(" + e + ")", STOP));
        assertEquals(
                "here\n\tShop.checkout(Shop.java:12)\n\tMain.main(Main.java:4)",
                LogpointTokens.expand("here$CALLSTACK", e -> "", STOP));
        assertEquals("$5 for {", LogpointTokens.expand("${price} for {", e -> "5", STOP), "a value's own $ is text");
    }
}
