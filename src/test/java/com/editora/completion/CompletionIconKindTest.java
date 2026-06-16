package com.editora.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompletionIconKindTest {

    @Test
    void everyKindResolvesToANonNullKey() {
        for (CompletionIconKind k : CompletionIconKind.values()) {
            assertNotNull(k.iconKey(), k + " has a null icon key");
        }
    }

    @Test
    void relatedKindsShareAGlyph() {
        assertEquals("method", CompletionIconKind.METHOD.iconKey());
        assertEquals("method", CompletionIconKind.FUNCTION.iconKey());
        assertEquals("method", CompletionIconKind.CONSTRUCTOR.iconKey());
        assertEquals("field", CompletionIconKind.FIELD.iconKey());
        assertEquals("field", CompletionIconKind.PROPERTY.iconKey());
        assertEquals("field", CompletionIconKind.CONSTANT.iconKey());
        assertEquals("variable", CompletionIconKind.VARIABLE.iconKey());
        assertEquals("class", CompletionIconKind.CLASS.iconKey());
        assertEquals("class", CompletionIconKind.STRUCT.iconKey());
        assertEquals("interface", CompletionIconKind.INTERFACE.iconKey());
        assertEquals("enum", CompletionIconKind.ENUM.iconKey());
        assertEquals("keyword", CompletionIconKind.KEYWORD.iconKey());
        assertEquals("snippet", CompletionIconKind.SNIPPET.iconKey());
        assertEquals("text", CompletionIconKind.TEXT.iconKey());
        assertEquals("other", CompletionIconKind.OTHER.iconKey());
    }
}
