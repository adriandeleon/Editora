package com.editora.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.Commenter;
import org.jspecify.annotations.Nullable;

/**
 * Pure: where a JavaScript/TypeScript name used at a place is declared in the same file, by scope — a parameter,
 * a {@code const}/{@code let}/{@code var} (destructured too), a {@code catch} binding or an import — the way a
 * reader resolves it without a language server. The symbol index keeps only top-level variables, so a local
 * {@code result} or a parameter {@code input} found nothing, or jumped to an unrelated top-level {@code const
 * input} (JS/TS review A105). Scopes are bracket blocks of the text with comments, strings and regexes blanked.
 */
public final class JsLocals {

    private static final Set<String> LANGUAGES =
            Set.of("javascript", "javascriptreact", "typescript", "typescriptreact");

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern VARIABLE =
            Pattern.compile("\\b(?:const|let|var|using)\\s+(?:([A-Za-z_$][\\w$]*)|([{\\[]))");
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:type\\s+)?([^;'\"]*?)\\s+from\\b");
    private static final Pattern ARROW_PARAM = Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*=>");
    private static final Set<String> NOT_PARAMS = Set.of("if", "for", "while", "switch", "with", "catch", "return");
    private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "readonly", "override");

    private JsLocals() {}

    /** Whether {@code language} is read here. */
    public static boolean supports(@Nullable String language) {
        return language != null && LANGUAGES.contains(language);
    }

    /**
     * The offset of the declaration of {@code name} that a use at {@code at} refers to, or {@code -1} when none
     * in an enclosing scope declares it (then the file's top-level declarations and the index decide).
     */
    public static int declarationOf(String text, int at, String name, String language) {
        if (!supports(language) || text.isEmpty()) {
            return -1;
        }
        String code = SourceBlanker.blank(text, Commenter.styleFor(language), language);
        int start = Math.min(at, code.length());
        while (start > 0
                && (Character.isLetterOrDigit(code.charAt(start - 1)) || "_$".indexOf(code.charAt(start - 1)) >= 0)) {
            start--;
        }
        if (prevNonBlank(code, start, 0) == '.' && (start < 3 || !code.startsWith("...", start - 3))) {
            return -1; // obj.name: a property, which the index's methods answer
        }
        int[] match = matchBrackets(code);
        List<int[]> found = new ArrayList<>(); // {declaration offset, scope start, scope end}
        variables(code, match, name, found);
        params(code, match, name, found);
        imports(code, name, found);
        int best = -1;
        int bestScope = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] d : found) {
            if (at < d[1] || at > d[2]) {
                continue;
            }
            int distance = d[0] <= at ? at - d[0] : Integer.MAX_VALUE / 2 + d[0]; // above the use first
            if (d[1] > bestScope || (d[1] == bestScope && distance < bestDistance)) {
                best = d[0];
                bestScope = d[1];
                bestDistance = distance;
            }
        }
        return best;
    }

    /** {@code const|let|var NAME} and destructuring patterns; scope = the innermost enclosing block. */
    private static void variables(String code, int[] match, String name, List<int[]> out) {
        Matcher m = VARIABLE.matcher(code);
        while (m.find()) {
            List<Integer> at = new ArrayList<>();
            if (m.group(1) != null) {
                if (m.group(1).equals(name)) {
                    at.add(m.start(1));
                }
            } else {
                int open = m.start(2);
                int close = match[open];
                if (close > open) {
                    bindings(code, open + 1, close, name, at);
                }
            }
            for (int offset : at) {
                int[] scope = enclosing(code, match, m.start());
                // A for/catch header's declaration belongs to the statement it heads.
                if (scope[0] >= 0 && code.charAt(scope[0]) == '(') {
                    scope = new int[] {scope[0], bodyEnd(code, match, match[scope[0]], scope[1])};
                }
                out.add(new int[] {offset, scope[0] + 1, scope[1]});
            }
        }
    }

    /** Function, method and arrow parameters, and {@code catch (e)}; scope = the parameter list and body. */
    private static void params(String code, int[] match, String name, List<int[]> out) {
        for (int open = code.indexOf('('); open >= 0; open = code.indexOf('(', open + 1)) {
            int close = match[open];
            if (close <= open) {
                continue;
            }
            boolean catchClause =
                    CATCH.matcher(code).region(Math.max(0, open - 8), open + 1).find();
            String before = wordBefore(code, open);
            if (!catchClause && NOT_PARAMS.contains(before)) {
                continue;
            }
            int body = bodyStart(code, close);
            if (body < 0 && !catchClause) {
                continue; // a call, not a parameter list
            }
            List<Integer> at = new ArrayList<>();
            int depth = 0;
            int start = open + 1;
            for (int i = open + 1; i <= close; i++) {
                char c = code.charAt(i);
                if (c == '(' || c == '[' || c == '{' || c == '<') {
                    depth++;
                } else if ((c == ')' || c == ']' || c == '}' || (c == '>' && code.charAt(i - 1) != '=')) && i < close) {
                    depth--;
                } else if ((c == ',' && depth == 0) || i == close) {
                    param(code, start, i, name, at);
                    start = i + 1;
                }
            }
            int[] outer = enclosing(code, match, open);
            int end = body >= 0 ? bodyEnd(code, match, close, outer[1]) : outer[1];
            for (int offset : at) {
                out.add(new int[] {offset, open, end});
            }
        }
        Matcher arrow = ARROW_PARAM.matcher(code);
        while (arrow.find()) {
            if (arrow.group(1).equals(name)) {
                int[] outer = enclosing(code, match, arrow.start());
                out.add(new int[] {arrow.start(1), arrow.start(1), bodyEnd(code, match, arrow.end() - 1, outer[1])});
            }
        }
    }

    /** One parameter {@code [from, to)}: its leading name, or the bindings of its destructuring pattern. */
    private static void param(String code, int from, int to, String name, List<Integer> out) {
        int i = from;
        while (true) {
            while (i < to && (Character.isWhitespace(code.charAt(i)) || code.charAt(i) == '.')) {
                i++; // spaces and a rest parameter's dots
            }
            Matcher m = IDENT.matcher(code).region(i, to);
            if (m.lookingAt() && MODIFIERS.contains(m.group())) {
                i = m.end();
                continue;
            }
            break;
        }
        if (i >= to) {
            return;
        }
        char c = code.charAt(i);
        if (c == '{' || c == '[') {
            bindings(code, i + 1, Math.min(to, matchFrom(code, i)), name, out);
            return;
        }
        Matcher m = IDENT.matcher(code).region(i, to);
        if (m.lookingAt() && m.group().equals(name)) {
            out.add(i);
        }
    }

    /** Names bound by a destructuring pattern's contents {@code [from, to)}: not keys ({@code a:}), not defaults. */
    private static void bindings(String code, int from, int to, String name, List<Integer> out) {
        Matcher m = IDENT.matcher(code).region(from, Math.max(from, to));
        while (m.find()) {
            if (!m.group().equals(name)) {
                continue;
            }
            char next = nextNonBlank(code, m.end(), to);
            char prev = prevNonBlank(code, m.start(), from);
            if (next != ':' && prev != '=' && prev != '.') {
                out.add(m.start());
            }
        }
    }

    /** {@code import a, { b as c, d }, * as e from "x"}: the local names; scope = the whole file. */
    private static void imports(String code, String name, List<int[]> out) {
        Matcher m = IMPORT.matcher(code);
        while (m.find()) {
            String clause = m.group(1);
            Matcher id = IDENT.matcher(clause);
            while (id.find()) {
                if (!id.group().equals(name)) {
                    continue;
                }
                String after = clause.substring(id.end()).stripLeading();
                if (!after.startsWith("as ") && !after.startsWith("as\t")) { // `b as c`: c is the local name
                    out.add(new int[] {m.start(1) + id.start(), 0, code.length()});
                }
            }
        }
    }

    // --- brackets ---------------------------------------------------------------------------------

    /** For each opening bracket its closing bracket's offset (and back), -1 when unmatched. */
    static int[] matchBrackets(String code) {
        int[] match = new int[code.length()];
        java.util.Arrays.fill(match, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                stack.push(i);
            } else if ((c == ')' || c == ']' || c == '}') && !stack.isEmpty()) {
                int open = stack.pop();
                match[open] = i;
                match[i] = open;
            }
        }
        return match;
    }

    private static int matchFrom(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /** The innermost bracket pair around {@code at}: {open, close}, or {-1, length} at the top level. */
    private static int[] enclosing(String code, int[] match, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) {
                    return new int[] {i, match[i] < 0 ? code.length() : match[i]};
                }
                depth--;
            }
        }
        return new int[] {-1, code.length()};
    }

    /**
     * Where a function body starts after its parameter list closing at {@code close}: the {@code {} or the
     * {@code =>}, past a TypeScript return type ({@code ): Promise<T> {}); -1 when neither follows (a call).
     */
    private static int bodyStart(String code, int close) {
        int i = close + 1;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        if (i >= code.length()) {
            return -1;
        }
        if (code.charAt(i) == '{' || code.startsWith("=>", i)) {
            return i;
        }
        if (code.charAt(i) != ':') {
            return -1;
        }
        int depth = 0; // a return type: to the first { or => outside its <>, () and []
        for (int k = i + 1; k < code.length() && k < close + 400; k++) {
            char c = code.charAt(k);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '=' && k + 1 < code.length() && code.charAt(k + 1) == '>' && depth == 0) {
                return k;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if ((c == '{' && depth == 0)) {
                return k;
            } else if (c == ';' || depth < 0) {
                return -1;
            }
        }
        return -1;
    }

    /** The end of the body that follows {@code from} (a {@code )} or {@code =>}): its block's close, else {@code fallback}. */
    private static int bodyEnd(String code, int[] match, int from, int fallback) {
        for (int i = from + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                return match[i] < 0 ? code.length() : match[i];
            }
            if (!Character.isWhitespace(c) && c != '=' && c != '>' && c != ':') {
                return fallback; // an expression body (or a return type): to the end of the enclosing block
            }
        }
        return fallback;
    }

    private static String wordBefore(String code, int at) {
        int end = at;
        while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(code.charAt(start - 1)) || code.charAt(start - 1) == '_')) {
            start--;
        }
        return code.substring(start, end);
    }

    private static char nextNonBlank(String code, int from, int limit) {
        for (int i = from; i < limit && i < code.length(); i++) {
            if (!Character.isWhitespace(code.charAt(i))) {
                return code.charAt(i);
            }
        }
        return 0;
    }

    private static char prevNonBlank(String code, int from, int limit) {
        for (int i = from - 1; i >= limit; i--) {
            if (!Character.isWhitespace(code.charAt(i))) {
                return code.charAt(i);
            }
        }
        return 0;
    }
}
