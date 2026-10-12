package com.editora.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses a project markdownlint configuration into the set of rule codes it turns <b>off</b>, so the
 * controller can merge it with the user's Settings before linting.
 *
 * <p>Understood, as markdownlint does:
 *
 * <ul>
 *   <li>the files in {@link #FILE_NAMES} — JSON is read as JSONC (comments, trailing commas), {@code .yaml}/
 *       {@code .yml} as YAML, and a {@code .markdownlint-cli2.*} file through its {@code config} object;
 *   <li>{@code "default": false} (all rules off) / {@code true} (all on), then each entry in order;
 *   <li>a rule named by code, alias or tag ({@link MarkdownLint#resolve}); {@code false} turns it off; {@code true},
 *       an options object or a severity string turns it on;
 *   <li>{@code "extends": "path"} — reported in {@link Parsed#extendsPath} for the caller to load first.
 * </ul>
 *
 * <p>Rule <em>options</em> ({@code line_length}, {@code br_spaces}…) are not interpreted — an options object only
 * counts as "enabled". Pure (parse-only) and tolerant: a file that cannot be parsed is reported as
 * {@link Parsed#valid() invalid} and changes nothing. Unit-tested directly.
 */
public final class MarkdownLintConfig {

    private MarkdownLintConfig() {}

    /** The config file names looked for in a directory, in markdownlint's order of precedence. */
    public static final List<String> FILE_NAMES = List.of(
            ".markdownlint-cli2.jsonc",
            ".markdownlint-cli2.yaml",
            ".markdownlint.jsonc",
            ".markdownlint.json",
            ".markdownlint.yaml",
            ".markdownlint.yml");

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();
    private static final ObjectMapper YAML = new YAMLMapper();

    /** One rule entry of a config: the name as written and whether it switches the rule(s) on. */
    public record Entry(String name, boolean on) {}

    /**
     * A parsed config.
     *
     * @param valid false when the text could not be parsed (or is not an object)
     * @param defaults the {@code default} entry, or {@code null} when absent
     * @param entries the rule entries, in file order
     * @param extendsPath the {@code extends} entry, or {@code null}
     */
    public record Parsed(boolean valid, Boolean defaults, List<Entry> entries, String extendsPath) {
        static final Parsed INVALID = new Parsed(false, null, List.of(), null);
        static final Parsed EMPTY = new Parsed(true, null, List.of(), null);
    }

    /** Parses JSON/JSONC {@code text}. */
    public static Parsed parse(String text) {
        return parse(text, JSON, false);
    }

    /** Parses the content of a config file called {@code fileName} (one of {@link #FILE_NAMES}). */
    public static Parsed parseFile(String fileName, String text) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean yaml = name.endsWith(".yaml") || name.endsWith(".yml");
        return parse(text, yaml ? YAML : JSON, name.startsWith(".markdownlint-cli2."));
    }

    private static Parsed parse(String text, ObjectMapper mapper, boolean cli2) {
        if (text == null || text.isBlank()) {
            return Parsed.EMPTY;
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            return Parsed.INVALID; // tolerant: a broken config never crashes linting
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Parsed.EMPTY;
        }
        if (!root.isObject()) {
            return Parsed.INVALID;
        }
        if (cli2) {
            root = root.get("config");
            if (root == null || !root.isObject()) {
                return Parsed.EMPTY; // a cli2 file without rule configuration
            }
        }
        Boolean defaults = null;
        String extendsPath = null;
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) root::fields) {
            String key = e.getKey();
            JsonNode v = e.getValue();
            if (key.equalsIgnoreCase("default")) {
                if (v.isBoolean()) {
                    defaults = v.asBoolean();
                }
            } else if (key.equalsIgnoreCase("extends")) {
                if (v.isTextual() && !v.asText().isBlank()) {
                    extendsPath = v.asText();
                }
            } else if (v.isBoolean()) {
                entries.add(new Entry(key, v.asBoolean()));
            } else if (v.isObject() || v.isTextual()) {
                entries.add(new Entry(key, true)); // options or a severity: the rule is in use
            }
        }
        return new Parsed(true, defaults, List.copyOf(entries), extendsPath);
    }

    /** Applies {@code config} on top of {@code disabled} (the state inherited from an extended file or none). */
    public static void apply(Parsed config, Set<String> disabled) {
        if (config == null || !config.valid()) {
            return;
        }
        if (config.defaults() != null) {
            if (config.defaults()) {
                disabled.clear();
            } else {
                disabled.addAll(MarkdownLint.CODES);
            }
        }
        for (Entry e : config.entries()) {
            Set<String> codes = MarkdownLint.resolve(e.name());
            if (e.on()) {
                disabled.removeAll(codes);
            } else {
                disabled.addAll(codes);
            }
        }
    }

    /** The rule codes disabled by JSON/JSONC {@code json}; empty on malformed input. */
    public static Set<String> disabledRules(String json) {
        Set<String> disabled = new HashSet<>();
        apply(parse(json), disabled);
        return disabled;
    }
}
