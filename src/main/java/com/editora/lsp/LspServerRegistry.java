package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a language id (as resolved by {@code editor.LanguageRegistry.forFileName}) to a language-server
 * launch spec — the command to run plus the filenames that mark a project root. Phase 1 supports only
 * <b>Java</b> (Eclipse JDT LS); the command is user-configurable (Settings, default {@code jdtls}) and
 * never bundled. Additional languages (and a TOML-driven registry) are future extensions.
 *
 * <p>All methods are static and pure (no process launch, no I/O) so they are unit-testable.
 */
public final class LspServerRegistry {

    /** A resolved server launch spec for one language. */
    public record ServerSpec(String languageId, List<String> command, List<String> rootMarkers) {
    }

    /** Build files (and {@code .git}) that mark a Java project root, nearest-first when walking up. */
    public static final List<String> JAVA_ROOT_MARKERS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", ".git");

    /** Default Java server command when the user leaves the Settings field blank. */
    public static final String DEFAULT_JAVA_COMMAND = "jdtls";

    private LspServerRegistry() {
    }

    /** Whether Phase 1 has a language server for {@code languageId}. */
    public static boolean isSupported(String languageId) {
        return "java".equals(languageId);
    }

    /**
     * The launch spec for {@code languageId}, using {@code configuredCommand} (blank ⇒ the language's
     * default), or {@code null} when the language is unsupported.
     */
    public static ServerSpec specFor(String languageId, String configuredCommand) {
        if (!"java".equals(languageId)) {
            return null;
        }
        List<String> command = tokenize(
                configuredCommand == null || configuredCommand.isBlank()
                        ? DEFAULT_JAVA_COMMAND : configuredCommand);
        return new ServerSpec("java", command, JAVA_ROOT_MARKERS);
    }

    /**
     * Splits a command string into argv on unquoted whitespace, honoring single/double quotes so a path
     * with spaces stays one token. Pure; mirrors the tokenizing done for the mmdc/maid commands.
     */
    public static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) {
            out.add(cur.toString());
        }
        return out;
    }
}
