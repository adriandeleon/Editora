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
 * <p>An options object switches its rule on and carries the rule's options; the ones this linter honours are
 * listed on {@link MarkdownLintOptions}, the rest are ignored. Pure (parse-only) and tolerant: a file that cannot be parsed is reported as
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

    /**
     * One rule entry of a config: the name as written, whether it switches the rule(s) on, and the options it
     * carries (option name → {@code Number}/{@code Boolean}/{@code String}; empty for a plain on/off entry).
     */
    public record Entry(String name, boolean on, Map<String, Object> options) {
        public Entry(String name, boolean on) {
            this(name, on, Map.of());
        }
    }

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
            } else if (v.isObject()) {
                Map<String, Object> options = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> o : (Iterable<Map.Entry<String, JsonNode>>) v::fields) {
                    JsonNode ov = o.getValue();
                    if (ov.isNumber()) {
                        options.put(o.getKey(), ov.numberValue());
                    } else if (ov.isBoolean()) {
                        options.put(o.getKey(), ov.asBoolean());
                    } else if (ov.isTextual()) {
                        options.put(o.getKey(), ov.asText());
                    } else if (ov.isArray()) {
                        List<String> items = new ArrayList<>();
                        ov.forEach(item -> items.add(item.asText()));
                        options.put(o.getKey(), items);
                    }
                }
                entries.add(new Entry(key, true, options)); // an options object: the rule is in use
            } else if (v.isTextual()) {
                entries.add(new Entry(key, true, Map.of("severity", v.asText()))); // a severity: in use
            }
        }
        return new Parsed(true, defaults, List.copyOf(entries), extendsPath);
    }

    /** Applies {@code config} on top of {@code disabled} (the state inherited from an extended file or none). */
    public static void apply(Parsed config, Set<String> disabled) {
        apply(config, disabled, new HashSet<>());
    }

    /**
     * As {@link #apply(Parsed, Set)}, also keeping in {@code enabled} the rules the config switches on <em>by
     * name</em> ({@code "MD009": true}, an options object, a severity) — those a project insists on, as opposed
     * to the ones that are merely on by default.
     */
    public static void apply(Parsed config, Set<String> disabled, Set<String> enabled) {
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
                enabled.addAll(codes);
            } else {
                disabled.addAll(codes);
                enabled.removeAll(codes);
            }
        }
    }

    /** {@code base} with the rule options {@code config} sets (later entries win; unknown options ignored). */
    public static MarkdownLintOptions options(Parsed config, MarkdownLintOptions base) {
        MarkdownLintOptions out = base == null ? MarkdownLintOptions.DEFAULT : base;
        if (config == null || !config.valid()) {
            return out;
        }
        for (Entry e : config.entries()) {
            for (String code : MarkdownLint.resolve(e.name())) {
                for (Map.Entry<String, Object> o : e.options().entrySet()) {
                    out = out.with(code, o.getKey(), o.getValue());
                }
            }
        }
        return out;
    }

    /**
     * JSON/JSONC config {@code json} with rule {@code code} switched off, edited as text so that comments and
     * layout survive: an existing {@code "CODE": …} entry gets the value {@code false}, otherwise the entry is
     * added first in the object. {@code null} or blank input gives a new config; text that is not an object is
     * returned as {@code null} (the caller must not write it).
     */
    public static String withRuleOff(String json, String code) {
        if (json == null || json.isBlank()) {
            return "{\n  \"" + code + "\": false\n}\n";
        }
        if (!parse(json).valid()) {
            return null;
        }
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                        "(\"" + java.util.regex.Pattern.quote(code)
                                + "\"\\s*:\\s*)(true|false|\"[^\"]*\"|\\{[^{}]*\\})",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(json);
        if (entry.find()) {
            return json.substring(0, entry.start(2)) + "false" + json.substring(entry.end(2));
        }
        int open = json.indexOf('{');
        if (open < 0) {
            return null;
        }
        String rest = json.substring(open + 1);
        boolean empty = rest.strip().startsWith("}");
        String eol = json.contains("\r\n") ? "\r\n" : "\n";
        return json.substring(0, open + 1)
                + eol
                + "  \"" + code + "\": false"
                + (empty
                        ? eol + rest.stripLeading()
                        : "," + (rest.startsWith("\n") || rest.startsWith("\r") ? "" : eol) + rest);
    }

    /** The rule codes disabled by JSON/JSONC {@code json}; empty on malformed input. */
    public static Set<String> disabledRules(String json) {
        Set<String> disabled = new HashSet<>();
        apply(parse(json), disabled);
        return disabled;
    }
}
