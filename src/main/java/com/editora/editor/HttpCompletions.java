package com.editora.editor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.http.HttpVariableIndex;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What a {@code .http} buffer offers after {@code {{}: the variables that resolve at the caret (the selected
 * environment's and the {@code @var} lines above), the dynamic built-ins, the {@code $dotenv} keys of the
 * {@code .env} beside the file, and the requests of the file for {@code {{name.response.…}}} — then, after
 * {@code name.response.}, its {@code body}, {@code headers.} and {@code status}. The names come from
 * {@link HttpVariableIndex}, the same index that marks unresolved references. Runs on the typing pause.
 */
final class HttpCompletions {

    /** Most rows one offer holds. */
    static final int MAX_ITEMS = 80;

    private static final String RESPONSE = ".response.";

    private HttpCompletions() {}

    /**
     * The offer for the caret at {@code column} of {@code line}, or null when the caret is not inside an open
     * {@code {{}. {@code after} is the rest of the caret's line (a {@code }}} already there is not repeated).
     */
    static MarkdownCompletions.@Nullable Offer offer(
            String document, int line, String before, String after, HttpVariableIndex.@Nullable Context ctx) {
        int open = before.lastIndexOf("{{");
        if (open < 0 || before.indexOf("}}", open) >= 0) {
            return null;
        }
        if (open > 0 && before.charAt(open - 1) == '\\') {
            return null; // "\{{" is the escape for a literal {{: no variable is being typed
        }
        int start = open + 2;
        while (start < before.length() && before.charAt(start) == ' ') {
            start++;
        }
        String partial = before.substring(start);
        if (partial.contains("{") || partial.contains("}")) {
            return null;
        }
        boolean closed = after.stripLeading().startsWith("}}");
        HttpVariableIndex.Context c = ctx == null ? HttpVariableIndex.Context.EMPTY : ctx;
        List<Completion> items = new ArrayList<>();
        List<String> requests = HttpVariableIndex.requestNames(document);
        int response = partial.indexOf(RESPONSE);
        if (response > 0) {
            String request = partial.substring(0, response);
            if (requests.contains(request)) {
                String base = request + RESPONSE;
                add(items, partial, base + "body", closed, tr("http.complete.responseBody"), CompletionIconKind.FIELD);
                add(items, partial, base + "body.$.", true, tr("http.complete.responsePath"), CompletionIconKind.FIELD);
                add(
                        items,
                        partial,
                        base + "headers.",
                        true,
                        tr("http.complete.responseHeader"),
                        CompletionIconKind.FIELD);
                add(
                        items,
                        partial,
                        base + "status",
                        closed,
                        tr("http.complete.responseStatus"),
                        CompletionIconKind.FIELD);
            }
            return items.isEmpty() ? null : new MarkdownCompletions.Offer(start, partial, items);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> variables = HttpVariableIndex.variablesAt(document, line, c);
        Set<String> all = new java.util.HashSet<>(variables);
        for (String name : variables) {
            if (nextLevel(name, partial, all) && seen.add(name)) {
                add(items, partial, name, closed, tr("http.complete.variable"), CompletionIconKind.VARIABLE);
            }
        }
        for (String request : requests) {
            add(items, partial, request + RESPONSE, true, tr("http.complete.response"), CompletionIconKind.REFERENCE);
        }
        for (String dyn : HttpVariableIndex.dynamicVariables()) {
            add(items, partial, dyn, closed, tr("http.complete.dynamic"), CompletionIconKind.CONSTANT);
        }
        for (String key : c.dotenvKeys()) {
            add(items, partial, "$dotenv " + key, closed, tr("http.complete.dotenv"), CompletionIconKind.CONSTANT);
        }
        return items.isEmpty() ? null : new MarkdownCompletions.Offer(start, partial, items);
    }

    /**
     * Whether {@code name} is offered for {@code partial}: a nested env member ({@code users[0].address.city}, whose
     * parent {@code users[0].address} is itself a variable) only once its parent and a separator are typed, so one
     * array in an environment cannot fill the popup with hundreds of rows and push out the responses and built-ins.
     */
    static boolean nextLevel(String name, String partial, Set<String> names) {
        String parent = null;
        for (int i = name.length() - 1; i > 0; i--) {
            char ch = name.charAt(i);
            if ((ch == '.' || ch == '[') && names.contains(name.substring(0, i))) {
                parent = name.substring(0, i);
                break;
            }
        }
        if (parent == null) {
            return true;
        }
        String typed = partial.toLowerCase(Locale.ROOT);
        return typed.length() > parent.length() && typed.startsWith(parent.toLowerCase(Locale.ROOT));
    }

    /** Adds {@code name} when it starts with what was typed; a {@code noClose} row (a path to go on, or a {@code }}} already after the caret) gets no {@code }}}. */
    private static void add(
            List<Completion> items,
            String partial,
            String name,
            boolean noClose,
            String detail,
            CompletionIconKind icon) {
        if (items.size() >= MAX_ITEMS || !name.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))) {
            return;
        }
        String insert = noClose ? name : name + "}}";
        items.add(Completion.lsp(name, insert, detail, null, icon, null, false, false, null));
    }
}
