package com.editora.lsp;

import java.util.List;
import java.util.Map;
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
    public static @Nullable Expected at(String source, int caret) {
        String text = blankLiterals(source, caret); // a "(" in a string or a comment is no bracket (sixth AS3)
        int c = Math.max(0, Math.min(caret, text.length()));
        int i = c;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        i = skipSpaceBack(text, i);
        if (i > 0 && text.charAt(i - 1) == '.' && (i < 2 || text.charAt(i - 2) != '.')) {
            // `String s = user.na|`: a member of the qualifier's, expected as the whole expression is (AN3)
            i = skipSpaceBack(text, qualifierStart(text, i - 1));
            if (endsWithWord(text, i, "new")) {
                i = skipSpaceBack(text, i - 3);
            }
        }
        boolean afterNew = endsWithWord(text, i, "new");
        if (afterNew) {
            i = skipSpaceBack(text, i - 3);
        }
        if (i > 0 && isTernaryBranch(text, i - 1)) {
            // `String q = ready ? a|` / `… : b|`: a branch is expected as the whole conditional is (AN3)
            i = skipSpaceBack(text, conditionalStart(text, i - 1));
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

    /**
     * {@code text} with what is inside its string, char and text-block literals and its comments before {@code end}
     * blanked out, offsets kept: the backward scans read only code. Read forward, as only forward can tell a quote
     * that opens a literal from one inside a comment (sixth review AS3).
     */
    static String blankLiterals(String text, int end) {
        int stop = Math.max(0, Math.min(end, text.length()));
        StringBuilder out = null;
        int i = 0;
        while (i < stop) {
            char ch = text.charAt(i);
            int from = -1;
            int to = -1;
            if (ch == '/' && i + 1 < stop && text.charAt(i + 1) == '/') {
                from = i;
                int nl = text.indexOf('\n', i);
                to = nl < 0 || nl > stop ? stop : nl;
            } else if (ch == '/' && i + 1 < stop && text.charAt(i + 1) == '*') {
                from = i;
                int close = text.indexOf("*/", i + 2);
                to = close < 0 || close + 2 > stop ? stop : close + 2;
            } else if (text.startsWith("\"\"\"", i)) {
                from = i + 3;
                int close = text.indexOf("\"\"\"", i + 3);
                to = close < 0 || close > stop ? stop : close;
                i = Math.min(stop, to + 3);
            } else if (ch == '"' || ch == '\'') {
                from = i + 1;
                int k = i + 1;
                while (k < stop && text.charAt(k) != ch && text.charAt(k) != '\n') {
                    k += text.charAt(k) == '\\' ? 2 : 1;
                }
                to = Math.min(k, stop);
                i = Math.min(stop, to + 1);
            }
            if (from < 0) {
                i++;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text);
            }
            for (int k = from; k < to; k++) {
                if (out.charAt(k) != '\n') {
                    out.setCharAt(k, ' ');
                }
            }
            if (i <= from) {
                i = Math.max(to, from + 1); // a comment: on past it
            }
        }
        return out == null ? text : out.toString();
    }

    /** The type part of a parameter's label from signature help: {@code int n} → {@code int}. */
    public static @Nullable String parameterType(@Nullable String label) {
        List<String> types = parameterTypes(label);
        return types.isEmpty() ? null : types.get(0);
    }

    /**
     * The types an argument for a parameter's label may have: its type, or for varargs ({@code Object... args})
     * one element first and the whole array second — {@code format("%d", count} passes an element (AN2).
     */
    public static List<String> parameterTypes(@Nullable String label) {
        if (label == null) {
            return List.of();
        }
        String s = label.strip().replaceAll("@[\\w.]+(?:\\([^)]*\\))?\\s+", "").replaceFirst("^final\\s+", "");
        int cut = s.lastIndexOf(' ');
        String type = cut < 0 ? s : s.substring(0, cut).strip();
        if (type.isEmpty() || NOT_TYPES.contains(type)) {
            return List.of();
        }
        if (type.endsWith("...")) {
            String element = type.substring(0, type.length() - 3).strip();
            return List.of(element, element + "[]");
        }
        return List.of(type);
    }

    private static final java.util.regex.Pattern TYPE_VARIABLE =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9]?(\\[\\])?");

    /** {@code T}, {@code E}, {@code K2}, or such with {@code []}: a generic method's type variable, not a type. */
    public static boolean isTypeVariable(String type) {
        return TYPE_VARIABLE.matcher(type.strip()).matches();
    }

    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "^\\s*(?:final\\s+)?([A-Za-z_][\\w.]*(?:<.*>)?(?:\\[\\])*)\\s+[A-Za-z_]\\w*\\s*=[^;=]*$");

    /**
     * The declared type of the variable the caret's line assigns ({@code List<String> l = List.of(}), or null —
     * also when the caret's call is an argument of another call ({@code boolean b = names.contains(requireNonNull(}),
     * whose result is not what the declaration receives (fourth review AF1).
     */
    public static @Nullable String declaredTarget(String text, int caret) {
        int from = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        String line = text.substring(from, Math.min(caret, text.length()));
        var m = DECLARATION.matcher(line);
        if (!m.find()) {
            return null;
        }
        int open = 0;
        for (char ch : line.substring(line.indexOf('=') + 1).toCharArray()) {
            open += ch == '(' ? 1 : ch == ')' ? -1 : 0;
        }
        return open > 1 ? null : m.group(1).strip();
    }

    /**
     * {@code types} with each type variable bound: to the declared target's single type argument
     * ({@code List<String> l = List.of(} → {@code String}), to the target itself when it has none
     * ({@code String r = requireNonNull(} → {@code String}), else to {@code Object}. Signature help names
     * {@code List.of}'s parameter {@code E}, which no item's type is, so only {@code null} was kept (third
     * review AT1).
     */
    /**
     * {@code types} with each type variable bound by matching the method's {@code returnType} against the declared
     * {@code target}: {@code T[] copyOf(T[], int)} into a {@code String[]} binds {@code T} to {@code String}, not
     * {@code String[]}, so the array argument is a {@code String[]} (fourth review AF1); {@code List<E>} into a
     * {@code List<String>} binds {@code E} to {@code String}. A variable the match does not bind is {@code Object}.
     * With no return type to match, as {@link #bindTypeVariables(List, String)}.
     */
    public static List<String> bindTypeVariables(
            List<String> types, @Nullable String target, @Nullable String returnType) {
        if (returnType == null || returnType.isBlank()) {
            return bindTypeVariables(types, target);
        }
        Map<String, String> bound = new java.util.HashMap<>();
        if (target != null) {
            unify(returnType.strip(), target.strip(), bound);
        }
        java.util.List<String> out = new java.util.ArrayList<>(types.size());
        for (String t : types) {
            String type = t.strip();
            if (!isTypeVariable(type)) {
                out.add(substitute(t, bound));
                continue;
            }
            boolean array = type.endsWith("[]");
            String name = array ? type.substring(0, type.length() - 2) : type;
            String to = bound.getOrDefault(name, "Object");
            out.add(array ? to + "[]" : to);
        }
        return out;
    }

    /** {@code type} with each bound type variable in it replaced: {@code Collection<? extends String>}, not {@code T}. */
    private static String substitute(String type, Map<String, String> bound) {
        String out = type;
        for (Map.Entry<String, String> e : bound.entrySet()) {
            out = out.replaceAll(
                    "\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    /**
     * Which argument of the call around {@code caret} the caret is in (0-based): the commas before it at the call's
     * own depth. What Smart Type falls back on when jdtls names no active parameter, as when no overload fits the
     * arguments so far (fifth review AV8). Null outside a call's parentheses.
     */
    public static @Nullable Integer argumentIndex(String source, int caret) {
        String text = blankLiterals(source, caret); // the same reading of literals and comments as at() (AS3)
        int depth = 0;
        int commas = 0;
        for (int i = Math.min(caret, text.length()) - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') {
                int q = i - 1;
                while (q >= 0 && (text.charAt(q) != ch || (q > 0 && text.charAt(q - 1) == '\\'))) {
                    q--;
                }
                if (q < 0) {
                    return null;
                }
                i = q;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth++;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                if (depth == 0) {
                    return ch == '(' ? commas : null;
                }
                depth--;
            } else if (ch == ',' && depth == 0) {
                commas++;
            } else if (ch == ';' && depth == 0) {
                return null;
            }
        }
        return null;
    }

    private static final Map<String, String> BOXED = Map.of(
            "boolean",
            "Boolean",
            "byte",
            "Byte",
            "char",
            "Character",
            "short",
            "Short",
            "int",
            "Integer",
            "long",
            "Long",
            "float",
            "Float",
            "double",
            "Double");

    /** Binds the type variables of {@code declared} (a method's return type) by its shape against {@code actual}. */
    private static void unify(String declared, String actual, Map<String, String> bound) {
        if (isTypeVariable(declared) && !declared.endsWith("[]")) {
            if (!actual.startsWith("?")) {
                bound.putIfAbsent(declared, BOXED.getOrDefault(actual, actual));
            }
            return;
        }
        if (declared.endsWith("[]")) {
            if (actual.endsWith("[]")) {
                unify(declared.substring(0, declared.length() - 2), actual.substring(0, actual.length() - 2), bound);
            }
            return;
        }
        List<String> d = typeArguments(declared);
        List<String> a = typeArguments(actual);
        if (!d.isEmpty() && d.size() == a.size()) {
            for (int i = 0; i < d.size(); i++) {
                unify(d.get(i), a.get(i), bound);
            }
        }
    }

    /** The top-level type arguments of {@code type} ({@code Map<String, List<Integer>>} → both), or none. */
    private static List<String> typeArguments(String type) {
        int lt = type.indexOf('<');
        if (lt < 0 || !type.endsWith(">")) {
            return List.of();
        }
        java.util.List<String> args = new java.util.ArrayList<>();
        int depth = 0;
        int start = lt + 1;
        for (int i = start; i < type.length() - 1; i++) {
            char ch = type.charAt(i);
            depth += ch == '<' ? 1 : ch == '>' ? -1 : 0;
            if (ch == ',' && depth == 0) {
                args.add(type.substring(start, i).strip());
                start = i + 1;
            }
        }
        args.add(type.substring(start, type.length() - 1).strip());
        return args;
    }

    public static List<String> bindTypeVariables(List<String> types, @Nullable String target) {
        String bound = "Object";
        if (target != null) {
            int lt = target.indexOf('<');
            if (lt < 0) {
                bound = target;
            } else if (target.endsWith(">")) {
                String args = target.substring(lt + 1, target.length() - 1).strip();
                int depth = 0;
                boolean single = true;
                for (char ch : args.toCharArray()) {
                    depth += ch == '<' ? 1 : ch == '>' ? -1 : 0;
                    single &= !(ch == ',' && depth == 0);
                }
                bound = single && !args.startsWith("?") ? args : "Object";
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>(types.size());
        for (String t : types) {
            out.add(isTypeVariable(t) ? (t.endsWith("[]") ? bound + "[]" : bound) : t);
        }
        return out;
    }

    /**
     * Where the qualifier ending at the {@code .} at {@code dot} begins: identifiers, calls, indexes, literals
     * and the chain they make ({@code users.get(0).name.}), also across lines.
     */
    private static int qualifierStart(String text, int dot) {
        int j = dot;
        while (true) {
            int k = skipSpaceBack(text, j);
            int end = k;
            while (k > 0) {
                char ch = text.charAt(k - 1);
                if (Character.isJavaIdentifierPart(ch)) {
                    k--;
                } else if (ch == ')' || ch == ']') {
                    int open = matchBack(text, k - 1);
                    if (open < 0) {
                        return k;
                    }
                    k = open;
                } else if (ch == '"') {
                    int q = text.lastIndexOf('"', k - 2);
                    if (q < 0) {
                        return k;
                    }
                    k = q;
                } else {
                    break;
                }
            }
            if (k == end) {
                return j; // nothing before the dot to qualify it
            }
            int before = skipSpaceBack(text, k);
            if (before > 0 && text.charAt(before - 1) == '.') {
                j = before - 1;
            } else {
                return k;
            }
        }
    }

    /** The opening bracket that the {@code )} or {@code ]} at {@code close} matches, or -1. */
    private static int matchBack(String text, int close) {
        char closer = text.charAt(close);
        char opener = closer == ')' ? '(' : '[';
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == closer) {
                depth++;
            } else if (ch == opener && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the {@code ?} or {@code :} at {@code at} is a conditional's, not {@code ?.}, {@code ::} or a label. */
    private static boolean isTernaryBranch(String text, int at) {
        char ch = text.charAt(at);
        if (ch == '?') {
            return true;
        }
        if (ch != ':'
                || (at > 0 && text.charAt(at - 1) == ':')
                || (at + 1 < text.length() && text.charAt(at + 1) == ':')) {
            return false;
        }
        int start = conditionalStart(text, at);
        return text.substring(start, at).contains("?"); // `case X:`, `for (String s :`, a label have none
    }

    /**
     * Where the conditional expression holding the {@code ?} or {@code :} at {@code at} begins: just after the
     * {@code =}, {@code (}, {@code ,}, {@code return} or statement start before it, at its own bracket depth.
     */
    private static int conditionalStart(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')' || ch == ']') {
                depth++;
            } else if (ch == '(' || ch == '[') {
                if (depth-- == 0) {
                    return i + 1;
                }
            } else if (depth == 0) {
                boolean comparison =
                        ch == '=' && (i > 0 && "=!<>".indexOf(text.charAt(i - 1)) >= 0 || text.charAt(i + 1) == '=');
                if ((ch == '=' && !comparison) || ch == ',' || ch == ';' || ch == '{' || ch == '}') {
                    return i + 1;
                }
                if (endsWithWord(text, i + 1, "return")) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    private static @Nullable Expected assignment(String text, int eq, boolean afterNew) {
        String left = text.substring(statementStart(text, eq), eq).strip();
        int nl = left.lastIndexOf('\n');
        if (nl >= 0
                && !DECLARED.matcher(left).matches()
                && !ASSIGNED.matcher(left).matches()) {
            left = left.substring(nl + 1).strip(); // a line above still being typed has no `;` to end it
        }
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
