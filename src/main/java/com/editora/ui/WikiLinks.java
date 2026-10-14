package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.markdown.WikiTargets;
import com.editora.vfs.Vfs;

/**
 * Resolving a wiki link ({@code [[note]]}) to a file, for everything that follows or draws one: the editor's
 * Open Link, the preview, completion, the link tools.
 *
 * <p>The answer comes from the link index of the project that holds the document ({@link MarkdownLinkGraph}),
 * by {@link WikiTargets}' rules. Until that index has been built — asking starts it — and for a document
 * outside any project, a name is looked for in the document's own folder only. Never blocks on a walk.
 */
final class WikiLinks {

    private WikiLinks() {}

    /** {@code Settings.markdownWikiLinks}, pushed on every settings apply; off, nothing is a wiki link. */
    static volatile boolean enabled = true;

    /** A wiki link as the editor hands it to the link opener: {@code [[target#heading]]}. */
    record Parsed(String target, String heading) {}

    static boolean isWikiForm(String link) {
        return link != null && link.startsWith("[[") && link.endsWith("]]") && link.length() > 4;
    }

    static Parsed parse(String wikiForm) {
        String inner = wikiForm.substring(2, wikiForm.length() - 2);
        int bar = inner.indexOf('|');
        if (bar >= 0) {
            inner = inner.substring(0, bar);
        }
        int hash = inner.indexOf('#');
        return hash < 0
                ? new Parsed(inner.strip(), "")
                : new Parsed(
                        inner.substring(0, hash).strip(),
                        inner.substring(hash + 1).strip());
    }

    /** The names a wiki link written in {@code document} may use; empty outside a project or before the index. */
    static WikiTargets targets(Path document) {
        MarkdownLinkGraph graph = MarkdownLinkGraph.forDocument(document);
        return graph == null ? WikiTargets.EMPTY : graph.targets();
    }

    /**
     * The file {@code target} names when written in {@code document}, or null: unknown, ambiguous, wiki
     * links switched off, or a document that has no file yet.
     */
    static Path resolve(Path document, String target) {
        if (!enabled || document == null || target == null || !Vfs.isLocal(document)) {
            return null;
        }
        Path doc = document.toAbsolutePath().normalize();
        WikiTargets targets = targets(doc);
        if (targets.size() > 0) {
            WikiTargets.Resolved r = targets.resolve(doc, target);
            return r == null ? null : r.file();
        }
        return inFolder(doc, target.strip());
    }

    /** Without an index: the file of that name beside the document, a Markdown extension being optional. */
    private static Path inFolder(Path doc, String target) {
        Path folder = doc.getParent();
        if (target.isEmpty()) {
            return doc;
        }
        if (folder == null) {
            return null;
        }
        // Names compare without case, so the folder is listed rather than probed — a bounded look, and only
        // until the project's index answers instead.
        List<Path> here = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            int seen = 0;
            for (Path entry : entries) {
                if (++seen > MAX_FOLDER_ENTRIES) {
                    break;
                }
                if (Files.isRegularFile(entry)) {
                    here.add(entry);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        WikiTargets.Resolved r = new WikiTargets(folder, here).resolve(doc, target);
        return r == null ? null : r.file();
    }

    private static final int MAX_FOLDER_ENTRIES = 2_000;
}
