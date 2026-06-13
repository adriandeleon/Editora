package com.editora.template;

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
import java.util.stream.Stream;

import com.editora.config.ConfigManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and serves file templates. Bundled templates live at {@code /com/editora/templates/<id>.json}
 * and are enumerated by a bundled {@code index.json} (a JSON array of ids, since a classpath/JAR
 * directory can't be listed); user templates are any {@code *.json} under
 * {@code <configDir>/templates/} and override a bundled template with the same id. Results are cached
 * until {@link #reload()}.
 *
 * <p>The JSON shape mirrors snippets (lenient — unknown fields ignored; string-or-array bodies):
 * <pre>{ "name", "description", "language", "fileName", "body" }</pre>
 * or, for multi-file templates, a {@code "files": [{ "path", "body" }]} array instead of fileName/body.
 */
public final class TemplateRegistry {

    private static final Logger LOG = Logger.getLogger(TemplateRegistry.class.getName());
    private static final String DIR = "/com/editora/templates/";

    private final ConfigManager config;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private List<Template> cache;
    /** Extra template source dirs (a plugin's {@code templates/}); their {@code *.json} win by id. */
    private final List<Path> extraDirs = new ArrayList<>();

    public TemplateRegistry(ConfigManager config) {
        this.config = config;
    }

    /** Drops the cache so edited/added user template files are picked up. */
    public synchronized void reload() {
        cache = null;
    }

    /** Adds an extra template source dir (a plugin's {@code templates/}); its {@code *.json} win by id. */
    public synchronized void addExtraSourceDir(Path dir) {
        if (dir != null && !extraDirs.contains(dir)) {
            extraDirs.add(dir);
            cache = null;
        }
    }

    /** All templates (bundled + user, user winning on id), in a stable order. */
    public synchronized List<Template> all() {
        if (cache == null) {
            cache = load();
        }
        return cache;
    }

    /** The user templates directory ({@code <configDir>/templates}); may not exist yet. */
    public Path userDir() {
        return config.getConfigDir().resolve("templates");
    }

    private List<Template> load() {
        Map<String, Template> byId = new LinkedHashMap<>();
        for (String id : bundledIds()) {
            Template t = readBundled(id);
            if (t != null) {
                byId.put(id, t);
            }
        }
        scanDir(userDir(), byId); // user overrides bundled
        for (Path extra : extraDirs) { // plugin templates win by id
            scanDir(extra, byId);
        }
        return new ArrayList<>(byId.values());
    }

    /** Adds every {@code *.json} template in {@code dir} (id = file stem), overriding any earlier entry. */
    private void scanDir(Path dir, Map<String, Template> byId) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        String id = stem(p.getFileName().toString());
                        if (!id.equals("index")) {
                            Template t = readUser(p, id);
                            if (t != null) {
                                byId.put(id, t);
                            }
                        }
                    });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to list templates in " + dir, e);
        }
    }

    private List<String> bundledIds() {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(DIR + "index.json")) {
            if (in != null) {
                return mapper.readValue(in, new TypeReference<List<String>>() { });
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled template index", e);
        }
        return List.of();
    }

    private Template readBundled(String id) {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(DIR + id + ".json")) {
            return in == null ? null : toTemplate(mapper.readValue(in, Dto.class), id);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Malformed bundled template " + id + " — skipped", e);
            return null;
        }
    }

    private Template readUser(Path file, String id) {
        try (InputStream in = Files.newInputStream(file)) {
            return toTemplate(mapper.readValue(in, Dto.class), id);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Malformed user template " + file + " — skipped", e);
            return null;
        }
    }

    private static Template toTemplate(Dto dto, String id) {
        if (dto == null) {
            return null;
        }
        String name = dto.name == null ? id : String.valueOf(dto.name);
        String description = joinText(dto.description, " ");
        String language = dto.language == null ? "" : String.valueOf(dto.language);
        List<TemplateFile> files = null;
        if (dto.files != null && !dto.files.isEmpty()) {
            files = new ArrayList<>();
            for (FileDto f : dto.files) {
                if (f != null && f.path != null) {
                    files.add(new TemplateFile(String.valueOf(f.path), joinText(f.body, "\n")));
                }
            }
        }
        String fileName = dto.fileName == null ? "" : String.valueOf(dto.fileName);
        String body = joinText(dto.body, "\n");
        return new Template(id, name, description, language, fileName, body, files);
    }

    /** Joins a field that may be a single string or a list of strings (VS Code style). */
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

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Jackson DTO (public fields so no getter-opens are needed). */
    static final class Dto {
        public Object name;        // String
        public Object description; // String or List<String>
        public Object language;    // String
        public Object fileName;    // String
        public Object body;        // String or List<String>
        public List<FileDto> files;
    }

    static final class FileDto {
        public Object path; // String
        public Object body; // String or List<String>
    }
}
