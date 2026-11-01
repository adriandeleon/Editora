package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.editora.index.SymbolIndex;
import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The declarations a Project Map {@code @name} query finds (Project Map review 3 E2): the editor's symbol index,
 * built on the first such query, and the {@code workspace/symbol} answer of a language server that runs for one of
 * the open files. The index answers at once; a server's answer, when one is asked, follows and is merged in,
 * ahead of the index's for the same declaration. {@code new ProjectMapSymbolSearch(index, lsp)::find} is the
 * {@link Source} the Project panel is given.
 */
final class ProjectMapSymbolSearch {

    /**
     * Where the Map's declaration search looks; {@link ProjectPanel#setMapSymbolSearch} takes one.
     */
    @FunctionalInterface
    interface Source {
        /**
         * Looks {@code query} (without its {@code @}) up among the declarations under {@code root}. Calls
         * {@code found} on the JavaFX thread once or more, each time with every match known so far, best first,
         * and {@code true} on the last call. {@code status} gets what the user should know meanwhile (the index
         * is being built, there is nothing to search with). FX thread.
         */
        void find(
                Path root,
                String query,
                Collection<Path> openFiles,
                BiConsumer<List<ProjectMapSearchResult.Match>, Boolean> found,
                Consumer<String> status);
    }

    private final IndexCoordinator index;
    private final @Nullable LspManager lsp;

    ProjectMapSymbolSearch(IndexCoordinator index, @Nullable LspManager lsp) {
        this.index = index;
        this.lsp = lsp;
    }

    /** {@link Source#find}: the index's answer at once, a language server's merged in when it arrives. */
    void find(
            Path root,
            String query,
            Collection<Path> openFiles,
            BiConsumer<List<ProjectMapSearchResult.Match>, Boolean> found,
            Consumer<String> status) {
        Path anchor = serverAnchor(root, openFiles);
        boolean indexed = index.isEnabled();
        if (!indexed && anchor == null) {
            status.accept(tr("project.map.status.symbolsOff"));
            found.accept(List.of(), true);
            return;
        }
        if (indexed && !index.isBuilt()) {
            status.accept(tr("project.map.status.symbolsIndexing"));
        }
        index.ensureBuilt(() -> {
            List<ProjectMapSearchResult.Match> fromIndex = fromIndex(root, query);
            if (anchor == null || lsp == null) {
                found.accept(ranked(query, List.of(), fromIndex), true);
                return;
            }
            found.accept(ranked(query, List.of(), fromIndex), false);
            lsp.workspaceSymbols(
                    anchor, query, matches -> found.accept(ranked(query, fromServer(root, matches), fromIndex), true));
        });
    }

    /** An open file under {@code root} whose language server answers {@code workspace/symbol}, or null. */
    private @Nullable Path serverAnchor(Path root, Collection<Path> openFiles) {
        if (lsp == null || openFiles == null) {
            return null;
        }
        for (Path file : openFiles) {
            if (file != null && com.editora.config.PathKeys.isAtOrUnder(file, root)) {
                try {
                    if (lsp.supportsWorkspaceSymbols(file)) {
                        return file;
                    }
                } catch (RuntimeException notReady) {
                    // a server starting or stopping: the index still answers
                }
            }
        }
        return null;
    }

    private List<ProjectMapSearchResult.Match> fromIndex(Path root, String query) {
        List<ProjectMapSearchResult.Match> out = new ArrayList<>();
        for (SymbolIndex.Hit hit : index.searchSymbols(query, ProjectMapSearch.MAX_MATCHES)) {
            Path file = hit.file();
            if (!com.editora.config.PathKeys.isAtOrUnder(file, root)) {
                continue; // the window's project is not the folder the Map shows
            }
            String container = hit.symbol().container();
            String kind = hit.symbol().kind().name().toLowerCase(java.util.Locale.ROOT);
            out.add(ProjectMapSearchResult.Match.symbol(
                    file,
                    relative(root, file),
                    hit.symbol().name(),
                    hit.symbol().line(),
                    container.isEmpty() ? kind : kind + " · " + container));
        }
        return out;
    }

    private static List<ProjectMapSearchResult.Match> fromServer(
            Path root, List<LspManager.WorkspaceSymbolMatch> matches) {
        List<ProjectMapSearchResult.Match> out = new ArrayList<>();
        for (LspManager.WorkspaceSymbolMatch match : matches) {
            Path file = match.file();
            if (file == null || !com.editora.config.PathKeys.isAtOrUnder(file, root)) {
                continue; // a library type (jdt://) or another project's: not on this map
            }
            String container = match.container() == null ? "" : match.container();
            String kind = match.kind() == null ? "" : match.kind();
            out.add(ProjectMapSearchResult.Match.symbol(
                    file,
                    relative(root, file),
                    match.name(),
                    match.line(),
                    container.isEmpty() ? kind : kind + " · " + container));
        }
        return out;
    }

    /**
     * Both answers in one ranked list, the server's first for the same declaration (a file and a line), at most
     * {@link ProjectMapSearch#MAX_MATCHES}.
     */
    static List<ProjectMapSearchResult.Match> ranked(
            String query, List<ProjectMapSearchResult.Match> server, List<ProjectMapSearchResult.Match> indexed) {
        Map<ProjectMapSearchResult.Match, Integer> rank = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<ProjectMapSearchResult.Match> all = new ArrayList<>();
        int order = 0;
        for (List<ProjectMapSearchResult.Match> source : List.of(server, indexed)) {
            for (ProjectMapSearchResult.Match match : source) {
                if (seen.add(match.path() + "\u0000" + match.line() + "\u0000" + match.symbol())) {
                    all.add(match);
                    rank.put(match, order);
                }
                order++;
            }
        }
        all.sort(ProjectMapSearch.symbolOrder(query, m -> rank.getOrDefault(m, Integer.MAX_VALUE)));
        return all.size() > ProjectMapSearch.MAX_MATCHES
                ? List.copyOf(all.subList(0, ProjectMapSearch.MAX_MATCHES))
                : List.copyOf(all);
    }

    private static String relative(Path root, Path file) {
        return com.editora.search.ProjectWalk.relativize(root, file);
    }
}
