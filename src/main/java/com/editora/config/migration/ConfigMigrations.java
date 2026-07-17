package com.editora.config.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The config schema-migration engine. On load a file is parsed to a Jackson tree, its stored
 * {@code schemaVersion} is read, registered {@link Migration} steps are applied in sequence up to the
 * {@link ConfigSchema}'s current version, and the tree is then deserialized onto the defaults POJO. Files
 * newer than this build are backed up and skipped (defaults are used) so an older Editora never clobbers a
 * newer config. All methods are static and side-effect-free except {@link #readVersioned}/{@link #backup}.
 */
public final class ConfigMigrations {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ConfigMigrations.class.getName());

    /** Cap on how many numbered backups of one file we keep before reusing the last name. */
    private static final int MAX_BACKUPS = 20;

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
            // Unreadable, or not even valid JSON/TOML. Returning defaults means the next save writes an EMPTY
            // store straight over it — so preserve what's there first (see keepCorrupt).
            keepCorrupt(file);
            return defaults;
        }
        if (tree == null || tree.isMissingNode()) {
            return defaults; // an empty file — nothing to preserve
        }
        try {
            ObjectNode migrated = upgrade(schema, tree, mapper);
            return mapper.readerForUpdating(defaults).readValue(migrated);
        } catch (NewerThanSupportedException e) {
            backupQuietly(file, e.storedVersion());
            return defaults;
        } catch (IOException | RuntimeException e) {
            // Malformed content or a misconfigured migration: fall back to defaults rather than crash — but
            // keep a copy first, because the very next save overwrites the file.
            keepCorrupt(file);
            return defaults;
        }
    }

    /**
     * Renames {@code file} out of the way as {@code <name>.v<storedVersion>.bak}. When that name is taken a
     * counter is appended rather than <em>skipping the move</em> (the old behavior): the file being displaced
     * is NEWER than this build understands, and we're about to load defaults over it — so skipping meant a
     * second downgrade overwrote a re-customized config with defaults while the only backup on disk was the
     * stale one from the first downgrade. The user's settings were lost with no copy at all.
     */
    public static void backup(Path file, int storedVersion) throws IOException {
        Files.move(file, freeName(file, ".v" + storedVersion + ".bak"));
    }

    /**
     * Keeps a copy of a config file we could not parse — overwhelmingly a torn write (a crash, a full disk, or
     * a kill mid-save). The caller loads defaults, and the next save writes those defaults over the file, so
     * without this the partially-written bookmarks/notes/projects are gone for good.
     */
    private static void keepCorrupt(Path file) {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) {
                return; // nothing worth keeping
            }
            Path kept = freeName(file, ".corrupt.bak");
            Files.copy(file, kept);
            LOG.log(
                    java.util.logging.Level.WARNING,
                    "Could not parse config file {0} — kept a copy at {1} and loaded defaults",
                    new Object[] {file, kept});
        } catch (IOException | RuntimeException ignored) {
            // best effort — we still return defaults
        }
    }

    /** {@code file + suffix}, with a counter appended when that name is already taken. */
    private static Path freeName(Path file, String suffix) {
        Path candidate = file.resolveSibling(file.getFileName() + suffix);
        for (int i = 2; Files.exists(candidate) && i <= MAX_BACKUPS; i++) {
            candidate = file.resolveSibling(file.getFileName() + suffix + "." + i);
        }
        return candidate;
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

    /**
     * v49 → v50 for {@code settings.toml}: grow an existing user's {@code todoPatterns} to include the new
     * built-in keyword defaults (HACK / NOTE / XXX / DONE) that joined TODO / FIXME, appending any whose
     * {@code name} isn't already present (custom entries are untouched, order preserved). Absent
     * {@code todoPatterns} needs nothing — the read path merges onto {@link com.editora.todo.TodoPatterns#defaults()},
     * which already lists every keyword.
     */
    static JsonNode growTodoDefaultKeywords(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode node = o.get("todoPatterns");
        if (node == null || !node.isArray()) {
            return input;
        }
        ArrayNode arr = (ArrayNode) node;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (JsonNode e : arr) {
            JsonNode nm = e.get("name");
            if (nm != null && nm.isTextual()) {
                present.add(nm.asText());
            }
        }
        for (com.editora.todo.TodoPattern p : com.editora.todo.TodoPatterns.defaults()) {
            if (present.contains(p.getName())) {
                continue;
            }
            ObjectNode t = JsonNodeFactory.instance.objectNode();
            t.put("name", p.getName());
            t.put("pattern", p.getPattern());
            t.put("color", p.getColor());
            t.put("caseSensitive", p.isCaseSensitive());
            t.put("enabled", p.isEnabled());
            arr.add(t);
        }
        return o;
    }

    /**
     * v77 -> v78 for {@code settings.toml}: split the single {@code aiApiKey} onto per-provider fields. The
     * key was a single field shared by both AI providers, so switching provider silently sent one provider's
     * credential to the other's endpoint. {@code aiApiKey} now holds the <em>Anthropic</em> key and
     * {@code aiApiKeyOpenai} the OpenAI-compatible one. A user whose saved provider was OpenAI had their
     * OpenAI-endpoint key in {@code aiApiKey}, so move it to {@code aiApiKeyOpenai} and clear {@code aiApiKey}
     * (else it would be resurrected as an Anthropic key). Anthropic was the default, so the common case — a
     * key saved under Anthropic — already lands in the right field and is left untouched.
     */
    static JsonNode splitAiApiKeyByProvider(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode providerNode = o.get("aiProvider");
        boolean openaiActive = providerNode != null
                && providerNode.isTextual()
                && providerNode.asText().strip().equalsIgnoreCase("openai");
        JsonNode keyNode = o.get("aiApiKey");
        boolean hasKey =
                keyNode != null && keyNode.isTextual() && !keyNode.asText().isBlank();
        JsonNode openaiKeyNode = o.get("aiApiKeyOpenai");
        boolean openaiKeyAlreadySet = openaiKeyNode != null
                && openaiKeyNode.isTextual()
                && !openaiKeyNode.asText().isBlank();
        if (openaiActive && hasKey && !openaiKeyAlreadySet) {
            o.put("aiApiKeyOpenai", keyNode.asText());
            o.put("aiApiKey", "");
        }
        return o;
    }

    /**
     * v1 -> v2 for {@code agent-sessions.json}: backfill {@code agentId} on every remembered session that
     * predates multi-agent support. Before this feature Claude Code was the only ACP agent, so any existing
     * entry was created by it — set {@code "agentId":"claude"} on entries missing/blank it. An entry that
     * already carries a non-blank {@code agentId} is left untouched (defensive, for partially-migrated data).
     */
    static JsonNode addDefaultAgentIdToSessions(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode node = o.get("sessions");
        if (node == null || !node.isArray()) {
            return input;
        }
        for (JsonNode e : (ArrayNode) node) {
            if (!(e instanceof ObjectNode entry)) {
                continue;
            }
            JsonNode id = entry.get("agentId");
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                entry.put("agentId", "claude");
            }
        }
        return input;
    }
}
