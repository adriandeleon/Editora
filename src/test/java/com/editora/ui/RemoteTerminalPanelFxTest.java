package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A terminal tab on an SSH host, through the coordinator's own remote shell: it answers what it is typed, its chip
 * names the host, a dropped connection leaves the tab with a Reconnect, and Reconnect finds the folder again on
 * the new connection (the old path belongs to a closed file system).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteTerminalPanelFxTest {

    @TempDir
    Path base;

    private EmbeddedSftpFixture fixture;
    private Path remoteDir;
    private Stage stage;
    private TerminalPanel panel;

    @BeforeAll
    static void boot() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
        Messages.init("en");
    }

    @BeforeEach
    void connect() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        fixture = EmbeddedSftpFixture.startWithShell(base);
        remoteDir = fixture.remotePath("app");
        Files.createDirectories(remoteDir);
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            if (panel != null) panel.shutdown();
            if (stage != null) stage.close();
            return null;
        });
        fixture.close();
        ExecTargets.setRemoteEnabled(null);
    }

    @Test
    void aRemoteTabAnswersSurvivesADropAndReconnectsOnTheNewConnection() throws Exception {
        fx(() -> {
            panel = new TerminalPanel(
                    (dir, shell) -> TerminalCoordinator.remoteShell(dir), (title, programs) -> true, () -> remoteDir);
            panel.setTargetLabel(TerminalCoordinator::targetLabel);
            stage = new Stage();
            stage.setScene(new Scene(panel, 800, 400));
            stage.show();
            panel.newTerminal();
            return null;
        });
        assertEquals(
                TerminalCoordinator.hostOf(Vfs.authorityOf(remoteDir)),
                fx(() -> ((Label) panel.activeTab().getGraphic()).getText()),
                "the chip names the host");

        type("echo $((6 * 7))\n");
        await(() -> screen().contains("42"), "the host's answer");

        fixture.dropConnection();
        await(
                () -> Boolean.TRUE.equals(call(() -> panel.showsReconnect(panel.activeTab()))),
                "the connection-lost banner");
        assertEquals(1, (int) fx(panel::terminalCount));

        fixture.reconnect();
        fx(() -> {
            reconnectButton().fire();
            return null;
        });
        // The host's shell opens off the FX thread; the old text and banner stay until it has.
        await(
                () -> Boolean.FALSE.equals(call(() -> panel.showsReconnect(panel.activeTab()))),
                "a new shell, on the new connection");
        type("echo $((6 * 9))\n");
        await(() -> screen().contains("54"), "the new shell's answer");
    }

    private void type(String text) throws Exception {
        fx(() -> {
            panel.activeView().getSession().sendString(text);
            return null;
        });
    }

    private String screen() {
        return call(() -> {
                    var buffer = panel.activeView().getSession().getTextBuffer();
                    buffer.lock();
                    try {
                        return buffer.getScreenLines();
                    } finally {
                        buffer.unlock();
                    }
                })
                .toString();
    }

    private Button reconnectButton() {
        var content = (StackPane) panel.activeTab().getContent();
        return content.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("terminal-lost-banner"))
                .flatMap(n -> ((HBox) n).getChildren().stream())
                .filter(n -> n instanceof Button)
                .map(n -> (Button) n)
                .findFirst()
                .orElseThrow();
    }

    private static <T> T call(Callable<T> work) {
        try {
            return fx(work);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
