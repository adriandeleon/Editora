package com.editora.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names a shell script defines and uses — its functions and variables — offered after the snippets when no Bash
 * server is running (Bash review C9). LSP is off by default, and a shell buffer's completion was the 13 snippets
 * only: {@code $GR} had nothing, though {@code GREETING} is set three lines up. Pure.
 */
public final class ShellBufferNames {

    /** Above this much text the buffer is not scanned on every keystroke (a generated or vendored script). */
    public static final int MAX_SCAN_CHARS = 512 * 1024;

    /** {@code name() {} or {@code function name}: a function's name (bash allows {@code - . :} in one). */
    private static final Pattern FUNCTION = Pattern.compile(
            "(?m)^[ \\t]*(?:function[ \\t]+([A-Za-z_][\\w.:-]*)|([A-Za-z_][\\w.:-]*)[ \\t]*\\([ \\t]*\\))");

    /** An assignment ({@code name=}, {@code name+=}, {@code name[i]=}) at a command's start, after a declaration word. */
    private static final Pattern ASSIGNED = Pattern.compile(
            "(?:^|[\\s;&|(])(?:(?:local|declare|typeset|export|readonly)(?:[ \\t]+-\\w+)*[ \\t]+)?([A-Za-z_]\\w*)(?:\\[[^\\]]*\\])?\\+?=");

    /** {@code for name in}, and {@code local name} (or another declaration word) without a value. */
    private static final Pattern BOUND = Pattern.compile(
            "(?:^|[\\s;&|(])(?:for[ \\t]+([A-Za-z_]\\w*)|(?:local|declare|typeset|export|readonly)(?:[ \\t]+-\\w+)*[ \\t]+([A-Za-z_]\\w*)[ \\t]*(?:$|[;\\n]))",
            Pattern.MULTILINE);

    /** A use: {@code $name} or <code>${name</code>. */
    private static final Pattern USED = Pattern.compile("\\$\\{?([A-Za-z_]\\w*)");

    private ShellBufferNames() {}

    /**
     * The completions for {@code prefix} from {@code text}, where the word being typed starts at {@code wordStart}:
     * variables only when the word follows {@code $} or <code>${</code>, else functions then variables. The word
     * being typed is not offered for itself; names are matched by prefix ignoring case, exact case first.
     */
    public static List<Completion> complete(String text, int wordStart, String prefix, int max) {
        if (text == null || prefix == null || prefix.isEmpty() || max <= 0 || text.length() > MAX_SCAN_CHARS) {
            return List.of();
        }
        boolean variablesOnly = wordStart > 0
                && (text.charAt(wordStart - 1) == '$'
                        || (text.charAt(wordStart - 1) == '{' && wordStart > 1 && text.charAt(wordStart - 2) == '$'));
        String typed = wordAt(text, wordStart);
        Set<String> functions = new TreeSet<>();
        Set<String> variables = new TreeSet<>();
        if (!variablesOnly) {
            collect(FUNCTION.matcher(text), functions, 1, 2);
        }
        collect(ASSIGNED.matcher(text), variables, 1, 1);
        collect(BOUND.matcher(text), variables, 1, 2);
        collect(USED.matcher(text), variables, 1, 1);
        variables.removeAll(functions);
        List<Completion> out = new ArrayList<>();
        add(out, functions, prefix, typed, "completion-function", max);
        add(out, variables, prefix, typed, "completion-variable", max);
        return out;
    }

    private static void collect(Matcher m, Set<String> into, int group, int otherGroup) {
        while (m.find()) {
            String name = m.group(group) != null ? m.group(group) : m.group(otherGroup);
            if (name != null) {
                into.add(name);
            }
        }
    }

    /** The identifier that starts at {@code from} (what is being typed, to leave out). */
    private static String wordAt(String text, int from) {
        int end = from;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(Math.max(0, Math.min(from, end)), end);
    }

    private static void add(
            List<Completion> out, Set<String> names, String prefix, String typed, String kind, int max) {
        List<String> exact = new ArrayList<>();
        List<String> folded = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.equals(typed) || name.length() <= prefix.length() && name.equalsIgnoreCase(prefix)) {
                continue; // what is being typed, or a name typed out in full
            }
            if (name.startsWith(prefix)) {
                exact.add(name);
            } else if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                folded.add(name);
            }
        }
        exact.addAll(folded);
        boolean function = kind.equals("completion-function");
        for (String name : exact) {
            if (out.size() >= max) {
                return;
            }
            out.add(Completion.lsp(
                    name,
                    name,
                    null,
                    null,
                    function ? CompletionIconKind.FUNCTION : CompletionIconKind.VARIABLE,
                    null,
                    false,
                    false,
                    null));
        }
    }
}
