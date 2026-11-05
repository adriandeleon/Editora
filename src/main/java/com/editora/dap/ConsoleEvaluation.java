package com.editora.dap;

/**
 * What an expression typed in the Debug console evaluated to: the value as the adapter shows it, or — when
 * {@code failed} — why it could not be evaluated, in the user's language. The console draws a failure in
 * red; it used to find one by an English {@code "error: "} prefix on the text (review D105).
 */
public record ConsoleEvaluation(String text, boolean failed) {

    public ConsoleEvaluation {
        text = text == null ? "" : text;
    }

    /** A value (or nothing, as {@code ""}). */
    public static ConsoleEvaluation value(String text) {
        return new ConsoleEvaluation(text, false);
    }

    /** A failure, worded for the console: {@code reason} after the translated "error:" label. */
    public static ConsoleEvaluation failure(String reason) {
        return new ConsoleEvaluation(
                com.editora.i18n.Messages.tr("debug.eval.error", reason == null ? "" : reason), true);
    }
}
