package com.editora.lsp;

import java.util.List;
import java.util.Set;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LspManager}'s pure helper that extracts completion trigger characters. */
class LspManagerTest {

    @Test
    void extractsTriggerCharactersFromCapabilities() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of(".", "<", "/")));
        assertEquals(Set.of('.', '<', '/'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void usesOnlyTheFirstCharOfMultiCharTriggers() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of("::", "->")));
        assertEquals(Set.of(':', '-'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void emptyWhenNoCompletionProviderOrNoTriggers() {
        assertTrue(LspManager.triggerCharsOf(null).isEmpty());
        assertTrue(LspManager.triggerCharsOf(new ServerCapabilities()).isEmpty()); // no completionProvider
        ServerCapabilities noTriggers = new ServerCapabilities();
        noTriggers.setCompletionProvider(new CompletionOptions()); // completionProvider, null triggerCharacters
        assertTrue(LspManager.triggerCharsOf(noTriggers).isEmpty());
    }
}
