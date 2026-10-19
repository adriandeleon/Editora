package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rerunning chosen node:test tests by name. node:test matches {@code --test-name-pattern} against a test's
 * name with its suites' names before it, space-separated ({@code ^group inner bad$} runs {@code inner bad} in
 * {@code describe('group')}), so a Test Results row ({@code group › inner bad}, an occurrence suffix
 * {@code (2)} on a repeat) maps back to one exact pattern. Pure.
 */
public final class NodeTestNames {

    private NodeTestNames() {}

    private static final Pattern OCCURRENCE = Pattern.compile(" \\(\\d+\\)$");

    /** The exact {@code --test-name-pattern} value that runs the test shown as {@code rowName}. */
    public static String pattern(String rowName) {
        String name = OCCURRENCE.matcher(rowName == null ? "" : rowName).replaceAll("");
        name = name.replace(TapParser.PARENT_SEPARATOR, " ");
        return "^" + quote(name) + "$";
    }

    /** The node flags that run only {@code rowNames} (empty = every test). */
    public static List<String> nodeFlags(List<String> rowNames) {
        List<String> out = new ArrayList<>();
        for (String n : rowNames) {
            out.add("--test-name-pattern=" + pattern(n));
        }
        return out;
    }

    /**
     * The package-manager task args that rerun {@code rowNames} through the same script: {@code run test} plus the
     * name patterns, after {@code --} for npm (which otherwise reads them as its own flags); yarn, pnpm and bun
     * pass what follows the script name on as it is. Any earlier filter after the script is replaced.
     */
    public static List<String> npmTask(List<String> taskArgs, List<String> rowNames, String manager) {
        List<String> out = new ArrayList<>();
        for (String a : taskArgs) {
            if (a.equals("--")) {
                break;
            }
            if (!a.startsWith("--test-name-pattern")) {
                out.add(a);
            }
        }
        if (rowNames.isEmpty()) {
            return out;
        }
        if (isNpm(manager)) {
            out.add("--");
        }
        out.addAll(nodeFlags(rowNames));
        return out;
    }

    private static boolean isNpm(String manager) {
        String m = manager == null ? "npm" : manager.replace('\\', '/');
        m = m.substring(m.lastIndexOf('/') + 1).replaceFirst("\\.(cmd|exe|bat)$", "");
        return m.equals("npm");
    }

    /** {@code text} with the regular-expression characters escaped (node reads the pattern as a RegExp). */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\^$.|?*+()[]{}/".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
