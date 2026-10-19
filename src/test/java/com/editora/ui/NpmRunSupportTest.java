package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpmRunSupportTest {

    @Test
    void colourUnlessNoColor() {
        assertEquals(Map.of("FORCE_COLOR", "1"), NpmRunSupport.environment(null));
        assertEquals(Map.of("FORCE_COLOR", "1"), NpmRunSupport.environment(""));
        assertEquals(Map.of(), NpmRunSupport.environment("1"));
    }

    @Test
    void dependenciesAreMissingOnlyWhenDeclaredAndNoNodeModulesUpToTheProject(@TempDir Path root) throws Exception {
        Path pkg = Files.createDirectories(root.resolve("packages/a"));
        Files.writeString(pkg.resolve("package.json"), "{\"devDependencies\":{\"typescript\":\"^6\"}}");
        assertTrue(NpmRunSupport.dependenciesMissing(pkg, root));
        Files.createDirectory(root.resolve("node_modules")); // hoisted to the workspace root
        assertFalse(NpmRunSupport.dependenciesMissing(pkg, root));
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"no-deps\"}");
        Files.delete(root.resolve("node_modules"));
        assertFalse(NpmRunSupport.dependenciesMissing(pkg, root), "nothing to install");
    }
}
