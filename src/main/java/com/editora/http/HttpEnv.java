package com.editora.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * is the empty string.
 */
public final class HttpEnv {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        putEnv(out, pub == null ? null : pub.get(SHARED));
        putEnv(out, priv == null ? null : priv.get(SHARED));
        putEnv(out, pub == null ? null : pub.get(environment));
        putEnv(out, priv == null ? null : priv.get(environment));
        return out;
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            return null; // malformed → contributes nothing (non-fatal)
        }
    }

    private static void putEnv(Map<String, String> out, JsonNode env) {
        if (env != null && env.isObject()) {
            for (Map.Entry<String, JsonNode> e : env.properties()) {
                flatten(out, e.getKey(), e.getValue(), 0);
            }
        }
    }

    /** Puts {@code key → value} and, for an object/array, every nested member under its dotted/indexed name. */
    private static void flatten(Map<String, String> out, String key, JsonNode v, int depth) {
        if (v == null || v.isNull()) {
            out.put(key, "");
        } else if (v.isValueNode()) {
            out.put(key, v.asText());
        } else {
            out.put(key, v.toString());
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
