package com.editora.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * A parsed {@code package.json} — what the build-tool integration needs: the project {@code name} (shown in the
 * Settings "Found: …" status, like Maven's artifactId; nullable), the {@code scripts} names (in declaration
 * order, each runnable via {@code <pm> run <name>}) with their commands (the popup shows them), the
 * {@code packageManager} field (corepack's authoritative package-manager declaration, e.g. {@code "pnpm@8.6.0"}),
 * whether it declares {@code workspaces} (a monorepo root), and whether it has any dependencies (so a missing
 * {@code node_modules} can be pointed out). Parsed with the existing Jackson {@code ObjectMapper} via
 * {@code readTree} (no POJO binding → no {@code module-info} {@code opens}). Pure. A {@code package.json} with no
 * {@code scripts} yields an empty list (built-ins only, downstream).
 */
public record NpmProject(
        @Nullable String name,
        List<String> scripts,
        @Nullable String packageManagerField,
        Map<String, String> scriptCommands,
        boolean workspaces,
        boolean hasDependencies) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public NpmProject {
        scripts = List.copyOf(scripts);
        scriptCommands = scriptCommands == null ? Map.of() : java.util.Collections.unmodifiableMap(scriptCommands);
    }

    public NpmProject(String name, List<String> scripts, String packageManagerField) {
        this(name, scripts, packageManagerField, Map.of(), false, false);
    }

    /** The command a script runs ({@code "node --test"}), or "" when it has none. */
    public String commandOf(String script) {
        String c = scriptCommands.get(script);
        return c == null ? "" : c;
    }

    /**
     * Parses {@code package.json} text. Throws if the JSON is malformed; the message then says where
     * ({@code "line 3, column 12: Unexpected end-of-input …"}), so the status line can point at it.
     */
    public static NpmProject parse(String packageJsonText) {
        try {
            JsonNode root = MAPPER.readTree(BuildTool.withoutBom(packageJsonText));
            JsonNode nameNode = root == null ? null : root.get("name");
            String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
            List<String> scripts = new ArrayList<>();
            Map<String, String> commands = new LinkedHashMap<>();
            JsonNode scriptsNode = root == null ? null : root.get("scripts");
            if (scriptsNode != null && scriptsNode.isObject()) {
                scriptsNode.fieldNames().forEachRemaining(n -> {
                    scripts.add(n);
                    JsonNode c = scriptsNode.get(n);
                    commands.put(n, c != null && c.isTextual() ? c.asText() : "");
                });
            }
            JsonNode pm = root == null ? null : root.get("packageManager");
            String pmField = pm != null && pm.isTextual() ? pm.asText() : null;
            boolean workspaces = root != null && root.has("workspaces");
            boolean deps = false;
            for (String key : List.of("dependencies", "devDependencies", "optionalDependencies")) {
                JsonNode d = root == null ? null : root.get(key);
                deps |= d != null && d.isObject() && d.size() > 0;
            }
            return new NpmProject(name, scripts, pmField, commands, workspaces, deps);
        } catch (JsonProcessingException e) {
            var loc = e.getLocation();
            String where = loc == null ? "" : "line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ": ";
            throw new IllegalArgumentException(where + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid package.json: " + e.getMessage(), e);
        }
    }
}
