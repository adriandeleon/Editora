package com.editora.markdown;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Switching a rule off in each kind of config file, as text, and the shapes a config may take. */
class MarkdownLintConfigEditsTest {

    private static String off(String file, String text) {
        return MarkdownLintConfig.withRuleOff(file, text, "MD013");
    }

    @Test
    void jsonKeepsItsLineEndingsAndRefusesWhatIsNotAnObject() {
        assertEquals(
                "{\r\n  \"MD013\": false,\r\n  \"MD001\": true\r\n}",
                MarkdownLintConfig.withRuleOff("{\r\n  \"MD001\": true\r\n}", "MD013"));
        assertEquals(
                "{\n  \"MD013\": false,\n\"MD001\": true}",
                MarkdownLintConfig.withRuleOff("{\"MD001\": true}", "MD013"));
        assertEquals("{\n  \"MD013\": false\n}", MarkdownLintConfig.withRuleOff("{}", "MD013"));
        assertNull(MarkdownLintConfig.withRuleOff("[1]", "MD013"));
        assertEquals("{\n  \"MD013\": false\n}\n", off(null, ""), "no file name: JSON");
    }

    @Test
    void yamlSetsTheValueInPlaceOrAppendsTheRule() {
        assertEquals("MD013: false # c\nMD001: false", off(".markdownlint.yaml", "MD013:true # c\nMD001: false"));
        assertNull(off(".markdownlint.yml", "MD013:\n  line_length: 9\n"), "options below: not ours to flatten");
        assertEquals("MD013: false\n", off(".markdownlint.yaml", null));
        assertEquals("MD001: false\nMD013: false\n", off(".markdownlint.yaml", "MD001: false"));
    }

    @Test
    void cli2YamlPutsTheRuleUnderConfig() {
        assertEquals("globs: [x]\nconfig:\n  MD013: false\n", off(".markdownlint-cli2.yaml", "globs: [x]"));
        assertEquals("config:\n  MD013: false\n", off(".markdownlint-cli2.yaml", ""));
        assertNull(off(".markdownlint-cli2.yaml", "config: {MD001: false}\n"), "a flow mapping");
        assertEquals(
                "config: # c\n  # note\n\n    MD001: false\n    MD013: false\nglobs: [x]\n",
                off(
                        ".markdownlint-cli2.yaml",
                        "config: # c\n  # note\n\n    MD001: false\n    MD013: true\nglobs: [x]\n"));
        assertNull(off(".markdownlint-cli2.yaml", "config:\n  MD013:\n    line_length: 9\n"));
        assertEquals("config:\n  MD013: false\n", off(".markdownlint-cli2.yaml", "config:\n"));
    }

    @Test
    void cli2JsonPutsTheRuleIntoItsConfigObject() {
        assertEquals("{\n  \"config\": {\n    \"MD013\": false\n  }\n}\n", off(".markdownlint-cli2.jsonc", " "));
        assertNull(off(".markdownlint-cli2.jsonc", "{"));
        assertEquals(
                "{\n  \"config\": { \"MD013\": false },\"globs\": []}",
                off(".markdownlint-cli2.jsonc", "{\"globs\": []}"));
        assertEquals("{\n  \"config\": { \"MD013\": false }\n}", off(".markdownlint-cli2.jsonc", "{}"));
        String withStrings = "{\"config\": { // c {\n \"a\": \"}\\\"\", \"MD001\": {\"x\": 1}}, \"globs\": []}";
        String edited = off(".markdownlint-cli2.jsonc", withStrings);
        assertTrue(
                edited.endsWith("\"MD001\": {\"x\": 1}}, \"globs\": []}"),
                "braces in strings and comments are skipped");
        assertTrue(edited.contains("\"MD013\": false"), edited);
    }

    @Test
    void theManualLineFollowsTheFileKind() {
        assertEquals("\"MD013\": false", MarkdownLintConfig.manualLine(null, "MD013"));
        assertEquals("MD013: false", MarkdownLintConfig.manualLine("a.YML", "MD013"));
    }

    @Test
    void configShapes() {
        assertEquals(
                List.of(),
                MarkdownLintConfig.parseFile(".markdownlint-cli2.jsonc", "{\"globs\": []}")
                        .entries());
        assertTrue(MarkdownLintConfig.parse("null").valid());
        assertFalse(MarkdownLintConfig.parse("[1]").valid());
        assertTrue(MarkdownLintConfig.parseFile(null, "{}").valid());
        MarkdownLintConfig.Parsed p = MarkdownLintConfig.parse(
                "{\"default\": \"x\", \"extends\": \" \", \"MD013\": {\"a\": [1,\"b\"], \"n\": null, \"b\": true,"
                        + " \"s\": \"t\"}, \"MD001\": 5}");
        assertNull(p.defaults(), "a default that is not true or false");
        assertNull(p.extendsPath(), "a blank extends");
        assertEquals(
                List.of(new MarkdownLintConfig.Entry(
                        "MD013", true, Map.of("a", List.of("1", "b"), "b", true, "s", "t"))),
                p.entries(),
                "a null option and a number for a rule are dropped");
    }
}
