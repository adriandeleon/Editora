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
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
final class FakeLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

    // --- recorded requests -------------------------------------------------------------------------
    final List<DidOpenTextDocumentParams> opened = new ArrayList<>();
    final List<DidChangeTextDocumentParams> changed = new ArrayList<>();
    final List<DidSaveTextDocumentParams> saved = new ArrayList<>();
    final List<DidCloseTextDocumentParams> closed = new ArrayList<>();
    final List<SignatureHelpParams> signatureHelps = new ArrayList<>();
    final List<InlayHintParams> inlayHints = new ArrayList<>();
    final List<SemanticTokensRangeParams> semanticRanges = new ArrayList<>();
    final List<SemanticTokensParams> semanticFulls = new ArrayList<>();
    final List<CompletionParams> completions = new ArrayList<>();
    final List<HoverParams> hovers = new ArrayList<>();
    final List<DocumentHighlightParams> highlights = new ArrayList<>();
    final List<CodeActionParams> codeActions = new ArrayList<>();
    final List<DocumentFormattingParams> formattings = new ArrayList<>();
    final List<DocumentRangeFormattingParams> rangeFormattings = new ArrayList<>();
    final List<ExecuteCommandParams> executedCommands = new ArrayList<>();
    final List<DidChangeConfigurationParams> configurations = new ArrayList<>();
    final List<DidChangeWatchedFilesParams> watchedFiles = new ArrayList<>();

    // --- canned responses --------------------------------------------------------------------------
    SignatureHelp signatureHelpResponse;
    List<InlayHint> inlayHintResponse = List.of();
    SemanticTokens semanticTokensResponse;
    List<TextEdit> formattingResponse = List.of();

    /** The last recorded element of {@code list}, or null when nothing was recorded. */
    static <T> T last(List<T> list) {
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
        return CompletableFuture.completedFuture(formattingResponse);
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
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        executedCommands.add(params);
        return CompletableFuture.completedFuture(null);
    }
}
