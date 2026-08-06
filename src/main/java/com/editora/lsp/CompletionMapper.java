package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.InsertTextFormat;

/**
 * Pure mapping from LSP {@link CompletionItem}s to the editor's {@link Completion} popup entries. The
 * insert text prefers an explicit {@code insertText}/{@code textEdit} newText.
 *
 * <p>A <b>snippet-format</b> item keeps its body: {@link #snippetOf} hands the raw template to the
 * editor, which expands it through the same {@code SnippetSession} local snippets use, so the server's
 * placeholders become real tab stops. Accepting jdtls's {@code java.util.$&#123;0:*&#125;;} therefore
 * selects the {@code *} ready to be typed over, and {@code add($&#123;1:e&#125;)} selects the argument —
 * where flattening the template to literal text (what this used to do) left a stray {@code *} or a
 * literal {@code e} to delete by hand, which is most of what made Java completion feel useless. The
 * {@code insert} field still carries the flattened text, since that is what de-duplication and the
 * label fallback compare on.
 *
 * <p>The IntelliJ-style presentation fields are populated here (the only place that touches lsp4j's
 * {@code CompletionItemKind}/tags): {@code iconKind} for the per-row glyph, the right-hand {@code detail}
 * (type/container, enriched from {@code labelDetails}), {@code sortText}/{@code preselect} for relevance
 * ordering, the {@code deprecated} flag, and the raw item as an opaque resolve token for the doc popup.
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
            Runnable onAccept = onAcceptFor == null ? null : onAcceptFor.apply(item);
            out.add(Completion.lsp(
                            label,
                            insert,
                            detailText(item),
                            onAccept,
                            iconKindOf(item.getKind()),
                            item.getSortText(),
                            Boolean.TRUE.equals(item.getPreselect()),
                            isDeprecated(item),
                            item)
                    .withReplaceRange(replaceRangeOf(item))
                    .withSnippet(snippetOf(item, label)));
        }
        return out;
    }

    /** Maps lsp4j's {@code CompletionItemKind} to the editor's display kind — the sole lsp4j-kind touchpoint. */
    static CompletionIconKind iconKindOf(CompletionItemKind k) {
        if (k == null) {
            return CompletionIconKind.OTHER;
        }
        return switch (k) {
            case Method -> CompletionIconKind.METHOD;
            case Function -> CompletionIconKind.FUNCTION;
            case Constructor -> CompletionIconKind.CONSTRUCTOR;
            case Field -> CompletionIconKind.FIELD;
            case Property -> CompletionIconKind.PROPERTY;
            case Variable -> CompletionIconKind.VARIABLE;
            case Class -> CompletionIconKind.CLASS;
            case Interface -> CompletionIconKind.INTERFACE;
            case Module -> CompletionIconKind.MODULE;
            case Unit -> CompletionIconKind.UNIT;
            case Value -> CompletionIconKind.VALUE;
            case Enum -> CompletionIconKind.ENUM;
            case Keyword -> CompletionIconKind.KEYWORD;
            case Snippet -> CompletionIconKind.SNIPPET;
            case Color -> CompletionIconKind.COLOR;
            case File -> CompletionIconKind.FILE;
            case Reference -> CompletionIconKind.REFERENCE;
            case Folder -> CompletionIconKind.FOLDER;
            case EnumMember -> CompletionIconKind.ENUM_MEMBER;
            case Constant -> CompletionIconKind.CONSTANT;
            case Struct -> CompletionIconKind.STRUCT;
            case Event -> CompletionIconKind.EVENT;
            case Operator -> CompletionIconKind.OPERATOR;
            case TypeParameter -> CompletionIconKind.TYPE_PARAMETER;
            case Text -> CompletionIconKind.TEXT;
        };
    }

    /** The right-hand muted hint: the type/container — {@code labelDetails.description} when present, else
     *  {@code detail}; newlines collapsed so it stays a single line. */
    static String detailText(CompletionItem item) {
        String desc =
                item.getLabelDetails() == null ? null : item.getLabelDetails().getDescription();
        String d = (desc != null && !desc.isBlank()) ? desc : item.getDetail();
        if (d == null) {
            return "";
        }
        return d.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    /** Whether the item is deprecated — via the modern {@code tags} list or the legacy {@code deprecated} flag. */
    @SuppressWarnings("deprecation")
    static boolean isDeprecated(CompletionItem item) {
        if (item.getTags() != null) {
            for (CompletionItemTag t : item.getTags()) {
                if (t == CompletionItemTag.Deprecated) {
                    return true;
                }
            }
        }
        return Boolean.TRUE.equals(item.getDeprecated());
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

    /**
     * The snippet to expand when this item is accepted, or null to insert {@link #insertText} literally.
     *
     * <p>Non-null only for a snippet-format item whose body actually contains a <b>tab stop</b>
     * ({@code $1} / {@code ${0:*}}), never merely a {@code $}. That distinction is load-bearing: a server
     * may mark an item as a snippet and still send an unescaped literal dollar (phpactor's {@code $user},
     * a shell variable), and running that through the snippet parser would read {@code $user} as a
     * variable and insert nothing at all. A body with no stop has nothing for a session to do anyway.
     */
    static com.editora.snippet.Snippet snippetOf(CompletionItem item, String label) {
        if (item.getInsertTextFormat() != InsertTextFormat.Snippet) {
            return null;
        }
        String raw = textEditNewText(item);
        if (raw == null) {
            raw = item.getInsertText();
        }
        if (raw == null || !hasTabStop(raw)) {
            return null;
        }
        return new com.editora.snippet.Snippet(label, label, raw, detailText(item), "");
    }

    /** True when {@code body} contains an LSP/TextMate tab stop — {@code $0}…{@code $9} or {@code ${0…}}. */
    static boolean hasTabStop(String body) {
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) != '$') {
                continue;
            }
            if (i > 0 && body.charAt(i - 1) == '\\') {
                continue; // an escaped literal dollar
            }
            char next = body.charAt(i + 1);
            if (Character.isDigit(next)) {
                return true;
            }
            if (next == '{' && i + 2 < body.length() && Character.isDigit(body.charAt(i + 2))) {
                return true;
            }
        }
        return false;
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

    /**
     * The {@code textEdit.range} as an editor {@link Completion.ReplaceRange}, or null when the item carries
     * no {@code textEdit} (an {@code insertText}-only item — the editor then walks the identifier / trigger
     * overlap itself).
     *
     * <p>Both ends are carried, because a server uses the end to say it is rewriting something that already
     * follows the caret (jdtls covering an existing {@code ;} in an import). For the
     * {@code InsertReplaceEdit} shape the <b>insert</b> range is used for both, matching VS Code's default
     * mode: its replace range deliberately reaches over the whole token after the caret, which would eat
     * text the user has not asked to lose.
     */
    static Completion.ReplaceRange replaceRangeOf(CompletionItem item) {
        if (item.getTextEdit() == null) {
            return null;
        }
        org.eclipse.lsp4j.Range range;
        if (item.getTextEdit().isLeft()) {
            range = item.getTextEdit().getLeft().getRange();
        } else {
            org.eclipse.lsp4j.InsertReplaceEdit ire = item.getTextEdit().getRight();
            range = ire == null ? null : ire.getInsert();
        }
        if (range == null || range.getStart() == null) {
            return null;
        }
        if (range.getEnd() == null) {
            return Completion.ReplaceRange.startingAt(
                    range.getStart().getLine(), range.getStart().getCharacter());
        }
        return new Completion.ReplaceRange(
                range.getStart().getLine(),
                range.getStart().getCharacter(),
                range.getEnd().getLine(),
                range.getEnd().getCharacter());
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
