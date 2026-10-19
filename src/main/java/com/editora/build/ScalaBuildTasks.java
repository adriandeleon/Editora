package com.editora.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the subprojects of an sbt build and the modules of a Mill build out of the tools' own listings, and
 * turns each into the tasks the actions popup offers for it. Pure.
 */
public final class ScalaBuildTasks {

    private ScalaBuildTasks() {}

    /**
     * A project line of {@code sbt projects}: {@code [info] 	 * core} (the current one) or {@code [info] 	   api}
     * — an {@code [info]} prefix (absent with {@code -no-colors -batch} on some versions), indentation, an
     * optional {@code *}, the id.
     */
    private static final Pattern SBT_PROJECT =
            Pattern.compile("^(?:\\[info\\])?\\s+(?:\\*\\s+)?([A-Za-z][A-Za-z0-9_\\-]*)\\s*$");

    /** ANSI colour escapes, which sbt writes even to a pipe when its colour detection guesses wrong. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    /**
     * The subproject ids of {@code sbt projects} output, in order. The listing follows an
     * {@code In file:/…} line; lines before it (sbt loading the build) are skipped.
     */
    public static List<String> sbtProjects(String output) {
        Set<String> out = new LinkedHashSet<>();
        boolean listing = false;
        for (String raw : lines(output)) {
            String line = ANSI.matcher(raw).replaceAll("");
            if (line.contains("In file:") || line.contains("In build ")) {
                listing = true;
                continue;
            }
            if (!listing) {
                continue;
            }
            Matcher m = SBT_PROJECT.matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /**
     * A line of {@code mill resolve __.compile}: a module's {@code compile} task ({@code app.compile},
     * {@code app.test.compile}) — what every code module has, unlike the root's own tasks ({@code clean},
     * {@code init}, …), which {@code resolve _} lists beside the modules.
     */
    private static final Pattern MILL_COMPILE = Pattern.compile("^([A-Za-z_`][A-Za-z0-9_`\\-.\\[\\],]*)\\.compile$");

    /** The modules {@code mill resolve __.compile} names (one task per line on standard output), in order. */
    public static List<String> millModules(String output) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : lines(output)) {
            Matcher m = MILL_COMPILE.matcher(ANSI.matcher(raw).replaceAll("").strip());
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Each sbt subproject's {@code compile}, {@code test} and {@code run}: {@code core/compile}. */
    public static List<String> sbtTasks(List<String> projects) {
        List<String> out = new ArrayList<>();
        for (String p : projects) {
            for (String task : List.of("compile", "test", "run")) {
                out.add(p + "/" + task);
            }
        }
        return out;
    }

    /**
     * Each Mill module's {@code compile} and {@code run} ({@code core.compile}); a test module ({@code core.test})
     * is offered as itself, which runs its tests.
     */
    public static List<String> millTasks(List<String> modules) {
        List<String> out = new ArrayList<>();
        for (String m : modules) {
            String last = m.substring(m.lastIndexOf('.') + 1);
            if (last.equals("test") || last.equals("tests")) {
                out.add(m);
            } else {
                out.add(m + ".compile");
                out.add(m + ".run");
            }
        }
        return out;
    }

    private static String[] lines(String output) {
        return output == null ? new String[0] : output.split("\\R");
    }
}
