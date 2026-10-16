package com.editora.wsl;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;

/**
 * The {@link ExecTargets} resolver for folders inside a WSL distribution ({@code \\wsl.localhost\<distro>\…},
 * {@code \\wsl$\<distro>\…}): one target per distribution and share spelling. A lookup of the path's text —
 * no I/O. Registered on Windows only.
 */
public final class WslTargets implements ExecTargets.Resolver {

    private static final WslTargets SHARED = new WslTargets(List.of("wsl.exe"));

    private final List<String> launcher;
    private final Map<String, WslExecTarget> byShare = new ConcurrentHashMap<>();

    public WslTargets(List<String> launcher) {
        this.launcher = List.copyOf(launcher);
    }

    public static WslTargets shared() {
        return SHARED;
    }

    @Override
    public ExecTarget forPath(Path path) {
        String text = path.toString();
        String prefix = WslPaths.sharePrefix(text);
        if (prefix == null) {
            return null;
        }
        String distro = WslPaths.distroOf(text);
        return byShare.computeIfAbsent(
                prefix.toLowerCase(Locale.ROOT), k -> new WslExecTarget(launcher, distro, prefix));
    }

    /** Whether {@code path} is inside a WSL distribution. No I/O. */
    public static boolean isWslPath(Path path) {
        return path != null && WslPaths.sharePrefix(path.toString()) != null;
    }
}
