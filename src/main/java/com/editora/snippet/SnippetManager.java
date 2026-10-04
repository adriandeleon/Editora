package com.editora.snippet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.ConfigManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Loads and serves snippets. Each language has a bundled resource
 * ({@code /com/editora/snippets/<lang>.json}) and an optional user file
 * ({@code <configDir>/snippets/<lang>.json}); {@code global} applies to every file. User snippets
 * override bundled ones with the same prefix, and a language snippet overrides a global one.
 *
 * <p>JSON is the VS Code format: a map of name → {@code {prefix, body, description}}, where
 * {@code body} is a string or an array of lines. Like VS Code's own snippet files it is read as JSONC —
 * {@code //} and block comments and trailing commas are accepted. Results are cached until {@link #reload()}.
 */
public final class SnippetManager {

    private static final Logger LOG = Logger.getLogger(SnippetManager.class.getName());
    private static final String GLOBAL = "global";

    private final ConfigManager config;
    // Lenient: real-world VS Code snippet files (e.g. friendly-snippets) carry extra fields like "scope",
    // and VS Code's own template for a user snippet file opens with a block of // comments.
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    /** language → its own snippets (bundled+user merged), loaded lazily. */
    private final Map<String, List<Snippet>> cache = new LinkedHashMap<>();
    /** Extra source dirs (e.g. plugin {@code snippets/} folders), each holding {@code <lang>.json} files. */
    private final List<Path> extraDirs = new ArrayList<>();

    public SnippetManager(ConfigManager config) {
        this.config = config;
    }

    /** Drops the cache so edited user snippet files are picked up. */
    public synchronized void reload() {
        cache.clear();
    }

    /** Adds an extra snippet source dir (a plugin's {@code snippets/}); its {@code <lang>.json} files win
     *  over bundled + user snippets on a prefix clash. Drops the cache so it's picked up. */
    public synchronized void addExtraSourceDir(Path dir) {
        if (dir != null && !extraDirs.contains(dir)) {
            extraDirs.add(dir);
            cache.clear();
        }
    }

    /** Snippets available in {@code language}: its own plus global, language winning on prefix clashes. */
    public synchronized List<Snippet> forLanguage(String language) {
        String lang = language == null || language.isBlank() ? GLOBAL : language;
        Map<String, Snippet> byPrefix = new LinkedHashMap<>();
        if (!lang.equals(GLOBAL)) {
            for (Snippet s : load(GLOBAL)) {
                byPrefix.put(s.prefix(), s);
            }
        }
        for (Snippet s : load(lang)) {
            byPrefix.put(s.prefix(), s); // language overrides global
        }
        return new ArrayList<>(byPrefix.values());
    }

    /** The snippet whose prefix exactly matches {@code prefix} for {@code language}, or null. */
    public synchronized Snippet byPrefix(String language, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        for (Snippet s : forLanguage(language)) {
            if (s.prefix().equals(prefix)) {
                return s;
            }
        }
        return null;
    }

    /** The user snippet file for a language (may not exist yet); used by the "Edit User Snippets" command. */
    public Path userFile(String language) {
        return config.getConfigDir().resolve("snippets").resolve(norm(language) + ".json");
    }

    private static String norm(String language) {
        return language == null || language.isBlank() ? GLOBAL : language;
    }

    /**
     * The user's own snippets for {@code language} (just {@code <configDir>/snippets/<lang>.json}, not the
     * bundled or plugin sources), in file order — for the Settings → Snippets management UI. A snippet with
     * multiple triggers is collapsed to its first prefix (the editor manages one trigger per snippet).
     */
    public synchronized List<Snippet> userSnippets(String language) {
        String lang = norm(language);
        List<Snippet> out = new ArrayList<>();
        Path file = userFile(lang);
        if (!Files.isReadable(file)) {
            return out;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Dto> map = mapper.readValue(in, new TypeReference<Map<String, Dto>>() {});
            if (map == null) {
                return out; // a snippet file whose content is the literal `null` ⇒ no snippets
            }
            map.forEach((name, dto) -> {
                if (dto == null) {
                    return;
                }
                List<String> ps = prefixes(dto.prefix);
                out.add(new Snippet(
                        name,
                        ps.isEmpty() ? "" : ps.get(0),
                        joinText(dto.body, "\n"),
                        joinText(dto.description, " "),
                        lang));
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read user snippets " + file, e);
        }
        return out;
    }

    /**
     * Why the user snippet file for {@code language} cannot be used — a short parse or read error — or
     * {@code null} when it is fine or simply absent. {@link #userSnippets} and the editor's own loading
     * treat an unreadable file as "no snippets"; callers that would <em>write</em> the file, or that want to
     * tell the user their snippets were not loaded, ask here first.
     */
    public synchronized String userFileProblem(String language) {
        Path file = userFile(norm(language));
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            mapper.readValue(in, new TypeReference<Map<String, Dto>>() {});
            return null;
        } catch (IOException e) {
            return describe(e);
        }
    }

    /** The user snippet files that exist but cannot be read, as file names ({@code python.json}), sorted. */
    public synchronized List<String> unreadableUserFiles() {
        List<String> out = new ArrayList<>();
        for (String lang : userSnippetLanguages()) {
            if (userFileProblem(lang) != null) {
                out.add(lang + ".json");
            }
        }
        return out;
    }

    private static String describe(IOException e) {
        if (e instanceof JsonProcessingException j) {
            String where =
                    j.getLocation() == null ? "" : " (line " + j.getLocation().getLineNr() + ")";
            return j.getOriginalMessage() + where;
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * The bundled (shipped) snippets for {@code language} — only the classpath resource, not the user file
     * or plugins. Shown read-only in the Settings → Snippets page; editing one writes a user override. A
     * multi-trigger snippet is collapsed to its first prefix (as in {@link #userSnippets}).
     */
    public synchronized List<Snippet> bundledSnippets(String language) {
        String lang = norm(language);
        List<Snippet> out = new ArrayList<>();
        try (InputStream in = SnippetManager.class.getResourceAsStream("/com/editora/snippets/" + lang + ".json")) {
            if (in == null) {
                return out;
            }
            Map<String, Dto> map = mapper.readValue(in, new TypeReference<Map<String, Dto>>() {});
            if (map == null) {
                return out; // a bundled snippet resource whose content is the literal `null`
            }
            map.forEach((name, dto) -> {
                if (dto == null) {
                    return;
                }
                List<String> ps = prefixes(dto.prefix);
                out.add(new Snippet(
                        name,
                        ps.isEmpty() ? "" : ps.get(0),
                        joinText(dto.body, "\n"),
                        joinText(dto.description, " "),
                        lang));
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled snippets for " + lang, e);
        }
        return out;
    }

    /**
     * Writes {@code snippets} as the user snippet file for {@code language} (VS Code shape:
     * {@code {name: {prefix, body, description}}}), creating {@code snippets/} as needed, then drops the
     * cache so the change is live. Blank-named entries are skipped; a blank description is omitted.
     *
     * <p>The file is rewritten <em>from what it already holds</em>: an entry that is saved again keeps the
     * fields this model does not carry ({@code scope}, …), its other triggers when {@code prefix} is an
     * array (a {@link Snippet} only has the first), and the array form of a body or description that did
     * not change. A file that exists but cannot be parsed is never replaced — that would silently discard
     * whatever the user wrote in it — and fails with an {@link IOException} instead. Comments in a JSONC
     * file are not kept by a rewrite.
     */
    public synchronized void saveUserSnippets(String language, List<Snippet> snippets) throws IOException {
        String lang = norm(language);
        Path file = userFile(lang);
        Files.createDirectories(file.getParent());
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                Map<String, Map<String, Object>> read =
                        mapper.readValue(in, new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
                if (read != null) {
                    existing = read;
                }
            } catch (IOException e) {
                throw new IOException(
                        file.getFileName() + " could not be read and was left untouched: " + describe(e), e);
            }
        }
        LinkedHashMap<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Snippet s : snippets) {
            if (s == null || s.name() == null || s.name().isBlank()) {
                continue;
            }
            Map<String, Object> old = existing.get(s.name());
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("prefix", mergedPrefix(old == null ? null : old.get("prefix"), s.prefix()));
            entry.put("body", keepShape(old == null ? null : old.get("body"), s.body(), "\n"));
            if (s.description() != null && !s.description().isBlank()) {
                entry.put("description", keepShape(old == null ? null : old.get("description"), s.description(), " "));
            }
            if (old != null) {
                old.forEach((k, v) -> {
                    if (!k.equals("prefix") && !k.equals("body") && !k.equals("description")) {
                        entry.put(k, v); // "scope" and anything else a VS Code file carries
                    }
                });
            }
            out.put(s.name(), entry);
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        cache.remove(lang); // forLanguage(lang) re-reads; global is independent
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

    private List<Snippet> load(String language) {
        return cache.computeIfAbsent(language, this::loadLanguage);
    }

    private List<Snippet> loadLanguage(String language) {
        Map<String, Snippet> byPrefix = new LinkedHashMap<>();
        try (InputStream bundled =
                SnippetManager.class.getResourceAsStream("/com/editora/snippets/" + language + ".json")) {
            if (bundled != null) {
                readInto(byPrefix, bundled, language);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled snippets for " + language, e);
        }
        Path user = userFile(language);
        if (Files.isReadable(user)) {
            try (InputStream in = Files.newInputStream(user)) {
                readInto(byPrefix, in, language); // user overrides bundled
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read user snippets " + user, e);
            }
        }
        for (Path dir : extraDirs) { // plugin-contributed snippets win over user + bundled
            Path f = dir.resolve(language + ".json");
            if (Files.isReadable(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    readInto(byPrefix, in, language);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to read plugin snippets " + f, e);
                }
            }
        }
        return new ArrayList<>(byPrefix.values());
    }

    private void readInto(Map<String, Snippet> byPrefix, InputStream in, String language) {
        try {
            Map<String, Dto> map = mapper.readValue(in, new TypeReference<Map<String, Dto>>() {});
            if (map == null) {
                return; // a snippet file whose content is the literal `null`
            }
            map.forEach((name, dto) -> {
                if (dto == null) {
                    return;
                }
                String body = joinText(dto.body, "\n");
                String description = joinText(dto.description, " ");
                for (String prefix : prefixes(dto.prefix)) { // VS Code allows a string or array of triggers
                    byPrefix.put(prefix, new Snippet(name, prefix, body, description, language));
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Malformed snippet JSON for " + language + " — skipped", e);
        }
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

    /** Jackson DTO for one snippet entry (public fields so no module-open of getters is needed). */
    static final class Dto {
        public Object prefix; // String or List<String>
        public Object body; // String or List<String>
        public Object description; // String or List<String>
    }
}
