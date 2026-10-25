package com.editora.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.editora.plugin.PluginInstaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Import Configuration: reads a zip made by Export Configuration ({@link ConfigExporter}), says what it holds by
 * part and what each part would replace here, and applies the parts the user keeps ticked.
 *
 * <p>A zip is refused, before anything is written, when it is not a zip, has no {@code settings.json} at its root
 * (every export has one), names a path outside the config folder ({@code ../}, an absolute or drive path, a
 * backslash), holds an entry or a total larger than the limits below, has more entries than an export ever
 * would, or was written by a newer Editora. Sizes are counted while the entries are inflated, not taken from the
 * zip's own headers.
 *
 * <p>What an export holds that is this computer's own state — window layouts, recent files, the project index,
 * trusted folders, Local History, the HTTP response history, backups — is never imported; it is listed as left
 * out. A part that is imported replaces that part here as a whole (files of the part that the zip does not hold
 * are removed), except the AI API keys, which an export leaves blank: a blank key in the zip keeps the key here.
 * The built-in tool folders under {@code plugins/} are never touched.
 *
 * <p>Pure apart from the file system it is given; no JavaFX.
 */
public final class ConfigImport {

    private ConfigImport() {}

    /** Largest single file taken from a zip. */
    public static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    /** Largest total taken from a zip. */
    public static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    /** Most entries an import looks at. */
    public static final int MAX_ENTRIES = 20_000;

    /** What can be imported, in the order the dialog lists it. */
    public enum Part {
        PREFERENCES(true, true),
        KEY_BINDINGS(true, true),
        EXTERNAL_TOOLS(true, true),
        SNIPPETS(true, true),
        TEMPLATES(true, true),
        ABBREVIATIONS(true, true),
        DICTIONARY(true, true),
        MACROS(true, false),
        THEMES(true, false),
        REMOTE_SITES(true, false),
        BOOKMARKS_NOTES(false, false),
        PLUGINS(false, false);

        private final boolean onByDefault;
        private final boolean live;

        Part(boolean onByDefault, boolean live) {
            this.onByDefault = onByDefault;
            this.live = live;
        }

        /** Ticked when the dialog opens. Bookmarks (paths of another computer) and plugins (code) are not. */
        public boolean onByDefault() {
            return onByDefault;
        }

        /** Applied in the open windows at once; the others take effect at the next start. */
        public boolean live() {
            return live;
        }

        /** Whether the part lives in {@code settings.json} (as some of its keys) rather than in files. */
        public boolean inSettingsFile() {
            return this == PREFERENCES || this == KEY_BINDINGS || this == EXTERNAL_TOOLS;
        }
    }

    /** The {@code settings.json} keys of the key-binding part. */
    static final Set<String> KEY_BINDING_KEYS =
            Set.of("keymap", "keybindings", "keybindingsMac", "keymapKeybindings", "keymapKeybindingsMac");
    /** The {@code settings.json} keys of the external-tools part. */
    static final Set<String> EXTERNAL_TOOL_KEYS = Set.of("externalTools");

    /** Why a zip is not imported. */
    public static final class Refused extends Exception {

        public enum Reason {
            NOT_A_ZIP,
            NOT_AN_EXPORT,
            UNSAFE_PATH,
            TOO_LARGE,
            NEWER_VERSION
        }

        private final Reason reason;
        private final @Nullable String entry;

        Refused(Reason reason, @Nullable String entry) {
            super(reason + (entry == null ? "" : ": " + entry));
            this.reason = reason;
            this.entry = entry;
        }

        public Reason reason() {
            return reason;
        }

        /** The entry it is about, or null. */
        public @Nullable String entry() {
            return entry;
        }
    }

    /**
     * One part as the dialog shows it: {@code files} in the zip ({@code 0} = the zip does not have it), and for
     * file parts how many of this computer's files it would replace, add, and remove. A part kept in
     * {@code settings.json} reports {@code files = 1} and {@code replaced = 1}.
     */
    public record Summary(Part part, int files, int replaced, int added, int removed) {

        public boolean available() {
            return files > 0;
        }
    }

    /** A zip that passed every check: its entries by part, what it leaves out, and its preferences. */
    public static final class Scan {
        private final Path zip;
        private final Map<Part, Set<String>> entries;
        private final Set<String> leftOut;
        private final ObjectNode settings;
        private final Set<String> settingsKeys;

        Scan(Path zip, Map<Part, Set<String>> entries, Set<String> leftOut, ObjectNode settings, Set<String> keys) {
            this.zip = zip;
            this.entries = entries;
            this.leftOut = leftOut;
            this.settings = settings;
            this.settingsKeys = keys;
        }

        public Path zip() {
            return zip;
        }

        /** Entries of the zip that are this computer's own state and are never imported. */
        public Set<String> leftOut() {
            return leftOut;
        }

        /** Each part: what the zip has, and what it would do to {@code configDir}. */
        public List<Summary> summaries(Path configDir) throws IOException {
            Map<Part, Set<String>> here = currentFiles(configDir);
            List<Summary> out = new ArrayList<>();
            for (Part part : Part.values()) {
                if (part.inSettingsFile()) {
                    boolean has = part == Part.PREFERENCES ? !settingsKeys.isEmpty() : hasAny(part);
                    out.add(new Summary(part, has ? 1 : 0, has ? 1 : 0, 0, 0));
                    continue;
                }
                Set<String> zipped = entries.getOrDefault(part, Set.of());
                Set<String> mine = here.getOrDefault(part, Set.of());
                int replaced = 0;
                for (String e : zipped) {
                    if (mine.contains(e)) {
                        replaced++;
                    }
                }
                int removed = 0;
                for (String m : mine) {
                    if (!zipped.contains(m)) {
                        removed++;
                    }
                }
                out.add(new Summary(
                        part, zipped.size(), replaced, zipped.size() - replaced, zipped.isEmpty() ? 0 : removed));
            }
            return out;
        }

        private boolean hasAny(Part part) {
            Set<String> keys = part == Part.KEY_BINDINGS ? KEY_BINDING_KEYS : EXTERNAL_TOOL_KEYS;
            return keys.stream().anyMatch(settingsKeys::contains);
        }
    }

    // --- reading ---------------------------------------------------------------------------------------------

    /**
     * Checks {@code zip} and sorts its entries into parts. Reads every entry once (to count its real size) and
     * keeps only {@code settings.json} in memory, brought up to this build's schema.
     */
    public static Scan scan(Path zip, ObjectMapper json) throws Refused, IOException {
        Map<Part, Set<String>> entries = new EnumMap<>(Part.class);
        Set<String> leftOut = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        byte[] settingsBytes = null;
        long total = 0;
        int count = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                String name = e.getName();
                if (++count > MAX_ENTRIES) {
                    throw new Refused(Refused.Reason.TOO_LARGE, null);
                }
                if (!isSafeName(name) || !seen.add(name)) {
                    throw new Refused(Refused.Reason.UNSAFE_PATH, name);
                }
                if (e.isDirectory()) {
                    continue;
                }
                byte[] kept = name.equals(ConfigManager.SETTINGS_FILE_NAME) ? new byte[0] : null;
                long size;
                try (InputStream in = zf.getInputStream(e)) {
                    if (kept != null) {
                        kept = readCapped(in, name);
                        size = kept.length;
                        settingsBytes = kept;
                    } else {
                        size = countCapped(in, name);
                    }
                }
                total += size;
                if (total > MAX_TOTAL_BYTES) {
                    throw new Refused(Refused.Reason.TOO_LARGE, name);
                }
                if (kept != null) {
                    continue;
                }
                Part part = partOf(name);
                if (part == null) {
                    leftOut.add(name);
                } else {
                    entries.computeIfAbsent(part, p -> new TreeSet<>()).add(name);
                }
            }
        } catch (ZipException notAZip) {
            throw new Refused(Refused.Reason.NOT_A_ZIP, null);
        }
        if (settingsBytes == null) {
            throw new Refused(Refused.Reason.NOT_AN_EXPORT, null);
        }
        Set<String> keys = new HashSet<>();
        ObjectNode settings = migratedSettings(settingsBytes, json, keys);
        return new Scan(zip, entries, leftOut, settings, keys);
    }

    /** {@link #scan(Path, ObjectMapper)} with the live configuration's JSON settings. */
    public static Scan scan(Path zip, SharedConfig shared) throws Refused, IOException {
        return scan(zip, shared.json());
    }

    /**
     * Imports {@code parts} of {@code scan} into the live configuration's folder: waits for its pending writes,
     * makes the backup, then applies. Returns the backup. Off the FX thread.
     */
    public static Path apply(Scan scan, Set<Part> parts, SharedConfig shared) throws IOException, Refused {
        if (!shared.flushWrites()) {
            throw new IOException("Timed out waiting for pending configuration writes");
        }
        Path backup = backup(shared.getConfigDir(), LocalDateTime.now());
        apply(scan, parts, shared.getConfigDir(), shared.json());
        return backup;
    }

    /** {@code settings.json} from the zip, at this build's schema; the keys it names go into {@code keys}. */
    private static ObjectNode migratedSettings(byte[] bytes, ObjectMapper json, Set<String> keys)
            throws Refused, IOException {
        JsonNode raw;
        try {
            raw = json.readTree(bytes);
        } catch (IOException e) {
            throw new Refused(Refused.Reason.NOT_AN_EXPORT, ConfigManager.SETTINGS_FILE_NAME);
        }
        if (raw == null || !raw.isObject()) {
            throw new Refused(Refused.Reason.NOT_AN_EXPORT, ConfigManager.SETTINGS_FILE_NAME);
        }
        raw.fieldNames().forEachRemaining(keys::add);
        keys.remove("schemaVersion");
        Path dir = Files.createTempDirectory("editora-import");
        try {
            Path file = dir.resolve(ConfigManager.SETTINGS_FILE_NAME);
            Files.write(file, bytes);
            List<ConfigLoadProblem> problems = new ArrayList<>();
            Settings read =
                    ConfigMigrations.readVersioned(file, json, new Settings(), ConfigSchema.SETTINGS, problems::add);
            for (ConfigLoadProblem p : problems) {
                if (p.kind() == ConfigLoadProblem.Kind.NEWER_VERSION) {
                    throw new Refused(Refused.Reason.NEWER_VERSION, ConfigManager.SETTINGS_FILE_NAME);
                }
                if (p.kind() == ConfigLoadProblem.Kind.UNREADABLE) {
                    throw new Refused(Refused.Reason.NOT_AN_EXPORT, ConfigManager.SETTINGS_FILE_NAME);
                }
            }
            return json.valueToTree(read);
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * Whether a zip entry name stays inside the folder it is unpacked in: relative, forward slashes only, no
     * empty, {@code .} or {@code ..} segment, no drive letter or NUL. Pure.
     */
    static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
            return false;
        }
        if (name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            return false;
        }
        String body = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String segment : body.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** The part a config-folder path (forward slashes) belongs to, or null when it is never imported. Pure. */
    static @Nullable Part partOf(String path) {
        switch (path) {
            case ConfigManager.ABBREVIATIONS_FILE_NAME -> {
                return Part.ABBREVIATIONS;
            }
            case ConfigManager.MACROS_FILE_NAME -> {
                return Part.MACROS;
            }
            case ConfigManager.DICTIONARY_FILE_NAME -> {
                return Part.DICTIONARY;
            }
            case ConfigManager.CONNECTIONS_FILE_NAME -> {
                return Part.REMOTE_SITES;
            }
            case ConfigManager.BOOKMARKS_FILE_NAME,
                    ConfigManager.NOTES_FILE_NAME,
                    ConfigManager.BREAKPOINTS_FILE_NAME -> {
                return Part.BOOKMARKS_NOTES;
            }
            case ConfigManager.PLUGINS_FILE_NAME -> {
                return Part.PLUGINS;
            }
            default -> {
                // fall through to the folders
            }
        }
        if (path.startsWith("snippets/")) {
            return Part.SNIPPETS;
        }
        if (path.startsWith("templates/")) {
            return Part.TEMPLATES;
        }
        if (path.startsWith("themes/") || path.startsWith("editor-themes/")) {
            return Part.THEMES;
        }
        String plugins = ConfigManager.PLUGINS_DIR_NAME + "/";
        if (path.startsWith(plugins)) {
            int slash = path.indexOf('/', plugins.length());
            if (slash > plugins.length() && !PluginInstaller.isReservedId(path.substring(plugins.length(), slash))) {
                return Part.PLUGINS;
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in, String name) throws IOException, Refused {
        byte[] bytes = in.readNBytes((int) Math.min(MAX_ENTRY_BYTES + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > MAX_ENTRY_BYTES) {
            throw new Refused(Refused.Reason.TOO_LARGE, name);
        }
        return bytes;
    }

    private static long countCapped(InputStream in, String name) throws IOException, Refused {
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        for (int r; (r = in.read(buf)) > 0; ) {
            n += r;
            if (n > MAX_ENTRY_BYTES) {
                throw new Refused(Refused.Reason.TOO_LARGE, name);
            }
        }
        return n;
    }

    // --- what is here ----------------------------------------------------------------------------------------

    /** This computer's files of each file part (paths relative to {@code configDir}, forward slashes). */
    static Map<Part, Set<String>> currentFiles(Path configDir) throws IOException {
        Map<Part, Set<String>> out = new EnumMap<>(Part.class);
        if (!Files.isDirectory(configDir)) {
            return out;
        }
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> top = Files.list(configDir)) {
            top.forEach(roots::add);
        }
        for (Path root : roots) {
            String name = root.getFileName().toString();
            boolean folder = Files.isDirectory(root);
            if (folder
                    && !(name.equals("snippets")
                            || name.equals("templates")
                            || name.equals("themes")
                            || name.equals("editor-themes")
                            || name.equals(ConfigManager.PLUGINS_DIR_NAME))) {
                continue; // history/, sync/, projects/, the language-server caches…: never part of an import
            }
            if (!folder) {
                Part part = partOf(name);
                if (part != null) {
                    out.computeIfAbsent(part, p -> new TreeSet<>()).add(name);
                }
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                    Path f = it.next();
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    String rel = configDir.relativize(f).toString().replace('\\', '/');
                    Part part = partOf(rel);
                    if (part != null) {
                        out.computeIfAbsent(part, p -> new TreeSet<>()).add(rel);
                    }
                }
            }
        }
        return out;
    }

    // --- applying --------------------------------------------------------------------------------------------

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** The folder in the config folder that holds the copies made before an import. */
    public static final String BACKUPS_DIR = "backups";

    /**
     * Copies the whole configuration of {@code configDir} (API keys and HTTP history included) to
     * {@code backups/config.before-import-<stamp>.zip} there, owner-only, before anything is replaced.
     */
    public static Path backup(Path configDir, LocalDateTime now) throws IOException {
        Path dir = Files.createDirectories(configDir.resolve(BACKUPS_DIR));
        Path target = dir.resolve("config.before-import-" + STAMP.format(now) + ".zip");
        for (int n = 2; Files.exists(target); n++) {
            target = dir.resolve("config.before-import-" + STAMP.format(now) + "-" + n + ".zip");
        }
        return ConfigExporter.exportTo(configDir, target, true, true);
    }

    /**
     * Writes the {@code parts} of {@code scan} into {@code configDir}. Settings parts are merged into the
     * current {@code settings.json} key by key; file parts replace that part here. Call {@link #backup} first.
     */
    public static void apply(Scan scan, Set<Part> parts, Path configDir, ObjectMapper json)
            throws IOException, Refused {
        if (parts.stream().anyMatch(Part::inSettingsFile)) {
            applySettings(scan, parts, configDir, json);
        }
        Map<Part, Set<String>> here = currentFiles(configDir);
        try (ZipFile zf = new ZipFile(scan.zip.toFile())) {
            for (Part part : parts) {
                Set<String> zipped = scan.entries.getOrDefault(part, Set.of());
                if (part.inSettingsFile() || zipped.isEmpty()) {
                    continue;
                }
                for (String mine : here.getOrDefault(part, Set.of())) {
                    if (!zipped.contains(mine)) {
                        Files.deleteIfExists(configDir.resolve(mine));
                    }
                }
                for (String name : zipped) {
                    ZipEntry e = zf.getEntry(name);
                    if (e == null) {
                        throw new Refused(Refused.Reason.NOT_AN_EXPORT, name); // the zip changed since the scan
                    }
                    byte[] bytes;
                    try (InputStream in = zf.getInputStream(e)) {
                        bytes = readCapped(in, name);
                    }
                    Path target = configDir.resolve(name).normalize();
                    if (!target.startsWith(configDir.normalize())) {
                        throw new Refused(Refused.Reason.UNSAFE_PATH, name);
                    }
                    Files.createDirectories(target.getParent());
                    ConfigWriter.writeAtomicChecked(target, bytes);
                }
            }
        }
    }

    private static void applySettings(Scan scan, Set<Part> parts, Path configDir, ObjectMapper json)
            throws IOException {
        Path file = configDir.resolve(ConfigManager.SETTINGS_FILE_NAME);
        ObjectNode current = Files.isRegularFile(file) && json.readTree(file.toFile()) instanceof ObjectNode o
                ? o
                : json.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = scan.settings.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            Part part = KEY_BINDING_KEYS.contains(key)
                    ? Part.KEY_BINDINGS
                    : EXTERNAL_TOOL_KEYS.contains(key) ? Part.EXTERNAL_TOOLS : Part.PREFERENCES;
            if (!parts.contains(part) || key.equals("schemaVersion")) {
                continue;
            }
            if (part != Part.PREFERENCES && !scan.settingsKeys.contains(key)) {
                continue; // the zip does not say: what is here stays
            }
            JsonNode value = field.getValue();
            if (key.startsWith("aiApiKey") && (value == null || value.asText().isBlank())) {
                continue; // an export leaves the keys blank; a blank one never replaces the key here
            }
            current.set(key, value);
        }
        if (!current.has("schemaVersion")) {
            current.put("schemaVersion", Settings.SCHEMA_VERSION);
        }
        ConfigWriter.writeAtomicChecked(
                file,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(current)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a temp folder; the OS cleans what is left
                }
            });
        } catch (IOException ignored) {
            // same
        }
    }
}
