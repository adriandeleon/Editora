package com.editora.dap.jvm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLooksTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final List<Path> stats = new ArrayList<>();
    private final Path here = Path.of("/work/src/com/acme/Util.kt");

    private SourceLooks looks() {
        return new SourceLooks(
                p -> {
                    stats.add(p);
                    return p.equals(here);
                },
                now::get);
    }

    @Test
    void aStepAfterAStepStatsNothingAgain() {
        SourceLooks looks = looks();
        List<Path> roots = List.of(Path.of("/work/src"), Path.of("/work/test"), Path.of("/lib/src"));
        // A library frame under no root, then the program's own file: each stop of a step resolves them both.
        for (int stop = 0; stop < 3; stop++) {
            assertNull(JvmSourceMap.resolve("kotlin/collections/CollectionsKt.kt", List.of(), roots, looks::exists));
            assertEquals(here, JvmSourceMap.resolve("com/acme/Util.kt", List.of(), roots, looks::exists));
            now.addAndGet(1_000_000_000L);
        }
        assertEquals(4, stats.size(), "three roots for the library frame, one for the program's file, once each");
    }

    @Test
    void aLookIsTakenAgainOnceStale() {
        SourceLooks looks = looks();
        assertTrue(looks.exists(here));
        assertTrue(looks.exists(here));
        assertEquals(1, stats.size());
        now.addAndGet(SourceLooks.FRESH_NANOS + 1);
        assertTrue(looks.exists(here));
        assertEquals(2, stats.size(), "a file removed meanwhile is seen within seconds");
    }

    @Test
    void clearForgetsAndTheMapStaysBounded() {
        SourceLooks looks = looks();
        Path missing = Path.of("/nowhere/A.kt");
        assertFalse(looks.exists(missing));
        looks.clear();
        assertFalse(looks.exists(missing));
        assertEquals(2, stats.size());
        for (int i = 0; i < SourceLooks.MAX + 10; i++) {
            looks.exists(Path.of("/nowhere/" + i + ".kt"));
        }
        assertTrue(Set.copyOf(stats).size() > SourceLooks.MAX);
    }
}
