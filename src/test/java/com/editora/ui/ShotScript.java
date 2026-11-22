package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A screenshot command script, as {@link Shots} runs it: one command per line, {@code op argument…}; a blank line
 * or a line starting with {@code #} is skipped ({@code #@} lines are the runner's fixture directives, see
 * {@code scripts/screenshots/Shoot.java}). {@code ${name}} in a line is a value the runner supplies, such as the
 * workspace folder or the output folder; {@code ${env:NAME}} is an environment variable.
 *
 * <p>Parsing checks every line before anything runs, so a typo fails at once with its line number instead of
 * as a missing screenshot after a minute of driving the window.
 */
final class ShotScript {

    /** One command: the 1-based line it came from, its op and the rest of the line (variables expanded). */
    record Step(int line, String op, String arg) {}

    /** The values a script may name with {@code ${…}}, besides {@code env:NAME}. */
    static final Set<String> VARIABLES =
            Set.of("workspace", "out", "suffix", "config", "home", "jdk", "fixtures", "javaDebugJar", "plugins");

    /** Every op and how many whitespace-separated words its argument needs at least. */
    static final Map<String, Integer> OPS = Map.ofEntries(
            Map.entry("set", 2),
            Map.entry("size", 2),
            Map.entry("start", 0),
            Map.entry("mode", 1),
            Map.entry("project", 1),
            Map.entry("open", 1),
            Map.entry("run", 1),
            Map.entry("runfast", 1),
            Map.entry("wait", 1),
            Map.entry("waitfor", 1),
            Map.entry("type", 0),
            Map.entry("key", 1),
            Map.entry("click", 1),
            Map.entry("clicksel", 1),
            Map.entry("clickat", 2),
            Map.entry("focuseditor", 0),
            Map.entry("focuslast", 0),
            Map.entry("focusprompt", 1),
            Map.entry("focussel", 1),
            Map.entry("keymain", 0),
            Map.entry("menu", 1),
            Map.entry("menuitem", 1),
            Map.entry("popitem", 1),
            Map.entry("ctxbp", 0),
            Map.entry("ctxrow", 1),
            Map.entry("choose", 3),
            Map.entry("combotext", 1),
            Map.entry("fieldtext", 1),
            Map.entry("settext", 1),
            Map.entry("retext", 1),
            Map.entry("settings", 1),
            Map.entry("winsize", 2),
            Map.entry("winpos", 2),
            Map.entry("closewins", 0),
            Map.entry("divider", 2),
            Map.entry("dividers", 0),
            Map.entry("windows", 0),
            Map.entry("windump", 1),
            Map.entry("dump", 1),
            Map.entry("installdebuglog", 0),
            Map.entry("maplinks", 3),
            Map.entry("maphover", 1),
            Map.entry("shot", 1),
            Map.entry("winshot", 1),
            Map.entry("winshotp", 1));

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");

    private ShotScript() {}

    /**
     * The steps of {@code lines}, with {@code ${name}} replaced from {@code values}. With {@code values} null the
     * names are only checked (a variable the runner does not know fails), and left as they are.
     *
     * @throws IllegalArgumentException naming the first bad line: an unknown op, too few arguments, a bad number
     *     or key, or an unknown variable
     */
    static List<Step> parse(List<String> lines, Map<String, String> values) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int n = i + 1;
            line = expand(line, values, n);
            String[] parts = line.split("\\s+", 2);
            String op = parts[0];
            String arg = parts.length > 1 ? parts[1] : "";
            Integer words = OPS.get(op);
            if (words == null) {
                throw new IllegalArgumentException("line " + n + ": unknown op '" + op + "'");
            }
            String[] args = arg.isBlank() ? new String[0] : arg.split("\\s+");
            if (args.length < words) {
                throw new IllegalArgumentException(
                        "line " + n + ": '" + op + "' takes at least " + words + " argument(s): " + line);
            }
            check(op, args, n, line);
            steps.add(new Step(n, op, arg));
        }
        return steps;
    }

    private static String expand(String line, Map<String, String> values, int n) {
        Matcher m = VARIABLE.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (name.startsWith("env:") && name.length() > 4) {
                value = values == null ? m.group() : System.getenv().getOrDefault(name.substring(4), "");
            } else if (VARIABLES.contains(name)) {
                value = values == null ? m.group() : values.getOrDefault(name, "");
            } else {
                throw new IllegalArgumentException("line " + n + ": unknown variable ${" + name + "}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The argument checks that can be made before the window exists: numbers and key names. */
    private static void check(String op, String[] a, int n, String line) {
        try {
            switch (op) {
                case "size", "winsize", "winpos" -> {
                    Double.parseDouble(a[0]);
                    Double.parseDouble(a[1]);
                }
                case "wait" -> Integer.parseInt(a[0]);
                case "divider" -> {
                    Integer.parseInt(a[0]);
                    Double.parseDouble(a[1]);
                }
                case "key" -> {
                    javafx.scene.input.KeyCode.valueOf(a[0]);
                    if (a.length > 2) {
                        Integer.parseInt(a[2]);
                    }
                }
                case "clickat" -> {
                    Double.parseDouble(a[0]);
                    Double.parseDouble(a[1]);
                }
                case "choose" -> {
                    Integer.parseInt(a[1]);
                    Integer.parseInt(a[2]);
                }
                case "maplinks" -> {
                    MapCardLinks.Kind.valueOf(a[0]);
                    Integer.parseInt(a[1]);
                    Integer.parseInt(a[2]);
                }
                case "shot" -> {
                    if (a.length != 1 && a.length != 5) {
                        throw new IllegalArgumentException("shot takes a file, or a file and x y w h");
                    }
                    for (int i = 1; i < a.length; i++) {
                        Double.parseDouble(a[i]);
                    }
                }
                case "windump", "dump" -> Integer.parseInt(a[0]);
                default -> {
                    // no argument check
                }
            }
        } catch (IllegalArgumentException e) { // NumberFormatException is one
            throw new IllegalArgumentException("line " + n + ": " + e.getMessage() + ": " + line, e);
        }
    }
}
