package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end guard for the background disk-preparation path used by expensive initial file opens. */
@Tag("fx")
class InitialFileLoadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void expensiveOpenEventuallyInstallsTheCompleteDocument() throws Exception {
        Path dir = Files.createTempDirectory("editora-async-open");
        Path file = dir.resolve("large.txt");
        String content = "0123456789abcdef".repeat(20_000); // above the asynchronous-load threshold
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            assertTrue(
                    (Boolean) FxTestSupport.call(fx.controller, "shouldLoadAsync", new Class<?>[] {Path.class}, file));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(fx.controller, "openPath", new Class<?>[] {Path.class}, file));

            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
            assertNotNull(buffer, "the tab shell should be visible immediately");
            assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
            assertEquals(content, FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(!FxTestSupport.callOnFx(buffer::isDirty), "a freshly loaded document must stay clean");
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
