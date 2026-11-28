package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which language servers wait for a folder's trust before loading its build ({@link UntrustedBuilds}), how far each
 * looks for a build, and the settings that keep jdtls's imports off.
 */
class UntrustedBuildsTest {

    @TempDir
    Path dir;

    @Test
    void onlyTheServersThatRunAProjectsBuildAreGated() {
        for (String id : List.of("java", "kotlin", "scala", "rust", "ruby", "csharp", "clojure", "dart", "elixir")) {
            assertTrue(UntrustedBuilds.gated(id), id);
        }
        for (String id : List.of("python", "typescript", "go", "xml", "bash", "json", "html", "css", "clangd")) {
            assertFalse(UntrustedBuilds.gated(id), id);
        }
        assertFalse(UntrustedBuilds.gated(null));
    }

    @Test
    void javaStartsWithItsImportsOffWhereTheOthersAreWithheld() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("build.gradle.kts"), "");
        assertTrue(UntrustedBuilds.importsOff("java", false));
        assertFalse(UntrustedBuilds.importsOff("java", true), "a trusted folder imports");
        assertFalse(UntrustedBuilds.importsOff("kotlin", false), "Kotlin is withheld instead");
        assertFalse(UntrustedBuilds.withholds("java", dir, false), "Java is never withheld");
        assertTrue(UntrustedBuilds.withholds("kotlin", dir, false));
        assertFalse(UntrustedBuilds.withholds("kotlin", dir, true), "trusted: started as before");
        assertTrue(UntrustedBuilds.hasBuild("java", dir));
    }

    @Test
    void aFolderWithoutABuildStartsItsServerUntrusted() throws Exception {
        Files.writeString(dir.resolve("main.rs"), "fn main() {}\n");
        Files.writeString(dir.resolve("core.clj"), "(ns core)\n");
        Files.writeString(dir.resolve("deps.edn"), "{}"); // read as data by clojure-lsp: nothing of the folder runs
        assertFalse(UntrustedBuilds.withholds("rust", dir, false), "a loose crate file has nothing to build");
        assertFalse(UntrustedBuilds.withholds("clojure", dir, false));
        Files.writeString(dir.resolve("pubspec.yaml"), "name: app\n"); // read as data: Dart runs no plugin without one
        assertFalse(UntrustedBuilds.withholds("dart", dir, false));
        Files.writeString(dir.resolve("analysis_options.yaml"), "plugins:\n  some_plugin: ^1.0.0\n");
        assertTrue(UntrustedBuilds.withholds("dart", dir, false), "its analyzer plugins run the folder's code");
        Files.writeString(dir.resolve("hello.exs"), "IO.puts(:hi)\n");
        assertFalse(UntrustedBuilds.withholds("elixir", dir, false), "a loose script: ElixirLS compiles nothing");
        Files.writeString(dir.resolve("mix.exs"), "defmodule App.MixProject do\nend\n");
        assertTrue(UntrustedBuilds.withholds("elixir", dir, false), "ElixirLS compiles the Mix project");
        assertFalse(UntrustedBuilds.withholds("elixir", dir, true), "trusted: started as before");
        assertFalse(UntrustedBuilds.hasBuild("java", dir));
        assertFalse(UntrustedBuilds.hasBuild("python", dir), "not a gated server");
        assertFalse(UntrustedBuilds.hasBuild("rust", dir.resolve("absent")), "an unreadable folder");
    }

    @Test
    void eachServerLooksAsDeepAsItsOwnDiscoveryDoes() throws Exception {
        Path crate = Files.createDirectories(dir.resolve("crates/app"));
        Files.writeString(crate.resolve("Cargo.toml"), "[package]\n");
        assertFalse(UntrustedBuilds.hasBuild("rust", dir), "two folders down: rust-analyzer does not look there");
        assertTrue(UntrustedBuilds.hasBuild("rust", dir.resolve("crates")), "one folder down");

        Path project = Files.createDirectories(dir.resolve("src/App"));
        Files.writeString(project.resolve("App.csproj"), "<Project/>");
        assertTrue(UntrustedBuilds.hasBuild("csharp", dir), "a solution's projects, two folders down");
        assertFalse(UntrustedBuilds.hasBuild("csharp", project.resolve("absent")));

        Path ignored = Files.createDirectories(dir.resolve("only/node_modules/pkg"));
        Files.writeString(ignored.resolve("x.csproj"), "<Project/>");
        assertFalse(
                UntrustedBuilds.hasBuild("csharp", dir.resolve("only")), "dependencies are not the project's build");

        Path bare = Files.createDirectories(dir.resolve("bare"));
        Files.writeString(bare.resolve(".csproj"), ""); // a name that is only the extension is not a project
        assertFalse(UntrustedBuilds.hasBuild("csharp", bare));
    }

    /**
     * Regression (HN dry-run audit, 2026-11-27): Ruby LSP requires every {@code ruby_lsp/**}{@code /addon.rb} under
     * the workspace and runs {@code bundle exec} on a {@code .ruby-lsp} bundle the folder ships, with no Gemfile at
     * the root, so no untrusted folder is safe to start it on.
     */
    @Test
    void rubyWaitsForTrustInEveryFolder() throws Exception {
        Files.createDirectories(dir.resolve("x/ruby_lsp/evil")).resolve("addon.rb");
        Files.writeString(dir.resolve("x/ruby_lsp/evil/addon.rb"), "system('touch /tmp/pwned')\n");
        assertTrue(UntrustedBuilds.withholds("ruby", dir, false), "an add-on, and no Gemfile");
        assertTrue(UntrustedBuilds.withholds("ruby", Files.createDirectories(dir.resolve("empty")), false));
        assertFalse(UntrustedBuilds.withholds("ruby", dir, true), "trusted: started as before");
    }

    /** Regression (audit): the Groovy server resolves a Gradle or Maven build when it starts, and was not gated. */
    @Test
    void groovyWaitsForTrustOnAGradleOrMavenBuild() throws Exception {
        Files.writeString(dir.resolve("Jenkinsfile"), "pipeline {}\n");
        assertFalse(UntrustedBuilds.withholds("groovy", dir, false), "a loose script: nothing to resolve");
        Files.writeString(dir.resolve("build.gradle"), "println 'runs'\n");
        assertTrue(UntrustedBuilds.withholds("groovy", dir, false));
        Path sub = Files.createDirectories(dir.resolve("other/scripts"));
        Files.writeString(dir.resolve("other/settings.gradle"), "");
        assertTrue(UntrustedBuilds.hasBuild("groovy", sub), "Gradle finds settings.gradle in a parent folder");
    }

    /**
     * Regression (audit): rust-analyzer looks for a crate in every child folder, dot folders and build/ included, and
     * in the parent folders, and a rust-project.json names proc-macro libraries; the search skipped all of those.
     */
    @Test
    void rustLooksWhereRustAnalyzerLooks() throws Exception {
        Path a = Files.createDirectories(dir.resolve("a/build"));
        Files.writeString(a.resolve("Cargo.toml"), "[package]\n");
        assertTrue(UntrustedBuilds.hasBuild("rust", dir.resolve("a")), "a crate in build/");
        Path b = Files.createDirectories(dir.resolve("b/.hidden"));
        Files.writeString(b.resolve("Cargo.toml"), "[package]\n");
        assertTrue(UntrustedBuilds.hasBuild("rust", dir.resolve("b")), "a crate in a dot folder");
        Path c = Files.createDirectories(dir.resolve("c"));
        Files.writeString(c.resolve("rust-project.json"), "{}");
        assertTrue(UntrustedBuilds.hasBuild("rust", c));
        Path d = Files.createDirectories(dir.resolve("d/src/bin"));
        Files.writeString(dir.resolve("d/Cargo.toml"), "[package]\n");
        assertTrue(UntrustedBuilds.hasBuild("rust", d), "the crate of a subfolder opened on its own");
        assertFalse(UntrustedBuilds.hasBuild("rust", Files.createDirectories(dir.resolve("e"))));
    }

    /** Regression (audit): a tree too big to finish searching was taken for one with no build. */
    @Test
    void aSearchThatRunsOutAnswersThatThereIsABuild() throws Exception {
        Path big = Files.createDirectories(dir.resolve("big"));
        for (int i = 0; i < 450; i++) {
            Files.createDirectory(big.resolve("d" + i));
        }
        assertTrue(UntrustedBuilds.hasBuild("rust", big), "not fully looked at: it waits for trust");
    }

    /** Regression (audit): astro-ls loads the project's own framework packages and Prettier from node_modules. */
    @Test
    void astroWaitsForTrustWithNodeModules() throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        assertFalse(UntrustedBuilds.withholds("astro", site, false), "no packages to load");
        Files.createDirectories(dir.resolve("node_modules/@astrojs/svelte"));
        assertTrue(UntrustedBuilds.withholds("astro", site, false), "Node finds them in a parent folder");
        Files.createDirectories(site.resolve("node_modules"));
        assertTrue(UntrustedBuilds.withholds("astro", site, false));
    }

    /**
     * Regression (audit): terraform-ls starts the providers installed in .terraform, Phpactor loads the Composer
     * autoloader, clojure-lsp runs the classpath command .lsp/config.edn names, and Metals launches a .bsp server.
     */
    @Test
    void terraformPhpClojureAndScalaWaitForWhatTheyWouldRun() throws Exception {
        Path tf = Files.createDirectories(dir.resolve("infra"));
        assertFalse(UntrustedBuilds.withholds("terraform", tf, false));
        Files.createDirectories(tf.resolve("modules/net/.terraform/providers"));
        assertTrue(UntrustedBuilds.withholds("terraform", tf, false));

        Path php = Files.createDirectories(dir.resolve("app"));
        assertFalse(UntrustedBuilds.withholds("php", php, false));
        Files.createDirectories(php.resolve("vendor"));
        Files.writeString(php.resolve("vendor/autoload.php"), "<?php\n");
        assertTrue(UntrustedBuilds.withholds("php", php, false));

        Path clj = Files.createDirectories(dir.resolve("clj"));
        Files.writeString(clj.resolve("deps.edn"), "{}");
        assertFalse(UntrustedBuilds.withholds("clojure", clj, false), "deps.edn is read as data");
        Files.createDirectories(clj.resolve(".lsp"));
        Files.writeString(clj.resolve(".lsp/config.edn"), "{:project-specs []}");
        assertTrue(UntrustedBuilds.withholds("clojure", clj, false));

        Path scala = Files.createDirectories(dir.resolve("scala"));
        Files.createDirectories(scala.resolve(".bsp"));
        assertTrue(UntrustedBuilds.withholds("scala", scala, false));
    }

    @Test
    void importsOffTurnsBothImportersAndTheWrapperOffAndKeepsTheRest() {
        Map<String, Object> java = Map.of(
                "autobuild",
                Map.of("enabled", false),
                "import",
                Map.of("gradle", Map.of("arguments", List.of("-Dorg.gradle.daemon.idletimeout=600000"))),
                "configuration",
                Map.of("runtimes", List.of()));
        Map<String, Object> off = UntrustedBuilds.withImportsOff(java);
        assertEquals(Map.of("enabled", false), off.get("autobuild"));
        @SuppressWarnings("unchecked")
        Map<String, Object> imports = (Map<String, Object>) off.get("import");
        @SuppressWarnings("unchecked")
        Map<String, Object> gradle = (Map<String, Object>) imports.get("gradle");
        assertEquals(false, gradle.get("enabled"));
        assertEquals(Map.of("enabled", false), gradle.get("wrapper"));
        assertEquals(List.of("-Dorg.gradle.daemon.idletimeout=600000"), gradle.get("arguments"), "kept");
        assertEquals(Map.of("enabled", false), imports.get("maven"));
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) off.get("configuration");
        assertEquals("disabled", configuration.get("updateBuildConfiguration"));
        assertEquals(List.of(), configuration.get("runtimes"), "kept");
    }

    @Test
    void theInitOptionsGetTheSameSettingsAndAnythingElsePassesThrough() {
        Object options = Map.of("bundles", List.of("a.jar"), "settings", Map.of("java", Map.of("x", 1)));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) UntrustedBuilds.initOptionsWithImportsOff(options);
        assertEquals(List.of("a.jar"), out.get("bundles"));
        @SuppressWarnings("unchecked")
        Map<String, Object> java = (Map<String, Object>) ((Map<String, Object>) out.get("settings")).get("java");
        assertEquals(1, java.get("x"));
        assertTrue(java.containsKey("import"));

        Object other = Map.of("provideFormatter", true);
        assertSame(other, UntrustedBuilds.initOptionsWithImportsOff(other));
        assertEquals(null, UntrustedBuilds.initOptionsWithImportsOff(null));
    }

    @Test
    void theRealJavaInitOptionsComeOutWithImportsOff() {
        Object options = ServerInitOptions.initOptionsFor("java", List.of(), null, false, List.of(), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> java = (Map<String, Object>) ((Map<String, Object>)
                        ((Map<String, Object>) UntrustedBuilds.initOptionsWithImportsOff(options)).get("settings"))
                .get("java");
        @SuppressWarnings("unchecked")
        Map<String, Object> imports = (Map<String, Object>) java.get("import");
        assertEquals(Map.of("enabled", false), imports.get("maven"));
        assertEquals(Map.of("enabled", false), java.get("autobuild"), "autobuild stays off");
    }
}
