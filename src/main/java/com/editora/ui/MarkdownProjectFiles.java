package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.editor.LanguageRegistry;
import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectWalk;

/**
 * The Markdown files of a project, for the features that look across them (link updates on rename, Go to
 * Heading in Project).
 *
 * <p>Blocking: call it off the FX thread. The walk is the one the symbol index and Find in Files use, so
 * the same directories are pruned and the same ignore rules apply; it is bounded, and it ends early once
 * {@code cancelled}.
 */
final class MarkdownProjectFiles {

    /** More Markdown files than this and the listing is partial (and says so). */
    static final int MAX_FILES = 5_000;

    /** A bigger file is not read: no hand-written document is this large. */
    static final long MAX_BYTES = 2_000_000;

    private MarkdownProjectFiles() {}

    record Listing(List<Path> files, boolean truncated) {}

    static Listing list(Path root, boolean respectGitignore, BooleanSupplier cancelled) {
        return list(root, respectGitignore, cancelled, MAX_FILES);
    }

    static Listing list(Path root, boolean respectGitignore, BooleanSupplier cancelled, int maxFiles) {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return new Listing(files, false);
        }
        GitignoreFilter ignore = respectGitignore ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                root, new ProjectWalk.Options(Integer.MAX_VALUE, maxFiles, ignore, cancelled), (p, rel, attrs) -> {
                    if (!attrs.isRegularFile() || attrs.size() > MAX_BYTES || !isMarkdown(p)) {
                        return ProjectWalk.Verdict.SKIP;
                    }
                    files.add(p);
                    return ProjectWalk.Verdict.ACCEPT;
                });
        return new Listing(List.copyOf(files), outcome.truncated());
    }

    static boolean isMarkdown(Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && "markdown".equals(LanguageRegistry.forFileName(name.toString()));
    }

    /** The file's text as UTF-8, or null when it cannot be read as such (it is then left alone). */
    static String read(Path file) {
        try {
            return Files.size(file) > MAX_BYTES ? null : Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
