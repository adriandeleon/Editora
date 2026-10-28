package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * bash-language-server's completion list as Editora shows it (Bash review C11). The server sends no sort text, so
 * the list was ordered by label and {@code case} came after six PATH commands; every row had the same icon and no
 * detail, so a sourced function and {@code nano} looked alike; and after {@code $} it still offered commands, so
 * accepting one gave {@code $nano}. Each item carries the server's own category in {@code data.type}.
 */
public final class BashCompletions {

    /** The Bash server's id. */
    static final String SERVER_ID = "bash";

    /** bash-language-server's {@code CompletionItemDataType}. */
    static final int BUILTIN = 0;

    static final int EXECUTABLE = 1;
    static final int RESERVED_WORD = 2;
    static final int SYMBOL = 3;
    static final int SNIPPET = 4;

    private BashCompletions() {}

    /**
     * {@code items} for a caret after {@code lineBeforeCaret} (the caret's line up to it, or null when not known):
     * after {@code $} or <code>${</code> only variables; otherwise ranked keywords and snippets first, then the
     * script's own functions and variables, builtins, and PATH commands last, each with a detail naming what it is.
     * Items keep a sort text and a detail the server gave. Mutates and returns the kept items.
     */
    public static List<CompletionItem> adjust(List<CompletionItem> items, @Nullable String lineBeforeCaret) {
        boolean variablesOnly = afterDollar(lineBeforeCaret);
        List<CompletionItem> out = new ArrayList<>(items.size());
        for (CompletionItem item : items) {
            if (item == null) {
                continue;
            }
            if (variablesOnly && item.getKind() != CompletionItemKind.Variable) {
                continue;
            }
            int type = dataType(item);
            if (item.getSortText() == null && item.getLabel() != null) {
                item.setSortText(rank(type) + item.getLabel());
            }
            if (item.getDetail() == null || item.getDetail().isBlank()) {
                String detail = detail(type, item.getKind());
                if (detail != null) {
                    item.setDetail(detail);
                }
            }
            out.add(item);
        }
        return out;
    }

    /** The text of {@code line} of {@code text} up to {@code character}, or null when it is not there. Pure. */
    public static @Nullable String lineBefore(@Nullable String text, int line, int character) {
        if (text == null || line < 0 || character < 0) {
            return null;
        }
        int from = 0;
        for (int k = 0; k < line; k++) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) {
                return null;
            }
            from = nl + 1;
        }
        int end = text.indexOf('\n', from);
        String l = text.substring(from, end < 0 ? text.length() : end);
        return l.substring(0, Math.min(character, l.length()));
    }

    /** Whether the word being typed follows {@code $} or <code>${</code>: a parameter expansion. Pure. */
    static boolean afterDollar(@Nullable String lineBeforeCaret) {
        if (lineBeforeCaret == null) {
            return false;
        }
        int end = lineBeforeCaret.length();
        while (end > 0 && isNameChar(lineBeforeCaret.charAt(end - 1))) {
            end--;
        }
        String before = lineBeforeCaret.substring(0, end);
        return before.endsWith("$") || before.endsWith("${");
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The server's category of {@code item} ({@code data.type}), or -1. */
    static int dataType(CompletionItem item) {
        Object data = item.getData();
        JsonElement type = data instanceof JsonObject o ? o.get("type") : null;
        try {
            return type != null && type.isJsonPrimitive() ? type.getAsInt() : -1;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return -1;
        }
    }

    /** A sort-text prefix: keywords and snippets, the script's symbols, builtins, then commands. */
    static String rank(int type) {
        return switch (type) {
            case RESERVED_WORD, SNIPPET -> "0";
            case SYMBOL -> "1";
            case BUILTIN -> "2";
            case EXECUTABLE -> "3";
            default -> "1";
        };
    }

    private static @Nullable String detail(int type, @Nullable CompletionItemKind kind) {
        return switch (type) {
            case BUILTIN -> tr("completion.bash.builtin");
            case EXECUTABLE -> tr("completion.bash.command");
            case RESERVED_WORD -> tr("completion.bash.keyword");
            case SYMBOL ->
                kind == CompletionItemKind.Function
                        ? tr("completion.bash.function")
                        : kind == CompletionItemKind.Variable ? tr("completion.bash.variable") : null;
            default -> null;
        };
    }
}
