package com.editora.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * What a pom.xml gets from the files <em>beside</em> it, read for the summary preview: the parent chain on
 * disk ({@code <relativePath>}, default {@code ../pom.xml}, else the parent's pom in the local repository)
 * and the BOMs its {@code <dependencyManagement>} imports, looked up in the local repository (Maven review
 * C5/C6). Each file is read once per modification time (a small cache), the chain is bounded to
 * {@link #MAX_DEPTH} hops and cycle-safe, and only <b>local</b> files are read — a remote (SFTP) buffer keeps
 * the one-file summary. This is still not an effective pom: profiles, BOMs imported by a parent and
 * {@code <relativePath>} rewrites through settings are not followed.
 */
public final class PomNeighbours {

    /** Parent hops followed before giving up (Maven itself has no limit; a chain past this is unusual). */
    static final int MAX_DEPTH = 5;

    /** Cached parsed files, by path; dropped wholesale past this many so the map cannot grow with every pom opened. */
    private static final int MAX_CACHE = 64;

    /**
     * One file's contribution: how a tag names it ({@code "reactor-parent 2.0.0"}, {@code "junit-bom 5.11.0
     * (import)"}), its properties and the versions it manages, every {@code ${…}} already resolved within it.
     */
    public record Source(
            String label,
            Map<String, String> properties,
            Map<String, String> managedDependencyVersions,
            Map<String, String> managedPluginVersions) {
        public Source {
            properties = Map.copyOf(properties);
            managedDependencyVersions = Map.copyOf(managedDependencyVersions);
            managedPluginVersions = Map.copyOf(managedPluginVersions);
        }
    }

    /** The parent chain, nearest first (empty when no parent file was found). */
    public record Resolved(List<Source> parents) {
        public static final Resolved NONE = new Resolved(List.of());

        public Resolved {
            parents = List.copyOf(parents);
        }
    }

    private record Cached(FileTime modified, PomSummary summary) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private PomNeighbours() {}

    /** Test seam: forget every cached file. */
    static void clearCache() {
        CACHE.clear();
    }

    /**
     * The parent chain of the pom at {@code pomPath} whose {@code <parent>} is {@code parent}: the file
     * {@code relativePath} names (blank ⇒ {@code ../pom.xml}; a folder ⇒ its {@code pom.xml}) when its
     * coordinates match, else the parent's pom in {@code localRepo}; then that file's parent, and so on.
     */
    public static Resolved parentChain(
            Path pomPath, PomSummary.Coordinates parent, @Nullable String relativePath, @Nullable Path localRepo) {
        if (pomPath == null || parent == null || com.editora.vfs.Vfs.isRemote(pomPath)) {
            return Resolved.NONE;
        }
        List<Source> out = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Path current = pomPath.toAbsolutePath().normalize();
        visited.add(current);
        PomSummary.Coordinates wanted = parent;
        String rel = relativePath;
        for (int depth = 0; depth < MAX_DEPTH && wanted != null; depth++) {
            Path file = locate(current, wanted, rel, localRepo);
            if (file == null || !visited.add(file)) {
                break; // not on disk, or a cycle
            }
            PomSummary s = read(file);
            if (s == null || !s.ok()) {
                break;
            }
            out.add(source(s, labelOf(s.coordinates(), wanted)));
            current = file;
            wanted = s.parent();
            rel = s.parentRelativePath();
        }
        return out.isEmpty() ? Resolved.NONE : new Resolved(out);
    }

    /**
     * The BOM {@code groupId:artifactId:version} imported by a pom, from {@code localRepo} — the versions it
     * manages, resolved through its own properties. Empty when the file is not in the repository.
     */
    public static Optional<Source> bom(Path localRepo, String groupId, String artifactId, String version) {
        Path pom = repositoryPom(localRepo, groupId, artifactId, version);
        if (pom == null || !Files.isRegularFile(pom)) {
            return Optional.empty();
        }
        PomSummary s = read(pom);
        if (s == null || !s.ok()) {
            return Optional.empty();
        }
        return Optional.of(source(s, artifactId + " " + version + " (import)"));
    }

    /** The parent file to read: the relative path when its coordinates agree, else the local repository's copy. */
    private static @Nullable Path locate(
            Path child, PomSummary.Coordinates parent, @Nullable String relativePath, @Nullable Path localRepo) {
        Path dir = child.getParent();
        if (dir != null) {
            String rel = relativePath == null || relativePath.isBlank() ? "../pom.xml" : relativePath.strip();
            Path candidate;
            try {
                candidate = dir.resolve(rel).normalize();
            } catch (RuntimeException e) {
                candidate = null;
            }
            if (candidate != null && Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            if (candidate != null && Files.isRegularFile(candidate)) {
                PomSummary s = read(candidate);
                if (s != null && s.ok() && matches(s.coordinates(), parent)) {
                    return candidate;
                }
            }
        }
        Path fromRepo = repositoryPom(localRepo, parent.groupId(), parent.artifactId(), parent.version());
        return fromRepo != null && Files.isRegularFile(fromRepo) ? fromRepo : null;
    }

    /** Maven's own check before it trusts a relativePath: same groupId:artifactId:version. */
    private static boolean matches(PomSummary.Coordinates found, PomSummary.Coordinates wanted) {
        return found.artifactId().equals(wanted.artifactId())
                && (wanted.groupId().isEmpty()
                        || found.groupId().isEmpty()
                        || found.groupId().equals(wanted.groupId()))
                && (wanted.version().isEmpty()
                        || found.version().isEmpty()
                        || found.version().equals(wanted.version()));
    }

    /** {@code <repo>/<g/r/o/u/p>/<artifact>/<version>/<artifact>-<version>.pom}, or null for unusable parts. */
    static @Nullable Path repositoryPom(@Nullable Path localRepo, String groupId, String artifactId, String version) {
        if (localRepo == null || bad(groupId) || bad(artifactId) || bad(version)) {
            return null;
        }
        Path dir = localRepo;
        for (String segment : groupId.split("\\.")) {
            if (segment.isEmpty()) {
                return null;
            }
            dir = dir.resolve(segment);
        }
        return dir.resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".pom");
    }

    private static boolean bad(String part) {
        return part == null
                || part.isBlank()
                || part.contains("${")
                || part.contains("/")
                || part.contains("\\")
                || part.contains("..");
    }

    private static String labelOf(PomSummary.Coordinates found, PomSummary.Coordinates wanted) {
        String artifact = found.artifactId().isEmpty() ? wanted.artifactId() : found.artifactId();
        String version = found.version().isEmpty() ? wanted.version() : found.version();
        return version.isEmpty() ? artifact : artifact + " " + version;
    }

    private static Source source(PomSummary s, String label) {
        Map<String, String> props = new LinkedHashMap<>();
        for (PomSummary.Property p : s.properties()) {
            props.put(p.name(), p.value());
        }
        Map<String, String> deps = new LinkedHashMap<>();
        for (PomSummary.Dependency d : s.managedDependencies()) {
            if (!d.effectiveVersion().isEmpty()) {
                deps.putIfAbsent(d.groupId() + ":" + d.artifactId(), d.effectiveVersion());
            }
        }
        Map<String, String> plugins = new LinkedHashMap<>();
        for (PomSummary.Plugin p : s.managedPlugins()) {
            if (!p.effectiveVersion().isEmpty()) {
                plugins.putIfAbsent(p.groupId() + ":" + p.artifactId(), p.effectiveVersion());
            }
        }
        return new Source(label, props, deps, plugins);
    }

    /** The one-file summary of {@code file}, cached by its modification time; null when unreadable. */
    private static @Nullable PomSummary read(Path file) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            Cached cached = CACHE.get(file);
            if (cached != null && cached.modified().equals(modified)) {
                return cached.summary();
            }
            if (Files.size(file) > 4L * 1024 * 1024) {
                return null; // a pom this size is not a pom
            }
            PomSummary s = PomSummary.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (CACHE.size() >= MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(file, new Cached(modified, s));
            return s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
