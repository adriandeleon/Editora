package com.editora.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Where a POSIX shell ({@code bash}, {@code sh}, …) comes from on Windows (pure, unit-tested).
 *
 * <p>A plain PATH lookup for {@code bash} on Windows almost always finds {@code C:\Windows\System32\bash.exe}
 * first: the WSL launcher. It runs Linux bash inside WSL, which cannot open a {@code C:\…} script path, so a
 * Run failed with "No such file or directory" — and without WSL there is no {@code bash} on the PATH at all,
 * because Git for Windows only adds its {@code cmd} folder by default. Git Bash is what a Windows user who
 * has bash almost always has, and it reads Windows paths, so it is looked for first: beside a {@code git.exe}
 * on the PATH, then in the standard install folders. The WSL launcher (anything under {@code System32},
 * {@code SysWOW64} or {@code WindowsApps}) is never taken.
 */
public final class WindowsShells {

    private WindowsShells() {}

    private static final Set<String> SHELLS = Set.of("bash", "sh", "dash", "zsh");

    /** Whether {@code name} (no folder, optionally ending in {@code .exe}) is a POSIX shell this resolves. */
    public static boolean isPosixShell(String name) {
        return SHELLS.contains(stem(name));
    }

    /**
     * The shell's {@code .exe}, or {@code null} when none was found.
     *
     * @param name {@code bash}, {@code sh}, … (with or without {@code .exe})
     * @param pathDirs the PATH's folders, in order
     * @param installRoots folders a Git for Windows install lives under ({@code %ProgramFiles%},
     *     {@code %ProgramFiles(x86)%}, {@code %LOCALAPPDATA%\Programs}), in order; blanks are skipped
     * @param isFile whether a path names an existing file
     */
    public static @Nullable String find(
            String name, List<String> pathDirs, List<String> installRoots, Predicate<String> isFile) {
        String exe = stem(name) + ".exe";
        // 1. The Git a user runs from a terminal: Git\cmd\git.exe (or Git\bin, Git\mingw64\bin) → Git\…
        for (String dir : pathDirs) {
            if (dir == null || dir.isBlank() || isSystemDir(dir) || !isFile.test(join(dir, "git.exe"))) {
                continue;
            }
            for (String gitRoot : List.of(parent(dir), parent(parent(dir)))) {
                String found = inGit(gitRoot, exe, isFile);
                if (found != null) {
                    return found;
                }
            }
        }
        // 2. A standard install, even when nothing of Git is on the PATH.
        for (String root : installRoots) {
            if (root != null && !root.isBlank()) {
                String found = inGit(join(root, "Git"), exe, isFile);
                if (found != null) {
                    return found;
                }
            }
        }
        // 3. Any other bash on the PATH (MSYS2, Cygwin, a portable Git) — but never the WSL launcher.
        for (String dir : pathDirs) {
            if (dir != null && !dir.isBlank() && !isSystemDir(dir) && isFile.test(join(dir, exe))) {
                return join(dir, exe);
            }
        }
        return null;
    }

    /** The install roots this machine's environment names, in the order {@link #find} tries them. */
    public static List<String> installRoots(java.util.function.Function<String, String> getenv) {
        List<String> roots = new ArrayList<>();
        for (String key : List.of("ProgramW6432", "ProgramFiles", "ProgramFiles(x86)")) {
            String v = getenv.apply(key);
            if (v != null && !v.isBlank() && !roots.contains(v)) {
                roots.add(v);
            }
        }
        String local = getenv.apply("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            roots.add(join(local, "Programs"));
        }
        return roots;
    }

    private static @Nullable String inGit(@Nullable String gitRoot, String exe, Predicate<String> isFile) {
        if (gitRoot == null || gitRoot.isBlank()) {
            return null;
        }
        for (String sub : List.of("bin", "usr\\bin")) {
            String candidate = join(join(gitRoot, sub), exe);
            if (isFile.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether {@code dir} is a Windows system folder, where {@code bash.exe} is the WSL launcher. */
    static boolean isSystemDir(String dir) {
        String d = dir.replace('/', '\\').toLowerCase(Locale.ROOT);
        while (d.endsWith("\\")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.endsWith("\\system32")
                || d.endsWith("\\syswow64")
                || d.endsWith("\\sysnative")
                || d.endsWith("\\windowsapps");
    }

    private static String stem(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }

    private static String join(String dir, String child) {
        return dir.endsWith("\\") || dir.endsWith("/") ? dir + child : dir + "\\" + child;
    }

    private static @Nullable String parent(@Nullable String dir) {
        if (dir == null) {
            return null;
        }
        String d = dir;
        while (d.endsWith("\\") || d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        int slash = Math.max(d.lastIndexOf('\\'), d.lastIndexOf('/'));
        return slash <= 0 ? null : d.substring(0, slash);
    }
}
