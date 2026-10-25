package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Tab titles that tell same-named files apart: when two open files share a name, each tab shows the nearest
 * folder that distinguishes it — {@code pom.xml · core}, {@code pom.xml · app} — as IntelliJ and VS Code do
 * (Maven review C8; a multi-module build is where same-named files are the norm). Pure.
 */
final class TabTitles {

    /** Separator between the file name and its distinguishing folder. */
    static final String SEPARATOR = " · ";

    /** How many folder segments a title may carry before giving up on distinguishing (keeps tabs short). */
    static final int MAX_SEGMENTS = 3;

    private TabTitles() {}

    /**
     * The title for {@code self} among {@code open} (which may include {@code self}): its file name, plus
     * {@link #SEPARATOR} and the shortest trailing folder path no other same-named open file shares.
     * Null paths (untitled buffers) are ignored; a file whose name is unique keeps its bare name.
     */
    static String titleFor(Path self, Collection<Path> open) {
        if (self == null || self.getFileName() == null) {
            return self == null ? "untitled" : self.toString();
        }
        String name = self.getFileName().toString();
        Path me = self.toAbsolutePath().normalize();
        List<Path> rivals = new ArrayList<>();
        for (Path p : open) {
            if (p == null
                    || p.getFileName() == null
                    || !name.equals(p.getFileName().toString())) {
                continue;
            }
            Path other = p.toAbsolutePath().normalize();
            if (!other.equals(me)) {
                rivals.add(other);
            }
        }
        if (rivals.isEmpty()) {
            return name;
        }
        Path myDir = me.getParent();
        for (int depth = 1; depth <= MAX_SEGMENTS; depth++) {
            String mine = trailing(myDir, depth);
            if (mine == null) {
                break; // shallower than the rivals: nothing more of ours to compare
            }
            boolean distinct = true;
            for (Path rival : rivals) {
                if (mine.equals(trailing(rival.getParent(), depth))) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                return name + SEPARATOR + mine;
            }
        }
        return name + SEPARATOR + trailing(myDir, MAX_SEGMENTS);
    }

    /** The last {@code depth} segments of {@code dir} joined with {@code /}; null when it has fewer. */
    private static @Nullable String trailing(@Nullable Path dir, int depth) {
        if (dir == null || dir.getNameCount() < depth) {
            return null;
        }
        int n = dir.getNameCount();
        StringBuilder sb = new StringBuilder();
        for (int i = n - depth; i < n; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(dir.getName(i));
        }
        return sb.toString();
    }
}
