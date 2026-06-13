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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and serves snippets. Each language has a bundled resource
 * ({@code /com/editora/snippets/<lang>.json}) and an optional user file
 * ({@code <configDir>/snippets/<lang>.json}); {@code global} applies to every file. User snippets
 * override bundled ones with the same prefix, and a language snippet overrides a global one.
 *
 * <p>JSON is the VS Code format: a map of name → {@code {prefix, body, description}}, where
 * {@code body} is a string or an array of lines. Results are cached until {@link #reload()}.
 */
public final class SnippetManager {

    private static final Logger LOG = Logger.getLogger(SnippetManager.class.getName());
    private static final String GLOBAL = "global";

    private final ConfigManager config;
    // Lenient: real-world VS Code snippet files (e.g. friendly-snippets) carry extra fields like "scope".
    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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
        String lang = language == null || language.isBlank() ? GLOBAL : language;
        return config.getConfigDir().resolve("snippets").resolve(lang + ".json");
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
