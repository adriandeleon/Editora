package com.editora.ui;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A buffer writing its marks keeps what another buffer on the same file added, and removes only its own. */
class MarkMergeTest {

    private static final Function<String, String> ID = Function.identity();

    @Test
    void anEntryTheBufferNeverSawIsSomeoneElsesAndStays() {
        // The buffer loaded [a], another window then stored b; this buffer now writes [a, c].
        assertEquals(
                List.of("a", "c", "b"), MarkMerge.withForeign(List.of("a", "b"), Set.of("a"), List.of("a", "c"), ID));
    }

    @Test
    void anEntryTheBufferSawAndNoLongerHoldsWasRemovedByIt() {
        assertEquals(List.of("b"), MarkMerge.withForeign(List.of("a", "b"), Set.of("a", "b"), List.of("b"), ID));
        assertEquals(List.of(), MarkMerge.withForeign(List.of("a"), Set.of("a"), List.of(), ID));
    }

    @Test
    void aBufferThatHasSeenNothingRemovesNothing() {
        assertEquals(List.of("c", "a", "b"), MarkMerge.withForeign(List.of("a", "b"), null, List.of("c"), ID));
    }

    @Test
    void withNothingForeignTheBuffersOwnListIsWrittenAsItIs() {
        List<String> mine = List.of("b", "a");
        assertSame(mine, MarkMerge.withForeign(List.of("a", "b"), Set.of("a", "b"), mine, ID));
        assertSame(mine, MarkMerge.withForeign(null, null, mine, ID));
    }

    @Test
    void keysOfNothingIsEmpty() {
        assertEquals(Set.of(), MarkMerge.keys(null, ID));
        assertEquals(Set.of("a"), MarkMerge.keys(List.of("a", "a"), ID));
    }
}
