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
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    /**
     * Built-ins that are normally <em>derived from the target file name</em> ({@code fileName},
     * {@code baseName}, {@code extension}). When the <b>file-name pattern itself</b> references one of
     * them, there is nothing to derive from yet (a new-from-template file has no name), so it must be
     * prompted instead — see {@link #discoverVariablesForNewFile}.
     */
    private static final java.util.Set<String> FILE_IDENTITY = java.util.Set.of("fileName", "baseName", "extension");

    private TemplateEngine() {}

    /** A named variable a template references that is not a built-in: its {@code name} and default value. */
    public record TemplateVar(String name, String defaultValue) {}

    /**
     * The distinct, ordered named variables across {@code texts} (body + fileName + paths) that are
     * <em>not</em> built-in (so the wizard must prompt for them). The first occurrence's {@code :default}
     * is kept as the pre-fill ({@code ""} when none).
     */
    public static List<TemplateVar> discoverVariables(String... texts) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String text : texts) {
            collect(text, false, seen);
        }
        return toVars(seen);
    }

    /**
     * Like {@link #discoverVariables}, but for creating a <em>new file</em> from a single-file template:
     * a file-identity built-in ({@code fileName}/{@code baseName}/{@code extension}) referenced in the
     * {@code fileNamePattern} is treated as a prompted variable, because there is no source file to
     * derive it from yet. The same name used only in {@code otherTexts} (the body) is still auto-derived.
     * The prompted answer then feeds both the file-name expansion and the body (the resolver checks the
     * wizard answers before its built-ins), so {@code ${baseName:Main}.java} finally asks for the name.
     */
    public static List<TemplateVar> discoverVariablesForNewFile(String fileNamePattern, String... otherTexts) {
        Map<String, String> seen = new LinkedHashMap<>();
        collect(fileNamePattern, true, seen); // file-name pattern first, so its default (e.g. "Main") wins
        for (String text : otherTexts) {
            collect(text, false, seen);
        }
        return toVars(seen);
    }

    /** Collects non-built-in {@code ${name[:default]}} refs from {@code text}; when {@code allowFileIdentity}
     *  is set, the file-identity built-ins are also collected (they can't be derived in this position). */
    private static void collect(String text, boolean allowFileIdentity, Map<String, String> seen) {
        if (text == null) {
            return;
        }
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (TemplateVariableResolver.isBuiltIn(name) && !(allowFileIdentity && FILE_IDENTITY.contains(name))) {
                continue;
            }
            seen.putIfAbsent(name, m.group(2) == null ? "" : m.group(2));
        }
    }

    private static List<TemplateVar> toVars(Map<String, String> seen) {
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
        String out = SnippetParser.parse((pattern == null ? "" : pattern).replace("${cursor}", ""), vars)
                .text();
        return collapseDuplicateExtension(out);
    }

    /**
     * Collapses a doubled trailing extension in a file name / path — {@code foo.md.md} → {@code foo.md}.
     * A template pattern like {@code ${baseName:document}.md} appends {@code .md}; if the user typed a
     * {@code baseName} that already ends in {@code .md}, the naive substitution yields {@code x.md.md}.
     * Only collapses when the last two extensions are <em>identical</em> (so {@code types.d.ts},
     * {@code foo.tar.gz}, {@code foo.min.js} are untouched), and only on the final path segment (a dotted
     * directory name is left alone). Pure and unit-tested.
     */
    static String collapseDuplicateExtension(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String dir = slash >= 0 ? name.substring(0, slash + 1) : "";
        String seg = slash >= 0 ? name.substring(slash + 1) : name;
        int lastDot = seg.lastIndexOf('.');
        if (lastDot <= 0) {
            return name;
        }
        String ext = seg.substring(lastDot); // ".md"
        String rest = seg.substring(0, lastDot); // "foo.md"
        return rest.length() > ext.length() && rest.endsWith(ext) ? dir + rest : name;
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
