package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code client/registerCapability} as real servers send it: the options arrive as raw JSON. Each test here
 * is a feature that advertising dynamic registration silently switched off — the decode used a plain gson,
 * or the options object was reduced to a Boolean.
 */
class LspDynamicRegistrationTest {

    private FakeLanguageServer fake;

    private LanguageServerSession session(ServerCapabilities caps) {
        fake = new FakeLanguageServer();
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of("pom.xml"));
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);
        s.attachForTest(fake, caps);
        return s;
    }

    /** A registration exactly as LSP4J hands it over: {@code registerOptions} is a gson element. */
    private static Registration registration(String id, String method, String optionsJson) {
        return new Registration(id, method, optionsJson == null ? null : JsonParser.parseString(optionsJson));
    }

    /**
     * {@code SemanticTokensWithRegistrationOptions.range} and {@code .full} are {@code Either}s that only
     * LSP4J's own gson can read. A plain gson threw on {@code "range": true}, which aborted the whole
     * registration batch and left tinymist without semantic tokens.
     */
    @Test
    void semanticTokenOptionsKeepTheirEitherFields() {
        var s = session(new ServerCapabilities());

        s.registerCapability(new RegistrationParams(List.of(registration(
                "st",
                "textDocument/semanticTokens",
                "{\"legend\":{\"tokenTypes\":[\"type\",\"variable\"],\"tokenModifiers\":[\"static\"]},"
                        + "\"range\":true,\"full\":{\"delta\":true}}"))));

        var provider = s.capabilities().getSemanticTokensProvider();
        assertNotNull(provider, "the registration must turn semantic tokens on");
        assertEquals(List.of("type", "variable"), provider.getLegend().getTokenTypes());
        assertTrue(provider.getRange().isLeft() && provider.getRange().getLeft(), "range support was dropped");
        assertTrue(provider.getFull().isRight(), "full must keep its options object");
        assertTrue(provider.getFull().getRight().getDelta(), "delta support was dropped");
        assertNotNull(LspManager.semanticTokensProvider(s.capabilities()), "the manager's gate must see it");
    }

    /** One unusable registration must not take the rest of the batch (or the response) down with it. */
    @Test
    void aMalformedRegistrationDoesNotAbortTheBatch() throws Exception {
        var s = session(new ServerCapabilities());
        List<String> refreshed = new ArrayList<>();
        s.setOnRefresh(refreshed::add);

        var answered = s.registerCapability(new RegistrationParams(List.of(
                registration("bad", "textDocument/semanticTokens", "{\"legend\":7}"),
                registration("sig", "textDocument/signatureHelp", "{\"triggerCharacters\":[\"(\"]}"))));

        assertFalse(answered.isCompletedExceptionally(), "the server must still get a normal reply");
        assertNotNull(s.capabilities().getSignatureHelpProvider(), "the valid registration was lost");
        assertEquals(List.of("("), s.capabilities().getSignatureHelpProvider().getTriggerCharacters());
        assertEquals(List.of("capabilities"), refreshed);
    }

    /**
     * jdtls registers rename with {@code prepareProvider: true}. Storing the registration as a Boolean
     * dropped it, so the rename prompt stopped validating the position and lost the server's placeholder.
     */
    @Test
    void renameRegistrationKeepsPrepareProvider() {
        var s = session(new ServerCapabilities());

        s.registerCapability(new RegistrationParams(
                List.of(registration("rn", "textDocument/rename", "{\"prepareProvider\":true}"))));

        var rename = s.capabilities().getRenameProvider();
        assertTrue(rename.isRight(), "the options object must be kept");
        assertTrue(rename.getRight().getPrepareProvider());
        assertTrue(LspManager.renameProvider(s.capabilities()));

        s.unregisterCapability(new UnregistrationParams(List.of(new Unregistration("rn", "textDocument/rename"))));
        assertFalse(LspManager.renameProvider(s.capabilities()), "unregistering must switch rename off again");
    }

    /** A registration without options is still a plain "on". */
    @Test
    void renameRegistrationWithoutOptionsIsEnabled() {
        var s = session(new ServerCapabilities());
        s.registerCapability(new RegistrationParams(List.of(registration("rn", "textDocument/rename", null))));
        assertTrue(LspManager.renameProvider(s.capabilities()));
    }

    @Test
    void codeActionRegistrationKeepsItsOptions() {
        var s = session(new ServerCapabilities());

        s.registerCapability(new RegistrationParams(List.of(registration(
                "ca", "textDocument/codeAction", "{\"codeActionKinds\":[\"quickfix\"],\"resolveProvider\":true}"))));

        var provider = s.capabilities().getCodeActionProvider();
        assertTrue(provider.isRight(), "the options object must be kept");
        assertEquals(List.of("quickfix"), provider.getRight().getCodeActionKinds());
        assertTrue(provider.getRight().getResolveProvider());
        assertTrue(LspManager.codeActionProvider(s.capabilities()));
    }

    /** An unregistration naming neither a known id nor a method used to throw on a null switch selector. */
    @Test
    void anUnregistrationWithoutAMethodIsIgnored() {
        var s = session(new ServerCapabilities());
        var ghost = new Unregistration(); // as gson builds it from {"id":"ghost"}: no method
        ghost.setId("ghost");
        var answered = s.unregisterCapability(new UnregistrationParams(List.of(ghost)));
        assertFalse(answered.isCompletedExceptionally());
    }

    // --- jdtls on-type formatting ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Object onTypeEnabled(Object settings) {
        Map<String, Object> java = (Map<String, Object>) ((Map<String, Object>) settings).get("java");
        Map<String, Object> format = (Map<String, Object>) java.get("format");
        return ((Map<String, Object>) format.get("onType")).get("enabled");
    }

    /**
     * jdtls registers {@code textDocument/onTypeFormatting} only while {@code java.format.onType.enabled}
     * is set, so the preference has to follow the editor's setting — in the pushed configuration and, for
     * a running server, again when the setting flips.
     */
    @Test
    void javaOnTypeFormattingPreferenceFollowsTheSetting() {
        assertEquals(Boolean.FALSE, onTypeEnabled(LanguageServerSession.defaultSettings(false)));
        assertEquals(Boolean.TRUE, onTypeEnabled(LanguageServerSession.defaultSettings(true)));

        var s = session(new ServerCapabilities());
        int before = fake.configurations.size();
        s.setJavaOnTypeFormatting(true);

        assertEquals(before + 1, fake.configurations.size(), "a running jdtls must be told the setting changed");
        assertEquals(
                Boolean.TRUE,
                onTypeEnabled(FakeLanguageServer.last(fake.configurations).getSettings()));

        s.setJavaOnTypeFormatting(true);
        assertEquals(before + 1, fake.configurations.size(), "an unchanged setting sends nothing");
    }

    @Test
    void javaInitializationOptionsCarryTheOnTypePreference() {
        assertEquals(
                Boolean.TRUE,
                onTypeEnabled(LspManager.javaInitOptions(List.of(), true).get("settings")));
        assertEquals(
                Boolean.FALSE,
                onTypeEnabled(LspManager.javaInitOptions(List.of()).get("settings")));
    }
}
