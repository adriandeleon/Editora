package com.editora.ui;

import java.nio.file.Path;

import com.editora.lsp.StartRefusal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the status line and the Settings card say when the TypeScript server cannot start (Node review B3, E4). */
class TypeScriptStartMessagesTest {

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void aRefusalShowsTheServersOwnWords() {
        String m = LspStartMessages.refused(
                "typescript-language-server",
                new StartRefusal(StartRefusal.Kind.SERVER_REFUSED, "Could not find a valid TypeScript installation."),
                true);
        assertTrue(m.contains("typescript-language-server"), m);
        assertTrue(m.contains("Could not find a valid TypeScript installation."), m);
    }

    @Test
    void typescriptSevenIsNamedAndTheFixIsInstallNeverNpmGlobal() {
        String m = LspStartMessages.refused(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.NO_TYPESCRIPT, "7.0.2"), true);
        assertTrue(m.contains("7.0.2"), m);
        assertFalse(m.contains("npm install -g"), m);
        // the action first, short enough for the status line at 1300 px (JS review B113)
        assertTrue(m.startsWith("Install TypeScript 5.9 (Settings → LSP)"), m);
        assertTrue(m.length() <= 90, m.length() + ": " + m);
        String none = LspStartMessages.refused(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.NO_TYPESCRIPT, ""), true);
        assertFalse(none.contains("npm install -g"), none);
        assertTrue(none.startsWith("Install TypeScript 5.9"), none);
        assertTrue(none.length() <= 90, none.length() + ": " + none);
        String untrusted = LspStartMessages.refused(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.UNTRUSTED_TYPESCRIPT, "/w"), true);
        assertFalse(untrusted.contains("{0}"), untrusted);
        assertFalse(untrusted.contains("''"), untrusted);
        assertTrue(untrusted.contains("Trust"), untrusted);
    }

    /** With No Project there is nothing to trust: the message says what does work (JS review B111). */
    @Test
    void withNoProjectTheUntrustedMessageDoesNotPointAtTrust() {
        String m = LspStartMessages.refused(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.UNTRUSTED_TYPESCRIPT, "/w"), false);
        assertFalse(m.contains("Trust This Project"), m);
        assertTrue(m.contains("only a project can be trusted"), m);
        assertTrue(m.contains("Install…"), m);
    }

    @Test
    void theSettingsLineNamesTheTypescriptItRunsOrWhyThereIsNone() {
        Path ts = Path.of("/g/typescript/lib/tsserver.js");
        var found = TypeScriptSdkStatus.shown(new com.editora.lsp.TypeScriptServer.Sdk(ts, "5.9.3", false, "5.9.3"));
        assertEquals(TypeScriptSdkStatus.Look.FOUND, found.look());
        assertEquals("5.9.3 — " + ts, found.detail());
        var own = TypeScriptSdkStatus.shown(new com.editora.lsp.TypeScriptServer.Sdk(ts, "5.9.3", true, "7.0.2"));
        assertEquals(TypeScriptSdkStatus.Look.FOUND, own.look());
        assertTrue(own.detail().startsWith("Editora's own, 5.9.3"), own.detail());
        // TypeScript 6: amber, it leaves @types/node out (JS review B101)
        var six = TypeScriptSdkStatus.shown(new com.editora.lsp.TypeScriptServer.Sdk(ts, "6.0.3", false, "6.0.3"));
        assertEquals(TypeScriptSdkStatus.Look.WARNING, six.look());
        assertTrue(six.text().contains("6.0.3"), six.text());
        // TypeScript 7: found but unusable — not "not found" (B113)
        var seven = TypeScriptSdkStatus.shown(new com.editora.lsp.TypeScriptServer.Sdk(null, "", false, "7.0.2"));
        assertEquals(TypeScriptSdkStatus.Look.PROBLEM, seven.look());
        assertEquals("TypeScript 7.0.2 has no tsserver", seven.text());
        assertEquals(
                TypeScriptSdkStatus.Look.MISSING,
                TypeScriptSdkStatus.shown(new com.editora.lsp.TypeScriptServer.Sdk(null, "", false, ""))
                        .look());
    }
}
