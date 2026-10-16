package com.editora.editor;

import com.editora.editorconfig.EditorConfigCharset;
import com.editora.editorconfig.EditorConfigProperties;

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
    public static String endOfLine(String language, String label) {
        return "shell".equals(language) && (LineEndings.CRLF.equals(label) || LineEndings.CR.equals(label))
                ? null
                : label;
    }

    /** The EditorConfig {@code charset} a buffer honours: never {@code utf-8-bom} for a shell script. */
    public static String charset(String language, String charset) {
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
}
