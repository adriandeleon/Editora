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

    /**
     * The content of config file {@code fileName} with rule {@code code} switched off, edited as text so that
     * comments and layout survive — whichever of {@link #FILE_NAMES} it is: JSON/JSONC and YAML, and the
     * {@code config} object of a {@code .markdownlint-cli2.*} file. {@code null} when no safe edit exists (a
     * file that does not parse, a rule written as a nested YAML mapping, a flow-style {@code config: {…}}): the
     * caller then tells the user the line to add ({@link #manualLine}).
     */
    public static String withRuleOff(String fileName, String text, String code) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean yaml = name.endsWith(".yaml") || name.endsWith(".yml");
        boolean cli2 = name.startsWith(".markdownlint-cli2.");
        String source = text == null ? "" : text;
        String edited;
        if (yaml) {
            edited = cli2 ? yamlUnderConfig(source, code) : yamlTopLevel(source, code, "");
        } else if (cli2) {
            edited = jsonUnderConfig(source, code);
        } else {
            edited = withRuleOff(source, code);
        }
        if (edited == null) {
            return null;
        }
        // Whatever was done to the text, the result must still be a config that switches the rule off.
        Set<String> off = new HashSet<>();
        Parsed parsed = parseFile(fileName, edited);
        apply(parsed, off);
        return parsed.valid() && off.contains(code) ? edited : null;
    }

    /** The line a user adds to config file {@code fileName} by hand to switch {@code code} off. */
    public static String manualLine(String fileName, String code) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") ? code + ": false" : "\"" + code + "\": false";
    }

    /** YAML: sets or appends {@code CODE: false} among the keys indented by {@code indent}. */
    private static String yamlTopLevel(String text, String code, String indent) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\\r?\\n", -1);
        java.util.regex.Pattern key = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(indent) + "([\"']?)" + java.util.regex.Pattern.quote(code)
                        + "\\1\\s*:(.*)$",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher m = key.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            String rest = m.group(2);
            int comment = rest.indexOf(" #");
            String value = (comment < 0 ? rest : rest.substring(0, comment)).strip();
            if (value.isEmpty() || value.startsWith("{") || value.startsWith("[") || value.startsWith("&")) {
                return null; // options on the following lines (or a flow mapping): not ours to flatten
            }
            // Only the value changes: the spacing around it and a trailing comment stay as they were.
            int valueStart = rest.length() - rest.stripLeading().length();
            int valueEnd = valueStart + value.length();
            lines[i] = lines[i].substring(0, m.start(2))
                    + (valueStart == 0 ? " " : rest.substring(0, valueStart))
                    + "false"
                    + rest.substring(valueEnd);
            return String.join(eol, lines);
        }
        String line = indent + code + ": false";
        if (text.isBlank()) {
            return line + eol;
        }
        return text + (text.endsWith("\n") ? "" : eol) + line + eol;
    }

    /** A cli2 YAML file: the rule goes under its block-style {@code config:} mapping. */
    private static String yamlUnderConfig(String text, String code) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\\r?\\n", -1);
        int config = -1;
        for (int i = 0; i < lines.length && config < 0; i++) {
            if (lines[i].matches("^config\\s*:.*$")) {
                config = i;
            }
        }
        if (config < 0) {
            return text + (text.isEmpty() || text.endsWith("\n") ? "" : eol) + "config:" + eol + "  " + code + ": false"
                    + eol;
        }
        String after = lines[config].substring(lines[config].indexOf(':') + 1);
        int comment = after.indexOf(" #");
        if (!(comment < 0 ? after : after.substring(0, comment)).isBlank()) {
            return null; // "config: {…}" on one line
        }
        // The mapping's own lines: everything indented under "config:".
        int end = config + 1;
        String indent = null;
        while (end < lines.length
                && (lines[end].isBlank() || lines[end].startsWith(" ") || lines[end].startsWith("#"))) {
            if (indent == null
                    && lines[end].startsWith(" ")
                    && !lines[end].isBlank()
                    && !lines[end].strip().startsWith("#")) {
                indent = lines[end].substring(
                        0, lines[end].length() - lines[end].stripLeading().length());
            }
            end++;
        }
        indent = indent == null ? "  " : indent;
        String body = String.join(eol, java.util.Arrays.copyOfRange(lines, config + 1, end));
        java.util.regex.Pattern existing = java.util.regex.Pattern.compile(
                "(?m)^" + java.util.regex.Pattern.quote(indent) + "[\"']?" + java.util.regex.Pattern.quote(code)
                        + "[\"']?\\s*:",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.List<String> out = new ArrayList<>(List.of(lines));
        if (existing.matcher(body).find()) {
            String edited = yamlTopLevel(body, code, indent);
            if (edited == null) {
                return null;
            }
            String[] replaced = edited.split("\\r?\\n", -1);
            for (int i = config + 1; i < end && i - config - 1 < replaced.length; i++) {
                out.set(i, replaced[i - config - 1]);
            }
        } else {
            out.add(config + 1, indent + code + ": false");
        }
        return String.join(eol, out);
    }

    /** A cli2 JSONC file: the rule goes into its {@code "config"} object (added when there is none). */
    private static String jsonUnderConfig(String text, String code) {
        if (text.isBlank()) {
            return "{\n  \"config\": {\n    \"" + code + "\": false\n  }\n}\n";
        }
        if (!parse(text).valid()) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"config\"\\s*:\\s*\\{").matcher(text);
        if (!m.find()) {
            int open = text.indexOf('{');
            String rest = text.substring(open + 1);
            boolean empty = rest.strip().startsWith("}");
            return text.substring(0, open + 1) + "\n  \"config\": { \"" + code + "\": false }"
                    + (empty ? "\n" + rest.stripLeading() : "," + rest);
        }
        int start = m.end() - 1;
        int depth = 0;
        boolean inString = false;
        int end = -1;
        for (int p = start; p < text.length() && end < 0; p++) {
            char c = text.charAt(p);
            if (inString) {
                if (c == '\\') {
                    p++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '/' && p + 1 < text.length() && text.charAt(p + 1) == '/') {
                int nl = text.indexOf('\n', p);
                p = nl < 0 ? text.length() : nl;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = p + 1;
            }
        }
        if (end < 0) {
            return null;
        }
        String object = withRuleOff(text.substring(start, end), code);
        return object == null ? null : text.substring(0, start) + object + text.substring(end);
    }

    /** The rule codes disabled by JSON/JSONC {@code json}; empty on malformed input. */
    public static Set<String> disabledRules(String json) {
        Set<String> disabled = new HashSet<>();
        apply(parse(json), disabled);
        return disabled;
    }
}
