package com.editora.ui;

import com.editora.dap.DapModels;
import com.editora.editor.BreakpointManager;

/**
 * Pure decisions about what a debug adapter's answers mean for the user: how a breakpoint looks in the gutter
 * while a session is live, which breakpoint an evaluation failure belongs to, and how an exception stop is
 * named. Unit-tested.
 */
final class DebugFeedback {

    /** Longest exception message shown in the status bar / panel; the rest is in the Variables view. */
    static final int MAX_EXCEPTION_TEXT = 200;

    private DebugFeedback() {}

    /**
     * The gutter state of one armed breakpoint.
     *
     * @param status what the adapter last said about it; null when it has not answered for it
     * @param evaluationFailure the adapter's complaint about this breakpoint's condition or log message,
     *     else null — it outranks a "verified" answer, which only says the location is fine
     * @param pendingText hover text of a breakpoint the adapter has not bound and gave no reason for
     * @param rejectedText hover text of a breakpoint the adapter refused without saying why
     */
    static BreakpointManager.Live live(
            DapModels.BreakpointStatus status, String evaluationFailure, String pendingText, String rejectedText) {
        if (evaluationFailure != null && !evaluationFailure.isBlank()) {
            return new BreakpointManager.Live(BreakpointManager.LiveState.REJECTED, evaluationFailure);
        }
        if (status == null) {
            return new BreakpointManager.Live(BreakpointManager.LiveState.PENDING, pendingText);
        }
        if (status.verified()) {
            return new BreakpointManager.Live(BreakpointManager.LiveState.VERIFIED, "");
        }
        if (status.failed()) {
            return new BreakpointManager.Live(
                    BreakpointManager.LiveState.REJECTED, status.message().isEmpty() ? rejectedText : status.message());
        }
        // Unverified is not rejected: java-debug binds a breakpoint when its class loads, and js-debug and
        // debugpy describe that same wait in `message`. Whatever the adapter said is shown on hover.
        return new BreakpointManager.Live(
                BreakpointManager.LiveState.PENDING, status.message().isEmpty() ? pendingText : status.message());
    }

    /**
     * Whether an adapter notice is about {@code expression} (a breakpoint's condition or log message).
     * java-debug reports a failed evaluation as {@code Breakpoint condition '<expr>' error: …} /
     * {@code [Logpoint] Log message '<expr>' error: …} with no breakpoint id, so the quoted expression is the
     * only link back to the breakpoint.
     */
    static boolean noticeNames(String notice, String expression) {
        return notice != null && expression != null && !expression.isBlank() && notice.contains("'" + expression + "'");
    }

    /**
     * {@code Type: message} for an exception stop — either part alone when the other is missing, the first
     * line of the message only, capped at {@link #MAX_EXCEPTION_TEXT}. {@code shortType} drops the package.
     */
    static String exceptionSummary(DapModels.ExceptionInfo info, boolean shortType) {
        if (info == null || info.isEmpty()) {
            return "";
        }
        String type = info.type();
        if (shortType && !type.isEmpty()) {
            int dot = type.lastIndexOf('.');
            type = dot >= 0 && dot < type.length() - 1 ? type.substring(dot + 1) : type;
        }
        String message = info.message();
        // java-debug hands the description over as a quoted string value: "java.lang.X: boom", quotes included.
        if (message.length() >= 2 && message.startsWith("\"") && message.endsWith("\"")) {
            message = message.substring(1, message.length() - 1).strip();
        }
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline).strip();
        }
        // Adapters often repeat the type in the description ("java.lang.IllegalStateException: boom").
        if (!info.type().isEmpty() && message.startsWith(info.type())) {
            message = message.substring(info.type().length()).replaceFirst("^[:\\s]+", "");
        }
        String text = type.isEmpty() ? message : message.isEmpty() ? type : type + ": " + message;
        return text.length() > MAX_EXCEPTION_TEXT ? text.substring(0, MAX_EXCEPTION_TEXT - 1) + "…" : text;
    }
}
