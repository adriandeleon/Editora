package com.editora.recovery;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A buffer's recovery id needs no secure random (no /dev/urandom read per tab: PERF-01, Pauta 6.0.1). */
class RecoveryStoreIdTest {

    @Test
    void idsAreFileNameSafeAndDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = RecoveryStore.newBufferId();
            assertTrue(id.matches("[A-Za-z0-9-]{1,64}"), id);
            seen.add(id);
        }
        assertEquals(10_000, seen.size());
    }
}
