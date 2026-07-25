package com.editora.lsp;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client capabilities Editora declares at {@code initialize}.
 *
 * <p>This is load-bearing in a way ordinary code is not: a capability decides what the <b>server</b> will
 * agree to do, so dropping one doesn't break a build or fail a test — the feature just quietly stops
 * working, often only on one server. Three separate features have already died exactly that way:
 *
 * <ul>
 *   <li><b>#674</b> — {@code new SignatureHelpCapabilities(sigInfo, true)} looks like "context support" but
 *       the second argument is <b>dynamicRegistration</b>. Setting it made jdtls stop advertising
 *       {@code signatureHelpProvider} statically, and signature help died outright.</li>
 *   <li><b>#676</b> — jdtls's {@code isResourceOperationSupported()} is <b>all-or-nothing</b>: it emits a
 *       {@code RenameFile} only when the client declares Create AND Rename AND Delete. Declaring just Rename
 *       made a class rename silently leave {@code OldName.java} on disk.</li>
 *   <li><b>#445/#410</b> — {@code resolveSupport(additionalTextEdits)} is what makes Pyright emit an
 *       auto-import's {@code import} line at all.</li>
 * </ul>
 *
 * <p>So these assertions are deliberately literal about the shapes that bit us, not a restatement of the
 * code. Each names the failure it guards.
 */
class ClientCapabilitiesTest {

    private static final ClientCapabilities CAPS = LanguageServerSession.clientCapabilities();

    // --- #674: the two-arg ctor trap ---------------------------------------------------------------

    /**
     * Context support must be on, and dynamic registration must NOT be — the two are adjacent in the API and
     * setting the wrong one made jdtls stop advertising signature help entirely.
     */
    @Test
    void signatureHelpDeclaresContextSupportAndNotDynamicRegistration() {
        var sig = CAPS.getTextDocument().getSignatureHelp();
        assertNotNull(sig, "signature help capability missing");
        assertTrue(Boolean.TRUE.equals(sig.getContextSupport()), "contextSupport must be declared (#674)");
        assertFalse(
                Boolean.TRUE.equals(sig.getDynamicRegistration()),
                "dynamicRegistration must stay off — jdtls then registers dynamically, which we don't handle (#674)");
        assertTrue(
                Boolean.TRUE.equals(sig.getSignatureInformation().getActiveParameterSupport()),
                "per-signature activeParameter is what keeps the highlighted parameter correct");
        assertTrue(
                Boolean.TRUE.equals(
                        sig.getSignatureInformation().getParameterInformation().getLabelOffsetSupport()),
                "label offsets are what let the popup bold the exact parameter span");
    }

    // --- #676: resource operations are all-or-nothing ----------------------------------------------

    /** All three kinds must be declared or jdtls strips the file move from a class rename. */
    @Test
    void workspaceEditDeclaresAllThreeResourceOperations() {
        var wsEdit = CAPS.getWorkspace().getWorkspaceEdit();
        assertNotNull(wsEdit, "workspaceEdit capability missing");
        assertTrue(Boolean.TRUE.equals(wsEdit.getDocumentChanges()), "documentChanges is the modern edit shape");
        var ops = wsEdit.getResourceOperations();
        assertNotNull(ops, "resourceOperations missing — jdtls will strip the RenameFile (#676)");
        assertTrue(ops.contains(ResourceOperationKind.Create), "Create missing — the gate is all-or-nothing");
        assertTrue(ops.contains(ResourceOperationKind.Rename), "Rename missing");
        assertTrue(ops.contains(ResourceOperationKind.Delete), "Delete missing — the gate is all-or-nothing");
    }

    /** We must answer {@code workspace/applyEdit} — it is how a server-side quick fix lands its changes. */
    @Test
    void weAcceptServerInitiatedEdits() {
        assertTrue(Boolean.TRUE.equals(CAPS.getWorkspace().getApplyEdit()), "applyEdit must be declared (#670)");
    }

    // --- completion ---------------------------------------------------------------------------------

    /** Snippet support + resolving additionalTextEdits: the latter is what makes Pyright emit auto-imports. */
    @Test
    void completionDeclaresSnippetAndAdditionalTextEditResolve() {
        var item = CAPS.getTextDocument().getCompletion().getCompletionItem();
        assertTrue(Boolean.TRUE.equals(item.getSnippetSupport()), "snippetSupport missing");
        var resolve = item.getResolveSupport();
        assertNotNull(resolve, "resolveSupport missing — no auto-imports from Pyright (#410/#445)");
        assertTrue(
                resolve.getProperties().contains("additionalTextEdits"),
                "additionalTextEdits must be resolvable — that is the auto-import payload");
    }

    // --- the features whose absence is invisible ----------------------------------------------------

    @Test
    void theOptInFeatureCapabilitiesAreAllDeclared() {
        var td = CAPS.getTextDocument();
        assertNotNull(td.getInlayHint(), "inlay hints (#681)");
        assertNotNull(td.getDocumentHighlight(), "occurrences (#675)");
        assertNotNull(td.getCallHierarchy(), "call hierarchy (#682)");
        assertNotNull(td.getTypeHierarchy(), "type hierarchy (#682)");
        assertNotNull(td.getDiagnostic(), "pull diagnostics — html/css/json deliver diagnostics only this way");
        assertNotNull(td.getCodeAction(), "code actions (#670)");
        assertTrue(
                Boolean.TRUE.equals(td.getRename().getPrepareSupport()),
                "prepareRename is what validates the position and supplies the placeholder (#676)");
    }

    /** Literal + resolve + data support are jointly what make jdtls quick fixes work. */
    @Test
    void codeActionDeclaresLiteralResolveAndDataSupport() {
        var ca = CAPS.getTextDocument().getCodeAction();
        assertNotNull(ca.getCodeActionLiteralSupport(), "without literal support servers return bare Commands");
        assertTrue(
                ca.getCodeActionLiteralSupport()
                        .getCodeActionKind()
                        .getValueSet()
                        .contains("quickfix"),
                "quickfix kind missing");
        assertNotNull(ca.getResolveSupport(), "resolveSupport lets a server defer the expensive edit");
        assertTrue(Boolean.TRUE.equals(ca.getDataSupport()), "dataSupport carries the server's opaque payload");
        assertTrue(Boolean.TRUE.equals(ca.getIsPreferredSupport()), "isPreferred drives the picker's ordering");
    }

    /** Semantic tokens: a legend we understand, and both request forms (jdtls has range=false, full=true). */
    @Test
    void semanticTokensDeclareALegendAndBothRequestForms() {
        var st = CAPS.getTextDocument().getSemanticTokens();
        assertNotNull(st, "semantic tokens capability missing");
        assertFalse(st.getTokenTypes().isEmpty(), "an empty legend means nothing can ever be decoded");
        assertFalse(st.getTokenModifiers().isEmpty(), "modifiers carry deprecated/static/readonly");
        assertTrue(st.getTokenTypes().contains("parameter"), "parameter is the distinction TextMate cannot make");
        assertNotNull(st.getRequests().getFull(), "full requests — the only form jdtls offers");
        assertNotNull(st.getRequests().getRange(), "range requests bound cost on large files");
    }

    /** Workspace-side: configuration answers (Pyright auto-import), watched files (#677), symbol search. */
    @Test
    void workspaceCapabilitiesCoverConfigurationWatchedFilesAndSymbols() {
        var ws = CAPS.getWorkspace();
        assertTrue(
                Boolean.TRUE.equals(ws.getConfiguration()),
                "without this Pyright never asks for autoImportCompletions and keeps its off default");
        assertNotNull(ws.getDidChangeWatchedFiles(), "external changes leave the project model stale (#677)");
        assertNotNull(ws.getSymbol(), "workspace/symbol backs Go to Symbol");
        assertNotNull(ws.getDidChangeConfiguration(), "we push settings after initialize");
    }

    /** {@code $/progress} is what replaced the guessed spinner with real server progress (#683). */
    @Test
    void windowDeclaresWorkDoneProgress() {
        assertNotNull(CAPS.getWindow(), "window capabilities missing");
        assertTrue(Boolean.TRUE.equals(CAPS.getWindow().getWorkDoneProgress()), "$/progress (#683)");
    }

    /** didSave must be declared or a save-triggered re-analysis never happens. */
    @Test
    void synchronizationDeclaresDidSave() {
        var sync = CAPS.getTextDocument().getSynchronization();
        assertNotNull(sync);
        assertTrue(Boolean.TRUE.equals(sync.getDidSave()), "didSave drives save-time re-analysis");
    }

    /** Capabilities are rebuilt per session; equal content each time, never a shared mutable instance. */
    @Test
    void capabilitiesAreBuiltFreshPerCall() {
        var a = LanguageServerSession.clientCapabilities();
        var b = LanguageServerSession.clientCapabilities();
        assertEquals(
                a.getTextDocument().getSemanticTokens().getTokenTypes(),
                b.getTextDocument().getSemanticTokens().getTokenTypes());
        assertTrue(a != b, "a shared instance could be mutated by one session and affect another");
    }
}
