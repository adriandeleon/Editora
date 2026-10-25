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
}
