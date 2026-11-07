package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.structured.StructuredParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JSON Lines file in a real window: it opens as its own language with the structured preview, a line that is
 * not one JSON value is squiggled, and fixing it takes the mark back.
 */
@Tag("fx")
class JsonLinesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aBadLineIsMarkedUntilItIsFixed(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("events.ndjson"), "{\"id\":1}\n{\"id\":2,}\n{\"id\":3}\n");
        try (WindowRig w = new WindowRig()) {
            EditorBuffer b = w.open(file);
            FxTestSupport.runOnFx(() -> {
                assertEquals("jsonl", b.getLanguage());
                assertSame(StructuredParser.Format.JSONL, b.structuredFormat());
            });
            w.await("the bad line's mark", () -> b.httpVariableDiagnostics().size() == 1);
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        List.of(2),
                        b.httpVariableDiagnostics().stream().map(d -> d.line()).toList());
                String text = b.getArea().getText();
                int comma = text.indexOf(",}");
                b.getArea().replaceText(comma, comma + 1, "");
            });
            w.await("the mark is taken back", () -> b.httpVariableDiagnostics().isEmpty());
            FxTestSupport.runOnFx(() -> assertTrue(b.isStructured()));
        }
    }
}
