package com.editora.markdown;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // --- C11 -------------------------------------------------------------------------------------------

    @Test
    void jsonIsReadAsJsonc() {
        assertEquals(Set.of("MD009"), MarkdownLintConfig.disabledRules("{\n  // comment\n  \"MD009\": false\n}"));
        assertEquals(Set.of("MD009"), MarkdownLintConfig.disabledRules("{ /* c */ \"MD009\": false, }"));
    }

    @Test
    void aliasesAndTagsNameRules() {
        assertEquals(
                Set.of("MD009", "MD041"),
                MarkdownLintConfig.disabledRules("{\"no-trailing-spaces\": false, \"first-line-heading\": false}"));
        assertEquals(Set.of("MD009", "MD010", "MD012"), MarkdownLintConfig.disabledRules("{\"whitespace\": false}"));
        assertEquals(Set.of(), MarkdownLintConfig.disabledRules("{\"line-length\": false}"));
    }

    @Test
    void anOptionsObjectOrASeverityEnablesTheRule() {
        Set<String> off = MarkdownLintConfig.disabledRules(
                "{\"default\": false, \"MD009\": {\"br_spaces\": 2}, \"no-hard-tabs\": true, \"MD012\": \"error\"}");
        assertFalse(off.contains("MD009"));
        assertFalse(off.contains("MD010"));
        assertFalse(off.contains("MD012"));
        assertTrue(off.contains("MD034"));
    }

    @Test
    void entriesApplyInOrderOnTopOfAnInheritedState() {
        Set<String> state = new java.util.HashSet<>(Set.of("MD034", "MD040"));
        MarkdownLintConfig.apply(MarkdownLintConfig.parse("{\"MD034\": true, \"MD009\": false}"), state);
        assertEquals(Set.of("MD040", "MD009"), state);
        MarkdownLintConfig.apply(MarkdownLintConfig.parse("{\"default\": true}"), state);
        assertEquals(Set.of(), state);
    }

    @Test
    void yamlAndCli2Files() {
        var yaml = MarkdownLintConfig.parseFile(
                ".markdownlint.yaml", "# c\ndefault: true\nMD009: false\nline-length: false\n");
        assertTrue(yaml.valid());
        Set<String> off = new java.util.HashSet<>();
        MarkdownLintConfig.apply(yaml, off);
        assertEquals(Set.of("MD009"), off);

        var cli2 = MarkdownLintConfig.parseFile(
                ".markdownlint-cli2.jsonc", "{ \"config\": { \"MD010\": false }, \"globs\": [\"**/*.md\"] }");
        off.clear();
        MarkdownLintConfig.apply(cli2, off);
        assertEquals(Set.of("MD010"), off);
        assertTrue(MarkdownLintConfig.parseFile(".markdownlint-cli2.yaml", "globs:\n  - a\n")
                .valid());
    }

    @Test
    void extendsIsReportedAndBrokenFilesAreInvalid() {
        assertEquals(
                "./base.json",
                MarkdownLintConfig.parse("{\"extends\": \"./base.json\"}").extendsPath());
        assertFalse(MarkdownLintConfig.parse("{ nope").valid());
        assertFalse(MarkdownLintConfig.parse("[1, 2]").valid());
        assertFalse(MarkdownLintConfig.parseFile(".markdownlint.yaml", "a: [unclosed")
                .valid());
        assertTrue(MarkdownLintConfig.parse("").valid());
    }

    // --- wave 2 — C11: options and explicit enables -------------------------------------------------------

    @Test
    void anOptionsObjectCarriesItsOptions() {
        var parsed = MarkdownLintConfig.parse("{\"no-trailing-spaces\": {\"br_spaces\": 0, \"strict\": true},"
                + " \"MD026\": {\"punctuation\": \".,;\"}, \"MD012\": {\"maximum\": 3}, \"MD013\": {\"line_length\": 120},"
                + " \"MD010\": {\"code_blocks\": true, \"spaces_per_tab\": 2}, \"MD041\": {\"level\": 2}}");
        MarkdownLintOptions o = MarkdownLintConfig.options(parsed, MarkdownLintOptions.DEFAULT);
        assertEquals(0, o.brSpaces());
        assertEquals(".,;", o.punctuation());
        assertEquals(3, o.maxBlankLines());
        assertTrue(o.tabsInCode());
        assertEquals(2, o.spacesPerTab());
        assertEquals(2, o.firstLineLevel());
        assertEquals(1, o.titleLevel());
    }

    @Test
    void optionsLayerOverAnInheritedConfigAndYamlCarriesThemToo() {
        MarkdownLintOptions base = MarkdownLintConfig.options(
                MarkdownLintConfig.parse("{\"MD012\": {\"maximum\": 3}, \"MD009\": {\"br_spaces\": 4}}"),
                MarkdownLintOptions.DEFAULT);
        MarkdownLintOptions o = MarkdownLintConfig.options(
                MarkdownLintConfig.parseFile(".markdownlint.yaml", "MD012:\n  maximum: 2\nMD022:\n  lines_above: 2\n"),
                base);
        assertEquals(2, o.maxBlankLines());
        assertEquals(4, o.brSpaces(), "inherited");
        assertEquals(2, o.linesAbove());
        assertEquals(MarkdownLintOptions.DEFAULT, MarkdownLintConfig.options(MarkdownLintConfig.parse("{ nope"), null));
    }

    @Test
    void rulesSwitchedOnByNameAreRemembered() {
        Set<String> off = new java.util.HashSet<>();
        Set<String> on = new java.util.HashSet<>();
        MarkdownLintConfig.apply(
                MarkdownLintConfig.parse("{\"default\": true, \"MD034\": true, \"MD009\": {\"br_spaces\": 2},"
                        + " \"MD040\": \"error\", \"MD012\": false, \"MD010\": true, \"no-hard-tabs\": false}"),
                off,
                on);
        assertEquals(Set.of("MD012", "MD010"), off);
        assertEquals(
                Set.of("MD034", "MD009", "MD040"), on, "default: true names no rule; a later false takes MD010 back");
    }
}
