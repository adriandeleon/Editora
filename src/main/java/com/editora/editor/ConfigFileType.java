package com.editora.editor;

import java.util.Locale;

/**
 * Resolves a file name or full path to a language id when the type is determined by the file's
 * <em>name or location</em> rather than a plain extension (e.g. {@code Dockerfile}, {@code ~/.ssh/config},
 * {@code /etc/hosts}, {@code .git/config}).
 *
 * <p>Pure (no toolkit), so it is unit-tested, and shared by {@link LanguageRegistry} (which needs the
 * language <em>name</em>) and {@link GrammarRegistry} (which needs the TextMate <em>scope</em>) so the two
 * can never disagree about which files are special. Returns {@code null} when the name/path is not one of
 * these cases — the caller then falls back to its extension map.
 *
 * <p>Accepts either a bare file name or a full path. Rules that depend on a parent directory (an SSH
 * {@code config} under {@code .ssh/}, {@code hosts} under {@code etc/}, a Git {@code config} under
 * {@code .git/}) only fire when a path is supplied; with a bare name they are skipped, which is why
 * {@link EditorBuffer#setPath} passes the full path.
 */
public final class ConfigFileType {

    private ConfigFileType() {
    }

    /** The language id for a name/location-determined config file, or {@code null} if not special. */
    public static String resolve(String fileNameOrPath) {
        if (fileNameOrPath == null || fileNameOrPath.isEmpty()) {
            return null;
        }
        String norm = fileNameOrPath.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String base = slash >= 0 ? norm.substring(slash + 1) : norm;
        String lower = base.toLowerCase(Locale.ROOT);

        // Dockerfile / Containerfile — extension-less, or a tag suffix (e.g. Dockerfile.dev).
        if (lower.equals("dockerfile") || lower.equals("containerfile")
                || lower.startsWith("dockerfile.") || lower.startsWith("containerfile.")) {
            return "dockerfile";
        }
        // dotenv — ".env", "<name>.env", ".env.local" / ".env.production", ...
        if (lower.equals(".env") || lower.startsWith(".env.") || lower.endsWith(".env")) {
            return "dotenv";
        }
        // SSH client/daemon config — explicit names anywhere, "config" under a .ssh dir, or any file in
        // a drop-in dir (ssh_config.d / sshd_config.d, or .ssh/config.d). A bare "config" is matched only
        // via the .ssh ancestor (too generic otherwise).
        if (lower.equals("ssh_config") || lower.equals("sshd_config")
                || (lower.equals("config") && hasAncestor(norm, ".ssh"))
                || hasAncestor(norm, "ssh_config.d") || hasAncestor(norm, "sshd_config.d")
                || (hasAncestor(norm, ".ssh") && hasAncestor(norm, "config.d"))) {
            return "ssh-config";
        }
        // Git config — ".gitconfig", "/etc/gitconfig", "<name>.gitconfig", or ".git/config".
        if (lower.equals(".gitconfig") || lower.equals("gitconfig") || lower.endsWith(".gitconfig")
                || (lower.equals("config") && hasAncestor(norm, ".git"))) {
            return "git-config";
        }
        // crontab — literal "crontab", a *.cron / *.crontab file, or any file in a cron.d drop-in dir.
        if (lower.equals("crontab") || lower.endsWith(".cron") || lower.endsWith(".crontab")
                || hasAncestor(norm, "cron.d")) {
            return "crontab";
        }
        // Caddyfile — usually the literal "Caddyfile" (or a *.caddyfile suffix).
        if (lower.equals("caddyfile") || lower.endsWith(".caddyfile")) {
            return "caddyfile";
        }
        // /etc/fstab — the basename is unambiguous enough on its own.
        if (lower.equals("fstab")) {
            return "fstab";
        }
        // /etc/hosts — only under an "etc" dir; a bare "hosts" collides with Ansible inventory files.
        if (lower.equals("hosts") && hasAncestor(norm, "etc")) {
            return "hosts";
        }
        // Debian/Ubuntu deb822 (RFC822 paragraphs): APT *.sources, *.dsc source control,
        // debian/control, debian/copyright, and /etc/apt/preferences{,.d}.
        if (lower.endsWith(".sources") || lower.endsWith(".dsc")
                || ((lower.equals("control") || lower.equals("copyright")) && hasAncestor(norm, "debian"))
                || (lower.equals("preferences") && hasAncestor(norm, "apt"))
                || hasAncestor(norm, "preferences.d")) {
            return "deb822";
        }
        // Classic one-line APT sources: /etc/apt/sources.list or a *.list under sources.list.d
        // (a *.sources there is deb822 and already matched above).
        if (lower.equals("sources.list") || hasAncestor(norm, "sources.list.d")) {
            return "apt-sources";
        }
        // Debian ifupdown network config: /etc/network/interfaces or a file under interfaces.d.
        if ((lower.equals("interfaces") && hasAncestor(norm, "network")) || hasAncestor(norm, "interfaces.d")) {
            return "interfaces";
        }
        // Debian packaging changelog: debian/changelog (or an installed changelog.Debian).
        if ((lower.equals("changelog") && hasAncestor(norm, "debian")) || lower.equals("changelog.debian")) {
            return "debian-changelog";
        }
        return null;
    }

    /** True if {@code normalizedPath} (forward-slash separators) has a directory segment equal to {@code dir}. */
    private static boolean hasAncestor(String normalizedPath, String dir) {
        int slash = normalizedPath.lastIndexOf('/');
        if (slash < 0) {
            return false; // bare name: no ancestor directories to inspect
        }
        for (String seg : normalizedPath.substring(0, slash).split("/")) {
            if (seg.equals(dir)) {
                return true;
            }
        }
        return false;
    }
}
