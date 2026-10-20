package com.editora.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an "Update Versions" of one {@code pom.xml} can offer, as a plan the preview renders row by row.
 *
 * <p>Pure, in two halves. {@link #plan} reads the pom once and decides which coordinates are worth asking a
 * repository about and which are not — and, for the ones that are not, <em>why</em>, so the preview can say
 * "3 skipped (property-driven / inherited)" instead of quietly checking a fraction. {@link #rows} then turns
 * the repository's answers into offers the user accepts one by one: a literal dependency or plugin version,
 * a BOM import (flagged, because its bump re-versions everything it manages), the {@code <parent>} version,
 * and a {@code <properties>} entry that is referenced <em>only</em> from {@code <version>} elements — the
 * modern pom's way of pinning, which the literal-only reading used to skip without a word.
 *
 * <p>A property that is also used elsewhere (a plugin configuration, a resource filter) is left alone: the
 * version elements are not the only thing a rewrite would change. A {@code ${project.version}} or any other
 * name the pom's own {@code <properties>} do not declare is not offered either.
 */
public final class PomUpgrades {

    private PomUpgrades() {}

    /** What a row changes when accepted; the preview groups rows by it. */
    public enum Kind {
        DEPENDENCY,
        BOM,
        PLUGIN,
        PARENT,
        PROPERTY
    }

    /**
     * One offer. {@code key} is the {@code groupId:artifactId} for a coordinate row and the property name for a
     * {@link Kind#PROPERTY} row; {@code major} says the leading number changes (JUnit 5 → 6), which deserves
     * a second look where a patch does not.
     */
    public record Row(Kind kind, String key, String current, String next, boolean major) {}

    /**
     * The read of a pom: the coordinates to look up, and the bookkeeping the rows are built from.
     *
     * @param coordinates every {@code groupId:artifactId} worth a repository lookup, in document order
     * @param literal coordinate → the literal version written for it (first site wins)
     * @param boms the coordinates among those that are BOM imports
     * @param plugins the coordinates among those that are plugins
     * @param propertyUsers property name → the coordinates whose {@code <version>} is {@code ${name}}, for
     *     the properties that may be offered
     * @param propertyValues property name → its literal value, for the same properties
     * @param parent the {@code <parent>} as {@code {groupId, artifactId, version}}, or null
     * @param skippedPropertyDriven version sites that reference a property this plan cannot offer
     * @param skippedInherited version sites with no version of their own
     */
    public record Plan(
            List<String> coordinates,
            Map<String, String> literal,
            Set<String> boms,
            Set<String> plugins,
            Map<String, List<String>> propertyUsers,
            Map<String, String> propertyValues,
            String[] parent,
            int skippedPropertyDriven,
            int skippedInherited) {

        /** Whether there is anything at all to ask a repository about. */
        public boolean hasLookups() {
            return !coordinates.isEmpty();
        }

        public boolean hasParent() {
            return parent != null && !parent[2].isEmpty();
        }

        public int skipped() {
            return skippedPropertyDriven + skippedInherited;
        }
    }

    public static Plan plan(String pom) {
        List<PomEdits.VersionSite> sites = PomEdits.versionSites(pom);
        Map<String, String> properties = PomEdits.properties(pom);
        Set<String> coordinates = new LinkedHashSet<>();
        Map<String, String> literal = new LinkedHashMap<>();
        Set<String> boms = new LinkedHashSet<>();
        Set<String> plugins = new LinkedHashSet<>();
        Map<String, List<String>> users = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        int skippedProperty = 0;
        int skippedInherited = 0;
        // A property is offerable when its value is a plain version and every reference to it is a
        // <version> element — counted first, so a property shared with a <configuration> is never offered.
        Map<String, Integer> versionReferences = new LinkedHashMap<>();
        for (PomEdits.VersionSite site : sites) {
            String name = site.propertyName();
            if (name != null) {
                versionReferences.merge(name, 1, Integer::sum);
            }
        }
        for (PomEdits.VersionSite site : sites) {
            if (site.isInherited()) {
                skippedInherited++;
                continue;
            }
            if (site.isPropertyDriven()) {
                String name = site.propertyName();
                String value = name == null ? null : properties.get(name);
                boolean offerable = value != null
                        && CentralVersions.isPlainVersion(value)
                        && PomEdits.referenceCount(pom, name) == versionReferences.getOrDefault(name, 0);
                if (!offerable) {
                    skippedProperty++;
                    continue;
                }
                users.computeIfAbsent(name, k -> new ArrayList<>()).add(site.ga());
                values.put(name, value);
            } else {
                if (!CentralVersions.isPlainVersion(site.version())) {
                    continue; // a range, LATEST/RELEASE: the author's choice, never looked up or rewritten
                }
                literal.putIfAbsent(site.ga(), site.version());
            }
            coordinates.add(site.ga());
            if (site.bomImport()) {
                boms.add(site.ga());
            }
            if (site.isPlugin()) {
                plugins.add(site.ga());
            }
        }
        String[] parent = PomEdits.parent(pom);
        if (parent != null && CentralVersions.isPlainVersion(parent[2])) {
            coordinates.add(parent[0] + ":" + parent[1]);
        }
        return new Plan(
                List.copyOf(coordinates),
                Map.copyOf(literal),
                Set.copyOf(boms),
                Set.copyOf(plugins),
                users,
                values,
                parent,
                skippedProperty,
                skippedInherited);
    }

    /**
     * The offers, given what the repository answered: dependencies first (BOM imports among them), then
     * plugins, then the parent, then properties. Only real upgrades (see {@link CentralVersions#isUpgrade}).
     *
     * @param latest {@code groupId:artifactId} → newest stable version; a coordinate with no answer is
     *     simply not offered
     */
    public static List<Row> rows(Plan plan, Map<String, String> latest) {
        List<Row> out = new ArrayList<>();
        if (plan == null || latest == null) {
            return out;
        }
        List<Row> plugins = new ArrayList<>();
        for (Map.Entry<String, String> e : plan.literal().entrySet()) {
            String ga = e.getKey();
            String next = latest.get(ga);
            if (next == null || !CentralVersions.isUpgrade(e.getValue(), next)) {
                continue;
            }
            Kind kind =
                    plan.plugins().contains(ga) ? Kind.PLUGIN : plan.boms().contains(ga) ? Kind.BOM : Kind.DEPENDENCY;
            (kind == Kind.PLUGIN ? plugins : out)
                    .add(new Row(kind, ga, e.getValue(), next, isMajor(e.getValue(), next)));
        }
        out.addAll(plugins);
        if (plan.hasParent()) {
            String ga = plan.parent()[0] + ":" + plan.parent()[1];
            String now = plan.parent()[2];
            String next = latest.get(ga);
            if (next != null && CentralVersions.isUpgrade(now, next)) {
                out.add(new Row(Kind.PARENT, ga, now, next, isMajor(now, next)));
            }
        }
        for (Map.Entry<String, List<String>> e : plan.propertyUsers().entrySet()) {
            String now = plan.propertyValues().get(e.getKey());
            String next = commonUpgrade(e.getValue(), latest);
            if (now != null && next != null && CentralVersions.isUpgrade(now, next)) {
                out.add(new Row(Kind.PROPERTY, e.getKey(), now, next, isMajor(now, next)));
            }
        }
        return out;
    }

    /**
     * The version every user of a shared property can move to: the <em>lowest</em> of their newest versions,
     * and only when every one of them answered — {@code ${junit.version}} shared by the API and the engine
     * must not be bumped to a number one of them has not published.
     */
    private static String commonUpgrade(List<String> users, Map<String, String> latest) {
        String best = null;
        for (String ga : users) {
            String candidate = latest.get(ga);
            if (candidate == null) {
                return null;
            }
            if (best == null || MavenVersion.isNewer(best, candidate)) {
                best = candidate;
            }
        }
        return best;
    }

    /** Whether the leading number changes — {@code 5.11.0 → 6.1.3}, {@code 3.3.0 → 4.0.1}. */
    public static boolean isMajor(String current, String next) {
        long now = MavenVersion.leadingNumber(current);
        long then = MavenVersion.leadingNumber(next);
        return now >= 0 && then >= 0 && now != then;
    }

    /** Writes the accepted rows into {@code pom}, format-preserving, and returns the new text. */
    public static String apply(String pom, List<Row> accepted) {
        if (pom == null || accepted == null || accepted.isEmpty()) {
            return pom;
        }
        Map<String, String> dependencies = new LinkedHashMap<>();
        Map<String, String> plugins = new LinkedHashMap<>();
        String out = pom;
        for (Row row : accepted) {
            switch (row.kind()) {
                case DEPENDENCY, BOM -> dependencies.put(row.key(), row.next());
                case PLUGIN -> plugins.put(row.key(), row.next());
                case PARENT -> out = PomEdits.setParentVersion(out, row.next());
                case PROPERTY -> out = PomEdits.setProperty(out, row.key(), row.next());
            }
        }
        out = PomEdits.setDependencyVersions(out, dependencies);
        return PomEdits.setPluginVersions(out, plugins);
    }
}
