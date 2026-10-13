package com.editora.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One child process to start on an {@link ExecTarget}: what a {@link ProcessBuilder} would be told, in a
 * form that can also be sent to another machine.
 *
 * @param argv the command and its arguments, never parsed by a shell
 * @param workingDir where it runs ({@code null}: the target's default directory)
 * @param env variables set on top of the target's own environment; a {@code null} value <em>removes</em>
 *     that variable
 * @param locale which of the two child environments it gets (see {@link ProcessRunner})
 * @param longLived a server, run or build rather than a one-shot command: on a remote target it must not
 *     outlive the connection that started it
 * @param discardStdout drop stdout at the source instead of piping it back
 * @param discardStderr drop stderr at the source instead of piping it back
 * @param scrubSecrets remove secret-looking variables ({@link SecretEnv}) from the inherited environment
 *     first; only a local target inherits one
 */
public record ExecSpec(
        List<String> argv,
        Path workingDir,
        Map<String, String> env,
        Locale locale,
        boolean longLived,
        boolean discardStdout,
        boolean discardStderr,
        boolean scrubSecrets) {

    /** The child's locale: {@code LC_ALL=C} for output Editora parses, the user's own for everything else. */
    public enum Locale {
        PARSE_STABLE,
        USER
    }

    public ExecSpec {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv is empty");
        }
        if (locale == null) {
            throw new IllegalArgumentException("locale is required");
        }
        env = env == null ? Map.of() : env;
    }

    /** A one-shot command with piped output and nothing scrubbed. */
    public static ExecSpec of(List<String> argv, Path workingDir, Map<String, String> env, Locale locale) {
        return new ExecSpec(argv, workingDir, env, locale, false, false, false, false);
    }

    public ExecSpec withLongLived(boolean value) {
        return new ExecSpec(argv, workingDir, env, locale, value, discardStdout, discardStderr, scrubSecrets);
    }

    public ExecSpec withDiscardedOutput(boolean stdout, boolean stderr) {
        return new ExecSpec(argv, workingDir, env, locale, longLived, stdout, stderr, scrubSecrets);
    }

    public ExecSpec withScrubbedSecrets(boolean value) {
        return new ExecSpec(argv, workingDir, env, locale, longLived, discardStdout, discardStderr, value);
    }
}
