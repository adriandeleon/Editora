package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which tsserver typescript-language-server is told to run, and what else it is told (Node review B1–B8). */
class TypeScriptServerTest {

    private static final Path WORKSPACE = Path.of("/w/app/node_modules/typescript/lib/tsserver.js");
    private static final Path INSTALLED = Path.of("/g/lib/node_modules/typescript/lib/tsserver.js");

    @Test
    void aTrustedFolderRunsItsOwnTypescript() {
        var c = TypeScriptServer.choose(WORKSPACE, WORKSPACE, INSTALLED, "6.0.3", false);
        assertEquals(TypeScriptServer.Outcome.WORKSPACE, c.outcome());
        assertEquals(WORKSPACE, c.tsserver());
    }

    @Test
    void anUntrustedFolderNeverRunsItsOwnTypescript() {
        var c = TypeScriptServer.choose(null, WORKSPACE, INSTALLED, "6.0.3", false);
        assertEquals(TypeScriptServer.Outcome.INSTALLED, c.outcome());
        assertEquals(INSTALLED, c.tsserver());
    }

    @Test
    void anUntrustedFolderWhoseOnlyTypescriptIsItsOwnIsWithheld() {
        var c = TypeScriptServer.choose(null, WORKSPACE, null, "7.0.2", false);
        assertEquals(TypeScriptServer.Outcome.WITHHELD_UNTRUSTED, c.outcome());
        assertFalse(c.starts());
    }

    @Test
    void typescriptSevenBesideTheServerIsNoTypescript() {
        var c = TypeScriptServer.choose(null, null, null, "7.0.2", false);
        assertEquals(TypeScriptServer.Outcome.NO_SDK, c.outcome());
        assertEquals("7.0.2", c.installedVersion());
    }

    @Test
    void theLooseFilesSessionOnlyEverUsesTheInstalledTypescript() {
        assertEquals(
                INSTALLED,
                TypeScriptServer.choose(WORKSPACE, WORKSPACE, INSTALLED, "", true)
                        .tsserver());
        assertEquals(
                TypeScriptServer.Outcome.NO_SDK,
                TypeScriptServer.choose(WORKSPACE, WORKSPACE, null, "", true).outcome());
    }

    @Test
    void theWalkFindsTheNearestTsserverWithinTheCeilingAndSkipsTypescriptSeven() {
        Path near = Path.of("/r/a/node_modules/typescript/lib/tsserver.js");
        Path far = Path.of("/r/node_modules/typescript/lib/tsserver.js");
        Set<Path> files = Set.of(near, far);
        assertEquals(near, TypeScriptServer.findTsserver(Path.of("/r/a/b"), null, files::contains));
        assertEquals(far, TypeScriptServer.findTsserver(Path.of("/r/x"), null, files::contains));
        assertNull(TypeScriptServer.findTsserver(Path.of("/r/x"), Path.of("/r/x"), files::contains));
        // A TypeScript 7 package has lib/version.cjs and lib/tsc.js but no tsserver.js.
        assertNull(TypeScriptServer.findTsserver(
                Path.of("/s"), null, p -> p.endsWith("node_modules/typescript/lib/tsc.js")));
    }

    @Test
    void theInstalledTypescriptIsFoundBesideTheServerAsNodeWouldResolveIt(@TempDir Path dir) throws Exception {
        Path global = dir.resolve("lib/node_modules");
        Path cli = global.resolve("typescript-language-server/lib/cli.mjs");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "");
        Path pkg = Files.createDirectories(global.resolve("typescript/lib"));
        Files.writeString(
                pkg.getParent().resolve("package.json"), "{ \"name\": \"typescript\", \"version\": \"7.0.2\" }");
        assertNull(TypeScriptServer.installedTsserver(cli), "TypeScript 7: no tsserver");
        assertEquals("7.0.2", TypeScriptServer.installedPackageVersion(cli));
        Files.writeString(pkg.resolve("tsserver.js"), "");
        assertEquals(pkg.resolve("tsserver.js"), TypeScriptServer.installedTsserver(cli));
        assertEquals("7.0.2", TypeScriptServer.sdkVersion(pkg.resolve("tsserver.js")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializationOptionsNameTheTsserverAndTurnTypeAcquisitionOffByDefault() {
        Map<String, Object> o = TypeScriptServer.initializationOptions(INSTALLED, false, false);
        assertEquals(INSTALLED.toString(), ((Map<String, Object>) o.get("tsserver")).get("path"));
        assertFalse(((Map<String, Object>) o.get("tsserver")).containsKey("useSyntaxServer"));
        assertEquals(true, o.get("disableAutomaticTypingAcquisition"));
        assertEquals(
                false,
                TypeScriptServer.initializationOptions(INSTALLED, true, false)
                        .get("disableAutomaticTypingAcquisition"));
        Map<String, Object> loose = TypeScriptServer.initializationOptions(INSTALLED, false, true);
        assertEquals("never", ((Map<String, Object>) loose.get("tsserver")).get("useSyntaxServer"));
        assertFalse(TypeScriptServer.initializationOptions(null, false, false).containsKey("tsserver"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsTurnInlayHintsAndCodeLensesOnForJavascriptAndTypescript() {
        Map<String, Object> on = TypeScriptServer.settings(true, true);
        for (String lang : List.of("javascript", "typescript")) {
            Map<String, Object> l = (Map<String, Object>) on.get(lang);
            Map<String, Object> hints = (Map<String, Object>) l.get("inlayHints");
            assertEquals("all", hints.get("includeInlayParameterNameHints"));
            assertEquals(true, ((Map<String, Object>) l.get("referencesCodeLens")).get("enabled"));
            assertEquals(true, ((Map<String, Object>) l.get("implementationsCodeLens")).get("enabled"));
        }
        Map<String, Object> off =
                (Map<String, Object>) TypeScriptServer.settings(false, false).get("javascript");
        assertEquals("none", ((Map<String, Object>) off.get("inlayHints")).get("includeInlayParameterNameHints"));
        assertEquals(false, ((Map<String, Object>) off.get("referencesCodeLens")).get("enabled"));
    }

    @Test
    void formattingOptionsAreTheDocumentsOwnIndent() {
        assertEquals(
                Map.of("tabSize", 2, "insertSpaces", true),
                TypeScriptServer.answer("formattingOptions", new FormattingIndent(2, true)));
        assertNull(TypeScriptServer.answer("formattingOptions", null));
        assertNull(TypeScriptServer.answer("typescript", new FormattingIndent(2, true)));
    }

    @Test
    void packageVersionReadsTheVersionField() {
        assertEquals("6.0.3", TypeScriptServer.packageVersion("{\"name\":\"typescript\",\n \"version\" : \"6.0.3\"}"));
        assertEquals("", TypeScriptServer.packageVersion("{}"));
        assertEquals("", TypeScriptServer.packageVersion(null));
        assertTrue(TypeScriptServer.CODE_ACTION_KINDS.contains("source"));
    }
}
