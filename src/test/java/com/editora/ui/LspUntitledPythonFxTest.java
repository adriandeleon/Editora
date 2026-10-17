package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An untitled buffer set to Python is served by the language server (Python review B14): before, every
 * request was keyed by a file, so it got no completion, errors or hover until saved. It is opened under a
 * made-up {@code Untitled-N.py}; saved, its real file takes over.
 */
@Tag("fx")
class LspUntitledPythonFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @Test
    void anUntitledPythonBufferIsServedAndItsFileTakesOverWhenSaved() throws Exception {
        fx = new LspCoordinatorFixture(root);
        fx.manager.configure(true, Map.of("python", "pyright-langserver --stdio"));
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            fx.coordinator.setServerAvailableForTest("python", true);
            EditorBuffer b = new EditorBuffer();
            b.setContent("zz: int = 's'\n");
            b.setLanguageOverride("python");
            fx.show(b);
            fx.host.active = b;
            fx.coordinator.syncBuffer(b);
            return b;
        });
        fx.settle();

        Path made = FxTestSupport.callOnFx(() -> fx.coordinator.lspPath(buffer));
        assertNotNull(made, "an untitled Python buffer gets a document");
        assertNull(buffer.getPath(), "and still has no file");
        assertEquals("Untitled-1.py", made.getFileName().toString());
        assertTrue(fx.manager.isManaged(made));
        var open = fx.server().opened.getLast().getTextDocument();
        assertEquals("python", open.getLanguageId());
        assertEquals("zz: int = 's'\n", open.getText());
        assertFalse(Files.exists(made), "nothing is written to disk");

        var error = new LspDiagnostic(0, 10, 0, 13, LspDiagnostic.Severity.ERROR, "not assignable", null, "Pyright");
        fx.run(() -> fx.coordinator.onDiagnostics(made, List.of(error)));
        assertEquals(
                List.of(error),
                FxTestSupport.callOnFx(() -> {
                    Object overlay = FxTestSupport.field(buffer, "lspOverlay");
                    return (List<?>) FxTestSupport.call(overlay, "diagnostics", new Class<?>[0]);
                }),
                "the error reaches the buffer");
        assertTrue(fx.coordinator.problems().isEmpty(), "no Problems row: there is no file to open");

        Path file = root.resolve("saved.py");
        Files.writeString(file, "zz: int = 's'\n");
        fx.run(() -> {
            buffer.setPath(file);
            fx.coordinator.notifyDocumentSaved(buffer, "zz: int = 's'\n");
        });
        assertTrue(fx.manager.isManaged(file), "the saved file is served");
        assertFalse(fx.manager.isManaged(made), "the made-up document is closed");
        assertEquals(file, FxTestSupport.callOnFx(() -> fx.coordinator.lspPath(buffer)));
    }
}
