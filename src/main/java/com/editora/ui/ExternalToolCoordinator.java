package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.externaltool.ExternalTool;
import com.editora.externaltool.ExternalToolService;
import com.editora.externaltool.ToolContext;
import com.editora.externaltool.ToolInvocation;
import com.editora.process.ProcessRunner;
import com.editora.run.StackTraceLinks;

import static com.editora.i18n.Messages.tr;

/**
 * The External Tools feature (user-defined CLI commands run on the active file/buffer), extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link ExternalToolService} +
 * the console {@link ExternalToolPanel} + the run/output logic + the {@code externalTool.*} commands; the
 * {@link ToolWindow} itself stays in {@code MainController} (built with {@link #panel()} as content), like
 * the other coordinators. Reaches the window through the shared host plus a small {@link Ops} extension.
 */
final class ExternalToolCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that this feature needs. */
    interface Ops {
        /** The active window's project root, or {@code null} when no project is open. */
        Path projectRoot();

        /** Opens (and focuses) the External Tools console tool window. */
        void openConsole();

        /** Handles a clicked file path in the console output (jump to it). */
        void onOutputLink(StackTraceLinks.Link link);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final ExternalToolService service = new ExternalToolService();
    private final ExternalToolPanel panel = new ExternalToolPanel();
    private CommandRegistry registry;
    /** The last external tool run, for {@code externalTool.rerunLast}. */
    private ExternalTool lastTool;

    ExternalToolCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        panel.setOnLink(ops::onOutputLink); // double-clicked path in output → jump
    }

    /** The console panel — used as the External Tools tool window's content (owned by {@code MainController}). */
    ExternalToolPanel panel() {
        return panel;
    }

    /** External Tools are disabled in Simple UI mode (mirrors the other heavy features). */
    boolean isEnabled() {
        return !host.simpleModeActive();
    }

    /** Registers the static + per-tool dynamic commands; call once from {@code MainController.registerCommands}. */
    void registerCommands(CommandRegistry registry) {
        this.registry = registry;
        registry.register(Command.of("externalTool.run", this::runPicker));
        registry.register(Command.of("externalTool.clearOutput", panel::clearConsole));
        registry.register(Command.of("externalTool.rerunLast", this::rerunLast));
        registerToolCommands();
    }

    /** Registers one {@code externalTool.run.<slug>} command per enabled tool (palette- and key-bindable). */
    private void registerToolCommands() {
        for (ExternalTool t : enabledTools()) {
            ExternalTool tool = t;
            registry.register(Command.of(ExternalTool.commandIdFor(t.getName()), t.getName(), () -> run(tool)));
        }
    }

    /** Drops stale {@code externalTool.run.*} commands and re-registers the current set (this window). */
    void refreshCommands() {
        if (registry == null) {
            return;
        }
        List<String> stale = new ArrayList<>();
        for (Command c : registry.all()) {
            if (c.id().startsWith("externalTool.run.")) {
                stale.add(c.id());
            }
        }
        stale.forEach(registry::remove);
        registerToolCommands();
    }

    /** Right-click "External Tools" submenu for the editor context menu: one item per enabled tool, each
     *  running it on the active buffer. Empty when the feature is off (Simple UI) or no tool is enabled —
     *  so the menu shows nothing (mirrors the plugin contributor). */
    List<MenuItem> editorMenuItems() {
        if (!isEnabled()) {
            return List.of();
        }
        List<ExternalTool> tools = enabledTools();
        if (tools.isEmpty()) {
            return List.of();
        }
        Menu submenu = new Menu(tr("editmenu.externalTools"));
        submenu.setGraphic(Icons.tools());
        for (ExternalTool t : tools) {
            ExternalTool tool = t;
            MenuItem item = new MenuItem(t.getName());
            item.setOnAction(e -> run(tool));
            submenu.getItems().add(item);
        }
        return List.of(submenu);
    }

    private List<ExternalTool> enabledTools() {
        List<ExternalTool> out = new ArrayList<>();
        for (ExternalTool t : host.settings().getExternalTools()) {
            if (t.isEnabled() && !t.getName().isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private void runPicker() {
        if (!isEnabled()) {
            return;
        }
        if (enabledTools().isEmpty()) {
            host.setStatus(tr("status.externalTool.none"));
            return;
        }
        QuickOpen<ExternalTool> picker = new QuickOpen<>(
                tr("command.externalTool.run"),
                tr("palette.externalTool.runPrompt"),
                () -> new ArrayList<>(enabledTools()),
                ExternalTool::getName,
                t -> tr("externalTool.output." + t.getOutput().name()),
                this::run);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private void rerunLast() {
        if (lastTool == null) {
            host.setStatus(tr("status.externalTool.noLast"));
            return;
        }
        run(lastTool);
    }

    /** Runs an external tool against the active buffer; applies its output per the tool's {@code OutputTarget}. */
    private void run(ExternalTool tool) {
        if (!isEnabled() || tool == null) {
            return;
        }
        ToolInvocation inv = ToolInvocation.of(tool, buildContext(), defaultDir());
        if (inv.isEmpty()) {
            host.setStatus(tr("status.externalTool.noCommand", tool.getName()));
            return;
        }
        lastTool = tool;
        host.setStatus(tr("status.externalTool.running", tool.getName()));
        service.run(inv, ExternalToolService.DEFAULT_TIMEOUT, r -> applyResult(tool, inv, r));
    }

    /** Captures the macro/stdin context from the active buffer (file fields empty for an unsaved buffer). */
    private ToolContext buildContext() {
        EditorBuffer b = host.activeBuffer();
        Path projectRoot = ops.projectRoot();
        if (b == null) {
            String proj = projectRoot != null ? projectRoot.toString() : "";
            return new ToolContext("", "", "", "", "", 1, 1, proj, "");
        }
        Path path = b.getPath();
        String filePath = "";
        String fileDir = "";
        String fileName = "";
        String baseName = "";
        if (path != null) {
            filePath = path.toAbsolutePath().toString();
            Path parent = path.toAbsolutePath().getParent();
            fileDir = parent == null ? "" : parent.toString();
            fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        var area = b.getArea();
        String projectFileDir = projectRoot != null ? projectRoot.toString() : fileDir;
        return new ToolContext(
                filePath,
                fileDir,
                fileName,
                baseName,
                area.getSelectedText(),
                area.getCurrentParagraph() + 1,
                area.getCaretColumn() + 1,
                projectFileDir,
                b.getContent());
    }

    /** Default working dir for a tool with a blank workingDir: the file's parent, else the project root. */
    private Path defaultDir() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.getPath() != null) {
            Path parent = b.getPath().toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return ops.projectRoot();
    }

    /** Applies a finished tool's result on the FX thread (console / replace selection / buffer / insert). */
    private void applyResult(ExternalTool tool, ToolInvocation inv, ProcessRunner.Result r) {
        if (tool.getOutput() == ExternalTool.OutputTarget.CONSOLE) {
            ops.openConsole();
            panel.show(tool.getName(), inv.displayCommand(), r.out(), r.err(), r.exit());
            host.setStatus(
                    r.ok()
                            ? tr("status.externalTool.done", tool.getName())
                            : tr("status.externalTool.failed", tool.getName(), r.message()));
            return;
        }
        // Text-target modes: apply stdout only on success; otherwise surface the error in the console.
        if (!r.ok() || r.out().isEmpty()) {
            ops.openConsole();
            panel.show(tool.getName(), inv.displayCommand(), r.out(), r.err(), r.exit());
            host.setStatus(tr("status.externalTool.failed", tool.getName(), r.message()));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isEditable()) {
            host.setStatus(tr("status.externalTool.notEditable"));
            return;
        }
        var area = b.getArea();
        switch (tool.getOutput()) {
            case REPLACE_BUFFER -> area.replaceText(r.out());
            case REPLACE_SELECTION -> area.replaceSelection(stripOneTrailingNewline(r.out()));
            case INSERT_AT_CARET -> area.insertText(area.getCaretPosition(), stripOneTrailingNewline(r.out()));
            default -> {}
        }
        host.setStatus(tr("status.externalTool.done", tool.getName()));
    }

    /** CLI tools usually append a trailing newline; drop one for selection/caret inserts (not whole-buffer). */
    private static String stripOneTrailingNewline(String s) {
        if (s.endsWith("\r\n")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("\n") || s.endsWith("\r")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Stops the external-tool worker thread (window close). */
    public void shutdown() {
        service.shutdown();
    }
}
