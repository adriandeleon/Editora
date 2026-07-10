package com.editora.build;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of parsing Cargo.toml into the package name + explicit [[bin]]/[[example]] target names. */
class CargoProjectTest {

    @Test
    void extractsPackageNameAndBinAndExampleTargets() {
        String toml = """
                [package]
                name = "my-crate"
                version = "0.1.0"

                [[bin]]
                name = "cli"
                path = "src/cli.rs"

                [[bin]]
                name = "daemon"

                [[example]]
                name = "demo"
                """;
        CargoProject p = CargoProject.parse(toml);
        assertEquals("my-crate", p.packageName());
        assertEquals(List.of("cli", "daemon"), p.binNames());
        assertEquals(List.of("demo"), p.exampleNames());
    }

    @Test
    void aPlainPackageHasNoExplicitTargets() {
        CargoProject p = CargoProject.parse("[package]\nname = \"solo\"\nversion = \"1.0.0\"\n");
        assertEquals("solo", p.packageName());
        assertTrue(p.binNames().isEmpty());
        assertTrue(p.exampleNames().isEmpty());
    }

    @Test
    void aVirtualWorkspaceManifestHasNoPackageName() {
        String toml = """
                [workspace]
                members = ["crate-a", "crate-b"]
                """;
        CargoProject p = CargoProject.parse(toml);
        assertNull(p.packageName(), "a virtual workspace has no [package] name");
        assertTrue(p.binNames().isEmpty());
    }

    @Test
    void malformedTomlThrows() {
        assertThrows(IllegalArgumentException.class, () -> CargoProject.parse("[package\nname ="));
    }
}
