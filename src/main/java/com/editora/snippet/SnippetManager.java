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
import org.jspecify.annotations.Nullable;

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
            "clojure",
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
            "maven-pom",
            "mermaid",
            "php",
            "powershell",
            "python",
            "ruby",
            "rust",
            "scala",
            "shell",
            "sql",
            "terraform",
            "toml",
            "typescript",
            "typst",
            "xml",
            "yaml");

    /** A language that also gets another language's snippets (its own win on a clash). A Maven pom is an XML
     *  file with snippets of its own ({@code maven-pom.json}: dep, plugin, profile…) on top of XML's. */
    private static final Map<String, String> FALLBACK = Map.of(
            "javascriptreact",
            "javascript",
            "typescriptreact",
            "typescript",
            "maven-pom",
            "xml",
            // HTML templates get the HTML snippets (HTML review C7)
            "html-template",
            "html",
            "handlebars",
            "html");

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
            UNREADABLE,
            /**
             * A newer Editora wrote the file, in a format this one does not know. Nothing was loaded from it and
             * it is never rewritten; {@code detail} is the name of the copy kept beside it, or empty.
             */
            NEWER_VERSION
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

    /**
     * The one thread snippet files are read and written on, for every window, in the order asked (PERF-01, Pauta
     * 6.0.1). The FX thread never reads one: an ask for a language not loaded yet is answered from what is loaded
     * (nothing of that language) and starts its load here; {@link #warm} loads a buffer's language as the buffer
     * is set up, so the first Tab or completion normally finds it loaded. Files are read and written outside this
     * object's lock, which is only ever held for what is in memory.
     */
    private static final java.util.concurrent.ExecutorService FILE_THREAD =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("snippet-files").factory());

    /** Where file work runs (a seam: a test may run it in place). */
    public java.util.concurrent.Executor fileWork = FILE_THREAD;

    /** Counts the cache drops ({@link #reload}, a new source): a load begun before one is not kept. Guarded. */
    private long cacheGeneration;
    /** Languages whose load {@link #warm} has queued and not finished. Guarded. */
    private final Set<String> warming = new HashSet<>();
    /** The user snippet languages as last listed, for the FX thread, which lists nothing. Guarded. */
    private @Nullable List<String> userLanguages;
    /** Set by {@link #fileSet} when a set is not loaded: what {@link #resolve} made of it is not cached. */
    private boolean incomplete;
    /** Serializes the writers of the user files among themselves (never held by a reader). */
    private final Object fileLock = new Object();
    /** A language whose files are not loaded yet: nothing of it, for now. */
    private static final FileSet NOT_LOADED = new FileSet(List.of(), List.of(), List.of());
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
        userLanguages = null;
        cacheGeneration++;
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
            cacheGeneration++;
        }
    }

    /**
     * Snippets available in {@code language}: its own, then its fallback language's, then global ones — one
     * {@link Snippet} per trigger. See the class comment for what shadows what.
     */
    public List<Snippet> forLanguage(String language) {
        prepare(setsFor(norm(language)));
        synchronized (this) {
            return new ArrayList<>(resolve(norm(language)).all());
        }
    }

    /** The snippet {@code prefix} stands for in {@code language} (the one Tab would expand), or null. */
    public @Nullable Snippet byPrefix(String language, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        prepare(setsFor(norm(language)));
        synchronized (this) {
            return resolve(norm(language)).byTrigger().get(prefix);
        }
    }

    /**
     * As {@link #byPrefix}, for the Tab key: null for a trigger that is offered in the completion popup and
     * the picker only. Those are the <em>bundled global</em> snippets ({@code date}, {@code time}) — ordinary
     * words that would otherwise expand in every file type whenever Tab follows them. A global snippet the
     * user wrote (or a bundled one they edited) is deliberate and expands like any other.
     */
    public @Nullable Snippet byTabTrigger(String language, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        prepare(setsFor(norm(language)));
        synchronized (this) {
            Resolved r = resolve(norm(language));
            return r.listOnly().contains(prefix) ? null : r.byTrigger().get(prefix);
        }
    }

    /** The most whitespace-separated words any trigger of {@code language} has (1 when none has a space). */
    public int maxTriggerWords(String language) {
        prepare(setsFor(norm(language)));
        synchronized (this) {
            return resolve(norm(language)).maxWords();
        }
    }

    // ---- Loading off the FX thread ----

    /** The languages {@code lang} resolves from: itself, its fallback, then {@code global}. */
    private static List<String> setsFor(String lang) {
        List<String> out = new ArrayList<>();
        out.add(lang);
        String fallback = FALLBACK.get(lang);
        if (fallback != null) {
            out.add(fallback);
        }
        if (!lang.equals(GLOBAL)) {
            out.add(GLOBAL);
        }
        return out;
    }

    /**
     * Makes the files of {@code langs} loaded: here, outside the lock, for a caller that may block (a test, the
     * snippet thread); never on the FX thread, which starts their load on the snippet thread instead and is
     * answered from what is loaded meanwhile.
     */
    private void prepare(List<String> langs) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            warmLanguages(langs);
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean all = true;
            for (String lang : langs) {
                all &= loadOutsideLock(lang);
            }
            if (all) {
                return; // else a reload dropped one meanwhile: read it again
            }
        }
    }

    /**
     * Reads {@code lang}'s files unless they are loaded, outside the lock, and keeps them — unless the cache was
     * dropped meanwhile. Returns whether they are loaded now.
     */
    private boolean loadOutsideLock(String lang) {
        long generation;
        List<Path> dirs;
        synchronized (this) {
            if (files.containsKey(lang)) {
                return true;
            }
            generation = cacheGeneration;
            dirs = List.copyOf(extraDirs);
        }
        List<Problem> found = new ArrayList<>();
        FileSet read = readFileSet(lang, dirs, found::add);
        synchronized (this) {
            if (generation != cacheGeneration) {
                return false;
            }
            if (!files.containsKey(lang)) {
                files.put(lang, read);
                resolved.clear(); // a language resolved without these files had none of them
                found.forEach(this::report);
            }
            return true;
        }
    }

    /**
     * Loads {@code language}'s snippet files (its own, its fallback's, the global ones) on the snippet thread
     * unless they are loaded: what a window does for each buffer it sets up, so the buffer's first Tab or
     * completion request finds them in memory. Never blocks.
     */
    public void warm(String language) {
        warmLanguages(setsFor(norm(language)));
    }

    private void warmLanguages(List<String> langs) {
        List<String> queued = new ArrayList<>();
        synchronized (this) {
            for (String lang : langs) {
                if (!files.containsKey(lang) && warming.add(lang)) {
                    queued.add(lang);
                }
            }
        }
        if (queued.isEmpty()) {
            return;
        }
        fileWork.execute(() -> {
            try {
                for (String lang : queued) {
                    loadOutsideLock(lang);
                }
            } finally {
                synchronized (this) {
                    warming.removeAll(queued);
                }
            }
        });
    }

    /** Whether {@code language}'s snippet files (its own, its fallback's, the global ones) are in memory. */
    public synchronized boolean isLoaded(String language) {
        return files.keySet().containsAll(setsFor(norm(language)));
    }

    /**
     * Runs {@code then} on the FX thread once {@code language}'s snippet files are loaded (on the snippet thread):
     * what the Settings page waits for before it lists a language's snippets.
     */
    public void whenLoaded(String language, Runnable then) {
        List<String> langs = setsFor(norm(language));
        fileWork.execute(() -> {
            prepare(langs);
            onFx(then);
        });
    }

    /** Runs {@code action} on the FX thread: at once there, else later (no toolkit: here). */
    private static void onFx(Runnable action) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            javafx.application.Platform.runLater(action);
        } catch (IllegalStateException noToolkit) {
            action.run();
        }
    }

    /** File work on the user's snippet files, which may fail. */
    @FunctionalInterface
    public interface FileWork {
        void run(SnippetManager manager) throws IOException;
    }

    /**
     * Runs {@code work} (a write of a user snippet file) on the snippet thread, after every file work asked for
     * before it, and tells {@code done} on the FX thread, with the failure or {@code null}.
     */
    public void later(FileWork work, Consumer<@Nullable IOException> done) {
        fileWork.execute(() -> {
            IOException failure = null;
            try {
                work.run(this);
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new IOException(e.getMessage(), e);
            }
            IOException result = failure;
            onFx(() -> done.accept(result));
        });
    }

    /** {@link #checkUserFiles} on the snippet thread; {@code then} gets its result on the FX thread. */
    public void checkUserFilesLater(Consumer<List<Problem>> then) {
        fileWork.execute(() -> {
            List<Problem> found = checkUserFiles();
            onFx(() -> then.accept(found));
        });
    }

    /** {@link #userEntryHasComments} on the snippet thread; {@code then} gets the answer on the FX thread. */
    public void userEntryHasCommentsLater(String language, String name, Consumer<Boolean> then) {
        fileWork.execute(() -> {
            boolean has = userEntryHasComments(language, name);
            onFx(() -> then.accept(has));
        });
    }

    /** The user snippet file for a language (may not exist yet); used by the "Edit User Snippets" command. */
    public Path userFile(String language) {
        return config.getConfigDir().resolve("snippets").resolve(norm(language) + ".json");
    }

    /** The language a user snippet file belongs to, or null when {@code file} is not one. */
    public @Nullable String userFileLanguage(Path file) {
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
    public List<String> languagesWithSnippets() {
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
    public List<Entry> entries(String language) {
        prepare(List.of(norm(language)));
        synchronized (this) {
            return entriesLocked(norm(language));
        }
    }

    private List<Entry> entriesLocked(String lang) {
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
    public List<Snippet> userSnippets(String language) {
        String lang = norm(language);
        prepare(List.of(lang));
        List<Snippet> out = new ArrayList<>();
        synchronized (this) {
            for (Raw r : fileSet(lang).user()) {
                if (!r.marker()) {
                    out.add(firstTrigger(r, lang));
                }
            }
        }
        return out;
    }

    /**
     * The bundled (shipped) snippets for {@code language} — only the classpath resource, not the user file
     * or plugins. A multi-trigger snippet is collapsed to its first prefix (as in {@link #userSnippets}).
     */
    public List<Snippet> bundledSnippets(String language) {
        String lang = norm(language);
        prepare(List.of(lang));
        List<Snippet> out = new ArrayList<>();
        synchronized (this) {
            for (Raw r : fileSet(lang).bundled()) {
                out.add(firstTrigger(r, lang));
            }
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
    public @Nullable String userFileProblem(String language) {
        Problem p = wholeFileProblem(norm(language));
        if (p == null) {
            return null;
        }
        return p.line() > 0 ? p.detail() + " (line " + p.line() + ")" : p.detail();
    }

    /** The problem that makes {@code language}'s user file unusable, or null. */
    public @Nullable Problem wholeFileProblem(String language) {
        String lang = norm(language);
        prepare(List.of(lang));
        synchronized (this) {
            Problem p = problems.get(userFile(lang) + "|");
            return p != null && p.wholeFile() ? p : null;
        }
    }

    /** The user snippet files that exist but cannot be read, as file names ({@code python.json}), sorted. */
    public List<String> unreadableUserFiles() {
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
    public List<Problem> checkUserFiles() {
        List<String> langs = userSnippetLanguages();
        prepare(langs);
        List<Problem> out = new ArrayList<>();
        synchronized (this) {
            for (String lang : langs) {
                String key = userFile(lang).toString() + "|";
                problems.forEach((k, p) -> {
                    if (k.startsWith(key)) {
                        out.add(p);
                    }
                });
            }
        }
        return out;
    }

    /**
     * What is known to be wrong with {@code language}'s user file, whole or per entry, as loaded (nothing is
     * read here; see {@link #whenLoaded}).
     */
    public synchronized List<Problem> problemsOf(String language) {
        String key = userFile(norm(language)) + "|";
        List<Problem> out = new ArrayList<>();
        problems.forEach((k, p) -> {
            if (k.startsWith(key)) {
                out.add(p);
            }
        });
        return out;
    }

    /** True when {@code language}'s user file holds a comment (which a whole-file rewrite would drop). */
    public boolean userFileHasComments(String language) {
        Path file = userFile(norm(language));
        try {
            return Files.exists(file) && hasComment(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the user's entry called {@code name} holds a comment — the one thing saving that entry drops. */
    public boolean userEntryHasComments(String language, String name) {
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
    public void saveUserEntry(String language, @Nullable String oldName, Entry e) throws IOException {
        synchronized (fileLock) {
            saveUserEntryUnlocked(language, oldName, e);
        }
    }

    private void saveUserEntryUnlocked(String language, @Nullable String oldName, Entry e) throws IOException {
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
    public void removeUserEntry(String language, String name) throws IOException {
        synchronized (fileLock) {
            String lang = norm(language);
            JsoncObject doc = readUserDocument(lang);
            if (doc.member(name) != null) {
                writeUserDocument(lang, doc.without(name));
            }
        }
    }

    /**
     * Switches the snippet called {@code name} off or back on. A bundled snippet the user has not edited is
     * switched off by a user entry holding only {@code "disabled": true}; switching it on removes that entry.
     */
    public void setDisabled(String language, String name, boolean disabled) throws IOException {
        synchronized (fileLock) {
            setDisabledUnlocked(language, name, disabled);
        }
    }

    private void setDisabledUnlocked(String language, String name, boolean disabled) throws IOException {
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
    public void saveUserSnippets(String language, List<Snippet> snippets) throws IOException {
        synchronized (fileLock) {
            saveUserSnippetsUnlocked(language, snippets);
        }
    }

    private void saveUserSnippetsUnlocked(String language, List<Snippet> snippets) throws IOException {
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
            if (!keep.contains(m.name()) && !isVersionMember(doc, m)) {
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

    /**
     * The format of a user snippet file, written into it as the member {@code schemaVersion} beside its
     * snippets. A file without one is version 1, the format before versions were written. To change the format:
     * raise this and convert an older file's text where it is read.
     */
    public static final int SCHEMA_VERSION = 1;

    /** The version {@code doc} carries: its numeric {@code schemaVersion} member, else the one before versions. */
    private int versionOf(JsoncObject doc) {
        JsoncObject.Member m = doc.member(com.editora.config.migration.UserFileVersion.KEY);
        if (m == null) {
            return com.editora.config.migration.UserFileVersion.BEFORE_VERSIONS;
        }
        String value = doc.valueText(m).strip();
        return value.matches("\\d{1,9}")
                ? Integer.parseInt(value)
                : com.editora.config.migration.UserFileVersion.BEFORE_VERSIONS;
    }

    /** Whether {@code m} is the file's version marker and not a snippet: a member of that name holding a number. */
    private static boolean isVersionMember(JsoncObject doc, JsoncObject.Member m) {
        return com.editora.config.migration.UserFileVersion.KEY.equals(m.name())
                && doc.valueText(m).strip().matches("\\d+");
    }

    /** {@code text} with the version marker added when it has none: every file the editor writes carries it. */
    private String stamped(String text) throws IOException {
        JsoncObject doc = JsoncObject.parse(text, mapper.getFactory());
        return doc.member(com.editora.config.migration.UserFileVersion.KEY) != null
                ? text
                : doc.with(
                        null,
                        mapper.writeValueAsString(com.editora.config.migration.UserFileVersion.KEY),
                        String.valueOf(SCHEMA_VERSION));
    }

    private JsoncObject readUserDocument(String lang) throws IOException {
        com.editora.perf.BlockingSites.enter("snippets read " + lang);
        Path file = userFile(lang);
        if (!Files.exists(file)) {
            return JsoncObject.parse("", mapper.getFactory());
        }
        JsoncObject doc;
        try {
            doc = JsoncObject.parse(Files.readString(file, StandardCharsets.UTF_8), mapper.getFactory());
        } catch (IOException e) {
            throw new IOException(file.getFileName() + " could not be read and was left untouched: " + describe(e), e);
        }
        if (versionOf(doc) > SCHEMA_VERSION) {
            // A change made here would be written in this build's format over a file it does not understand.
            throw new IOException(
                    file.getFileName() + " was written by a newer version of Editora and was left untouched");
        }
        return doc;
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
    private static @Nullable Object plainValue(JsonNode node) {
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
        com.editora.perf.BlockingSites.enter("snippets write " + lang);
        Path file = userFile(lang);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, stamped(text), StandardCharsets.UTF_8);
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Runnable changed;
        synchronized (this) {
            files.remove(lang);
            resolved.clear();
            problems.keySet().removeIf(k -> k.startsWith(file + "|"));
            userLanguages = null;
            cacheGeneration++; // a load of the old file still under way is not kept
            changed = onUserFilesChanged;
        }
        changed.run();
    }

    /** The {@code prefix} value for a set of triggers: a string for one, an array for several. */
    private static Object prefixValue(@Nullable Object old, List<String> prefixes) {
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
    private static Object mergedPrefix(@Nullable Object old, String prefix) {
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
    private static Object keepShape(@Nullable Object old, String value, String separator) {
        String v = value == null ? "" : value;
        return old instanceof List<?> && joinText(old, separator).equals(v) ? old : v;
    }

    /** Languages that currently have a user snippet file (the basenames under {@code snippets/}), sorted. */
    public List<String> userSnippetLanguages() {
        if (javafx.application.Platform.isFxApplicationThread()) {
            List<String> known;
            synchronized (this) {
                known = userLanguages;
            }
            if (known == null) {
                fileWork.execute(this::listUserSnippetLanguages); // listed on the snippet thread, for the next ask
                synchronized (this) {
                    known = userLanguages; // listed already when the work ran in place (a test's seam)
                }
                return known == null ? List.of() : known;
            }
            return known;
        }
        return listUserSnippetLanguages();
    }

    /** Lists the user snippet languages and keeps them for the FX thread. Blocking. */
    private List<String> listUserSnippetLanguages() {
        com.editora.perf.BlockingSites.enter("snippets list");
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
        synchronized (this) {
            userLanguages = List.copyOf(out);
        }
        return out;
    }

    // ---- Loading ----

    /** {@code language}'s files as loaded; under the lock. Not loaded: nothing, and {@link #incomplete} is set. */
    private FileSet fileSet(String language) {
        FileSet cached = files.get(language);
        if (cached != null) {
            return cached;
        }
        incomplete = true;
        return NOT_LOADED;
    }

    /**
     * Reads {@code language}'s files: the bundled resource, each of {@code dirs}' and the user's. Blocking, and
     * never under the lock; what is wrong goes to {@code problems}.
     */
    private FileSet readFileSet(String language, List<Path> dirs, Consumer<Problem> problems) {
        com.editora.perf.BlockingSites.enter("snippets load " + language);
        List<Raw> bundled = new ArrayList<>();
        try (InputStream in = SnippetManager.class.getResourceAsStream("/com/editora/snippets/" + language + ".json")) {
            if (in != null) {
                bundled = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), null, problems);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled snippets for " + language, e);
        }
        List<Raw> plugin = new ArrayList<>();
        for (Path dir : dirs) {
            plugin.addAll(readFile(dir.resolve(language + ".json"), problems));
        }
        return new FileSet(bundled, plugin, readFile(userFile(language), problems));
    }

    private List<Raw> readFile(Path file, Consumer<Problem> problems) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file, problems);
        } catch (IOException e) {
            problems.accept(new Problem(file, Problem.Kind.UNREADABLE, "", describe(e), 0));
            return new ArrayList<>();
        }
    }

    /** The entries of one file's text. Problems are reported against {@code file} (null = a bundled resource). */
    private List<Raw> parse(String text, @Nullable Path file, Consumer<Problem> problems) {
        List<Raw> out = new ArrayList<>();
        JsoncObject doc;
        try {
            doc = JsoncObject.parse(text, mapper.getFactory());
        } catch (JsoncObject.NotAnObjectException e) {
            report(problems, file, Problem.Kind.NOT_AN_OBJECT, "", describe(e), 1);
            return out;
        } catch (IOException e) {
            int line = e instanceof JsonProcessingException j && j.getLocation() != null
                    ? j.getLocation().getLineNr()
                    : 0;
            report(problems, file, Problem.Kind.SYNTAX, "", describe(e), line);
            return out;
        }
        int stored = versionOf(doc);
        if (stored > SCHEMA_VERSION) {
            // A newer Editora's file: nothing is loaded from it. The user's own file stays where it is, for
            // that newer Editora, with a copy beside it; a plugin's is only passed over.
            Path copy = file != null && userFileLanguage(file) != null
                    ? com.editora.config.migration.UserFileVersion.keepCopyOfNewer(file, stored)
                    : null;
            report(
                    problems,
                    file,
                    Problem.Kind.NEWER_VERSION,
                    "",
                    copy == null ? "" : copy.getFileName().toString(),
                    0);
            return out;
        }
        for (JsoncObject.Member m : doc.members) {
            if (isVersionMember(doc, m)) {
                continue; // the file's format version, not a snippet
            }
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
                report(problems, file, Problem.Kind.BAD_ENTRY, m.name(), "", doc.lineOf(m.keyStart()));
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
    private static @Nullable Object plain(JsonNode node) {
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

    private static void report(
            Consumer<Problem> problems, @Nullable Path file, Problem.Kind kind, String entry, String detail, int line) {
        if (file == null) {
            LOG.log(Level.WARNING, "Bundled snippet resource problem: " + kind + " " + entry + " " + detail);
            return;
        }
        problems.accept(new Problem(file, kind, entry, detail, line));
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
        incomplete = false;
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
        if (!incomplete) {
            resolved.put(language, r); // one made while a set was still loading is made again once it has
        }
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
    private static List<String> prefixes(@Nullable Object prefix) {
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
    private static String joinText(@Nullable Object value, String separator) {
        if (value instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                // a description line that ends in a line break needs no space after it: the second line of a
                // two-line shell description started with one in the documentation panel (Bash review C15)
                if (i > 0 && !(" ".equals(separator) && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n')) {
                    sb.append(separator);
                }
                sb.append(String.valueOf(parts.get(i)));
            }
            return sb.toString();
        }
        return value == null ? "" : String.valueOf(value);
    }
}
