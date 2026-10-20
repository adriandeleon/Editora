package com.editora.maven;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves the latest stable version of each artifact from a Maven repository's metadata.
 *
 * <p>The fetch is injected rather than performed here, so the decision logic — which coordinates to ask
 * about, what counts as an answer, what a failure means — is testable without a network. The caller supplies
 * something that turns a repository-relative path into the file's text, or null when it cannot be had.
 *
 * <p>Exists because {@code versions-maven-plugin} rewrites dependency versions but not <b>plugin</b> ones:
 * its in-place goals expose {@code processDependencies}, {@code processDependencyManagement} and
 * {@code processParent}, and for plugins it offers only {@code display-plugin-updates}. Verified against
 * 2.21.0 rather than assumed.
 */
public final class CentralVersions {

    /** Maven Central's repository root. HTTPS only — this is code that will be compiled and run. */
    public static final String CENTRAL = "https://repo1.maven.org/maven2/";

    private CentralVersions() {}

    /**
     * The latest stable version for each {@code groupId:artifactId}, skipping any that cannot be resolved.
     *
     * <p>Skipping rather than failing: an artifact that is missing, unreachable, or has only pre-releases
     * simply keeps the version the archetype wrote. A partial update is useful; an aborted one is not, and
     * one unreachable coordinate should not cost the other six their update.
     *
     * @param coordinates {@code groupId:artifactId} strings
     * @param fetch repository-relative path → file contents, or null when unavailable
     */
    public static Map<String, String> latest(Iterable<String> coordinates, Function<String, String> fetch) {
        Map<String, String> out = new LinkedHashMap<>();
        if (coordinates == null || fetch == null) {
            return out;
        }
        for (String ga : coordinates) {
            String[] parts = split(ga);
            if (parts == null) {
                continue;
            }
            String xml;
            try {
                xml = fetch.apply(MavenMetadata.metadataPath(parts[0], parts[1]));
            } catch (RuntimeException e) {
                continue; // one bad coordinate must not sink the rest
            }
            String latest = MavenMetadata.latestStable(xml);
            if (latest != null) {
                out.put(ga, latest);
            }
        }
        return out;
    }

    /**
     * Drops entries whose resolved version is not actually newer than what the pom already has.
     *
     * <p>Without this an "update" can silently <em>downgrade</em>: a pom pinned to a milestone the archetype
     * chose deliberately, or to a version newer than the latest stable one, would be rewritten backwards.
     * "Newer" is {@link MavenVersion}'s — Maven's own rules, under which a number beats any qualifier — and,
     * belt and braces, the candidate's leading number must not be below the current one: whatever a
     * comparator makes of {@code r09} against {@code 33.0.0-jre}, no version that starts lower is an upgrade.
     *
     * @param current {@code groupId:artifactId} → the version in the pom today
     * @param latest {@code groupId:artifactId} → the newest stable version found
     */
    public static Map<String, String> upgradesOnly(Map<String, String> current, Map<String, String> latest) {
        Map<String, String> out = new LinkedHashMap<>();
        if (current == null || latest == null) {
            return out;
        }
        latest.forEach((ga, next) -> {
            String now = current.get(ga);
            if (now != null && isUpgrade(now, next)) {
                out.put(ga, next);
            }
        });
        return out;
    }

    /**
     * Strictly newer by Maven's ordering, and not starting with a smaller number. Pure — unit-tested.
     *
     * <p>A current version that is not a plain version — a property reference ({@code ${surefire.version}}),
     * a range ({@code [2.0,3.0)}), {@code LATEST}/{@code RELEASE} — is never "upgraded": {@code PomEdits}
     * would write a literal over the indirection or range the author chose. The semver comparator used to
     * drop these by accident (a letter outranked every number); now it is deliberate.
     */
    static boolean isUpgrade(String now, String next) {
        if (!isPlainVersion(now) || !isPlainVersion(next)) {
            return false;
        }
        if (!MavenVersion.isNewer(next, now)) {
            return false;
        }
        long nowLeading = MavenVersion.leadingNumber(now);
        long nextLeading = MavenVersion.leadingNumber(next);
        return nowLeading < 0 || nextLeading >= nowLeading;
    }

    /** A literal version: no property reference, no range brackets or list, no {@code LATEST}/{@code RELEASE}. */
    static boolean isPlainVersion(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String s = v.strip();
        char c = s.charAt(0);
        if (c == '[' || c == '(' || s.contains("${") || s.contains(",")) {
            return false;
        }
        return !s.equalsIgnoreCase("LATEST") && !s.equalsIgnoreCase("RELEASE");
    }

    private static String[] split(String ga) {
        if (ga == null) {
            return null;
        }
        int colon = ga.indexOf(':');
        if (colon <= 0 || colon == ga.length() - 1) {
            return null;
        }
        return new String[] {ga.substring(0, colon), ga.substring(colon + 1)};
    }
}
