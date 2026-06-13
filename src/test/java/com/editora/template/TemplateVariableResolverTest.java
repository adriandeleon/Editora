package com.editora.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure template variable resolver (fixed clock, no toolkit). */
class TemplateVariableResolverTest {

    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 6, 10, 9, 30, 0);

    private TemplateVariableResolver resolver(Map<String, String> answers) {
        return new TemplateVariableResolver(
                answers, "Ada", "MyProj", "com.acme", "Widget.java", "/src", "/src/Widget.java", CLOCK);
    }

    @Test
    void resolvesTemplateBuiltins() {
        TemplateVariableResolver r = resolver(Map.of());
        assertEquals("Ada", r.resolve("author"));
        assertEquals("MyProj", r.resolve("projectName"));
        assertEquals("com.acme", r.resolve("packageName"));
        assertEquals("Widget.java", r.resolve("fileName"));
        assertEquals("Widget", r.resolve("baseName"));
        assertEquals("java", r.resolve("extension"));
        assertEquals("2026-06-10", r.resolve("date"));
        assertEquals("2026", r.resolve("year"));
        assertEquals("09:30", r.resolve("time"));
    }

    @Test
    void answersTakePrecedenceOverBuiltins() {
        TemplateVariableResolver r = resolver(Map.of("author", "Grace", "className", "Foo"));
        assertEquals("Grace", r.resolve("author")); // wizard answer wins
        assertEquals("Foo", r.resolve("className")); // a non-built-in named var
    }

    @Test
    void cursorIsNotResolvedHere() {
        assertNull(resolver(Map.of()).resolve("cursor")); // engine rewrites ${cursor} → $0
    }

    @Test
    void delegatesStandardSnippetVariables() {
        assertEquals("Widget.java", resolver(Map.of()).resolve("TM_FILENAME"));
        assertEquals("Widget", resolver(Map.of()).resolve("TM_FILENAME_BASE"));
    }

    @Test
    void unknownNameIsNull() {
        assertNull(resolver(Map.of()).resolve("nope")); // → falls back to ${nope:default}
    }

    @Test
    void isBuiltInCoversTemplateAndSnippetVars() {
        assertTrue(TemplateVariableResolver.isBuiltIn("author"));
        assertTrue(TemplateVariableResolver.isBuiltIn("cursor"));
        assertTrue(TemplateVariableResolver.isBuiltIn("TM_FILENAME"));
        assertFalse(TemplateVariableResolver.isBuiltIn("className")); // user-supplied
    }
}
