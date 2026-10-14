package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;

/**
 * The files of a project by name, for resolving wiki links in the linter: {@code [[note]]} means the file called
 * {@code note} (the Markdown extension is optional, case does not matter) — in the document's own folder first,
 * else the one file of that name in the project; two candidates are no answer.
 *
 * <p>Built by walking the project the way Find in Files does (ignore rules, bounded), on the lint thread, and
 * kept until the project's files change or the listing is a minute old.
 */
final class WikiNoteNames {

    private static final int MAX_FILES = 20_000;
    private static final long MAX_AGE_MILLIS = 60_000;

    private final Path root;
    private final long built = System.currentTimeMillis();
    /** Lower-case file name, with and without a Markdown extension → the files called that. */
    private final Map<String, List<Path>> byName = new HashMap<>();

    private WikiNoteNames(Path root) {
        this.root = root;
    }

    static WikiNoteNames of(Path root, boolean respectGitignore) {
        WikiNoteNames names = new WikiNoteNames(root);
        GitignoreFilter filter = respectGitignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        ProjectWalk.walk(root, new ProjectWalk.Options(25, MAX_FILES, filter, () -> false), (file, rel, attrs) -> {
            if (!attrs.isRegularFile()) {
                return ProjectWalk.Verdict.SKIP;
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            names.byName.computeIfAbsent(name, k -> new ArrayList<>(1)).add(file);
            String bare = withoutMarkdownExtension(name);
            if (!bare.equals(name)) {
                names.byName.computeIfAbsent(bare, k -> new ArrayList<>(1)).add(file);
            }
            return ProjectWalk.Verdict.ACCEPT;
        });
        return names;
    }

    boolean stale(Path forRoot) {
        return !root.equals(forRoot) || System.currentTimeMillis() - built > MAX_AGE_MILLIS;
    }

    /**
     * The file {@code target} names for a document in {@code folder}, or null when none or several do. A
     * target with a folder in it ({@code guides/setup}) is a path: from the document's folder, else from the
     * project root.
     */
    Path resolve(Path folder, String target) {
        String wanted = target.strip().replace('\\', '/');
        if (wanted.isEmpty()) {
            return null;
        }
        if (wanted.indexOf('/') >= 0) {
            for (Path base : new Path[] {folder, root}) {
                for (String candidate : new String[] {wanted, wanted + ".md", wanted + ".markdown"}) {
                    try {
                        Path file = base.resolve(candidate).normalize();
                        if (Files.isRegularFile(file)) {
                            return file;
                        }
                    } catch (RuntimeException notAPath) {
                        return null;
                    }
                }
            }
            return null;
        }
        List<Path> candidates = byName.getOrDefault(wanted.toLowerCase(Locale.ROOT), List.of());
        Path only = null;
        int inFolder = 0;
        for (Path file : candidates) {
            if (folder.equals(file.getParent())) {
                only = inFolder++ == 0 ? file : only;
            }
        }
        if (inFolder == 1) {
            return only; // the note next to the document wins over its namesakes elsewhere
        }
        return inFolder == 0 && candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String withoutMarkdownExtension(String name) {
        if (name.endsWith(".md")) {
            return name.substring(0, name.length() - 3);
        }
        return name.endsWith(".markdown") ? name.substring(0, name.length() - 9) : name;
    }
}
