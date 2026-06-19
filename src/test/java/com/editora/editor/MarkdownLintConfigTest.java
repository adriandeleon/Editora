package com.editora.editor;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the {@code .markdownlint.json} parser. */
class MarkdownLintConfigTest {

    @Test
    void disablesAnExplicitFalseRule() {
        Set<String> off = MarkdownLintConfig.disabledRules("{\"MD013\": false, \"MD009\": true}");
        assertTrue(off.contains("MD013"));
        assertFalse(off.contains("MD009"));
    }

    @Test
    void defaultFalseDisablesAllThenReEnables() {
        Set<String> off = MarkdownLintConfig.disabledRules("{\"default\": false, \"MD009\": true}");
        assertTrue(off.contains("MD040"));
        assertFalse(off.contains("MD009"));
    }

    @Test
    void caseInsensitiveKeys() {
        assertTrue(MarkdownLintConfig.disabledRules("{\"md040\": false}").contains("MD040"));
    }

    @Test
    void toleratesMalformedOrEmpty() {
        assertTrue(MarkdownLintConfig.disabledRules("not json").isEmpty());
        assertTrue(MarkdownLintConfig.disabledRules("").isEmpty());
        assertTrue(MarkdownLintConfig.disabledRules(null).isEmpty());
        assertTrue(MarkdownLintConfig.disabledRules("[1,2,3]").isEmpty());
    }
}
