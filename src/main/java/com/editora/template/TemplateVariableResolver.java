package com.editora.template;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

import com.editora.snippet.SnippetParser;
import com.editora.snippet.VariableResolver;

/**
 * Resolves template variables for {@link TemplateEngine}: the wizard's named answers first, then the
 * template built-ins ({@code author}, {@code projectName}, {@code packageName}, {@code fileName},
 * {@code baseName}, {@code extension}, {@code date}, {@code year}, {@code time}), then the standard
 * snippet variables (TM_, CURRENT_, SELECTION, CLIPBOARD) via a wrapped {@link VariableResolver}, then
 * {@code null} (so {@code ${x:default}} falls back). Pure given a fixed clock, so it is unit-tested.
 *
 * <p>{@code ${cursor}} is <em>not</em> resolved here — {@link TemplateEngine} rewrites it to {@code $0}
 * (the final caret) before parsing. {@link #isBuiltIn(String)} is the single source of truth shared with
 * {@link TemplateEngine#discoverVariables} so the wizard never asks about a name we can resolve.
 */
public final class TemplateVariableResolver implements SnippetParser.Variables {

    /** Template-specific built-ins (plus {@code cursor}, handled as {@code $0} by the engine). */
    private static final Set<String> TEMPLATE_BUILTINS = Set.of(
            "cursor", "author", "projectName", "packageName", "fileName", "baseName", "extension",
            "date", "year", "time");

    /** The names {@link VariableResolver} resolves — also never asked of the user. */
    private static final Set<String> SNIPPET_BUILTINS = Set.of(
            "TM_FILENAME", "TM_FILENAME_BASE", "TM_DIRECTORY", "TM_FILEPATH", "TM_SELECTED_TEXT",
            "SELECTION", "CLIPBOARD", "TM_LINE_INDEX", "TM_LINE_NUMBER", "TM_CURRENT_LINE",
            "CURRENT_YEAR", "CURRENT_YEAR_SHORT", "CURRENT_MONTH", "CURRENT_MONTH_NAME", "CURRENT_DATE",
            "CURRENT_HOUR", "CURRENT_MINUTE", "CURRENT_SECOND");

    private final Map<String, String> answers;
    private final String author;
    private final String projectName;
    private final String packageName;
    private final String fileName;
    private final LocalDateTime now;
    private final VariableResolver delegate;

    public TemplateVariableResolver(Map<String, String> answers, String author, String projectName,
            String packageName, String fileName, String directory, String filePath, LocalDateTime now) {
        this.answers = answers == null ? Map.of() : answers;
        this.author = author == null ? "" : author;
        this.projectName = projectName == null ? "" : projectName;
        this.packageName = packageName == null ? "" : packageName;
        this.fileName = fileName == null ? "" : fileName;
        this.now = now == null ? LocalDateTime.now() : now;
        this.delegate = new VariableResolver(this.fileName, directory == null ? "" : directory,
                filePath == null ? "" : filePath, "", "", 0, "");
    }

    /** Whether {@code name} is auto-resolved (so the variable wizard must not prompt for it). */
    public static boolean isBuiltIn(String name) {
        return TEMPLATE_BUILTINS.contains(name) || SNIPPET_BUILTINS.contains(name);
    }

    @Override
    public String resolve(String name) {
        if (answers.containsKey(name)) {
            return answers.get(name);
        }
        return switch (name) {
            case "author" -> author;
            case "projectName" -> projectName;
            case "packageName" -> packageName;
            // When there is no driving file name (e.g. a multi-file template), return null so a
            // ${baseName:default} falls back to its default rather than an empty string.
            case "fileName" -> fileName.isEmpty() ? null : fileName;
            case "baseName" -> blankToNull(baseName(fileName));
            case "extension" -> blankToNull(extension(fileName));
            case "date" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "year" -> now.format(DateTimeFormatter.ofPattern("yyyy"));
            case "time" -> now.format(DateTimeFormatter.ofPattern("HH:mm"));
            case "cursor" -> null; // rewritten to $0 by TemplateEngine before parsing
            default -> delegate.resolve(name);
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String baseName(String file) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 && dot < file.length() - 1 ? file.substring(dot + 1) : "";
    }
}
