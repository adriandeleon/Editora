package com.editora.jstools;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A typing pause reuses the ESLint setup found a moment ago; a save or a stale answer looks again (L3). */
class EslintSetupsTest {

    private final AtomicInteger looks = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();
    private final EslintSetups setups = new EslintSetups(
            (file, project) -> {
                looks.incrementAndGet();
                return new JsTools.EslintSetup(Optional.empty(), Optional.empty());
            },
            now::get);

    private final Path project = Path.of("/work/app").toAbsolutePath();
    private final Path a = project.resolve("src/a.js");
    private final Path b = project.resolve("src/b.ts");

    @Test
    void aTypingPauseReusesARecentAnswerForTheSameFolder() {
        var first = setups.get(a, project, false);
        assertSame(first, setups.get(a, project, true), "the same file, a moment later");
        assertSame(first, setups.get(b, project, true), "a file in the same folder finds the same setup");
        assertEquals(1, looks.get());
    }

    @Test
    void aCheckThatMayFollowAChangeOnDiskLooksAgain() {
        setups.get(a, project, true);
        setups.get(a, project, false); // shown, saved, settings, Fix All
        assertEquals(2, looks.get());
    }

    @Test
    void anOldAnswerIsLookedForAgain() {
        setups.get(a, project, true);
        now.addAndGet(EslintSetups.FRESH_NANOS);
        setups.get(a, project, true);
        assertEquals(2, looks.get());
    }

    @Test
    void anotherFolderOrProjectIsItsOwnLookup() {
        setups.get(a, project, true);
        setups.get(project.resolve("lib/c.js"), project, true);
        setups.get(a, null, true);
        assertEquals(3, looks.get());
    }
}
