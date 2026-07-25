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
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
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
    public final List<ExecuteCommandParams> executedCommands = new ArrayList<>();
    public final List<DidChangeConfigurationParams> configurations = new ArrayList<>();
    public final List<DidChangeWatchedFilesParams> watchedFiles = new ArrayList<>();
    public final List<DefinitionParams> definitions = new ArrayList<>();
    public final List<ReferenceParams> references = new ArrayList<>();
    public final List<DocumentSymbolParams> documentSymbols = new ArrayList<>();
    public final List<WorkspaceSymbolParams> workspaceSymbols = new ArrayList<>();
    public final List<PrepareRenameParams> prepareRenames = new ArrayList<>();
    public final List<RenameParams> renames = new ArrayList<>();

    // --- canned responses --------------------------------------------------------------------------
    public SignatureHelp signatureHelpResponse;
    public List<InlayHint> inlayHintResponse = List.of();
    public SemanticTokens semanticTokensResponse;
    public List<TextEdit> formattingResponse = List.of();
    public List<Location> definitionResponse = List.of();
    public List<Location> referenceResponse = List.of();
    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbolResponse = List.of();
    public List<WorkspaceSymbol> workspaceSymbolResponse = List.of();
    public Either3<org.eclipse.lsp4j.Range, PrepareRenameResult, org.eclipse.lsp4j.PrepareRenameDefaultBehavior>
            prepareRenameResponse;
    public WorkspaceEdit renameResponse;
    /** When set, the next request of that kind completes exceptionally — the error paths must degrade, not throw. */
    public boolean failEverything;

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
        return CompletableFuture.completedFuture(signatureHelpResponse);
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        inlayHints.add(params);
        return CompletableFuture.completedFuture(inlayHintResponse);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        semanticRanges.add(params);
        return CompletableFuture.completedFuture(semanticTokensResponse);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        semanticFulls.add(params);
        return CompletableFuture.completedFuture(semanticTokensResponse);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        completions.add(params);
        return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        hovers.add(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        highlights.add(params);
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        codeActions.add(params);
        return CompletableFuture.completedFuture(List.of());
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
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forLeft(definitionResponse));
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
        return failEverything ? failed() : CompletableFuture.completedFuture(documentSymbolResponse);
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
    private static <T> CompletableFuture<T> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("simulated transport failure"));
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        rangeFormattings.add(params);
        return CompletableFuture.completedFuture(formattingResponse);
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
        return failEverything ? failed() : CompletableFuture.completedFuture(Either.forRight(workspaceSymbolResponse));
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        executedCommands.add(params);
        return CompletableFuture.completedFuture(null);
    }
}
