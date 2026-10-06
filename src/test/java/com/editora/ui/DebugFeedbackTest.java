package com.editora.ui;

import com.editora.dap.DapModels;
import com.editora.editor.BreakpointManager;
import com.editora.editor.BreakpointManager.LiveState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugFeedbackTest {

    private static BreakpointManager.Live live(DapModels.BreakpointStatus status, String failure) {
        return DebugFeedback.live(status, failure, "pending", "rejected");
    }

    @Test
    void aBreakpointTheAdapterHasNotAnsweredForIsPending() {
        assertEquals(new BreakpointManager.Live(LiveState.PENDING, "pending"), live(null, null));
    }

    @Test
    void aVerifiedBreakpointNeedsNoExplanation() {
        assertEquals(
                new BreakpointManager.Live(LiveState.VERIFIED, ""),
                live(new DapModels.BreakpointStatus(3, true, false, "bound", 3), null));
    }

    /** The first answer of java-debug for a class that is not loaded yet: it must not read as a refusal. */
    @Test
    void unverifiedWithoutAReasonIsPendingNotRejected() {
        assertEquals(
                new BreakpointManager.Live(LiveState.PENDING, "pending"),
                live(new DapModels.BreakpointStatus(3, false, false, "", 3), null));
    }

    /** js-debug ("Unbound breakpoint") and debugpy ("Waiting for code to be loaded…") put the wait in message. */
    @Test
    void anUnverifiedBreakpointShowsWhatTheAdapterSaidButIsStillPending() {
        assertEquals(
                new BreakpointManager.Live(LiveState.PENDING, "Unbound breakpoint"),
                live(new DapModels.BreakpointStatus(3, false, false, " Unbound breakpoint ", -1), null));
    }

    @Test
    void aFailedBreakpointIsRejectedWithTheAdaptersMessageOrAGenericOne() {
        assertEquals(
                new BreakpointManager.Live(LiveState.REJECTED, "Breakpoint added to invalid line."),
                live(new DapModels.BreakpointStatus(3, false, true, "Breakpoint added to invalid line.", 3), null));
        assertEquals(
                new BreakpointManager.Live(LiveState.REJECTED, "rejected"),
                live(new DapModels.BreakpointStatus(3, false, true, null, 3), null));
    }

    /** "Verified" is about the location; a condition that does not compile makes the breakpoint useless anyway. */
    @Test
    void anEvaluationFailureOutranksAVerifiedLocation() {
        assertEquals(
                new BreakpointManager.Live(LiveState.REJECTED, "Breakpoint condition 'x' error: no x"),
                live(new DapModels.BreakpointStatus(3, true, false, "", 3), "Breakpoint condition 'x' error: no x"));
    }

    @Test
    void aNoticeIsMatchedToABreakpointByItsQuotedExpression() {
        String condition = "Breakpoint condition 'nosuch > 1' error: nosuch cannot be resolved to a variable.";
        String log = "[Logpoint] Log message 'v={oops}' error: oops cannot be resolved";
        assertTrue(DebugFeedback.noticeNames(condition, "nosuch > 1"));
        assertTrue(DebugFeedback.noticeNames(log, "v={oops}"));
        assertFalse(DebugFeedback.noticeNames(condition, "nosuch"), "only the whole expression identifies it");
        assertFalse(DebugFeedback.noticeNames(condition, ""), "a plain breakpoint has no expression to match");
        assertFalse(DebugFeedback.noticeNames(condition, null));
        assertFalse(DebugFeedback.noticeNames(null, "nosuch > 1"));
    }

    @Test
    void anExceptionIsNamedByTypeAndMessage() {
        // What java-debug 0.53 answers: the description is the quoted string value, type repeated in it.
        DapModels.ExceptionInfo javaDebug = new DapModels.ExceptionInfo(
                "java.lang.IllegalStateException", "\"java.lang.IllegalStateException: boom: not ready\"");
        assertEquals(
                "java.lang.IllegalStateException: boom: not ready", DebugFeedback.exceptionSummary(javaDebug, false));
        assertEquals("IllegalStateException: boom: not ready", DebugFeedback.exceptionSummary(javaDebug, true));
    }

    @Test
    void anExceptionWithOnlyOnePartShowsThatPart() {
        assertEquals("ValueError", DebugFeedback.exceptionSummary(new DapModels.ExceptionInfo("ValueError", ""), true));
        assertEquals(
                "ValueError: bad input",
                DebugFeedback.exceptionSummary(new DapModels.ExceptionInfo(null, "ValueError: bad input"), true));
        assertEquals("", DebugFeedback.exceptionSummary(new DapModels.ExceptionInfo("", " "), true));
        assertEquals("", DebugFeedback.exceptionSummary(null, true));
    }

    @Test
    void aLongOrMultiLineMessageIsCutToOneShortLine() {
        String first = DebugFeedback.exceptionSummary(new DapModels.ExceptionInfo("E", "one\ntwo\nthree"), false);
        assertEquals("E: one", first);
        String capped = DebugFeedback.exceptionSummary(new DapModels.ExceptionInfo("E", "x".repeat(900)), false);
        assertEquals(DebugFeedback.MAX_EXCEPTION_TEXT, capped.length());
        assertTrue(capped.endsWith("…"));
    }
}
