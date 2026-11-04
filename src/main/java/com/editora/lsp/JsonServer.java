package com.editora.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * What Editora tells vscode-json-language-server (JS review A102).
 *
 * <ul>
 *   <li>The JSON-with-comments files are opened as {@code jsonc}, not {@code json}: the server relaxes comments and
 *       trailing commas only for that id, so a commented {@code tsconfig.json} (what {@code tsc --init} writes) got
 *       an error per comment and per trailing comma.</li>
 *   <li>{@code json.schemas} pairs {@code package.json} and {@code tsconfig.json}/{@code jsconfig.json} with schemas
 *       bundled in Editora — completion, hover and validation offline ({@code "strictt"}, {@code "type": "modul"} and
 *       {@code "scripts": 5} were all accepted). The settings carry {@code validate.enable}: an object without it
 *       switches the server's validation off.</li>
 * </ul>
 */
public final class JsonServer {

    private static final Logger LOG = Logger.getLogger(JsonServer.class.getName());

    private JsonServer() {}

    /** The server id of vscode-json-language-server. */
    public static final String SERVER_ID = "json";

    /**
     * The language id {@code file} opens with on the JSON server: {@code jsonc} for the JSON-with-comments
     * configuration files, else {@code json}. Pure.
     */
    public static String languageId(@Nullable Path file) {
        if (file == null || file.getFileName() == null) {
            return "json";
        }
        Path name = file.getFileName();
        String lower = name.toString().toLowerCase(Locale.ROOT);
        Path parent = file.getParent();
        boolean vscode = parent != null
                && parent.getFileName() != null
                && ".vscode".equals(parent.getFileName().toString());
        boolean jsonc = lower.endsWith(".jsonc")
                || lower.endsWith(".code-workspace")
                || (lower.startsWith("tsconfig") && lower.endsWith(".json"))
                || (lower.startsWith("jsconfig") && lower.endsWith(".json"))
                || lower.equals(".eslintrc")
                || lower.equals(".eslintrc.json")
                || lower.equals(".babelrc")
                || lower.equals(".babelrc.json")
                || lower.equals(".swcrc")
                || lower.equals("deno.json")
                || lower.equals("bun.lock")
                || (vscode && lower.endsWith(".json"));
        return jsonc ? "jsonc" : "json";
    }

    /** The {@code workspace/didChangeConfiguration} settings: validation on, formatting on, the bundled schemas. */
    static Map<String, Object> settings() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("validate", Map.of("enable", true));
        json.put("format", Map.of("enable", true));
        json.put("schemas", schemas());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("json", json);
        return out;
    }

    /** The bundled schema associations; the schema bodies are sent inline (no file or network access). */
    static List<Object> schemas() {
        List<Object> out = new ArrayList<>();
        addSchema(out, "package.schema.json", "editora://schemas/package.json", List.of("package.json"));
        addSchema(
                out,
                "tsconfig.schema.json",
                "editora://schemas/tsconfig.json",
                List.of("tsconfig.json", "tsconfig.*.json", "tsconfig-*.json", "jsconfig.json", "jsconfig.*.json"));
        return out;
    }

    private static void addSchema(List<Object> out, String resource, String id, List<String> fileMatch) {
        com.google.gson.JsonElement schema = bundled(resource);
        if (schema == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fileMatch", fileMatch);
        entry.put("url", id);
        entry.put("schema", schema);
        out.add(entry);
    }

    private static final Map<String, com.google.gson.JsonElement> BUNDLED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static com.google.gson.@Nullable JsonElement bundled(String resource) {
        com.google.gson.JsonElement known = BUNDLED.get(resource);
        if (known != null) {
            return known;
        }
        try (InputStream in = JsonServer.class.getResourceAsStream("/com/editora/lsp/schemas/" + resource)) {
            if (in == null) {
                return null;
            }
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            BUNDLED.put(resource, parsed);
            return parsed;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "could not read the bundled JSON schema " + resource, e);
            return null;
        }
    }
}
