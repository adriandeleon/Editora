package com.editora.maven;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenLifecycleTest {

    @Test
    void phasesAreInMavenDefaultOrder() {
        assertEquals(
                List.of("clean", "validate", "compile", "test", "package", "verify", "install", "site", "deploy"),
                MavenLifecycle.PHASES);
    }
}
