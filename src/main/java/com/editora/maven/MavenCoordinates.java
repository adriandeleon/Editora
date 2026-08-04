package com.editora.maven;

import java.nio.file.Path;
import java.util.Set;

/**
 * Pure validation and derivation for Maven project coordinates. No toolkit, no I/O — the wizard's decisions
 * live here so they can be unit-tested.
 */
public final class MavenCoordinates {

    /** Maven's own rule for a groupId/artifactId (see maven-artifact's {@code ModelValidator}). */
    private static final String ID = "[A-Za-z0-9_][A-Za-z0-9_\\-.]*";

    /** Reserved words that cannot appear as a Java package segment. */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "_",
            "true",
            "false",
            "null");

    private MavenCoordinates() {}

    public static boolean isValidGroupId(String s) {
        return s != null && s.matches(ID);
    }

    public static boolean isValidArtifactId(String s) {
        return s != null && s.matches(ID);
    }

    public static boolean isValidVersion(String s) {
        return s != null && !s.isBlank() && s.strip().equals(s) && !s.contains("/") && !s.contains("\\");
    }

    /**
     * The package a new project defaults to, the way IDEA derives it: {@code groupId.artifactId}, with every
     * segment sanitised into a legal Java identifier. A groupId already ending in the artifactId is not
     * doubled ({@code com.example} + {@code example} stays {@code com.example}).
     */
    public static String defaultPackage(String groupId, String artifactId) {
        String g = groupId == null ? "" : groupId.strip();
        String a = artifactId == null ? "" : artifactId.strip();
        String combined = g.isEmpty() ? a : (a.isEmpty() || g.endsWith("." + a) || g.equals(a) ? g : g + "." + a);
        StringBuilder out = new StringBuilder();
        for (String raw : combined.split("\\.")) {
            String seg = sanitizeSegment(raw);
            if (seg.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(seg);
        }
        return out.toString();
    }

    /** One package segment → a legal Java identifier, or "" when nothing usable is left. */
    private static String sanitizeSegment(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                // A segment starting with a digit is legal Maven but illegal Java ("2048"), so prefix an
                // underscore and keep going — dropping to a single char would mangle the whole name.
                if (sb.isEmpty() && !Character.isJavaIdentifierStart(c)) {
                    sb.append('_');
                }
                sb.append(c);
            } else if (!sb.isEmpty() && (c == '-' || c == '.')) {
                sb.append('_'); // a dashed artifactId is extremely common: my-app -> my_app
            }
        }
        String s = sb.toString();
        return JAVA_KEYWORDS.contains(s) ? s + "_" : s;
    }

    /**
     * Where {@code archetype:generate} will put the project. Maven creates {@code <cwd>/<artifactId>} — the
     * directory is <em>derived</em>, never chosen, which is why the wizard shows it read-only and refuses to
     * proceed when it already exists.
     */
    public static Path projectDir(Path parentDir, String artifactId) {
        return parentDir == null || artifactId == null || artifactId.isBlank()
                ? null
                : parentDir.resolve(artifactId.strip()).normalize();
    }

    /**
     * Parses a user-typed {@code groupId:artifactId:version} into an archetype, or returns {@code null} when
     * it isn't three valid non-blank parts. Never throws — the caller reports a status.
     */
    public static MavenArchetype parseGav(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.strip().split(":");
        if (parts.length != 3) {
            return null;
        }
        String g = parts[0].strip();
        String a = parts[1].strip();
        String v = parts[2].strip();
        if (!isValidGroupId(g) || !isValidArtifactId(a) || !isValidVersion(v)) {
            return null;
        }
        return new MavenArchetype(g, a, v, "", "", false);
    }
}
