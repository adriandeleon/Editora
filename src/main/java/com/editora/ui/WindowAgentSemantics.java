package com.editora.ui;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Captures synchronized LSP state on FX, waits off FX, then checks document and server leases. */
final class WindowAgentSemantics implements AgentSemantics {
    private final WindowAgentDocuments.Host host;
    private final WindowAgentDocuments documents;
    private final AgentWorkspace workspace;
    private final ObjectMapper json = new ObjectMapper();

    WindowAgentSemantics(WindowAgentDocuments.Host host, WindowAgentDocuments documents, AgentWorkspace workspace) {
        this.host = host;
        this.documents = documents;
        this.workspace = workspace;
    }

    @Override
    public JsonNode capabilities(AgentDocuments.Snapshot source, AgentCancellation c) throws Exception {
        return AgentFx.call(c, () -> {
            if (!documents.current(source)) throw new IllegalStateException("Stale semantic source");
            host.ensureLsp(host.find(source.path()));
            var out = json.createObjectNode()
                    .put("path", source.path().toString())
                    .put("revision", source.revision());
            try {
                var endpoint = host.lsp().agentEndpoint(source.path());
                var operations = out.putArray("operations");
                var supported = endpoint.operations();
                supported.forEach(operations::add);
                out.put("initialized", true).put("available", !supported.isEmpty());
                if (supported.isEmpty())
                    out.put(
                            "reason",
                            "No semantic capability registered yet; retry capability discovery after initialization");
            } catch (IllegalStateException unavailable) {
                out.put("available", false).put("reason", unavailable.getMessage());
                out.putArray("operations");
            }
            return out;
        });
    }

    @Override
    public JsonNode request(
            String operation,
            JsonNode args,
            AgentDocuments.Snapshot source,
            List<AgentDocuments.Snapshot> preimages,
            AgentCancellation c)
            throws Exception {
        // Resolve targets off FX before inspecting scene/document state.
        for (var snapshot : preimages) workspace.resolve(snapshot.path().toString());
        var future = AgentFx.call(c, () -> {
            for (var snapshot : preimages) {
                var buffer = host.find(snapshot.path());
                if (buffer == null || !documents.current(snapshot))
                    throw new IllegalStateException("Document changed before semantic request");
                host.ensureLsp(buffer);
                buffer.sendLspChange();
            }
            var endpoint = host.lsp().agentEndpoint(source.path());
            var sent = endpoint.snapshots();
            for (var snapshot : preimages) {
                String wire = sent.get(snapshot.path().toUri().toString());
                if (wire != null && !wire.equals(snapshot.text()))
                    throw new IllegalStateException("LSP synchronization pending; retry after server initialization");
            }
            if (!source.text().equals(sent.get(source.path().toUri().toString())))
                throw new IllegalStateException("Source is not synchronized to LSP");
            return endpoint.request(operation, source.path().toUri().toString(), args);
        });
        JsonNode result;
        try (var hook = c.onCancel(() -> future.cancel(true))) {
            result = future.get(30, TimeUnit.SECONDS);
        } finally {
            future.cancel(true);
        }
        if (!AgentFx.call(c, () -> preimages.stream().allMatch(documents::current)))
            throw new IllegalStateException("Stale semantic response; a document changed during analysis");
        ObjectNode out = json.createObjectNode()
                .put("source", workspace.root().relativize(source.path()).toString())
                .put("revision", source.revision())
                .put("freshness", "CLIENT_REVISION_CURRENT_AT_RESPONSE")
                .put("serverRevision", "UNVERSIONED_RESPONSE");
        if (result.isObject()) result.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        else out.set("items", result);
        return out;
    }
}
