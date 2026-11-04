package com.editora.jstools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which ESLint, Prettier and Biome a JS/TS file has, and who formats it (JS review F). */
class JsToolsTest {

    private static final Path PROJECT = Path.of("/w/app").toAbsolutePath();
    private static final Path FILE = PROJECT.resolve("src/main.ts");

    /** A made-up file system: files and their text (a package.json's), nothing else exists. */
    private final Map<Path, String> files = new HashMap<>();

    private void file(String relative) {
        files.put(PROJECT.resolve(relative), "");
    }

    private void file(String relative, String text) {
        files.put(PROJECT.resolve(relative), text);
    }

    private JsTools.Lookup lookup(boolean windows) {
        return new JsTools.Lookup(
                PROJECT, Path.of("/home/u"), windows, files::containsKey, p -> Optional.ofNullable(files.get(p)));
    }

    private JsTools.Lookup lookup() {
        return lookup(false);
    }

    private static final Predicate<Path> TRUSTED = p -> true;

    @Test
    void appliesToJavaScriptAndTypeScriptOnly() {
        for (String name : new String[] {"a.js", "a.jsx", "a.mjs", "a.cjs", "a.ts", "a.tsx", "a.mts", "a.CTS"}) {
            assertTrue(JsTools.applies(Path.of(name)), name);
        }
        for (String name : new String[] {"a.json", "a.css", "a.md", "js", ".ts", "a.vue"}) {
            assertFalse(JsTools.applies(Path.of(name)), name);
        }
        assertFalse(JsTools.applies(null));
    }

    @Test
    void aFlatConfigWinsOverAnOlderOneInTheSameFolderAndTheNearestFolderWins() {
        file(".eslintrc.json");
        file("eslint.config.mjs");
        var config = JsTools.eslintConfig(FILE, lookup()).orElseThrow();
        assertEquals(PROJECT, config.dir());
        assertTrue(config.flat());
        assertEquals(PROJECT.resolve("eslint.config.mjs"), config.file());

        file("src/.eslintrc.cjs");
        var nearer = JsTools.eslintConfig(FILE, lookup()).orElseThrow();
        assertEquals(PROJECT.resolve("src"), nearer.dir(), "the folder nearest the file");
        assertFalse(nearer.flat());
    }

    @Test
    void packageJsonsEslintConfigCountsButAPackageJsonWithoutItDoesNot() {
        file("package.json", "{\"name\":\"a\"}");
        assertTrue(JsTools.eslintConfig(FILE, lookup()).isEmpty());
        file("package.json", "﻿{\"name\":\"a\",\"eslintConfig\":{\"rules\":{}}}");
        var config = JsTools.eslintConfig(FILE, lookup()).orElseThrow();
        assertEquals(PROJECT.resolve("package.json"), config.file());
        assertFalse(config.flat());
        file("package.json", "{ broken");
        assertTrue(JsTools.eslintConfig(FILE, lookup()).isEmpty(), "a broken package.json says nothing");
    }

    @Test
    void theSearchStopsAtTheProjectRoot() {
        files.put(PROJECT.getParent().resolve("eslint.config.js"), "");
        assertTrue(JsTools.eslintConfig(FILE, lookup()).isEmpty(), "a configuration above the project is not used");
    }

    @Test
    void withNoProjectTheSearchGoesAFewFoldersAndNeverReachesHome() {
        Path home = Path.of("/home/u").toAbsolutePath();
        Path deep = home.resolve("a/b/c.js");
        files.put(home.resolve("eslint.config.js"), "");
        var noProject = new JsTools.Lookup(null, home, false, files::containsKey, p -> Optional.empty());
        assertTrue(JsTools.eslintConfig(deep, noProject).isEmpty(), "~/eslint.config.js is not a project's");
        files.put(home.resolve("a/eslint.config.js"), "");
        assertEquals(
                home.resolve("a"),
                JsTools.eslintConfig(deep, noProject).orElseThrow().dir());
    }

    @Test
    void aFileInsideNodeModulesIsLookedUpFromAboveIt() {
        file("eslint.config.js");
        Path dep = PROJECT.resolve("node_modules/lib/node_modules/x/index.js");
        files.put(PROJECT.resolve("node_modules/lib/eslint.config.js"), "");
        assertEquals(PROJECT, JsTools.eslintConfig(dep, lookup()).orElseThrow().dir());
    }

    @Test
    void theToolIsThePackagesOwnScriptRunWithNodeElseItsBinCommandExceptOnWindows() {
        file("node_modules/.bin/eslint");
        var bin = JsTools.tool(JsTools.Kind.ESLINT, FILE, lookup()).orElseThrow();
        assertFalse(bin.viaNode());
        assertEquals(PROJECT.resolve("node_modules/.bin/eslint"), bin.script());
        assertEquals(java.util.List.of(bin.script().toString()), bin.command(java.util.List.of("/n/node")));
        assertTrue(JsTools.tool(JsTools.Kind.ESLINT, FILE, lookup(true)).isEmpty(), "never a .cmd shim");

        file("node_modules/eslint/bin/eslint.js");
        var script = JsTools.tool(JsTools.Kind.ESLINT, FILE, lookup(true)).orElseThrow();
        assertTrue(script.viaNode());
        assertEquals(PROJECT, script.root());
        assertEquals(
                java.util.List.of("/n/node", "--no-warnings", script.script().toString()),
                script.command(java.util.List.of("/n/node", "--no-warnings")));
    }

    @Test
    void aMonorepoPackagesOwnToolBeatsTheRootsAndPrettierTwoIsFound() {
        file("node_modules/prettier/bin/prettier.cjs");
        file("src/node_modules/prettier/bin-prettier.js");
        var tool = JsTools.tool(JsTools.Kind.PRETTIER, FILE, lookup()).orElseThrow();
        assertEquals(PROJECT.resolve("src"), tool.root());
        assertEquals(PROJECT.resolve("src/node_modules/prettier/bin-prettier.js"), tool.script());
    }

    @Test
    void eslintSetupNeedsBothAConfigurationAndEslint() {
        assertFalse(JsTools.eslint(FILE, lookup()).ready());
        file("eslint.config.js");
        var noTool = JsTools.eslint(FILE, lookup());
        assertTrue(noTool.config().isPresent());
        assertTrue(noTool.tool().isEmpty(), "config found, eslint not installed");
        file("node_modules/eslint/bin/eslint.js");
        assertTrue(JsTools.eslint(FILE, lookup()).ready());
    }

    @Test
    void autoUsesPrettierWhenTheProjectUsesIt() {
        file("node_modules/prettier/bin/prettier.cjs");
        assertEquals(
                JsTools.Formatter.SERVER,
                JsTools.formatPlan("auto", FILE, lookup(), TRUSTED).formatter(),
                "installed as a dependency of something else, not used: the server");
        file("package.json", "{\"devDependencies\":{\"prettier\":\"^3\"}}");
        var plan = JsTools.formatPlan("auto", FILE, lookup(), TRUSTED);
        assertEquals(JsTools.Formatter.PRETTIER, plan.formatter());
        assertEquals(PROJECT, plan.dir());
        assertNull(plan.problem());
        files.remove(PROJECT.resolve("package.json"));
        file(".prettierrc");
        assertEquals(
                JsTools.Formatter.PRETTIER,
                JsTools.formatPlan("auto", FILE, lookup(), TRUSTED).formatter());
    }

    @Test
    void autoUsesBiomeWithABiomeJsonAndRunsItFromThatFolder() {
        file("biome.jsonc");
        file("node_modules/@biomejs/biome/bin/biome");
        var plan = JsTools.formatPlan("auto", FILE, lookup(), TRUSTED);
        assertEquals(JsTools.Formatter.BIOME, plan.formatter());
        assertEquals(PROJECT, plan.dir());
        file(".prettierrc.json");
        file("node_modules/prettier/bin/prettier.cjs");
        assertEquals(
                JsTools.Formatter.PRETTIER,
                JsTools.formatPlan("auto", FILE, lookup(), TRUSTED).formatter(),
                "both configured: Prettier first");
    }

    @Test
    void autoFallsBackToTheServerQuietlyAndAnExplicitChoiceSaysWhy() {
        file(".prettierrc");
        var none = JsTools.formatPlan("auto", FILE, lookup(), TRUSTED);
        assertEquals(JsTools.Formatter.SERVER, none.formatter());
        assertNull(none.problem(), "configured but not installed: auto says nothing");
        assertEquals(
                JsTools.NOT_INSTALLED,
                JsTools.formatPlan("prettier", FILE, lookup(), TRUSTED).problem());
        assertEquals(
                JsTools.NOT_INSTALLED,
                JsTools.formatPlan("biome", FILE, lookup(), TRUSTED).problem());

        file("node_modules/prettier/bin/prettier.cjs");
        Predicate<Path> untrusted = p -> false;
        var auto = JsTools.formatPlan("auto", FILE, lookup(), untrusted);
        assertEquals(JsTools.Formatter.SERVER, auto.formatter(), "untrusted: the server");
        assertNull(auto.problem());
        var explicit = JsTools.formatPlan("prettier", FILE, lookup(), untrusted);
        assertEquals(JsTools.Formatter.SERVER, explicit.formatter());
        assertEquals(JsTools.UNTRUSTED, explicit.problem());
        assertEquals(
                JsTools.Formatter.SERVER,
                JsTools.formatPlan("server", FILE, lookup(), TRUSTED).formatter());
    }

    @Test
    void anExplicitChoiceNeedsNoConfiguration() {
        file("node_modules/@biomejs/biome/bin/biome");
        var plan = JsTools.formatPlan("biome", FILE, lookup(), TRUSTED);
        assertEquals(JsTools.Formatter.BIOME, plan.formatter());
        assertEquals(PROJECT, plan.dir(), "no biome.json: from the folder holding it");
    }

    @Test
    void theInstalledVersionIsReadFromThePackageNotRun() {
        file("node_modules/eslint/bin/eslint.js");
        file("node_modules/eslint/package.json", "{\"name\":\"eslint\",\"version\":\"9.12.0\"}");
        var tool = JsTools.tool(JsTools.Kind.ESLINT, FILE, lookup()).orElseThrow();
        assertEquals(Optional.of("9.12.0"), JsTools.installedVersion(tool, lookup()));
        file("node_modules/@biomejs/biome/bin/biome");
        var biome = JsTools.tool(JsTools.Kind.BIOME, FILE, lookup()).orElseThrow();
        assertTrue(JsTools.installedVersion(biome, lookup()).isEmpty());
        assertTrue(Set.of(JsTools.Kind.values()).size() == 3);
    }
}
