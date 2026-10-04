package com.editora.lsp;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

/**
 * Turns a {@code textDocument/hover} answer into the Markdown the hover popup renders. Pure.
 *
 * <p>The popup always parses Markdown, so the three shapes a server may send have to be normalised:
 *
 * <ul>
 *   <li>{@code MarkupContent} of kind {@code markdown} — used as is;</li>
 *   <li>{@code MarkupContent} of kind {@code plaintext} — escaped, so {@code __init__} is not read as
 *       strong emphasis ("init" in bold, a name that does not exist) and line breaks survive;</li>
 *   <li>the legacy {@code MarkedString} list — a bare string is Markdown by definition, a
 *       {@code {language, value}} pair is code and is fenced (jdtls sends its Java signature that way; pasted
 *       in as prose, {@code List<Integer>} lost its type argument to inline HTML).</li>
 * </ul>
 */
final class HoverMarkdown {

    private HoverMarkdown() {}

    static String of(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        var contents = hover.getContents();
        if (contents.isRight()) {
            MarkupContent mc = contents.getRight();
            if (mc == null || mc.getValue() == null) {
                return "";
            }
            return MarkupKind.PLAINTEXT.equals(mc.getKind()) ? escapePlainText(mc.getValue()) : mc.getValue();
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : contents.getLeft()) {
            if (entry == null) {
                continue;
            }
            if (entry.isLeft()) {
                sb.append(entry.getLeft() == null ? "" : entry.getLeft());
            } else {
                MarkedString ms = entry.getRight();
                if (ms != null && ms.getValue() != null) {
                    sb.append(ms.getLanguage() == null ? ms.getValue() : fenced(ms.getLanguage(), ms.getValue()));
                }
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    /** {@code value} as a fenced code block; the fence is made longer than any backtick run inside it. */
    static String fenced(String language, String value) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < value.length(); i++) {
            run = value.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + language.strip() + "\n" + value.strip() + "\n" + fence;
    }

    /**
     * Plain text as Markdown that renders to the same characters: every ASCII punctuation character is
     * backslash-escaped (CommonMark allows that for all of them), a line break inside a paragraph becomes a
     * hard break, and leading indentation is kept with no-break spaces (four or more ordinary spaces would
     * start an indented code block, fewer are dropped).
     */
    static String escapePlainText(String text) {
        String[] lines = text.strip().split("\\R", -1);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                sb.append(line.charAt(indent) == '\t' ? "    " : " ");
                indent++;
            }
            for (int c = indent; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (isAsciiPunctuation(ch)) {
                    sb.append('\\');
                }
                sb.append(ch);
            }
            if (i < lines.length - 1) {
                boolean paragraphContinues = !line.isEmpty() && !lines[i + 1].isBlank();
                sb.append(paragraphContinues ? "\\\n" : "\n");
            }
        }
        return sb.toString();
    }

    private static boolean isAsciiPunctuation(char ch) {
        return (ch >= '!' && ch <= '/')
                || (ch >= ':' && ch <= '@')
                || (ch >= '[' && ch <= '`')
                || (ch >= '{' && ch <= '~');
    }
}
