package com.editora.text;

import com.editora.editops.LineEndings;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.editorconfig.EditorConfigProperties;
import org.jspecify.annotations.Nullable;

/**
 * Shell scripts and the bytes the shell cannot read: a CRLF line ending ({@code $'\r': command not found},
 * {@code set: pipefail\r: invalid option name}) and a UTF-8 byte-order mark, which hides the {@code #!} line and
 * becomes part of the first command. Pure decisions for the open notice, the EditorConfig rules a shell buffer
 * honours, and the line endings a new shell file is written with.
 */
public final class ShellScripts {

    private ShellScripts() {}

    /** What is wrong with a shell buffer's bytes, for the open notice. */
    public enum Problem {
        NONE,
        CRLF,
        BOM,
        CRLF_AND_BOM
    }

    /**
     * The problem a buffer of {@code language} has when its file uses {@code lineEnding} ({@code LF}/{@code CRLF}
     * /{@code CR}) and was decoded as {@code charset}. Only shell scripts: other languages read CRLF and a BOM.
     */
    public static Problem problem(String language, String lineEnding, String charset) {
        if (!"shell".equals(language)) {
            return Problem.NONE;
        }
        boolean crlf = LineEndings.CRLF.equals(lineEnding) || LineEndings.CR.equals(lineEnding);
        boolean bom = EditorConfigCharset.UTF_8_BOM.equals(charset);
        return crlf && bom ? Problem.CRLF_AND_BOM : crlf ? Problem.CRLF : bom ? Problem.BOM : Problem.NONE;
    }

    /**
     * The EditorConfig {@code end_of_line} label a buffer honours: a shell script keeps LF whatever a broad
     * {@code [*] end_of_line = crlf} says, or every save would make it unrunnable.
     */
    public static @Nullable String endOfLine(String language, @Nullable String label) {
        return "shell".equals(language) && (LineEndings.CRLF.equals(label) || LineEndings.CR.equals(label))
                ? null
                : label;
    }

    /** The EditorConfig {@code charset} a buffer honours: never {@code utf-8-bom} for a shell script. */
    public static @Nullable String charset(String language, @Nullable String charset) {
        return "shell".equals(language) && EditorConfigCharset.UTF_8_BOM.equals(charset) ? null : charset;
    }

    /**
     * The EditorConfig rules a buffer of {@code language} honours: for a shell script, {@code end_of_line = crlf}
     * / {@code cr} and {@code charset = utf-8-bom} are dropped (it keeps LF and gets no BOM); else {@code rules}.
     */
    public static EditorConfigProperties honoured(String language, EditorConfigProperties rules) {
        if (rules == null || !"shell".equals(language)) {
            return rules;
        }
        String eol = endOfLine(language, LineEndings.labelOf(rules.endOfLine())) == null ? null : rules.endOfLine();
        String charset = charset(language, rules.charset());
        if (java.util.Objects.equals(eol, rules.endOfLine()) && java.util.Objects.equals(charset, rules.charset())) {
            return rules;
        }
        return new EditorConfigProperties(
                rules.insertSpaces(),
                rules.indentSize(),
                rules.tabWidth(),
                eol,
                charset,
                rules.trimTrailingWhitespace(),
                rules.insertFinalNewline(),
                rules.maxLineLength());
    }

    /** Whether a new file called {@code fileName} holding {@code text} is a shell script: its name or its #!. */
    public static boolean isShellScript(String fileName, String text) {
        if ("shell".equals(LanguageRegistry.forFileName(fileName == null ? "" : fileName))) {
            return true;
        }
        if (text == null || !text.startsWith("#!")) {
            return false;
        }
        int nl = text.indexOf('\n');
        return "sh".equals(Shebang.extension(nl < 0 ? text : text.substring(0, nl)));
    }

    /** A {@code # shellcheck shell=zsh} directive (ShellCheck's own way to name the dialect). */
    private static final java.util.regex.Pattern SHELLCHECK_ZSH =
            java.util.regex.Pattern.compile("^\\s*#\\s*shellcheck\\s+(?:.*\\s)?shell=zsh\\b");

    /** How far {@link #isZshDialect} looks for a directive: the comments at the top of a script. */
    private static final int DIRECTIVE_LINES = 20;

    /**
     * Whether a shell buffer named {@code fileName} holding {@code text} is zsh, which ShellCheck cannot check: a
     * {@code .zsh} name, a zsh shebang ({@code #!/bin/zsh}, {@code #!/usr/bin/env zsh}), or a {@code # shellcheck
     * shell=zsh} directive among its first lines. Bash review C14/D2: the problem counter says such a file is not
     * checked instead of showing a check over a script nothing linted.
     */
    public static boolean isZshDialect(@Nullable String fileName, @Nullable String text) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".zsh")) {
            return true;
        }
        if (text == null || text.isEmpty()) {
            return false;
        }
        int from = 0;
        for (int line = 0; line < DIRECTIVE_LINES && from <= text.length(); line++) {
            int nl = text.indexOf('\n', from);
            String l = text.substring(from, nl < 0 ? text.length() : nl);
            if (line == 0 && l.startsWith("#!")) {
                String[] tokens = l.substring(2).strip().split("\\s+");
                int k = 0;
                if (k < tokens.length && baseName(tokens[k]).equals("env")) {
                    k++;
                    while (k < tokens.length && (tokens[k].startsWith("-") || tokens[k].contains("="))) {
                        k++;
                    }
                }
                if (k < tokens.length && baseName(tokens[k]).equals("zsh")) {
                    return true;
                }
            } else if (SHELLCHECK_ZSH.matcher(l).find()) {
                return true;
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return false;
    }

    /** POSIX shells without bash's {@code [[ ]]}: a script run by one of them needs {@code [ ]}. */
    private static final java.util.Set<String> POSIX_SHELLS = java.util.Set.of("sh", "dash", "ash", "posh");

    /**
     * Whether {@code firstLine} is a shebang naming a POSIX shell that has no {@code [[ ]]} ({@code #!/bin/sh},
     * {@code #!/usr/bin/env dash}, {@code #!/bin/ash}); false for bash, zsh, ksh and no shebang at all.
     */
    public static boolean isPosixShebang(String firstLine) {
        if (firstLine == null) {
            return false;
        }
        String line = firstLine.strip();
        if (!line.startsWith("#!")) {
            return false;
        }
        String[] tokens = line.substring(2).strip().split("\\s+");
        int k = 0;
        if (k < tokens.length && baseName(tokens[k]).equals("env")) {
            k++;
            while (k < tokens.length && (tokens[k].startsWith("-") || tokens[k].contains("="))) {
                k++;
            }
        }
        return k < tokens.length && POSIX_SHELLS.contains(baseName(tokens[k]));
    }

    /**
     * A shell snippet body for a buffer whose first line is {@code firstLine}: under a POSIX {@code sh}/{@code dash}
     * shebang, bash's {@code [[ … ]]} tests become {@code [ … ]} (dash says {@code [[: not found}).
     */
    public static String snippetBody(String body, String firstLine) {
        if (body == null || !isPosixShebang(firstLine)) {
            return body;
        }
        return body.replace("[[ ", "[ ").replace(" ]]", " ]");
    }

    private static String baseName(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return (slash >= 0 ? token.substring(slash + 1) : token).toLowerCase(java.util.Locale.ROOT);
    }
}
