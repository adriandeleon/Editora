package com.editora.maven;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Finds a pom.xml under a Maven project that is not well-formed XML. lemminx-maven builds the whole workspace's
 * reactor for its coordinate completion, and one malformed pom anywhere in it makes every completion after the
 * first come back with only {@code <!--} and {@code <![CDATA[} — for every pom, with no message (review 2, E3; a
 * pom that is well-formed but an invalid model does no such harm). The Maven server cannot be told to skip it, so
 * the editor names the file instead. Reads files only; call it off the FX thread.
 */
public final class MalformedPoms {

    /** A pom that does not parse: where, and the parser's words. {@code line}/{@code column} 1-based, 0 = unknown. */
    public record Problem(Path file, int line, int column, String message) {}

    /** Folders never walked: build output, dependencies, version control, and hidden folders. */
    private static final Set<String> SKIPPED = Set.of("target", "node_modules", "build", "out", "bin");

    /** How deep below the root the walk goes, and how many poms it reads at most. */
    static final int MAX_DEPTH = 8;

    static final int MAX_POMS = 400;

    /** A pom larger than this is not read (none is; a generated monster is not what breaks completion). */
    static final long MAX_BYTES = 4L * 1024 * 1024;

    private MalformedPoms() {}

    /**
     * The first pom.xml under {@code root} (itself included) that is not well-formed, or null. {@code openText}
     * gives an open file's unsaved text (null for one not open), which is what the server sees for it.
     */
    public static @Nullable Problem firstIn(@Nullable Path root, Function<Path, @Nullable String> openText) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        Problem[] found = new Problem[1];
        int[] read = new int[1];
        try {
            Files.walkFileTree(
                    root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            Path name = dir.getFileName();
                            String n = name == null ? "" : name.toString();
                            if (!dir.equals(root) && (n.startsWith(".") || SKIPPED.contains(n))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!"pom.xml".equals(String.valueOf(file.getFileName())) || attrs.size() > MAX_BYTES) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (++read[0] > MAX_POMS) {
                                return FileVisitResult.TERMINATE;
                            }
                            Problem p = check(file, openText.apply(file));
                            if (p != null) {
                                found[0] = p;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            return found[0];
        }
        return found[0];
    }

    /** {@code pom}'s problem when its text ({@code text}, or the file's when null) is not well-formed; else null. */
    static @Nullable Problem check(Path pom, @Nullable String text) {
        String xml = text;
        if (xml == null) {
            try {
                xml = Files.readString(pom);
            } catch (IOException | RuntimeException e) {
                return null; // unreadable or not UTF-8: not what this looks for
            }
        }
        try {
            PomDom.parse(xml);
            return null;
        } catch (PomDom.Failure f) {
            String why = String.valueOf(f.getMessage());
            // a DOCTYPE is refused by this parser's hardening, not by XML: such a pom is well-formed
            return why.contains("DOCTYPE") ? null : new Problem(pom, f.line, f.column, why);
        }
    }
}
