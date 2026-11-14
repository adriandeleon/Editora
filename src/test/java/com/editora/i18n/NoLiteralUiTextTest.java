package com.editora.i18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No English word is typed into a control: every label, prompt, title and tooltip takes its text from the
 * catalog, through {@link Messages#tr}. A literal in one of those places is shown as written in all six
 * languages, and nothing else notices: the catalogs stay in parity because the string is in none of them.
 *
 * <p>The scan reads each source that uses JavaFX controls and looks inside the arguments of a control's
 * constructor, of the setters that take text, and of the two helpers that name a control. A literal there
 * with two letters side by side fails the test unless it is the key of a lookup ({@code tr("some.key")}) or
 * one of the entries of {@link #NOT_LANGUAGE}, each of which says why it is not a word to translate.
 */
class NoLiteralUiTextTest {

    /** The places that take text a user reads: a control's constructor, a text setter, a naming helper. */
    private static final Pattern TEXT_POSITION = Pattern.compile("(?:new (?:Label|Button|CheckBox|MenuItem|Tooltip"
            + "|Text|RadioButton|ToggleButton|Tab|TitledPane|Hyperlink|CheckMenuItem|RadioMenuItem|Menu"
            + "|TableColumn(?:<[^>]*>)?|TreeTableColumn(?:<[^>]*>)?)"
            + "|\\.set(?:Text|PromptText|Title|HeaderText|ContentText|AccessibleText|AccessibleHelp"
            + "|AccessibleRoleDescription)|ControlNames\\.tooltip|Icons\\.name)\\(");

    /**
     * Calls whose literal argument is not shown: a catalog lookup takes a key ({@code tr},
     * {@code PickerKeys.hint}), a property lookup takes a name, and the rest compare or cut text.
     */
    private static final Set<String> NOT_SHOWN = Set.of(
            "tr",
            "hint",
            "getProperty",
            "equals",
            "equalsIgnoreCase",
            "startsWith",
            "endsWith",
            "contains",
            "indexOf",
            "matches",
            "replace",
            "replaceAll",
            "replaceFirst",
            "split");

    /** Literals that reach a control and are not words of a language, each with the reason. */
    private static final Map<String, String> NOT_LANGUAGE = Map.ofEntries(
            Map.entry("Aa", "the match-case glyph of every find bar"),
            Map.entry("AB", "the preserve-case glyph of the find bar"),
            Map.entry("ripgrep", "the name of a program"),
            Map.entry("gdb", "the name of a program, as a prompt in its path field"),
            Map.entry("git", "the name of a program, as a prompt in its path field"),
            Map.entry("gh", "the name of a program, as a prompt in its path field"),
            Map.entry("mmdc", "the name of a program, as a prompt in its path field"),
            Map.entry("dot", "the name of a program, as a prompt in its path field"),
            Map.entry("plantuml", "the name of a program, as a prompt in its path field"),
            Map.entry("typst", "the name of a program, as a prompt in its path field"),
            Map.entry("node", "the name of a program, as a prompt in its path field"),
            Map.entry("gpg", "the name of a program, as a prompt in its path field"),
            Map.entry("docker", "the name of a program, as a prompt in its path field"),
            Map.entry("claude-opus-4-8", "a model id, as a prompt"),
            Map.entry("claude-haiku-4-5", "a model id, as a prompt"),
            Map.entry("com.example", "a sample Maven group id"),
            Map.entry("http://www.example.com", "a sample address, as a prompt"),
            Map.entry("https://github.com/user/repo.git", "a sample address, as a prompt"),
            Map.entry("sftp://", "a URL scheme"),
            Map.entry("iiiiiiiiii", "measured, never shown: tells a fixed-width font from a proportional one"),
            Map.entry("WWWWWWWWWW", "measured, never shown: tells a fixed-width font from a proportional one"),
            Map.entry("UTF-8", "the name of an encoding"),
            Map.entry(" ms", "a unit"),
            Map.entry("ARG ", "a Dockerfile keyword"),
            Map.entry("Editora", "the app's name"),
            Map.entry("untitled", "the name of a buffer with no file, the same in every language as a file name is"));

    private static final Pattern TWO_LETTERS = Pattern.compile("\\p{L}{2}");

    /** A character written by its code, as the arrow of a disclosure toggle is. */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u[0-9A-Fa-f]{4}");

    @Test
    void noControlTakesItsTextFromALiteral() throws IOException {
        Set<String> problems = new TreeSet<>();
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("import javafx.scene")) {
                    continue; // no control is built here
                }
                String code = withoutComments(source);
                Matcher position = TEXT_POSITION.matcher(code);
                while (position.find()) {
                    for (Literal literal : shownLiterals(code, position.end())) {
                        if (!TWO_LETTERS
                                .matcher(UNICODE_ESCAPE.matcher(literal.text()).replaceAll(""))
                                .find()) {
                            continue; // a symbol, a separator, a unit of one letter
                        }
                        if (NOT_LANGUAGE.containsKey(literal.text())) {
                            used.add(literal.text());
                            continue;
                        }
                        long line = code.substring(0, literal.at())
                                        .chars()
                                        .filter(c -> c == '\n')
                                        .count()
                                + 1;
                        problems.add(file + ":" + line + "  \"" + literal.text() + "\"");
                    }
                }
            }
        }
        assertTrue(
                problems.isEmpty(),
                "take the text from the catalog with tr(\"key\"), or list it in NOT_LANGUAGE with the reason:\n"
                        + String.join("\n", problems));
        assertEquals(NOT_LANGUAGE.keySet(), used, "NOT_LANGUAGE lists a literal no control takes any more");
    }

    private record Literal(String text, int at) {}

    /**
     * The string literals among the arguments that open at {@code from}, leaving out those handed to one of
     * {@link #NOT_SHOWN}, at any depth: the key in {@code tr(on ? "a.key" : "b.key")} is not shown.
     */
    private static List<Literal> shownLiterals(String code, int from) {
        List<Literal> found = new java.util.ArrayList<>();
        Deque<String> calls = new ArrayDeque<>();
        int depth = 1;
        int i = from;
        while (i < code.length() && depth > 0) {
            char c = code.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < code.length() && code.charAt(end) != '"') {
                    end += code.charAt(end) == '\\' ? 2 : 1;
                }
                if (calls.stream().noneMatch(NOT_SHOWN::contains)) {
                    found.add(new Literal(code.substring(i + 1, Math.min(end, code.length())), i));
                }
                i = end + 1;
                continue;
            }
            if (c == '(') {
                int start = i;
                while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
                    start--;
                }
                calls.push(code.substring(start, i));
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth > 0) {
                    calls.pop();
                }
            }
            i++;
        }
        return found;
    }

    /**
     * The source with comments blanked and text blocks and character literals emptied, line breaks kept, so
     * that a quote in a comment does not open a literal and a line number still points at the source.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append("\"\"");
                appendLineBreaks(out, source, i, end);
                i = end;
            } else if (c == '"') {
                int end = i + 1;
                while (end < n && source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, n);
                out.append(source, i, end);
                i = end;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < n && source.charAt(end) != '\'') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                out.append("' '");
                i = Math.min(end + 1, n);
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                appendLineBreaks(out, source, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendLineBreaks(StringBuilder out, String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\n') {
                out.append('\n');
            }
        }
    }
}
