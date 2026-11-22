package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The screenshot scripts the repo ships all parse (DOCS-18), and the parser says which line is wrong. */
class ShotScriptTest {

    @Test
    void everyShippedScriptParses() throws Exception {
        Path root = Path.of("scripts/screenshots");
        List<Path> scripts;
        try (Stream<Path> files = Files.walk(root)) {
            scripts = files.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        assertTrue(scripts.size() >= 40, "the website's scripts are under " + root + ": " + scripts.size());
        int shots = 0;
        for (Path script : scripts) {
            List<ShotScript.Step> steps;
            try {
                steps = ShotScript.parse(Files.readAllLines(script), null);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(script + ": " + e.getMessage(), e);
            }
            long pictures = steps.stream()
                    .filter(s -> s.op().startsWith("shot") || s.op().startsWith("winshot"))
                    .count();
            assertTrue(pictures > 0, script + " takes no picture");
            assertTrue(steps.stream().anyMatch(s -> s.op().equals("start")), script + " never starts the window");
            shots += (int) pictures;
        }
        assertTrue(shots >= 100, "pictures across the scripts: " + shots);
    }

    @Test
    void variablesExpandAndCommentsAreSkipped() {
        List<ShotScript.Step> steps = ShotScript.parse(
                List.of("# a comment", "#@ trust none", "", "open ${workspace}/a.txt 3", "shot ${out}/a${suffix}.png"),
                Map.of("workspace", "/w", "out", "/o", "suffix", ".dark"));

        assertEquals(
                List.of(new ShotScript.Step(4, "open", "/w/a.txt 3"), new ShotScript.Step(5, "shot", "/o/a.dark.png")),
                steps);
    }

    @Test
    void aBadLineFailsWithItsNumber() {
        assertEquals("line 2: unknown op 'shoot'", message("start", "shoot x.png"));
        assertTrue(message("start", "key NOPE").startsWith("line 2: "), "a key that is no KeyCode");
        assertTrue(message("size 900", "start").startsWith("line 1: 'size' takes at least 2"));
        assertTrue(message("wait soon").startsWith("line 1: "), "a wait that is no number");
        assertEquals("line 1: unknown variable ${scratch}", message("open ${scratch}/a"));
        assertTrue(message("shot a.png 0 0 10").startsWith("line 1: "), "a crop needs all four numbers");
    }

    private static String message(String... lines) {
        return assertThrows(IllegalArgumentException.class, () -> ShotScript.parse(List.of(lines), null))
                .getMessage();
    }
}
