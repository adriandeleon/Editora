package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentDocuments;
import com.editora.agent.runtime.AgentWorkspace;
import com.editora.editor.EditorBuffer;

/** Window-owned document transactions. Disk reads and writes use the editor's existing file workflows. */
final class WindowAgentDocuments implements AgentDocuments {
    interface Host {
        default void ensureLsp(EditorBuffer buffer) {}

        default com.editora.lsp.LspManager lsp() {
            return null;
        }

        default Path configDirectory() {
            return null;
        }

        default com.fasterxml.jackson.databind.node.ObjectNode editorContext() {
            return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        }

        EditorBuffer find(Path path);

        List<EditorBuffer> buffers();

        void open(Path path, AgentCancellation cancellation, CompletableFuture<EditorBuffer> result);

        EditorBuffer create(Path path);

        CompletableFuture<Boolean> save(EditorBuffer buffer, AgentCancellation cancellation);

        Diagnostics diagnostics(Path path);

        byte[] saveBytes(EditorBuffer buffer);

        void showDiff(Path path, String before, String after);
    }

    com.editora.agent.runtime.AgentSemantics semantics() {
        return host.lsp() == null ? null : new WindowAgentSemantics(host, this, workspace);
    }

    com.fasterxml.jackson.databind.JsonNode awareness(AgentCancellation cancellation) throws Exception {
        var out = AgentFx.call(cancellation, host::editorContext);
        String active = out.path("active").path("path").asText();
        if (!active.isBlank() && workspace.allows(Path.of(active)))
            ((com.fasterxml.jackson.databind.node.ObjectNode) out.get("active"))
                    .put("path", workspace.root().relativize(Path.of(active)).toString());
        else out.remove("active");
        var open = out.putArray("open");
        var states = states(cancellation);
        for (var state : states.stream().limit(128).toList())
            open.addObject()
                    .put("path", workspace.root().relativize(state.path()).toString())
                    .put("revision", state.revision())
                    .put("dirty", state.dirty());
        out.put("openTruncated", states.size() > 128);
        out.put("workspace", workspace.root().toString());
        return out;
    }

    private final Host host;
    private final AgentWorkspace workspace;
    // Only read on FX. Weak keys avoid retaining tabs closed during a long agent session.
    private final Map<EditorBuffer, String> identities = new java.util.WeakHashMap<>();

    WindowAgentDocuments(Host host, AgentWorkspace workspace) {
        this.host = host;
        this.workspace = workspace;
    }

    @Override
    public Snapshot read(Path path, AgentCancellation cancellation) throws Exception {
        workspace.resolve(path.toString());
        EditorBuffer existing = AgentFx.call(cancellation, () -> host.find(path));
        if (existing == null) {
            if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("A regular text file is required");
            }
            if (java.nio.file.Files.size(path) > AgentWorkspace.MAX_FILE_CHARS) {
                throw new IOException("File too large for native agent editing");
            }
            CompletableFuture<EditorBuffer> loaded = new CompletableFuture<>();
            AgentFx.call(cancellation, () -> {
                host.open(path, cancellation, loaded);
                return null;
            });
            try (var hook = cancellation.onCancel(() -> loaded.cancel(false))) {
                existing = loaded.get(30, TimeUnit.SECONDS);
            } finally {
                loaded.cancel(false);
            }
            if (existing == null) {
                throw new IOException("File is unavailable, binary, or too large");
            }
        }
        EditorBuffer buffer = existing;
        return AgentFx.call(cancellation, () -> snapshot(buffer, path));
    }

    private Snapshot snapshot(EditorBuffer buffer, Path expectedPath) {
        return snapshot(buffer, expectedPath, true);
    }

    boolean current(Snapshot expected) {
        EditorBuffer buffer = host.find(expected.path());
        return buffer != null
                && snapshot(buffer, expected.path(), false).revision().equals(expected.revision());
    }

    @Override
    public List<Snapshot> open(AgentCancellation cancellation) throws Exception {
        List<State> states = states(cancellation);
        if (states.size() > 128)
            throw new IllegalStateException(
                    "Too many open workspace files for a semantic batch; close unrelated files");
        List<Snapshot> result = new ArrayList<>();
        for (State state : states) {
            result.add(read(state.path(), cancellation));
        }
        return List.copyOf(result);
    }

    @Override
    public List<State> states(AgentCancellation cancellation) throws Exception {
        // Confinement checks may touch the filesystem; never perform them on FX.
        List<Path> paths = AgentFx.call(
                cancellation,
                () -> host.buffers().stream()
                        .map(EditorBuffer::getPath)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        List<Path> allowed = paths.stream().filter(workspace::allows).toList();
        return AgentFx.call(cancellation, () -> {
            List<State> result = new ArrayList<>();
            for (Path path : allowed) {
                EditorBuffer buffer = host.find(path);
                if (buffer != null) {
                    Snapshot snapshot = snapshot(buffer, path.toAbsolutePath().normalize(), false);
                    result.add(new State(snapshot.path(), snapshot.revision(), snapshot.dirty()));
                }
            }
            return List.copyOf(result);
        });
    }

    private Snapshot snapshot(EditorBuffer buffer, Path expectedPath, boolean includeText) {
        if (buffer.isDisposed()
                || buffer.getPath() == null
                || !buffer.getPath().toAbsolutePath().normalize().equals(expectedPath)) {
            throw new IllegalStateException("Document closed or changed path; reread the file");
        }
        String text = includeText ? buffer.getContent() : "";
        if (includeText && text.length() > AgentWorkspace.MAX_FILE_CHARS) {
            throw new IllegalStateException("Document too large");
        }
        return new Snapshot(
                expectedPath,
                identities.computeIfAbsent(buffer, unused -> UUID.randomUUID().toString()) + ":" + buffer.docVersion(),
                text,
                buffer.isDirty());
    }

    @Override
    public List<Snapshot> apply(List<Edit> edits, AgentCancellation cancellation) throws Exception {
        for (Edit edit : edits) {
            workspace.resolve(edit.path().toString());
        }
        return AgentFx.call(cancellation, () -> {
            List<EditorBuffer> buffers = new ArrayList<>();
            List<String> replacements = new ArrayList<>();
            var unique = new HashSet<Path>();
            // Validate EVERY target before the first mutation; no nested FX event loops inside commit.
            for (Edit edit : edits) {
                if (!unique.add(edit.path())) {
                    throw new IllegalArgumentException("Duplicate edit target");
                }
                EditorBuffer buffer = host.find(edit.path());
                if (buffer == null || !buffer.isEditable()) {
                    throw new IllegalStateException("Document closed or read-only");
                }
                Snapshot current = snapshot(buffer, edit.path());
                if (!current.revision().equals(edit.revision())) {
                    throw new IllegalStateException("Stale document revision; reread before editing "
                            + edit.path().getFileName());
                }
                String replacement = AgentDocuments.replacement(current.text(), edit);
                if (replacement.length() > AgentWorkspace.MAX_FILE_CHARS) {
                    throw new IllegalArgumentException("Edit too large");
                }
                buffers.add(buffer);
                replacements.add(replacement);
            }
            cancellation.check();
            // Background tabs deliberately defer LSP startup. An agent mutation must acquire that
            // ownership before sendLspChange; otherwise it silently leaves callers on old server text.
            for (EditorBuffer buffer : buffers) host.ensureLsp(buffer);
            cancellation.check();
            List<Snapshot> changed = new ArrayList<>();
            for (int i = 0; i < buffers.size(); i++) {
                EditorBuffer buffer = buffers.get(i);
                buffer.replaceWholeDocument(replacements.get(i));
                buffer.sendLspChange();
                changed.add(snapshot(buffer, edits.get(i).path()));
            }
            return List.copyOf(changed);
        });
    }

    @Override
    public Snapshot create(Path path, String text, AgentCancellation cancellation) throws Exception {
        workspace.resolve(path.toString());
        if (java.nio.file.Files.exists(path)) {
            throw new IOException("File already exists; read and edit it instead");
        }
        return AgentFx.call(cancellation, () -> {
            if (host.find(path) != null) {
                throw new IllegalStateException("An open document already owns this path");
            }
            EditorBuffer buffer = host.create(path);
            buffer.replaceWholeDocument(text);
            host.ensureLsp(buffer);
            buffer.sendLspChange();
            return snapshot(buffer, path);
        });
    }

    @Override
    public void save(List<Snapshot> snapshots, AgentCancellation cancellation) throws Exception {
        for (Snapshot expected : snapshots) {
            workspace.resolve(expected.path().toString());
            CompletableFuture<Boolean> saved = AgentFx.call(cancellation, () -> {
                EditorBuffer buffer = host.find(expected.path());
                if (buffer == null
                        || !buffer.isEditable()
                        || !snapshot(buffer, expected.path()).revision().equals(expected.revision())) {
                    throw new IllegalStateException("Stale document; reread before saving");
                }
                return host.save(buffer, cancellation);
            });
            try (var hook = cancellation.onCancel(() -> saved.cancel(false))) {
                if (!saved.get(60, TimeUnit.SECONDS)) {
                    throw new IOException("Save was rejected or failed");
                }
            } finally {
                // A timed-out or interrupted caller must invalidate a staged save before it can commit.
                saved.cancel(false);
            }
            Snapshot current = read(expected.path(), cancellation);
            if (current.dirty() || !current.revision().equals(expected.revision())) {
                throw new IOException("Document changed during save; the newer text is still unsaved");
            }
        }
    }

    @Override
    public boolean saved(Snapshot expected, AgentCancellation cancellation) throws Exception {
        workspace.resolve(expected.path().toString());
        byte[] bytes = AgentFx.call(cancellation, () -> {
            EditorBuffer buffer = host.find(expected.path());
            if (buffer == null) {
                return null;
            }
            Snapshot now = snapshot(buffer, expected.path());
            return now.dirty() || !now.revision().equals(expected.revision()) ? null : host.saveBytes(buffer);
        });
        cancellation.check();
        if (bytes == null || !java.nio.file.Files.isRegularFile(expected.path())) {
            return false;
        }
        try (var in = java.nio.file.Files.newInputStream(expected.path())) {
            return java.util.Arrays.equals(bytes, in.readNBytes(bytes.length + 1));
        }
    }

    @Override
    public Diagnostics diagnostics(Path path, AgentCancellation cancellation) throws Exception {
        workspace.resolve(path.toString());
        return AgentFx.call(cancellation, () -> host.diagnostics(path));
    }

    @Override
    public void showDiff(Path path, String before, String after, AgentCancellation cancellation) throws Exception {
        AgentFx.call(cancellation, () -> {
            host.showDiff(path, before, after);
            return null;
        });
    }
}
