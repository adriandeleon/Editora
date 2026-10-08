package com.editora.template;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.editora.config.ConfigManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and serves file templates from three places, later ones overriding earlier ones by id:
 *
 * <ol>
 *   <li><b>bundled</b> — {@code /com/editora/templates/<id>.json}, enumerated by a bundled
 *       {@code index.json} (a classpath/JAR directory can't be listed);
 *   <li><b>plugin</b> — each enabled plugin's {@code templates/*.json};
 *   <li><b>user</b> — {@code <configDir>/templates/*.json}. The user's own file always wins: a plugin
 *       must not be able to replace "Java Class" behind the user's back.
 * </ol>
 *
 * <p><b>Nothing is cached</b> except the bundled set (which cannot change). Every window has its own
 * registry over the same folder, so a cache made a template added in one window invisible in the others
 * until each ran "Reload Templates"; reading a handful of small files when a picker opens costs nothing
 * next to that.
 *
 * <p>The JSON shape (unknown fields ignored; a body may be a string or an array of lines):
 * <pre>{ "name", "description", "language", "fileName", "body", "labels": { "variable": "Label" } }</pre>
 * or, for a multi-file template, a {@code "files": [{ "path", "body" }]} array instead of fileName/body.
 * A file that is not that shape is <em>skipped and reported</em> ({@link #problems()}) rather than loaded
 * as a nameless, empty row.
 */
public final class TemplateRegistry {

    private static final Logger LOG = Logger.getLogger(TemplateRegistry.class.getName());
    private static final String DIR = "/com/editora/templates/";
    /** The bundled index's stem: a user file with this id could never be told apart from the index. */
    public static final String RESERVED_ID = "index";

    /** Why a template file was skipped. */
    public enum ProblemKind {
        /** Not parseable as JSON ({@code detail} = the parser's message, {@code line} = where). */
        MALFORMED_JSON,
        /** Valid JSON that is not an object (an array, a string, {@code null}). */
        NOT_AN_OBJECT,
        /** No {@code name}, or a blank one. */
        MISSING_NAME,
        /** Neither a {@code body} nor a non-empty {@code files} array, or a body that is not text. */
        NO_CONTENT,
        /** {@code files} is not an array of objects that each have a non-blank {@code path}. */
        BAD_FILES,
        /** The file is called {@code index.json}, which is reserved. */
        RESERVED_ID,
        /** The file could not be read ({@code detail} = the IO error). */
        UNREADABLE
    }

    /** A template file that was skipped: which, why, and (for a syntax error) on which line. */
    public record Problem(Path file, ProblemKind kind, String detail, int line) {}

    /** The result of one read of every source. */
    public record Loaded(List<Template> templates, List<Problem> problems) {}

    private final ConfigManager config;
    private final ObjectMapper mapper = new ObjectMapper();
    /** The bundled templates, read once (they are classpath resources and cannot change). */
    private List<Template> bundled;
    /** Extra template source dirs (a plugin's {@code templates/}); they override bundled, not user. */
    private final List<Path> extraDirs = new ArrayList<>();

    public TemplateRegistry(ConfigManager config) {
        this.config = config;
    }

    /** Kept for callers that used to drop a cache: every read is already fresh. */
    public synchronized void reload() {
        // Nothing to drop — see the class comment.
    }

    /** Adds an extra template source dir (a plugin's {@code templates/}). */
    public synchronized void addExtraSourceDir(Path dir) {
        if (dir != null && !extraDirs.contains(dir)) {
            extraDirs.add(dir);
        }
    }

    /** All templates (bundled, then plugin, then user — each overriding by id), in a stable order. */
    public synchronized List<Template> all() {
        return load().templates();
    }

    /** The template files skipped by a read of every source, in source order. */
    public synchronized List<Problem> problems() {
        return load().problems();
    }

    /** Reads every source once: the templates and the files that had to be skipped. */
    public synchronized Loaded load() {
        Map<String, Template> byId = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        for (Template t : bundledTemplates()) {
            byId.put(t.id(), t);
        }
        for (Path extra : extraDirs) {
            scanDir(extra, Template.Origin.PLUGIN, byId, problems);
        }
        scanDir(userDir(), Template.Origin.USER, byId, problems); // the user's own file wins
        return new Loaded(List.copyOf(byId.values()), List.copyOf(problems));
    }

    /** The user templates directory ({@code <configDir>/templates}); may not exist yet. */
    public Path userDir() {
        return config.getConfigDir().resolve("templates");
    }

    /** The bundled (shipped) templates — only the classpath resources, not the user dir or plugins. */
    public synchronized List<Template> bundledTemplates() {
        if (bundled == null) {
            List<Template> out = new ArrayList<>();
            for (String id : bundledIds()) {
                Template t = readBundled(id);
                if (t != null) {
                    out.add(t);
                }
            }
            bundled = List.copyOf(out);
        }
        return bundled;
    }

    /** The plugins' templates, id-keyed (a later plugin overriding an earlier one). */
    public synchronized List<Template> pluginTemplates() {
        Map<String, Template> byId = new LinkedHashMap<>();
        for (Path extra : extraDirs) {
            scanDir(extra, Template.Origin.PLUGIN, byId, new ArrayList<>());
        }
        return new ArrayList<>(byId.values());
    }

    /** The user's own templates ({@code <configDir>/templates/*.json}), id-keyed in file order. */
    public synchronized List<Template> userTemplates() {
        Map<String, Template> byId = new LinkedHashMap<>();
        scanDir(userDir(), Template.Origin.USER, byId, new ArrayList<>());
        return new ArrayList<>(byId.values());
    }

    /** Every {@code *.json} file in the user templates folder, valid or not, sorted by name. */
    public synchronized List<Path> userFiles() {
        return jsonFiles(userDir());
    }

    /**
     * True when {@code id} can be a template's file stem: a file name every platform accepts (no path
     * separators, no reserved characters or device names), so {@code <id>.json} always stays inside
     * {@link #userDir()} — and not the reserved {@link #RESERVED_ID}.
     */
    public static boolean isValidId(String id) {
        return id != null && !id.isBlank() && PortableFileName.isPortable(id) && !isReservedId(id);
    }

    /** True for the one id a template may not have: {@code index}, the bundled list's own file name. */
    public static boolean isReservedId(String id) {
        return id != null && RESERVED_ID.equalsIgnoreCase(id.trim());
    }

    /**
     * Writes {@code t} as the user template {@code <configDir>/templates/<id>.json} (creating the dir).
     * Single-file templates write {@code fileName}/{@code body}; multi-file ones write a {@code files}
     * array. A blank name is written as the id, so the saved file is always one this registry loads.
     */
    public synchronized void saveUserTemplate(Template t) throws IOException {
        if (t == null || t.id() == null || t.id().isBlank()) {
            return;
        }
        if (!isValidId(t.id())) {
            throw new IOException("Invalid template id: " + t.id());
        }
        Files.createDirectories(userDir());
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name() == null || t.name().isBlank() ? t.id() : t.name());
        if (t.description() != null && !t.description().isBlank()) {
            m.put("description", t.description());
        }
        if (t.language() != null && !t.language().isBlank()) {
            m.put("language", t.language());
        }
        if (!t.labels().isEmpty()) {
            m.put("labels", new java.util.TreeMap<>(t.labels()));
        }
        if (t.isMultiFile()) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (TemplateFile f : t.files()) {
                LinkedHashMap<String, Object> fm = new LinkedHashMap<>();
                fm.put("path", f.path());
                fm.put("body", lines(f.body()));
                files.add(fm);
            }
            m.put("files", files);
        } else {
            if (t.fileName() != null && !t.fileName().isBlank()) {
                m.put("fileName", t.fileName());
            }
            m.put("body", t.body() == null ? "" : t.body());
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(m);
        Path file = userFile(t.id());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** A multi-line body as an array of lines (readable to hand-edit); a one-line body as a string. */
    private static Object lines(String body) {
        String text = body == null ? "" : body;
        return text.indexOf('\n') < 0 ? text : List.of(text.split("\n", -1));
    }

    /**
     * Copies {@code t} (a bundled or plugin template) into the user templates folder under the same id, so
     * it overrides the original and can be edited — multi-file templates included. Returns the file, which
     * is left untouched when the user already has one for that id.
     */
    public synchronized Path duplicateToUser(Template t) throws IOException {
        if (t == null || !isValidId(t.id())) {
            throw new IOException("Invalid template id: " + (t == null ? null : t.id()));
        }
        Path file = userFile(t.id());
        if (!Files.exists(file)) {
            saveUserTemplate(t.asUserCopy());
        }
        return file;
    }

    /** Deletes the user template file for {@code id} (reverting to a plugin's or the bundled one, if any). */
    public synchronized void deleteUserTemplate(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return;
        }
        if (!PortableFileName.isPortable(id)) {
            throw new IOException("Invalid template id: " + id); // never resolve a path-shaped id
        }
        Files.deleteIfExists(userFile(id));
    }

    /**
     * The user's file for {@code id}: the existing one whatever the case of its extension
     * ({@code Notes.JSON}), else {@code <id>.json}.
     */
    public synchronized Path userFile(String id) {
        for (Path p : jsonFiles(userDir())) {
            if (stem(p.getFileName().toString()).equals(id)) {
                return p;
            }
        }
        return userDir().resolve(id + ".json");
    }

    private static List<Path> jsonFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to list templates in " + dir, e);
            return List.of();
        }
    }

    /** Adds every valid {@code *.json} template in {@code dir} (id = file stem), overriding earlier entries. */
    private void scanDir(Path dir, Template.Origin origin, Map<String, Template> byId, List<Problem> problems) {
        String source = origin == Template.Origin.PLUGIN ? pluginName(dir) : "";
        for (Path p : jsonFiles(dir)) {
            String id = stem(p.getFileName().toString());
            if (isReservedId(id)) {
                if (origin == Template.Origin.USER) {
                    problems.add(new Problem(p, ProblemKind.RESERVED_ID, "", 0));
                }
                continue; // a plugin may ship its own index.json the way the bundled set does
            }
            try (InputStream in = Files.newInputStream(p)) {
                Template t = parse(mapper.readTree(in), id, origin, source, p, problems);
                if (t != null) {
                    byId.remove(id); // re-insert, so an override takes the later source's position
                    byId.put(id, t);
                }
            } catch (JsonProcessingException e) {
                int line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
                problems.add(new Problem(p, ProblemKind.MALFORMED_JSON, e.getOriginalMessage(), line));
            } catch (IOException e) {
                problems.add(new Problem(p, ProblemKind.UNREADABLE, String.valueOf(e.getMessage()), 0));
            }
        }
    }

    /** A plugin's display name for its {@code templates/} dir: the plugin folder's name. */
    private static String pluginName(Path templatesDir) {
        Path parent = templatesDir.toAbsolutePath().normalize().getParent();
        return parent == null || parent.getFileName() == null
                ? ""
                : parent.getFileName().toString();
    }

    private List<String> bundledIds() {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(DIR + "index.json")) {
            if (in != null) {
                return mapper.readValue(in, new TypeReference<List<String>>() {});
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read bundled template index", e);
        }
        return List.of();
    }

    private Template readBundled(String id) {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(DIR + id + ".json")) {
            if (in == null) {
                return null;
            }
            List<Problem> problems = new ArrayList<>();
            Template t = parse(mapper.readTree(in), id, Template.Origin.BUNDLED, "", Path.of(id + ".json"), problems);
            if (t == null) {
                LOG.warning("Invalid bundled template " + id + ": " + problems);
            }
            return t;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Malformed bundled template " + id + " — skipped", e);
            return null;
        }
    }

    /** Validates {@code root} and builds the template, or records why not and returns null. */
    private static Template parse(
            JsonNode root, String id, Template.Origin origin, String source, Path file, List<Problem> problems) {
        if (root == null || !root.isObject()) {
            problems.add(new Problem(file, ProblemKind.NOT_AN_OBJECT, "", 0));
            return null;
        }
        String name = textOf(root.get("name"), " ");
        if (name == null || name.isBlank()) {
            problems.add(new Problem(file, ProblemKind.MISSING_NAME, "", 0));
            return null;
        }
        String description = textOf(root.get("description"), " ");
        String language = textOf(root.get("language"), " ");
        String fileName = textOf(root.get("fileName"), "");
        List<TemplateFile> files = null;
        JsonNode filesNode = root.get("files");
        if (filesNode != null && !filesNode.isNull()) {
            if (!filesNode.isArray()) {
                problems.add(new Problem(file, ProblemKind.BAD_FILES, "", 0));
                return null;
            }
            files = new ArrayList<>();
            for (JsonNode f : filesNode) {
                String path = f != null && f.isObject() ? textOf(f.get("path"), "") : null;
                String body = f != null && f.isObject() ? textOf(f.get("body"), "\n") : null;
                if (path == null || path.isBlank() || (f.has("body") && body == null)) {
                    problems.add(new Problem(file, ProblemKind.BAD_FILES, "", 0));
                    return null;
                }
                files.add(new TemplateFile(path, body == null ? "" : body));
            }
        }
        String body = textOf(root.get("body"), "\n");
        boolean multi = files != null && !files.isEmpty();
        if (!multi && (!root.has("body") || body == null)) {
            problems.add(new Problem(file, ProblemKind.NO_CONTENT, "", 0));
            return null;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode labelsNode = root.get("labels");
        if (labelsNode != null && labelsNode.isObject()) {
            labelsNode.fieldNames().forEachRemaining(key -> {
                JsonNode value = labelsNode.get(key);
                if (value.isTextual() && !value.asText().isBlank()) {
                    labels.put(key, value.asText());
                }
            });
        }
        return new Template(
                id,
                name.strip(),
                description == null ? "" : description,
                language == null ? "" : language.strip(),
                fileName == null ? "" : fileName,
                body == null ? "" : body,
                multi ? List.copyOf(files) : null,
                labels,
                origin,
                source);
    }

    /**
     * A field that may be one string or an array of strings (VS Code style), joined with
     * {@code separator}; {@code ""} when absent, null when it is some other shape.
     */
    private static String textOf(JsonNode node, String separator) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (JsonNode part : node) {
                if (!part.isValueNode()) {
                    return null;
                }
                if (i++ > 0) {
                    sb.append(separator);
                }
                sb.append(part.asText());
            }
            return sb.toString();
        }
        return node.isValueNode() ? node.asText() : null;
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
