package com.editora.lsp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The named type a line of Java source is in, nested types joined by dots ({@code AbstractList.SubList}): what an
 * implementation inside a library class is listed as, so a nested class's rows no longer read as their outer
 * class's (fifth review BN4). Read from the braces; comments, strings and anonymous classes are skipped.
 */
public final class JavaTypeAtLine {

    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private JavaTypeAtLine() {}

    /** The type {@code line} (0-based) is in, or null when it is in none or the source cannot be read. */
    public static @Nullable String at(String source, int line) {
        record Open(String name, int depth) {}
        Deque<Open> open = new ArrayDeque<>();
        String pending = null;
        int depth = 0;
        int row = 0;
        StringBuilder word = new StringBuilder();
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char ch = source.charAt(i);
            if (ch == '\n') {
                if (++row > line) {
                    break;
                }
                continue;
            }
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = (end < 0 ? n : end) - 1;
                continue;
            }
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) {
                    row += source.charAt(k) == '\n' ? 1 : 0;
                }
                if (row > line) {
                    break;
                }
                i = stop - 1;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                int k = i + 1;
                while (k < n && source.charAt(k) != ch && source.charAt(k) != '\n') {
                    k += source.charAt(k) == '\\' ? 2 : 1;
                }
                i = Math.min(k, n - 1);
                continue;
            }
            if (Character.isJavaIdentifierPart(ch)) {
                word.append(ch);
                continue;
            }
            if (!word.isEmpty()) {
                pending = typeName(source, i, word.toString(), pending);
                word.setLength(0);
            }
            if (ch == '{') {
                depth++;
                if (pending != null) {
                    open.push(new Open(pending, depth));
                    pending = null;
                }
            } else if (ch == '}') {
                if (!open.isEmpty() && open.peek().depth() == depth) {
                    open.pop();
                }
                depth--;
            } else if (ch == ';') {
                pending = null;
            }
        }
        if (open.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        open.descendingIterator()
                .forEachRemaining(o -> out.append(out.isEmpty() ? "" : ".").append(o.name()));
        return out.toString();
    }

    /** After the word ending at {@code end}: a type keyword names the next word; else what was pending stays. */
    private static @Nullable String typeName(String source, int end, String word, @Nullable String pending) {
        if (!word.equals("class") && !word.equals("interface") && !word.equals("enum") && !word.equals("record")) {
            return pending;
        }
        int start = end - word.length();
        if (start > 0 && source.charAt(start - 1) == '.') {
            return pending; // Foo.class
        }
        Matcher m = TYPE.matcher(source);
        return m.find(start) && m.start() == start ? m.group(1) : pending;
    }
}
