package com.editora.config.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        int stored = versionOf(tree, schema.versionWithoutMarker(tree));
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
        return readVersioned(file, mapper, defaults, schema, problem -> {});
    }

    /**
     * As {@link #readVersioned(Path, ObjectMapper, Object, ConfigSchema)}, reporting anything that could not be
     * read as written to {@code problems} so the caller can tell the user and decide whether the file may be
     * saved again (see {@link ConfigLoadProblem#mustNotOverwrite}).
     *
     * <p>A value of the wrong type (a hand edit such as {@code "showMinimap": "yes"}) is <b>not</b> fatal: that
     * one top-level property keeps its default and every other property is still read. Jackson's bulk update
     * stops at the first bad value, which used to leave every later property — key bindings, API keys — at its
     * default and let the next save write that loss back to disk.
     */
    public static <T> T readVersioned(
            Path file, ObjectMapper mapper, T defaults, ConfigSchema schema, Consumer<ConfigLoadProblem> problems) {
        if (file == null) {
            return defaults;
        }
        restoreSetAsideCopy(file, mapper, defaults, schema, problems);
        if (!Files.isReadable(file)) {
            return defaults;
        }
        JsonNode tree;
        boolean bytesReplaced;
        try {
            Decoded decoded = decode(file);
            bytesReplaced = decoded.bytesReplaced();
            tree = mapper.readTree(decoded.text());
        } catch (IOException e) {
            // Unreadable, or not even valid JSON/TOML. Returning defaults means the next save writes an EMPTY
            // store straight over it — so preserve what's there first (see keepCorrupt).
            problems.accept(unreadable(file));
            return defaults;
        }
        if (!isStoreShape(tree, schema)) {
            // Empty, blank, or valid JSON that is not a store ("null", a string, a number). A store that
            // exists is never written empty, so this is damage — a write the OS never flushed before a power
            // cut, a sync tool's placeholder, an emptied buffer saved by another editor — not "no config
            // yet". Loading defaults in silence let the next save make the loss permanent.
            problems.accept(unreadable(file));
            return defaults;
        }
        ObjectNode migrated;
        try {
            migrated = upgrade(schema, tree, mapper);
        } catch (NewerThanSupportedException e) {
            problems.accept(new ConfigLoadProblem(
                    file, ConfigLoadProblem.Kind.NEWER_VERSION, List.of(), backupQuietly(file, e.storedVersion())));
            return defaults;
        } catch (RuntimeException e) {
            // A misconfigured migration: fall back to defaults rather than crash — but keep a copy first,
            // because the very next save overwrites the file.
            problems.accept(unreadable(file));
            return defaults;
        }
        if (bytesReplaced) {
            // The file loads, but not as written, and the next save makes the replacement permanent. Say so,
            // and keep the original bytes — except beside the Local History index, where any backup stops
            // blob collection (HistoryIndexGuard) although this index still lists every revision.
            problems.accept(new ConfigLoadProblem(
                    file,
                    ConfigLoadProblem.Kind.NOT_UTF8,
                    List.of(),
                    schema.keepsCopyOfUndecodableFile() ? keepCorrupt(file) : null));
        }
        try {
            return mapper.readerForUpdating(defaults).readValue(migrated);
        } catch (IOException | RuntimeException bulkFailure) {
            List<String> skipped = new ArrayList<>();
            T merged = readPropertyByProperty(mapper, defaults, migrated, skipped);
            if (!skipped.isEmpty()) {
                problems.accept(
                        new ConfigLoadProblem(file, ConfigLoadProblem.Kind.VALUES_SKIPPED, skipped, keepCorrupt(file)));
            }
            return merged;
        }
    }

    /**
     * Merges {@code migrated} onto {@code target} one top-level property at a time, so a property whose value
     * cannot be deserialized is skipped (its name added to {@code skipped}) without affecting the others. Only
     * used after the bulk update failed; properties that update already applied are simply applied again.
     */
    private static <T> T readPropertyByProperty(
            ObjectMapper mapper, T target, ObjectNode migrated, List<String> skipped) {
        T merged = target;
        for (Map.Entry<String, JsonNode> property : migrated.properties()) {
            ObjectNode single = mapper.createObjectNode();
            single.set(property.getKey(), property.getValue());
            try {
                merged = mapper.readerForUpdating(merged).readValue(single);
            } catch (IOException | RuntimeException badValue) {
                skipped.add(property.getKey());
            }
        }
        return merged;
    }

    /**
     * The text of a config file, decoded so that an encoding detail does not cost the whole file: a leading
     * UTF-8 byte-order mark (Windows Notepad's "UTF-8 with BOM", older PowerShell) is dropped, and a byte that
     * is not valid UTF-8 (a file saved as Windows-1252 with one accented name) becomes U+FFFD in that one value.
     *
     * <p>{@code Files.readString} throws on the stray byte, and a parser handed a {@code String} rejects the
     * BOM as an unexpected character. Either way the file used to read as unparseable, so every value in it
     * fell back to its default and the next save wrote those defaults over it.
     */
    public static String readText(Path file) throws IOException {
        return decode(file).text();
    }

    /** A config file's text and whether decoding it had to replace bytes that are not UTF-8. */
    private record Decoded(String text, boolean bytesReplaced) {}

    /** {@link #readText} for bytes already in hand (a store re-read in order to merge with it). */
    public static String decodeText(byte[] bytes) {
        return decode(null, bytes).text();
    }

    private static Decoded decode(Path file) throws IOException {
        return decode(file, Files.readAllBytes(file));
    }

    private static Decoded decode(Path file, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean replaced = text.indexOf('\uFFFD') >= 0 && !isValidUtf8(bytes);
        if (replaced && file != null) {
            LOG.log(
                    java.util.logging.Level.WARNING,
                    "Config file {0} is not valid UTF-8; the undecodable bytes were replaced",
                    file);
        }
        return new Decoded(!text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text, replaced);
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException malformed) {
            return false;
        }
    }

    /** {@code .v<n>.bak}, optionally numbered ({@code .v<n>.bak.2}): a file {@link #backup} moved aside. */
    private static final java.util.regex.Pattern SET_ASIDE =
            java.util.regex.Pattern.compile("\\.v(\\d+)\\.bak(\\.\\d+)?");

    /**
     * Brings back a copy of {@code file} that an older build moved aside, when this build can read it.
     *
     * <p>Running an older Editora once moves every store it does not understand to {@code <name>.v<n>.bak}
     * and starts that store from defaults. Going back to the newer build used to leave things that way for
     * good: the defaults file loads as a perfectly valid older config, and nothing ever read the
     * {@code .bak} again — preferences, key bindings, API keys, notes and projects stayed "lost" until the
     * user found the copies and renamed them by hand.
     *
     * <p>So, before a store is read: if a set-aside copy with a schema this build supports is beside it, and
     * the file in its place is absent, empty or still holds nothing but defaults, the copy is moved back
     * ({@link ConfigLoadProblem.Kind#NEWER_COPY_RESTORED}). When the file in its place has been changed, there
     * are two sets of data and no way to choose for the user; the file in use is loaded and the copy is
     * reported ({@link ConfigLoadProblem.Kind#NEWER_COPY_KEPT}). A copy newer than this build is left alone.
     * Called by {@link #readVersioned}; public for an owner that must decide something before it reads.
     */
    public static <T> void restoreSetAsideCopy(
            Path file, ObjectMapper mapper, T defaults, ConfigSchema schema, Consumer<ConfigLoadProblem> problems) {
        Path copy;
        try {
            copy = newestSetAsideCopy(file, schema.currentVersion());
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (copy == null) {
            return;
        }
        try {
            if (holdsOnlyDefaults(file, mapper, defaults, schema)) {
                try {
                    Files.move(
                            copy,
                            file,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                    Files.move(copy, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                LOG.log(java.util.logging.Level.INFO, "Restored config file {0} from {1}", new Object[] {file, copy});
                problems.accept(
                        new ConfigLoadProblem(file, ConfigLoadProblem.Kind.NEWER_COPY_RESTORED, List.of(), null));
            } else {
                problems.accept(new ConfigLoadProblem(file, ConfigLoadProblem.Kind.NEWER_COPY_KEPT, List.of(), copy));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not restore config file {0} from {1}: {2}", new Object[] {
                file, copy, e
            });
            problems.accept(new ConfigLoadProblem(file, ConfigLoadProblem.Kind.NEWER_COPY_KEPT, List.of(), copy));
        }
    }

    /**
     * The set-aside copy of {@code file} to restore: of those whose schema version this build supports, the
     * one with the newest version, then the one moved aside last. {@code null} when there is none.
     */
    static Path newestSetAsideCopy(Path file, int currentVersion) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        String name = file.getFileName().toString();
        Path best = null;
        int bestVersion = -1;
        long bestTime = Long.MIN_VALUE;
        try (java.nio.file.DirectoryStream<Path> copies = Files.newDirectoryStream(dir, name + ".v*.bak*")) {
            for (Path candidate : copies) {
                String suffix = candidate.getFileName().toString().substring(name.length());
                java.util.regex.Matcher m = SET_ASIDE.matcher(suffix);
                if (!m.matches() || !Files.isRegularFile(candidate)) {
                    continue;
                }
                int version;
                try {
                    version = Integer.parseInt(m.group(1));
                } catch (NumberFormatException tooLong) {
                    continue;
                }
                if (version > currentVersion) {
                    continue; // still newer than this build: it stays where it is
                }
                long time = Files.getLastModifiedTime(candidate).toMillis();
                if (version > bestVersion || (version == bestVersion && time >= bestTime)) {
                    best = candidate;
                    bestVersion = version;
                    bestTime = time;
                }
            }
        }
        return best;
    }

    /**
     * Whether {@code file} is absent, has no content, or holds only values equal to {@code defaults}' —
     * apart from its version stamp and the keys the app maintains by itself
     * ({@link ConfigSchema#selfMaintainedKeys}). A file that cannot be parsed is not "only defaults".
     */
    static <T> boolean holdsOnlyDefaults(Path file, ObjectMapper mapper, T defaults, ConfigSchema schema)
            throws IOException {
        if (!Files.exists(file)) {
            return true;
        }
        String text = readText(file);
        if (text.isBlank()) {
            return true;
        }
        JsonNode live = mapper.readTree(text);
        if (live == null || !live.isObject()) {
            return false;
        }
        live = upgrade(schema, live, mapper);
        JsonNode pristine = mapper.readTree(mapper.writeValueAsBytes(defaults));
        for (Map.Entry<String, JsonNode> property : live.properties()) {
            String key = property.getKey();
            if ("schemaVersion".equals(key) || schema.selfMaintainedKeys().contains(key)) {
                continue;
            }
            if (!property.getValue().equals(pristine.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a parsed file can be a store at all: a JSON object, or — for the one store whose first format
     * was one ({@code recent-files.json}, schema 0) — a bare array. Everything else, including "no content",
     * is damage.
     */
    static boolean isStoreShape(JsonNode tree, ConfigSchema schema) {
        if (tree == null || tree.isMissingNode()) {
            return false;
        }
        return tree.isObject() || (tree.isArray() && schema.step(0) != null);
    }

    /**
     * Keeps a copy of {@code file} beside it ({@code <name>.corrupt.bak}, numbered when taken, reused when
     * identical) before something replaces content that could not be read.
     *
     * @return the copy's path, or {@code null} when no copy could be made
     */
    public static Path keepCopy(Path file) {
        return keepCorrupt(file);
    }

    /**
     * An {@link ConfigLoadProblem.Kind#UNREADABLE} problem for {@code file}, keeping a copy of it beside it
     * first. For an owner that knows a file is damaged when {@link #readVersioned} cannot — a zero-length
     * Local History index beside stored revision bodies, which reads as "an empty file".
     */
    public static ConfigLoadProblem unreadable(Path file) {
        return new ConfigLoadProblem(file, ConfigLoadProblem.Kind.UNREADABLE, List.of(), keepCorrupt(file));
    }

    /**
     * Renames {@code file} out of the way as {@code <name>.v<storedVersion>.bak}. When that name is taken a
     * counter is appended rather than <em>skipping the move</em> (the old behavior): the file being displaced
     * is NEWER than this build understands, and we're about to load defaults over it — so skipping meant a
     * second downgrade overwrote a re-customized config with defaults while the only backup on disk was the
     * stale one from the first downgrade. The user's settings were lost with no copy at all.
     *
     * @return the backup's path
     */
    public static Path backup(Path file, int storedVersion) throws IOException {
        Path target = freeName(file, ".v" + storedVersion + ".bak");
        Files.move(file, target);
        return target;
    }

    /**
     * Keeps a copy of a config file we could not parse — overwhelmingly a torn write (a crash, a full disk, or
     * a kill mid-save). The caller loads defaults, and the next save writes those defaults over the file, so
     * without this the partially-written bookmarks/notes/projects are gone for good.
     *
     * @return the copy's path, or {@code null} when no copy could be made
     */
    private static Path keepCorrupt(Path file) {
        try {
            Path existing = identicalBackup(file, ".corrupt.bak");
            if (existing != null) {
                return existing; // the same damage as last launch: one copy of it is enough
            }
            Path kept = freeName(file, ".corrupt.bak");
            Files.copy(file, kept);
            LOG.log(
                    java.util.logging.Level.WARNING,
                    "Could not read all of config file {0} — kept a copy at {1}",
                    new Object[] {file, kept});
            return kept;
        } catch (IOException | RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "Could not keep a copy of config file {0}: {1}", new Object[] {
                file, e
            });
            return null;
        }
    }

    /**
     * An existing {@code file + suffix[.n]} backup whose content equals {@code file}, or {@code null}.
     *
     * <p>A store that is only rewritten when the user changes it (connections, macros, plugins, trusted
     * folders, abbreviations) stays damaged from one launch to the next. Copying it again each time filled all
     * {@link #MAX_BACKUPS} names with the same bytes; the next launch could then make no copy, and the file
     * became write-protected — so saving a connection silently did nothing.
     */
    private static Path identicalBackup(Path file, String suffix) throws IOException {
        Path candidate = file.resolveSibling(file.getFileName() + suffix);
        for (int i = 2; Files.exists(candidate); i++) {
            if (Files.isRegularFile(candidate) && Files.mismatch(file, candidate) == -1) {
                return candidate;
            }
            if (i > MAX_BACKUPS) {
                break;
            }
            candidate = file.resolveSibling(file.getFileName() + suffix + "." + i);
        }
        return null;
    }

    /**
     * {@code file + suffix}, with a counter appended when that name is already taken. Once all
     * {@link #MAX_BACKUPS} names are taken the last one is returned as is; the copy or move then fails with
     * {@code FileAlreadyExistsException}, which callers report as a failed backup rather than replacing one.
     */
    private static Path freeName(Path file, String suffix) {
        Path candidate = file.resolveSibling(file.getFileName() + suffix);
        for (int i = 2; Files.exists(candidate) && i <= MAX_BACKUPS; i++) {
            candidate = file.resolveSibling(file.getFileName() + suffix + "." + i);
        }
        return candidate;
    }

    /** {@link #backup}, returning {@code null} instead of throwing when the file could not be moved aside. */
    private static Path backupQuietly(Path file, int storedVersion) {
        try {
            return backup(file, storedVersion);
        } catch (IOException | RuntimeException e) {
            // The newer file is still in place. The caller reports the failed backup so its owner stops
            // saving the file for this session (ConfigLoadProblem.mustNotOverwrite) instead of replacing it.
            LOG.log(java.util.logging.Level.WARNING, "Could not back up newer config file {0}: {1}", new Object[] {
                file, e
            });
            return null;
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
    /**
     * v7 → v8 for a session file: seed {@code openToolWindows} from the three single-id fields, which is
     * what a side's open set was before a side could hold two windows.
     *
     * <p>A seeding step rather than a plain identity because the read path has no other way to know what
     * was open: the map is the source of truth from v8 on, so an un-seeded upgrade would silently restore
     * a session with every tool window closed.
     */
    static JsonNode seedOpenToolWindows(JsonNode input) {
        if (!(input instanceof ObjectNode o) || o.has("openToolWindows")) {
            return input;
        }
        ObjectNode open = JsonNodeFactory.instance.objectNode();
        record Legacy(String side, String field) {}
        for (Legacy l : List.of(
                new Legacy("LEFT", "openLeftToolWindow"),
                new Legacy("RIGHT", "openRightToolWindow"),
                new Legacy("BOTTOM", "openBottomToolWindow"))) {
            JsonNode id = o.get(l.field());
            if (id != null && id.isTextual() && !id.asText().isEmpty()) {
                open.set(l.side(), JsonNodeFactory.instance.arrayNode().add(id.asText()));
            }
        }
        o.set("openToolWindows", open);
        return o;
    }

    /**
     * The names the auto-saved last recording was stored under before v2 — one per UI language. They were
     * the entry's identity, so switching language orphaned it; these literals are frozen here (not read from
     * the catalogs, which may change) only to recognize the old entries.
     */
    private static final java.util.Set<String> LEGACY_UNNAMED_MACRO = java.util.Set.of(
            "unnamed macro",
            "macro sin nombre",
            "macro sans nom",
            "unbenanntes Makro",
            "macro senza nome",
            "macro sem nome");

    /**
     * v1 → v2 for {@code macros.json}.
     *
     * <ul>
     *   <li>Each macro gets an {@code id}: the slug its {@code macro.run.<slug>} command (and so any key
     *       binding) already used. Where several names shared a slug the <em>last</em> keeps it — that is the
     *       one the shared command ran — and the earlier ones, unreachable until now, get a suffix.
     *   <li>The auto-saved "unnamed macro" entry becomes {@code lastId} with an empty name. If the user had
     *       switched language there may be several; the last one is the live one, the rest keep their names.
     *   <li>A tab, newline or carriage return inside a text step becomes a {@code TAB}/{@code ENTER} key
     *       step, which replays through the same handlers as the key.
     * </ul>
     */
    static JsonNode macrosGainIds(JsonNode input) {
        if (!(input instanceof ObjectNode o) || !(o.get("macros") instanceof ArrayNode macros)) {
            return input;
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        String lastId = "";
        ObjectNode last = null;
        for (int i = macros.size() - 1; i >= 0; i--) {
            if (!(macros.get(i) instanceof ObjectNode m)) {
                continue;
            }
            JsonNode nameNode = m.get("name");
            String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : "";
            String id = com.editora.macro.MacroIds.unique(com.editora.macro.MacroIds.legacySlug(name), taken);
            taken.add(id);
            m.put("id", id);
            if (last == null && LEGACY_UNNAMED_MACRO.contains(name)) {
                last = m;
                lastId = id;
            }
            if (m.get("steps") instanceof ArrayNode steps) {
                m.set("steps", splitControlText(steps));
            }
        }
        if (last != null) {
            last.put("name", "");
        }
        o.put("lastId", lastId);
        return o;
    }

    /** Rewrites text steps holding a tab/newline as text and {@code TAB}/{@code ENTER} key steps, in order. */
    private static ArrayNode splitControlText(ArrayNode steps) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode step : steps) {
            JsonNode kind = step.get("kind");
            JsonNode value = step.get("value");
            boolean text = kind == null || !kind.isTextual() || "text".equals(kind.asText());
            if (!text || value == null || !value.isTextual()) {
                out.add(step);
                continue;
            }
            String s = value.asText();
            StringBuilder run = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '\t' && c != '\n' && c != '\r') {
                    run.append(c);
                    continue;
                }
                if (!run.isEmpty()) {
                    out.add(macroStep("text", run.toString()));
                    run.setLength(0);
                }
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++; // one Enter, however the line end was spelled
                }
                out.add(macroStep("key", c == '\t' ? "TAB" : "ENTER"));
            }
            if (!run.isEmpty()) {
                out.add(macroStep("text", run.toString()));
            }
        }
        return out;
    }

    private static ObjectNode macroStep(String kind, String value) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("kind", kind);
        n.put("value", value);
        return n;
    }

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
     * v49 → v50 for the settings file: grow an existing user's {@code todoPatterns} to include the new
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
     * v77 -> v78 for the settings file: split the single {@code aiApiKey} onto per-provider fields. The
     * key was a single field shared by both AI providers, so switching provider silently sent one provider's
     * credential to the other's endpoint. {@code aiApiKey} now holds the <em>Anthropic</em> key and
     * {@code aiApiKeyOpenai} the OpenAI-compatible one. A user whose saved provider was OpenAI had their
     * OpenAI-endpoint key in {@code aiApiKey}, so move it to {@code aiApiKeyOpenai} and clear {@code aiApiKey}
     * (else it would be resurrected as an Anthropic key). Anthropic was the default, so the common case — a
     * key saved under Anthropic — already lands in the right field and is left untouched.
     */
    /**
     * v80 → v81 for the settings file: split the single {@code keybindings} override map into per-platform
     * maps ({@code keybindings} = Ctrl-based Windows/Linux, {@code keybindingsMac} = Cmd-based macOS), so a config
     * synced between OSes no longer double-binds a rebound command (#439). A rebind's UNBIND suppressor keys to
     * the resolved chord, so a mac-written {@code Cmd-S UNBIND} doesn't suppress the {@code Ctrl-S} default on
     * Windows, leaving it live alongside the (platform-neutral) new chord. We assume the existing map belongs to
     * the platform running the migration and move it into that slot, clearing the other so the leak can't happen.
     */
    static JsonNode splitKeybindingsByPlatform(JsonNode input) {
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
        return input instanceof ObjectNode o ? splitKeybindings(o, mac) : input;
    }

    /**
     * Pure core of {@link #splitKeybindingsByPlatform}. On non-macOS the existing {@code keybindings} already is
     * the Ctrl slot (and {@code keybindingsMac} defaults empty), so it's identity. On macOS the existing map is
     * Cmd-based: move it to {@code keybindingsMac} and empty {@code keybindings}, so a later Windows/Linux load of
     * the same file starts from defaults instead of stale Cmd-chord overrides.
     */
    static ObjectNode splitKeybindings(ObjectNode o, boolean mac) {
        if (!mac || o.has("keybindingsMac")) {
            // Already split (the step is re-run for a current-shape file that lost its schemaVersion marker):
            // moving again would replace the user's Cmd overrides with the Ctrl map and empty that one.
            return o;
        }
        JsonNode existing = o.get("keybindings");
        o.set(
                "keybindingsMac",
                existing != null && existing.isObject() ? existing.deepCopy() : JsonNodeFactory.instance.objectNode());
        o.set("keybindings", JsonNodeFactory.instance.objectNode());
        return o;
    }

    /**
     * v88 → v89: turns the Projects feature on for existing installs.
     *
     * <p>Changing the field's default only reaches a fresh config dir. Jackson writes every modelled field on
     * save, so anyone who has ever run Editora has {@code projectSupport = false} stored explicitly and would
     * keep it forever.
     *
     * <p><b>This cannot tell "never enabled it" from "deliberately turned it off"</b> — the stored file looks
     * identical either way — so it turns the feature on for both. That is the intent: Projects went from
     * opt-in to on, and one checkbox turns it back off. It only writes when the stored value is {@code false},
     * so a config already carrying {@code true} is untouched.
     */
    static JsonNode enableProjectSupport(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode current = o.get("projectSupport");
        if (current != null && current.isBoolean() && !current.asBoolean()) {
            o.put("projectSupport", true);
        }
        return o;
    }

    /**
     * v100→101: puts {@code toolbar.recent} back into a <em>saved</em> toolbar layout.
     *
     * <p>Recent moved out of the toolbar's fixed tail and into the customizable icon cluster, where it is now
     * part of {@code ToolbarCatalog.defaultLayout}. A user who never customized the toolbar has an empty
     * saved layout and picks the default up for free — but a saved layout is used verbatim, so for anyone who
     * had rearranged their bar the button would simply have vanished from both halves. Nothing sheds an item
     * <em>in</em> the way {@code ToolbarLayout.sanitize} sheds unknown ids out, so it has to be inserted here.
     *
     * <p>Inserted directly after {@code file.saveAs}, its position in the shipped default; appended if the
     * layout has no Save As (someone can have removed it). A layout that already names it — and an empty one,
     * which means "use the default" — is left exactly as it is, so the step is idempotent and never disturbs a
     * user who has already placed Recent somewhere of their own choosing.
     */
    static JsonNode restoreRecentToToolbarLayout(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode layout = o.get("toolbarLayout");
        if (!(layout instanceof ArrayNode arr) || arr.isEmpty()) {
            return o; // never customized ⇒ the default layout applies, which already has Recent
        }
        for (JsonNode n : arr) {
            if (n.isTextual() && "toolbar.recent".equals(n.asText())) {
                return o; // already there — do not move what the user placed
            }
        }
        ArrayNode out = o.arrayNode();
        boolean inserted = false;
        for (JsonNode n : arr) {
            out.add(n);
            if (!inserted && n.isTextual() && "file.saveAs".equals(n.asText())) {
                out.add("toolbar.recent");
                inserted = true;
            }
        }
        if (!inserted) {
            out.add("toolbar.recent");
        }
        o.set("toolbarLayout", out);
        return o;
    }

    /**
     * v104 → v105 for the settings file: drops two keys that were only ever written by accident or never read.
     *
     * <p>{@code authorNameRaw} was not a setting: Jackson serialized the {@code getAuthorNameRaw()} helper
     * next to {@code authorName}, which itself was written through the <em>resolving</em> getter — so a blank
     * "follow the OS user" author name was persisted as the OS user name on the first save. Where the junk key
     * still records that the configured name was blank, the blank is restored; that is the only case the file
     * proves, so an {@code authorName} that merely equals the OS user name is left alone.
     *
     * <p>{@code ijhttpCommand} was never read by any feature (the HTTP client is built in).
     */
    static JsonNode retireUnusedSettingsKeys(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        JsonNode raw = o.remove("authorNameRaw");
        if (raw != null && raw.isTextual() && raw.asText().isBlank()) {
            o.put("authorName", "");
        }
        o.remove("ijhttpCommand");
        return o;
    }

    /** The plugin-registry URL every build up to schema 106 shipped, and froze into a fresh settings file. */
    static final String FROZEN_PLUGIN_REGISTRY =
            "https://raw.githubusercontent.com/adriandeleon/editora-plugins/main/index.json";
    /** The Maven archetype catalog URL every build up to schema 106 shipped and wrote out as a literal. */
    static final String FROZEN_MAVEN_ARCHETYPE_CATALOG = "https://repo.maven.apache.org/maven2/archetype-catalog.xml";

    /**
     * v106 → v107 for the settings file: a {@code pluginRegistryUrl} or {@code mavenArchetypeCatalogUrl} that
     * is the built-in address of the build that wrote it becomes blank, which now means "the built-in
     * default".
     *
     * <p>Both were written as literals by the first save, so every install carries them whether or not the
     * user ever opened the page. The setters recognise the <em>current</em> default, but once a default
     * moves they cannot recognise the old one: such a file would keep the stale address for good. The old
     * addresses are spelled out here for that reason, not read from {@code Settings}.
     *
     * <p>Only an exact match is blanked. A URL the user chose is a different string and is left alone; a
     * user who typed the built-in address chose what blank resolves to. Safe to repeat.
     */
    static JsonNode blankFrozenDefaultUrls(JsonNode input) {
        if (!(input instanceof ObjectNode o)) {
            return input;
        }
        blankIfEqual(o, "pluginRegistryUrl", FROZEN_PLUGIN_REGISTRY);
        blankIfEqual(o, "mavenArchetypeCatalogUrl", FROZEN_MAVEN_ARCHETYPE_CATALOG);
        return o;
    }

    /**
     * v115 → v116 for the settings file: a file with no {@code pdfPageSize} gets {@code "letter"}.
     *
     * <p>From this version a missing key means "the paper of this region" (see
     * {@code PdfPageSizes}), which is right for a new installation but would silently switch an existing
     * one to A4 outside the Letter countries. Every earlier build exported Letter when the key was absent,
     * so that is written down. A stored value — either size — is the user's and is not touched. Safe to
     * repeat.
     */
    static JsonNode keepLegacyPdfPageSize(JsonNode input) {
        if (input instanceof ObjectNode o && !o.hasNonNull("pdfPageSize")) {
            o.put("pdfPageSize", "letter");
        }
        return input;
    }

    private static void blankIfEqual(ObjectNode o, String key, String frozen) {
        JsonNode value = o.get(key);
        if (value != null && value.isTextual() && frozen.equals(value.asText().strip())) {
            o.put(key, "");
        }
    }

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
