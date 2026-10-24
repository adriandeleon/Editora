package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How the code-action list is arranged and narrowed, by the actions' LSP kinds. Pure.
 *
 * <p>jdtls answers a caret with everything it could do there: the fixes for the problem under it, assists, a
 * dozen refactorings and every file-level generator, in no useful order, with {@code isPreferred} never set
 * and some titles twice ("Organize imports" as a {@code quickassist} and as {@code source.organizeImports}).
 * So the list put "Extract interface..." first on a plain line and the fix for the squiggle below ten
 * generators (Java review A4). Here a server's preferred actions come first, then the fixes, assists,
 * refactorings and source actions, each in the server's order, and a title already listed is dropped.
 */
public final class CodeActionKinds {

    private CodeActionKinds() {}

    /** The groups, in the order the list shows them. */
    public enum Group {
        FIX,
        ASSIST,
        REFACTOR,
        SOURCE
    }

    /** The group of an action of {@code kind} (an empty or unknown kind is an assist). */
    public static Group group(String kind) {
        if (matches(kind, "quickfix")) {
            return Group.FIX;
        }
        if (matches(kind, "refactor")) {
            return Group.REFACTOR;
        }
        if (matches(kind, "source")) {
            return Group.SOURCE;
        }
        return Group.ASSIST;
    }

    /** Whether {@code kind} is {@code prefix} or one of its sub-kinds ({@code refactor.extract} ⊂ {@code refactor}). */
    public static boolean matches(String kind, String prefix) {
        return kind != null && (kind.equals(prefix) || kind.startsWith(prefix + "."));
    }

    /** {@code items} that {@code keep} accepts, grouped and de-duplicated as the class comment says. */
    public static List<LspManager.CodeActionItem> arrange(
            List<LspManager.CodeActionItem> items, Predicate<LspManager.CodeActionItem> keep) {
        List<LspManager.CodeActionItem> kept = new ArrayList<>();
        for (LspManager.CodeActionItem item : items) {
            if (item != null && keep.test(item)) {
                kept.add(item);
            }
        }
        List<LspManager.CodeActionItem> sorted = new ArrayList<>(kept);
        // A server's own preferred actions first (jdtls marks none), then by group; stable within each.
        sorted.sort(Comparator.comparing((LspManager.CodeActionItem i) -> !i.preferred())
                .thenComparing(i -> group(i.kind())));
        Set<String> titles = new HashSet<>();
        List<LspManager.CodeActionItem> out = new ArrayList<>();
        for (LspManager.CodeActionItem item : sorted) {
            if (titles.add(item.title().strip())) {
                out.add(item);
            }
        }
        return out;
    }
}
