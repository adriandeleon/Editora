package com.editora.ui;

import java.util.ArrayList;

import com.editora.command.Command;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What {@link SearchEverywherePopup} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class SearchEverywhereHost implements SearchEverywherePopup.Ops {

    private final MainController mc;

    SearchEverywhereHost(MainController mc) {
        this.mc = mc;
    }

    /**
     * The commands, files and symbols as they are now, to search off the FX thread (PERF-01, Pauta 6.0.1): taken on
     * the FX thread, it asks each command once whether it can run and copies the key chords, searches nothing; the
     * index's corpus is its unchanging lists ({@link IndexCoordinator#corpus}).
     */
    @Override
    public SearchEverywherePopup.Sources sources() {
        var gates = mc.paletteGates();
        var context = mc.paletteContext();
        // Hoisted: inverting the keymap allocates a map of every binding, so doing it per matching
        // command turned a one-character query into hundreds of rebuilds of the same map.
        java.util.Map<String, String> chords = mc.invertBindings();
        java.util.List<Command> commands = java.util.List.copyOf(mc.registry.all());
        java.util.Set<String> enabled = new java.util.HashSet<>();
        for (Command c : commands) {
            // A command whose feature is off is listed, grayed, with an explanation — the same choice
            // the palette makes (#532). Hiding it means the user never learns it exists, which matters
            // most here precisely because this picker can stand in for the palette.
            if (Chrome.paletteEnabled(c.id(), gates, context)) {
                enabled.add(c.id());
            }
        }
        IndexCoordinator.Corpus corpus = mc.indexCoordinator.corpus();
        return new SearchEverywherePopup.Sources() {
            @Override
            public java.util.List<com.editora.search.SearchEverywhere.Item> commands(String query) {
                return commandItems(commands, enabled, chords, query);
            }

            @Override
            public java.util.List<com.editora.search.SearchEverywhere.Item> files(String query) {
                return corpus.fileItems(query);
            }

            @Override
            public java.util.List<com.editora.search.SearchEverywhere.Item> symbols(String query) {
                return corpus.symbolItems(query);
            }
        };
    }

    /** The command rows for {@code query}, from a copy of the commands: runs on any thread. */
    static java.util.List<com.editora.search.SearchEverywhere.Item> commandItems(
            java.util.List<Command> commands,
            java.util.Set<String> enabled,
            java.util.Map<String, String> chords,
            String query) {
        java.util.List<com.editora.search.SearchEverywhere.Item> out = new ArrayList<>();
        boolean all = query == null || query.isBlank();
        for (Command c : CommandPalette.orderedMatches(commands, query)) {
            com.editora.search.FuzzyMatch.Match m = all ? null : com.editora.search.FuzzyMatch.of(c.title(), query);
            if (all || m != null) {
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.COMMAND,
                        c.title(),
                        chords.getOrDefault(c.id(), ""),
                        c.description(),
                        m == null ? 0 : m.score(),
                        enabled.contains(c.id()),
                        c));
            }
        }
        return out;
    }

    @Override
    public java.util.List<com.editora.search.SearchEverywhere.Item> commands(String query) {
        return sources().commands(query);
    }

    @Override
    public java.util.List<com.editora.search.SearchEverywhere.Item> files(String query) {
        return sources().files(query);
    }

    @Override
    public java.util.List<com.editora.search.SearchEverywhere.Item> symbols(String query) {
        return sources().symbols(query);
    }

    @Override
    public void laterSymbols(
            String query, java.util.function.Consumer<java.util.List<com.editora.search.SearchEverywhere.Item>> cb) {
        mc.lspCoordinator.libraryTypeItems(query, cb);
    }

    @Override
    public void ensureIndex(Runnable then) {
        mc.indexCoordinator.ensureSearchable(then); // the symbol index and the project index's files
    }

    @Override
    public @Nullable String disabledReason(com.editora.search.SearchEverywhere.Item item) {
        // Only a command can be listed-but-inert; a file or symbol row is always actionable.
        return item.payload() instanceof Command c ? mc.disabledCommandReason(c.id()) : null;
    }

    @Override
    public void openDocs(com.editora.search.SearchEverywhere.Item item) {
        if (item.payload() instanceof Command c) {
            mc.openExternalUrl(CommandPalette.docsUrl(c.id()));
        } else {
            mc.setStatus(tr("status.searchEverywhere.noDocs"));
        }
    }

    @Override
    public boolean showInMap(com.editora.search.SearchEverywhere.Item item) {
        return mc.projectsEnabled() && mc.projectPanel().showInMap(item.payload()); // Map review 3 E9
    }

    @Override
    public void choose(com.editora.search.SearchEverywhere.Item item) {
        switch (item.payload()) {
            case Command c -> mc.registry.run(c.id());
            case java.nio.file.Path file -> mc.fileWorkflows.openPath(file);
            case com.editora.index.SymbolIndex.Hit hit ->
                mc.openAndGoto(hit.file(), hit.symbol().line(), hit.symbol().column());
            case com.editora.lsp.LspManager.WorkspaceSymbolMatch library -> mc.lspCoordinator.openLibraryType(library);
            default -> {
                // Unreachable: every Item this window builds carries one of the three above.
            }
        }
    }
}
