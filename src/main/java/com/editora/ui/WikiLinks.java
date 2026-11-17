package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.markdown.WikiTargets;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

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
    static @Nullable Path resolve(@Nullable Path document, String target) {
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

    /**
     * The path a Markdown link written in {@code document} should carry for wiki {@code target}: relative to
     * the document's folder, with forward slashes; null when the target does not resolve.
     */
    static @Nullable String relativePath(@Nullable Path document, String target) {
        Path file = resolve(document, target);
        Path dir =
                document == null ? null : document.toAbsolutePath().normalize().getParent();
        if (file == null || dir == null) {
            return null;
        }
        try {
            return dir.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Without an index: the file of that name beside the document, a Markdown extension being optional. */
    private static @Nullable Path inFolder(Path doc, String target) {
        Path folder = doc.getParent();
        if (target.isEmpty()) {
            return doc;
        }
        if (folder == null) {
            return null;
        }
        List<Path> here = filesIn(folder);
        if (here == null) {
            return null;
        }
        WikiTargets.Resolved r = new WikiTargets(folder, here).resolve(doc, target);
        return r == null ? null : r.file();
    }

    /** A folder's files as last listed, and when (System.nanoTime). */
    private record Listed(List<Path> files, long at) {}

    private static final java.util.Map<Path, Listed> LISTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Path> LISTING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long LISTED_FRESH_NANOS = 3_000_000_000L;

    /**
     * {@code folder}'s files. Off the FX thread it lists the folder now; on it (a preview or command asking) it
     * answers the last listing and lists again behind it, and a folder never listed answers null — unresolved
     * until the listing lands (PERF-01, Pauta 6.0.1). Following a link lists off the thread first.
     */
    private static @Nullable List<Path> filesIn(Path folder) {
        boolean fx;
        try {
            fx = javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            fx = false;
        }
        if (!fx) {
            return listNow(folder);
        }
        Listed known = LISTED.get(folder);
        if ((known == null || System.nanoTime() - known.at() > LISTED_FRESH_NANOS) && LISTING.add(folder)) {
            Thread.ofVirtual().name("wiki-folder-list").start(() -> {
                try {
                    listNow(folder);
                } finally {
                    LISTING.remove(folder);
                }
            });
        }
        return known == null ? null : known.files();
    }

    private static @Nullable List<Path> listNow(Path folder) {
        com.editora.perf.BlockingSites.enter("WikiLinks.inFolder");
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
        if (LISTED.size() >= 256) {
            LISTED.clear();
        }
        LISTED.put(folder, new Listed(List.copyOf(here), System.nanoTime()));
        return here;
    }

    private static final int MAX_FOLDER_ENTRIES = 2_000;
}
