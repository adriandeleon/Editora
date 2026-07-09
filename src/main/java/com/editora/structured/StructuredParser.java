package com.editora.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses JSON / YAML / TOML text into a neutral {@link StructuredNode} tree for the 3-mode preview, and
 * (when the document is an OpenAPI/Swagger spec) an {@link OpenApiModel}. The only place the module touches
 * Jackson: JSON via {@link ObjectMapper}, YAML via {@link YAMLMapper}, TOML via {@link TomlMapper} — all
 * yield a {@code JsonNode}, converted once to the toolkit-free tree. Pure; unit-tested. Called off the FX
 * thread (the caller uses {@code PREVIEW_POOL}).
 */
public final class StructuredParser {

    /** Which structured format a buffer holds (from its language id). */
    public enum Format {
        JSON,
        YAML,
        TOML;

        /** The format for a buffer {@code language}, or {@code null} if it isn't a structured format. */
        public static Format forLanguage(String language) {
            if (language == null) {
                return null;
            }
            return switch (language) {
                case "json" -> JSON;
                case "yaml" -> YAML;
                case "toml" -> TOML;
                default -> null;
            };
        }
    }

    /** Guard so a pathological document can't build millions of tree nodes on the FX thread. */
    static final int MAX_NODES = 50_000;

    /**
     * The parse outcome: a {@code root} tree (never null on success), an optional {@code openApi} model when
     * the doc is a spec, or an {@code error} message on failure / over the node cap.
     */
    public record Parsed(StructuredNode root, OpenApiModel openApi, String error) {
        public boolean ok() {
            return error == null && root != null;
        }

        public boolean isOpenApi() {
            return openApi != null;
        }
    }

    private StructuredParser() {}

    public static Parsed parse(String text, Format fmt) {
        if (fmt == null) {
            return new Parsed(null, null, "unsupported format");
        }
        if (text == null || text.isBlank()) {
            return new Parsed(new StructuredNode(null, StructuredNode.Kind.OBJECT, null, List.of()), null, null);
        }
        try {
            JsonNode json = mapperFor(fmt).readTree(text);
            if (json == null || json.isMissingNode()) {
                return new Parsed(new StructuredNode(null, StructuredNode.Kind.OBJECT, null, List.of()), null, null);
            }
            int[] count = {0};
            StructuredNode root = fromJson(null, json, count);
            OpenApiModel api = fmt == Format.TOML || !OpenApiParser.detect(json) ? null : OpenApiParser.parse(json);
            return new Parsed(root, api, null);
        } catch (TooLargeException e) {
            return new Parsed(null, null, "document too large to preview (over " + MAX_NODES + " nodes)");
        } catch (Exception e) {
            return new Parsed(null, null, firstLine(e.getMessage()));
        }
    }

    static ObjectMapper mapperFor(Format fmt) {
        return switch (fmt) {
            case JSON -> new ObjectMapper();
            case YAML -> new YAMLMapper();
            case TOML -> new TomlMapper();
        };
    }

    /** Recursively converts a Jackson {@code JsonNode} to the neutral tree, counting nodes against the cap. */
    static StructuredNode fromJson(String key, JsonNode n, int[] count) {
        if (++count[0] > MAX_NODES) {
            throw new TooLargeException();
        }
        if (n.isObject()) {
            List<StructuredNode> children = new ArrayList<>();
            n.fields().forEachRemaining(e -> children.add(fromJson(e.getKey(), e.getValue(), count)));
            return new StructuredNode(key, StructuredNode.Kind.OBJECT, null, children);
        }
        if (n.isArray()) {
            List<StructuredNode> children = new ArrayList<>();
            for (JsonNode el : n) {
                children.add(fromJson(null, el, count));
            }
            return new StructuredNode(key, StructuredNode.Kind.ARRAY, null, children);
        }
        if (n.isTextual()) {
            return new StructuredNode(key, StructuredNode.Kind.STRING, n.asText(), null);
        }
        if (n.isNumber()) {
            return new StructuredNode(key, StructuredNode.Kind.NUMBER, n.asText(), null);
        }
        if (n.isBoolean()) {
            return new StructuredNode(key, StructuredNode.Kind.BOOLEAN, String.valueOf(n.asBoolean()), null);
        }
        return new StructuredNode(key, StructuredNode.Kind.NULL, "null", null);
    }

    private static String firstLine(String msg) {
        if (msg == null || msg.isBlank()) {
            return "parse error";
        }
        int nl = msg.indexOf('\n');
        return (nl >= 0 ? msg.substring(0, nl) : msg).strip();
    }

    /** Lower-cases a scalar for keyword checks (shared helper, kept here so {@code OpenApiParser} can reuse). */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static final class TooLargeException extends RuntimeException {
        TooLargeException() {
            super(null, null, false, false);
        }
    }
}
