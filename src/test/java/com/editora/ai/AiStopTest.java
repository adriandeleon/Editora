package com.editora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStopTest {

    @Test
    void onlyAnExplicitNormalStopIsComplete() {
        assertEquals(AiStop.Outcome.COMPLETE, AiStop.classify("end_turn"));
        assertEquals(AiStop.Outcome.COMPLETE, AiStop.classify("stop_sequence"));
        assertEquals(AiStop.Outcome.COMPLETE, AiStop.classify("stop"));
    }

    @Test
    void outputAndContextLimitsAreTruncations() {
        assertEquals(AiStop.Outcome.TRUNCATED, AiStop.classify("max_tokens"));
        assertEquals(AiStop.Outcome.TRUNCATED, AiStop.classify("length"));
        assertEquals(AiStop.Outcome.TRUNCATED, AiStop.classify("max_turn_requests"));
        assertEquals(AiStop.Outcome.TRUNCATED, AiStop.classify("model_context_window_exceeded"));
    }

    @Test
    void aMissingReasonMeansTheStreamEndedEarly() {
        assertEquals(AiStop.Outcome.INCOMPLETE, AiStop.classify(null));
        assertEquals(AiStop.Outcome.INCOMPLETE, AiStop.classify(""));
        assertEquals(AiStop.Outcome.INCOMPLETE, AiStop.classify(AiStop.INCOMPLETE));
    }

    @Test
    void refusalsCancellationsAndUnknownReasonsAreNotComplete() {
        assertEquals(AiStop.Outcome.REFUSED, AiStop.classify("refusal"));
        assertEquals(AiStop.Outcome.REFUSED, AiStop.classify("content_filter"));
        assertEquals(AiStop.Outcome.CANCELLED, AiStop.classify("cancelled"));
        assertEquals(AiStop.Outcome.OTHER, AiStop.classify("tool_use"));
        assertEquals(AiStop.Outcome.OTHER, AiStop.classify("pause_turn"));
        assertEquals(AiStop.Outcome.OTHER, AiStop.classify("something_new"));
    }

    @Test
    void usableNeedsBothANormalStopAndSomeText() {
        assertTrue(AiStop.usable("end_turn", "x"));
        assertFalse(AiStop.usable("end_turn", ""));
        assertFalse(AiStop.usable("end_turn", "  \n"));
        assertFalse(AiStop.usable("end_turn", null));
        assertFalse(AiStop.usable("max_tokens", "half an ans"));
        assertFalse(AiStop.usable(AiStop.INCOMPLETE, "half an ans"));
    }
}
