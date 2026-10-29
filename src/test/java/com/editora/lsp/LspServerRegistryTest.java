package com.editora.lsp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspServerRegistryTest {

    @Test
    void supportedLanguagesMapToTheirServer() {
        assertTrue(LspServerRegistry.isSupported("java"));
        assertTrue(LspServerRegistry.isSupported("javascript"));
        assertTrue(LspServerRegistry.isSupported("typescript"));
        assertTrue(LspServerRegistry.isSupported("typescriptreact"));
        assertTrue(LspServerRegistry.isSupported("python"));
        // groovy is served by gvy, which has no command until it is installed (like the pom.xml server).
        assertTrue(LspServerRegistry.isSupported("groovy"));
        assertTrue(LspServerRegistry.isSupported("clojure"));
        assertFalse(LspServerRegistry.isSupported("plaintext"));
        assertEquals("java", LspServerRegistry.serverIdFor("java"));
        // One TypeScript server serves all four JS/TS dialects.
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascriptreact"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescriptreact"));
        assertEquals("groovy", LspServerRegistry.serverIdFor("groovy"));
        assertEquals(List.of(), LspServerRegistry.commandFor("groovy", Map.of()), "not installed: no command");
        assertEquals(
                List.of("java", "-jar", "/x/gls.jar"),
                LspServerRegistry.commandFor("groovy", Map.of("groovy", "java -jar /x/gls.jar")));
        assertTrue(LspServerRegistry.rootMarkersFor("groovy").contains("Jenkinsfile"));
        // clojure-lsp serves .clj, .cljs, .cljc and .edn alike, from PATH until the installer names its own copy.
        assertEquals("clojure", LspServerRegistry.serverIdFor("clojure"));
        assertEquals(List.of("clojure-lsp"), LspServerRegistry.commandFor("clojure", Map.of()));
        assertEquals(
                List.of("/c/clojure-lsp"),
                LspServerRegistry.commandFor("clojure", Map.of("clojure", "/c/clojure-lsp")));
        assertEquals(
                List.of("deps.edn", "project.clj", "bb.edn", "shadow-cljs.edn", "build.boot", ".git"),
                LspServerRegistry.rootMarkersFor("clojure"));
        assertNull(LspServerRegistry.serverIdFor("plaintext"));
        assertNull(LspServerRegistry.specFor("plaintext", Map.of()));
    }

    @Test
    void blankCommandFallsBackToDefault() {
        var spec = LspServerRegistry.specFor("java", Map.of());
        assertEquals(List.of("jdtls"), spec.command());
        assertEquals("java", spec.serverId());
        assertTrue(spec.rootMarkers().contains("pom.xml"));

        assertEquals(List.of("jdtls"), LspServerRegistry.specFor("java", null).command());
    }

    @Test
    void typescriptServerDefaultsAndMarkers() {
        var spec = LspServerRegistry.specFor("typescript", Map.of());
        assertEquals("typescript", spec.serverId());
        assertEquals(List.of("typescript-language-server", "--stdio"), spec.command());
        assertTrue(spec.rootMarkers().contains("tsconfig.json"));
        assertTrue(spec.rootMarkers().contains("package.json"));
        // A .js file routes to the same TypeScript server.
        assertEquals(
                "typescript", LspServerRegistry.specFor("javascript", Map.of()).serverId());
    }

    @Test
    void pythonServerDefaultsAndMarkers() {
        assertTrue(LspServerRegistry.isSupported("python"));
        assertEquals("python", LspServerRegistry.serverIdFor("python"));
        var spec = LspServerRegistry.specFor("python", Map.of());
        assertEquals("python", spec.serverId());
        assertEquals(List.of("pyright-langserver", "--stdio"), spec.command());
        assertTrue(spec.rootMarkers().contains("pyproject.toml"));
    }

    @Test
    void xmlJsonBashServersDefaultsAndMarkers() {
        assertTrue(LspServerRegistry.isSupported("xml"));
        assertTrue(LspServerRegistry.isSupported("json"));
        assertTrue(LspServerRegistry.isSupported("shell"));
        assertEquals("xml", LspServerRegistry.serverIdFor("xml"));
        assertEquals("json", LspServerRegistry.serverIdFor("json"));
        // The Bash server's id is "bash" but it serves the "shell" language id.
        assertEquals("bash", LspServerRegistry.serverIdFor("shell"));

        assertEquals(
                List.of("lemminx"), LspServerRegistry.specFor("xml", Map.of()).command());
        assertEquals(
                List.of("vscode-json-language-server", "--stdio"),
                LspServerRegistry.specFor("json", Map.of()).command());
        assertEquals(
                List.of("bash-language-server", "start"),
                LspServerRegistry.specFor("shell", Map.of()).command());

        assertTrue(LspServerRegistry.specFor("xml", Map.of()).rootMarkers().contains("pom.xml"));
        assertTrue(LspServerRegistry.specFor("json", Map.of()).rootMarkers().contains("package.json"));
        assertTrue(LspServerRegistry.specFor("shell", Map.of()).rootMarkers().contains(".git"));
    }

    @Test
    void yamlGoRustPhpRubyServersDefaultsAndMarkers() {
        for (String lang : List.of("yaml", "go", "rust", "php", "ruby")) {
            assertTrue(LspServerRegistry.isSupported(lang), lang);
            // For these five the server id equals the language id.
            assertEquals(lang, LspServerRegistry.serverIdFor(lang), lang);
        }
        assertEquals(
                List.of("yaml-language-server", "--stdio"),
                LspServerRegistry.specFor("yaml", Map.of()).command());
        assertEquals(List.of("gopls"), LspServerRegistry.specFor("go", Map.of()).command());
        assertEquals(
                List.of("rust-analyzer"),
                LspServerRegistry.specFor("rust", Map.of()).command());
        assertEquals(
                List.of("phpactor", "language-server"),
                LspServerRegistry.specFor("php", Map.of()).command());
        assertEquals(
                List.of("ruby-lsp"), LspServerRegistry.specFor("ruby", Map.of()).command());

        assertTrue(LspServerRegistry.specFor("go", Map.of()).rootMarkers().contains("go.mod"));
        assertTrue(LspServerRegistry.specFor("rust", Map.of()).rootMarkers().contains("Cargo.toml"));
        assertTrue(LspServerRegistry.specFor("php", Map.of()).rootMarkers().contains("composer.json"));
        assertTrue(LspServerRegistry.specFor("ruby", Map.of()).rootMarkers().contains("Gemfile"));
    }

    @Test
    void systemsLanguageServersDefaultsAndMarkers() {
        // clangd is one server serving BOTH c and cpp (like the TypeScript server's JS/TS family).
        assertEquals("clangd", LspServerRegistry.serverIdFor("c"));
        assertEquals("clangd", LspServerRegistry.serverIdFor("cpp"));
        assertEquals(List.of("clangd"), LspServerRegistry.specFor("c", Map.of()).command());
        assertEquals(
                List.of("clangd"), LspServerRegistry.specFor("cpp", Map.of()).command());
        assertTrue(LspServerRegistry.specFor("c", Map.of()).rootMarkers().contains("compile_commands.json"));

        // The remaining eight: server id == language id.
        for (String lang : List.of("html", "css", "kotlin", "lua", "dockerfile", "sql", "terraform", "toml")) {
            assertTrue(LspServerRegistry.isSupported(lang), lang);
            assertEquals(lang, LspServerRegistry.serverIdFor(lang), lang);
        }
        assertEquals(
                List.of("vscode-html-language-server", "--stdio"),
                LspServerRegistry.specFor("html", Map.of()).command());
        assertEquals(
                List.of("vscode-css-language-server", "--stdio"),
                LspServerRegistry.specFor("css", Map.of()).command());
        assertEquals(
                List.of("kotlin-lsp", "--stdio"),
                LspServerRegistry.specFor("kotlin", Map.of()).command(),
                "JetBrains' Kotlin LSP (D1)");
        assertEquals(
                List.of("kotlin-language-server"),
                LspServerRegistry.specFor("kotlin", Map.of("kotlin", "kotlin-language-server"))
                        .command(),
                "fwcd's server stays a configured command");
        assertEquals(
                List.of(
                        List.of("settings.gradle.kts", "settings.gradle"),
                        List.of("build.gradle.kts", "build.gradle", "pom.xml")),
                LspServerRegistry.rootMarkerTiersForServer("kotlin"),
                "a multi-module Gradle build is one root; no .git fallback");
        assertEquals(
                List.of("lua-language-server"),
                LspServerRegistry.specFor("lua", Map.of()).command());
        assertEquals(
                List.of("docker-langserver", "--stdio"),
                LspServerRegistry.specFor("dockerfile", Map.of()).command());
        assertEquals(List.of("sqls"), LspServerRegistry.specFor("sql", Map.of()).command());
        assertEquals(
                List.of("terraform-ls", "serve"),
                LspServerRegistry.specFor("terraform", Map.of()).command());
        assertEquals(
                List.of("taplo", "lsp", "stdio"),
                LspServerRegistry.specFor("toml", Map.of()).command());

        assertTrue(LspServerRegistry.specFor("kotlin", Map.of()).rootMarkers().contains("build.gradle.kts"));
        assertTrue(
                LspServerRegistry.specFor("terraform", Map.of()).rootMarkers().contains(".terraform"));
    }

    @Test
    void csharpServerDefaultsAndMarkers() {
        assertTrue(LspServerRegistry.isSupported("csharp"));
        assertEquals("csharp", LspServerRegistry.serverIdFor("csharp"));
        assertEquals(
                List.of("csharp-ls"),
                LspServerRegistry.specFor("csharp", Map.of()).command());
        assertTrue(LspServerRegistry.specFor("csharp", Map.of()).rootMarkers().contains(".git"));
    }

    @Test
    void typstServerDefaultsAndMarkers() {
        assertTrue(LspServerRegistry.isSupported("typst"));
        assertEquals("typst", LspServerRegistry.serverIdFor("typst"));
        assertEquals(
                List.of("tinymist", "lsp"),
                LspServerRegistry.specFor("typst", Map.of()).command());
        assertTrue(LspServerRegistry.specFor("typst", Map.of()).rootMarkers().contains("typst.toml"));
        assertTrue(LspServerRegistry.specFor("typst", Map.of()).rootMarkers().contains(".git"));
    }

    @Test
    void astroServerDefaultsAndMarkers() {
        assertTrue(LspServerRegistry.isSupported("astro"));
        assertEquals("astro", LspServerRegistry.serverIdFor("astro"));
        assertEquals(
                List.of("astro-ls", "--stdio"),
                LspServerRegistry.specFor("astro", Map.of()).command());
        assertTrue(LspServerRegistry.specFor("astro", Map.of()).rootMarkers().contains("astro.config.mjs"));
        assertTrue(LspServerRegistry.specFor("astro", Map.of()).rootMarkers().contains("package.json"));
    }

    @Test
    void configuredCommandIsTokenizedPerServer() {
        var java = LspServerRegistry.specFor("java", Map.of("java", "java -jar /opt/jdtls/launcher.jar -data ws"));
        assertEquals(List.of("java", "-jar", "/opt/jdtls/launcher.jar", "-data", "ws"), java.command());

        var ts = LspServerRegistry.specFor("typescript", Map.of("typescript", "vtsls --stdio"));
        assertEquals(List.of("vtsls", "--stdio"), ts.command());
    }

    @Test
    void tokenizeHonorsQuotesAndCollapsesWhitespace() {
        assertEquals(
                List.of("/opt/my server/jdtls", "-x"), LspServerRegistry.tokenize("\"/opt/my server/jdtls\"   -x"));
        assertEquals(List.of("a", "b"), LspServerRegistry.tokenize("  a    b  "));
        assertEquals(List.of(), LspServerRegistry.tokenize("   "));
        assertEquals(List.of(), LspServerRegistry.tokenize(null));
    }

    @Test
    void withDataDirAppendsWorkspaceArgUnlessAlreadyPresent() {
        var base = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), LspServerRegistry.JAVA_ROOT_MARKERS);
        assertEquals(
                List.of("jdtls", "-data", "/ws/abc"),
                LspServerRegistry.withDataDir(base, "/ws/abc").command());

        // A command that already specifies -data (user-customized) is left untouched.
        var custom = new LspServerRegistry.ServerSpec(
                "java", List.of("jdtls", "-data", "/mine"), LspServerRegistry.JAVA_ROOT_MARKERS);
        assertEquals(custom, LspServerRegistry.withDataDir(custom, "/ws/abc"));
        // Null/blank dir is a no-op.
        assertEquals(base, LspServerRegistry.withDataDir(base, null));
        assertEquals(base, LspServerRegistry.withDataDir(base, "  "));
    }

    @Test
    void workspaceDirNameIsStablePerRootAndDistinctAcrossRoots() {
        java.nio.file.Path a = java.nio.file.Path.of("/home/u/projA");
        String na = LspServerRegistry.workspaceDirName(a);
        assertEquals(na, LspServerRegistry.workspaceDirName(a)); // stable for the same root
        assertEquals(16, na.length());
        assertTrue(na.matches("[0-9a-f]{16}")); // filesystem-safe hex
        assertFalse(na.equals(LspServerRegistry.workspaceDirName(java.nio.file.Path.of("/home/u/projB"))));
    }

    @Test
    void mavenPomServerRoutesOnItsPseudoLanguageId() {
        // The Maven server is keyed on the routing-only pseudo id; its editor language stays "xml".
        assertEquals("maven-pom", LspServerRegistry.serverIdFor("maven-pom"));
        assertTrue(LspServerRegistry.isSupported("maven-pom"));
        // No built-in command — the install recipe writes the java -cp launcher into Settings.
        assertEquals("", LspServerRegistry.defaultCommandFor("maven-pom"));
        assertEquals(List.of("pom.xml", ".git"), LspServerRegistry.rootMarkersForServer("maven-pom"));
        // Tiered: the nearest pom first, then the repository (the coordinator then widens to the reactor root).
        assertEquals(
                List.of(List.of("pom.xml"), List.of(".git")), LspServerRegistry.rootMarkerTiersForServer("maven-pom"));
        // The plain XML server is unchanged and distinct from the Maven server.
        assertEquals("xml", LspServerRegistry.serverIdFor("xml"));
    }

    @Test
    void displayNamesCoverTheServersWhoseProgramNameSaysTheWrongThing() {
        assertEquals("lemminx", LspServerRegistry.displayNameFor("xml"), "not lemminx-linux-x86_64");
        assertEquals("lemminx-maven", LspServerRegistry.displayNameFor("maven-pom"), "not java");
        assertEquals("jdtls", LspServerRegistry.displayNameFor("java"));
        assertNull(LspServerRegistry.displayNameFor("go"), "gopls is its own right name");
        assertNull(LspServerRegistry.displayNameFor(null));
    }

    @Test
    void isPomFileMatchesOnlyPomXmlCaseInsensitively() {
        assertTrue(LspServerRegistry.isPomFile("pom.xml"));
        assertTrue(LspServerRegistry.isPomFile("POM.XML"));
        assertFalse(LspServerRegistry.isPomFile("build.xml"));
        assertFalse(LspServerRegistry.isPomFile("mypom.xml"));
        assertFalse(LspServerRegistry.isPomFile("pom.xml.bak"));
        assertFalse(LspServerRegistry.isPomFile(null));
    }

    @Test
    void protocolLanguageIdTranslatesMavenRouteToXml() {
        // lemminx keys its XML handling on the "xml" languageId; lemminx-maven detects the POM by URI.
        assertEquals("xml", LspServerRegistry.protocolLanguageId("maven-pom"));
        // Every other id passes through unchanged (so no other server is affected).
        assertEquals("xml", LspServerRegistry.protocolLanguageId("xml"));
        assertEquals("java", LspServerRegistry.protocolLanguageId("java"));
        assertEquals("javascript", LspServerRegistry.protocolLanguageId("javascript"));
        assertEquals("shellscript", LspServerRegistry.protocolLanguageId("shell"), "LSP's id for shell scripts");
    }

    /**
     * The large-source tier (10,000 lines by default) turns language servers off; Python keeps Pyright to
     * 50,000 lines, which it checks in seconds (Python review B10).
     */
    @Test
    void pythonKeepsItsServerInTheLargeFileTier() {
        assertTrue(LspServerRegistry.lspAllowedForSize("python", true, 12_000));
        assertFalse(LspServerRegistry.lspAllowedForSize("python", true, 50_000));
        assertTrue(LspServerRegistry.lspAllowedForSize("java", true, 12_000), "Java keeps jdtls to 50,000 lines (C16)");
        assertFalse(LspServerRegistry.lspAllowedForSize("typescript", true, 12_000));
        assertTrue(LspServerRegistry.lspAllowedForSize("java", false, 12_000), "below the tier: always");
    }

    /** Review 2, A5: a bare {@code java} that loads this machine's jars is not sent to a remote host. */
    @org.junit.jupiter.api.Test
    void aRemoteHostIsNotSentALocalClasspath() {
        String local = "java -cp \"/home/u/.editora/plugins/lsp/maven/*\" org.eclipse.lemminx.XMLServerLauncher";
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(), LspServerRegistry.remoteCommandFor("maven-pom", local, null));
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("lemminx"), LspServerRegistry.remoteCommandFor("xml", local, null), "the host's own");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("java", "-jar", "server.jar"),
                LspServerRegistry.remoteCommandFor("groovy", "java -jar server.jar", null),
                "a jar named without a path is still the host's");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("lemminx-maven"),
                LspServerRegistry.remoteCommandFor("maven-pom", local, "lemminx-maven"),
                "the host's configured tool wins");
    }

    /** HTML review A15: a `node …/htmlServerNodeMain.js` command is named by the server, not by `node`. */
    @Test
    void theWebServersAreNamedByTheirProgramWhenAGenericLauncherStartsThem() {
        assertEquals(
                "vscode-html-language-server",
                LspServerRegistry.displayNameFor("html", List.of("node", "/x/htmlServerNodeMain.js", "--stdio")));
        assertEquals(
                "vscode-css-language-server",
                LspServerRegistry.displayNameFor("css", List.of("/usr/bin/nodejs", "/x/cssServerMain.js")));
        assertEquals("vscode-json-language-server", LspServerRegistry.displayNameFor("json", List.of("wrap.sh")));
        assertEquals("vscode-html-language-server", LspServerRegistry.displayNameFor("html", List.of()));
        assertNull(
                LspServerRegistry.displayNameFor("html", List.of("superhtml", "lsp")),
                "another program keeps its own name");
        assertNull(LspServerRegistry.displayNameFor("go", List.of("node")), "only the web servers");
        assertEquals("lemminx", LspServerRegistry.displayNameFor("xml", List.of("node")));
    }

    /** HTML review A14: the server answers a 48,000-line page in well under a second. */
    @Test
    void htmlKeepsItsServerUpTo50000Lines() {
        assertEquals(50_000, LspServerRegistry.heavyFileLspLineLimit("html"));
        assertTrue(LspServerRegistry.lspAllowedForSize("html", true, 48_004));
        assertFalse(LspServerRegistry.lspAllowedForSize("html", true, 50_000));
        assertEquals(0, LspServerRegistry.heavyFileLspLineLimit("css"));
    }
}
