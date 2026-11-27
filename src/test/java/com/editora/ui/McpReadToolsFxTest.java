package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read tools against a real window: the file rule, no server, and a file's Local History after a save. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpReadToolsFxTest {

    @TempDir
    static Path work;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> c) {
        try {
            return FxTestSupport.callOnFx(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EditorBuffer open(String name, String text) throws Exception {
        Path file = work.toRealPath().resolve(name);
        Files.writeString(file, text);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        Await.until(
                () -> {
                    EditorBuffer b = onFx(
                            () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
                    return b != null && file.equals(b.getPath()) && !b.isLoading();
                },
                "the file opened");
        return onFx(() -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    @Test
    void aFileNoServerServesSaysSoAndAClosedFileIsRefused() throws Exception {
        EditorBuffer b = open("plain.txt", "hello\n");
        IllegalStateException none = assertThrows(
                IllegalStateException.class,
                () -> fx.controller.navigate(b.getPath().toString(), 1, 1, false));
        assertEquals("No language server serves this file.", none.getMessage());
        IllegalStateException closed = assertThrows(
                IllegalStateException.class,
                () -> fx.controller.hover(work.resolve("nope.txt").toString(), 1, 1));
        assertTrue(closed.getMessage().startsWith("No open buffer"), closed.getMessage());
    }

    @Test
    void localHistoryListsTheSavesAndReadsARevision() throws Exception {
        EditorBuffer b = open("kept.txt", "one\n");
        String path = b.getPath().toString();
        FxTestSupport.runOnFx(() -> b.getArea().replaceText("two\n"));
        FxTestSupport.runOnFx(() -> fx.controller.registry.run("file.save"));
        Await.until(() -> !fx.controller.localHistory(path).isEmpty(), "a revision was kept");
        var revisions = fx.controller.localHistory(path);
        assertEquals(0, revisions.get(0).index());
        String newest = fx.controller.localHistoryText(path, 0);
        assertTrue(newest.equals("two\n") || newest.equals("one\n"), newest);
        assertThrows(IllegalStateException.class, () -> fx.controller.localHistoryText(path, revisions.size()));
    }
}
