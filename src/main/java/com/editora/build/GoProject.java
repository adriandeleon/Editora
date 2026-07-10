package com.editora.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code go.mod} — just the module path from its {@code module <path>} line (shown as the Settings
 * "Found: …" label; {@code null} when absent, e.g. a {@code go.work} workspace with no root module). Pure — a
 * single regex, no full go.mod parse (Go's task list is static, so the module line is display-only).
 */
public record GoProject(String moduleName) {

    private static final Pattern MODULE = Pattern.compile("(?m)^\\s*module\\s+(\\S+)");

    /** The module path from {@code go.mod} text, or {@code null} when there's no {@code module} line. */
    public static GoProject parse(String goModText) {
        if (goModText == null || goModText.isBlank()) {
            return new GoProject(null);
        }
        Matcher m = MODULE.matcher(goModText);
        return new GoProject(m.find() ? m.group(1) : null);
    }
}
