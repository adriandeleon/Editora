package com.editora.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What SDKMAN has installed: {@code $SDKMAN_DIR/candidates/<candidate>/<identifier>/} (default
 * {@code ~/.sdkman}), with {@code current} a link to the default. A JDK's identifier carries its vendor
 * ({@code 21.0.9-tem}); Maven's and Gradle's are the version. Reads the disk: off the FX thread.
 */
public final class SdkmanInstalls {

    private SdkmanInstalls() {}

    /** SDKMAN's folder: {@code SDKMAN_DIR}, else {@code ~/.sdkman}. */
    public static Path dir(Map<String, String> env, Path userHome) {
        String set = env.get("SDKMAN_DIR");
        return set != null && !set.isBlank() ? Path.of(set.strip()) : userHome.resolve(".sdkman");
    }

    /** SDKMAN's folder for this process's environment. */
    public static Path dir() {
        return dir(System.getenv(), Path.of(System.getProperty("user.home", ".")));
    }

    /** SDKMAN's candidate name for a tool: {@code java} for {@code jdk}, else the tool's own. */
    public static String candidate(String tool) {
        return ToolInstall.JDK.equals(tool) ? "java" : tool;
    }

    /** {@code tool}'s installs, newest first; {@code current} and other links are skipped. */
    public static List<ToolInstall> installs(Path sdkmanDir, String tool) {
        Path dir = sdkmanDir.resolve("candidates").resolve(candidate(tool));
        List<ToolInstall> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                Path name = child.getFileName();
                if (name == null || Files.isSymbolicLink(child) || !Files.isDirectory(child)) {
                    continue;
                }
                String id = name.toString();
                SdkmanIds.Parsed parsed = ToolInstall.JDK.equals(tool) ? SdkmanIds.parse(id) : null;
                out.add(new ToolInstall(
                        tool,
                        parsed == null ? id : parsed.version(),
                        parsed == null ? null : parsed.vendor(),
                        parsed != null && parsed.fx(),
                        child.toAbsolutePath().normalize(),
                        ToolInstall.SDKMAN,
                        id));
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable folder has nothing to offer
        }
        out.sort(Comparator.comparing((ToolInstall i) -> Versions.key(i.version()))
                .reversed());
        return out;
    }
}
