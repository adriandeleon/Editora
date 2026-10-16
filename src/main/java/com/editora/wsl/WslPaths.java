package com.editora.wsl;

import java.util.Locale;

/**
 * How Windows and a WSL distribution name the same files. A folder inside the distribution is
 * {@code \\wsl.localhost\<distro>\home\ana} (or the older {@code \\wsl$\<distro>\…}) on Windows and
 * {@code /home/ana} inside; a Windows drive is {@code C:\x} on Windows and {@code /mnt/c/x} inside (WSL's
 * default automount). Pure: the text of paths, so it is tested on any machine.
 */
public final class WslPaths {

    private static final String[] HOSTS = {"\\\\wsl.localhost\\", "\\\\wsl$\\"};

    private WslPaths() {}

    /**
     * The share prefix of a path inside a distribution, as written ({@code \\wsl.localhost\Ubuntu}), or null
     * when the path is not inside one.
     */
    public static String sharePrefix(String windowsPath) {
        if (windowsPath == null) {
            return null;
        }
        String lower = windowsPath.toLowerCase(Locale.ROOT);
        for (String host : HOSTS) {
            if (lower.startsWith(host)) {
                int end = windowsPath.indexOf('\\', host.length());
                String prefix = end < 0 ? windowsPath : windowsPath.substring(0, end);
                return prefix.length() > host.length() ? prefix : null;
            }
        }
        return null;
    }

    /** The distribution a path is inside ({@code Ubuntu}), or null. */
    public static String distroOf(String windowsPath) {
        String prefix = sharePrefix(windowsPath);
        return prefix == null ? null : prefix.substring(prefix.lastIndexOf('\\') + 1);
    }

    /**
     * The distribution's name for {@code windowsPath}: a path inside it ({@code /home/ana}) or a drive's
     * ({@code /mnt/c/x}); null for anything else (a network share, another distribution's folder).
     */
    public static String toLinux(String windowsPath, String distro) {
        String prefix = sharePrefix(windowsPath);
        if (prefix != null) {
            if (!distroOf(windowsPath).equalsIgnoreCase(distro)) {
                return null;
            }
            String rest = windowsPath.substring(prefix.length()).replace('\\', '/');
            return rest.isEmpty() ? "/" : (rest.startsWith("/") ? rest : "/" + rest);
        }
        if (windowsPath != null
                && windowsPath.length() >= 2
                && Character.isLetter(windowsPath.charAt(0))
                && windowsPath.charAt(1) == ':') {
            String rest = windowsPath.substring(2).replace('\\', '/');
            String drive = "/mnt/" + Character.toLowerCase(windowsPath.charAt(0));
            return rest.isEmpty() || rest.equals("/") ? drive : drive + (rest.startsWith("/") ? rest : "/" + rest);
        }
        return null;
    }

    /**
     * Windows's name for the distribution's {@code linuxPath}: a drive's for {@code /mnt/<letter>/…}, else the
     * share's, under {@code sharePrefix} ({@code \\wsl.localhost\Ubuntu}).
     */
    public static String toWindows(String linuxPath, String sharePrefix) {
        if (linuxPath == null || !linuxPath.startsWith("/")) {
            return null;
        }
        if (linuxPath.matches("/mnt/[a-zA-Z](/.*)?")) {
            String rest = linuxPath.length() > 7 ? linuxPath.substring(7) : ""; // after "/mnt/c/"
            return Character.toUpperCase(linuxPath.charAt(5)) + ":\\" + rest.replace('/', '\\');
        }
        return linuxPath.equals("/") ? sharePrefix + "\\" : sharePrefix + linuxPath.replace('/', '\\');
    }
}
