package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A language server whose start fails — here a command that cannot be run; on a remote host, a connection
 * that broke while the server was being started. The tab it was opened for must not stay marked as served,
 * with the loading bar spinning, until something happens to re-sync it; and as with a lost connection,
 * nothing is restarted.
 */
@Tag("fx")
@DisabledOnOs(
        OS.WINDOWS) // a directory as the command is what makes the fork fail; Windows resolves commands its own way
class LspStartFailureFxTest {

    @TempDir
    Path base;

    private LspManager manager;
    private LspCoordinator coordinator;
    private final LspCoordinatorFixture.Host host = new LspCoordinatorFixture.Host();
    private final LspCoordinatorFixture.Ops ops = new LspCoordinatorFixture.Ops();

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
        Path app = Files.createDirectories(base.resolve("app"));
        Files.writeString(app.resolve("main.py"), "print('hi')\n");
        // Executable to the availability probe, but nothing that can be exec'd.
        Path notAServer = Files.createDirectories(base.resolve("not-a-server"));
        host.settings.setPythonLspEnabled(true);
        host.settings.setPythonLspCommand(notAServer.toString());
        ops.projectRoot = app;
        // Server statuses are not routed to the coordinator: the failure itself must stop the loading bar.
        manager = new LspManager((file, diagnostics) -> {}, (type, message) -> {});
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.applySupport();
        });
        // Every probe result re-gates the open tabs, which would retry the start; let them all land first.
        // The probe executor is one thread, so this probe answers after the ones applySupport queued.
        CompletableFuture<Boolean> probed = new CompletableFuture<>();
        manager.detectCommand("python", notAServer.toString(), probed::complete);
        assertTrue(probed.get(20, TimeUnit.SECONDS), "the directory passes the availability probe");
        FxTestSupport.drainFx();
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> host.buffers.forEach(EditorBuffer::dispose));
        manager.close();
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(50);
        }
    }

    private long startsAnnounced() throws Exception {
        String label = FxTestSupport.callOnFx(
                () -> (String) FxTestSupport.call(coordinator, "serverLabel", new Class<?>[] {String.class}, "python"));
        String starting = tr("status.lsp.starting", label);
        return FxTestSupport.callOnFx(
                () -> host.statuses.stream().filter(starting::equals).count());
    }

    /** Opens main.py as the active tab and waits for its server's start to fail. */
    private EditorBuffer openAndFail() throws Exception {
        Path file = base.resolve("app/main.py");
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            buffer.setPath(file);
            buffer.setContent("print('hi')\n");
            host.buffers.add(buffer);
            host.active = buffer;
            coordinator.syncBuffer(buffer);
            // As syncBuffer leaves it, before the failure (posted to this thread) can be heard.
            assertTrue(buffer.isLspActive());
            assertEquals(Boolean.TRUE, ops.loading.get(ops.loading.size() - 1));
        });
        assertEquals(1, startsAnnounced());

        await("the tab went quiet", () -> {
            try {
                return !FxTestSupport.callOnFx(buffer::isLspActive);
            } catch (Exception e) {
                return false;
            }
        });
        return buffer;
    }

    @Test
    void aFailedStartLeavesTheTabQuietAndIsNotRetried() throws Exception {
        EditorBuffer buffer = openAndFail();

        Thread.sleep(500); // a retry, if one were coming, would have started by now
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertFalse(manager.isManaged(buffer.getPath()));
            assertFalse(buffer.isLspActive());
            assertEquals(Boolean.FALSE, ops.loading.get(ops.loading.size() - 1), "the loading bar stopped");
            assertNull(ops.statusBarLabels.get(ops.statusBarLabels.size() - 1), "no server named in the status bar");
        });
        assertEquals(1, startsAnnounced(), "a failed start is not retried");
        String crashed = tr("status.lsp.crashed", tr("install.lang.python"));
        assertFalse(host.statuses.contains(crashed), "a failed start is not reported as a crash: " + host.statuses);
    }

    @Test
    void anotherServersProbeResultDoesNotRetryAFailedStart() throws Exception {
        EditorBuffer buffer = openAndFail();

        // What startup does 22 more times: another server answers its probe.
        FxTestSupport.runOnFx(() -> coordinator.serverDetected("go", true));
        Thread.sleep(500);
        FxTestSupport.drainFx();
        assertEquals(1, startsAnnounced(), "a probe of gopls must not re-open a Python tab");
        assertFalse(FxTestSupport.callOnFx(buffer::isLspActive));

        // Its own server's answer does re-gate it: that is the one that could have changed.
        FxTestSupport.runOnFx(() -> coordinator.serverDetected("python", true));
        assertEquals(2, startsAnnounced());
    }
}
