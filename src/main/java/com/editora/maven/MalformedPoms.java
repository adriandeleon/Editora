package com.editora.maven;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Finds the poms under a Maven server's root that are not well-formed XML. lemminx-maven builds its workspace from
 * the files on disk (open tabs' unsaved text plays no part), and one malformed pom in it makes every coordinate
 * completion after the first come back with only {@code <!--} and {@code <![CDATA[} — for every pom, with no
 * message (review 2, E3; a pom that is well-formed but an invalid model does no such harm). The editor takes such a
 * pom out of the server's workspace (see {@code PomServerSupport}). The walk is lemminx-maven 0.12.0's own
 * ({@code MavenLemminxExtension.computeAddedWorkspaceProjects} and {@code match}): ten levels deep, skipping only
 * dot-folders and {@code target}, reading {@code pom*.xml}, {@code *pom.xml} and {@code *.pom} (review 3, E22).
 * Reads files only; call it off the FX thread.
 */
public final class MalformedPoms {

    /** A pom that does not parse: where, and the parser's words. {@code line}/{@code column} 1-based, 0 = unknown. */
    public record Problem(Path file, int line, int column, String message) {}

    /** How deep below the root the walk goes: lemminx-maven's {@code walkFileTree} depth. */
    static final int MAX_DEPTH = 10;

    /** A pom larger than this is not read (none is; a generated monster is not what breaks completion). */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private MalformedPoms() {}

    /**
     * Whether lemminx-maven treats {@code file} as a Maven project: its {@code match(Path)} — a name that starts with
     * {@code pom} and ends with {@code .xml}, or ends with {@code pom.xml} or {@code .pom}. Pure.
     */
    public static boolean isWorkspacePom(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString();
        return (n.startsWith("pom") && n.endsWith(".xml")) || n.endsWith("pom.xml") || n.endsWith(".pom");
    }

    /** Whether lemminx-maven's walk skips {@code dir} and everything under it: a dot-folder or {@code target}. */
    static boolean skipped(Path dir) {
        Path name = dir.getFileName();
        String n = name == null ? "" : name.toString();
        return n.startsWith(".") || n.equals("target");
    }

    /**
     * Every workspace pom under {@code root} (as lemminx-maven walks it, the root itself included) whose text on disk
     * is not well-formed, in walk order.
     */
    public static List<Problem> allIn(@Nullable Path root) {
        List<Problem> found = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return found;
        }
        try {
            Files.walkFileTree(
                    root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // the root too, as lemminx-maven does: a workspace folder named .x or target adds nothing
                            return skipped(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (isWorkspacePom(file) && attrs.size() <= MAX_BYTES) {
                                Problem p = check(file, null);
                                if (p != null) {
                                    found.add(p);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            return found;
        }
        return found;
    }

    /** {@code pom}'s problem when its text ({@code text}, or the file's when null) is not well-formed; else null. */
    public static @Nullable Problem check(Path pom, @Nullable String text) {
        String xml = text;
        if (xml == null) {
            try {
                if (!Files.isRegularFile(pom)) {
                    return null; // gone, or a folder at the walk's last level
                }
                xml = Files.readString(pom);
            } catch (IOException | RuntimeException e) {
                return null; // unreadable or not UTF-8: lemminx-maven cannot read it either, so never adds it
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
