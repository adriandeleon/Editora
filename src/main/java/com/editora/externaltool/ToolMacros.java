package com.editora.externaltool;

import java.util.Map;

/**
 * Pure {@code $Name$} macro expander for External Tools (unit-tested). Recognizes the IntelliJ-style tokens
 * below; an unknown {@code $X$} is left literal, and {@code $$} expands to a single {@code $}.
 *
 * <ul>
 *   <li>{@code $FilePath$} — absolute path of the file
 *   <li>{@code $FileDir$} — absolute parent directory
 *   <li>{@code $FileName$} — file name with extension
 *   <li>{@code $FileNameWithoutExtension$} — file name without extension
 *   <li>{@code $SelectedText$} — the current selection (empty if none)
 *   <li>{@code $LineNumber$} / {@code $ColumnNumber$} — 1-based caret position
 *   <li>{@code $ProjectFileDir$} — the project root (or the file's directory)
 * </ul>
 */
public final class ToolMacros {

    private ToolMacros() {}

    public static String expand(String template, ToolContext ctx) {
        if (template == null || template.indexOf('$') < 0) {
            return template == null ? "" : template;
        }
        Map<String, String> vars = Map.ofEntries(
                Map.entry("FilePath", ns(ctx.filePath())),
                Map.entry("FileDir", ns(ctx.fileDir())),
                Map.entry("FileName", ns(ctx.fileName())),
                Map.entry("FileNameWithoutExtension", ns(ctx.fileNameWithoutExtension())),
                Map.entry("SelectedText", ns(ctx.selectedText())),
                Map.entry("LineNumber", Integer.toString(ctx.lineNumber())),
                Map.entry("ColumnNumber", Integer.toString(ctx.columnNumber())),
                Map.entry("ProjectFileDir", ns(ctx.projectFileDir())));
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < n && template.charAt(i + 1) == '$') {
                out.append('$'); // $$ escape → literal $
                i += 2;
                continue;
            }
            int close = template.indexOf('$', i + 1);
            if (close < 0) {
                out.append(template, i, n); // dangling $ — copy the rest verbatim
                break;
            }
            String name = template.substring(i + 1, close);
            String value = vars.get(name);
            if (value != null) {
                out.append(value);
                i = close + 1;
            } else {
                // Not a macro: this '$' is literal (a shell variable, a price). Only it is consumed, so the
                // '$' that would have "closed" it can still open the macro that follows — `echo $USER
                // $FilePath$` must expand $FilePath$. An unknown $X$ comes out unchanged either way.
                out.append('$');
                i++;
            }
        }
        return out.toString();
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }
}
