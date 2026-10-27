package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What Go to Super Method reads from the text and the symbol tree around the caret (eighth review BQ1, BQ2, BR2): the
 * members enclosing a line, the arrow of the lambda the caret is in, a constructor's {@code super(...)} call, and
 * whether two method symbols are the same method. Pure: no server, no UI.
 */
public final class JavaMembers {

    private JavaMembers() {}

    /**
     * The symbols whose whole range (annotations and Javadoc included) holds {@code line}, outermost first; the last is
     * the innermost. Fields, constants and enum constants count, so an anonymous class or a lambda they hold is found
     * (ninth review DP2). Empty when the line is outside every symbol.
     */
    public static List<MemberSpan> enclosing(List<MemberSpan> members, int line) {
        List<MemberSpan> chain = new ArrayList<>();
        List<MemberSpan> level = members;
        boolean descended = true;
        while (descended) {
            descended = false;
            for (MemberSpan m : level) {
                if (m.startLine() <= line && line <= m.endLine() && !m.kind().equals("package")) {
                    chain.add(m);
                    level = m.children();
                    descended = true;
                    break;
                }
            }
        }
        return chain;
    }

    /** {@code text} with comments and string, character and text-block contents as spaces; offsets and lines kept. */
    static String code(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(out, i + 3, Math.max(i + 3, end - 3));
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                blank(out, i + 1, Math.min(j, n));
                i = Math.min(j + 1, n);
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int from, int to) {
        for (int k = from; k < to && k < out.length(); k++) {
            if (out.charAt(k) != '\n') {
                out.setCharAt(k, ' ');
            }
        }
    }

    /**
     * The offset of the {@code ->} of the innermost lambda whose body holds {@code caret}, reading {@code text} from
     * {@code from} (the enclosing method's start); -1 when the caret is in no lambda. A switch rule's arrow
     * ({@code case X ->}) is no lambda.
     */
    public static int lambdaArrow(String text, int from, int caret) {
        String code = code(text);
        record Frame(int blockArrow, int[] pending, boolean[] switchLabel) {}
        java.util.ArrayDeque<Frame> frames = new java.util.ArrayDeque<>();
        frames.push(new Frame(-1, new int[] {-1}, new boolean[] {false}));
        int arrowBeforeBrace = -1;
        int end = Math.min(caret, code.length());
        for (int i = Math.max(0, from); i < end; i++) {
            char c = code.charAt(i);
            Frame top = frames.peek();
            if (c == '-' && i + 1 < end && code.charAt(i + 1) == '>') {
                if (!top.switchLabel()[0]) {
                    top.pending()[0] = i;
                    arrowBeforeBrace = i;
                }
                top.switchLabel()[0] = false;
                i++;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                boolean block = c == '{' && arrowBeforeBrace >= 0 && top.pending()[0] == arrowBeforeBrace;
                if (block) {
                    top.pending()[0] = -1; // the arrow now belongs to the block it opens
                }
                frames.push(new Frame(block ? arrowBeforeBrace : -1, new int[] {-1}, new boolean[] {false}));
                arrowBeforeBrace = -1;
            } else if (c == ')' || c == ']' || c == '}') {
                if (frames.size() > 1) {
                    frames.pop();
                }
                arrowBeforeBrace = -1;
            } else if (c == ',' || c == ';') {
                top.pending()[0] = -1;
                if (c == ';') { // a rule's labels run on across commas: case 1, 2 -> (ninth review BP1)
                    top.switchLabel()[0] = false;
                }
                arrowBeforeBrace = -1;
            } else if (Character.isJavaIdentifierStart(c)
                    && (i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1)))) {
                int j = i;
                while (j < end && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                String word = code.substring(i, j);
                if (word.equals("case") || word.equals("default")) {
                    top.switchLabel()[0] = true;
                }
                i = j - 1;
                arrowBeforeBrace = -1;
            } else if (c == ':') {
                top.switchLabel()[0] = false; // an old-style case label ends at its colon
                arrowBeforeBrace = -1;
            } else if (!Character.isWhitespace(c)) {
                arrowBeforeBrace = -1;
            }
        }
        for (Frame f : frames) { // innermost first
            if (f.pending()[0] >= 0) {
                return f.pending()[0];
            }
            if (f.blockArrow() >= 0) {
                return f.blockArrow();
            }
        }
        return -1;
    }

    /**
     * The offset of the {@code ->} of the lambda whose parameters hold {@code caret} ({@code (String item) ->},
     * {@code x ->}), reading from {@code from} (the enclosing member's start); -1 when the caret is in no lambda's
     * parameters (tenth review BO3).
     */
    public static int lambdaParameterArrow(String text, int from, int caret) {
        String code = code(text);
        int start = Math.min(Math.max(0, caret), code.length());
        while (start > from && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--; // the caret anywhere in a parameter's name
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\w$\\s,.<>\\[\\]@?&]*?(\\))?\\s*->")
                .matcher(code)
                .region(start, code.length());
        if (!m.lookingAt()) {
            return -1;
        }
        int arrow = m.end() - 2;
        boolean parenthesized = m.group(1) != null;
        if (!parenthesized && code.substring(start, arrow).strip().matches(".*[\\s,].*")) {
            return -1; // an argument list before a one-parameter lambda: call(a, x -> ...)
        }
        return lambdaArrow(text, from, arrow + 2) == arrow ? arrow : -1;
    }

    /**
     * The offset of the {@code ::} of the method reference the caret is in ({@code String::length}), else -1 (tenth
     * review BO3).
     */
    public static int methodReference(String text, int caret) {
        String code = code(text);
        int from = Math.max(0, caret - 200);
        int to = Math.min(code.length(), caret + 200);
        for (int k = code.indexOf("::", from); k >= 0 && k < to; k = code.indexOf("::", k + 2)) {
            int left = k;
            while (left > 0
                    && (Character.isJavaIdentifierPart(code.charAt(left - 1)) || code.charAt(left - 1) == '.')) {
                left--;
            }
            int right = k + 2;
            while (right < code.length() && Character.isJavaIdentifierPart(code.charAt(right))) {
                right++;
            }
            if (left <= caret && caret <= right) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The offset of the {@code super} of the {@code super(...)} call between {@code from} and {@code to} (a
     * constructor's text), outside comments and strings; -1 when it calls none.
     */
    public static int superCall(String text, int from, int to) {
        String code = code(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\w.])super\\s*\\(")
                .matcher(code)
                .region(Math.max(0, from), Math.min(to, code.length()));
        return m.find() ? m.start() : -1;
    }

    /**
     * The offset of the {@code this} of the {@code this(...)} call between {@code from} and {@code to} (a constructor
     * chained to another), outside comments and strings; -1 when it calls none.
     */
    public static int thisCall(String text, int from, int to) {
        String code = code(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\w.])this\\s*\\(")
                .matcher(code)
                .region(Math.max(0, from), Math.min(to, code.length()));
        return m.find() ? m.start() : -1;
    }

    /**
     * The modifiers written before a declaration's name, from {@code from} (its first line) to {@code name}: the words
     * outside comments, strings and annotations. A static or private method overrides nothing (ninth review DP1).
     */
    public static java.util.Set<String> modifiers(String text, int from, int name) {
        String code = code(text);
        String head = declarationHead(code, from, name);
        head = head.replaceAll("@[\\w.]+\\s*(\\([^()]*\\))?", " ");
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "\\b(public|protected|private|static|abstract|final|default|synchronized|native|strictfp)\\b")
                .matcher(head);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * The code before a declaration's name at {@code name}, from {@code from} (its line's start) or from what ends the
     * statement or block before it on that line, outside parentheses: {@code private static class V { void v()} gives
     * {@code  void } (tenth review DO1).
     */
    private static String declarationHead(String code, int from, int name) {
        int start = Math.max(0, from);
        int end = Math.max(start, Math.min(name, code.length()));
        int depth = 0;
        for (int i = end - 1; i >= start; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
            } else if (depth == 0 && (c == '{' || c == '}' || c == ';')) {
                return code.substring(i + 1, end);
            }
        }
        return code.substring(start, end);
    }

    /**
     * Whether the declaration from {@code from} to its name at {@code name} is annotated {@code @annotation}, by its
     * simple or qualified name ({@code @Override}, {@code @java.lang.Override}).
     */
    public static boolean annotated(String text, int from, int name, String annotation) {
        String code = code(text);
        String head = declarationHead(code, from, name);
        return java.util.regex.Pattern.compile("@(?:\\w+\\s*\\.\\s*)*" + annotation + "\\b")
                .matcher(head)
                .find();
    }

    /**
     * In a library source, whose outline has no anonymous classes: the offsets of the instantiated type's name and of
     * the method's name when the caret, after {@code from} (the enclosing member's start), is in a method of an
     * anonymous class ({@code new X() { ... void m() { | } }}); else null (ninth review BP2).
     */
    public static int @Nullable [] anonymousMethod(String text, int from, int caret) {
        String code = code(text);
        int end = Math.min(caret, code.length());
        java.util.ArrayDeque<int[]> braces = new java.util.ArrayDeque<>(); // {offset of '{', anonymous type name or -1}
        java.util.regex.Pattern anonymous =
                java.util.regex.Pattern.compile("new\\s+([\\w.]+)\\s*(<[^{};]*?>)?\\s*\\([^{};]*\\)\\s*$");
        for (int i = Math.max(0, from); i < end; i++) {
            char c = code.charAt(i);
            if (c == '{') {
                java.util.regex.Matcher m = anonymous.matcher(code.substring(Math.max(from, i - 300), i));
                int type = m.find() ? Math.max(from, i - 300) + m.start(1) : -1;
                braces.push(new int[] {i, type});
            } else if (c == '}' && !braces.isEmpty()) {
                braces.pop();
            }
        }
        // innermost: a method body directly inside an anonymous class body
        List<int[]> open = new ArrayList<>(braces); // innermost first
        for (int k = 0; k + 1 < open.size(); k++) {
            if (open.get(k)[1] >= 0) {
                return null; // in an anonymous class's body, outside its methods
            }
            int[] body = open.get(k + 1);
            if (body[1] < 0) {
                continue;
            }
            int bodyStart = body[0];
            int methodBrace = open.get(k)[0];
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "([\\w$]+)\\s*\\([^(){};]*\\)\\s*(throws\\s+[\\w.,\\s]+)?\\s*$")
                    .matcher(code.substring(bodyStart + 1, methodBrace));
            if (m.find()) {
                return new int[] {body[1], bodyStart + 1 + m.start(1)};
            }
            return null;
        }
        return null;
    }

    /** A method symbol's simple name: {@code label} of {@code label()} or {@code map(T) : R}. */
    public static String methodName(String symbol) {
        int paren = symbol.indexOf('(');
        return (paren < 0 ? symbol : symbol.substring(0, paren)).strip();
    }

    /** A method symbol's parameter types, as written: {@code [String, int]} of {@code go(String, int)}. */
    public static List<String> parameters(String symbol) {
        int open = symbol.indexOf('(');
        int close = symbol.lastIndexOf(')');
        List<String> out = new ArrayList<>();
        if (open < 0 || close <= open + 1) {
            return out;
        }
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = symbol.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(symbol.substring(start, i).strip());
                start = i + 1;
            }
        }
        out.add(symbol.substring(start, close).strip());
        return out;
    }

    /**
     * The methods among {@code members} that {@code method} (a symbol name) may override: the same name and parameter
     * types, as written without type arguments or package; a type variable ({@code T}, {@code E}) of the super method
     * matches any. An overload of another type does not (ninth review DP1).
     */
    public static List<MemberSpan> overridden(List<MemberSpan> members, String method) {
        return overridden(members, method, java.util.Set.of());
    }

    /**
     * As {@link #overridden(List, String)}, where {@code typeVariables} are also type variables of the super method's
     * type, whatever their names ({@code Item}, {@code Req}; tenth review EO1).
     */
    public static List<MemberSpan> overridden(
            List<MemberSpan> members, String method, java.util.Set<String> typeVariables) {
        return overridden(members, method, typeVariables, java.util.Map.of());
    }

    /**
     * As {@link #overridden(List, String, java.util.Set)}, where a type variable in {@code bindings} stands for the
     * type the subtype gives it ({@code Store<String>}: {@code T} is String), and matches that type alone (tenth review
     * DO2).
     */
    public static List<MemberSpan> overridden(
            List<MemberSpan> members,
            String method,
            java.util.Set<String> typeVariables,
            java.util.Map<String, String> bindings) {
        String name = methodName(method);
        List<String> params = parameters(method);
        List<MemberSpan> out = new ArrayList<>();
        List<MemberSpan> exact = new ArrayList<>();
        for (MemberSpan m : members) {
            if (m.kind().equals("method") && methodName(m.name()).equals(name)) {
                List<String> theirs = parameters(m.name());
                boolean same = theirs.size() == params.size();
                boolean equal = same;
                for (int i = 0; same && i < params.size(); i++) {
                    same = sameParameter(params.get(i), theirs.get(i), typeVariables, bindings);
                    equal = equal && bare(params.get(i)).equals(bare(theirs.get(i)));
                }
                if (same) {
                    out.add(m);
                }
                if (equal) {
                    exact.add(m);
                }
            }
        }
        return exact.isEmpty() ? out : exact; // put(int) overrides put(int), not put(T)
    }

    /** Whether {@code mine} may override a super method's parameter {@code theirs}. */
    static boolean sameParameter(String mine, String theirs) {
        return sameParameter(mine, theirs, java.util.Set.of());
    }

    /**
     * Whether {@code mine} may override a super method's parameter {@code theirs}, which is a type variable when it
     * is one of {@code typeVariables} or is shaped like one ({@code T}, {@code E2}: a method's own are not in its
     * type's outline name).
     */
    static boolean sameParameter(String mine, String theirs, java.util.Set<String> typeVariables) {
        return sameParameter(mine, theirs, typeVariables, java.util.Map.of());
    }

    static boolean sameParameter(
            String mine, String theirs, java.util.Set<String> typeVariables, java.util.Map<String, String> bindings) {
        String a = bare(mine);
        String b = bare(theirs);
        if (a.equals(b)) {
            return true;
        }
        String array = b.replaceAll("[\\[\\]]|\\.\\.\\.", "");
        String bound = bindings.get(array);
        if (bound != null) {
            return a.equals(bare(bound) + "[]".repeat(dims(b)));
        }
        boolean primitive = dims(a) == dims(b)
                && a.replaceAll("[\\[\\]]", "").matches("boolean|byte|char|short|int|long|float|double");
        boolean typeVariable = !primitive && (typeVariables.contains(array) || array.matches("[A-Z][A-Z0-9]?"));
        return typeVariable && a.replaceAll("[\\[\\]]|\\.\\.\\.", "").length() > 0 && dims(a) >= dims(b);
    }

    /** A parameter type without type arguments, package or annotations; varargs as an array. */
    private static String bare(String type) {
        String t = type.replaceAll("@[\\w.]+", "")
                .replaceAll("<.*>", "")
                .replace("...", "[]")
                .replaceAll("\\s", "");
        int dot = t.lastIndexOf('.', t.indexOf('[') < 0 ? t.length() : t.indexOf('['));
        return dot < 0 ? t : t.substring(dot + 1);
    }

    private static int dims(String type) {
        return (int) type.chars().filter(c -> c == '[').count();
    }

    /**
     * The type {@code name}d whose name is on {@code line} among {@code members} and their children; else the first
     * type of that name: jdtls's hierarchy item for an anonymous class's type gives a line apart from its outline's
     * (ninth review DP3).
     */
    public static @Nullable MemberSpan typeAt(List<MemberSpan> members, int line, String name) {
        MemberSpan at = typeAt(members, line);
        return at != null ? at : typeNamed(members, name.replaceAll("<.*", "").strip());
    }

    private static @Nullable MemberSpan typeNamed(List<MemberSpan> members, String name) {
        for (MemberSpan m : members) {
            if (m.isType() && m.name().replaceAll("<.*", "").strip().equals(name)) {
                return m;
            }
        }
        for (MemberSpan m : members) {
            MemberSpan inner = typeNamed(m.children(), name);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /**
     * The type arguments a declaration's header gives {@code type} ({@code Store} in {@code class S implements
     * Store<String>}, or in {@code new Store<String>() {}}), reading {@code text} from {@code from} to the first
     * {@code {}; empty when it gives none (a raw type, {@code <>}) or does not name it. The last mention wins, so a
     * bound in the header's own type parameters ({@code <T extends Comparable<T>>}) does not (tenth review DO2).
     */
    public static List<String> typeArguments(String text, int from, String type) {
        String code = code(text);
        int start = Math.max(0, Math.min(from, code.length()));
        int brace = code.indexOf('{', start);
        int end = brace < 0 ? code.length() : brace;
        String simple = type.replaceAll("<.*", "").strip();
        simple = simple.substring(simple.lastIndexOf('.') + 1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?<![\\w$])(?:[\\w$]+\\s*\\.\\s*)*" + java.util.regex.Pattern.quote(simple) + "\\s*<")
                .matcher(code)
                .region(start, end);
        List<String> found = List.of();
        while (m.find()) {
            List<String> args = new ArrayList<>();
            int depth = 0;
            int arg = m.end();
            for (int i = m.end(); i < end; i++) {
                char c = code.charAt(i);
                if (c == '<') {
                    depth++;
                } else if ((c == '>' && depth-- == 0) || (c == ',' && depth == 0)) {
                    if (!code.substring(arg, i).isBlank()) {
                        args.add(code.substring(arg, i).strip());
                    }
                    arg = i + 1;
                    if (c == '>') {
                        break;
                    }
                }
            }
            if (!args.isEmpty()) {
                found = args;
            }
        }
        return found;
    }

    /**
     * A method's symbol name read from its declaration in {@code text}, the name at {@code name}: {@code compare(String,
     * String)}, as jdtls names it in an outline (for a method no outline holds, tenth review BO2).
     */
    public static String signatureAt(String text, int name) {
        String code = code(text);
        int end = name;
        while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
            end++;
        }
        int open = code.indexOf('(', end);
        List<String> params = new ArrayList<>();
        if (open >= 0) {
            int depth = 0;
            int arg = open + 1;
            for (int i = open + 1; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '<' || c == '(') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                } else if ((c == ')' && depth-- == 0) || (c == ',' && depth == 0)) {
                    String param = code.substring(arg, i)
                            .replaceAll("@[\\w.]+(\\s*\\([^)]*\\))?", " ")
                            .replaceAll("\\bfinal\\b", " ")
                            .strip()
                            .replaceAll("\\s*[\\w$]+(\\s*\\[\\s*\\])*$", "")
                            .strip();
                    if (!param.isEmpty()) {
                        params.add(param.replaceAll("\\s+", " "));
                    }
                    arg = i + 1;
                    if (c == ')') {
                        break;
                    }
                }
            }
        }
        return code.substring(name, end) + "(" + String.join(", ", params) + ")";
    }

    /** {@code type} with each type variable in {@code bindings} replaced by what it stands for. */
    public static String substitute(String type, java.util.Map<String, String> bindings) {
        if (bindings.isEmpty()) {
            return type;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*").matcher(type);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String bound = bindings.get(m.group());
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(bound != null ? bound : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The type variables {@code type} sees: its own and its enclosing types', from their outline names
     * ({@code Store<Item>}, {@code EnumMap<K extends Enum<K>, V>}; tenth review EO1).
     */
    public static java.util.Set<String> typeParameters(List<MemberSpan> members, MemberSpan type) {
        java.util.Set<String> out = new java.util.HashSet<>();
        typeParameters(members, type, out);
        return out;
    }

    private static boolean typeParameters(List<MemberSpan> members, MemberSpan type, java.util.Set<String> out) {
        for (MemberSpan m : members) {
            if (m == type || typeParameters(m.children(), type, out)) {
                if (m.isType()) {
                    out.addAll(typeParameters(m.name()));
                }
                return true;
            }
        }
        return false;
    }

    /** A type parameter's name, after any annotations: {@code K} in {@code K extends Enum<K>}. */
    private static final java.util.regex.Pattern TYPE_PARAMETER =
            java.util.regex.Pattern.compile("^\\s*(?:@[\\w.]+\\s*)*([A-Za-z_$][\\w$]*)");

    /** The type parameter names in a type's outline name: {@code KeyStore<Key, Val>} gives Key and Val. */
    public static List<String> typeParameters(String typeName) {
        int open = typeName.indexOf('<');
        int close = typeName.lastIndexOf('>');
        List<String> out = new ArrayList<>();
        if (open < 0 || close < open || typeName.startsWith("new ")) {
            return out;
        }
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i <= close; i++) {
            char c = typeName.charAt(i);
            if (c == '<') {
                depth++;
            } else if ((c == '>' && depth-- == 0) || (c == ',' && depth == 0)) {
                java.util.regex.Matcher m = TYPE_PARAMETER.matcher(typeName.substring(start, i));
                if (m.find()) {
                    out.add(m.group(1));
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** The type whose name is on {@code line} among {@code members} and their children, or null. */
    public static @Nullable MemberSpan typeAt(List<MemberSpan> members, int line) {
        for (MemberSpan m : members) {
            if (m.isType() && m.nameLine() == line) {
                return m;
            }
            if (m.startLine() <= line && line <= m.endLine()) {
                @Nullable MemberSpan inner = typeAt(m.children(), line);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }
}
