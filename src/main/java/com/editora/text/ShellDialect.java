package com.editora.text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Which shell a shell-language buffer is written for, as far as its name and first lines say. Pure.
 *
 * <p>Editora gives every sh-family file the one {@code shell} language; bash-language-server and ShellCheck pick
 * the dialect themselves. ShellCheck has no zsh mode, so a zsh script on the Bash server is never checked (Bash
 * review D2, C14): the problem counter must not call it clean.
 */
public final class ShellDialect {

    private ShellDialect() {}

    /** How many leading lines are read for a shebang and a {@code # shellcheck shell=} directive. */
    private static final int HEAD_LINES = 20;

    /** ShellCheck's file-wide dialect directive: {@code # shellcheck shell=bash} (with other directives too). */
    private static final Pattern SHELL_DIRECTIVE =
            Pattern.compile("^\\s*#\\s*shellcheck\\s+(?:.*\\s)?shell=([A-Za-z0-9_-]+)");

    /**
     * Whether a file named {@code fileName} (a name or a path; null when unsaved) holding {@code text} (its
     * start is enough) is a zsh script: a {@code .zsh} name or a zsh startup file, a zsh shebang
     * ({@code #!/bin/zsh}, {@code #!/usr/bin/env zsh}), or a {@code # shellcheck shell=zsh} directive. A
     * directive naming another shell wins over the name.
     */
    public static boolean isZsh(@Nullable String fileName, @Nullable String text) {
        String head = text == null ? "" : text;
        String directive = directiveShell(head);
        if (directive != null) {
            return directive.equals("zsh");
        }
        String first = firstLine(head);
        if (first.startsWith("#!")) {
            return "zsh".equals(shebangShell(first));
        }
        if (fileName == null) {
            return false;
        }
        String norm = fileName.replace('\\', '/');
        String lower = norm.substring(norm.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return lower.endsWith(".zsh") || ShellFiles.isZshStartupFile(lower);
    }

    /**
     * The shell a {@code # shellcheck shell=…} directive in the first lines of {@code text} names (lower case),
     * or null when there is none. Pure.
     */
    public static @Nullable String directiveShell(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int lines = 0;
        for (String line : (Iterable<String>) text.lines()::iterator) {
            if (++lines > HEAD_LINES) {
                break;
            }
            Matcher m = SHELL_DIRECTIVE.matcher(line);
            if (m.find()) {
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The interpreter a shebang line names, past {@code env} and its options (lower case), or null. */
    static @Nullable String shebangShell(String firstLine) {
        String line = firstLine.strip();
        if (!line.startsWith("#!")) {
            return null;
        }
        String[] tokens = line.substring(2).strip().split("\\s+");
        int k = 0;
        if (k < tokens.length && baseName(tokens[k]).equals("env")) {
            k++;
            while (k < tokens.length && (tokens[k].startsWith("-") || tokens[k].contains("="))) {
                k++;
            }
        }
        return k < tokens.length && !tokens[k].isEmpty() ? baseName(tokens[k]) : null;
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).strip();
    }

    private static String baseName(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return (slash >= 0 ? token.substring(slash + 1) : token).toLowerCase(Locale.ROOT);
    }
}
