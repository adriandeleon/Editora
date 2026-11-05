package com.editora.format;

/**
 * What an external formatter printed, made safe to put into a buffer. Pure.
 *
 * <p>The editor holds text with {@code \n} line breaks and writes the file's own line ending back at save. A tool
 * configured for CRLF (Ruff's {@code line-ending = "cr-lf"}, or {@code "native"} on Windows; Prettier's
 * {@code endOfLine: "crlf"}; ESLint's {@code linebreak-style: windows}) prints {@code \r\n}. Diffed as it came, the
 * changed span ended between a {@code \r} and its {@code \n}, and the lone {@code \r} became an extra line break
 * (Python review 2, A103).
 */
public final class FormatterOutput {

    private FormatterOutput() {}

    /** {@code text} with every {@code \r\n} and lone {@code \r} turned into {@code \n}. */
    public static String lf(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
