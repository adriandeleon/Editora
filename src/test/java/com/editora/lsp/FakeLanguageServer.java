package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * An in-process {@link LanguageServer} test double that <b>records the params it is given</b>, so tests can
 * assert what Editora actually puts on the wire. Attached via {@code LanguageServerSession.attachForTest}.
 *
 * <p>This is the tier the LSP suite was missing. The pure mappers were already 90-100% covered and produced
 * no bugs this cycle; every defect lived in the request-building code, which needed a forked subprocess to
 * reach and so was asserted by nothing. Recording the params turns "does the server answer?" (a live probe,
 * slow and environment-dependent) into "did we ask correctly?" (a unit test).
 *
 * <p>Responses default to empty/null — a test that cares about a response sets the matching field. Only the
 * requests Editora actually issues are implemented; the rest inherit lsp4j's defaults.
 */
public final class FakeLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

    // --- recorded requests -------------------------------------------------------------------------
    public final List<DidOpenTextDocumentParams> opened = new ArrayList<>();
    public final List<DidChangeTextDocumentParams> changed = new ArrayList<>();
    public final List<DidSaveTextDocumentParams> saved = new ArrayList<>();
    public final List<DidCloseTextDocumentParams> closed = new ArrayList<>();
    public final List<SignatureHelpParams> signatureHelps = new ArrayList<>();
    public final List<InlayHintParams> inlayHints = new ArrayList<>();
    public final List<SemanticTokensRangeParams> semanticRanges = new ArrayList<>();
    public final List<SemanticTokensParams> semanticFulls = new ArrayList<>();
    public final List<CompletionParams> completions = new ArrayList<>();
    public final List<HoverParams> hovers = new ArrayList<>();
    public final List<DocumentHighlightParams> highlights = new ArrayList<>();
    public final List<CodeActionParams> codeActions = new ArrayList<>();
    public final List<DocumentFormattingParams> formattings = new ArrayList<>();
    public final List<DocumentRangeFormattingParams> rangeFormattings = new ArrayList<>();
    public final List<DocumentOnTypeFormattingParams> onTypeFormattings = new ArrayList<>();
    public final List<ExecuteCommandParams> executedCommands = new ArrayList<>();
    public final List<DidChangeConfigurationParams> configurations = new ArrayList<>();
    public final List<DidChangeWatchedFilesParams> watchedFiles = new ArrayList<>();
    public final List<DefinitionParams> definitions = new ArrayList<>();
    public final List<ImplementationParams> implementations = new ArrayList<>();
    public final List<TypeDefinitionParams> typeDefinitions = new ArrayList<>();
    public final List<DeclarationParams> declarations = new ArrayList<>();
    public final List<ReferenceParams> references = new ArrayList<>();
    public final List<DocumentSymbolParams> documentSymbols = new ArrayList<>();
    public final List<WorkspaceSymbolParams> workspaceSymbols = new ArrayList<>();
    public final List<PrepareRenameParams> prepareRenames = new ArrayList<>();
    public final List<RenameParams> renames = new ArrayList<>();

    // --- canned responses --------------------------------------------------------------------------
    public SignatureHelp signatureHelpResponse;
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completionFuture;
    public CompletableFuture<SignatureHelp> signatureHelpFuture;
    public CompletableFuture<Hover> hoverFuture;
    public List<InlayHint> inlayHintResponse = List.of();
    public SemanticTokens semanticTokensResponse;
    public List<TextEdit> formattingResponse = List.of();
    /** The value {@code workspace/executeCommand} answers with (null unless a test sets it). */
    public Object executeCommandResponse;
    /** When set, answers {@code workspace/executeCommand} per request instead of the canned value above. */
    public volatile java.util.function.Function<ExecuteCommandParams, Object> executeCommandHandler;

    public List<TextEdit> onTypeFormattingResponse = List.of();
    public List<Location> definitionResponse = List.of();
    public List<Location> implementationResponse = List.of();
    public List<Location> typeDefinitionResponse = List.of();
    public List<Location> declarationResponse = List.of();
    public List<Location> referenceResponse = List.of();
    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbolResponse = List.of();
    public List<WorkspaceSymbol> workspaceSymbolResponse = List.of();
    public Either3<org.eclipse.lsp4j.Range, PrepareRenameResult, org.eclipse.lsp4j.PrepareRenameDefaultBehavior>
            prepareRenameResponse;
    public WorkspaceEdit renameResponse;
    /** When set, the next request of that kind completes exceptionally — the error paths must degrade, not throw. */
    public boolean failEverything;

    /**
     * When set, the requests the editor repeats on every typing pause (diagnostics, symbols, folding ranges,
     * semantic tokens, inlay hints, highlights) are recorded but <b>not answered</b>: each gets a future that
     * stays open in {@link #held} — a server too busy to reply. A test then sees how many requests the
     * client leaves unanswered and which of them it cancelled.
     */
    public volatile boolean holdReplies;

    public final List<CompletableFuture<?>> held = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Held requests the client has neither cancelled nor had answered. */
    public long unanswered() {
        return held.stream().filter(f -> !f.isDone()).count();
    }

    public long cancelled() {
        return held.stream().filter(CompletableFuture::isCancelled).count();
    }

    private <T> CompletableFuture<T> answer(T value) {
        if (holdReplies) {
            CompletableFuture<T> open = new CompletableFuture<>();
            held.add(open);
            return open;
        }
        return CompletableFuture.completedFuture(value);
    }

    public final List<org.eclipse.lsp4j.FoldingRangeRequestParams> foldingRanges = new ArrayList<>();
    public final List<org.eclipse.lsp4j.SemanticTokensDeltaParams> semanticDeltas = new ArrayList<>();

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.FoldingRange>> foldingRange(
            org.eclipse.lsp4j.FoldingRangeRequestParams params) {
        foldingRanges.add(params);
        return answer(List.of());
    }

    @Override
    public CompletableFuture<Either<SemanticTokens, org.eclipse.lsp4j.SemanticTokensDelta>> semanticTokensFullDelta(
            org.eclipse.lsp4j.SemanticTokensDeltaParams params) {
        semanticDeltas.add(params);
        if (failEverything) {
            return failed();
        }
        if (semanticDeltaResponse != null) {
            return answer(semanticDeltaResponse);
        }
        return answer(semanticTokensResponse == null ? null : Either.forLeft(semanticTokensResponse));
    }

    /** One custom {@code java/…} message, as it went on the wire (#746). */
    public record Raw(String method, Object params) {}

    public final List<Raw> rawRequests = new ArrayList<>();
    public final List<Raw> rawNotifications = new ArrayList<>();
    /** The value a raw request answers with (null unless a test sets it). */
    public Object rawResponse;
    /** When set, answers each raw request per method instead of the canned value above. */
    public volatile java.util.function.Function<Raw, Object> rawHandler;

    /** Installs this fake as the session's raw sink, so custom requests/notifications are recorded. */
    public LanguageServerSession.RawSink rawSink() {
        return new LanguageServerSession.RawSink() {
            @Override
            public CompletableFuture<Object> request(String method, Object params) {
                Raw raw = new Raw(method, params);
                rawRequests.add(raw);
                var handler = rawHandler;
                if (handler != null && !failEverything) {
                    return CompletableFuture.completedFuture(handler.apply(raw));
                }
                return failEverything ? failed() : CompletableFuture.completedFuture(rawResponse);
            }

            @Override
            public void notification(String method, Object params) {
                rawNotifications.add(new Raw(method, params));
            }
        };
    }

    /** The last recorded element of {@code list}, or null when nothing was recorded. */
    public static <T> T last(List<T> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    // --- LanguageServer ----------------------------------------------------------------------------

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {}

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // --- TextDocumentService -----------------------------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        opened.add(params);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        changed.add(params);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        closed.add(params);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        saved.add(params);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        signatureHelps.add(params);
        return signatureHelpFuture != null
                ? signatureHelpFuture
                : CompletableFuture.completedFuture(signatureHelpResponse);
    }

    public final List<org.eclipse.lsp4j.CodeLensParams> codeLenses = new ArrayList<>();
    /** The lenses {@code codeLens/resolve} was asked about, in order. */
    public final List<org.eclipse.lsp4j.CodeLens> resolvedCodeLenses = new ArrayList<>();
    /** Unresolved lenses; resolve gives each one "N references" from the number in its {@code data}. */
    public List<org.eclipse.lsp4j.CodeLens> codeLensResponse = List.of();

    @Override
    public CompletableFuture<List<? extends org.eclipse.lsp4j.CodeLens>> codeLens(
            org.eclipse.lsp4j.CodeLensParams params) {
        codeLenses.add(params);
        return answer(codeLensResponse);
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.CodeLens> resolveCodeLens(org.eclipse.lsp4j.CodeLens unresolved) {
        resolvedCodeLenses.add(unresolved);
        var resolved = new org.eclipse.lsp4j.CodeLens(unresolved.getRange(), null, unresolved.getData());
        resolved.setCommand(
                new org.eclipse.lsp4j.Command(unresolved.getData() + " references", "java.show.references"));
        return answer(resolved);
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        inlayHints.add(params);
        return answer(inlayHintResponse);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        semanticRanges.add(params);
        return answer(semanticTokensResponse);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        semanticFulls.add(params);
        return answer(semanticTokensResponse);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        completions.add(params);
        return completionFuture != null
                ? completionFuture
                : CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        hovers.add(params);
        if (hoverFuture != null) {
            return hoverFuture;
        }
        return failEverything ? failed() : CompletableFuture.completedFuture(hoverResponse);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        highlights.add(params);
        return failEverything ? failed() : answer(highlightResponse);
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        codeActions.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(codeActionResponse);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        formattings.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(formattingResponse);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        definitions.add(params);
        if (definitionLinksResponse != null && !failEverything) {
            return CompletableFuture.completedFuture(Either.forRight(definitionLinksResponse));
        }
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forLeft(definitionResponse));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        implementations.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forLeft(implementationResponse));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            TypeDefinitionParams params) {
        typeDefinitions.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forLeft(typeDefinitionResponse));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(
            DeclarationParams params) {
        declarations.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forLeft(declarationResponse));
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        onTypeFormattings.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(onTypeFormattingResponse);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        references.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(referenceResponse);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        documentSymbols.add(params);
        return failEverything ? failed() : answer(documentSymbolResponse);
    }

    @Override
    public CompletableFuture<
                    Either3<
                            org.eclipse.lsp4j.Range,
                            PrepareRenameResult,
                            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>>
            prepareRename(PrepareRenameParams params) {
        prepareRenames.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(prepareRenameResponse);
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        renames.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(renameResponse);
    }

    /** A future that completes exceptionally, as a real transport failure would. */
    public final List<org.eclipse.lsp4j.DocumentDiagnosticParams> diagnosticPulls = new ArrayList<>();
    /** The report {@code textDocument/diagnostic} answers with (null unless a test sets it). */
    public org.eclipse.lsp4j.DocumentDiagnosticReport diagnosticResponse;

    @Override
    public CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport> diagnostic(
            org.eclipse.lsp4j.DocumentDiagnosticParams params) {
        diagnosticPulls.add(params);
        return failEverything ? failed() : answer(diagnosticResponse);
    }

    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("simulated transport failure"));
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        rangeFormattings.add(params);
        return CompletableFuture.completedFuture(formattingResponse);
    }

    // --- hierarchies, resolves and the other answers a coordinator flow needs --------------------------

    public List<Either<Command, CodeAction>> codeActionResponse = List.of();
    public List<DocumentHighlight> highlightResponse = List.of();
    public Hover hoverResponse;
    /** When set, {@code textDocument/definition} answers in the {@code LocationLink} shape instead. */
    public List<LocationLink> definitionLinksResponse;
    /** When set, {@code workspace/symbol} answers in the older {@code SymbolInformation} shape instead. */
    public List<SymbolInformation> symbolInformationResponse;
    /** When set, {@code semanticTokens/full/delta} answers with it rather than with a full set. */
    public Either<SemanticTokens, org.eclipse.lsp4j.SemanticTokensDelta> semanticDeltaResponse;

    public final List<CodeAction> resolvedCodeActions = new ArrayList<>();
    /** What {@code codeAction/resolve} answers with; identity (the unresolved action) when null. */
    public volatile java.util.function.UnaryOperator<CodeAction> codeActionResolver;

    @Override
    public CompletableFuture<CodeAction> resolveCodeAction(CodeAction unresolved) {
        resolvedCodeActions.add(unresolved);
        if (failEverything) {
            return failed();
        }
        var resolver = codeActionResolver;
        return CompletableFuture.completedFuture(resolver == null ? unresolved : resolver.apply(unresolved));
    }

    public final List<CompletionItem> resolvedCompletions = new ArrayList<>();
    /** What {@code completionItem/resolve} answers with; identity (the unresolved item) when null. */
    public volatile java.util.function.UnaryOperator<CompletionItem> completionResolver;

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        resolvedCompletions.add(unresolved);
        if (failEverything) {
            return failed();
        }
        var resolver = completionResolver;
        return CompletableFuture.completedFuture(resolver == null ? unresolved : resolver.apply(unresolved));
    }

    public final List<org.eclipse.lsp4j.CallHierarchyPrepareParams> callHierarchyPrepares = new ArrayList<>();
    public final List<org.eclipse.lsp4j.CallHierarchyItem> incomingCallRequests = new ArrayList<>();
    public final List<org.eclipse.lsp4j.CallHierarchyItem> outgoingCallRequests = new ArrayList<>();
    public List<org.eclipse.lsp4j.CallHierarchyItem> callHierarchyResponse = List.of();
    public List<org.eclipse.lsp4j.CallHierarchyIncomingCall> incomingCallsResponse = List.of();
    public List<org.eclipse.lsp4j.CallHierarchyOutgoingCall> outgoingCallsResponse = List.of();

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyItem>> prepareCallHierarchy(
            org.eclipse.lsp4j.CallHierarchyPrepareParams params) {
        callHierarchyPrepares.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(callHierarchyResponse);
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
            org.eclipse.lsp4j.CallHierarchyIncomingCallsParams params) {
        incomingCallRequests.add(params.getItem());
        return failEverything ? failed() : CompletableFuture.completedFuture(incomingCallsResponse);
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
            org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams params) {
        outgoingCallRequests.add(params.getItem());
        return failEverything ? failed() : CompletableFuture.completedFuture(outgoingCallsResponse);
    }

    public final List<org.eclipse.lsp4j.TypeHierarchyPrepareParams> typeHierarchyPrepares = new ArrayList<>();
    public final List<org.eclipse.lsp4j.TypeHierarchyItem> supertypeRequests = new ArrayList<>();
    public final List<org.eclipse.lsp4j.TypeHierarchyItem> subtypeRequests = new ArrayList<>();
    public List<org.eclipse.lsp4j.TypeHierarchyItem> typeHierarchyResponse = List.of();
    public List<org.eclipse.lsp4j.TypeHierarchyItem> supertypesResponse = List.of();
    public List<org.eclipse.lsp4j.TypeHierarchyItem> subtypesResponse = List.of();

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> prepareTypeHierarchy(
            org.eclipse.lsp4j.TypeHierarchyPrepareParams params) {
        typeHierarchyPrepares.add(params);
        return failEverything ? failed() : CompletableFuture.completedFuture(typeHierarchyResponse);
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> typeHierarchySupertypes(
            org.eclipse.lsp4j.TypeHierarchySupertypesParams params) {
        supertypeRequests.add(params.getItem());
        return failEverything ? failed() : CompletableFuture.completedFuture(supertypesResponse);
    }

    @Override
    public CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> typeHierarchySubtypes(
            org.eclipse.lsp4j.TypeHierarchySubtypesParams params) {
        subtypeRequests.add(params.getItem());
        return failEverything ? failed() : CompletableFuture.completedFuture(subtypesResponse);
    }

    // --- WorkspaceService --------------------------------------------------------------------------

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        configurations.add(params);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        watchedFiles.add(params);
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        workspaceSymbols.add(params);
        if (symbolInformationResponse != null && !failEverything) {
            return CompletableFuture.completedFuture(Either.forLeft(symbolInformationResponse));
        }
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forRight(workspaceSymbolResponse));
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        executedCommands.add(params);
        var handler = executeCommandHandler;
        if (handler != null && !failEverything) {
            return CompletableFuture.completedFuture(handler.apply(params));
        }
        return failEverything ? failed() : CompletableFuture.completedFuture(executeCommandResponse);
    }
}
