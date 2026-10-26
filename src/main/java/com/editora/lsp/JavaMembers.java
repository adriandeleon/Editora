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
     * The types and methods whose whole range (annotations and Javadoc included) holds {@code line}, outermost first;
     * the last is the innermost. Empty when the line is outside every type.
     */
    public static List<MemberSpan> enclosing(List<MemberSpan> members, int line) {
        List<MemberSpan> chain = new ArrayList<>();
        List<MemberSpan> level = members;
        boolean descended = true;
        while (descended) {
            descended = false;
            for (MemberSpan m : level) {
                if (m.startLine() <= line && line <= m.endLine() && (m.isType() || m.isCallable())) {
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
                top.switchLabel()[0] = false;
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
     * The methods among {@code members} that {@code method} (a symbol name) may override: the same name and number of
     * parameters, and of several such, those whose parameter types are written the same.
     */
    public static List<MemberSpan> overridden(List<MemberSpan> members, String method) {
        String name = methodName(method);
        List<String> params = parameters(method);
        List<MemberSpan> same = new ArrayList<>();
        for (MemberSpan m : members) {
            if (m.kind().equals("method")
                    && methodName(m.name()).equals(name)
                    && parameters(m.name()).size() == params.size()) {
                same.add(m);
            }
        }
        if (same.size() > 1) {
            List<MemberSpan> exact = same.stream()
                    .filter(m -> parameters(m.name()).equals(params))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }
        return same;
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
