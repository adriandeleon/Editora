package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the crumb trail {@link FileBreadcrumb} shows: the cumulative path segments of the active file,
 * with the user's home directory collapsed into a single {@code ~} crumb.
 *
 * <p>The collapse is what makes the bar readable. Spelled out, every path under a home directory opens
 * with two crumbs that are the same on every file the user will ever have open ({@code home / adl / …}),
 * so the segments that actually distinguish this file start a third of the way along a bar that scrolls.
 * One {@code ~} crumb still navigates to the home directory, so nothing is lost but the noise.
 *
 * <p>Pure and toolkit-free — the home directory is a parameter rather than a system-property read — so the
 * trail can be tested without a JavaFX toolkit or a particular machine's layout.
 */
public final class BreadcrumbTrail {

    /** One crumb: the path clicking it navigates to, and the text shown on it. */
    public record Crumb(Path path, String label) {}

    /** Label of the collapsed home crumb. */
    public static final String HOME_LABEL = "~";

    private BreadcrumbTrail() {}

    /**
     * The crumbs for {@code file}, root-first. The filesystem root ({@code /} or {@code C:\}) is not a crumb
     * of its own; when {@code file} lies under {@code home}, home's own segments collapse into one
     * {@link #HOME_LABEL} crumb pointing at the home directory itself.
     *
     * @param file the path to break up; taken as-is (callers pass an absolute path)
     * @param home the user's home directory, or {@code null} to collapse nothing
     */
    public static List<Crumb> of(Path file, Path home) {
        List<Crumb> out = new ArrayList<>();
        int homeSegments = homeSegmentCount(file, home);
        Path acc = file.getRoot();
        int i = 0;
        for (Path segment : file) {
            acc = acc == null ? segment : acc.resolve(segment);
            i++;
            if (i < homeSegments) {
                continue; // swallowed by the "~" crumb, which stands for the whole home prefix
            }
            out.add(new Crumb(acc, i == homeSegments ? HOME_LABEL : segment.toString()));
        }
        if (out.isEmpty()) {
            out.add(new Crumb(file, label(file))); // a bare root ("/") has no name elements to walk
        }
        return out;
    }

    /** Display text for a path with no crumb context — its file name, else the whole path (a root). */
    static String label(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    /**
     * How many leading segments of {@code file} the home directory covers, or 0 when it covers none.
     *
     * <p>The filesystem check is not redundant with {@code startsWith}: a remote (SFTP) buffer's path
     * belongs to another provider, where the local home directory is meaningless and comparing the two
     * would at best be nonsense and at worst provider-mismatched.
     */
    private static int homeSegmentCount(Path file, Path home) {
        if (home == null || file == null) {
            return 0;
        }
        Path normalized;
        try {
            normalized = home.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return 0; // an unresolvable home is simply not collapsed
        }
        if (normalized.getNameCount() == 0 || !file.getFileSystem().equals(normalized.getFileSystem())) {
            return 0;
        }
        return file.startsWith(normalized) ? normalized.getNameCount() : 0;
    }
}
