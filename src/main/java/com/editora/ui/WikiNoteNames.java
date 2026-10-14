package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /** The one resolver every wiki link goes through — the editor's follow and completion use it too. */
    private com.editora.markdown.WikiTargets targets = com.editora.markdown.WikiTargets.EMPTY;

    private WikiNoteNames(Path root) {
        this.root = root;
    }

    static WikiNoteNames of(Path root, boolean respectGitignore) {
        WikiNoteNames names = new WikiNoteNames(root);
        List<Path> files = new ArrayList<>();
        GitignoreFilter filter = respectGitignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        ProjectWalk.walk(root, new ProjectWalk.Options(25, MAX_FILES, filter, () -> false), (file, rel, attrs) -> {
            if (!attrs.isRegularFile()) {
                return ProjectWalk.Verdict.SKIP;
            }
            files.add(file);
            return ProjectWalk.Verdict.ACCEPT;
        });
        names.targets = new com.editora.markdown.WikiTargets(root, files);
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
        if (target.isBlank()) {
            return null;
        }
        // The resolver asks which document the link is in; only its folder matters.
        com.editora.markdown.WikiTargets.Resolved r = targets.resolve(folder.resolve("-"), target);
        return r == null ? null : r.file();
    }
}
