package com.editora.maven;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Finds the poms under a Maven server's root that are not well-formed XML. lemminx-maven builds its workspace from
 * the files on disk (open tabs' unsaved text plays no part), and one malformed pom in it makes every coordinate
 * completion after the first come back with only {@code <!--} and {@code <![CDATA[} — for every pom, with no
 * message (review 2, E3). The editor takes such a pom out of the server's workspace (see {@code PomServerSupport}).
 * A well-formed pom does the same harm only when Maven cannot build its model and its strict re-read fails too
 * ({@link #unbuildable}, review 4, E31): that one is named, never left out. The walk is lemminx-maven 0.12.0's own
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
        return scan(root).malformed();
    }

    /**
     * What one walk of a Maven server's root found: the poms not well-formed ({@code malformed}), the well-formed
     * ones lemminx-maven's strict model reader refuses ({@code unbuildable}, see {@link #unbuildable}), both in walk
     * order, and every workspace pom's last-modified time in milliseconds ({@code modified}: what tells whether a pom
     * changed since a server started, review 4, E31).
     */
    public record Scan(List<Problem> malformed, List<Unbuildable> unbuildable, Map<Path, Long> modified) {
        public Scan {
            malformed = List.copyOf(malformed);
            unbuildable = List.copyOf(unbuildable);
            modified = Map.copyOf(modified);
        }

        /** Whether a pom here was modified after {@code millis} (a server's start). */
        public boolean changedAfter(long millis) {
            return modified.values().stream().anyMatch(t -> t > millis);
        }
    }

    /** Walks {@code root} as lemminx-maven does ({@link #allIn}) and reports what {@link Scan} holds. */
    public static Scan scan(@Nullable Path root) {
        List<Problem> malformed = new ArrayList<>();
        List<Unbuildable> unbuildable = new ArrayList<>();
        Map<Path, Long> modified = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return new Scan(malformed, unbuildable, modified);
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
                            if (isWorkspacePom(file) && attrs.isRegularFile() && attrs.size() <= MAX_BYTES) {
                                modified.put(file, attrs.lastModifiedTime().toMillis());
                                String text = read(file);
                                Problem p = text == null ? null : check(file, text);
                                if (p != null) {
                                    malformed.add(p);
                                } else if (text != null) {
                                    Unbuildable u = unbuildable(file, text);
                                    if (u != null) {
                                        unbuildable.add(u);
                                    }
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
            return new Scan(malformed, unbuildable, modified);
        }
        return new Scan(malformed, unbuildable, modified);
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null; // unreadable or not UTF-8: lemminx-maven cannot read it either, so never adds it
        }
    }

    /** Why a well-formed pom is {@linkplain #unbuildable unbuildable}. */
    public enum Why {
        /** A DOCTYPE with an internal subset (entities): Maven's reader takes no DTD. */
        ENTITIES,
        /** A child of {@code <project>} that appears twice ({@code element}). */
        DUPLICATE,
        /** A child of {@code <project>} that the POM 4.0.0 model does not have ({@code element}). */
        UNKNOWN
    }

    /** A well-formed pom Maven's strict reader refuses: where ({@code line} 1-based, 0 = unknown), why, which tag. */
    public record Unbuildable(Path file, int line, Why why, String element, boolean hasParent) {}

    /** The elements a POM 4.0.0 {@code <project>} may hold (Maven 3.9's {@code Model}). */
    static final Set<String> PROJECT_CHILDREN = Set.of(
            "modelVersion",
            "parent",
            "groupId",
            "artifactId",
            "version",
            "packaging",
            "name",
            "description",
            "url",
            "inceptionYear",
            "organization",
            "licenses",
            "developers",
            "contributors",
            "mailingLists",
            "prerequisites",
            "modules",
            "scm",
            "issueManagement",
            "ciManagement",
            "distributionManagement",
            "properties",
            "dependencyManagement",
            "dependencies",
            "repositories",
            "pluginRepositories",
            "build",
            "reports",
            "reporting",
            "profiles");

    /** A DOCTYPE declaration, and whether it opens an internal subset ({@code [}). */
    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE\\b[^>\\[]*(\\[)?");

    /**
     * Why lemminx-maven may hold a null project for well-formed {@code text} (review 4, E31): when Maven cannot build
     * a pom's effective model (an unresolvable parent, offline) lemminx-maven re-reads it with Maven's strict reader,
     * and that refuses a DOCTYPE that declares entities, a {@code <project>} child given twice, and one the model does
     * not have. Only {@code <project>}'s own children are checked: cheap, and what names the likely pom. Null when
     * none of these is found; a pom that is not a {@code <project>} (lemminx-maven ignores it) is never named. Pure.
     */
    public static @Nullable Unbuildable unbuildable(Path pom, @Nullable String text) {
        if (text == null) {
            return null;
        }
        String xml = text;
        Matcher dt = DOCTYPE.matcher(xml);
        if (dt.find()) {
            if (dt.group(1) != null) {
                return new Unbuildable(pom, lineAt(xml, dt.start()), Why.ENTITIES, "DOCTYPE", hasParent(xml));
            }
            // a plain DOCTYPE does Maven no harm; blank it out (keeping the lines) so the parser here reads the rest
            int end = xml.indexOf('>', dt.end());
            if (end < 0) {
                return null;
            }
            xml = xml.substring(0, dt.start()) + " ".repeat(end + 1 - dt.start()) + xml.substring(end + 1);
        }
        org.w3c.dom.Document doc;
        try {
            doc = PomDom.parse(xml);
        } catch (PomDom.Failure f) {
            return null;
        }
        org.w3c.dom.Element project = doc.getDocumentElement();
        if (project == null || !"project".equals(localName(project))) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        boolean parent = false;
        Unbuildable found = null;
        for (org.w3c.dom.Node n = project.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof org.w3c.dom.Element el) {
                String name = localName(el);
                parent |= name.equals("parent");
                if (found == null && !PROJECT_CHILDREN.contains(name)) {
                    found = new Unbuildable(pom, PomDom.lineOf(el), Why.UNKNOWN, name, false);
                } else if (found == null && !seen.add(name)) {
                    found = new Unbuildable(pom, PomDom.lineOf(el), Why.DUPLICATE, name, false);
                }
            }
        }
        return found == null ? null : new Unbuildable(found.file(), found.line(), found.why(), found.element(), parent);
    }

    private static boolean hasParent(String xml) {
        return xml.contains("<parent>") || xml.contains("<parent ");
    }

    private static String localName(org.w3c.dom.Element el) {
        String q = el.getTagName();
        int colon = q.indexOf(':');
        return colon < 0 ? q : q.substring(colon + 1);
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
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
