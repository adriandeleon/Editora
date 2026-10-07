package com.editora.ai;

/**
 * What a finished generation's stop reason means for a caller that is about to <em>use</em> the text.
 * Pure. {@link AiClient} and {@link CodexAiClient} report the provider's reason (Anthropic names, with the
 * OpenAI ones mapped by {@link OpenAiSse#finishReason}), or {@link #INCOMPLETE} when the stream simply
 * ended. Only {@link Outcome#COMPLETE} says "this is the whole answer": a reply cut off at the output
 * limit, or one whose stream closed before the model said it was done, looks exactly like a short answer
 * unless the reason is checked — and replacing the user's text with it loses the rest.
 */
public final class AiStop {

    /** The model finished its answer. */
    public static final String END_TURN = "end_turn";

    /** The response ended without any stop reason or terminator: the connection closed mid-answer. */
    public static final String INCOMPLETE = "incomplete";

    /** How a generation ended, as far as applying its text is concerned. */
    public enum Outcome {
        /** An explicit normal stop was received: the text is the model's whole answer. */
        COMPLETE,
        /** Cut off at an output or context limit: the text is only the beginning of the answer. */
        TRUNCATED,
        /** No stop reason arrived at all: the stream ended early. */
        INCOMPLETE,
        /** The model or a content filter declined. */
        REFUSED,
        /** The request was cancelled. */
        CANCELLED,
        /** Some other stop (a tool call, a paused turn, an unknown reason): not a finished answer. */
        OTHER
    }

    private AiStop() {}

    /** Classifies {@code stopReason}; anything not known to mean "finished" is not {@link Outcome#COMPLETE}. */
    public static Outcome classify(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return Outcome.INCOMPLETE;
        }
        return switch (stopReason) {
            case END_TURN, "stop", "stop_sequence" -> Outcome.COMPLETE;
            case "max_tokens", "length", "max_turn_requests", "model_context_window_exceeded" -> Outcome.TRUNCATED;
            case INCOMPLETE -> Outcome.INCOMPLETE;
            case "refusal", "content_filter" -> Outcome.REFUSED;
            case "cancelled" -> Outcome.CANCELLED;
            default -> Outcome.OTHER;
        };
    }

    /** True only for a non-empty answer that ended with an explicit normal stop. */
    public static boolean usable(String stopReason, String text) {
        return classify(stopReason) == Outcome.COMPLETE && text != null && !text.isBlank();
    }
}
