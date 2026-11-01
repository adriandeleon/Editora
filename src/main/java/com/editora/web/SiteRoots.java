package com.editora.web;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The one rule that decides a static site's root folder, shared by HTML Live Preview ({@link
 * LivePreviewServer#serve}) and the HTML language server's workspace ({@code RootResolver.siteRoot}), so the
 * editor and the browser resolve {@code /css/site.css} against the same folder (HTML review 2, A101).
 *
 * <p>A file in the window's project uses the project root. A loose file climbs from its folder to the nearest
 * folder holding an {@code index.html}, then on while the parent folder holds one too (the highest
 * <em>contiguous</em> {@code index.html}): {@code site/blog/post.html} is rooted at {@code site/} when both hold an
 * {@code index.html}, and a stray saved page higher up (in Downloads, or {@code ~/src}) is not reached past a folder
 * without one. The climb never reaches an {@linkplain #isUnsafeDocRoot unsafe folder}: the home folder or above
 * it, a catch-all folder (Downloads, Desktop, Documents, their localized and OneDrive forms), a temp folder or a
 * filesystem root. Without any {@code index.html} the file's own folder is the root. File reads only.
 */
public final class SiteRoots {

    /** How far a loose file's search climbs at most. */
    private static final int MAX_HOPS = 32;

    /** Shared temp folders a downloaded page may be saved into, on top of {@code java.io.tmpdir}. */
    private static final List<String> SHARED_TEMP = List.of("/tmp", "/private/tmp", "/var/tmp", "/dev/shm");

    private SiteRoots() {}

    /**
     * The folder to serve for {@code file}: the project root when the file is inside it (and the project is a safe
     * root), else {@link #looseRoot}. The result may still be unsafe (a page sitting directly in the home or in
     * Downloads): the caller checks {@link #isUnsafeDocRoot} and refuses.
     */
    public static Path siteRoot(Path file, @Nullable Path projectRoot, @Nullable Path home) {
        Path abs = file.toAbsolutePath().normalize();
        if (projectRoot != null && projectRoot.getFileSystem() == abs.getFileSystem()) {
            Path pr = projectRoot.toAbsolutePath().normalize();
            if (abs.startsWith(pr) && !abs.equals(pr) && !isUnsafeDocRoot(pr, home)) {
                return pr;
            }
        }
        return looseRoot(abs, home);
    }

    /** {@link #siteRoot(Path, Path, Path)} against the current user's home. */
    public static Path siteRoot(Path file, @Nullable Path projectRoot) {
        return siteRoot(file, projectRoot, userHome());
    }

    /**
     * A loose file's site root: the nearest folder up from the file's own holding an {@code index.html}, raised
     * while its parent holds one too, never into an unsafe folder; else the file's folder.
     */
    public static Path looseRoot(Path file, @Nullable Path home) {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        Path folder = parent == null ? abs : parent;
        Path found = null;
        Path d = folder;
        for (int hops = 0; d != null && hops < MAX_HOPS && !isUnsafeDocRoot(d, home); hops++, d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("index.html"))) {
                found = d;
                break;
            }
        }
        if (found == null) {
            return folder;
        }
        for (int hops = 0; hops < MAX_HOPS; hops++) {
            Path up = found.getParent();
            if (up == null || isUnsafeDocRoot(up, home) || !Files.isRegularFile(up.resolve("index.html"))) {
                break;
            }
            found = up;
        }
        return found;
    }

    /**
     * Whether serving {@code docRoot} would expose far more than a web site: a filesystem root; the user's home
     * directory or any ancestor of it ({@code /home}, {@code /Users}); a catch-all folder of the home ({@link
     * #catchAllFolders}) when the page sits directly in it; a temp folder ({@code java.io.tmpdir}, {@code /tmp},
     * {@code /private/tmp}, {@code /var/tmp}, {@code /dev/shm}) or an ancestor of one; or anything on a
     * non-default (remote) file system, whose home cannot be compared with ours. {@code home} may be null
     * (unknown) — then only the home rules are skipped.
     */
    public static boolean isUnsafeDocRoot(@Nullable Path docRoot, @Nullable Path home) {
        return isUnsafeDocRoot(docRoot, home, System::getenv);
    }

    /** {@link #isUnsafeDocRoot(Path, Path)} with the environment supplied (the OneDrive variables; tests). */
    static boolean isUnsafeDocRoot(
            @Nullable Path docRoot, @Nullable Path home, Function<String, @Nullable String> env) {
        if (docRoot == null || docRoot.getFileSystem() != FileSystems.getDefault()) {
            return true;
        }
        Path root = real(docRoot);
        if (root.getParent() == null) {
            return true;
        }
        if (home != null) {
            Path h = real(home);
            if (h.startsWith(root)) {
                return true;
            }
            for (Path folder : catchAllFolders(h, env)) {
                if (root.equals(real(folder))) {
                    return true;
                }
            }
        }
        List<String> temps = new ArrayList<>(SHARED_TEMP);
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isBlank()) {
            temps.add(tmp);
        }
        for (String t : temps) {
            try {
                if (real(Path.of(t)).startsWith(root)) {
                    return true;
                }
            } catch (RuntimeException notAPath) {
                // a Windows-only spelling on another OS, or the reverse: not this machine's temp folder
            }
        }
        return false;
    }

    /** {@link #isUnsafeDocRoot(Path, Path)} against the current user's home. */
    public static boolean isUnsafeDocRoot(@Nullable Path docRoot) {
        return isUnsafeDocRoot(docRoot, userHome());
    }

    /**
     * Downloads, Desktop and Documents under {@code home}; the same three under {@code ~/OneDrive} and the OneDrive
     * folders named by {@code %OneDrive%}, {@code %OneDriveConsumer%} and {@code %OneDriveCommercial%} (Windows
     * folder backup moves Desktop and Documents there); plus their names from {@code ~/.config/user-dirs.dirs}.
     */
    static List<Path> catchAllFolders(Path home, Function<String, @Nullable String> env) {
        List<Path> bases = new ArrayList<>();
        bases.add(home);
        bases.add(home.resolve("OneDrive"));
        for (String var : List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
            String v = env.apply(var);
            if (v != null && !v.isBlank()) {
                try {
                    bases.add(Path.of(v));
                } catch (RuntimeException notAPath) {
                    // ignore an unusable value
                }
            }
        }
        List<Path> out = new ArrayList<>();
        for (Path base : bases) {
            out.add(base.resolve("Downloads"));
            out.add(base.resolve("Desktop"));
            out.add(base.resolve("Documents"));
        }
        Path dirs = home.resolve(".config").resolve("user-dirs.dirs");
        try {
            if (Files.isRegularFile(dirs)) {
                for (String line : Files.readAllLines(dirs)) {
                    Path p = xdgUserDir(line, home);
                    if (p != null) {
                        out.add(p);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: the English names still apply
        }
        return out;
    }

    /** {@code XDG_DOWNLOAD_DIR="$HOME/Descargas"} ⇒ {@code <home>/Descargas}, for the three catch-all keys only. */
    static @Nullable Path xdgUserDir(String line, Path home) {
        String l = line.strip();
        int eq = l.indexOf('=');
        if (eq < 0) {
            return null;
        }
        String key = l.substring(0, eq).strip();
        if (!key.equals("XDG_DOWNLOAD_DIR") && !key.equals("XDG_DESKTOP_DIR") && !key.equals("XDG_DOCUMENTS_DIR")) {
            return null;
        }
        String value = l.substring(eq + 1).strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("$HOME/")) {
            return home.resolve(value.substring("$HOME/".length()));
        }
        return value.startsWith("/") ? Path.of(value) : null;
    }

    static @Nullable Path userHome() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }

    /**
     * {@code p} canonicalized. A path that does not exist yet is canonicalized through its nearest existing
     * ancestor, so it still compares equal to the same folder reached through a canonical parent (macOS
     * {@code /var} → {@code /private/var}, {@code /tmp} → {@code /private/tmp}, Windows 8.3 short names).
     */
    static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(abs));
        } catch (IOException e) {
            return abs;
        }
    }
}
