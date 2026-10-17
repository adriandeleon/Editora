package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

/**
 * The java-debug settings Editora sends before each Java session ({@code vscode.java.updateDebugSettings}, which
 * java-debug reads as its {@code DebugSettings}; a launch without its own {@code stepFilters} falls back to these,
 * and an attach has no other way to get them). Without this call java-debug ran on its defaults: Step Into went
 * into JDK internals ({@code String.length()}, {@code String.coder()}) with no source to show, and static fields
 * appeared nowhere in Variables.
 *
 * @param stepFilters skip JDK classes and synthetic methods when stepping (constructors and static initializers
 *     are not skipped: they are the user's code)
 * @param showStatics show a class's static fields among the variables
 * @param exceptionTypes exception classes the exception breakpoints are limited to (empty: every exception)
 */
public record JavaDebugSettings(boolean stepFilters, boolean showStatics, List<String> exceptionTypes) {

    /** The jdtls command java-debug registers for these settings. */
    public static final String COMMAND = "vscode.java.updateDebugSettings";

    /**
     * The JDK's packages, as JDI class filters. java-debug does not expand VS Code's {@code $JDK} itself (the
     * extension does, client side), so the list is spelled out: what IntelliJ skips by default, plus {@code jdk.*}.
     */
    static final List<String> JDK_CLASSES = List.of(
            "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "org.w3c.*", "org.xml.*", "org.ietf.*", "org.omg.*");

    public JavaDebugSettings {
        exceptionTypes = exceptionTypes == null ? List.of() : List.copyOf(exceptionTypes);
    }

    /** The defaults: step filters on, statics shown, every exception. */
    public static JavaDebugSettings defaults() {
        return new JavaDebugSettings(true, true, List.of());
    }

    /** Pure: the exception classes in {@code text} — separated by commas, spaces or semicolons — in order. */
    public static List<String> parseExceptionTypes(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split("[,;\\s]+")) {
            String type = part.strip();
            if (!type.isEmpty() && !out.contains(type)) {
                out.add(type);
            }
        }
        return out;
    }

    /** The JSON string the command takes: the fields of java-debug's {@code DebugSettings} that Editora sets. */
    public String toJson() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("skipClasses", stepFilters ? JDK_CLASSES : List.of());
        filters.put("skipSynthetics", stepFilters);
        filters.put("skipStaticInitializers", false);
        filters.put("skipConstructors", false);
        Map<String, Object> exceptions = new LinkedHashMap<>();
        exceptions.put("exceptionTypes", exceptionTypes);
        Map<String, Object> settings = new LinkedHashMap<>();
        // java-debug sets its log level from every update and fails the command (after applying the rest) when the
        // level is missing: Level.parse(null).
        settings.put("logLevel", "WARNING");
        settings.put("showStaticVariables", showStatics);
        settings.put("stepFilters", filters);
        settings.put("exceptionFilters", exceptions);
        // Re-applies the exception breakpoints of a live session with the new types.
        settings.put("exceptionFiltersUpdated", true);
        return new Gson().toJson(settings);
    }
}
