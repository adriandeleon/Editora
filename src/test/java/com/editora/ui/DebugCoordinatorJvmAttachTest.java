package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which attach goes to Editora's own JVM adapter: a build's JVM anchored at a Kotlin or Groovy file (java-debug
 * never binds a breakpoint outside a {@code .java} file), never a Java one.
 */
class DebugCoordinatorJvmAttachTest {

    @Test
    void aKotlinOrGroovyAnchorAttachesThroughTheJvmAdapter() {
        assertEquals("kotlin", DebugCoordinator.jvmAdapterLanguage(Path.of("/p/src/main/kotlin/App.kt")));
        assertEquals("kotlin", DebugCoordinator.jvmAdapterLanguage(Path.of("/p/tool.main.kts")));
        assertEquals("groovy", DebugCoordinator.jvmAdapterLanguage(Path.of("/p/src/App.groovy")));
        assertNull(DebugCoordinator.jvmAdapterLanguage(Path.of("/p/src/App.java")), "java-debug, through jdtls");
        assertNull(DebugCoordinator.jvmAdapterLanguage(Path.of("/p/app.py")));
        assertNull(DebugCoordinator.jvmAdapterLanguage(null));
    }
}
