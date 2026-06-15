package com.editora.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the environment names from an HTTP Client environment file ({@code http-client.env.json} /
 * {@code http-client.private.env.json}) — a JSON object whose top-level keys are environment names
 * (e.g. {@code {"dev": {...}, "prod": {...}}}). Only the <b>names</b> are needed (for the picker);
 * {@code ijhttp} performs the actual variable resolution. Pure (takes the file content as a string),
 * so it is unit-tested.
 */
public final class HttpEnv {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpEnv() {}

    /** The IntelliJ-reserved environment whose variables are shared across every environment. */
    private static final String SHARED = "$shared";

    /** The variables of one environment ({@code name → value}) from {@code json}, with the {@code $shared}
     *  environment merged in underneath (a specific environment's value wins), or empty if absent. */
    public static java.util.Map<String, String> variables(String json, String environment) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank() || environment == null || environment.isEmpty()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            putEnv(out, root.get(SHARED)); // shared first (lowest precedence)
            putEnv(out, root.get(environment)); // the selected environment overrides
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
        return out;
    }

    private static void putEnv(java.util.Map<String, String> out, JsonNode env) {
        if (env != null && env.isObject()) {
            for (Map.Entry<String, JsonNode> e : env.properties()) {
                JsonNode v = e.getValue();
                out.put(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
            }
        }
    }

    /** The ordered top-level environment names in {@code json} (excluding the reserved {@code $shared}), or
     *  an empty list if it is blank/invalid. */
    public static List<String> environmentNames(String json) {
        List<String> names = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return names;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root != null && root.isObject()) {
                root.fieldNames().forEachRemaining(name -> {
                    if (!SHARED.equals(name)) {
                        names.add(name);
                    }
                });
            }
        } catch (Exception e) {
            return new ArrayList<>(); // malformed → no environments (non-fatal)
        }
        return names;
    }
}
