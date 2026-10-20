package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pom.xml merged with the {@code <parent>} chain found <b>beside it on disk</b> — the one piece of
 * inheritance a module of a multi-module build needs for its tasks tree to be useful. Profiles and
 * {@code <build><plugins>} (and {@code <pluginManagement>}) are declared once in the parent in that layout;
 * read literally, a module offered no profile toggles and no {@code spring-boot:run} / {@code javafx:run}
 * rows, and the user had to open the parent pom to see them.
 *
 * <p>Still no effective pom, still offline, still cheap: the parent is followed through {@code <relativePath>}
 * (Maven's default {@code ../pom.xml}), only while the file exists under {@code boundary} (the open project's
 * root — never a folder the user did not open; no bound when there is no project), only while its
 * coordinates are the ones the child names (a mismatch means Maven would resolve the parent from a
 * repository, which this does not), for a bounded depth and never twice through the same file. Per-file
 * parses are cached by path and modification time, because detection re-runs on every save, tab switch and
 * focus-regain.
 *
 * <p>Merge rules (Maven's, as far as the tasks tree can tell): parent entries come first; a child
 * {@code <plugin>} with the same {@code groupId:artifactId} overrides its parent's declaration, with the
 * parent's executions kept unless the child redeclares an execution id; a profile with the same id in both
 * is one toggle whose plugin lists are merged the same way; a child property overrides a parent's. A plugin
 * version written as {@code ${property}} is resolved through the merged properties (plus {@code
 * project.version} and {@code project.groupId}), so the goal-descriptor lookup reads the version the build
 * runs instead of guessing the newest one installed.
 */
public final class PomInheritance {

    /** How far up a {@code <parent>} chain is followed. */
    static final int MAX_DEPTH = 8;

    private static final int MAX_PROPERTY_HOPS = 10;

    private static final int MAX_CACHE_ENTRIES = 256;

    private record Cached(long mtime, PomModel model) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private PomInheritance() {}

    /**
     * {@code pomXml} merged with its on-disk parents under {@code boundary} ({@code null} = unbounded). The
     * file itself must parse; an unreadable or unparsable parent simply ends the chain (the module's own pom
     * is still shown — a broken parent is Maven's complaint to make, not a reason to hide the module).
     */
    public static PomModel effective(Path pomXml, Path boundary) throws IOException, PomParseException {
        PomModel child = cachedParse(pomXml);
        List<PomModel> chain = new ArrayList<>();
        chain.add(child);
        Set<Path> seen = new LinkedHashSet<>();
        Path current = pomXml.toAbsolutePath().normalize();
        seen.add(current);
        Path top = boundary == null ? null : boundary.toAbsolutePath().normalize();
        PomModel model = child;
        while (model.parent() != null && chain.size() <= MAX_DEPTH) {
            Path parentPom = parentPomPath(current, model.parent());
            if (parentPom == null
                    || (top != null && !parentPom.startsWith(top))
                    || !seen.add(parentPom)
                    || !Files.isRegularFile(parentPom)) {
                break;
            }
            PomModel parent;
            try {
                parent = cachedParse(parentPom);
            } catch (IOException | PomParseException | RuntimeException e) {
                break;
            }
            if (!matches(parent, model.parent())) {
                break; // not the parent the child names: Maven would go to a repository for it
            }
            chain.add(parent);
            model = parent;
            current = parentPom;
        }
        return chain.size() == 1 ? resolveVersions(child) : merge(chain);
    }

    /** Where {@code <parent>} points from {@code pomXml}: {@code <relativePath>} (a directory means its
     *  {@code pom.xml}), else {@code ../pom.xml}. Null when the relativePath is written empty, which is how
     *  a pom says "repository only". */
    static Path parentPomPath(Path pomXml, PomModel.Parent parent) {
        Path dir = pomXml.getParent();
        if (dir == null) {
            return null;
        }
        String rel = parent.relativePath();
        if (rel == null || rel.isBlank()) {
            return dir.resolve("..").resolve("pom.xml").normalize();
        }
        Path target = dir.resolve(rel).normalize();
        if (Files.isDirectory(target)) {
            target = target.resolve("pom.xml");
        }
        return target;
    }

    private static boolean matches(PomModel parent, PomModel.Parent ref) {
        return (ref.artifactId().isEmpty() || ref.artifactId().equals(parent.artifactId()))
                && (ref.groupId().isEmpty() || ref.groupId().equals(parent.groupId()));
    }

    private static PomModel cachedParse(Path pomXml) throws IOException, PomParseException {
        Path key = pomXml.toAbsolutePath().normalize();
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(key).toMillis();
        } catch (IOException e) {
            mtime = -1;
        }
        Cached cached = CACHE.get(key);
        if (cached != null && cached.mtime() == mtime && mtime >= 0) {
            return cached.model();
        }
        PomModel parsed = PomParser.parseFile(key);
        if (mtime >= 0) {
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(key, new Cached(mtime, parsed));
        }
        return parsed;
    }

    /** Merges a chain ordered child first; pure, so the rules above are unit-tested without files. */
    static PomModel merge(List<PomModel> childFirst) {
        PomModel child = childFirst.get(0);
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, PomModel.Plugin> plugins = new LinkedHashMap<>();
        Map<String, PomModel.Plugin> managed = new LinkedHashMap<>();
        Map<String, PomModel.Profile> profiles = new LinkedHashMap<>();
        List<String> inherited = new ArrayList<>();
        for (int i = childFirst.size() - 1; i >= 0; i--) {
            PomModel model = childFirst.get(i);
            if (i > 0) {
                inherited.add(0, model.artifactId());
            }
            properties.putAll(model.properties());
            mergePlugins(plugins, model.plugins());
            mergePlugins(managed, model.managedPlugins());
            for (PomModel.Profile p : model.profiles()) {
                PomModel.Profile existing = profiles.get(p.id());
                if (existing == null) {
                    profiles.put(p.id(), p);
                    continue;
                }
                Map<String, PomModel.Plugin> merged = new LinkedHashMap<>();
                mergePlugins(merged, existing.plugins());
                mergePlugins(merged, p.plugins());
                profiles.put(
                        p.id(),
                        new PomModel.Profile(
                                p.id(),
                                p.activeByDefault() || existing.activeByDefault(),
                                List.copyOf(merged.values()),
                                p.activations().isEmpty() ? existing.activations() : p.activations()));
            }
        }
        PomModel merged = new PomModel(
                child.groupId(),
                child.artifactId(),
                child.version(),
                child.packaging(),
                List.copyOf(plugins.values()),
                List.copyOf(profiles.values()),
                child.parent(),
                java.util.Collections.unmodifiableMap(properties),
                List.copyOf(managed.values()),
                List.copyOf(inherited));
        return resolveVersions(merged);
    }

    /** Child-over-parent by {@code groupId:artifactId}; executions merged by id (the child's wins). */
    private static void mergePlugins(Map<String, PomModel.Plugin> into, List<PomModel.Plugin> add) {
        for (PomModel.Plugin plugin : add) {
            PomModel.Plugin existing = into.get(plugin.key());
            if (existing == null) {
                into.put(plugin.key(), plugin);
                continue;
            }
            Map<String, PomModel.Execution> executions = new LinkedHashMap<>();
            for (PomModel.Execution e : existing.executions()) {
                executions.put(e.id(), e);
            }
            for (PomModel.Execution e : plugin.executions()) {
                executions.put(e.id(), e);
            }
            String version =
                    plugin.version() == null || plugin.version().isBlank() ? existing.version() : plugin.version();
            into.put(
                    plugin.key(),
                    new PomModel.Plugin(
                            plugin.groupId(), plugin.artifactId(), version, List.copyOf(executions.values())));
        }
    }

    /** Every plugin's {@code ${property}} version replaced by what the model's properties say it is. */
    static PomModel resolveVersions(PomModel model) {
        if (model.properties().isEmpty() && model.version().isEmpty()) {
            return model;
        }
        List<PomModel.Profile> profiles = new ArrayList<>();
        for (PomModel.Profile p : model.profiles()) {
            profiles.add(new PomModel.Profile(
                    p.id(), p.activeByDefault(), resolveVersions(model, p.plugins()), p.activations()));
        }
        return new PomModel(
                model.groupId(),
                model.artifactId(),
                model.version(),
                model.packaging(),
                resolveVersions(model, model.plugins()),
                List.copyOf(profiles),
                model.parent(),
                model.properties(),
                resolveVersions(model, model.managedPlugins()),
                model.inheritedFrom());
    }

    private static List<PomModel.Plugin> resolveVersions(PomModel model, List<PomModel.Plugin> plugins) {
        List<PomModel.Plugin> out = new ArrayList<>(plugins.size());
        for (PomModel.Plugin p : plugins) {
            String resolved = resolve(model, p.version());
            out.add(
                    resolved.equals(p.version())
                            ? p
                            : new PomModel.Plugin(p.groupId(), p.artifactId(), resolved, p.executions()));
        }
        return List.copyOf(out);
    }

    /**
     * {@code value} with every {@code ${name}} expanded through the model's properties and the {@code
     * project.version}/{@code project.groupId} built-ins; unchanged when a reference cannot be answered (the
     * caller then treats it as unknown rather than as a literal with braces in it).
     */
    public static String resolve(PomModel model, String value) {
        String current = value == null ? "" : value;
        for (int hop = 0; hop < MAX_PROPERTY_HOPS && current.contains("${"); hop++) {
            int start = current.indexOf("${");
            int end = current.indexOf('}', start);
            if (end < 0) {
                return value;
            }
            String name = current.substring(start + 2, end).strip();
            String replacement = switch (name) {
                case "project.version", "pom.version", "version" -> model.version();
                case "project.groupId", "pom.groupId", "groupId" -> model.groupId();
                case "project.artifactId", "pom.artifactId", "artifactId" -> model.artifactId();
                default -> model.properties().get(name);
            };
            if (replacement == null || replacement.isEmpty()) {
                return value;
            }
            current = current.substring(0, start) + replacement + current.substring(end + 1);
        }
        return current.contains("${") ? value : current;
    }
}
