package com.editora.editor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every {@code "include": "#name"} in a bundled grammar must name a rule the grammar defines. tm4e skips an
 * include it cannot resolve without a word (Editora silences tm4e's warnings, see {@code App}), so a missing
 * rule is a silent hole: the shell grammar's {@code #arithmetic_dollar} was missing, which made every
 * {@code $(( … ))} a command substitution and every {@code $((x << 2))} a here-doc that ran to the end of the
 * file.
 */
class BundledGrammarIncludesTest {

    /**
     * Dangling includes that come from the upstream grammars as published and lose nothing visible today. A new
     * grammar or update must not add to this list; the shell entries were fixed and must not come back.
     */
    private static final Map<String, Set<String>> KNOWN_UPSTREAM = Map.of(
            "c", Set.of("string_context"),
            "cpp", Set.of("preprocessor_number_literal", "string_escaped_char"),
            "graphql", Set.of("graphql-object-type", "literal-quasi-embedded"),
            "kotlin", Set.of("type-constraint"));

    private static void collect(JsonNode node, Set<String> defined, Set<String> included) {
        if (node.isObject()) {
            JsonNode repo = node.get("repository");
            if (repo != null && repo.isObject()) {
                repo.fieldNames().forEachRemaining(defined::add);
            }
            JsonNode include = node.get("include");
            if (include != null && include.isTextual() && include.asText().startsWith("#")) {
                included.add(include.asText().substring(1));
            }
            node.elements().forEachRemaining(child -> collect(child, defined, included));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, defined, included));
        }
    }

    @Test
    void everyLocalIncludeNamesARuleTheGrammarDefines() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> names = new ArrayList<>(GrammarRegistry.shared().availableLanguageNames());
        names.add("html-derivative"); // embedded only, so not a pickable language, but bundled all the same
        List<String> problems = new ArrayList<>();
        for (String name : names) {
            String resource = "/com/editora/grammars/" + name + ".tmLanguage.json";
            try (InputStream in = GrammarRegistry.class.getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                Set<String> defined = new HashSet<>();
                Set<String> included = new TreeSet<>();
                collect(json.readTree(in), defined, included);
                included.removeAll(defined);
                included.removeAll(KNOWN_UPSTREAM.getOrDefault(name, Set.of()));
                if (!included.isEmpty()) {
                    problems.add(name + ": " + included);
                }
            }
        }
        assertEquals(List.of(), problems, "includes of rules the grammar does not define");
    }
}
