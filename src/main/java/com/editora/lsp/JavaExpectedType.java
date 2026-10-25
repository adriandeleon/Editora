package com.editora.lsp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The type a Java expression at the caret should have, read from the text around it, for Smart Type completion
 * (IntelliJ's Ctrl+Shift+Space). jdtls ranks expected-type proposals higher but has no request that names the
 * type, so Editora reads it where the code says it:
 *
 * <ul>
 *   <li>{@code String s = |}: the declared type ({@code var} gives none);
 *   <li>{@code name = |}, {@code this.name = |}, {@code items[i] = |}: the type {@code name} is declared with
 *       earlier in the file;
 *   <li>{@code return |}: the return type of the enclosing method (none inside a lambda);
 *   <li>{@code if (|}, {@code while (|}, after {@code &&}, {@code ||} or {@code !}: {@code boolean};
 *   <li>{@code call(|} and {@code call(a, |}: an argument, whose type the caller asks signature help for
 *       ({@link #parameterType}).
 * </ul>
 *
 * A word being typed at the caret, and a {@code new} before it, are looked past. Pure.
 */
public final class JavaExpectedType {

    private JavaExpectedType() {}

    /** Where the expectation comes from. */
    public enum Source {
        DECLARATION,
        ASSIGNMENT,
        RETURN,
        CONDITION,
        ARGUMENT
    }

    /**
     * The expected type, or for {@link Source#ARGUMENT} null until signature help names it; {@code afterNew} when
     * the caret's word follows {@code new} (then classes are what fit).
     */
    public record Expected(Source source, @Nullable String type, boolean afterNew) {}

    private static final String TYPE = "[\\w.$]+(?:<.*>)?(?:\\[\\])*";

    /** {@code [final] [@Ann] Type name[]} — the left side of a declaration. */
    private static final Pattern DECLARED =
            Pattern.compile("(?:final\\s+)?(?:@[\\w.]+(?:\\(.*?\\))?\\s+)*(" + TYPE + ")\\s+[\\w$]+((?:\\[\\])*)");

    /** {@code name}, {@code this.name} or {@code name[…]} — the left side of a plain assignment. */
    private static final Pattern ASSIGNED = Pattern.compile("(?:this\\.)?([\\w$]+)((?:\\[[^\\]]*\\])*)");

    /** A method header: {@code … Type name(params) [throws …]}. */
    private static final Pattern METHOD = Pattern.compile("(?s)(?:.*[\\s>])?(" + TYPE
            + ")\\s+[\\w$]+\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)\\s*(?:throws\\s+[\\w.$,\\s]+)?");

    /** Headers that open a block inside a method: the search for the method goes on past them. */
    private static final Pattern INNER_BLOCK = Pattern.compile(
            "(?s)(?:\\}\\s*)?(?:(?:else\\s+)?if|for|while|switch|synchronized|catch|try)\\s*(?:\\(.*\\))?|else|try|do|finally|"
                    + "(?:case\\b.*|default)\\s*(?:->|:)|static|");

    private static final java.util.Set<String> NOT_TYPES = java.util.Set.of(
            "return", "new", "throw", "else", "case", "yield", "package", "import", "instanceof", "var", "assert");

    /** The expectation at {@code caret}, or null where the code states none. */
    public static @Nullable Expected at(String text, int caret) {
        int c = Math.max(0, Math.min(caret, text.length()));
        int i = c;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        i = skipSpaceBack(text, i);
        boolean afterNew = endsWithWord(text, i, "new");
        if (afterNew) {
            i = skipSpaceBack(text, i - 3);
        }
        if (i == 0) {
            return null;
        }
        char p = text.charAt(i - 1);
        if (p == '=' && i >= 2 && "=!<>+-*/%&|^:".indexOf(text.charAt(i - 2)) < 0) {
            return assignment(text, i - 1, afterNew);
        }
        if (endsWithWord(text, i, "return")) {
            String type = returnType(text, i - "return".length());
            return type == null ? null : new Expected(Source.RETURN, type, afterNew);
        }
        if (p == '!' || (i >= 2 && (text.startsWith("&&", i - 2) || text.startsWith("||", i - 2)))) {
            return new Expected(Source.CONDITION, "boolean", false);
        }
        if (p == '(' || p == ',') {
            int open = p == '(' ? i - 1 : openBracket(text, i - 1);
            if (open < 0 || text.charAt(open) != '(') {
                return null;
            }
            int before = skipSpaceBack(text, open);
            for (String keyword : new String[] {"if", "while"}) {
                if (p == '(' && endsWithWord(text, before, keyword)) {
                    return new Expected(Source.CONDITION, "boolean", false);
                }
            }
            if (before > 0 && Character.isJavaIdentifierPart(text.charAt(before - 1))
                    || before > 0 && text.charAt(before - 1) == '>') {
                for (String keyword : new String[] {"for", "switch", "catch", "synchronized", "try"}) {
                    if (endsWithWord(text, before, keyword)) {
                        return null;
                    }
                }
                return new Expected(Source.ARGUMENT, null, afterNew);
            }
        }
        return null;
    }

    /** The type part of a parameter's label from signature help: {@code int n} → {@code int}. */
    public static @Nullable String parameterType(@Nullable String label) {
        if (label == null) {
            return null;
        }
        String s = label.strip().replaceAll("@[\\w.]+(?:\\([^)]*\\))?\\s+", "").replaceFirst("^final\\s+", "");
        int cut = s.lastIndexOf(' ');
        String type = cut < 0 ? s : s.substring(0, cut).strip();
        if (type.endsWith("...")) {
            type = type.substring(0, type.length() - 3) + "[]";
        }
        return type.isEmpty() || NOT_TYPES.contains(type) ? null : type;
    }

    private static @Nullable Expected assignment(String text, int eq, boolean afterNew) {
        String left = text.substring(statementStart(text, eq), eq).strip();
        Matcher d = DECLARED.matcher(left);
        if (d.matches()) {
            String type = d.group(1);
            if (NOT_TYPES.contains(type)) {
                return null;
            }
            return new Expected(Source.DECLARATION, type + d.group(2), afterNew);
        }
        Matcher a = ASSIGNED.matcher(left);
        if (!a.matches()) {
            return null;
        }
        String type = declaredTypeOf(text, a.group(1), statementStart(text, eq));
        if (type == null) {
            return null;
        }
        int dims = a.group(2).isEmpty() ? 0 : a.group(2).split("\\[", -1).length - 1;
        for (int k = 0; k < dims && type.endsWith("[]"); k++) {
            type = type.substring(0, type.length() - 2);
        }
        return new Expected(Source.ASSIGNMENT, type, afterNew);
    }

    /** The type {@code name} was last declared with before {@code limit}: a field, local or parameter. */
    private static @Nullable String declaredTypeOf(String text, String name, int limit) {
        Matcher m = Pattern.compile("(" + TYPE.replace(".*", "[^;=(){}]*") + ")\\s+" + Pattern.quote(name)
                        + "\\s*((?:\\[\\])*)\\s*[=;,)]")
                .matcher(text.substring(0, limit));
        String found = null;
        while (m.find()) {
            String type = m.group(1);
            if (!NOT_TYPES.contains(type) && !"var".equals(type)) {
                found = type + m.group(2);
            }
        }
        return found;
    }

    /** The return type of the method {@code at} is in, or null (void, a lambda, a field initializer). */
    private static @Nullable String returnType(String text, int at) {
        int from = at;
        while (true) {
            int open = openBrace(text, from);
            if (open < 0) {
                return null;
            }
            String header = text.substring(statementStart(text, open), open).strip();
            if (header.endsWith("->")) {
                return null;
            }
            if (INNER_BLOCK.matcher(header).matches()) {
                from = open;
                continue;
            }
            Matcher m = METHOD.matcher(header);
            if (!m.matches()) {
                return null;
            }
            String type = m.group(1);
            return "void".equals(type) || NOT_TYPES.contains(type) ? null : type;
        }
    }

    /** The unmatched {@code {} before {@code at}, or -1. */
    private static int openBrace(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '}') {
                depth++;
            } else if (ch == '{' && depth-- == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The unmatched {@code (}, {@code [} or {@code {} before {@code at}, or -1. */
    private static int openBracket(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')' || ch == ']' || ch == '}') {
                depth++;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                if (depth-- == 0) {
                    return i;
                }
            } else if (ch == ';' && depth == 0) {
                return -1;
            }
        }
        return -1;
    }

    /** Where the statement holding {@code at} starts: after the last {@code ;}, brace or open paren. */
    private static int statementStart(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')' || ch == ']') {
                depth++;
            } else if (ch == '(' || ch == '[') {
                if (depth-- == 0) {
                    return i + 1;
                }
            } else if ((ch == ';' || ch == '{' || ch == '}') && depth <= 0) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int skipSpaceBack(String text, int i) {
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static boolean endsWithWord(String text, int end, String word) {
        int start = end - word.length();
        return start >= 0
                && text.startsWith(word, start)
                && (start == 0 || !Character.isJavaIdentifierPart(text.charAt(start - 1)));
    }
}
