package com.editora.toolchain;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * What jkit (jkit-project.dev) has installed, read straight from its folder, so it works with any jkit version
 * and without running it. jkit keeps each install in {@code $JKIT_HOME/tools/<tool>/<id>/} (default
 * {@code ~/.jkit}) with a {@code jkit-install.properties} marker ({@code tool}, {@code version}, {@code vendor},
 * {@code fx}), and its defaults as links {@code global/<slot>} → an install. GraalVM builds live under
 * {@code tools/jdk} with a GraalVM vendor. Reads the disk: off the FX thread.
 */
public final class JkitInstalls {

    /** The marker jkit writes into every install folder. */
    public static final String MARKER = "jkit-install.properties";

    private JkitInstalls() {}

    /** jkit's folder: {@code JKIT_HOME}, else {@code ~/.jkit}. */
    public static Path home(Map<String, String> env, Path userHome) {
        String set = env.get("JKIT_HOME");
        return set != null && !set.isBlank() ? Path.of(set.strip()) : userHome.resolve(".jkit");
    }

    /** jkit's folder for this process's environment. */
    public static Path home() {
        return home(System.getenv(), Path.of(System.getProperty("user.home", ".")));
    }

    /** The folder jkit keeps {@code tool}'s installs in ({@code jdk}, {@code maven}, {@code gradle}). */
    public static Path toolsDir(Path jkitHome, String tool) {
        return jkitHome.resolve("tools").resolve(tool);
    }

    /**
     * {@code tool}'s installs, newest first: every folder under {@code tools/<tool>} with a readable marker.
     * Links are skipped (jkit's stable aliases); a folder without a marker is a half-finished install.
     */
    public static List<ToolInstall> installs(Path jkitHome, String tool) {
        Path dir = toolsDir(jkitHome, tool);
        List<ToolInstall> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                ToolInstall install = read(child, tool);
                if (install != null) {
                    out.add(install);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable folder has nothing to offer
        }
        out.sort(Comparator.comparing((ToolInstall i) -> Versions.key(i.version()))
                .reversed());
        return out;
    }

    /** One install folder, or null when it has no readable marker. */
    static @Nullable ToolInstall read(Path folder, String tool) {
        Path marker = folder.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(marker)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String version = p.getProperty("version", "").strip();
        if (version.isEmpty()) {
            return null;
        }
        String vendor = p.getProperty("vendor", "").strip();
        Path name = folder.getFileName();
        return new ToolInstall(
                tool,
                version,
                vendor.isEmpty() ? null : Vendors.fromFoojay(vendor),
                Boolean.parseBoolean(p.getProperty("fx", "false").strip()),
                folder.toAbsolutePath().normalize(),
                ToolInstall.JKIT,
                name == null ? version : name.toString());
    }

    /**
     * Where jkit's global link for {@code slot} ({@code jdk}, {@code maven}, {@code gradle}, {@code graalvm})
     * points, or null when there is none. A symbolic link on Linux and macOS; on Windows jkit makes a directory
     * junction, which {@link Files#isSymbolicLink} does not report, and writes the target into
     * {@code global/.<slot>.target} beside it, which is read when the link itself says nothing.
     */
    public static @Nullable Path global(Path jkitHome, String slot) {
        Path dir = jkitHome.resolve("global");
        Path link = dir.resolve(slot);
        try {
            if (Files.isSymbolicLink(link)) {
                Path target = Files.readSymbolicLink(link);
                return dir.resolve(target).toAbsolutePath().normalize();
            }
            Path targetFile = dir.resolve("." + slot + ".target");
            if (Files.isRegularFile(targetFile)) {
                String named = Files.readString(targetFile).strip();
                if (!named.isEmpty()) {
                    Path target = dir.resolve(named).toAbsolutePath().normalize();
                    return Files.isDirectory(target) ? target : null;
                }
            }
            if (Files.isDirectory(link)) { // a junction without its target file: where it really leads
                Path real = link.toRealPath();
                return real.equals(link.toAbsolutePath().normalize()) ? null : real;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
