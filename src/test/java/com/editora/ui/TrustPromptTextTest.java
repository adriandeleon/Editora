package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trust prompt says a folder's own TypeScript as what it is (JS review B110). */
class TrustPromptTextTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void aFolderWithOnlyItsOwnTypescriptGetsABodyAboutThat(@TempDir Path root) throws Exception {
        Path lib = Files.createDirectories(root.resolve("node_modules/typescript/lib"));
        Files.writeString(lib.getParent().resolve("package.json"), "{ \"version\": \"5.4.5\" }");
        String request = "typescript: " + lib.resolve("tsserver.js");
        String body = TrustPromptText.body(root, List.of(request));
        assertTrue(body.startsWith("This folder has its own TypeScript."), body);
        assertFalse(body.contains(".editora/settings.json"), body);
        assertTrue(
                body.contains("TypeScript 5.4.5 in node_modules/typescript, run by the TypeScript / JavaScript server"),
                body);
        assertFalse(body.contains("''"), body);
        assertEquals("Run the folder app's own TypeScript?", TrustPromptText.header("app", List.of(request)));
        assertEquals(
                "Use the settings of the folder app?", TrustPromptText.header("app", List.of("python: x", request)));
    }

    @Test
    void settingsFromTheFileKeepTheSettingsBody(@TempDir Path root) {
        String body = TrustPromptText.body(root, List.of("python: /x/pyright --stdio"));
        assertTrue(body.contains(".editora/settings.json"), body);
        assertTrue(body.contains("python: /x/pyright --stdio"), body);
    }

    @Test
    void anAstroSdkIsNamedForAstro(@TempDir Path root) throws Exception {
        Path lib = Files.createDirectories(root.resolve("node_modules/typescript/lib"));
        assertEquals(
                "TypeScript in node_modules/typescript, loaded by the Astro server",
                TrustPromptText.sdkLine(root, "astro: " + lib));
    }
}
