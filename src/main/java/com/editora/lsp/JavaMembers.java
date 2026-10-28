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
        return enclosing(members, line, Integer.MAX_VALUE);
    }

    /**
     * As {@link #enclosing(List, int)} at {@code column} of {@code line}: a member holds the caret only from its start
     * column on its first line to its end column on its last, so on a one-line type ({@code class V { void v() {} }})
     * the class name and a later member are not the first member's (eleventh review DM3, twelfth review BL3).
     */
    public static List<MemberSpan> enclosing(List<MemberSpan> members, int line, int column) {
        return enclosing(members, line, column, null);
    }

    /**
     * As {@link #enclosing(List, int, int)}, reading {@code text} for a range that jdtls starts at a comment ending
     * code on the line before the member ({@code implements Runnable { // note}): that line is not the member's, so the
     * comment and the code before it belong to whatever holds them (twelfth review BL3).
     */
    public static List<MemberSpan> enclosing(List<MemberSpan> members, int line, int column, @Nullable String text) {
        List<MemberSpan> chain = new ArrayList<>();
        List<MemberSpan> level = members;
        boolean descended = true;
        while (descended) {
            descended = false;
            for (MemberSpan m : level) {
                boolean outside = line < m.startLine()
                        || line > m.endLine()
                        || (line == m.startLine() && (column < m.startColumn() || trailingStart(text, m)))
                        || (line == m.endLine() && column > m.endColumn());
                if (!outside && !m.kind().equals("package")) {
                    chain.add(m);
                    level = m.children();
                    descended = true;
                    break;
                }
            }
        }
        return chain;
    }

    /** Whether {@code m}'s range starts at a comment after other code on a line before its name's. */
    private static boolean trailingStart(@Nullable String text, MemberSpan m) {
        if (text == null || m.startLine() >= m.nameLine() || m.startColumn() == 0) {
            return false;
        }
        int at = 0;
        for (int l = 0; l < m.startLine() && at >= 0; l++) {
            at = text.indexOf('\n', at);
            at = at < 0 ? -1 : at + 1;
        }
        if (at < 0) {
            return false;
        }
        int end = Math.min(text.length(), at + m.startColumn());
        return !text.substring(at, end).isBlank();
    }

    /** {@link #code}, for the {@code ui} package's readers of Java text. */
    public static String codeOf(String text) {
        return code(text);
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
        boolean beforeParen = start < code.length() && code.charAt(start) == '(';
        if (beforeParen) {
            start++; // the caret just before the parameters' "(" (eleventh review BO3)
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\w$\\s,.<>\\[\\]@?&]*?(\\))?\\s*->")
                .matcher(code)
                .region(start, code.length());
        if (!m.lookingAt()) {
            return -1;
        }
        int arrow = m.end() - 2;
        boolean parenthesized = m.group(1) != null;
        if (beforeParen && !parenthesized) {
            return -1;
        }
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
            while (left > 0) {
                char c = code.charAt(left - 1);
                if (Character.isJavaIdentifierPart(c) || c == '.') {
                    left--;
                } else if (c == ']' || c == '>') { // String[]::new, List<String>::new (eleventh review BO3)
                    int open = matching(code, left - 1, c == ']' ? '[' : '<', c);
                    if (open < 0) {
                        break;
                    }
                    left = open;
                } else {
                    break;
                }
            }
            int right = k + 2;
            while (right < code.length() && Character.isWhitespace(code.charAt(right))) {
                right++;
            }
            if (right < code.length() && code.charAt(right) == '<') { // Collections::<List<String>>emptyList
                int close = closing(code, right, '<', '>', to); // nested type arguments (twelfth review BO3)
                right = close >= to ? right : close + 1;
            }
            while (right < code.length() && Character.isJavaIdentifierPart(code.charAt(right))) {
                right++;
            }
            if (left <= caret && caret <= right) {
                return k;
            }
        }
        return -1;
    }

    /** The offset of the {@code open} matching the {@code close} at {@code at}, reading back; -1 if none. */
    private static int matching(String code, int at, char open, char close) {
        int depth = 0;
        for (int i = at; i >= 0 && at - i < 200; i--) {
            char c = code.charAt(i);
            if (c == close) {
                depth++;
            } else if (c == open && --depth == 0) {
                return i;
            } else if (c == ';' || c == '{' || c == '}') {
                return -1;
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
        int header = anonymousHeader(code, from, end);
        if (header >= 0) {
            return new int[] {header, -1}; // on its header, from the type's name to its '{': the class (thirteenth BK2)
        }
        java.util.ArrayDeque<int[]> braces = new java.util.ArrayDeque<>(); // {offset of '{', anonymous type name or -1}
        for (int i = Math.max(0, from); i < end; i++) {
            char c = code.charAt(i);
            if (c == '{') {
                java.util.regex.Matcher m = ANONYMOUS.matcher(code.substring(Math.max(from, i - 300), i));
                // the name's last segment: jdtls prepares Comparator's hierarchy there, the anonymous class's at
                // "java" of java.util.Comparator (eleventh review BM1)
                int type = m.find() ? lastSegmentIn(code, Math.max(from, i - 300) + m.start(1)) : -1;
                braces.push(new int[] {i, type});
            } else if (c == '}' && !braces.isEmpty()) {
                braces.pop();
            }
        }
        // innermost: a method body directly inside an anonymous class body
        List<int[]> open = new ArrayList<>(braces); // innermost first
        if (!open.isEmpty() && open.get(0)[1] >= 0) {
            // in an anonymous class's body, outside its methods: on a method's header (its name, modifiers,
            // annotations, parameters, Javadoc), that method, as IntelliJ takes the member the caret's leaf is in
            // (eleventh review EM2, twelfth review DL1); elsewhere in the body (a field, between members), the
            // anonymous class itself, whose name offset stands for it with -1 as the method's (EL2)
            int name = methodHeaderName(text, code, open.get(0)[0] + 1, end);
            return new int[] {open.get(0)[1], name};
        }
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

    /** An anonymous class's header before its body's {@code '{'}: {@code new}, its (wrapped) name, type arguments and arguments. */
    private static final java.util.regex.Pattern ANONYMOUS = java.util.regex.Pattern.compile(
            "new\\s+([\\w$]+(?:\\s*\\.\\s*[\\w$]+)*)\\s*(<[^{};]*?>)?\\s*\\((?:[^(){};]|\\([^(){};]*\\))*\\)\\s*$");

    /**
     * The offset of the last segment of the type an anonymous class instantiates when {@code caret} is on its header,
     * from the type's name to the body's {@code '{'} ({@code new} itself is the enclosing member's, as in IntelliJ);
     * else -1 (thirteenth review BK2, EL2).
     */
    private static int anonymousHeader(String code, int from, int caret) {
        int depth = 0;
        for (int i = caret; i < code.length() && i < caret + 2000; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth <= 0 && (c == ';' || c == '}')) {
                return -1;
            } else if (depth <= 0 && c == '{') {
                int window = Math.max(from, i - 300);
                java.util.regex.Matcher m = ANONYMOUS.matcher(code.substring(window, i));
                return m.find() && window + m.start(1) <= caret ? lastSegmentIn(code, window + m.start(1)) : -1;
            }
        }
        return -1;
    }

    /**
     * The offset of the name of the method whose header holds {@code caret}, in a class body opened at {@code bodyStart}:
     * the header runs from what ends the member before it to the method's {@code {}, its parameter list and the
     * Javadoc right above it included; -1 when the caret is in no method header (a field, between members). The
     * member's start is found back past the parameter list the caret may be in (an unmatched {@code (}), the header's
     * end forward from that start, so a caret inside the parentheses or an annotated parameter does not lose the
     * method (twelfth review DL1).
     */
    private static int methodHeaderName(String text, String code, int bodyStart, int caret) {
        int at = Math.min(caret, code.length());
        int start = bodyStart;
        int depth = 0;
        for (int i = at - 1; i >= bodyStart; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth = Math.max(0, depth - 1); // an unmatched '(' is the list the caret is in
            } else if (depth == 0 && (c == '{' || c == '}' || c == ';')) {
                start = i + 1;
                break;
            }
        }
        int first = start;
        while (first < code.length() && Character.isWhitespace(code.charAt(first))) {
            first++;
        }
        if (at < first && !inLeadingComment(text.substring(start, Math.min(first, text.length())), at - start)) {
            return -1; // between members, not on one
        }
        int brace = -1;
        depth = 0;
        for (int i = first; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == ';' || c == '}' || c == '=')) {
                return -1; // a field, or the body's end
            } else if (depth == 0 && c == '{') {
                brace = i;
                break;
            }
        }
        if (brace < 0 || at > brace) {
            return -1;
        }
        // the name: the identifier before the header's first '(' outside its annotations and type parameters
        int i = first;
        while (i < brace) {
            char c = code.charAt(i);
            if (c == '@') {
                i++;
                while (i < brace && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
                    i++;
                }
                while (i < brace && Character.isWhitespace(code.charAt(i))) {
                    i++;
                }
                if (i < brace && code.charAt(i) == '(') {
                    i = closing(code, i, '(', ')', brace) + 1;
                }
            } else if (c == '<') {
                i = closing(code, i, '<', '>', brace) + 1;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < brace && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                int k = j;
                while (k < brace && Character.isWhitespace(code.charAt(k))) {
                    k++;
                }
                if (k < brace && code.charAt(k) == '(') {
                    return code.substring(i, j).equals("new") ? -1 : i; // not an anonymous class's own header
                }
                i = j;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * Whether {@code rel} is in a comment of {@code gap}, the blanks and comments before a member's header, that the
     * member owns, as IntelliJ binds it: its Javadoc or a block comment, with other comments after it too (thirteenth
     * review EM2), or a {@code //} comment on lines of its own with no blank line before the header (BK6).
     */
    static boolean inLeadingComment(String gap, int rel) {
        for (int i = 0; i < gap.length(); ) {
            boolean line = gap.startsWith("//", i);
            if (!line && !gap.startsWith("/*", i)) {
                i++;
                continue;
            }
            int end = line ? gap.indexOf('\n', i) : gap.indexOf("*/", i + 2);
            end = end < 0 ? gap.length() : line ? end : end + 2;
            if (rel >= i && rel <= end) {
                int lineStart = gap.lastIndexOf('\n', i);
                return !line
                        || (lineStart >= 0
                                && gap.substring(lineStart, i)
                                        .replaceAll("(?s)/\\*.*?\\*/", "")
                                        .isBlank() // its own line, or after a Javadoc
                                && !java.util.regex.Pattern.compile("\n[ \t]*\n")
                                        .matcher(gap.substring(end))
                                        .find());
            }
            i = end;
        }
        return false;
    }

    /** The offset of the {@code close} matching the {@code open} at {@code at}, reading on; {@code limit} if none. */
    private static int closing(String code, int at, char open, char close, int limit) {
        int depth = 0;
        for (int i = at; i < limit; i++) {
            if (code.charAt(i) == open) {
                depth++;
            } else if (code.charAt(i) == close && --depth == 0) {
                return i;
            }
        }
        return limit;
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
        return overridden(members, method, typeVariables, bindings, m -> java.util.Map.of());
    }

    /**
     * As {@link #overridden(List, String, java.util.Set, java.util.Map)}, with each candidate's own method type
     * variables from {@code ownTypeVariables}: each key is a type variable of that method, bound to the asked method's
     * type variable at its position, or to null (any type) when the two do not pair up (eleventh review BM2).
     */
    public static List<MemberSpan> overridden(
            List<MemberSpan> members,
            String method,
            java.util.Set<String> typeVariables,
            java.util.Map<String, String> bindings,
            java.util.function.Function<MemberSpan, java.util.Map<String, @Nullable String>> ownTypeVariables) {
        String name = methodName(method);
        List<String> params = parameters(method);
        List<MemberSpan> out = new ArrayList<>();
        List<MemberSpan> exact = new ArrayList<>();
        for (MemberSpan m : members) {
            if (m.kind().equals("method") && methodName(m.name()).equals(name)) {
                List<String> theirs = parameters(m.name());
                boolean same = theirs.size() == params.size();
                boolean equal = same;
                java.util.Map<String, @Nullable String> own = same ? ownTypeVariables.apply(m) : java.util.Map.of();
                java.util.Set<String> vars = typeVariables;
                java.util.Map<String, String> bound = bindings;
                if (!own.isEmpty()) {
                    vars = new java.util.HashSet<>(typeVariables);
                    vars.addAll(own.keySet());
                    java.util.Map<String, String> withOwn = new java.util.HashMap<>(bindings);
                    own.forEach((k, v) -> {
                        if (v != null) {
                            withOwn.put(k, v);
                        } else {
                            withOwn.remove(k); // the method's own, so not the type's of the same name
                        }
                    });
                    bound = withOwn;
                }
                for (int i = 0; same && i < params.size(); i++) {
                    same = sameParameter(params.get(i), theirs.get(i), vars, bound);
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
        String simple = name.replaceAll("<.*", "").replaceAll(".*[$.]", "").strip(); // jdtls's Outer$Inner too
        MemberSpan named = typeNamedAt(members, line, simple); // one of several types on a line (thirteenth BK4)
        MemberSpan at = named != null ? named : typeAt(members, line);
        return at != null ? at : typeNamed(members, simple);
    }

    private static @Nullable MemberSpan typeNamedAt(List<MemberSpan> members, int line, String name) {
        for (MemberSpan m : members) {
            if (m.isType()
                    && m.nameLine() == line
                    && m.name().replaceAll("<.*", "").strip().equals(name)) {
                return m;
            }
            MemberSpan inner =
                    m.startLine() <= line && line <= m.endLine() ? typeNamedAt(m.children(), line, name) : null;
            if (inner != null) {
                return inner;
            }
        }
        return null;
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
     * {@code {}; empty when it gives none (a raw type, {@code <>}) or does not name it. Only a mention outside every
     * {@code <…>} counts, the first that gives arguments: not a bound in the header's own type parameters
     * ({@code <T extends Comparable<T>>}), nor an argument of it or of another supertype ({@code List<List<Double>>},
     * {@code Comparable<Store<Integer>>}) (tenth review DO2, eleventh review DM1).
     */
    public static List<String> typeArguments(String text, int from, String type) {
        return typeArguments(text, from, type, false);
    }

    /** As {@link #typeArguments(String, int, String)}; {@code ownName}: {@code from} is the declared type's own name. */
    public static List<String> typeArguments(String text, int from, String type, boolean ownName) {
        List<String> found = new ArrayList<>();
        mentions(text, from, type, ownName, (code, after) -> {
            if (after < code.length() && code.charAt(after) == '<') {
                List<String> args = arguments(code, after);
                if (!args.isEmpty()) {
                    found.addAll(args);
                    return true;
                }
            }
            return false;
        });
        return found;
    }

    /**
     * Whether a declaration's header names {@code type} raw ({@code interface R extends Store}), outside every
     * {@code <…>}: its type variables are then their erasure (eleventh review BM4). A {@code <>} is no raw type.
     */
    public static boolean namesRaw(String text, int from, String type, boolean ownName) {
        boolean[] raw = {false};
        mentions(text, from, type, ownName, (code, after) -> {
            raw[0] = after >= code.length() || (code.charAt(after) != '<' && code.charAt(after) != '.');
            return raw[0];
        });
        return raw[0];
    }

    /**
     * Calls {@code at} with the offset after each mention of {@code type}'s simple name (and the blanks after it)
     * outside every {@code <…>}, from {@code from} to the header's {@code {}, until it answers true.
     */
    private static void mentions(
            String text, int from, String type, boolean ownName, java.util.function.BiPredicate<String, Integer> at) {
        String code = code(text);
        int start = Math.max(0, Math.min(from, code.length()));
        int brace = code.indexOf('{', start);
        int end = brace < 0 ? code.length() : brace;
        String simple = type.replaceAll("<.*", "").strip();
        simple = simple.substring(simple.lastIndexOf('.') + 1);
        int i = start;
        if (ownName) { // the declared type's own name, and its own type parameters
            while (i < end && Character.isJavaIdentifierPart(code.charAt(i))) {
                i++;
            }
        }
        int depth = 0;
        while (i < end) {
            char c = code.charAt(i);
            if (c == '<') {
                depth++;
                i++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
                i++;
            } else if (Character.isJavaIdentifierStart(c)
                    && (i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1)))) {
                int j = i;
                while (j < end && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                if (depth == 0 && code.substring(i, j).equals(simple)) {
                    int after = j;
                    while (after < end && Character.isWhitespace(code.charAt(after))) {
                        after++;
                    }
                    if (at.test(code, after)) {
                        return;
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
    }

    /**
     * The offset of the last segment of the (qualified) type name at {@code at}, after an optional {@code new}:
     * {@code Comparator} of {@code new java.util.Comparator<>()} (eleventh review BM1).
     */
    public static int lastSegment(String text, int at) {
        return lastSegmentIn(code(text), at);
    }

    private static int lastSegmentIn(String code, int at) {
        int i = Math.max(0, Math.min(at, code.length()));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:new\\s+)?([\\w$]+(?:\\s*\\.\\s*[\\w$]+)*)")
                .matcher(code)
                .region(i, code.length());
        if (!m.lookingAt()) {
            return i;
        }
        String name = m.group(1);
        int dot = name.lastIndexOf('.');
        int last = m.start(1) + dot + 1;
        while (last < code.length() && Character.isWhitespace(code.charAt(last))) {
            last++;
        }
        return last;
    }

    /**
     * The offset where the qualified type name whose last segment is at {@code last} starts ({@code Wrong12} of
     * {@code new Wrong12.Mid.Sink<>()}), and its segments (twelfth review BL1).
     */
    public static java.util.Map.Entry<Integer, List<String>> qualifiedName(String text, int last) {
        String code = code(text);
        int start = Math.max(0, Math.min(last, code.length()));
        while (start > 0) {
            int i = start - 1;
            while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
                i--;
            }
            if (i < 0 || code.charAt(i) != '.') {
                break;
            }
            i--;
            while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
                i--;
            }
            int j = i;
            while (j >= 0 && Character.isJavaIdentifierPart(code.charAt(j))) {
                j--;
            }
            if (j == i) {
                break;
            }
            start = j + 1;
        }
        int end = Math.max(0, Math.min(last, code.length()));
        while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
            end++;
        }
        List<String> segments = new ArrayList<>();
        for (String part : code.substring(start, end).split("\\.")) {
            if (!part.isBlank()) {
                segments.add(part.strip());
            }
        }
        return java.util.Map.entry(start, segments);
    }

    /** The nested type {@code path} names under {@code outer} ({@code Mid}, {@code Sink}), or null. */
    public static @Nullable MemberSpan nestedType(MemberSpan outer, List<String> path) {
        return nestedType(List.of(), outer, path);
    }

    /**
     * As above, also among {@code members}, the outline {@code outer} is in: jdtls lists a class file's member types
     * flat, beside their outer type, nested only by range (thirteenth review DK1).
     */
    public static @Nullable MemberSpan nestedType(List<MemberSpan> members, MemberSpan outer, List<String> path) {
        MemberSpan at = outer;
        for (String name : path) {
            MemberSpan next = null;
            for (MemberSpan child : at.children()) {
                if (child.isType() && child.name().replaceAll("<.*", "").strip().equals(name)) {
                    next = child;
                    break;
                }
            }
            for (int i = 0; next == null && i < members.size(); i++) {
                MemberSpan m = members.get(i);
                if (m != at
                        && m.isType()
                        && m.name().replaceAll("<.*", "").strip().equals(name)
                        && m.startLine() >= at.startLine()
                        && m.endLine() <= at.endLine()
                        && m.nameLine() > at.nameLine()) {
                    next = m;
                }
            }
            if (next == null) {
                return null;
            }
            at = next;
        }
        return at;
    }

    /**
     * The type whose name starts at {@code line} and {@code column}: one of several types declared on one line
     * (thirteenth review BK4); else the type whose name is on {@code line}, else the innermost type holding it, the
     * class of a constructor a definition answered (BK3). Null for none.
     */
    public static @Nullable MemberSpan typeAt(List<MemberSpan> members, int line, int column) {
        for (MemberSpan m : members) {
            if (m.isType() && m.nameLine() == line && m.nameColumn() == column) {
                return m;
            }
            MemberSpan inner = m.startLine() <= line && line <= m.endLine() ? typeAt(m.children(), line, column) : null;
            if (inner != null && inner.nameLine() == line && inner.nameColumn() == column) {
                return inner;
            }
        }
        MemberSpan named = typeAt(members, line);
        return named != null ? named : holding(members, line);
    }

    private static @Nullable MemberSpan holding(List<MemberSpan> members, int line) {
        for (MemberSpan m : members) {
            if (m.startLine() <= line && line <= m.endLine()) {
                MemberSpan inner = holding(m.children(), line);
                return inner != null ? inner : m.isType() ? m : null;
            }
        }
        return null;
    }

    /** The comma-separated arguments of the {@code <…>} opening at {@code open}. */
    private static List<String> arguments(String code, int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int arg = open + 1;
        for (int i = open + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '<') {
                depth++;
            } else if ((c == '>' && depth-- == 0) || (c == ',' && depth == 0)) {
                if (!code.substring(arg, i).isBlank()) {
                    args.add(code.substring(arg, i).strip().replaceAll("\\s+", " "));
                }
                arg = i + 1;
                if (c == '>') {
                    break;
                }
            }
        }
        return args;
    }

    /**
     * A method's own type parameters ({@code <K, V>} of {@code public <K, V> Map<K, V> f()}), read from its declaration
     * in {@code text} from {@code from} (its line's start) to its name at {@code name} (eleventh review BM2).
     */
    public static List<String> methodTypeParameters(String text, int from, int name) {
        String head = declarationHead(code(text), from, name)
                .replaceAll("@[\\w.]+(\\s*\\([^)]*\\))?", " ")
                .replaceFirst(
                        "^\\s*((public|protected|private|static|abstract|final|default|synchronized|native|strictfp)"
                                + "\\s+)*",
                        "")
                .strip();
        if (!head.startsWith("<")) {
            return List.of();
        }
        int depth = 0;
        for (int i = 0; i < head.length(); i++) {
            if (head.charAt(i) == '<') {
                depth++;
            } else if (head.charAt(i) == '>' && --depth == 0) {
                return typeParameters("m" + head.substring(0, i + 1));
            }
        }
        return List.of();
    }

    /** Each type parameter's erasure in a type's outline name: {@code Narrow<T extends Number>} gives T as Number. */
    public static java.util.Map<String, String> typeParameterErasures(String typeName) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        int open = typeName.indexOf('<');
        int close = typeName.lastIndexOf('>');
        if (open < 0 || close < open) {
            return out;
        }
        for (String p : arguments(typeName.substring(0, close + 1), open)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "^(?:@[\\w.]+\\s*)*([A-Za-z_$][\\w$]*)(?:\\s+extends\\s+([\\w$.]+))?")
                    .matcher(p);
            if (m.find()) {
                String bound = m.group(2) == null ? "Object" : m.group(2);
                out.put(m.group(1), bound.substring(bound.lastIndexOf('.') + 1));
            }
        }
        return out;
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

    /**
     * {@code name} with the type parameters written after it at {@code at} in {@code text} ({@code LKeyed<Key>} of a
     * decompiled {@code public interface LKeyed<Key> {}), or {@code name} when none are (twelfth review DL2).
     */
    public static String withTypeParameters(String text, int at, String name) {
        String code = code(text);
        int i = Math.max(0, Math.min(at, code.length()));
        while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) {
            i++;
        }
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        if (i >= code.length() || code.charAt(i) != '<') {
            return name;
        }
        int close = closing(code, i, '<', '>', code.length());
        return close >= code.length()
                ? name
                : name + code.substring(i, close + 1).replaceAll("\\s+", " ");
    }

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
