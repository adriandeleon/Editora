package com.editora.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** A launched text rewritten past the diff budget still maps, by its unchanged head and tail (perf P7). */
class LaunchedLinesBoundedTest {

    @Test
    void aRewrittenFileMapsItsHeadAndTailQuickly() {
        int n = 40_000;
        List<String> launched = new ArrayList<>(List.of("package p;", "class C {"));
        List<String> current = new ArrayList<>(List.of("package p;", "class C {"));
        for (int i = 0; i < n; i++) {
            launched.add("    int a" + i + ";");
            current.add("\tlong b" + i + ";");
        }
        launched.add("}");
        current.add("}");

        LaunchedLines.Mapping map = assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> LaunchedLines.Mapping.between(launched, current));

        assertEquals(1, map.toLaunched(1));
        assertEquals(1, map.toCurrent(1));
        assertEquals(n + 2, map.toLaunched(n + 2), "the closing brace");
        assertEquals(n + 2, map.toCurrent(n + 2));
    }
}
