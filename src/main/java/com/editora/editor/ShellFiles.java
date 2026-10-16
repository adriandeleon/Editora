package com.editora.editor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one table of which files are shell scripts. Pure.
 *
 * <p>{@link LanguageRegistry} (the language: status bar, comments, Run, the Bash language server),
 * {@link GrammarRegistry} (the colours) and {@link ConfigFileType} (names with no telling extension) all read it.
 * They used to keep their own lists, and the lists drifted: a {@code .ksh} file, {@code /etc/bash.bashrc} or a
 * {@code *.profile} got shell colours from the grammar table while the language table called them plain text,
 * so they had no comment toggle, no Run and no language server; and {@code .envrc}, {@code /etc/bashrc} and
 * {@code /etc/zshrc} fell through to the generic "…rc is INI" rule, where every {@code ;} starts a comment.
 */
public final class ShellFiles {

    private ShellFiles() {}

    /** File extensions (lower case, no dot) of shell scripts and startup files. */
    public static final List<String> EXTENSIONS =
            List.of("sh", "bash", "zsh", "ksh", "mksh", "bashrc", "zshrc", "profile", "ebuild", "eclass");

    /**
     * Whole file names (lower case) that are shell. Bash, zsh, ksh and POSIX sh startup files all share sh
     * syntax; the bare forms ({@code bashrc}, {@code zshrc}, …) are the system-wide copies in {@code /etc} or
     * {@code /etc/zsh}. {@code .bash_aliases} is sourced by Debian's default {@code .bashrc}; X session scripts
     * are sourced as sh; {@code .envrc} is direnv's, which runs it with bash; {@code mvnw}/{@code gradlew} are
     * the build wrappers (their {@code .cmd}/{@code .bat} siblings are batch files by extension); PKGBUILD and
     * APKBUILD are Arch and Alpine package recipes written in bash.
     */
    private static final Set<String> NAMES = Set.of(
            ".profile",
            ".bashrc",
            ".bash_profile",
            ".bash_login",
            ".bash_logout",
            ".bash_aliases",
            ".bash_functions",
            ".zshrc",
            ".zshenv",
            ".zprofile",
            ".zlogin",
            ".zlogout",
            ".zsh_aliases",
            ".zsh_functions",
            ".aliases",
            ".shrc",
            ".kshrc",
            ".mkshrc",
            ".profile.local",
            ".bashrc.local",
            ".zshrc.local",
            ".xprofile",
            ".xinitrc",
            ".xsession",
            ".xsessionrc",
            ".envrc",
            "bashrc",
            "zshrc",
            "zshenv",
            "zprofile",
            "zlogin",
            "zlogout",
            "mvnw",
            "gradlew",
            "pkgbuild",
            "apkbuild");

    /**
     * zsh's startup files. They hold zsh syntax with nothing in the file to say so — no shebang, no {@code .zsh}
     * extension — so bash-language-server would lint them as bash and report valid zsh as parse errors.
     */
    private static final Set<String> ZSH_STARTUP = Set.of(
            ".zshrc",
            ".zshenv",
            ".zprofile",
            ".zlogin",
            ".zlogout",
            ".zsh_aliases",
            ".zsh_functions",
            ".zshrc.local",
            "zshrc",
            "zshenv",
            "zprofile",
            "zlogin",
            "zlogout");

    /** Whether {@code extension} (no dot, any case) is a shell script's. */
    public static boolean isShellExtension(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a file is shell by its name or location, whatever its extension says: {@code lowerName} is the
     * lower-cased last path segment, {@code normalizedPath} the whole path with forward slashes (or just the
     * name). {@code /etc/profile} counts only under an {@code etc} directory; a file called {@code profile}
     * elsewhere could be anything.
     */
    public static boolean isShellName(String lowerName, String normalizedPath) {
        if (NAMES.contains(lowerName)) {
            return true;
        }
        return lowerName.equals("profile") && normalizedPath != null && ("/" + normalizedPath).contains("/etc/");
    }

    /**
     * Whether {@code fileName} (a name or a path) is a zsh startup file: one the Bash language server must not
     * be given (see {@link #ZSH_STARTUP}). A {@code *.zsh} script is not one of them — the server sees its
     * extension and leaves its linting alone itself.
     */
    public static boolean isZshStartupFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String norm = fileName.replace('\\', '/');
        String lower = norm.substring(norm.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return ZSH_STARTUP.contains(lower) || lower.endsWith(".zshrc");
    }
}
