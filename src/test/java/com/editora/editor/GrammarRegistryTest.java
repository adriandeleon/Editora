package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class GrammarRegistryTest {

    @Test
    void forLanguageNameResolvesBundledLanguages() {
        assertNotNull(GrammarRegistry.shared().forLanguageName("java"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("python"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("csharp"));
    }

    @Test
    void forLanguageNameIsCaseInsensitive() {
        assertNotNull(GrammarRegistry.shared().forLanguageName("Java"));
    }

    @Test
    void forLanguageNameUnknownOrNullIsNull() {
        assertNull(GrammarRegistry.shared().forLanguageName("klingon"));
        assertNull(GrammarRegistry.shared().forLanguageName(null));
        assertNull(GrammarRegistry.shared().forLanguageName("plaintext"));
    }

    @Test
    void availableLanguageNamesListsBundledOnly() {
        Set<String> names = GrammarRegistry.shared().availableLanguageNames();
        assertTrue(names.contains("java"));
        assertTrue(names.contains("markdown"));
        assertTrue(names.contains("csharp"));
        assertFalse(names.contains("plaintext"));
    }
}
