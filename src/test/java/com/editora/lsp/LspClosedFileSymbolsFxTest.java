package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's outline of a file with no tab (explore round, position 10) asks a server that already runs and
 * never starts one: with no session for the file's language, there is nothing to ask and nothing is started.
 */
@Tag("fx")
class LspClosedFileSymbolsFxTest {

    @TempDir
    Path temp;

    private LspManager manager;

    @BeforeAll
    static void boot() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void close() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void withNoRunningServerAClosedFileIsNotOutlinedAndNoServerStarts() throws Exception {
        Path file = Files.writeString(temp.resolve("Demo.java"), "class Demo {}\n");
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.configure(true, java.util.Map.of());
        assertFalse(manager.outlinesClosedFile(file), "no session: no outline");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<SymbolNode>> got = new AtomicReference<>(List.of());
        manager.closedFileSymbols(file, "class Demo {}\n", symbols -> {
            got.set(symbols);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(got.get(), "nothing to say");
        assertFalse(manager.hasSession(LspServerRegistry.JAVA_SERVER_ID, temp), "and no server was started");
        assertFalse(manager.isManaged(file), "nor the file opened");
    }
}
