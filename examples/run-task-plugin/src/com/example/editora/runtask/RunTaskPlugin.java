package com.example.editora.runtask;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A simple task-runner tool window: type a shell command (e.g. {@code npm run build} or {@code make}), run
 * it in the active file's directory via {@code bash -lc} (a login shell, so PATH resolves npm/make), and
 * stream combined stdout/stderr into the output area. Stop kills the process. The command runs off the FX
 * thread; output is appended on it.
 */
public class RunTaskPlugin implements Plugin {

    private volatile Process current;

    @Override
    public void start(PluginContext ctx) {
        TextField command = new TextField("npm run build");
        command.setPromptText("e.g. npm run build  /  make  /  ./gradlew test");
        TextArea output = new TextArea();
        output.setEditable(false);
        output.getStyleClass().add("run-task-output");
        VBox.setVgrow(output, Priority.ALWAYS);

        Button run = new Button("Run");
        Button stop = new Button("Stop");
        stop.setDisable(true);

        run.setOnAction(e -> {
            Path file = ctx.activeEditor().filePath();
            Path dir = file != null && file.toAbsolutePath().getParent() != null
                    ? file.toAbsolutePath().getParent()
                    : Path.of(System.getProperty("user.home"));
            String cmd = command.getText();
            if (cmd == null || cmd.isBlank()) {
                return;
            }
            output.setText("$ " + cmd + "   (in " + dir + ")\n");
            run.setDisable(true);
            stop.setDisable(false);
            new Thread(() -> exec(dir, cmd, output, run, stop), "run-task").start();
        });
        stop.setOnAction(e -> {
            Process p = current;
            if (p != null) {
                p.destroy();
            }
        });

        Label title = new Label("Task Runner");
        title.getStyleClass().add("tool-window-title");
        HBox controls = new HBox(8, command, run, stop);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(command, Priority.ALWAYS);
        VBox box = new VBox(8, title, controls, output);
        box.setPadding(new Insets(8));
        Region content = box;

        ctx.registerToolWindow("runtask", "Task Runner", ToolWindowSide.BOTTOM, content, null);
    }

    private void exec(Path dir, String cmd, TextArea output, Button run, Button stop) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", cmd)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            current = p;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line;
                    Platform.runLater(() -> output.appendText(l + "\n"));
                }
            }
            int code = p.waitFor();
            Platform.runLater(() -> output.appendText("\n[exit " + code + "]\n"));
        } catch (Exception e) {
            Platform.runLater(() -> output.appendText("\n[failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "]\n"));
        } finally {
            current = null;
            Platform.runLater(() -> {
                run.setDisable(false);
                stop.setDisable(true);
            });
        }
    }
}
