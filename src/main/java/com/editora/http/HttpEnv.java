package com.editora.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.editora.i18n.Messages.tr;

/**
 * Reads the HTTP Client environment files ({@code http-client.env.json} and the git-ignored
 * {@code http-client.private.env.json}): JSON objects whose top-level keys are environment names
 * ({@code {"dev": {...}, "prod": {...}}}) plus the reserved {@code $shared} environment, whose variables every
 * environment inherits. It lists the environment names (for the picker) and builds the variables of the
 * selected one, which {@link HttpVars} then substitutes. Pure, so it is unit-tested.
 *
 * <p>JetBrains semantics: the private file overrides the public one, and a specific environment overrides
 * {@code $shared} — so the merge order, lowest first, is public {@code $shared}, private {@code $shared},
 * public environment, private environment. A nested object is reachable by a dotted name
 * ({@code {{client.host.url}}}, or {@code {{client['host.url']}}} for a key that holds a dot), an array
 * element by index ({@code {{users[0].name}}}), and the object itself as its JSON text; a JSON {@code null}
 * is the empty string. A number is sent as written in the file ({@code 0.0001}, not {@code 1.0E-4}).
 *
 * <p>The files are read leniently, as people edit them by hand: a UTF-8 byte-order mark, Java-style line and
 * block comments, and trailing commas are accepted. A file that still cannot be read contributes
 * nothing, and {@link #problem} says why, so the editor and the run can name the file instead of blaming every
 * variable in it.
 */
public final class HttpEnv {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES) // "100.50" stays "100.50"
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    /** The byte-order mark some editors put at the start of a UTF-8 file. */
    private static final char BOM = '\uFEFF';

    /** The shared (committed) environment file's name. */
    public static final String PUBLIC_FILE = "http-client.env.json";

    /** The private (git-ignored, secrets) environment file's name. */
    public static final String PRIVATE_FILE = "http-client.private.env.json";

    /** The IntelliJ-reserved environment whose variables are shared across every environment. */
    private static final String SHARED = "$shared";

    private HttpEnv() {}

    /** The variables of one environment ({@code name → value}) from one file's {@code json}, with its
     *  {@code $shared} environment merged in underneath (a specific environment's value wins), or empty. */
    public static Map<String, String> variables(String json, String environment) {
        return merged(json, null, environment);
    }

    /**
     * The variables of {@code environment} from both files, merged lowest-first as public {@code $shared},
     * private {@code $shared}, public environment, private environment. A blank/invalid file contributes
     * nothing; no environment selected means no variables.
     */
    public static Map<String, String> merged(String publicJson, String privateJson, String environment) {
        Map<String, String> out = new LinkedHashMap<>();
        if (environment == null || environment.isEmpty()) {
            return out;
        }
        JsonNode pub = read(publicJson);
        JsonNode priv = read(privateJson);
        // Merge the four layers as JSON first (nested objects key by key), then flatten once: a nested object's
        // own {{name}} is then the merged object, the same one its {{name.key}} members come from.
        ObjectNode merged = MAPPER.createObjectNode();
        mergeInto(merged, pub == null ? null : pub.get(SHARED));
        mergeInto(merged, priv == null ? null : priv.get(SHARED));
        mergeInto(merged, pub == null ? null : pub.get(environment));
        mergeInto(merged, priv == null ? null : priv.get(environment));
        for (Map.Entry<String, JsonNode> e : merged.properties()) {
            flatten(out, e.getKey(), e.getValue(), 0);
        }
        return out;
    }

    /** Lays {@code layer}'s members over {@code target}: objects merge member by member, anything else replaces. */
    private static void mergeInto(ObjectNode target, JsonNode layer) {
        if (layer == null || !layer.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : layer.properties()) {
            JsonNode existing = target.get(e.getKey());
            if (existing instanceof ObjectNode existingObject && e.getValue().isObject()) {
                mergeInto(existingObject, e.getValue());
            } else {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    private static JsonNode read(String json) {
        if (json == null || withoutBom(json).isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(withoutBom(json));
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            return null; // malformed → contributes nothing; problem() says why
        }
    }

    /** {@code text} without a leading byte-order mark. */
    static String withoutBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /**
     * Why an env file's {@code json} contributes nothing — it is not valid JSON (with the line and column), or its
     * top level is not an object — or {@code null} when it is usable (or blank, which is simply empty).
     */
    public static String problem(String json) {
        if (json == null || withoutBom(json).isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(withoutBom(json));
            return root == null || root.isObject() ? null : tr("http.env.notObject");
        } catch (JsonProcessingException e) {
            var at = e.getLocation();
            String what = e.getOriginalMessage() == null
                    ? ""
                    : e.getOriginalMessage().lines().findFirst().orElse("");
            return at == null
                    ? what
                    : tr("http.env.parseError", String.valueOf(at.getLineNr()), String.valueOf(at.getColumnNr()), what);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** Puts {@code key → value} and, for an object/array, every nested member under its dotted/indexed name. */
    private static void flatten(Map<String, String> out, String key, JsonNode v, int depth) {
        if (v == null || v.isNull()) {
            out.put(key, "");
        } else if (v.isNumber()) {
            // As written: a BigDecimal keeps "100.50" and "0.0001"; asText() of a double gave "1.0E-4".
            out.put(key, v.isFloatingPointNumber() ? v.decimalValue().toPlainString() : v.asText());
        } else if (v.isValueNode()) {
            out.put(key, v.asText());
        } else {
            out.put(key, json(v));
            if (depth >= 16) {
                return;
            }
            if (v.isObject()) {
                for (Map.Entry<String, JsonNode> e : v.properties()) {
                    String child = e.getKey().contains(".") ? key + "['" + e.getKey() + "']" : key + "." + e.getKey();
                    flatten(out, child, e.getValue(), depth + 1);
                }
            } else if (v.isArray()) {
                for (int i = 0; i < v.size(); i++) {
                    flatten(out, key + "[" + i + "]", v.get(i), depth + 1);
                }
            }
        }
    }

    /** {@code v} as JSON text, its numbers as written (plain, never in scientific notation). */
    private static String json(JsonNode v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return v.toString();
        }
    }

    /** The ordered top-level environment names in {@code json} (excluding the reserved {@code $shared}), or
     *  an empty list if it is blank/invalid. */
    public static List<String> environmentNames(String json) {
        return environmentNames(json, null);
    }

    /** The environment names of both files: the public file's in order, then any only the private file has. */
    public static List<String> environmentNames(String publicJson, String privateJson) {
        List<String> names = new ArrayList<>();
        for (JsonNode root : new JsonNode[] {read(publicJson), read(privateJson)}) {
            if (root != null) {
                root.fieldNames().forEachRemaining(name -> {
                    if (!SHARED.equals(name) && !names.contains(name)) {
                        names.add(name);
                    }
                });
            }
        }
        return names;
    }
}
