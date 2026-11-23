package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What the Debug window's Autos show, as Visual Studio's Autos window does: the variables and member chains on the
 * line a program stopped at and the line before it ({@code total}, {@code order.items}, {@code self.count}), in the
 * order they appear there. Strings and comments are skipped, and so are keywords, a call's own name (its receiver
 * stays: {@code list.add(x)} gives {@code list} and {@code x}) and a declaration's type ({@code String who} gives
 * {@code who}). What the adapter cannot evaluate is dropped later, where it answers. Pure.
 */
public final class AutoExpressions {

    private AutoExpressions() {}

    /** How many expressions the Autos show at most. */
    public static final int MAX = 10;

    /** An identifier, then {@code .x}, {@code ->x} or {@code ::x} links. */
    private static final Pattern CHAIN =
            Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*(?:\\s*(?:\\.|->|::)\\s*[A-Za-z_$][\\w$]*)*");

    /** The words of the debugged languages that are not values. */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract",
            "and",
            "as",
            "assert",
            "async",
            "await",
            "break",
            "case",
            "catch",
            "class",
            "const",
            "continue",
            "def",
            "default",
            "defer",
            "del",
            "delete",
            "do",
            "elif",
            "else",
            "end",
            "enum",
            "except",
            "export",
            "extends",
            "false",
            "False",
            "final",
            "finally",
            "fn",
            "for",
            "foreach",
            "from",
            "fun",
            "func",
            "function",
            "global",
            "go",
            "goto",
            "if",
            "impl",
            "implements",
            "import",
            "in",
            "instanceof",
            "interface",
            "is",
            "lambda",
            "let",
            "local",
            "loop",
            "match",
            "mod",
            "module",
            "mut",
            "new",
            "nil",
            "None",
            "nonlocal",
            "not",
            "null",
            "object",
            "of",
            "or",
            "package",
            "pass",
            "private",
            "protected",
            "pub",
            "public",
            "raise",
            "record",
            "repeat",
            "require",
            "return",
            "self",
            "static",
            "struct",
            "super",
            "switch",
            "synchronized",
            "then",
            "this",
            "throw",
            "throws",
            "trait",
            "true",
            "True",
            "try",
            "type",
            "typeof",
            "undefined",
            "unless",
            "until",
            "use",
            "val",
            "var",
            "void",
            "when",
            "where",
            "while",
            "with",
            "yield",
            "int",
            "long",
            "short",
            "byte",
            "char",
            "float",
            "double",
            "boolean",
            "bool",
            "string",
            "str",
            "usize",
            "isize",
            "u8",
            "u16",
            "u32",
            "u64",
            "i8",
            "i16",
            "i32",
            "i64",
            "f32",
            "f64",
            "auto",
            "unsigned",
            "signed",
            "sizeof",
            "echo",
            "print",
            "println",
            "printf",
            "puts",
            "fmt");

    /**
     * The expressions of {@code previous} (the line before the stop, or null) and {@code current} (the stop's line)
     * of a file in {@code language} ({@code python}, {@code java}…): each once, in the order they appear, at most
     * {@link #MAX}.
     */
    public static List<String> of(@Nullable String language, @Nullable String previous, @Nullable String current) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : new String[] {previous, current}) {
            if (line == null) {
                continue;
            }
            String code = codeOf(line, language);
            Matcher m = CHAIN.matcher(code);
            while (m.find() && out.size() < MAX) {
                String chain = m.group().replaceAll("\\s+", "");
                int after = skipSpaces(code, m.end());
                char next = after < code.length() ? code.charAt(after) : '\0';
                if (next == '(' || next == '!') { // a call (Rust's macro! too): its receiver, not the call itself
                    chain = receiverOf(chain);
                } else if ((Character.isLetter(next) || next == '_') && !KEYWORDS.contains(wordAt(code, after))
                        || next == '<') {
                    continue; // a declaration's type (String who, List<X> xs), not a value; "count is None" is one
                }
                if (chain == null || chain.isEmpty()) {
                    continue;
                }
                String first = firstPart(chain);
                boolean member = !first.equals(chain);
                if (KEYWORDS.contains(first) && !(member && (first.equals("self") || first.equals("this")))) {
                    continue; // a keyword; but self.count and this.total are values
                }
                if (isPythonish(language) && isPythonName(code, m.start(), after, next)) {
                    continue; // a keyword argument's or annotation's name in Python (f(x=1), x: int)
                }
                out.add(chain);
            }
        }
        return List.copyOf(out);
    }

    /** {@code chain} without its last link ({@code a.b.c} → {@code a.b}), or null for a lone name. */
    private static @Nullable String receiverOf(String chain) {
        int cut = Math.max(chain.lastIndexOf('.'), Math.max(chain.lastIndexOf("->"), chain.lastIndexOf("::")));
        return cut <= 0 ? null : chain.substring(0, cut);
    }

    private static String firstPart(String chain) {
        Matcher m = Pattern.compile("^[A-Za-z_$][\\w$]*").matcher(chain);
        return m.find() ? m.group() : chain;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** The word starting at {@code i} of {@code s}, or "". */
    private static String wordAt(String s, int i) {
        int j = i;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        return s.substring(i, j);
    }

    /**
     * Whether the name at {@code start} is a Python annotation's ({@code x: int}) or a keyword argument's
     * ({@code f(key=a)}: after {@code (} or {@code ,}, before one {@code =}).
     */
    private static boolean isPythonName(String code, int start, int after, char next) {
        if (next == ':' && (after + 1 >= code.length() || code.charAt(after + 1) != '=')) {
            return true;
        }
        if (next != '=' || (after + 1 < code.length() && code.charAt(after + 1) == '=')) {
            return false;
        }
        int before = start - 1;
        while (before >= 0 && code.charAt(before) == ' ') {
            before--;
        }
        return before >= 0 && (code.charAt(before) == '(' || code.charAt(before) == ',');
    }

    private static boolean isPythonish(@Nullable String language) {
        return "python".equals(language);
    }

    /**
     * {@code line} with its strings blanked and its line comment cut: {@code #} in Python, Ruby, shells, Elixir;
     * {@code --} in Lua and SQL; {@code //} (and a block comment's text) elsewhere.
     */
    static String codeOf(String line, @Nullable String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        boolean hash =
                Set.of("python", "ruby", "bash", "shell", "elixir", "perl", "r").contains(lang);
        boolean dashes = "lua".equals(lang);
        StringBuilder out = new StringBuilder(line.length());
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < n && line.charAt(j) != c) {
                    j += line.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(' ');
                i = Math.min(j, n);
                continue;
            }
            if ((hash && c == '#')
                    || (dashes && c == '-' && i + 1 < n && line.charAt(i + 1) == '-')
                    || (!hash && !dashes && c == '/' && i + 1 < n && line.charAt(i + 1) == '/')) {
                break;
            }
            if (!hash && !dashes && c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                out.append(' ');
                i = end + 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The 0-based lines {@code line - 1} and {@code line} of {@code text}: {@code [previous or null, current]}. */
    public static String[] linesAround(String text, int line) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        String current = line >= 0 && line < lines.size() ? lines.get(line) : null;
        String previous = null;
        for (int i = line - 1; i >= 0 && i < lines.size(); i--) {
            if (!lines.get(i).isBlank()) {
                previous = lines.get(i); // the nearest line with code on it
                break;
            }
        }
        return new String[] {previous, current};
    }
}
