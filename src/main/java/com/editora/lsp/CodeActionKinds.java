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
        /** A suppression ("Disable ShellCheck rule SC2086"): no fix, listed after the fixes (Bash review C10). */
        SUPPRESS,
        ASSIST,
        REFACTOR,
        SOURCE
    }

    /** The group of an action of {@code kind} (an empty or unknown kind is an assist). */
    public static Group group(String kind) {
        if (matches(kind, BashCodeActions.SUPPRESS_KIND)) {
            return Group.SUPPRESS;
        }
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

    /** jdtls's Inline Method (English only, as all its titles): it rewrites every call and deletes the method. */
    public static boolean isInlineMethod(String title) {
        return title.strip().toLowerCase(java.util.Locale.ROOT).startsWith("inline method");
    }

    /** jdtls's Make Static, which it offers with kind {@code refactor.inline}; its titles are English only. */
    public static boolean isMakeStatic(String title) {
        return title.strip().toLowerCase(java.util.Locale.ROOT).startsWith("make static");
    }

    /**
     * Whether a jdtls action of {@code kind} is a generator, which Generate lists: the {@code source.generate.*}
     * kinds and Override/Implement. Organize Imports, Sort Members and "Change modifiers to final" are source
     * actions too, but they generate nothing (re-review BN6, EN3).
     */
    public static boolean isJavaGenerator(String kind) {
        return (matches(kind, "source.generate") && !"source.generate.finalModifiers".equals(kind))
                || "source.overrideMethods".equals(kind);
    }

    /**
     * Whether a jdtls quick assist repeats a generator or a source action that has a command of its own:
     * "Generate Getter for 'x'", "Generate Constructors", "Override/Implement Methods...", "Sort Members…". jdtls
     * offers them on a class name in the quick-fix list as well as under Generate (Java review A4, B11, E4).
     */
    public static boolean isJavaGeneratorAssist(LspManager.CodeActionItem item) {
        if (!"quickassist".equals(item.kind())) {
            return false;
        }
        String command = null;
        if (item.raw() instanceof org.eclipse.lsp4j.CodeAction action && action.getCommand() != null) {
            command = action.getCommand().getCommand();
        } else if (item.raw() instanceof org.eclipse.lsp4j.Command c) {
            command = c.getCommand();
        }
        if (command != null && command.startsWith("java.action.") && command.endsWith("Prompt")) {
            return true;
        }
        String t = item.title();
        return t.startsWith("Generate ")
                || t.startsWith("Override/Implement Methods")
                || t.startsWith("Sort Members")
                || t.startsWith("Change modifiers to final");
    }

    /** Whether {@code kind} is {@code prefix} or one of its sub-kinds ({@code refactor.extract} ⊂ {@code refactor}). */
    public static boolean matches(String kind, String prefix) {
        return kind != null && (kind.equals(prefix) || kind.startsWith(prefix + "."));
    }

    /**
     * Within a group, what acts on the selection or caret before what acts on the whole type: Extract Variable,
     * Constant, Field, Method and Parameter, then Inline, then the rest, then Extract Interface and Move. Refactor
     * This over a selected expression offered Extract Interface first, as its Enter default (Java review B11).
     */
    static int scope(String kind) {
        if (matches(kind, "refactor.extract.interface") || matches(kind, "refactor.move")) {
            return 3;
        }
        if (matches(kind, "refactor.extract")) {
            return 0;
        }
        if (matches(kind, "refactor.inline")) {
            return 1;
        }
        return 2;
    }

    /**
     * Whether Code Actions (the caret's quick-fix list) shows {@code item}. jdtls's generators and other source
     * actions have the Generate command (Java review A4). Another server's source actions are listed too, except
     * on a line with a problem: there lemminx's "Minify XML" sat beside every fix, and on an enumeration error was
     * the only "fix" offered (XML review B14). Generate still lists them.
     */
    public static boolean inQuickFixList(boolean java, LspManager.CodeActionItem item, boolean onProblem) {
        boolean source = group(item.kind()) == Group.SOURCE;
        if (java) {
            return !source && !isJavaGeneratorAssist(item);
        }
        return !source || !onProblem;
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
                .thenComparing(i -> group(i.kind()))
                .thenComparingInt(i -> scope(i.kind())));
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
