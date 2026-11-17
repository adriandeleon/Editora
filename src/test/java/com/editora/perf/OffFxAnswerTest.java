package com.editora.perf;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OffFx.Answer}: what work done off the FX thread answered, read with a fallback (PERF-01, Pauta 6.0.1). */
class OffFxAnswerTest {

    @Test
    void orGivesTheValueOnlyWhenTheWorkWentThroughWithOne() {
        OffFx.Answer<String> value = new OffFx.Answer<>("found", null);
        assertTrue(value.ok());
        assertEquals("found", value.or("fallback"));
        assertEquals("", value.why());

        OffFx.Answer<String> nothing = new OffFx.Answer<>(null, null);
        assertTrue(nothing.ok(), "work that answered null still went through");
        assertEquals("fallback", nothing.or("fallback"), "but has no value to give");

        OffFx.Answer<String> failed = new OffFx.Answer<>("stale", new IOException("disk gone"));
        assertFalse(failed.ok());
        assertEquals("fallback", failed.or("fallback"), "a failure's value is never used");
        assertEquals("disk gone", failed.why());
    }
}
