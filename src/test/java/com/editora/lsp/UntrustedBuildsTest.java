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
        for (String id : List.of("java", "kotlin", "scala", "rust", "ruby", "csharp", "clojure")) {
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
        Object options = LspManager.initOptionsFor("java", List.of(), null, false, List.of(), false);
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
