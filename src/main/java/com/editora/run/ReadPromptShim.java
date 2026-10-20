package com.editora.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Makes {@code read -p "Prompt: "} show its prompt in the Run console.
 *
 * <p>Bash prints a {@code read -p} prompt only when standard input is a terminal, and the Run console is a
 * pipe — so a script asking a question showed nothing at all while it waited, and the user could not tell it
 * wanted input. Without a pseudo-terminal the only place to fix that is bash itself: a non-interactive bash
 * sources the file named by {@code BASH_ENV} before the script, and this file defines a {@code read} function
 * that, when stdin is not a terminal, prints the {@code -p} prompt to stderr (where bash prints it) and then
 * runs the real {@code builtin read} with the same arguments. Calls without a {@code -p} skip the option scan,
 * an {@code xtrace} ({@code set -x}) run does not trace the wrapper, and a {@code BASH_ENV} the user already
 * had is sourced first.
 *
 * <p>Only for a local bash run: the file lives on this machine, and only bash reads {@code BASH_ENV}
 * ({@code sh}-mode bash, dash and zsh do not).
 */
public final class ReadPromptShim {

    private ReadPromptShim() {}

    /** Carries a {@code BASH_ENV} the user already had, which the shim sources first. */
    static final String USER_BASH_ENV = "EDITORA_USER_BASH_ENV";

    /** The shim's text. */
    static final String SCRIPT = """
            # Editora Run: show `read -p` prompts, which bash prints only when stdin is a terminal.
            # Not traced itself: a traced run (bash -x) starts with xtrace on while this file is sourced.
            { __editora_xtrace=$-; set +x; } 2>/dev/null
            if [ -n "${EDITORA_USER_BASH_ENV:-}" ]; then . "$EDITORA_USER_BASH_ENV"; fi
            read() {
              { local __ed_x=$- __ed_i=1 __ed_a __ed_p=; set +x; } 2>/dev/null
              if [ ! -t 0 ]; then
                case "$*" in
                  *-*p*)
                    while [ "$__ed_i" -le "$#" ]; do
                      __ed_a=${!__ed_i}
                      case $__ed_a in
                        --) break ;;
                        -*p) __ed_i=$((__ed_i + 1)); __ed_p=${!__ed_i-} ;;
                        -p?*) __ed_p=${__ed_a#-p} ;;
                        -[!-]*p?*) __ed_p=${__ed_a#*p} ;;
                        -*[adinNtu]) __ed_i=$((__ed_i + 1)) ;;
                        -*) ;;
                        *) break ;;
                      esac
                      __ed_i=$((__ed_i + 1))
                    done
                    if [ -n "$__ed_p" ]; then printf '%s' "$__ed_p" >&2; fi
                    ;;
                esac
              fi
              case $__ed_x in *x*) set -x ;; esac
              builtin read "$@"
            }
            case $__editora_xtrace in *x*) set -x ;; esac
            """;

    private static volatile @Nullable Path written;

    /**
     * The variables that load the shim into a bash run: {@code BASH_ENV} pointing at it, plus the user's own
     * {@code BASH_ENV} (from {@code inherited}) passed on so it still runs. Empty when the file could not be
     * written — the script then runs exactly as before, just without visible prompts.
     */
    public static Map<String, String> environment(Map<String, String> inherited) {
        Path file = file();
        if (file == null) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        String user = inherited == null ? null : inherited.get("BASH_ENV");
        if (user != null && !user.isBlank() && !user.equals(file.toString())) {
            env.put(USER_BASH_ENV, user);
        }
        env.put("BASH_ENV", file.toString());
        return env;
    }

    /** The shim written once per process to a temp file (deleted on exit); null when that failed. */
    static @Nullable Path file() {
        Path f = written;
        if (f != null && Files.isRegularFile(f)) {
            return f;
        }
        synchronized (ReadPromptShim.class) {
            if (written != null && Files.isRegularFile(written)) {
                return written;
            }
            try {
                Path created = Files.createTempFile("editora-read-prompt", ".bash");
                Files.writeString(created, SCRIPT, StandardCharsets.UTF_8);
                created.toFile().deleteOnExit();
                written = created;
                return created;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
