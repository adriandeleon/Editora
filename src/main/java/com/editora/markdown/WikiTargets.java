package com.editora.markdown;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What a wiki link's target names: an immutable picture of a project's files, asked for the file behind
 * {@code [[note]]}. Pure — it is given the files, and never looks at the disk.
 *
 * <p>The rules, in order:
 *
 * <ol>
 *   <li>An empty target ({@code [[#heading]]}) is the document itself.
 *   <li>A target with a folder ({@code [[guides/setup]]}) is a path: relative to the document's folder,
 *       else to the project root.
 *   <li>A bare name is a file of that name in the document's own folder, else the only file of that name in
 *       the project. Two candidates at the same step are an ambiguous link, which resolves to nothing.
 * </ol>
 *
 * Names compare without case, and a Markdown file's extension is optional ({@code [[note]]} is
 * {@code note.md}); any other file is named in full ({@code ![[diagram.png]]}).
 */
public final class WikiTargets {

    /** A wiki target's file, and for a target written with a folder the folder it was relative to. */
    public record Resolved(Path file, @Nullable Path base) {}

    private static final List<String> MARKDOWN_EXTENSIONS = List.of(".md", ".markdown", ".mdown", ".mkd", ".mkdn");

    /** An index with no files: every bare name is unresolved. */
    public static final WikiTargets EMPTY = new WikiTargets(null, List.of());

    private final @Nullable Path root;
    private final Map<String, List<Path>> byName = new HashMap<>();
    private final Map<String, Path> byPath = new HashMap<>();
    private final int size;
    private final List<Path> all;

    public WikiTargets(@Nullable Path root, Collection<Path> files) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        int n = 0;
        List<Path> listed = new ArrayList<>();
        for (Path f : files) {
            Path file = f.toAbsolutePath().normalize();
            Path name = file.getFileName();
            if (name == null) {
                continue;
            }
            n++;
            listed.add(file);
            String lower = name.toString().toLowerCase(Locale.ROOT);
            byName.computeIfAbsent(lower, k -> new ArrayList<>(1)).add(file);
            String bare = stripMarkdownExtension(lower);
            if (!bare.equals(lower)) {
                byName.computeIfAbsent(bare, k -> new ArrayList<>(1)).add(file);
            }
            byPath.put(key(file), file);
            String barePath = stripMarkdownExtension(key(file));
            byPath.putIfAbsent(barePath, file);
        }
        this.size = n;
        this.all = List.copyOf(listed);
    }

    /** Every file, in no particular order (for completion). */
    public java.util.Collection<Path> files() {
        return all;
    }

    /** The project root the files were listed under, or null. */
    public @Nullable Path root() {
        return root;
    }

    public int size() {
        return size;
    }

    private static String key(Path file) {
        return file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** The file {@code target} names when written in {@code doc}, or null (unknown or ambiguous). */
    public @Nullable Resolved resolve(Path doc, String target) {
        if (doc == null || target == null) {
            return null;
        }
        Path document = doc.toAbsolutePath().normalize();
        String t = target.strip().replace('\\', '/');
        if (t.isEmpty()) {
            return new Resolved(document, null);
        }
        Path folder = document.getParent();
        if (t.indexOf('/') >= 0) {
            for (Path base : new Path[] {folder, root}) {
                if (base == null) {
                    continue;
                }
                try {
                    Path found = byPath.get(key(
                            base.resolve(t.startsWith("/") ? t.substring(1) : t).normalize()));
                    if (found != null) {
                        return new Resolved(found, base);
                    }
                } catch (RuntimeException e) {
                    // not a path this platform can name: try the next base
                }
            }
            return null;
        }
        List<Path> named = byName.get(t.toLowerCase(Locale.ROOT));
        if (named == null) {
            return null;
        }
        Path here = null;
        int inFolder = 0;
        for (Path candidate : named) {
            if (folder != null && folder.equals(candidate.getParent())) {
                here = candidate;
                inFolder++;
            }
        }
        if (inFolder == 1 && here != null) {
            return new Resolved(here, null);
        }
        return inFolder == 0 && named.size() == 1 ? new Resolved(named.get(0), null) : null;
    }

    /** Whether {@code target} is written with a file extension ({@code note.md}, {@code pic.png}). */
    public static boolean hasExtension(String target) {
        String t = target.strip();
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        int dot = t.lastIndexOf('.');
        return dot > slash + 1 && dot < t.length() - 1 && !t.substring(dot + 1).contains(" ");
    }

    /** {@code name} without a Markdown extension; any other name is returned as it is. */
    public static String stripMarkdownExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : MARKDOWN_EXTENSIONS) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    /** How a wiki link names {@code file}: its file name, with the Markdown extension only when asked for. */
    public static String name(Path file, boolean withExtension) {
        String name = String.valueOf(file.getFileName());
        return withExtension ? name : stripMarkdownExtension(name);
    }
}
