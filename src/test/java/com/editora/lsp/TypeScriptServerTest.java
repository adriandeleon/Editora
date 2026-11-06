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
        Path root = Path.of("/").toAbsolutePath(); // a drive on Windows: the walk works on absolute paths
        Path near = root.resolve("r/a/node_modules/typescript/lib/tsserver.js");
        Path far = root.resolve("r/node_modules/typescript/lib/tsserver.js");
        Set<Path> files = Set.of(near, far);
        assertEquals(near, TypeScriptServer.findTsserver(root.resolve("r/a/b"), null, files::contains));
        assertEquals(far, TypeScriptServer.findTsserver(root.resolve("r/x"), null, files::contains));
        assertNull(TypeScriptServer.findTsserver(root.resolve("r/x"), root.resolve("r/x"), files::contains));
        // A TypeScript 7 package has lib/version.cjs and lib/tsc.js but no tsserver.js.
        assertNull(TypeScriptServer.findTsserver(
                root.resolve("s"), null, p -> p.endsWith("node_modules/typescript/lib/tsc.js")));
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

    /** The LSP segment names the TypeScript a file runs on once the server says it (JS review B109). */
    @Test
    void theSegmentNamesTheRunningTypescriptAndAnIgnoredFolderVersionIsSeen() {
        assertEquals("TypeScript 5.9.3", TypeScriptServer.segmentName("typescript-language-server", "5.9.3"));
        assertEquals("typescript-language-server", TypeScriptServer.segmentName("typescript-language-server", ""));
        assertEquals("5.4.5", TypeScriptServer.differentMajor("5.4.5", "6.0.3"));
        assertEquals("", TypeScriptServer.differentMajor("5.4.5", "5.9.3"), "same major: nothing to say");
        assertEquals("", TypeScriptServer.differentMajor("", "5.9.3"));
    }

    @Test
    void anUntrustedFoldersOwnTypescriptOfAnotherMajorIsReported(@TempDir Path dir) throws Exception {
        Path folderLib = Files.createDirectories(dir.resolve("app/node_modules/typescript/lib"));
        Files.writeString(folderLib.getParent().resolve("package.json"), "{ \"version\": \"4.9.5\" }");
        Files.writeString(folderLib.resolve("tsserver.js"), "");
        Path ownLib = Files.createDirectories(dir.resolve("cfg/node_modules/typescript/lib"));
        Files.writeString(ownLib.getParent().resolve("package.json"), "{ \"version\": \"5.9.3\" }");
        Files.writeString(ownLib.resolve("tsserver.js"), "");
        var installed = TypeScriptServer.choose(
                null, folderLib.resolve("tsserver.js"), ownLib.resolve("tsserver.js"), "", false);
        assertEquals("4.9.5", TypeScriptServer.ignoredFolderVersion(installed, dir.resolve("app"), false));
        assertEquals("", TypeScriptServer.ignoredFolderVersion(installed, dir.resolve("app"), true), "loose files");
        var workspace = TypeScriptServer.choose(folderLib.resolve("tsserver.js"), null, null, "", false);
        assertEquals(
                "", TypeScriptServer.ignoredFolderVersion(workspace, dir.resolve("app"), false), "trusted: runs it");
    }

    /** A moved .ts/.js file or folder asks the server for import updates; node_modules and others do not (B105). */
    @Test
    void sourceFilesAndFoldersOutsideNodeModulesRenameImports(@TempDir Path dir) throws Exception {
        for (String name : List.of("a.ts", "b.tsx", "c.mts", "d.js", "e.jsx", "f.mjs", "g.cjs", "h.d.ts")) {
            assertTrue(TypeScriptServer.renamesImports(dir.resolve(name)), name);
        }
        assertTrue(TypeScriptServer.renamesImports(Files.createDirectories(dir.resolve("src/lib"))));
        assertFalse(TypeScriptServer.renamesImports(dir.resolve("README.md")));
        assertFalse(TypeScriptServer.renamesImports(dir.resolve("node_modules/x/index.js")));
        assertFalse(TypeScriptServer.renamesImports(null));
    }

    /** typescript-language-server outlives a killed tsserver; its log line is how Editora knows (JS review B102). */
    @Test
    void aKilledOrFailedTsserverIsSeenInTheServersLog() {
        assertTrue(
                TypeScriptServer.tsserverDied("[lspserver] [tsclient] [tsserver] Exited. Code: null. Signal: SIGKILL"));
        assertTrue(TypeScriptServer.tsserverDied("[tsserver] Exited. Code: 134. Signal: null"));
        assertFalse(TypeScriptServer.tsserverDied("[tsserver] Exited. Code: 0. Signal: null"));
        assertFalse(TypeScriptServer.tsserverDied("Using Typescript version (user-setting) 5.9.3"));
        assertFalse(TypeScriptServer.tsserverDied(null));
    }

    @Test
    void editorasOwnTypescriptComesBeforeTheOneBesideTheServer(@TempDir Path dir) throws Exception {
        Path global = dir.resolve("lib/node_modules");
        Path cli = global.resolve("typescript-language-server/lib/cli.mjs");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "");
        Path beside = Files.createDirectories(global.resolve("typescript/lib"));
        Files.writeString(beside.getParent().resolve("package.json"), "{ \"version\": \"6.0.3\" }");
        Files.writeString(beside.resolve("tsserver.js"), "");
        var onBeside = TypeScriptServer.sdk(cli, null);
        assertEquals(beside.resolve("tsserver.js"), onBeside.tsserver());
        assertFalse(onBeside.own());
        assertFalse(onBeside.suitable(), "TypeScript 6 leaves @types/node out (JS review B101)");
        Path own = Files.createDirectories(dir.resolve("cfg/plugins/lsp/typescript/node_modules/typescript/lib"));
        Files.writeString(own.getParent().resolve("package.json"), "{ \"version\": \"5.9.3\" }");
        Files.writeString(own.resolve("tsserver.js"), "");
        var onOwn = TypeScriptServer.sdk(cli, own.resolve("tsserver.js"));
        assertEquals(own.resolve("tsserver.js"), onOwn.tsserver());
        assertTrue(onOwn.own());
        assertTrue(onOwn.suitable());
        assertEquals("6.0.3", onOwn.besideVersion());
        // a TypeScript 7 beside the server and no own copy: nothing to run
        Files.delete(beside.resolve("tsserver.js"));
        assertFalse(TypeScriptServer.sdk(cli, null).found());
        // the start choice takes the own one even then (NodeServerPaths.typeScriptChoice passes it as the installed
        // one)
        var choice = TypeScriptServer.choose(null, null, own.resolve("tsserver.js"), "7.0.2", false);
        assertEquals(TypeScriptServer.Outcome.INSTALLED, choice.outcome());
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
