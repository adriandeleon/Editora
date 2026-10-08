package com.editora.snippet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.ConfigManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Loads and serves snippets. Each language has a bundled resource
 * ({@code /com/editora/snippets/<lang>.json}), optional plugin files and an optional user file
 * ({@code <configDir>/snippets/<lang>.json}); {@code global} applies to every file.
 *
 * <h2>Who wins</h2>
 *
 * <p>Within one language the three sources are merged <b>by name</b>, in the order bundled &lt; plugin &lt;
 * user: a user entry called {@code "For Loop"} <em>is</em> the bundled {@code "For Loop"}, edited — every
 * trigger of the bundled one is replaced, so changing its trigger does not leave the old one expanding. A
 * user entry carrying {@code "disabled": true} switches the snippet of that name off.
 *
 * <p>Across names, a trigger belongs to the highest source that uses it (user over plugin over bundled),
 * and a language's snippets come before its fallback language's ({@code typescriptreact} →
 * {@code typescript}) and before global ones. Two snippets of the <em>same</em> source that share a trigger
 * are both kept — the completion popup and the picker list both — and Tab expands the first.
 *
 * <p>JSON is the VS Code format: a map of name → {@code {prefix, body, description, scope}}, where
 * {@code body} is a string or an array of lines and {@code scope} is an optional comma-separated list of
 * language ids the snippet is limited to. Like VS Code's own snippet files it is read as JSONC —
 * {@code //} and block comments and trailing commas are accepted. Results are cached until {@link #reload()}.
 */
public final class SnippetManager {

    private static final Logger LOG = Logger.getLogger(SnippetManager.class.getName());
    private static final String GLOBAL = "global";

    /** Where a snippet comes from, lowest priority first. */
    public enum Source {
        BUNDLED,
        PLUGIN,
        USER
    }

    /**
     * The languages a bundled snippet file ships for ({@code global} excluded), sorted.
     * {@code SnippetManagerTest} pins this against the resource directory.
     */
    public static final List<String> BUNDLED_LANGUAGES = List.of(
            "batchfile",
            "c",
            "cpp",
            "csharp",
            "css",
            "dockerfile",
            "go",
            "groovy",
            "html",
            "ini",
            "java",
            "javascript",
            "json",
            "kotlin",
            "lua",
            "markdown",
            "mermaid",
            "php",
            "powershell",
            "python",
            "ruby",
            "rust",
            "shell",
            "sql",
            "terraform",
            "toml",
            "typescript",
            "typst",
            "xml",
            "yaml");

    /** A language that also gets another language's snippets (its own win on a clash). */
    private static final Map<String, String> FALLBACK =
            Map.of("javascriptreact", "javascript", "typescriptreact", "typescript");

    /** VS Code language ids a {@code scope} may use for a language Editora names differently. */
    private static final Map<String, String> SCOPE_ALIASES = Map.of(
            "shellscript", "shell",
            "bat", "batchfile",
            "jsonc", "json",
            "dockercompose", "yaml",
            "plaintext", "text",
            "objective-c", "c",
            "vue-html", "html");

    /**
     * One snippet as its file holds it, for the Settings page: every trigger, not just the first.
     *
     * @param source where the shown fields come from ({@code USER} once the user has edited or added it)
     * @param bundled a bundled snippet of this name exists (so removing a user entry reverts to it)
     * @param disabled switched off by the user file
     */
    public record Entry(
            String name,
            List<String> prefixes,
            String body,
            String description,
            String scope,
            Source source,
            boolean bundled,
            boolean disabled) {

        /** A new or edited user snippet. */
        public static Entry user(String name, List<String> prefixes, String body, String description) {
            return new Entry(name, prefixes, body, description, "", Source.USER, false, false);
        }

        /** The triggers as the Settings field shows them: comma-separated. */
        public String prefixText() {
            return String.join(", ", prefixes);
        }

        public Entry withFields(String newName, List<String> newPrefixes, String newBody, String newDescription) {
            return new Entry(newName, newPrefixes, newBody, newDescription, scope, Source.USER, bundled, disabled);
        }
    }

    /** Why a snippet file, or one entry of it, was not loaded. */
    public record Problem(Path file, Kind kind, String entry, String detail, int line) {
        public enum Kind {
            /** The file is not valid JSON; {@code detail} is the parser's reason. Nothing was loaded from it. */
            SYNTAX,
            /** The file's top level is not an object. Nothing was loaded from it. */
            NOT_AN_OBJECT,
            /** {@code entry} is not an object; only that entry was skipped. */
            BAD_ENTRY,
            /** The file could not be read; {@code detail} is the I/O reason. */
            UNREADABLE
        }

        /** True when the whole file is unusable (so it must not be rewritten). */
        public boolean wholeFile() {
            return kind != Kind.BAD_ENTRY;
        }

        public String fileName() {
            return file.getFileName().toString();
        }
    }

    private final ConfigManager config;
    // Lenient: real-world VS Code snippet files (e.g. friendly-snippets) carry extra fields, and VS Code's own
    // template for a user snippet file opens with a block of // comments.
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private final ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    /** One entry of one file, as written. A {@code marker} is disabled and has nothing else: it only disables. */
    private record Raw(
            String name, List<String> prefixes, String body, String description, String scope, boolean disabled) {
        boolean marker() {
            return disabled && prefixes.isEmpty() && body.isEmpty();
        }
    }

    private record FileSet(List<Raw> bundled, List<Raw> plugin, List<Raw> user) {}

    private record Loaded(Raw raw, Source source, String language) {}

    /** What a buffer of one language is offered, resolved once per language until the next reload. */
    private record Resolved(List<Snippet> all, Map<String, Snippet> byTrigger, Set<String> listOnly, int maxWords) {}

    private final Map<String, FileSet> files = new HashMap<>();
    private final Map<String, Resolved> resolved = new HashMap<>();
    /** Extra source dirs (e.g. plugin {@code snippets/} folders), each holding {@code <lang>.json} files. */
    private final List<Path> extraDirs = new ArrayList<>();
    /** Problems found by the loads since the last {@link #reload()}, by file then entry. */
    private final Map<String, Problem> problems = new LinkedHashMap<>();

    private Consumer<Problem> problemListener = p -> {};
    private Runnable onUserFilesChanged = () -> {};

    public SnippetManager(ConfigManager config) {
        this.config = config;
    }

    /** Told once about each problem a load finds (again after a {@link #reload()} if it is still there). */
    public synchronized void setProblemListener(Consumer<Problem> listener) {
        this.problemListener = listener == null ? p -> {} : listener;
    }

    /** Run after this manager writes a user snippet file, so other windows can {@link #reload()}. */
    public synchronized void setOnUserFilesChanged(Runnable action) {
        this.onUserFilesChanged = action == null ? () -> {} : action;
    }

    /** Drops the cache so edited user snippet files are picked up. */
    public synchronized void reload() {
        files.clear();
        resolved.clear();
        problems.clear();
    }

    /**
     * Adds an extra snippet source dir (a plugin's {@code snippets/}). Its {@code <lang>.json} files rank
     * above the bundled snippets and below the user's own. Drops the cache so it's picked up.
     */
    public synchronized void addExtraSourceDir(Path dir) {
        if (dir != null && !extraDirs.contains(dir)) {
            extraDirs.add(dir);
            files.clear();
            resolved.clear();
        }
    }

    /**
     * Snippets available in {@code language}: its own, then its fallback language's, then global ones — one
     * {@link Snippet} per trigger. See the class comment for what shadows what.
     */
    public synchronized List<Snippet> forLanguage(String language) {
        return new ArrayList<>(resolve(norm(language)).all());
    }

    /** The snippet {@code prefix} stands for in {@code language} (the one Tab would expand), or null. */
    public synchronized Snippet byPrefix(String language, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        return resolve(norm(language)).byTrigger().get(prefix);
    }

    /**
     * As {@link #byPrefix}, for the Tab key: null for a trigger that is offered in the completion popup and
     * the picker only. Those are the <em>bundled global</em> snippets ({@code date}, {@code time}) — ordinary
     * words that would otherwise expand in every file type whenever Tab follows them. A global snippet the
     * user wrote (or a bundled one they edited) is deliberate and expands like any other.
     */
    public synchronized Snippet byTabTrigger(String language, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        Resolved r = resolve(norm(language));
        return r.listOnly().contains(prefix) ? null : r.byTrigger().get(prefix);
    }

    /** The most whitespace-separated words any trigger of {@code language} has (1 when none has a space). */
    public synchronized int maxTriggerWords(String language) {
        return resolve(norm(language)).maxWords();
    }

    /** The user snippet file for a language (may not exist yet); used by the "Edit User Snippets" command. */
    public Path userFile(String language) {
        return config.getConfigDir().resolve("snippets").resolve(norm(language) + ".json");
    }

    /** The language a user snippet file belongs to, or null when {@code file} is not one. */
    public String userFileLanguage(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        Path dir = config.getConfigDir().resolve("snippets").toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        String name = abs.getFileName().toString();
        return dir.equals(abs.getParent()) && name.endsWith(".json")
                ? name.substring(0, name.length() - ".json".length())
                : null;
    }

    private static String norm(String language) {
        return language == null || language.isBlank() ? GLOBAL : language;
    }

    /** Every language that has snippets to show: the bundled files' plus the user's, {@code global} first. */
    public synchronized List<String> languagesWithSnippets() {
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(BUNDLED_LANGUAGES);
        sorted.addAll(FALLBACK.keySet());
        sorted.addAll(userSnippetLanguages());
        sorted.remove(GLOBAL);
        List<String> out = new ArrayList<>();
        out.add(GLOBAL);
        out.addAll(sorted);
        return out;
    }

    /**
     * The snippets Settings shows for {@code language}: the bundled ones in file order — each replaced by the
     * user's entry of the same name when there is one — then the user's own. Plugin snippets are not listed.
     */
    public synchronized List<Entry> entries(String language) {
        String lang = norm(language);
        FileSet fs = fileSet(lang);
        Map<String, Raw> user = new LinkedHashMap<>();
        for (Raw u : fs.user()) {
            user.put(u.name(), u);
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Raw b : fs.bundled()) {
            if (!seen.add(b.name())) {
                continue;
            }
            Raw u = user.get(b.name());
            if (u == null || u.marker()) {
                out.add(entry(b, Source.BUNDLED, true, u != null && u.disabled()));
            } else {
                out.add(entry(u, Source.USER, true, u.disabled()));
            }
        }
        for (Raw u : user.values()) {
            if (seen.add(u.name())) {
                out.add(entry(u, Source.USER, false, u.disabled()));
            }
        }
        return out;
    }

    private static Entry entry(Raw r, Source source, boolean bundled, boolean disabled) {
        return new Entry(r.name(), r.prefixes(), r.body(), r.description(), r.scope(), source, bundled, disabled);
    }

    /**
     * The user's own snippets for {@code language} (just {@code <configDir>/snippets/<lang>.json}, not the
     * bundled or plugin sources), in file order. A snippet with several triggers is collapsed to its first;
     * {@link #entries} has them all.
     */
    public synchronized List<Snippet> userSnippets(String language) {
        String lang = norm(language);
        List<Snippet> out = new ArrayList<>();
        for (Raw r : fileSet(lang).user()) {
            if (!r.marker()) {
                out.add(firstTrigger(r, lang));
            }
        }
        return out;
    }

    /**
     * The bundled (shipped) snippets for {@code language} — only the classpath resource, not the user file
     * or plugins. A multi-trigger snippet is collapsed to its first prefix (as in {@link #userSnippets}).
     */
    public synchronized List<Snippet> bundledSnippets(String language) {
        String lang = norm(language);
        List<Snippet> out = new ArrayList<>();
        for (Raw r : fileSet(lang).bundled()) {
            out.add(firstTrigger(r, lang));
        }
        return out;
    }

    private static Snippet firstTrigger(Raw r, String lang) {
        return new Snippet(
                r.name(), r.prefixes().isEmpty() ? "" : r.prefixes().get(0), r.body(), r.description(), lang);
    }

    /**
     * Why the user snippet file for {@code language} cannot be used at all — a short parse or read error —
     * or {@code null} when it is fine, merely has a bad entry, or is absent. Callers that would
     * <em>write</em> the file ask here first.
     */
    public synchronized String userFileProblem(String language) {
        Problem p = wholeFileProblem(norm(language));
        if (p == null) {
            return null;
        }
        return p.line() > 0 ? p.detail() + " (line " + p.line() + ")" : p.detail();
    }

    /** The problem that makes {@code language}'s user file unusable, or null. */
    public synchronized Problem wholeFileProblem(String language) {
        String lang = norm(language);
        fileSet(lang);
        Problem p = problems.get(userFile(lang) + "|");
        return p != null && p.wholeFile() ? p : null;
    }

    /** The user snippet files that exist but cannot be read, as file names ({@code python.json}), sorted. */
    public synchronized List<String> unreadableUserFiles() {
        List<String> out = new ArrayList<>();
        for (String lang : userSnippetLanguages()) {
            if (wholeFileProblem(lang) != null) {
                out.add(lang + ".json");
            }
        }
        return out;
    }

    /**
     * Loads every user snippet file and returns what is wrong with them (whole files and single entries),
     * in file-name order. Reads files: call it off the FX thread when it is not already cached.
     */
    public synchronized List<Problem> checkUserFiles() {
        List<Problem> out = new ArrayList<>();
        for (String lang : userSnippetLanguages()) {
            fileSet(lang);
            String key = userFile(lang).toString() + "|";
            problems.forEach((k, p) -> {
                if (k.startsWith(key)) {
                    out.add(p);
                }
            });
        }
        return out;
    }

    /** True when {@code language}'s user file holds a comment (which a whole-file rewrite would drop). */
    public synchronized boolean userFileHasComments(String language) {
        Path file = userFile(norm(language));
        try {
            return Files.exists(file) && hasComment(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the user's entry called {@code name} holds a comment — the one thing saving that entry drops. */
    public synchronized boolean userEntryHasComments(String language, String name) {
        try {
            JsoncObject doc = readUserDocument(norm(language));
            JsoncObject.Member m = doc.member(name);
            return m != null && hasComment(doc.valueText(m));
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether JSONC {@code text} has a {@code //} or block comment outside its strings. Pure. */
    static boolean hasComment(String text) {
        boolean inString = false;
        for (int k = 0; k + 1 < text.length(); k++) {
            char c = text.charAt(k);
            if (inString) {
                if (c == '\\') {
                    k++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '/' && (text.charAt(k + 1) == '/' || text.charAt(k + 1) == '*')) {
                return true;
            }
        }
        return false;
    }

    // ---- Writing the user file ----

    /**
     * Saves one snippet to {@code language}'s user file: replaces the entry called {@code oldName} where it
     * stands (a rename keeps its place, its {@code scope} and any field this model does not carry), or
     * appends a new one when {@code oldName} is null or not in the file. Only that entry's text is rewritten,
     * so the rest of the file — comments included — is kept as the user wrote it.
     *
     * @throws IOException when the file exists but cannot be parsed (it is left untouched) or cannot be written
     */
    public synchronized void saveUserEntry(String language, String oldName, Entry e) throws IOException {
        if (e == null || e.name() == null || e.name().isBlank()) {
            return;
        }
        String lang = norm(language);
        JsoncObject doc = readUserDocument(lang);
        JsoncObject.Member old = oldName == null ? null : doc.member(oldName);
        if (old == null) {
            old = doc.member(e.name()); // saving under a name the file already has replaces that entry
        }
        Map<String, Object> before = old == null ? null : valueMap(doc, old);
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("prefix", prefixValue(before == null ? null : before.get("prefix"), e.prefixes()));
        value.put("body", keepShape(before == null ? null : before.get("body"), e.body(), "\n"));
        if (e.description() != null && !e.description().isBlank()) {
            value.put(
                    "description", keepShape(before == null ? null : before.get("description"), e.description(), " "));
        }
        if (e.scope() != null && !e.scope().isBlank()) {
            value.put("scope", e.scope());
        }
        if (before != null) {
            before.forEach((k, v) -> {
                if (!isModelled(k)) {
                    value.put(k, v); // anything else a VS Code file carries
                }
            });
        }
        if (e.disabled()) {
            value.put("disabled", true);
        }
        writeUserDocument(
                lang,
                doc.with(
                        old == null ? null : old.name(),
                        mapper.writeValueAsString(e.name()),
                        writer.writeValueAsString(value)));
    }

    private static boolean isModelled(String key) {
        return key.equals("prefix")
                || key.equals("body")
                || key.equals("description")
                || key.equals("scope")
                || key.equals("disabled");
    }

    /** Removes the user entry called {@code name} (a bundled snippet of that name shows again). */
    public synchronized void removeUserEntry(String language, String name) throws IOException {
        String lang = norm(language);
        JsoncObject doc = readUserDocument(lang);
        if (doc.member(name) != null) {
            writeUserDocument(lang, doc.without(name));
        }
    }

    /**
     * Switches the snippet called {@code name} off or back on. A bundled snippet the user has not edited is
     * switched off by a user entry holding only {@code "disabled": true}; switching it on removes that entry.
     */
    public synchronized void setDisabled(String language, String name, boolean disabled) throws IOException {
        String lang = norm(language);
        JsoncObject doc = readUserDocument(lang);
        JsoncObject.Member m = doc.member(name);
        Map<String, Object> value = m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(valueMap(doc, m));
        if (disabled) {
            value.put("disabled", true);
        } else {
            value.remove("disabled");
            if (m == null) {
                return;
            }
            if (value.isEmpty()) {
                writeUserDocument(lang, doc.without(name));
                return;
            }
        }
        writeUserDocument(
                lang,
                doc.with(m == null ? null : name, mapper.writeValueAsString(name), writer.writeValueAsString(value)));
    }

    /**
     * Makes {@code snippets} the user snippet file for {@code language}: entries no longer listed are removed,
     * the others are saved as {@link #saveUserEntry} does (an entry saved again keeps its other triggers when
     * {@code prefix} is an array — a {@link Snippet} only has the first — and the array form of a body that
     * did not change). Blank-named entries are skipped. A file that exists but cannot be parsed is never
     * replaced and fails with an {@link IOException} instead.
     */
    public synchronized void saveUserSnippets(String language, List<Snippet> snippets) throws IOException {
        String lang = norm(language);
        JsoncObject doc = readUserDocument(lang);
        Set<String> keep = new HashSet<>();
        for (Snippet s : snippets) {
            if (s != null && s.name() != null && !s.name().isBlank()) {
                keep.add(s.name());
            }
        }
        String text = doc.text;
        for (JsoncObject.Member m : doc.members) {
            if (!keep.contains(m.name())) {
                text = JsoncObject.parse(text, mapper.getFactory()).without(m.name());
            }
        }
        for (Snippet s : snippets) {
            if (s == null || s.name() == null || s.name().isBlank()) {
                continue;
            }
            JsoncObject cur = JsoncObject.parse(text, mapper.getFactory());
            JsoncObject.Member old = cur.member(s.name());
            Map<String, Object> before = old == null ? null : valueMap(cur, old);
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("prefix", mergedPrefix(before == null ? null : before.get("prefix"), s.prefix()));
            value.put("body", keepShape(before == null ? null : before.get("body"), s.body(), "\n"));
            if (s.description() != null && !s.description().isBlank()) {
                value.put(
                        "description",
                        keepShape(before == null ? null : before.get("description"), s.description(), " "));
            }
            if (before != null) {
                before.forEach((k, v) -> {
                    if (!k.equals("prefix") && !k.equals("body") && !k.equals("description")) {
                        value.put(k, v); // "scope" and anything else a VS Code file carries
                    }
                });
            }
            if (before != null && value.equals(before)) {
                continue; // untouched: leave its text exactly as the user wrote it
            }
            text = cur.with(
                    old == null ? null : s.name(),
                    mapper.writeValueAsString(s.name()),
                    writer.writeValueAsString(value));
        }
        if (text.isBlank()) {
            text = "{ }\n"; // an empty list still leaves a file, as before
        }
        writeUserDocument(lang, text);
    }

    private JsoncObject readUserDocument(String lang) throws IOException {
        Path file = userFile(lang);
        if (!Files.exists(file)) {
            return JsoncObject.parse("", mapper.getFactory());
        }
        try {
            return JsoncObject.parse(Files.readString(file, StandardCharsets.UTF_8), mapper.getFactory());
        } catch (IOException e) {
            throw new IOException(file.getFileName() + " could not be read and was left untouched: " + describe(e), e);
        }
    }

    private Map<String, Object> valueMap(JsoncObject doc, JsoncObject.Member m) {
        try {
            JsonNode node = mapper.readTree(doc.valueText(m));
            if (node != null && node.isObject()) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                node.properties().forEach(e -> out.put(e.getKey(), plainValue(e.getValue())));
                return out;
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Unreadable snippet entry " + m.name(), e);
        }
        return new LinkedHashMap<>();
    }

    /** A JSON value as plain Java (map, list, string, number, boolean, null) — no data binding involved. */
    private static Object plainValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            node.properties().forEach(e -> out.put(e.getKey(), plainValue(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(plainValue(n)));
            return out;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isNumber() ? node.numberValue() : node.asText();
    }

    private void writeUserDocument(String lang, String text) throws IOException {
        Path file = userFile(lang);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        files.remove(lang);
        resolved.clear();
        problems.keySet().removeIf(k -> k.startsWith(file + "|"));
        onUserFilesChanged.run();
    }

    /** The {@code prefix} value for a set of triggers: a string for one, an array for several. */
    private static Object prefixValue(Object old, List<String> prefixes) {
        List<String> clean = new ArrayList<>();
        for (String p : prefixes == null ? List.<String>of() : prefixes) {
            if (p != null && !p.isBlank() && !clean.contains(p)) {
                clean.add(p);
            }
        }
        if (old instanceof List<?> && prefixes(old).equals(clean)) {
            return old;
        }
        return clean.isEmpty() ? "" : clean.size() == 1 ? clean.get(0) : clean;
    }

    /**
     * The {@code prefix} value to write: when the file had an array of triggers, the array with its first
     * (the one the editor shows) replaced by {@code prefix}; otherwise the plain string.
     */
    private static Object mergedPrefix(Object old, String prefix) {
        String p = prefix == null ? "" : prefix;
        List<String> olds = prefixes(old);
        if (!(old instanceof List<?>) || olds.size() < 2) {
            return p;
        }
        if (olds.get(0).equals(p)) {
            return old;
        }
        List<String> merged = new ArrayList<>();
        if (!p.isBlank()) {
            merged.add(p);
        }
        for (String o : olds.subList(1, olds.size())) {
            if (!merged.contains(o)) {
                merged.add(o);
            }
        }
        return merged.size() == 1 ? merged.get(0) : merged;
    }

    /** {@code old} itself when it is an array that still joins to {@code value}; else the string. */
    private static Object keepShape(Object old, String value, String separator) {
        String v = value == null ? "" : value;
        return old instanceof List<?> && joinText(old, separator).equals(v) ? old : v;
    }

    /** Languages that currently have a user snippet file (the basenames under {@code snippets/}), sorted. */
    public synchronized List<String> userSnippetLanguages() {
        Path dir = config.getConfigDir().resolve("snippets");
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".json"))
                        .map(n -> n.substring(0, n.length() - ".json".length()))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to list user snippet languages", e);
            }
        }
        return out;
    }

    // ---- Loading ----

    private FileSet fileSet(String language) {
        FileSet cached = files.get(language);
        if (cached != null) {
            return cached;
        }
        List<Raw> bundled = new ArrayList<>();
        try (InputStream in = SnippetManager.class.getResourceAsStream("/com/editora/snippets/" + language + ".json")) {
            if (in != null) {
                bundled = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), null);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled snippets for " + language, e);
        }
        List<Raw> plugin = new ArrayList<>();
        for (Path dir : extraDirs) {
            plugin.addAll(readFile(dir.resolve(language + ".json")));
        }
        FileSet fs = new FileSet(bundled, plugin, readFile(userFile(language)));
        files.put(language, fs);
        return fs;
    }

    private List<Raw> readFile(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file);
        } catch (IOException e) {
            report(new Problem(file, Problem.Kind.UNREADABLE, "", describe(e), 0));
            return new ArrayList<>();
        }
    }

    /** The entries of one file's text. Problems are reported against {@code file} (null = a bundled resource). */
    private List<Raw> parse(String text, Path file) {
        List<Raw> out = new ArrayList<>();
        JsoncObject doc;
        try {
            doc = JsoncObject.parse(text, mapper.getFactory());
        } catch (JsoncObject.NotAnObjectException e) {
            report(file, new Problem(file, Problem.Kind.NOT_AN_OBJECT, "", describe(e), 1));
            return out;
        } catch (IOException e) {
            int line = e instanceof JsonProcessingException j && j.getLocation() != null
                    ? j.getLocation().getLineNr()
                    : 0;
            report(file, new Problem(file, Problem.Kind.SYNTAX, "", describe(e), line));
            return out;
        }
        for (JsoncObject.Member m : doc.members) {
            JsonNode node;
            try {
                node = mapper.readTree(doc.valueText(m));
            } catch (IOException e) {
                node = null;
            }
            if (node == null || node.isNull()) {
                continue; // "name": null — nothing there, as before
            }
            if (!node.isObject()) {
                report(file, new Problem(file, Problem.Kind.BAD_ENTRY, m.name(), "", doc.lineOf(m.keyStart())));
                continue;
            }
            JsonNode disabled = node.get("disabled");
            out.add(new Raw(
                    m.name(),
                    prefixes(plain(node.get("prefix"))),
                    joinText(plain(node.get("body")), "\n"),
                    joinText(plain(node.get("description")), " "),
                    joinText(plain(node.get("scope")), ","),
                    disabled != null && disabled.asBoolean(false)));
        }
        return out;
    }

    /** A string, a list of strings, or null — the shapes {@code prefix}/{@code body}/{@code description} take. */
    private static Object plain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            node.forEach(n -> out.add(n.isNull() ? "null" : n.asText()));
            return out;
        }
        return node.isContainerNode() ? null : node.asText();
    }

    private void report(Path file, Problem p) {
        if (file == null) {
            LOG.log(
                    Level.WARNING,
                    "Bundled snippet resource problem: " + p.kind() + " " + p.entry() + " " + p.detail());
            return;
        }
        report(p);
    }

    private void report(Problem p) {
        String key = p.file() + "|" + p.entry();
        if (problems.put(key, p) == null) {
            LOG.log(Level.WARNING, "Snippet file " + p.file() + ": " + p.kind() + " " + p.entry() + " " + p.detail());
            problemListener.accept(p);
        }
    }

    private static String describe(IOException e) {
        if (e instanceof JsoncObject.NotAnObjectException) {
            return "the file must contain a JSON object";
        }
        if (e instanceof JsonProcessingException j) {
            return j.getOriginalMessage();
        }
        return e.getMessage() == null ? "I/O error" : e.getMessage();
    }

    private Resolved resolve(String language) {
        Resolved cached = resolved.get(language);
        if (cached != null) {
            return cached;
        }
        List<List<Loaded>> sets = new ArrayList<>(); // highest priority first
        if (!language.equals(GLOBAL)) {
            sets.add(mergeByName(language));
            String fallback = FALLBACK.get(language);
            if (fallback != null) {
                sets.add(mergeByName(fallback));
            }
        }
        sets.add(mergeByName(GLOBAL));

        List<Snippet> all = new ArrayList<>();
        Map<String, Snippet> byTrigger = new HashMap<>();
        Set<String> listOnly = new HashSet<>();
        Set<String> claimed = new HashSet<>();
        int maxWords = 1;
        Source[] order = {Source.USER, Source.PLUGIN, Source.BUNDLED};
        for (List<Loaded> set : sets) {
            for (Source source : order) {
                Set<String> tier = new HashSet<>();
                for (Loaded l : set) {
                    if (l.source() != source || !scopeAllows(l.raw().scope(), language)) {
                        continue;
                    }
                    for (String prefix : l.raw().prefixes()) {
                        if (claimed.contains(prefix)) {
                            continue; // a higher source already uses this trigger
                        }
                        Snippet s = new Snippet(
                                l.raw().name(), prefix, l.raw().body(), l.raw().description(), l.language());
                        all.add(s);
                        tier.add(prefix);
                        if (byTrigger.putIfAbsent(prefix, s) == null) {
                            maxWords = Math.max(maxWords, prefix.trim().split("\\s+").length);
                            if (source == Source.BUNDLED && l.language().equals(GLOBAL)) {
                                listOnly.add(prefix);
                            }
                        }
                    }
                }
                claimed.addAll(tier);
            }
        }
        Resolved r = new Resolved(List.copyOf(all), byTrigger, listOnly, maxWords);
        resolved.put(language, r);
        return r;
    }

    /** One language's three sources merged by name: bundled, then plugin, then user (which may disable). */
    private List<Loaded> mergeByName(String language) {
        FileSet fs = fileSet(language);
        Map<String, Loaded> byName = new LinkedHashMap<>();
        for (Raw r : fs.bundled()) {
            byName.put(r.name(), new Loaded(r, Source.BUNDLED, language));
        }
        for (Raw r : fs.plugin()) {
            byName.put(r.name(), new Loaded(r, Source.PLUGIN, language));
        }
        for (Raw r : fs.user()) {
            if (r.disabled()) {
                byName.remove(r.name());
            } else {
                byName.put(r.name(), new Loaded(r, Source.USER, language));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Whether a snippet with VS Code {@code scope} (a comma-separated list of language ids; blank = no limit)
     * is offered in {@code language}. A dotted TextMate scope name in the list is not understood and limits
     * nothing. Pure.
     */
    static boolean scopeAllows(String scope, String language) {
        if (scope == null || scope.isBlank()) {
            return true;
        }
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        for (String part : scope.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            // "text.html", "source.python": a TextMate scope selector (older bundles), not a language id.
            if (id.indexOf('.') >= 0 || id.equals(lang) || lang.equals(SCOPE_ALIASES.get(id))) {
                return true;
            }
        }
        return false;
    }

    /** Normalizes the {@code prefix} field (a string or an array of strings) to the non-blank triggers. */
    private static List<String> prefixes(Object prefix) {
        List<String> out = new ArrayList<>();
        if (prefix instanceof List<?> list) {
            for (Object p : list) {
                if (p != null && !p.toString().isBlank()) {
                    out.add(p.toString());
                }
            }
        } else if (prefix != null && !prefix.toString().isBlank()) {
            out.add(prefix.toString());
        }
        return out;
    }

    /** Joins a field that may be a single string or a list of strings (VS Code allows both for
     *  {@code body} and, in some bundles, {@code description}). */
    private static String joinText(Object value, String separator) {
        if (value instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(separator);
                }
                sb.append(String.valueOf(parts.get(i)));
            }
            return sb.toString();
        }
        return value == null ? "" : String.valueOf(value);
    }
}
