package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * How many times each declaration is used, on the Project Map's declaration rows — the Map's Structure columns and a
 * card's — as the editor's code lens says it (Map code browser gap 11). A file's counts are asked of its running
 * language server once its outline is shown, and again a moment after the outline changes ({@link #QUIET}, so
 * typing does not ask on every keystroke): the server's references lens where it has one (jdtls, TypeScript,
 * rust-analyzer), else its references counted for the first {@link #MAX_COUNTED} declarations. Nothing starts a
 * server; without one a row shows no count. Kept per file by declaration line, for the files shown.
 */
final class MapUsageCounts {

    /** A declaration to count: its file's 0-based line, and its name as written there. */
    record Declaration(int line, String name) {}

    /** How long an outline must stay unchanged before its counts are asked again. */
    static final Duration QUIET = Duration.millis(1200);

    /** How many declarations of one file are counted one by one, without a references lens. */
    static final int MAX_COUNTED = 40;

    private final Supplier<MapCardLinks.@Nullable Source> source;
    private final Predicate<Path> shown;
    private final Runnable onChanged;
    private final Map<Path, Map<Integer, Integer>> counts = new HashMap<>();
    private final Map<Path, PauseTransition> waiting = new HashMap<>();
    /** Bumped for a file each time it is asked again or forgotten: an older answer is dropped. */
    private final Map<Path, Long> asked = new HashMap<>();

    private final Set<Path> answered = new HashSet<>();
    /** Files asked and not answered yet. */
    private final Set<Path> inFlight = new HashSet<>();

    private boolean disposed;

    /**
     * @param source where the counts are asked (the Map's link source; null: none to ask)
     * @param shown whether a file's declarations are on the map now (a Structure column of the Map's, or a card's)
     * @param onChanged told when a file's counts came or went: the rows are drawn again
     */
    MapUsageCounts(Supplier<MapCardLinks.@Nullable Source> source, Predicate<Path> shown, Runnable onChanged) {
        this.source = source;
        this.shown = shown;
        this.onChanged = onChanged;
    }

    /** How many times the declaration on {@code file}'s 0-based {@code line} is used, or null when not known. */
    @Nullable
    Integer count(Path file, int line) {
        Map<Integer, Integer> of = counts.get(file);
        return of == null ? null : of.get(line);
    }

    /** What a row says for {@code count} uses: "no uses", "1 use", "12 uses". */
    static String text(int count) {
        return tr("project.map.usages", count);
    }

    /**
     * {@code file}'s outline is {@code outline} now: its counts are asked {@link #QUIET} later, if its declarations
     * are still shown then; a file no longer shown is forgotten.
     */
    void outlineChanged(Path file, @Nullable List<ProjectMapStructure.Node> outline) {
        if (disposed) {
            return;
        }
        if (outline == null || !shown.test(file)) {
            forget(file);
            return;
        }
        PauseTransition timer = waiting.computeIfAbsent(file, key -> new PauseTransition(QUIET));
        List<Declaration> declarations = declarations(outline);
        timer.setOnFinished(event -> {
            waiting.remove(file);
            ask(file, declarations);
        });
        timer.playFromStart();
    }

    private void ask(Path file, List<Declaration> declarations) {
        MapCardLinks.Source from = source.get();
        if (disposed || from == null || !shown.test(file) || declarations.isEmpty()) {
            return;
        }
        long id = asked.merge(file, 1L, Long::sum);
        inFlight.add(file);
        from.usages(file, declarations, answer -> {
            if (disposed || !java.util.Objects.equals(asked.get(file), id)) {
                return; // asked again since, or forgotten
            }
            inFlight.remove(file);
            if (answer == null) {
                if (counts.remove(file) != null) {
                    onChanged.run();
                }
                return;
            }
            counts.put(file, Map.copyOf(answer));
            answered.add(file);
            onChanged.run();
        });
    }

    /** Forgets {@code file}'s counts, and drops an answer still on its way. */
    void forget(Path file) {
        PauseTransition timer = waiting.remove(file);
        if (timer != null) {
            timer.stop();
        }
        asked.merge(file, 1L, Long::sum);
        answered.remove(file);
        inFlight.remove(file);
        if (counts.remove(file) != null) {
            onChanged.run();
        }
    }

    /**
     * The files whose declarations are on the map now are {@code shownFiles}: each not counted yet, nor waiting, is
     * asked about (its outline from {@code outlineOf}, null while it has none); any other file is forgotten. Cheap:
     * run on the Map's layout pass.
     */
    void shown(
            Set<Path> shownFiles,
            java.util.function.Function<Path, @Nullable List<ProjectMapStructure.Node>> outlineOf) {
        if (disposed) {
            return;
        }
        for (Path file : List.copyOf(counts.keySet())) {
            if (!shownFiles.contains(file)) {
                forget(file);
            }
        }
        for (Path file : shownFiles) {
            if (!answered.contains(file) && !inFlight.contains(file) && !waiting.containsKey(file)) {
                List<ProjectMapStructure.Node> outline = outlineOf.apply(file);
                if (outline != null) {
                    outlineChanged(file, outline);
                }
            }
        }
    }

    /** Whether {@code file}'s counts were answered. For tests. */
    boolean answered(Path file) {
        return answered.contains(file);
    }

    void dispose() {
        disposed = true;
        waiting.values().forEach(PauseTransition::stop);
        waiting.clear();
        counts.clear();
    }

    /** {@code outline}'s declarations, depth first, each by its line and the name it is written with. Pure. */
    static List<Declaration> declarations(List<ProjectMapStructure.Node> outline) {
        List<Declaration> out = new ArrayList<>();
        collect(outline, out);
        return List.copyOf(out);
    }

    private static void collect(List<ProjectMapStructure.Node> nodes, List<Declaration> out) {
        for (ProjectMapStructure.Node node : nodes) {
            String name = ProjectMapView.followName(node.symbol().label());
            if (!name.isBlank()) {
                out.add(new Declaration(node.symbol().line(), name));
            }
            collect(node.children(), out);
        }
    }

    /**
     * The counts a server's code lenses give, by line: each references lens's number ("12 references" → 12). Lenses
     * of another kind, or whose title starts with no number, are left out. Pure.
     */
    static Map<Integer, Integer> fromLenses(List<LspManager.CodeLensSpan> lenses) {
        Map<Integer, Integer> out = new HashMap<>();
        for (LspManager.CodeLensSpan lens : lenses) {
            if (lens.kind() != LspManager.CodeLensKind.REFERENCES) {
                continue;
            }
            Integer count = leadingNumber(lens.title());
            if (count != null) {
                out.putIfAbsent(lens.line(), count);
            }
        }
        return out;
    }

    /** The number a title starts with ("12 references", "1 reference"), or null. Pure. */
    static @Nullable Integer leadingNumber(@Nullable String title) {
        String text = title == null ? "" : title.strip();
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 9) {
            return null;
        }
        return Integer.parseInt(text.substring(0, end));
    }
}
