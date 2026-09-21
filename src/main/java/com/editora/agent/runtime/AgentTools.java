package com.editora.agent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Explicit registration, deterministic discovery, strict input validation before permission or execution. */
public final class AgentTools {
    private static final java.util.Set<String> SCHEMA_KEYWORDS = java.util.Set.of(
            "$schema",
            "$id",
            "$ref",
            "$defs",
            "definitions",
            "$comment",
            "title",
            "description",
            "default",
            "examples",
            "deprecated",
            "readOnly",
            "writeOnly",
            "type",
            "enum",
            "const",
            "anyOf",
            "oneOf",
            "allOf",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "uniqueItems",
            "minLength",
            "maxLength",
            "minimum",
            "maximum");
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentTools register(AgentTool tool) {
        if (tools.putIfAbsent(tool.spec().name(), tool) != null) {
            throw new IllegalArgumentException(
                    "Duplicate tool name: " + tool.spec().name());
        }
        return this;
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<AgentTool.Spec> specs() {
        return tools.values().stream().map(AgentTool::spec).toList();
    }

    /** Supported schema vocabulary is deliberately small; native schemas use only these keywords. */
    public static void validate(JsonNode value, JsonNode schema) {
        validate(value, schema, schema, 0);
    }

    private static void validate(JsonNode value, JsonNode schema, JsonNode root, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Schema nesting exceeds limit");
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) throw new IllegalArgumentException("Schema rejects value");
            return;
        }
        if (!schema.isObject()) throw new IllegalArgumentException("Invalid schema");
        // These constraints cannot be silently ignored; remote schemas remain observations on failure.
        var keywords = schema.fieldNames();
        while (keywords.hasNext()) {
            String key = keywords.next();
            if (!SCHEMA_KEYWORDS.contains(key))
                throw new IllegalArgumentException("Unsupported schema constraint: " + key);
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.startsWith("#/")) throw new IllegalArgumentException("Only local schema references are supported");
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) throw new IllegalArgumentException("Unknown schema reference");
            validate(value, target, root, depth + 1);
        }
        for (String key : List.of("anyOf", "oneOf", "allOf"))
            if (schema.has(key)) {
                var choices = schema.get(key);
                if (!choices.isArray() || choices.size() > 32)
                    throw new IllegalArgumentException("Invalid schema alternatives");
                int matches = 0;
                for (var choice : choices)
                    try {
                        validate(value, choice, root, depth + 1);
                        matches++;
                    } catch (IllegalArgumentException rejected) {
                    }
                if (key.equals("allOf") ? matches != choices.size() : key.equals("oneOf") ? matches != 1 : matches == 0)
                    throw new IllegalArgumentException("Schema alternatives rejected value");
            }
        if (schema.path("type").isArray()) {
            boolean matched = false;
            for (var type : schema.get("type")) {
                var variant = ((com.fasterxml.jackson.databind.node.ObjectNode) schema).deepCopy();
                variant.set("type", type);
                try {
                    validate(value, variant, root, depth + 1);
                    matched = true;
                    break;
                } catch (IllegalArgumentException rejected) {
                }
            }
            if (!matched) throw new IllegalArgumentException("No allowed type matched");
            return;
        }
        String type = schema.path("type").asText();
        boolean matches =
                switch (type) {
                    case "object" -> value.isObject();
                    case "array" -> value.isArray();
                    case "string" -> value.isTextual();
                    case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
                    case "boolean" -> value.isBoolean();
                    case "number" -> value.isNumber();
                    case "null" -> value.isNull();
                    case "" -> true;
                    default -> false;
                };
        if (!matches) {
            throw new IllegalArgumentException("Expected " + type);
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) {
                found |= option.equals(value);
            }
            if (!found) {
                throw new IllegalArgumentException("Value outside enum");
            }
        }
        if (schema.has("const") && !schema.get("const").equals(value))
            throw new IllegalArgumentException("Value differs from const");
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    throw new IllegalArgumentException("Missing argument: " + required.asText());
                }
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode property = schema.path("properties").get(field.getKey());
                if (property == null) {
                    JsonNode additional = schema.get("additionalProperties");
                    if (additional == null) continue;
                    validate(field.getValue(), additional, root, depth + 1);
                    continue;
                }
                validate(field.getValue(), property, root, depth + 1);
            }
        } else if (value.isArray()) {
            if (value.size() > schema.path("maxItems").asInt(128)
                    || value.size() < schema.path("minItems").asInt(0)) {
                throw new IllegalArgumentException("Array size outside limits");
            }
            for (JsonNode item : value) {
                if (schema.has("items")) validate(item, schema.get("items"), root, depth + 1);
            }
            if (schema.path("uniqueItems").asBoolean()
                    && new java.util.HashSet<JsonNode>(java.util.stream.StreamSupport.stream(value.spliterator(), false)
                                            .toList())
                                    .size()
                            != value.size()) throw new IllegalArgumentException("Duplicate array item");
        } else if (value.isTextual()
                && (value.textValue().length() > schema.path("maxLength").asInt(1_000_000)
                        || value.textValue().length() < schema.path("minLength").asInt(0))) {
            throw new IllegalArgumentException("String exceeds limit");
        } else if (value.isNumber()
                && ((schema.has("minimum")
                                && value.decimalValue()
                                                .compareTo(schema.get("minimum").decimalValue())
                                        < 0)
                        || (schema.has("maximum")
                                && value.decimalValue()
                                                .compareTo(schema.get("maximum").decimalValue())
                                        > 0))) {
            throw new IllegalArgumentException("Integer outside limits");
        }
    }
}
