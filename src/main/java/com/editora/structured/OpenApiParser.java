package com.editora.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Detects and shallow-parses an OpenAPI 3 / Swagger 2 document (a JSON/YAML tree) into an
 * {@link OpenApiModel}. Pure; unit-tested. Handles both the OpenAPI 3 shape ({@code servers},
 * {@code components/schemas}) and the Swagger 2 shape ({@code host}/{@code basePath}/{@code schemes},
 * {@code definitions}); {@code $ref}s are shown by their simple name, not deep-resolved.
 */
public final class OpenApiParser {

    /** The HTTP methods a path item may declare (lower-case, as they appear in the spec). */
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private OpenApiParser() {}

    /** Whether {@code root} looks like an OpenAPI/Swagger document (has a textual {@code openapi}/{@code swagger}). */
    public static boolean detect(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        return isTextual(root, "openapi") || isTextual(root, "swagger");
    }

    public static OpenApiModel parse(JsonNode root) {
        JsonNode info = root.path("info");
        String title = text(info, "title");
        String version = text(info, "version");
        String description = text(info, "description");
        return new OpenApiModel(title, version, description, servers(root), paths(root), schemas(root));
    }

    private static List<OpenApiModel.Server> servers(JsonNode root) {
        List<OpenApiModel.Server> out = new ArrayList<>();
        JsonNode servers = root.get("servers");
        if (servers != null && servers.isArray()) { // OpenAPI 3
            for (JsonNode s : servers) {
                String url = text(s, "url");
                if (url != null) {
                    out.add(new OpenApiModel.Server(url, text(s, "description")));
                }
            }
        } else if (root.has("host")) { // Swagger 2: host + basePath + schemes
            String host = text(root, "host");
            String basePath = text(root, "basePath");
            String suffix = (host == null ? "" : host) + (basePath == null ? "" : basePath);
            JsonNode schemes = root.get("schemes");
            if (schemes != null && schemes.isArray() && schemes.size() > 0) {
                for (JsonNode sc : schemes) {
                    out.add(new OpenApiModel.Server(sc.asText() + "://" + suffix, null));
                }
            } else if (!suffix.isBlank()) {
                out.add(new OpenApiModel.Server(suffix, null));
            }
        }
        return out;
    }

    private static List<OpenApiModel.PathItem> paths(JsonNode root) {
        List<OpenApiModel.PathItem> out = new ArrayList<>();
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return out;
        }
        paths.fields().forEachRemaining(pe -> {
            JsonNode item = pe.getValue();
            if (!item.isObject()) {
                return;
            }
            List<OpenApiModel.Operation> ops = new ArrayList<>();
            item.fields().forEachRemaining(oe -> {
                String method = oe.getKey();
                JsonNode op = oe.getValue();
                if (METHODS.contains(StructuredParser.lower(method)) && op.isObject()) {
                    ops.add(operation(method.toUpperCase(java.util.Locale.ROOT), op));
                }
            });
            out.add(new OpenApiModel.PathItem(pe.getKey(), ops));
        });
        return out;
    }

    private static OpenApiModel.Operation operation(String method, JsonNode op) {
        List<OpenApiModel.Param> params = new ArrayList<>();
        JsonNode ps = op.get("parameters");
        if (ps != null && ps.isArray()) {
            for (JsonNode p : ps) {
                String name = text(p, "name");
                if (name == null && p.has("$ref")) {
                    name = refName(p.get("$ref").asText());
                }
                params.add(new OpenApiModel.Param(
                        name == null ? "?" : name,
                        text(p, "in"),
                        p.path("required").asBoolean(false),
                        paramType(p),
                        text(p, "description")));
            }
        }
        List<OpenApiModel.Response> responses = new ArrayList<>();
        JsonNode rs = op.get("responses");
        if (rs != null && rs.isObject()) {
            rs.fields()
                    .forEachRemaining(re ->
                            responses.add(new OpenApiModel.Response(re.getKey(), text(re.getValue(), "description"))));
        }
        return new OpenApiModel.Operation(
                method,
                text(op, "summary"),
                text(op, "description"),
                op.path("deprecated").asBoolean(false),
                params,
                responses);
    }

    private static List<OpenApiModel.Schema> schemas(JsonNode root) {
        JsonNode schemas = root.path("components").path("schemas"); // OpenAPI 3
        if (schemas.isMissingNode() || !schemas.isObject()) {
            schemas = root.path("definitions"); // Swagger 2
        }
        List<OpenApiModel.Schema> out = new ArrayList<>();
        if (!schemas.isObject()) {
            return out;
        }
        schemas.fields().forEachRemaining(se -> {
            JsonNode schema = se.getValue();
            List<OpenApiModel.Property> props = new ArrayList<>();
            Set<String> required = requiredNames(schema.get("required"));
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties
                        .fields()
                        .forEachRemaining(pe -> props.add(new OpenApiModel.Property(
                                pe.getKey(), typeOf(pe.getValue()), required.contains(pe.getKey()))));
            }
            out.add(new OpenApiModel.Schema(se.getKey(), typeOf(schema), props));
        });
        return out;
    }

    // --- helpers ---

    private static Set<String> requiredNames(JsonNode required) {
        if (required == null || !required.isArray()) {
            return Set.of();
        }
        java.util.HashSet<String> set = new java.util.HashSet<>();
        for (JsonNode r : required) {
            set.add(r.asText());
        }
        return set;
    }

    /** A parameter's type: OpenAPI 3 nests it under {@code schema}; Swagger 2 puts {@code type} inline. */
    private static String paramType(JsonNode p) {
        JsonNode schema = p.get("schema");
        return schema != null ? typeOf(schema) : typeOf(p);
    }

    /** A displayable type for a schema node: its {@code type}, an array's item type, or a {@code $ref} name. */
    private static String typeOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        if (node.has("$ref")) {
            return refName(node.get("$ref").asText());
        }
        String type = text(node, "type");
        if ("array".equals(type)) {
            JsonNode items = node.get("items");
            String inner = items == null ? "" : typeOf(items);
            return inner.isEmpty() ? "array" : inner + "[]";
        }
        return type == null ? "" : type;
    }

    /** The simple name of a {@code $ref} (its last path segment), e.g. {@code #/components/schemas/Pet} → {@code Pet}. */
    static String refName(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
    }

    private static boolean isTextual(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        return n != null && n.isTextual();
    }

    private static String text(JsonNode obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonNode n = obj.get(field);
        return n != null && n.isValueNode() ? n.asText() : null;
    }
}
