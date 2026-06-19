package com.editora.editor;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a project {@code .markdownlint.json} into the set of rule codes it turns <b>off</b>, so the
 * controller can merge it with the user's Settings before linting. Supports the common markdownlint
 * config forms: {@code {"MD013": false}} (disable a rule) and {@code {"default": false}} (disable all,
 * then re-enable the rules explicitly set {@code true}). Rule <em>aliases</em> ("line-length") and
 * per-rule option objects aren't interpreted — only the {@code MDxxx} on/off booleans (v1).
 *
 * <p>Pure (parse-only) and tolerant: any malformed JSON yields an empty set. Unit-tested directly.
 */
public final class MarkdownLintConfig {

    private MarkdownLintConfig() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The rule codes disabled by {@code json}; empty on malformed input. */
    public static Set<String> disabledRules(String json) {
        Set<String> disabled = new HashSet<>();
        if (json == null || json.isBlank()) {
            return disabled;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return disabled; // tolerant: a broken config never crashes linting
        }
        if (root == null || !root.isObject()) {
            return disabled;
        }
        JsonNode def = root.get("default");
        if (def != null && def.isBoolean() && !def.asBoolean()) {
            for (MarkdownLint.Rule r : MarkdownLint.RULES) {
                disabled.add(r.code());
            }
        }
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) root::fields) {
            String key = e.getKey().toUpperCase(Locale.ROOT);
            if (!key.matches("MD\\d+") || !e.getValue().isBoolean()) {
                continue;
            }
            if (e.getValue().asBoolean()) {
                disabled.remove(key);
            } else {
                disabled.add(key);
            }
        }
        return disabled;
    }
}
