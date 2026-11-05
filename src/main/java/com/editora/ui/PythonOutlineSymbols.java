package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.PythonScan;
import com.editora.editor.highlight.TextMateHighlighter.Symbol;

/**
 * What a Python file's Structure shows without a language server besides its defs and classes — pure, tested.
 *
 * <p>The grammar names only {@code def}/{@code class}/{@code type} declarations, so the outline left out the
 * module's constants and a class's fields, which Pyright's outline lists (review 2 C113): a module-level
 * {@code NAME = …} / {@code NAME: T = …} and a class-body {@code name: T} / {@code name = …} are added here.
 * Locals inside functions are not structure, nor are names assigned inside an {@code if} or {@code for}.
 * The marker beside a def ({@code async}, {@code property}, {@code classmethod} …) is read here too, in both
 * modes.
 */
final class PythonOutlineSymbols {

    private PythonOutlineSymbols() {}

    /** `NAME =` (not `==`), `NAME: T`, `NAME: T = …` at the start of a statement. */
    private static final Pattern ASSIGNMENT = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(?::(?!=)|=(?!=))");

    private static final Set<String> KEYWORDS = Set.of(
            "if",
            "elif",
            "else",
            "for",
            "while",
            "try",
            "except",
            "finally",
            "with",
            "def",
            "class",
            "return",
            "lambda",
            "match",
            "case",
            "type",
            "async",
            "await",
            "import",
            "from",
            "global",
            "nonlocal",
            "del",
            "assert",
            "raise",
            "pass",
            "break",
            "continue",
            "yield",
            "print");

    /** The module-level variables and class-level fields of {@code text}, as outline symbols. */
    static List<Symbol> variables(String text) {
        List<PythonScan.Line> lines = PythonScan.scan(text); // one entry per line: the index is the line
        List<Symbol> out = new ArrayList<>();
        // The classes we are in, innermost first: {header indent, body indent (-1 until its first statement)}.
        java.util.ArrayDeque<int[]> classes = new java.util.ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            PythonScan.Line l = lines.get(i);
            if (!l.statement()) {
                continue;
            }
            int indent = l.indentEnd() - l.start();
            while (!classes.isEmpty() && indent <= classes.peek()[0]) {
                classes.pop(); // that class has ended
            }
            int[] cls = classes.peek();
            if (cls != null && cls[1] < 0) {
                cls[1] = indent;
            }
            String word = PythonScan.keyword(text, l);
            if (word.equals("class")) {
                classes.push(new int[] {indent, -1});
                continue;
            }
            boolean classLevel = cls != null && indent == cls[1];
            if (indent != 0 && !classLevel || KEYWORDS.contains(word)) {
                continue;
            }
            Matcher m = ASSIGNMENT.matcher(text);
            m.region(l.indentEnd(), l.end());
            if (!m.lookingAt()) {
                continue;
            }
            String name = m.group(1);
            out.add(new Symbol(i, name, classLevel ? "field" : isConstantName(name) ? "constant" : "variable"));
        }
        return out;
    }

    private static boolean isConstantName(String name) {
        return name.chars().anyMatch(Character::isLetter) && name.equals(name.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * The small marker shown beside a def/class row: {@code async} for an {@code async def}, and the decorators
     * that change what a def is ({@code property}, {@code classmethod}, {@code staticmethod}, {@code abstract},
     * {@code setter}, {@code overload}) — read from the def's line and the decorator lines right above it.
     * {@code ""} when there is none.
     */
    static String marker(java.util.function.IntFunction<String> lineAt, int line) {
        String def = lineAt.apply(line);
        if (def == null) {
            return "";
        }
        String head = def.strip();
        List<String> marks = new ArrayList<>();
        int indent = def.length() - def.stripLeading().length();
        for (int i = line - 1; i >= 0 && line - i <= 8; i--) {
            String above = lineAt.apply(i);
            if (above == null) {
                break;
            }
            String a = above.strip();
            if (!a.startsWith("@") || above.length() - above.stripLeading().length() != indent) {
                break;
            }
            String mark = decoratorMark(a);
            if (mark != null && !marks.contains(mark)) {
                marks.add(0, mark);
            }
        }
        if (head.startsWith("async ") && head.substring(6).stripLeading().startsWith("def ")) {
            marks.add(0, "async");
        }
        return String.join(" ", marks);
    }

    private static @org.jspecify.annotations.Nullable String decoratorMark(String decorator) {
        String name = decorator.substring(1).strip();
        int paren = name.indexOf('(');
        if (paren >= 0) {
            name = name.substring(0, paren).strip();
        }
        String last = name.substring(name.lastIndexOf('.') + 1);
        return switch (last) {
            case "property", "cached_property" -> "property";
            case "classmethod" -> "classmethod";
            case "staticmethod" -> "staticmethod";
            case "abstractmethod" -> "abstract";
            case "setter", "deleter" -> last;
            case "overload" -> "overload";
            default -> null;
        };
    }
}
