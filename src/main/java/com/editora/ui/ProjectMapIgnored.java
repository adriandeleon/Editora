package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.editora.search.DependencyDirs;
import com.editora.search.GitignoreFilter;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * Why a Project Map row is not the project's own code, so the Map can dim it (review 3 A11, E7): ignored by
 * {@code .gitignore}, a dependency folder ({@code node_modules}), vendored ({@code vendor/}, {@code third_party/}),
 * or marked {@code linguist-generated} / {@code linguist-vendored} in the root's {@code .gitattributes}. The search
 * already skips ignored files; the map showed them like source, and a file it showed could not be found.
 *
 * <p>Reading the rules ({@link #load}) and classifying ({@link #classify}) touch the disk (each folder's own {@code
 * .gitignore}): call them off the FX thread, from one thread at a time. The pattern matching is pure.
 */
final class ProjectMapIgnored {

    /** What a dimmed row is; each has the tooltip line that says so. */
    enum Reason {
        IGNORED("project.map.ignored.gitignore"),
        DEPENDENCY("project.map.ignored.dependency"),
        VENDOR("project.map.ignored.vendor"),
        GENERATED("project.map.ignored.generated"),
        VENDORED("project.map.ignored.vendored");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** The message key of the row tooltip's line. */
        String key() {
            return key;
        }
    }

    /** Folder names that hold vendored third-party code. */
    static final Set<String> VENDOR_NAMES = Set.of("vendor", "third_party", "third-party", "thirdparty");

    private final Path root;
    private final boolean local;
    private final GitignoreFilter gitignore;
    private final GitignoreFilter generated;
    private final GitignoreFilter vendored;
    /** Each folder's filter with the {@code .gitignore} files from the root down to it, by root-relative path. */
    private final Map<String, GitignoreFilter> folderFilters = new HashMap<>();
    /** Each folder's own verdict, which its rows inherit, by root-relative path; {@code NONE} for none. */
    private final Map<String, Object> folderReasons = new HashMap<>();

    private static final Object NONE = new Object();

    private ProjectMapIgnored(Path root, boolean local, GitignoreFilter gitignore, String attributes) {
        this.root = root;
        this.local = local;
        this.gitignore = gitignore;
        this.generated = GitignoreFilter.parse(attributePatterns(attributes, "linguist-generated"));
        this.vendored = GitignoreFilter.parse(attributePatterns(attributes, "linguist-vendored"));
    }

    /** The rules of {@code root}: its {@code .gitignore} files (with those above it) and its {@code .gitattributes}. */
    static ProjectMapIgnored load(Path root) {
        boolean local = Vfs.isLocal(root);
        String attributes = "";
        try {
            Path file = root.resolve(".gitattributes");
            if (Files.isRegularFile(file)) {
                attributes = Files.readString(file);
            }
        } catch (IOException | RuntimeException e) {
            // no attributes: nothing is marked generated
        }
        return new ProjectMapIgnored(root, local, GitignoreFilter.load(root, false), attributes);
    }

    /** For tests: rules given as text, no file read below the root. */
    static ProjectMapIgnored of(Path root, String gitignore, String attributes) {
        return new ProjectMapIgnored(root, false, GitignoreFilter.parse(gitignore), attributes);
    }

    Path root() {
        return root;
    }

    /**
     * Why the row at {@code path} is dimmed, or null for the project's own code. A row inside a dimmed folder is
     * dimmed for the folder's reason. Touches the disk (a folder's own {@code .gitignore}) on a local root.
     */
    @Nullable
    Reason classify(Path path, boolean directory) {
        if (!path.startsWith(root) || path.equals(root)) {
            return null;
        }
        String rel = relative(path);
        int slash = rel.lastIndexOf('/');
        String parent = slash < 0 ? "" : rel.substring(0, slash);
        Reason inherited = parent.isEmpty() ? null : folderReason(parent);
        if (inherited != null) {
            return inherited;
        }
        return directory ? folderReason(rel) : ownReason(rel, parent, false);
    }

    private @Nullable Reason folderReason(String rel) {
        Object known = folderReasons.get(rel);
        if (known != null) {
            return known == NONE ? null : (Reason) known;
        }
        int slash = rel.lastIndexOf('/');
        String parent = slash < 0 ? "" : rel.substring(0, slash);
        Reason reason = parent.isEmpty() ? null : folderReason(parent);
        if (reason == null) {
            reason = ownReason(rel, parent, true);
        }
        folderReasons.put(rel, reason == null ? NONE : reason);
        return reason;
    }

    private @Nullable Reason ownReason(String rel, String parent, boolean directory) {
        if (directory) {
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            if (DependencyDirs.isDependencyDir(rel)) {
                return Reason.DEPENDENCY;
            }
            if (VENDOR_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return Reason.VENDOR;
            }
        }
        if (generated.ignored(rel, directory)) {
            return Reason.GENERATED;
        }
        if (vendored.ignored(rel, directory)) {
            return Reason.VENDORED;
        }
        return filterFor(parent).ignored(rel, directory) ? Reason.IGNORED : null;
    }

    /** The filter for paths in folder {@code rel}: the root's, plus every nested {@code .gitignore} down to it. */
    private GitignoreFilter filterFor(String rel) {
        if (rel.isEmpty() || !local) {
            return gitignore; // a remote root reads its root's file only: one round trip per folder is too many
        }
        GitignoreFilter known = folderFilters.get(rel);
        if (known != null) {
            return known;
        }
        int slash = rel.lastIndexOf('/');
        GitignoreFilter parent = filterFor(slash < 0 ? "" : rel.substring(0, slash));
        GitignoreFilter filter = parent.nested(root.resolve(rel), rel);
        folderFilters.put(rel, filter);
        return filter;
    }

    private String relative(Path path) {
        StringBuilder rel = new StringBuilder();
        for (Path part : root.relativize(path)) {
            if (!rel.isEmpty()) {
                rel.append('/');
            }
            rel.append(part);
        }
        return rel.toString();
    }

    /**
     * The patterns of {@code .gitattributes} lines that set {@code attribute} (bare or {@code =true}), as gitignore
     * text: a later line that unsets it ({@code -attr}, {@code !attr}, {@code attr=false}) becomes a negation, so the
     * last line that speaks wins, as it does for git.
     */
    static String attributePatterns(String attributes, String attribute) {
        if (attributes == null || attributes.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String raw : attributes.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> parts = List.of(line.split("\\s+"));
            if (parts.size() < 2 || parts.getFirst().startsWith("[attr]")) {
                continue;
            }
            Boolean set = null;
            for (String part : parts.subList(1, parts.size())) {
                if (part.equals(attribute) || part.equals(attribute + "=true")) {
                    set = true;
                } else if (part.equals("-" + attribute)
                        || part.equals("!" + attribute)
                        || part.equals(attribute + "=false")) {
                    set = false;
                }
            }
            if (set != null) {
                out.append(set ? "" : "!").append(parts.getFirst()).append('\n');
            }
        }
        return out.toString();
    }
}
