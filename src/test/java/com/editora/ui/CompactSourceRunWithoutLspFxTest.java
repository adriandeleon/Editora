package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("fx")
class CompactSourceRunWithoutLspFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void compactFileIsRunnableInSimpleMode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "void main() { IO.println(\"Hello\"); }\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, true, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            boolean runnable = false;
            for (int i = 0; i < 100; i++) {
                runnable = FxTestSupport.callOnFx(() -> {
                    EditorBuffer buffer =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    return buffer != null && buffer.isRunnable();
                });
                if (runnable) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(runnable, "compact-file Run should appear with LSP disabled by Simple mode");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void selectedJdkRunsCompactFileEvenWhenSystemJavaDiffers(@TempDir Path dir) throws Exception {
        assumeTrue(Runtime.version().feature() >= 25, "compact source needs JDK 25+");
        Path file = dir.resolve("Selected.java");
        Files.writeString(file, "void main() { IO.println(\"SELECTED_JDK=\" + Runtime.version().feature()); }\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, true, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setMavenJdkHome(System.getProperty("java.home")));
            boolean ready = false;
            for (int i = 0; i < 100; i++) {
                ready = FxTestSupport.callOnFx(() -> {
                    EditorBuffer buffer =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    return buffer != null && buffer.isRunnable();
                });
                if (ready) break;
                Thread.sleep(20);
            }
            assertTrue(ready);
            FxTestSupport.runOnFx(() -> {
                CommandRegistry commands = FxTestSupport.field(fx.controller, "registry");
                commands.run("file.run");
            });
            RunCoordinator run = FxTestSupport.field(fx.controller, "runCoordinator");
            String expected = "SELECTED_JDK=" + Runtime.version().feature();
            boolean printed = false;
            for (int i = 0; i < 200; i++) {
                printed = FxTestSupport.callOnFx(() -> {
                    CodeArea output = FxTestSupport.field(run.panel(), "output");
                    return output.getText().contains(expected);
                });
                if (printed) break;
                Thread.sleep(50);
            }
            assertTrue(printed, "selected JDK should compile and run the compact file");
            List<String> command = FxTestSupport.field(run, "lastRunCommand");
            assertEquals(
                    Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                            .toString(),
                    command.getFirst());
        } finally {
            fx.dispose();
        }
    }

    @Test
    void shebangReportsWhenSourceReleaseExceedsSelectedJdk(@TempDir Path dir) throws Exception {
        assumeTrue(Runtime.version().feature() >= 25);
        int requested = Runtime.version().feature() + 1;
        Path file = dir.resolve("launcher");
        Files.writeString(file, "#!/usr/bin/env -S java --source " + requested + "\nvoid main() {}\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, true, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setMavenJdkHome(System.getProperty("java.home")));
            boolean ready = false;
            for (int i = 0; i < 100; i++) {
                ready = FxTestSupport.callOnFx(() -> {
                    EditorBuffer buffer =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    return buffer != null && buffer.isRunnable();
                });
                if (ready) break;
                Thread.sleep(20);
            }
            assertTrue(ready);
            FxTestSupport.runOnFx(() -> {
                CommandRegistry commands = FxTestSupport.field(fx.controller, "registry");
                commands.run("file.run");
            });
            String expected = Messages.tr(
                    "status.run.needJdkVersion", requested, Runtime.version().feature());
            boolean explained = false;
            for (int i = 0; i < 100; i++) {
                explained = FxTestSupport.callOnFx(() -> {
                    StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
                    Label echo = FxTestSupport.field(status, "echo");
                    return expected.equals(echo.getText());
                });
                if (explained) break;
                Thread.sleep(20);
            }
            assertTrue(explained, "the selected JDK is too old for the shebang's --source release");
        } finally {
            fx.dispose();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
