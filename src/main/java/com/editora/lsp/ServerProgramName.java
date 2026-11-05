package com.editora.lsp;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The program a server command runs, as the status bar's "LSP:" segment and the progress lines name it. The first
 * word was taken as it was, so a command run through a wrapper named the wrapper: {@code env NODE_OPTIONS=…
 * typescript-language-server --stdio} read "LSP: env" and "env: Loaded and ready", {@code npx
 * typescript-language-server --stdio} read "npx" (JS review B114). The word naming the server's default program
 * wins wherever it is; otherwise leading wrappers, their options and {@code NAME=value} settings are skipped. Pure.
 */
public final class ServerProgramName {

    private ServerProgramName() {}

    /** Programs that only start another program. */
    private static final Set<String> WRAPPERS =
            Set.of("env", "npx", "bunx", "pnpx", "nice", "nohup", "exec", "command", "time", "sudo", "doas");

    /**
     * The program's name in {@code tokens} (the command split into words), for a server whose default program is
     * {@code defaultProgram} ("" when it has none).
     */
    public static String of(List<String> tokens, String defaultProgram) {
        if (tokens == null || tokens.isEmpty()) {
            return defaultProgram == null ? "" : defaultProgram;
        }
        if (defaultProgram != null && !defaultProgram.isBlank()) {
            for (String t : tokens) {
                String name = baseName(t);
                if (name.equals(defaultProgram) || stripExtension(name).equals(defaultProgram)) {
                    return defaultProgram;
                }
            }
        }
        for (String t : tokens) {
            String name = baseName(t);
            if (t.startsWith("-") || t.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                continue;
            }
            if (WRAPPERS.contains(stripExtension(name).toLowerCase(Locale.ROOT))) {
                continue;
            }
            return name;
        }
        return baseName(tokens.get(0));
    }

    /** The last part of a path, on either separator (a Windows command is read on any platform). */
    private static String baseName(String word) {
        int cut = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return cut < 0 ? word : word.substring(cut + 1);
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".cmd", ".exe", ".bat", ".js", ".mjs", ".cjs")) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }
}
