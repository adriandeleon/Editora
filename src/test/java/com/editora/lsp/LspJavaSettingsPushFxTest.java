package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java settings that reach a running jdtls without a restart: the excluded types from Settings (re-review
 * DN1) and the project's own style when its .editorconfig, formatter profile or build file changes (AN5).
 */
@Tag("fx")
class LspJavaSettingsPushFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private List<FakeLanguageServer> fakes;

    @BeforeEach
    void setUp() {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private String lastPush() {
        var pushes = fakes.get(0).configurations;
        assertFalse(pushes.isEmpty(), "a configuration was pushed");
        return String.valueOf(pushes.get(pushes.size() - 1).getSettings());
    }

    @Test
    void theDefaultExclusionsAreVscodeJavasWithAwtAndSettingsAgree() {
        assertEquals(
                List.of("java.awt.*", "com.sun.*", "sun.*", "jdk.*", "org.graalvm.*", "io.micrometer.shaded.*"),
                LspServerSettings.FILTERED_TYPES);
        assertEquals(LspServerSettings.FILTERED_TYPES, Settings.DEFAULT_JAVA_EXCLUDED_TYPES);
        assertFalse(LspServerSettings.FILTERED_TYPES.contains("java.beans.*"), "JavaBeans can be imported (DN1)");
    }

    @Test
    void excludedTypesFromSettingsReachTheRunningServer() throws Exception {
        Path f = root.resolve("A.java");
        Files.writeString(f, "class A {}\n");
        manager.openDocument(f, root, "java", "class A {}\n");

        manager.setJavaFilteredTypes(List.of("java.awt.*", "org.example.legacy.*"));

        assertTrue(lastPush().contains("org.example.legacy.*"), lastPush());
    }

    @Test
    void aNewDefaultJdkSaysARunningJavaServerKeepsItsOwn() throws Exception {
        assertFalse(manager.setJavaDefaultJdk("/jdk/21"), "no Java server runs: nothing to restart");
        Path f = root.resolve("A.java");
        Files.writeString(f, "class A {}\n");
        manager.openDocument(f, root, "java", "class A {}\n");
        assertTrue(manager.hasSession("java", root), "started or starting: no second \"Starting…\" (C18)");
        assertTrue(manager.setJavaDefaultJdk("/jdk/25"), "it keeps /jdk/21 until restarted (re-review CN7)");
        assertFalse(manager.setJavaDefaultJdk("/jdk/25"), "unchanged");
    }

    @Test
    void aChangedEditorConfigPushesTheProjectsImportLayoutAgain() throws Exception {
        Path f = root.resolve("A.java");
        Files.writeString(f, "class A {}\n");
        manager.openDocument(f, root, "java", "class A {}\n");
        int before = fakes.get(0).configurations.size();

        Path editorconfig = root.resolve(".editorconfig");
        Files.writeString(editorconfig, "root = true\n[*.java]\nij_java_class_count_to_use_import_on_demand = 3\n");
        com.editora.editorconfig.EditorConfig.clearCache();
        manager.projectStyleChanged(editorconfig);

        assertEquals(before + 1, fakes.get(0).configurations.size());
        assertTrue(lastPush().contains("starThreshold=3"), lastPush());

        Files.writeString(editorconfig, "root = true\n");
        com.editora.editorconfig.EditorConfig.clearCache();
        manager.projectStyleChanged(editorconfig);
        assertTrue(lastPush().contains("starThreshold=99"), "a key removed is jdtls's default again: " + lastPush());

        int now = fakes.get(0).configurations.size();
        manager.projectStyleChanged(f);
        assertEquals(now, fakes.get(0).configurations.size(), "a .java save pushes nothing");
    }

    @Test
    void aProjectsOwnProblemsGetThroughWhenOnlyOpenFilesAreShown() throws Exception {
        List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        LspManager m =
                new LspManager((file, list) -> delivered.add(file.getFileName().toString()), (t, msg) -> {});
        try {
            LspTestHooks.useFakeSessions(m);
            m.configure(true, Map.of("java", "jdtls"));
            m.setOpenDocumentDiagnosticsOnly(true);
            Path f = root.resolve("A.java");
            Files.writeString(f, "class A {}\n");
            m.openDocument(f, root, "java", "class A {}\n");
            var d = new org.eclipse.lsp4j.Diagnostic(
                    new org.eclipse.lsp4j.Range(
                            new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(0, 1)),
                    "Build path specifies execution environment JavaSE-1.8.");
            LspTestHooks.publishDiagnostics(
                    m,
                    f,
                    new org.eclipse.lsp4j.PublishDiagnosticsParams(root.toUri().toString(), List.of(d)));
            LspTestHooks.publishDiagnostics(
                    m,
                    f,
                    new org.eclipse.lsp4j.PublishDiagnosticsParams(
                            root.resolve("Closed.java").toUri().toString(), List.of(d)));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (delivered.isEmpty() && System.nanoTime() < deadline) {
                var latch = new java.util.concurrent.CountDownLatch(1);
                javafx.application.Platform.runLater(latch::countDown);
                latch.await();
            }
            assertEquals(List.of(root.getFileName().toString()), delivered, "the folder's, not a closed file's (C19)");
            assertTrue(LspManager.projectLevel(root.resolve("pom.xml")), "a build file's are the project's too");
            assertTrue(LspManager.projectLevel(root));
            assertFalse(LspManager.projectLevel(f));
        } finally {
            m.close();
        }
    }
}
