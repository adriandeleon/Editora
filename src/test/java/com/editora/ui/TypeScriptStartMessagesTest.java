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
        String m = LspCoordinator.startRefusedMessage(
                "typescript-language-server",
                new StartRefusal(StartRefusal.Kind.SERVER_REFUSED, "Could not find a valid TypeScript installation."));
        assertTrue(m.contains("typescript-language-server"), m);
        assertTrue(m.contains("Could not find a valid TypeScript installation."), m);
    }

    @Test
    void typescriptSevenIsNamedAndTheFixIsGiven() {
        String m = LspCoordinator.startRefusedMessage(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.NO_TYPESCRIPT, "7.0.2"));
        assertTrue(m.contains("7.0.2"), m);
        assertTrue(m.contains("typescript@6"), m);
        String none = LspCoordinator.startRefusedMessage(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.NO_TYPESCRIPT, ""));
        assertTrue(none.contains("typescript@6"), none);
        String untrusted = LspCoordinator.startRefusedMessage(
                "typescript-language-server", new StartRefusal(StartRefusal.Kind.UNTRUSTED_TYPESCRIPT, "/w"));
        assertFalse(untrusted.contains("{0}"), untrusted);
        assertFalse(untrusted.contains("''"), untrusted);
    }

    @Test
    void theSettingsLineNamesTheTypescriptItRunsOrWhyThereIsNone() {
        var found = TypeScriptSdkStatus.shown(Path.of("/g/typescript/lib/tsserver.js"), "6.0.3", "6.0.3");
        assertTrue(found.found());
        assertEquals("6.0.3 — " + Path.of("/g/typescript/lib/tsserver.js"), found.detail());
        var seven = TypeScriptSdkStatus.shown(null, "", "7.0.2");
        assertFalse(seven.found());
        assertTrue(seven.tool().contains("7.0.2"), seven.tool());
        assertFalse(TypeScriptSdkStatus.shown(null, "", "").found());
    }
}
