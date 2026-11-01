package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema reloaded from disk is saved as far as lemminx can tell, so the files using it are revalidated: lemminx
 * does that only on {@code didSave}, and a reload sent only {@code didChange} (XML/pom review 4, B31).
 */
@Tag("fx")
class XmlReloadRevalidatesFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(manager::close);
    }

    @Test
    void aReloadedSchemaIsSentAndThenSaved() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("xml", "lemminx"));
        String before = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>\n";
        String after = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><!-- urgent --></xs:schema>\n";
        Path xsd = Files.writeString(root.resolve("note.xsd"), after);
        manager.openDocument(xsd, root, "xml", before);
        LspCoordinator coordinator =
                FxTestSupport.callOnFx(() -> new LspCoordinator(new CoordinatorHostStub(), manager, new LspOpsStub()));
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(xsd);
            b.setContent(after); // what the reload put in the tab
            return b;
        });

        FxTestSupport.runOnFx(() -> coordinator.reloadedFromDisk(buffer));

        FakeLanguageServer fake = fakes.get(0);
        long until = System.currentTimeMillis() + 5000;
        while (fake.saved.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertEquals(1, fake.saved.size(), "the reloaded schema is saved");
        assertTrue(
                fake.changed.stream()
                        .anyMatch(c -> c.getContentChanges().stream()
                                .anyMatch(e -> e.getText().contains("urgent"))),
                "after its reloaded text");
    }
}
