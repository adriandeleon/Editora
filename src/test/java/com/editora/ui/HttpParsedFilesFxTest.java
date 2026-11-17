package com.editora.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1): an HTTP run command never parses the {@code .http} file on the FX thread. The parse is made
 * off it and kept per document version; a second command while it is out is refused; an edit is parsed again.
 */
@Tag("fx")
class HttpParsedFilesFxTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void stop() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void theFileIsParsedOffTheFxThreadOncePerVersionAndASecondPressMeanwhileIsRefused() throws Exception {
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(site + (Platform.isFxApplicationThread() ? "@fx" : "@off")));
        HttpParsedFiles files = new HttpParsedFiles();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("GET https://a.example/one\n\n###\n\nPOST https://a.example/two\n");
            return b;
        });

        CompletableFuture<HttpParsedFiles.Parsed> first = new CompletableFuture<>();
        boolean[] second = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            assertTrue(files.withParsed(buffer, first::complete));
            second[0] = files.withParsed(buffer, p -> {
                throw new AssertionError("a second press while the parse is out runs nothing");
            });
            assertTrue(files.busy(buffer));
        });
        assertFalse(second[0], "refused while busy");
        HttpParsedFiles.Parsed parsed = first.get(20, TimeUnit.SECONDS);
        assertEquals(2, parsed.requests().size());
        assertEquals("POST", parsed.parsed().get(1).method());
        assertEquals(1, FxTestSupport.callOnFx(() -> parsed.indexAt(4)));

        // The same version answers from memory, at once, with no parse anywhere.
        heard.clear();
        HttpParsedFiles.Parsed[] again = new HttpParsedFiles.Parsed[1];
        FxTestSupport.runOnFx(() -> files.withParsed(buffer, p -> again[0] = p));
        assertTrue(again[0] == parsed, "kept for the version");
        assertEquals(List.of(), heard);

        // An edit: parsed again, off the FX thread.
        FxTestSupport.runOnFx(() -> buffer.getArea().appendText("\n###\nGET https://a.example/three\n"));
        CompletableFuture<HttpParsedFiles.Parsed> third = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> files.withParsed(buffer, third::complete));
        assertEquals(3, third.get(20, TimeUnit.SECONDS).requests().size());
        assertTrue(heard.contains("HttpParsedFiles.parse@off"), String.valueOf(heard));
        assertFalse(heard.stream().anyMatch(h -> h.endsWith("@fx")), String.valueOf(heard));
    }
}
