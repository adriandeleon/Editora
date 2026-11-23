package com.editora.run;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a PHP file's Run ▶ goes, if it has one: a script with code outside its classes and functions, an
 * {@code index.php} (its web app's server), Laravel's {@code artisan} — from the top; a PHPUnit test file at its class
 * (its tests). A file of classes alone has none: it does nothing when run, and in a web app it is served from its
 * entry ({@link PhpApps}). Pure.
 */
public final class PhpRunLines {

    private PhpRunLines() {}

    /** Top-level statements that declare rather than do. */
    private static final Set<String> DECLARING = Set.of(
            "namespace",
            "use",
            "declare",
            "class",
            "abstract",
            "final",
            "readonly",
            "interface",
            "trait",
            "enum",
            "function",
            "require",
            "require_once",
            "include",
            "include_once",
            "return"); // a configuration file's return [ … ]

    /** What a helpers file's {@code if} asks before it declares: {@code function_exists}, {@code class_exists}… */
    private static final Pattern GUARD = Pattern.compile(
            "\\b(?:function_exists|class_exists|interface_exists|trait_exists|enum_exists|defined)\\s*\\(");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][\\w]*");

    private static final Pattern CLASS_LINE = Pattern.compile("^\\s*(?:(?:abstract|final|readonly)\\s+)*class\\s+\\w+");

    /** The 0-based line of file {@code name}'s Run ▶ (text {@code text}), or -1 for none. */
    public static int runLine(String name, String text) {
        if (PhpApps.isArtisan(name) || name.equalsIgnoreCase("index.php")) {
            return 0;
        }
        if (PhpApps.isTestFile(name, text)) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (CLASS_LINE.matcher(lines[i]).find()) {
                    return i;
                }
            }
            return 0;
        }
        return hasTopLevelCode(text) ? 0 : -1;
    }

    /**
     * Whether {@code text} runs something of its own: a statement in its PHP code outside every class, function and
     * block, other than a declaration ({@code namespace}, {@code use}, {@code class}, {@code function}…), an
     * include, a {@code return}, or a helpers file's {@code if (!function_exists(…))} guard. A template's HTML does
     * not count.
     */
    public static boolean hasTopLevelCode(String text) {
        String code = topLevel(text);
        for (String statement : code.split(";")) {
            String s = statement.strip();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = WORD.matcher(s);
            if (s.startsWith("#[")) {
                continue; // an attribute before a declaration
            }
            if (!m.lookingAt()) {
                return true; // $x = …, echo-less output like (new App)->run()
            }
            String word = m.group().toLowerCase(Locale.ROOT);
            if (word.equals("if") && GUARD.matcher(s).find()) {
                continue; // if (!function_exists('x')) { function x() … }: a helpers file's guard
            }
            if (!DECLARING.contains(word)) {
                return true; // echo, print, a call, exit, an if…
            }
        }
        return false;
    }

    /**
     * The PHP code of {@code text} at brace depth 0, its strings, comments and blocks blanked: the statements a file
     * runs from the top. A block's braces end the statement before them, so {@code class A {…}} leaves
     * {@code class A ;}.
     */
    static String topLevel(String text) {
        StringBuilder out = new StringBuilder();
        int n = text.length();
        boolean php = false;
        int depth = 0;
        for (int i = 0; i < n; i++) {
            if (!php) {
                if (text.startsWith("<?php", i)) {
                    php = true;
                    i += 4;
                } else if (text.startsWith("<?=", i)) {
                    php = true;
                    out.append("echo ");
                    i += 2;
                }
                continue;
            }
            char c = text.charAt(i);
            if (text.startsWith("?>", i)) {
                php = false;
                out.append(';');
                i++;
                continue;
            }
            if (c == '#' && !text.startsWith("#[", i) || text.startsWith("//", i)) {
                int end = endOfLineComment(text, i);
                i = end - 1;
                continue;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c) {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                if (depth == 0) {
                    out.append("''");
                }
                i = Math.min(j, n);
                continue;
            }
            if (text.startsWith("<<<", i)) { // a heredoc or nowdoc: to its closing identifier
                Matcher id = Pattern.compile("<<<\\s*['\"]?([A-Za-z_]\\w*)")
                        .matcher(text)
                        .region(i, n);
                if (id.lookingAt()) {
                    Matcher close = Pattern.compile("(?m)^\\s*" + Pattern.quote(id.group(1)) + "\\b")
                            .matcher(text)
                            .region(id.end(), n);
                    if (depth == 0) {
                        out.append("''");
                    }
                    i = close.find() ? close.end() - 1 : n;
                    continue;
                }
            }
            if (c == '{') {
                if (depth == 0) {
                    out.append(';');
                }
                depth++;
                continue;
            }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Where a {@code //} or {@code #} comment starting at {@code i} ends: its line's end, or a {@code ?>}. */
    private static int endOfLineComment(String text, int i) {
        int eol = text.indexOf('\n', i);
        int close = text.indexOf("?>", i);
        int end = eol < 0 ? text.length() : eol;
        return close >= 0 && close < end ? close : end;
    }
}
