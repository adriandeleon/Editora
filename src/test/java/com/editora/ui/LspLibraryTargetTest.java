package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A definition outside the server's root and the project is a library file (the stdlib, site-packages,
 * Pyright's bundled stubs): it stays on the server it was reached from and opens in View mode (Python review
 * B4). Before, it got a server of its own, rooted at the library folder.
 */
class LspLibraryTargetTest {

    @Test
    void aTargetOutsideTheServerRootAndTheProjectIsALibraryFile() {
        Path root = Path.of("/home/u/proj");
        assertTrue(LspCoordinator.isLibraryTarget(Path.of("/usr/lib/python3.13/posixpath.py"), root, null));
        assertTrue(LspCoordinator.isLibraryTarget(
                Path.of("/home/u/.nvm/lib/node_modules/pyright/dist/typeshed-fallback/stdlib/builtins.pyi"),
                root,
                root));
        assertFalse(LspCoordinator.isLibraryTarget(Path.of("/home/u/proj/pkg/util.py"), root, null));
        assertFalse(
                LspCoordinator.isLibraryTarget(Path.of("/home/u/proj/other/x.py"), Path.of("/home/u/proj/svc"), root),
                "another folder of the same project is not a library");
    }
}
