package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/** Typed, cancellable agent requests over the existing LSP session. Never applies server edits/commands. */
public final class LspAgentService {
    private final LanguageServerSession session;
    private final BooleanSupplier current;
    private final ObjectMapper json = new ObjectMapper();

    LspAgentService(LanguageServerSession session, BooleanSupplier current) {
        this.session = session;
        this.current = current;
    }

    public Map<String, String> snapshots() {
        return session.documentSnapshots();
    }

    public Integer version(String uri) {
        return session.documentVersion(uri);
    }

    public List<String> operations() {
        session.agentServer();
        var c = session.capabilities();
        if (c == null) return List.of();
        List<String> ops = new ArrayList<>();
        support(ops, "symbols", c.getDocumentSymbolProvider());
        support(ops, "workspace_symbols", c.getWorkspaceSymbolProvider());
        support(ops, "definition", c.getDefinitionProvider());
        support(ops, "declaration", c.getDeclarationProvider());
        support(ops, "implementation", c.getImplementationProvider());
        support(ops, "type_definition", c.getTypeDefinitionProvider());
        support(ops, "references", c.getReferencesProvider());
        support(ops, "hover", c.getHoverProvider());
        if (c.getSignatureHelpProvider() != null) ops.add("signature");
        support(ops, "highlights", c.getDocumentHighlightProvider());
        support(ops, "call_hierarchy", c.getCallHierarchyProvider());
        support(ops, "type_hierarchy", c.getTypeHierarchyProvider());
        support(ops, "code_actions", c.getCodeActionProvider());
        support(ops, "code_action", c.getCodeActionProvider());
        support(ops, "rename", c.getRenameProvider());
        support(ops, "format", c.getDocumentFormattingProvider());
        return List.copyOf(ops);
    }

    private static void support(List<String> list, String name, Either<Boolean, ?> value) {
        if (value != null && (value.isLeft() ? Boolean.TRUE.equals(value.getLeft()) : value.getRight() != null))
            list.add(name);
    }

    /** Invoke on FX after flushing document changes. Transformations run off FX via the raw future. */
    public CompletableFuture<JsonNode> request(String operation, String uri, JsonNode args) {
        if (!current.getAsBoolean() || !operations().contains(operation))
            return CompletableFuture.failedFuture(
                    new IllegalStateException("LSP operation unsupported or unavailable: " + operation));
        var server = session.agentServer();
        var text = server.getTextDocumentService();
        var doc = new TextDocumentIdentifier(uri);
        var pos = new Position(args.path("line").asInt(), args.path("character").asInt());
        var range = new Range(pos, pos);
        Map<String, Integer> versions = new java.util.HashMap<>();
        snapshots().keySet().forEach(key -> versions.put(key, version(key)));
        CompletableFuture<JsonNode> result =
                switch (operation) {
                    case "symbols" -> map(text.documentSymbol(new DocumentSymbolParams(doc)), v -> symbols(v, uri));
                    case "workspace_symbols" ->
                        map(
                                server.getWorkspaceService()
                                        .symbol(new WorkspaceSymbolParams(
                                                args.path("query").asText())),
                                this::workspaceSymbols);
                    case "definition" -> map(text.definition(new DefinitionParams(doc, pos)), this::locations);
                    case "declaration" -> map(text.declaration(new DeclarationParams(doc, pos)), this::locations);
                    case "implementation" ->
                        map(text.implementation(new ImplementationParams(doc, pos)), this::locations);
                    case "type_definition" ->
                        map(text.typeDefinition(new TypeDefinitionParams(doc, pos)), this::locations);
                    case "references" ->
                        map(
                                text.references(new ReferenceParams(doc, pos, new ReferenceContext(true))),
                                v -> locations(Either.forLeft(v == null ? List.of() : v)));
                    case "hover" ->
                        map(text.hover(new HoverParams(doc, pos)), v -> {
                            var out = json.createObjectNode();
                            out.put(
                                    "text",
                                    v == null ? "" : com.editora.agent.runtime.AgentContext.bounded(hover(v), 5000));
                            return out;
                        });
                    case "signature" ->
                        map(text.signatureHelp(new SignatureHelpParams(doc, pos)), v -> {
                            var out = json.createObjectNode();
                            var items = out.putArray("signatures");
                            if (v != null && v.getSignatures() != null)
                                v.getSignatures().stream()
                                        .limit(12)
                                        .forEach(s -> items.add(
                                                com.editora.agent.runtime.AgentContext.bounded(s.getLabel(), 1000)));
                            return out;
                        });
                    case "highlights" ->
                        map(text.documentHighlight(new DocumentHighlightParams(doc, pos)), v -> {
                            var out = json.createObjectNode();
                            var items = out.putArray("items");
                            if (v != null)
                                v.stream().limit(100).forEach(h -> {
                                    var item = items.addObject();
                                    item.set("range", range(h.getRange()));
                                    item.put("kind", String.valueOf(h.getKind()));
                                });
                            return out;
                        });
                    case "rename" -> {
                        if (args.path("new_name").asText().isBlank())
                            yield CompletableFuture.failedFuture(new IllegalArgumentException("new_name required"));
                        yield map(
                                text.rename(new RenameParams(
                                        doc, pos, args.get("new_name").asText())),
                                v -> edits(v, versions));
                    }
                    case "format" ->
                        map(
                                text.formatting(new DocumentFormattingParams(doc, new FormattingOptions(4, true))),
                                v -> edits(
                                        new WorkspaceEdit(
                                                Map.of(uri, v == null ? List.of() : new ArrayList<TextEdit>(v))),
                                        versions));
                    case "code_actions", "code_action" ->
                        map(text.codeAction(new CodeActionParams(doc, range, new CodeActionContext(List.of()))), v -> {
                            var actions = v == null ? List.<Either<Command, CodeAction>>of() : v;
                            if (operation.equals("code_action")) {
                                int index = args.path("action_index").asInt(-1);
                                if (index < 0 || index >= actions.size())
                                    throw new IllegalArgumentException(
                                            "Invalid action_index; query code_actions first");
                                var candidate = actions.get(index);
                                if (candidate.isLeft()
                                        || candidate.getRight().getCommand() != null
                                        || candidate.getRight().getDisabled() != null
                                        || candidate.getRight().getEdit() == null)
                                    throw new IllegalStateException(
                                            "Action requires a server command/resolve or is disabled; only explicit edit-only actions are supported");
                                return edits(candidate.getRight().getEdit(), versions);
                            }
                            var out = json.createObjectNode();
                            var items = out.putArray("actions");
                            for (int i = 0; i < Math.min(100, actions.size()); i++) {
                                var action = actions.get(i);
                                var item = items.addObject().put("index", i);
                                item.put(
                                        "title",
                                        action.isLeft()
                                                ? action.getLeft().getTitle()
                                                : action.getRight().getTitle());
                                item.put(
                                        "applicable",
                                        action.isRight()
                                                && action.getRight().getDisabled() == null
                                                && action.getRight().getCommand() == null
                                                && action.getRight().getEdit() != null);
                            }
                            return out;
                        });
                    case "call_hierarchy" ->
                        hierarchy(
                                text.prepareCallHierarchy(new CallHierarchyPrepareParams(doc, pos)),
                                args.path("direction").asText("incoming"),
                                true);
                    case "type_hierarchy" ->
                        hierarchy(
                                text.prepareTypeHierarchy(new TypeHierarchyPrepareParams(doc, pos)),
                                args.path("direction").asText("parents"),
                                false);
                    default ->
                        CompletableFuture.failedFuture(new IllegalArgumentException("Unknown semantic operation"));
                };
        return map(result, value -> {
            if (!current.getAsBoolean()) throw new IllegalStateException("Language server changed during request");
            return value;
        });
    }

    /** Cancellation of a transformed future reaches the actual LSP4J request. */
    private static <T, R> CompletableFuture<R> map(CompletableFuture<T> source, Function<T, R> transform) {
        var result = source.thenApplyAsync(transform);
        result.whenComplete((v, e) -> {
            if (result.isCancelled()) source.cancel(true);
        });
        return result;
    }

    private CompletableFuture<JsonNode> hierarchy(
            CompletableFuture<? extends List<?>> prepared, String direction, boolean calls) {
        var active = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>>(prepared);
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        prepared.whenComplete((roots, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (result.isCancelled()) return;
            if (roots == null || roots.isEmpty()) {
                result.complete(json.createObjectNode().putArray("items"));
                return;
            }
            try {
                var text = session.agentServer().getTextDocumentService();
                Object root = roots.getFirst();
                CompletableFuture<?> child;
                if (calls)
                    child = direction.equals("outgoing")
                            ? text.callHierarchyOutgoingCalls(
                                    new CallHierarchyOutgoingCallsParams((CallHierarchyItem) root))
                            : text.callHierarchyIncomingCalls(
                                    new CallHierarchyIncomingCallsParams((CallHierarchyItem) root));
                else
                    child = direction.equals("children")
                            ? text.typeHierarchySubtypes(new TypeHierarchySubtypesParams((TypeHierarchyItem) root))
                            : text.typeHierarchySupertypes(new TypeHierarchySupertypesParams((TypeHierarchyItem) root));
                active.set(child);
                if (result.isCancelled()) child.cancel(true);
                child.whenComplete((values, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    try {
                        var out = json.createObjectNode();
                        var items = out.putArray("items");
                        if (values instanceof List<?> list)
                            for (Object value : list.stream().limit(100).toList()) {
                                if (value instanceof CallHierarchyIncomingCall v) value = v.getFrom();
                                if (value instanceof CallHierarchyOutgoingCall v) value = v.getTo();
                                if (value instanceof CallHierarchyItem v)
                                    item(
                                            items.addObject(),
                                            v.getName(),
                                            v.getUri(),
                                            v.getSelectionRange(),
                                            v.getKind(),
                                            v.getDetail());
                                if (value instanceof TypeHierarchyItem v)
                                    item(
                                            items.addObject(),
                                            v.getName(),
                                            v.getUri(),
                                            v.getSelectionRange(),
                                            v.getKind(),
                                            v.getDetail());
                            }
                        out.put("preparedRoots", roots.size());
                        result.complete(out);
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    }
                });
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((v, e) -> {
            if (result.isCancelled()) active.get().cancel(true);
        });
        return result;
    }

    private JsonNode symbols(List<Either<SymbolInformation, DocumentSymbol>> values, String uri) {
        var out = json.createObjectNode();
        var items = out.putArray("items");
        if (values != null)
            for (var value : values) {
                if (items.size() >= 100) break;
                if (value.isLeft()) {
                    var v = value.getLeft();
                    item(
                            items.addObject(),
                            v.getName(),
                            v.getLocation().getUri(),
                            v.getLocation().getRange(),
                            v.getKind(),
                            v.getContainerName());
                } else symbol(items, value.getRight(), uri, "");
            }
        out.put("truncated", items.size() >= 100);
        return out;
    }

    private void symbol(
            com.fasterxml.jackson.databind.node.ArrayNode items, DocumentSymbol v, String uri, String parent) {
        if (items.size() >= 100) return;
        item(items.addObject(), v.getName(), uri, v.getSelectionRange(), v.getKind(), parent);
        if (v.getChildren() != null) for (var child : v.getChildren()) symbol(items, child, uri, v.getName());
    }

    private JsonNode workspaceSymbols(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> values) {
        var out = json.createObjectNode();
        var items = out.putArray("items");
        if (values != null && values.isLeft())
            for (var v : values.getLeft().stream().limit(100).toList())
                item(
                        items.addObject(),
                        v.getName(),
                        v.getLocation().getUri(),
                        v.getLocation().getRange(),
                        v.getKind(),
                        v.getContainerName());
        if (values != null && values.isRight())
            for (var v : values.getRight().stream().limit(100).toList()) {
                var loc = v.getLocation();
                item(
                        items.addObject(),
                        v.getName(),
                        loc.isLeft() ? loc.getLeft().getUri() : loc.getRight().getUri(),
                        loc.isLeft() ? loc.getLeft().getRange() : null,
                        v.getKind(),
                        v.getContainerName());
            }
        out.put("truncated", items.size() >= 100);
        return out;
    }

    private JsonNode locations(Either<List<? extends Location>, List<? extends LocationLink>> values) {
        var out = json.createObjectNode();
        var items = out.putArray("items");
        if (values != null && values.isLeft())
            for (var v : values.getLeft().stream().limit(100).toList())
                item(items.addObject(), "", v.getUri(), v.getRange(), null, "");
        if (values != null && values.isRight())
            for (var v : values.getRight().stream().limit(100).toList())
                item(items.addObject(), "", v.getTargetUri(), v.getTargetSelectionRange(), null, "");
        out.put("truncated", items.size() >= 100);
        return out;
    }

    private void item(ObjectNode item, String name, String uri, Range range, SymbolKind kind, String container) {
        item.put("symbol", name)
                .put("uri", uri)
                .put("kind", kind == null ? "" : kind.name())
                .put("container", container == null ? "" : container);
        if (range != null) item.set("range", range(range));
    }

    private ObjectNode range(Range range) {
        var out = json.createObjectNode();
        out.putObject("start")
                .put("line", range.getStart().getLine())
                .put("character", range.getStart().getCharacter());
        out.putObject("end")
                .put("line", range.getEnd().getLine())
                .put("character", range.getEnd().getCharacter());
        return out;
    }

    private String hover(Hover value) {
        if (value.getContents() == null) return "";
        if (value.getContents().isRight()) return value.getContents().getRight().getValue();
        StringBuilder out = new StringBuilder();
        for (var v : value.getContents().getLeft())
            out.append(v.isLeft() ? v.getLeft() : v.getRight().getValue()).append('\n');
        return out.toString();
    }

    private JsonNode edits(WorkspaceEdit edit, Map<String, Integer> versions) {
        var out = json.createObjectNode();
        var files = out.putArray("edits");
        if (edit == null) return out;
        // JDT LS can serialize the unused form as an empty container. Only two nonempty
        // forms are ambiguous; all actual edits still pass version, target and operation checks.
        if (edit.getChanges() != null
                && !edit.getChanges().isEmpty()
                && edit.getDocumentChanges() != null
                && !edit.getDocumentChanges().isEmpty())
            throw new IllegalArgumentException("Ambiguous WorkspaceEdit forms");
        if (edit.getChanges() != null)
            edit.getChanges().forEach((uri, changes) -> fileEdit(files, uri, changes, versions));
        if (edit.getDocumentChanges() != null)
            for (var change : edit.getDocumentChanges()) {
                if (change.isRight())
                    throw new IllegalArgumentException(
                            "Resource operations require a separate transaction; no files were changed");
                var id = change.getLeft().getTextDocument();
                if (id.getVersion() != null && !id.getVersion().equals(versions.get(id.getUri())))
                    throw new IllegalArgumentException("Stale LSP WorkspaceEdit version");
                List<TextEdit> plain = new ArrayList<>();
                for (var value : change.getLeft().getEdits()) {
                    if (value.isRight()) throw new IllegalArgumentException("Snippet edits require separate handling");
                    plain.add(value.getLeft());
                }
                fileEdit(files, id.getUri(), plain, versions);
            }
        return out;
    }

    private void fileEdit(
            com.fasterxml.jackson.databind.node.ArrayNode files,
            String uri,
            List<? extends TextEdit> edits,
            Map<String, Integer> versions) {
        if (!versions.containsKey(uri))
            throw new IllegalStateException("Read and synchronize affected file before preparing edit: " + uri);
        if (files.size() >= 16 || edits.size() > 2000) throw new IllegalArgumentException("Oversized WorkspaceEdit");
        var file = files.addObject().put("uri", uri);
        var changes = file.putArray("edits");
        for (var edit : edits) {
            if (edit instanceof AnnotatedTextEdit)
                throw new IllegalArgumentException("Annotated edits require separate consent");
            var change = changes.addObject().put("newText", edit.getNewText());
            change.set("range", range(edit.getRange()));
        }
    }
}
