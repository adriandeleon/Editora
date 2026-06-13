package com.editora.config.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The config schema-migration engine. On load a file is parsed to a Jackson tree, its stored
 * {@code schemaVersion} is read, registered {@link Migration} steps are applied in sequence up to the
 * {@link ConfigSchema}'s current version, and the tree is then deserialized onto the defaults POJO. Files
 * newer than this build are backed up and skipped (defaults are used) so an older Editora never clobbers a
 * newer config. All methods are static and side-effect-free except {@link #readVersioned}/{@link #backup}.
 */
public final class ConfigMigrations {

    private ConfigMigrations() {}

    /**
     * The schema version of a parsed tree: a bare array ⇒ {@code 0} (the legacy {@code recent-files.json}
     * form); an object's {@code schemaVersion} number if present; otherwise {@code assumedLegacy} (the
     * pre-versioning baseline).
     */
    public static int versionOf(JsonNode tree, int assumedLegacy) {
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            return assumedLegacy;
        }
        if (tree.isArray()) {
            return 0;
        }
        if (tree.isObject()) {
            JsonNode v = tree.get("schemaVersion");
            return v != null && v.isNumber() ? v.asInt() : assumedLegacy;
        }
        return assumedLegacy;
    }

    /**
     * Applies the migration chain for {@code schema} to {@code tree} and returns the migrated object with
     * its {@code schemaVersion} stamped to the current version. Throws {@link NewerThanSupportedException}
     * if the file is newer than this build supports.
     */
    public static ObjectNode upgrade(ConfigSchema schema, JsonNode tree, ObjectMapper mapper) {
        int current = schema.currentVersion();
        int stored = versionOf(tree, schema.assumedLegacyVersion());
        if (stored > current) {
            throw new NewerThanSupportedException(schema, stored, current);
        }
        JsonNode node = applySteps(tree, stored, current, schema::step);
        ObjectNode obj = node != null && node.isObject() ? (ObjectNode) node : mapper.createObjectNode();
        obj.put("schemaVersion", current);
        return obj;
    }

    /** Applies the {@code from → to} migration chain in order, one step per version. Pure. */
    static JsonNode applySteps(JsonNode tree, int from, int to, java.util.function.IntFunction<Migration> stepFor) {
        JsonNode node = tree;
        for (int v = from; v < to; v++) {
            Migration step = stepFor.apply(v);
            if (step == null) {
                throw new IllegalStateException("Missing migration v" + v + " -> v" + (v + 1));
            }
            node = step.apply(node);
        }
        return node;
    }

    /**
     * Reads {@code file} with {@code mapper}, migrating it to {@code schema}'s current version, then merges
     * the result onto {@code defaults}. Missing/unreadable/malformed ⇒ {@code defaults}; a file newer than
     * this build ⇒ backed up to {@code <name>.v<n>.bak} and {@code defaults} returned.
     */
    public static <T> T readVersioned(Path file, ObjectMapper mapper, T defaults, ConfigSchema schema) {
        if (file == null || !Files.isReadable(file)) {
            return defaults;
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(Files.readString(file));
        } catch (IOException e) {
            return defaults;
        }
        if (tree == null || tree.isMissingNode()) {
            return defaults;
        }
        try {
            ObjectNode migrated = upgrade(schema, tree, mapper);
            return mapper.readerForUpdating(defaults).readValue(migrated);
        } catch (NewerThanSupportedException e) {
            backupQuietly(file, e.storedVersion());
            return defaults;
        } catch (IOException | RuntimeException e) {
            // Malformed content or a misconfigured migration: fall back to defaults rather than crash.
            return defaults;
        }
    }

    /** Renames {@code file} to {@code <name>.v<storedVersion>.bak}, preserving any existing backup. */
    public static void backup(Path file, int storedVersion) throws IOException {
        Path bak = file.resolveSibling(file.getFileName() + ".v" + storedVersion + ".bak");
        if (!Files.exists(bak)) {
            Files.move(file, bak);
        }
    }

    private static void backupQuietly(Path file, int storedVersion) {
        try {
            backup(file, storedVersion);
        } catch (IOException ignored) {
            // best effort — if we can't back it up we still return defaults and won't overwrite on save
        }
    }

    /**
     * A no-op step for a purely <b>additive</b> schema bump (new field with a default): the read path
     * merges onto defaults, so old files need no transform — they just get re-stamped to the new version.
     */
    static JsonNode identity(JsonNode input) {
        return input;
    }

    /** v0 → v1 for {@code recent-files.json}: wrap the legacy bare array into {@code { "files": [ … ] }}. */
    static JsonNode wrapRecentFilesArray(JsonNode input) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.set("files", input != null && input.isArray() ? input : JsonNodeFactory.instance.arrayNode());
        return o;
    }

    /**
     * v1 → v2 for {@code projects.json}: seed the new {@code openProjectIds} (the open-window set) from the
     * single {@code activeProjectId} that pre-multi-window installs tracked, so the previously-active
     * project reopens as its own window on first launch. No active project ⇒ an empty set (the global
     * window opens by default).
     */
    static JsonNode seedOpenProjectIds(JsonNode input) {
        if (!(input instanceof ObjectNode o) || o.has("openProjectIds")) {
            return input;
        }
        ArrayNode ids = JsonNodeFactory.instance.arrayNode();
        JsonNode active = o.get("activeProjectId");
        if (active != null && active.isTextual() && !active.asText().isEmpty()) {
            ids.add(active.asText());
        }
        o.set("openProjectIds", ids);
        return o;
    }
}
