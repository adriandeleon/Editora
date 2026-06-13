package com.editora.lsp;

import com.editora.completion.Completion;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;

/**
 * Pure mapping from LSP {@link CompletionItem}s to the editor's {@link Completion} popup entries. The
 * insert text prefers an explicit {@code insertText}/{@code textEdit} newText; snippet-format items fall
 * back to the plain label (Editora inserts literal text for LSP items rather than running a snippet
 * session), so server placeholders like {@code $0} are never inserted verbatim.
 */
public final class CompletionMapper {

    private CompletionMapper() {}

    public static List<Completion> map(List<CompletionItem> items) {
        return map(items, null);
    }

    /**
     * Maps items, attaching {@code onAcceptFor.apply(item)} as each completion's accept hook (e.g. to
     * resolve + apply a TypeScript auto-import's {@code additionalTextEdits}). A null factory ⇒ no hook.
     */
    public static List<Completion> map(
            List<CompletionItem> items, java.util.function.Function<CompletionItem, Runnable> onAcceptFor) {
        List<Completion> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (CompletionItem item : items) {
            if (item == null || item.getLabel() == null) {
                continue;
            }
            String label = item.getLabel().strip();
            String insert = insertText(item, label);
            String detail = item.getDetail();
            Runnable onAccept = onAcceptFor == null ? null : onAcceptFor.apply(item);
            out.add(Completion.lsp(label, insert, detail == null ? "" : detail, onAccept));
        }
        return out;
    }

    /** Whether {@code item} may carry deferred edits (auto-import) — has resolve data or additional edits. */
    public static boolean mayHaveAdditionalEdits(CompletionItem item) {
        return item != null
                && (item.getData() != null
                        || (item.getAdditionalTextEdits() != null
                                && !item.getAdditionalTextEdits().isEmpty()));
    }

    /**
     * The literal text to insert. Prefers the {@code textEdit}/{@code insertText} (the display label is
     * decorated — e.g. "names : List&lt;String&gt;" — and must never be inserted). Snippet-format text has
     * its placeholders stripped (Editora inserts plain text for LSP items, not a snippet session). Only
     * when neither is present do we fall back to the label's leading identifier.
     */
    static String insertText(CompletionItem item, String label) {
        String raw = textEditNewText(item);
        if (raw == null) {
            raw = item.getInsertText();
        }
        if (raw == null) {
            return leadingIdentifier(label);
        }
        return item.getInsertTextFormat() == InsertTextFormat.Snippet ? stripSnippet(raw) : raw;
    }

    /** Strips LSP snippet syntax to plain text: {@code ${1:name}}→{@code name}, {@code ${1}}/{@code $1}/
     *  {@code $0}→removed, and {@code \$ \} \\} unescaped. Pure. */
    static String stripSnippet(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s.replaceAll("\\$\\{\\d+:([^}]*)\\}", "$1") // ${1:default} -> default
                .replaceAll("\\$\\{\\d+\\}", "") // ${1} -> (removed)
                .replaceAll("\\$\\d+", ""); // $1 / $0 -> (removed)
        return out.replace("\\$", "$").replace("\\}", "}").replace("\\\\", "\\");
    }

    /** The leading Java-identifier run of {@code label} (e.g. "names : List…" → "names"). */
    private static String leadingIdentifier(String label) {
        if (label == null) {
            return "";
        }
        int i = 0;
        while (i < label.length() && Character.isJavaIdentifierPart(label.charAt(i))) {
            i++;
        }
        return i == 0 ? label : label.substring(0, i);
    }

    private static String textEditNewText(CompletionItem item) {
        if (item.getTextEdit() == null) {
            return null;
        }
        if (item.getTextEdit().isLeft()) {
            return item.getTextEdit().getLeft().getNewText();
        }
        return item.getTextEdit().getRight() == null
                ? null
                : item.getTextEdit().getRight().getNewText();
    }
}
