package com.editora.agent.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Semantic exploration and preview leases; the native document tools retain ownership of mutations. */
public final class AgentSemanticTools {
    @FunctionalInterface
    public interface Commit {
        AgentTool.Result apply(List<AgentDocuments.Edit> edits, AgentCancellation cancellation) throws Exception;
    }

    private record Proposal(List<AgentDocuments.Edit> edits) {}

    private record Splice(int start, int end, String text) {}

    private final AgentWorkspace workspace;
    private final AgentDocuments documents;
    private final AgentSemantics semantics;
    private final Commit commit;
    private final AgentContextRanker.Index discoveries;
    private AgentAcceptance acceptance;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Proposal> proposals = new LinkedHashMap<>();

    public AgentSemanticTools(
            AgentWorkspace workspace, AgentDocuments documents, AgentSemantics semantics, Commit commit) {
        this(workspace, documents, semantics, commit, new AgentContextRanker.Index());
    }

    public AgentSemanticTools(
            AgentWorkspace workspace,
            AgentDocuments documents,
            AgentSemantics semantics,
            Commit commit,
            AgentContextRanker.Index discoveries) {
        this.workspace = workspace;
        this.documents = documents;
        this.semantics = semantics;
        this.commit = commit;
        this.discoveries = discoveries;
    }

    public AgentSemanticTools(
            AgentWorkspace workspace,
            AgentDocuments documents,
            AgentSemantics semantics,
            Commit commit,
            AgentContextRanker.Index discoveries,
            AgentAcceptance acceptance) {
        this(workspace, documents, semantics, commit, discoveries);
        this.acceptance = acceptance;
    }

    public void register(AgentTools tools) throws Exception {
        add(
                tools,
                "semantic_capabilities",
                "Discover currently supported LSP operations for a live file. Unsupported or unavailable operations are explicit; read the file first.",
                "{\"path\":{\"type\":\"string\"}}",
                List.of("path"),
                AgentTool.Effect.READ,
                (a, c) -> AgentTool.Result.ok(semantics
                        .capabilities(
                                documents.read(workspace.resolve(a.get("path").asText()), c), c)
                        .toString()));
        String position =
                "\"path\":{\"type\":\"string\"},\"revision\":{\"type\":\"string\"},\"line\":{\"type\":\"integer\",\"minimum\":0},\"character\":{\"type\":\"integer\",\"minimum\":0},\"query\":{\"type\":\"string\",\"maxLength\":500},\"direction\":{\"type\":\"string\",\"enum\":[\"incoming\",\"outgoing\",\"parents\",\"children\"]}";
        add(
                tools,
                "semantic_query",
                "Query live code using LSP. First discover semantic_capabilities. Use operation=symbols (no position needed) to obtain exact zero-based UTF-16 selection ranges before references or rename; avoid guessing character offsets. Returns compact symbols/ranges and source revision; follow locations with targeted read_file. Stale responses fail.",
                "{" + position
                        + ",\"operation\":{\"type\":\"string\",\"enum\":[\"symbols\",\"workspace_symbols\",\"definition\",\"declaration\",\"implementation\",\"type_definition\",\"references\",\"hover\",\"signature\",\"highlights\",\"call_hierarchy\",\"type_hierarchy\",\"code_actions\"]}}",
                List.of("path", "revision", "operation"),
                AgentTool.Effect.READ,
                (a, c) -> {
                    var source = source(a, c);
                    String operation = a.get("operation").asText();
                    var result = semantics.request(operation, a, source, List.of(source), c);
                    for (var item : result.path("items")) {
                        try {
                            Path file = workspace.resolve(
                                    Path.of(java.net.URI.create(item.path("uri").asText()))
                                            .toString());
                            discoveries.note(
                                    workspace.root().relativize(file).toString(),
                                    operation.equals("references")
                                            ? AgentContextRanker.Signal.REFERENCE
                                            : AgentContextRanker.Signal.DEFINITION);
                        } catch (Exception unavailable) {
                            /* Library/external symbols are not workspace context candidates. */
                        }
                    }
                    if (acceptance != null) acceptance.semantic(operation, result, source, a);
                    return AgentTool.Result.ok(result.toString());
                });
        add(
                tools,
                "semantic_prepare",
                "Preview rename, formatting, or an edit-only code action. Read ALL affected files before requesting; unseen targets, resource operations, commands, stale versions and overlapping edits are rejected. Returns a proposal token and diffs; apply separately with semantic_apply.",
                "{" + position
                        + ",\"operation\":{\"type\":\"string\",\"enum\":[\"rename\",\"format\",\"code_action\"]},\"new_name\":{\"type\":\"string\",\"maxLength\":200},\"action_index\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":99}}",
                List.of("path", "revision", "operation"),
                AgentTool.Effect.READ,
                this::prepare);
        add(
                tools,
                "semantic_apply",
                "Apply a previously previewed semantic proposal through revision-checked undoable editor transactions. Newer user edits cause a conflict. Validation is invalidated.",
                "{\"proposal\":{\"type\":\"string\"}}",
                List.of("proposal"),
                AgentTool.Effect.WORKSPACE_WRITE,
                (a, c) -> {
                    Proposal proposal = proposals.remove(a.get("proposal").asText());
                    if (proposal == null)
                        return AgentTool.Result.failure("Unknown or consumed proposal; prepare again");
                    return commit.apply(proposal.edits(), c);
                });
    }

    private AgentDocuments.Snapshot source(JsonNode args, AgentCancellation c) throws Exception {
        var source = documents.read(workspace.resolve(args.get("path").asText()), c);
        if (!source.revision().equals(args.get("revision").asText()))
            throw new IllegalStateException("Stale semantic source; reread");
        offset(source.text(), args.path("line").asInt(), args.path("character").asInt());
        return source;
    }

    private AgentTool.Result prepare(JsonNode args, AgentCancellation c) throws Exception {
        var source = source(args, c);
        Map<Path, AgentDocuments.Snapshot> preimages = new LinkedHashMap<>();
        for (var snapshot : documents.open(c)) preimages.put(snapshot.path(), snapshot);
        preimages.put(source.path(), source);
        var result =
                semantics.request(args.get("operation").asText(), args, source, List.copyOf(preimages.values()), c);
        List<AgentDocuments.Edit> edits = translate(result.path("edits"), preimages, workspace);
        if (edits.isEmpty()) return AgentTool.Result.ok("No semantic changes proposed");
        // Validate every lease before displaying any preview. Commit validates the same leases again.
        var current = new java.util.HashMap<Path, String>();
        documents.states(c).forEach(state -> current.put(state.path(), state.revision()));
        for (var edit : edits)
            if (!edit.revision().equals(current.get(edit.path())))
                throw new IllegalStateException("Semantic proposal is stale; prepare again");
        ObjectNode output = json.createObjectNode();
        String id = UUID.randomUUID().toString();
        output.put("proposal", id);
        var files = output.putArray("files");
        for (var edit : edits) {
            documents.showDiff(edit.path(), preimages.get(edit.path()).text(), edit.newText(), c);
            files.addObject()
                    .put("path", workspace.root().relativize(edit.path()).toString())
                    .put("revision", edit.revision());
        }
        if (proposals.size() >= 8)
            proposals.remove(proposals.keySet().iterator().next());
        proposals.put(id, new Proposal(List.copyOf(edits)));
        return AgentTool.Result.ok(output.toString());
    }

    static List<AgentDocuments.Edit> translate(
            JsonNode changes, Map<Path, AgentDocuments.Snapshot> preimages, AgentWorkspace workspace) throws Exception {
        if (!changes.isArray() || changes.size() > 16)
            throw new IllegalArgumentException("Invalid or oversized semantic edit");
        List<AgentDocuments.Edit> out = new ArrayList<>();
        var seen = new java.util.HashSet<Path>();
        for (var change : changes) {
            Path path = workspace.resolve(
                    Path.of(java.net.URI.create(change.path("uri").asText())).toString());
            var snapshot = preimages.get(path);
            if (snapshot == null)
                throw new IllegalStateException("Read affected file before preparing semantic edit: " + path);
            if (!seen.add(path)) throw new IllegalArgumentException("Repeated semantic target");
            List<Splice> splices = new ArrayList<>();
            for (var edit : change.path("edits")) {
                var range = edit.path("range");
                int start = offset(
                        snapshot.text(),
                        range.path("start").path("line").asInt(-1),
                        range.path("start").path("character").asInt(-1));
                int end = offset(
                        snapshot.text(),
                        range.path("end").path("line").asInt(-1),
                        range.path("end").path("character").asInt(-1));
                if (end < start || !edit.path("newText").isTextual())
                    throw new IllegalArgumentException("Invalid semantic range");
                splices.add(new Splice(start, end, edit.get("newText").asText()));
                if (splices.size() > 2000) throw new IllegalArgumentException("Too many semantic edits");
            }
            splices.sort(Comparator.comparingInt(Splice::start).thenComparingInt(Splice::end));
            int lastEnd = -1, lastStart = -1;
            for (var splice : splices) {
                if (splice.start() < lastEnd || splice.start() == lastStart)
                    throw new IllegalArgumentException("Overlapping semantic edits");
                lastEnd = splice.end();
                lastStart = splice.start();
            }
            StringBuilder text = new StringBuilder(snapshot.text());
            for (var splice : splices.reversed()) text.replace(splice.start(), splice.end(), splice.text());
            if (text.length() > AgentWorkspace.MAX_FILE_CHARS)
                throw new IllegalArgumentException("Semantic edit too large");
            out.add(new AgentDocuments.Edit(path, snapshot.revision(), "", text.toString()));
        }
        return List.copyOf(out);
    }

    /** Strict UTF-16 range conversion; never clamp invalid server coordinates into valid edits. */
    static int offset(String text, int line, int column) {
        if (line < 0 || column < 0) throw new IllegalArgumentException("Negative semantic position");
        int start = 0;
        for (int i = 0; i < line; i++) {
            int end = text.indexOf('\n', start);
            if (end < 0) throw new IllegalArgumentException("Line outside document");
            start = end + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        if (column > end - start) throw new IllegalArgumentException("Column outside line");
        int pos = start + column;
        if (pos > start
                && pos < text.length()
                && Character.isHighSurrogate(text.charAt(pos - 1))
                && Character.isLowSurrogate(text.charAt(pos)))
            throw new IllegalArgumentException("Position splits UTF-16 character");
        return pos;
    }

    private void add(
            AgentTools tools,
            String name,
            String description,
            String properties,
            List<String> required,
            AgentTool.Effect effect,
            AgentTool.Handler handler)
            throws Exception {
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", json.readTree(properties));
        var req = schema.putArray("required");
        required.forEach(req::add);
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        name, description, schema, null, effect, Duration.ofSeconds(40), true, "editora-lsp"),
                handler));
    }
}
