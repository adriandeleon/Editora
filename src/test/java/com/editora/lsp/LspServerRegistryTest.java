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
        // groovy has highlighting but no bundled language server.
        assertFalse(LspServerRegistry.isSupported("groovy"));
        assertFalse(LspServerRegistry.isSupported("plaintext"));
        assertEquals("java", LspServerRegistry.serverIdFor("java"));
        // One TypeScript server serves all four JS/TS dialects.
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascriptreact"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescriptreact"));
        assertNull(LspServerRegistry.serverIdFor("groovy"));
        assertNull(LspServerRegistry.specFor("groovy", Map.of()));
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
                List.of("kotlin-language-server"),
                LspServerRegistry.specFor("kotlin", Map.of()).command());
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
}
