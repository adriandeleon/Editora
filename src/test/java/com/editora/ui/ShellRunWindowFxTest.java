package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Running a shell script in a real window, with a new user's settings. */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the scripts are POSIX shell
class ShellRunWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.onSpinWait();
        }
    }

    private static String console(MainController c) {
        CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
        return output.getText();
    }

    private static DialogPane showingDialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private static EditorBuffer open(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        await(
                "the file to open",
                () -> c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        FxTestSupport.runOnFx(b::settleRunScan);
        return b;
    }

    @Test
    void aNewUserCanRunAShellScript() throws Exception {
        // Defaults: LSP off. Shell Run used to need LSP *and* the Bash server toggle, so a .sh had no ▶, no
        // Run File item, and Run said "Not a runnable file" while a .py beside it ran.
        Path script = dir.resolve("hello.sh");
        Files.writeString(script, "#!/bin/sh\necho \"hello from sh\"\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            assertFalse(fx.shared.getSettings().isLspSupport(), "a new user's settings");
            EditorBuffer b = open(c, script);
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "the gutter ▶ and Run File are there");

            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the script's output", () -> console(c).contains("hello from sh\n"));
        }
    }

    @Test
    void aCrlfScriptIsConvertedBeforeItRunsWhenTheUserAgrees() throws Exception {
        Path script = dir.resolve("crlf.sh");
        Files.writeString(script, "#!/bin/sh\r\nif true; then\r\n  echo one\r\nfi\r\necho two\r\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            EditorBuffer b = open(c, script);
            assertEquals("CRLF", FxTestSupport.callOnFx(b::getLineEnding));
            CommandRegistry registry = FxTestSupport.field(c, "registry");

            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the line-ending warning", () -> showingDialog() != null);
            DialogPane pane = FxTestSupport.callOnFx(ShellRunWindowFxTest::showingDialog);
            assertNotNull(pane);
            assertTrue(FxTestSupport.callOnFx(pane::getContentText).contains("CRLF"));
            assertFalse(FxTestSupport.callOnFx(c.runCoordinator::isRunning), "nothing runs before the answer");

            Button convert = FxTestSupport.callOnFx(() -> pane.getButtonTypes().stream()
                    .filter(t -> t.getText().equals(tr("run.shellWarning.fix")))
                    .map(t -> (Button) pane.lookupButton(t))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.runOnFx(convert::fire);
            await("the converted script's output", () -> console(c).contains("one\ntwo\n"));
            assertFalse(Files.readString(script).contains("\r"), "saved with LF before it ran");
        }
    }

    @Test
    void cancellingTheWarningRunsNothing() throws Exception {
        Path script = dir.resolve("crlf.sh");
        Files.writeString(script, "echo one\r\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            open(c, script);
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the line-ending warning", () -> showingDialog() != null);
            DialogPane pane = FxTestSupport.callOnFx(ShellRunWindowFxTest::showingDialog);
            FxTestSupport.runOnFx(() -> ((Button) pane.lookupButton(ButtonType.CANCEL)).fire());
            FxTestSupport.drainFx();
            assertFalse(FxTestSupport.callOnFx(c.runCoordinator::consoleInUse));
            assertTrue(Files.readString(script).contains("\r\n"), "the file is untouched");
        }
    }

    @Test
    void aRemoteShellScriptIsRunnableAsSoonAsItOpens() throws Exception {
        // The buffer gate was `lspEnabled() && local && …`, while the settings-apply gate ignored `local`: a
        // remote script had no ▶ when opened, then gained one after any settings change.
        try (AsyncTestScope async = new AsyncTestScope()) {
            com.editora.vfs.EmbeddedSftpFixture sftp = async.own(com.editora.vfs.EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("deploy.sh"), "#!/bin/sh\necho deployed\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                workflows.openPath(sftp.remotePath("deploy.sh"));
                EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
                workflows.afterBufferLoad(opening, loaded::countDown);
                return opening;
            });
            async.await(loaded, "document load");
            async.awaitFx();
            FxTestSupport.runOnFx(b::settleRunScan);
            assertTrue(FxTestSupport.callOnFx(b::isShell));
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "a remote script runs on its host, like a remote .py");
        }
    }

    private static String echo(MainController c) throws Exception {
        return FxTestSupport.callOnFx(
                () -> ((javafx.scene.control.Label) FxTestSupport.field(c.statusBar, "echo")).getText());
    }

    private static FxWindowFixture debugWindow() throws Exception {
        return FxWindowFixture.create(
                Files.createTempDirectory("shell-debug-cfg"),
                shared -> shared.getSettings().setDebugSupport(true));
    }

    @Test
    void debuggingAShellScriptPointsAtRunWithTrace() throws Exception {
        // It said "Debugging is unavailable for this file (check Settings → Debugging)" — no setting helps.
        Path script = dir.resolve("deploy.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        try (FxWindowFixture fx = debugWindow()) {
            MainController c = fx.controller;
            open(c, script);
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            FxTestSupport.drainFx();
            assertEquals(tr("status.debug.shellNotDebuggable"), echo(c));

            FxTestSupport.runOnFx(() -> c.debugCoordinator.debugConfig(
                    new com.editora.config.RunConfiguration("D", "shell", "deploy.sh", "", "", "", "", "", "", "")));
            assertEquals(tr("status.debug.shellNotDebuggable"), echo(c), "and for a shell configuration");
            FxTestSupport.runOnFx(() -> c.debugCoordinator.debugConfig(
                    new com.editora.config.RunConfiguration("P", "python", "tool.py", "", "", "", "", "", "", "")));
            assertEquals(
                    tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type.python")),
                    echo(c),
                    "the type's name, not its id");
        }
    }

    @Test
    void debuggingAnUntitledTextBufferDoesNotAskToSaveItFirst() throws Exception {
        try (FxWindowFixture fx = debugWindow()) {
            MainController c = fx.controller;
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.new"));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            FxTestSupport.drainFx();
            OverlayHost overlay = FxTestSupport.field(c, "overlayHost");
            assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "no Save As prompt for a file nothing can debug");
            assertEquals(tr("status.debug.languageNotDebuggable"), echo(c));
        }
    }

    @Test
    void aSourcedStartupFileHasNoRun() throws Exception {
        Path xinitrc = dir.resolve(".xinitrc");
        Files.writeString(xinitrc, "exec i3\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            EditorBuffer b = open(fx.controller, xinitrc);
            assertTrue(FxTestSupport.callOnFx(b::isShell));
            assertFalse(FxTestSupport.callOnFx(b::isRunnable), "one stray click on its ▶ started a window manager");
        }
    }

    @Test
    void saveAsRunConfigurationSavesAShellScriptConfiguration() throws Exception {
        Path script = dir.resolve("deploy.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            open(c, script);
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(
                    () -> registry.run("run.saveConfig")); // said "Open a Java file in the project first."
            OverlayHost overlay = FxTestSupport.field(c, "overlayHost");
            await("the name prompt", overlay::isShowing);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.layout.StackPane root = FxTestSupport.field(overlay, "overlayRoot");
                javafx.scene.Node card = root.getChildren().get(1);
                assertEquals("deploy.sh", ((javafx.scene.control.TextField) card.lookup(".text-field")).getText());
                card.lookupAll(".button").stream()
                        .filter(n ->
                                n instanceof Button button && tr("dialog.ok").equals(button.getText()))
                        .map(n -> (Button) n)
                        .findFirst()
                        .orElseThrow()
                        .fire();
            });
            java.util.List<com.editora.config.RunConfiguration> saved =
                    FxTestSupport.callOnFx(() -> c.config.getWorkspaceState().getRunConfigurations());
            assertEquals(1, saved.size());
            assertEquals("shell", saved.get(0).type());
            assertEquals(script.toString(), saved.get(0).target(), "no project: the absolute path");
        }
    }
}
