package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenoRunTest {

    private static final Path P = Path.of("/p").toAbsolutePath();

    private static java.util.Optional<Path> root(String file, Path project, String... files) {
        Set<Path> there = new java.util.HashSet<>();
        for (String f : files) {
            there.add(P.resolve(f));
        }
        return DenoRun.root(P.resolve(file), project, null, there::contains);
    }

    @Test
    void theNearestDenoConfigMakesTheProject() {
        assertEquals(P, root("src/main.ts", P, "deno.json").orElseThrow());
        assertEquals(
                P.resolve("app"),
                root("app/src/main.ts", P, "deno.jsonc", "app/deno.jsonc").orElseThrow());
        assertEquals(P, root("main.ts", P, "deno.json", "package.json").orElseThrow(), "both: Deno's");
    }

    @Test
    void aNearerPackageJsonIsANodePackage() {
        assertTrue(root("web/index.ts", P, "deno.json", "web/package.json").isEmpty());
        assertTrue(root("main.ts", P).isEmpty(), "no config at all");
    }

    @Test
    void theWalkStopsAtTheProjectRoot() {
        assertTrue(root("app/main.ts", P.resolve("app"), "deno.json").isEmpty(), "above the project: not looked at");
    }

    @Test
    void outsideAnyProjectTheWalkStopsAtHome() {
        Set<Path> there = Set.of(P.resolve("deno.json"));
        assertTrue(DenoRun.root(P.resolve("me/x/main.ts"), null, P.resolve("me"), there::contains)
                .isEmpty());
        assertEquals(
                P,
                DenoRun.root(P.resolve("x/main.ts"), null, null, there::contains)
                        .orElseThrow());
    }

    @Test
    void testFilesAreDenoTestsOwn() {
        for (String name : List.of("test.ts", "math_test.ts", "math.test.mjs", "a.b_test.tsx", "TEST.JS")) {
            assertTrue(DenoRun.isTestFile(name), name);
        }
        for (String name : List.of("main.ts", "latest.ts", "test.json", "math-test.ts", "tests.ts")) {
            assertFalse(DenoRun.isTestFile(name), name);
        }
        assertFalse(DenoRun.isTestFile(null));
    }

    @Test
    void denoIsKnownByItsName() {
        assertTrue(DenoRun.isDeno("deno"));
        assertTrue(DenoRun.isDeno("/home/u/.deno/bin/deno"));
        assertTrue(DenoRun.isDeno("C:\\Deno\\DENO.EXE"));
        assertFalse(DenoRun.isDeno("node"));
        assertFalse(DenoRun.isDeno("denort"));
        assertFalse(DenoRun.isDeno(null));
    }

    @Test
    void aProgramRunsWithEveryPermission() {
        assertEquals(
                List.of("deno", "run", "--allow-all", "/p/main.ts", "a", "b"),
                DenoRun.argv("deno", false, null, "/p/main.ts", List.of("a", "b")));
    }

    @Test
    void aTestRunIsFilteredToItsTestAndGetsItsArgumentsAfterTheDashes() {
        assertEquals(
                List.of("deno", "test", "--allow-all", "--filter", "/^adds \\(big\\)$/", "/p/a_test.ts"),
                DenoRun.argv("deno", true, "adds (big)", "/p/a_test.ts", List.of()));
        assertEquals(
                List.of("deno", "test", "--allow-all", "/p/a_test.ts", "--", "x"),
                DenoRun.argv("deno", true, "", "/p/a_test.ts", List.of("x")));
    }

    @Test
    void theFilterMatchesTheNameLiterally() {
        assertEquals("/^a\\/b \\$1\\.\\*$/", DenoRun.filterPattern("a/b $1.*"));
    }

    @Test
    void theTestNameRidesInTheNodeArgumentsAndNodeNeverSeesIt() {
        List<String> args = List.of("--test", DenoRun.FILTER_ARG + "adds", "--test-name-pattern", "adds");
        assertEquals("adds", DenoRun.filterOf(args));
        assertEquals(List.of("--test", "--test-name-pattern", "adds"), DenoRun.withoutFilter(args));
        assertNull(DenoRun.filterOf(List.of("--test")));
        assertNull(DenoRun.filterOf(null));
        assertEquals(List.of(), DenoRun.withoutFilter(null));
    }

    @Test
    void theInspectorWaitsAfterTheSubcommand() {
        assertEquals(
                List.of("deno", "run", "--inspect-wait=127.0.0.1:9333", "--allow-all"),
                DenoRun.inspected(List.of("deno", "run", "--allow-all"), 9333));
        assertEquals(
                List.of("deno", "test", "--inspect-wait=127.0.0.1:1", "--allow-all", "--filter", "/^a$/"),
                DenoRun.inspected(List.of("deno", "test", "--allow-all", "--filter", "/^a$/"), 1));
    }

    @Test
    void aShebangsDenoGetsRunAndLosesItsOwnInspector() {
        assertEquals(List.of("deno", "run", "--inspect-wait=127.0.0.1:2"), DenoRun.inspected(List.of("deno"), 2));
        assertEquals(
                List.of("/bin/deno", "run", "--inspect-wait=127.0.0.1:2", "--allow-net"),
                DenoRun.inspected(List.of("/bin/deno", "--allow-net"), 2));
        assertEquals(
                List.of("deno", "run", "--inspect-wait=127.0.0.1:3", "-A"),
                DenoRun.inspected(List.of("deno", "run", "--inspect-brk", "-A"), 3));
    }
}
