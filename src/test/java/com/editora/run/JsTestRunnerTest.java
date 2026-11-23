package com.editora.run;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsTestRunnerTest {

    private static final Path P = Path.of("/p").toAbsolutePath();

    private static Optional<JsTestRunner.Kind> kind(String packageJson, String... files) {
        Set<Path> there = new java.util.HashSet<>();
        for (String f : files) {
            there.add(P.resolve(f));
        }
        return JsTestRunner.kindOf(P, packageJson, there::contains);
    }

    @Test
    void theRunnerIsReadFromThePackage() {
        assertEquals(Optional.of(JsTestRunner.Kind.JEST), kind("{\"devDependencies\": {\"jest\": \"^30\"}}"));
        assertEquals(Optional.of(JsTestRunner.Kind.JEST), kind("{\"jest\": {\"testEnvironment\": \"node\"}}"));
        assertEquals(Optional.of(JsTestRunner.Kind.JEST), kind("{}", "jest.config.ts"));
        assertEquals(Optional.of(JsTestRunner.Kind.VITEST), kind("{\"devDependencies\": {\"vitest\": \"^5\"}}"));
        assertEquals(Optional.of(JsTestRunner.Kind.VITEST), kind(null, "vitest.config.mts"));
        assertEquals(
                Optional.of(JsTestRunner.Kind.VITEST),
                kind("{\"devDependencies\": {\"jest\": \"1\", \"vitest\": \"1\"}}"),
                "both: Vitest (a Jest-compatible API, the newer choice)");
        assertEquals(Optional.empty(), kind("{\"devDependencies\": {\"mocha\": \"1\"}}"), "node --test's");
        assertEquals(Optional.empty(), kind("{ broken"));
    }

    @Test
    void theInstalledRunnerIsFoundUpToTheProject() {
        Map<Path, String> files = new HashMap<>();
        files.put(P.resolve("packages/web/package.json"), "{\"devDependencies\": {\"vitest\": \"5\"}}");
        files.put(P.resolve("node_modules/vitest/vitest.mjs"), "");
        Path test = P.resolve("packages/web/src/a.test.ts");
        Optional<JsTestRunner.Runner> found = JsTestRunner.find(test, P, null, files::containsKey, p -> files.get(p));
        assertEquals(
                Optional.of(new JsTestRunner.Runner(
                        JsTestRunner.Kind.VITEST,
                        P.resolve("node_modules/vitest/vitest.mjs"),
                        P.resolve("packages/web"))),
                found,
                "hoisted to the workspace's node_modules");
        files.remove(P.resolve("node_modules/vitest/vitest.mjs"));
        assertTrue(
                JsTestRunner.find(test, P, null, files::containsKey, p -> files.get(p))
                        .isEmpty(),
                "not installed");
    }

    @Test
    void jestRunsInOneProcessFilteredByTheFullName() {
        var jest = new JsTestRunner.Runner(JsTestRunner.Kind.JEST, P.resolve("node_modules/jest/bin/jest.js"), P);
        assertEquals(
                List.of(
                        P.resolve("node_modules/jest/bin/jest.js").toString(),
                        "--runInBand",
                        "-t",
                        "^math \\(basic\\) adds$",
                        "a.test.js"),
                JsTestRunner.args(jest, "a.test.js", new JsTestNames.Target(List.of("math (basic)", "adds"), false)));
        assertEquals(
                List.of(P.resolve("node_modules/jest/bin/jest.js").toString(), "--runInBand", "a.test.js"),
                JsTestRunner.args(jest, "a.test.js", null),
                "the whole file");
    }

    @Test
    void vitestRunsOnceFilteredWithEitherSeparator() {
        var vitest = new JsTestRunner.Runner(JsTestRunner.Kind.VITEST, P.resolve("node_modules/vitest/vitest.mjs"), P);
        assertEquals(
                List.of(
                        P.resolve("node_modules/vitest/vitest.mjs").toString(),
                        "run",
                        "a.test.ts",
                        "-t",
                        "^suite( | > )inner\\.x(( | > )|$)"),
                JsTestRunner.args(vitest, "a.test.ts", new JsTestNames.Target(List.of("suite", "inner.x"), true)));
    }

    @Test
    void theTestRidesInTheNodeArgumentsAndNodeNeverSeesIt() {
        var target = new JsTestNames.Target(List.of("a b", "c"), true);
        List<String> args = List.of("--test", JsTestRunner.namesArg(target));
        assertEquals(target, JsTestRunner.namesOf(args));
        assertEquals(List.of("--test"), DenoRun.withoutFilter(args));
        assertNull(JsTestRunner.namesOf(List.of("--test")));
    }
}
