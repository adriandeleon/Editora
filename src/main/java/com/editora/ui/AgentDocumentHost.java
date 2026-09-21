package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentDocuments;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.mcp.McpBridge;

import static com.editora.i18n.Messages.tr;

/** Editor workflow adapter; MainController supplies composition, this class owns asynchronous loading. */
final class AgentDocumentHost implements WindowAgentDocuments.Host {
    private final CoordinatorHost host;
    private final FileWorkflowCoordinator files;
    private final Consumer<EditorBuffer> add;
    private final McpBridge bridge;
    private final DiffCoordinator diffs;
    private final com.editora.lsp.LspManager lsp;
    private final Consumer<EditorBuffer> ensureLsp;

    AgentDocumentHost(
            CoordinatorHost host,
            FileWorkflowCoordinator files,
            Consumer<EditorBuffer> add,
            McpBridge bridge,
            DiffCoordinator diffs) {
        this(host, files, add, bridge, diffs, null, buffer -> {});
    }

    AgentDocumentHost(
            CoordinatorHost host,
            FileWorkflowCoordinator files,
            Consumer<EditorBuffer> add,
            McpBridge bridge,
            DiffCoordinator diffs,
            com.editora.lsp.LspManager lsp,
            Consumer<EditorBuffer> ensureLsp) {
        this.host = host;
        this.files = files;
        this.add = add;
        this.bridge = bridge;
        this.diffs = diffs;
        this.lsp = lsp;
        this.ensureLsp = ensureLsp;
    }

    @Override
    public com.editora.lsp.LspManager lsp() {
        return lsp;
    }

    AgentDocumentHost withLsp(com.editora.lsp.LspManager manager, Consumer<EditorBuffer> ensure) {
        return new AgentDocumentHost(host, files, add, bridge, diffs, manager, ensure);
    }

    @Override
    public void ensureLsp(EditorBuffer buffer) {
        ensureLsp.accept(buffer);
    }

    @Override
    public Path configDirectory() {
        return files.agentConfigDirectory();
    }

    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode editorContext() {
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var buffer = host.activeBuffer();
        if (buffer != null && buffer.getPath() != null && host.isLocalBuffer(buffer)) {
            int offset = buffer.narrowStart() + buffer.getArea().getCaretPosition();
            String full = buffer.getContent();
            int line = 0;
            int start = 0;
            for (int i = 0; i < offset; i++)
                if (full.charAt(i) == '\n') {
                    line++;
                    start = i + 1;
                }
            var active = out.putObject("active")
                    .put("path", buffer.getPath().toAbsolutePath().normalize().toString())
                    .put("language", buffer.getLanguage())
                    .put("line", line)
                    .put("character", offset - start)
                    .put("narrowed", buffer.isNarrowed())
                    .put(
                            "selection",
                            com.editora.agent.runtime.AgentContext.bounded(
                                    buffer.getArea().getSelectedText(), 2000))
                    .put("dirty", buffer.isDirty());
            active.put("documentVersion", buffer.docVersion());
            active.put(
                    "errors",
                    bridge.getDiagnostics(buffer.getPath().toString()).stream()
                            .filter(d -> "ERROR".equalsIgnoreCase(d.severity()))
                            .count());
            active.put(
                    "diagnosticFreshness",
                    lsp == null
                            ? "UNAVAILABLE"
                            : lsp.agentDiagnostics(buffer.getPath(), full).freshness());
        }
        return out;
    }

    @Override
    public EditorBuffer find(Path path) {
        // The workspace resolver has already checked/canonicalized targets off FX; no filesystem IO here.
        return buffers().stream()
                .filter(buffer -> buffer.getPath() != null && PathKeys.sameNormalized(buffer.getPath(), path))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<EditorBuffer> buffers() {
        List<EditorBuffer> result = new ArrayList<>();
        host.forEachBuffer(result::add);
        return List.copyOf(result);
    }

    @Override
    public void open(Path path, AgentCancellation cancellation, CompletableFuture<EditorBuffer> result) {
        files.fileLoadExecutor.execute(() -> {
            try {
                cancellation.check();
                var load = files.prepareLoad(path, true);
                Platform.runLater(() -> {
                    if (result.isDone() || cancellation.isCancelled()) {
                        return;
                    }
                    try {
                        if (load.binary() || load.truncated()) {
                            result.complete(null);
                            return;
                        }
                        EditorBuffer existing = find(path);
                        if (existing != null) {
                            result.complete(existing);
                            return;
                        }
                        EditorBuffer buffer = new EditorBuffer();
                        buffer.setPath(path);
                        files.applyPreparedLoad(buffer, load);
                        add.accept(buffer);
                        result.complete(buffer);
                    } catch (Exception failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    @Override
    public EditorBuffer create(Path path) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(path);
        buffer.setContent("");
        buffer.markClean();
        add.accept(buffer);
        return buffer;
    }

    @Override
    public CompletableFuture<Boolean> save(EditorBuffer buffer, AgentCancellation cancellation) {
        return files.saveForAgent(buffer, cancellation);
    }

    @Override
    public byte[] saveBytes(EditorBuffer buffer) {
        return files.saveBytes(buffer);
    }

    @Override
    public AgentDocuments.Diagnostics diagnostics(Path path) {
        var entries = bridge.getDiagnostics(path.toString());
        boolean available = host.isLspManaged(path);
        int errors = (int) entries.stream()
                .filter(d -> "ERROR".equalsIgnoreCase(d.severity()))
                .count();
        String text = entries.stream()
                .map(d -> d.line() + ": " + d.severity() + " " + d.message())
                .collect(java.util.stream.Collectors.joining("\n"));
        var buffer = find(path);
        var evidence = lsp == null ? null : lsp.agentDiagnostics(path, buffer == null ? null : buffer.getContent());
        String freshness = evidence == null ? (available ? "UNKNOWN" : "UNAVAILABLE") : evidence.freshness();
        return new AgentDocuments.Diagnostics(
                available,
                errors,
                freshness + " diagnostics:\n" + text,
                freshness,
                evidence == null ? 0 : evidence.generation(),
                evidence == null ? null : evidence.version());
    }

    @Override
    public void showDiff(Path path, String before, String after) {
        String name = path.getFileName().toString();
        diffs.openDiff(
                tr("agent.diffTitle", name),
                tr("agent.before"),
                tr("agent.after"),
                name,
                name,
                cb -> cb.accept(DiffCoordinator.DiffContent.text(before)),
                cb -> cb.accept(DiffCoordinator.DiffContent.text(after)),
                DiffViewerPane.EditableSide.NONE,
                null);
    }
}
