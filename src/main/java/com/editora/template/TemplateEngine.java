package com.editora.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.SnippetParser;

/**
 * Pure template rendering, layered on {@link SnippetParser}: variable discovery (which named variables a
 * template body references and so must be asked of the user), substitution (reusing the snippet parser,
 * with {@code ${cursor}} rewritten to {@code $0} = the final caret), file-name expansion, and
 * target-path resolution with a traversal guard. No toolkit — unit-tested.
 */
public final class TemplateEngine {

    /** Matches a {@code ${name}} or {@code ${name:default}} reference (name starts with a letter/_). */
    private static final Pattern VAR =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private TemplateEngine() {
    }

    /** A named variable a template references that is not a built-in: its {@code name} and default value. */
    public record TemplateVar(String name, String defaultValue) {
    }

    /**
     * The distinct, ordered named variables across {@code texts} (body + fileName + paths) that are
     * <em>not</em> built-in (so the wizard must prompt for them). The first occurrence's {@code :default}
     * is kept as the pre-fill ({@code ""} when none).
     */
    public static List<TemplateVar> discoverVariables(String... texts) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = VAR.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                if (TemplateVariableResolver.isBuiltIn(name)) {
                    continue;
                }
                seen.putIfAbsent(name, m.group(2) == null ? "" : m.group(2));
            }
        }
        List<TemplateVar> out = new ArrayList<>();
        seen.forEach((name, def) -> out.add(new TemplateVar(name, def)));
        return out;
    }

    /**
     * Substitutes {@code body} into a {@link ParsedSnippet}: {@code ${cursor}} becomes {@code $0} (the
     * final caret), {@code ${var}}/{@code ${var:default}} are resolved by {@code vars}, and any numeric
     * {@code $1…} stays a navigable tab stop.
     */
    public static ParsedSnippet substitute(String body, SnippetParser.Variables vars) {
        String prepared = (body == null ? "" : body).replace("${cursor}", "$0");
        return SnippetParser.parse(prepared, vars);
    }

    /** Expands a single-line pattern (file name) to plain text — no tab stops, {@code ${cursor}} dropped. */
    public static String expand(String pattern, SnippetParser.Variables vars) {
        return SnippetParser.parse((pattern == null ? "" : pattern).replace("${cursor}", ""), vars).text();
    }

    /**
     * Resolves a multi-file template's {@code pathPattern} against {@code dir}, or {@code null} if the
     * result escapes {@code dir} (a {@code ../} traversal guard). The path may itself contain variables.
     */
    public static Path resolveTargetPath(Path dir, String pathPattern, SnippetParser.Variables vars) {
        if (dir == null) {
            return null;
        }
        String rel = expand(pathPattern, vars).trim();
        if (rel.isEmpty()) {
            return null;
        }
        Path base = dir.toAbsolutePath().normalize();
        Path resolved = base.resolve(rel).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }
}
