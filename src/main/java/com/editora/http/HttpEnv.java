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

    private HttpEnv() {
    }

    /** The variables of one environment ({@code name → value}) from {@code json}, or empty if absent. */
    public static java.util.Map<String, String> variables(String json, String environment) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank() || environment == null || environment.isEmpty()) {
            return out;
        }
        try {
            JsonNode env = MAPPER.readTree(json).get(environment);
            if (env != null && env.isObject()) {
                for (Map.Entry<String, JsonNode> e : env.properties()) {
                    JsonNode v = e.getValue();
                    out.put(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
                }
            }
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
        return out;
    }

    /** The ordered top-level environment names in {@code json}, or an empty list if it is blank/invalid. */
    public static List<String> environmentNames(String json) {
        List<String> names = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return names;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root != null && root.isObject()) {
                root.fieldNames().forEachRemaining(names::add);
            }
        } catch (Exception e) {
            return new ArrayList<>(); // malformed → no environments (non-fatal)
        }
        return names;
    }
}
