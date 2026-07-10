package com.editora.build;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A parsed {@code package.json} — just the three things the build-tool integration needs: the project
 * {@code name} (shown in the Settings "Found: …" status, like Maven's artifactId; nullable), the {@code
 * scripts} names (in declaration order, each runnable via {@code <pm> run <name>}), and the {@code
 * packageManager} field (corepack's authoritative package-manager declaration, e.g. {@code "pnpm@8.6.0"}).
 * Parsed with the existing Jackson {@code ObjectMapper} via {@code readTree} (no POJO binding → no {@code
 * module-info} {@code opens}). Pure. A {@code package.json} with no {@code scripts} yields an empty list
 * (built-ins only, downstream).
 */
public record NpmProject(String name, List<String> scripts, String packageManagerField) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parses {@code package.json} text. Throws if the JSON is malformed (the caller reports it distinctly). */
    public static NpmProject parse(String packageJsonText) {
        try {
            JsonNode root = MAPPER.readTree(packageJsonText);
            JsonNode nameNode = root == null ? null : root.get("name");
            String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
            List<String> scripts = new ArrayList<>();
            JsonNode scriptsNode = root == null ? null : root.get("scripts");
            if (scriptsNode != null && scriptsNode.isObject()) {
                scriptsNode.fieldNames().forEachRemaining(scripts::add);
            }
            JsonNode pm = root == null ? null : root.get("packageManager");
            String pmField = pm != null && pm.isTextual() ? pm.asText() : null;
            return new NpmProject(name, List.copyOf(scripts), pmField);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid package.json: " + e.getMessage(), e);
        }
    }
}
