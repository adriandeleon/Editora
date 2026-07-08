package com.editora.maven;

import java.util.List;

/**
 * A pom.xml, parsed literally (no parent inheritance, no default-lifecycle-binding resolution, no
 * {@code <pluginManagement>}) — exactly what {@link PomParser} can read directly off the file. This is
 * deliberately not an "effective pom": {@code Execution.phase()} is the phase text as written (or {@code
 * ""} when a plugin relies on its own implicit default phase), and a plugin declared only for
 * configuration (no {@code <executions>}) still appears with an empty {@link Plugin#executions()} list.
 *
 * <p>{@code <build>/<pluginManagement>/<plugins>} is intentionally not parsed: those entries contribute no
 * runnable goal unless redeclared in {@code <plugins>}, and modeling inherited-vs-declared plugin identity
 * correctly would need real Maven model resolution — out of scope for a pom.xml-only parser.
 */
public record PomModel(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        List<Plugin> plugins,
        List<Profile> profiles) {

    /** A {@code <build>/<plugins>/<plugin>} (top-level or profile-scoped). */
    public record Plugin(String groupId, String artifactId, String version, List<Execution> executions) {}

    /** One {@code <execution>}: its id, the literal bound phase (or {@code ""}), and its goals. */
    public record Execution(String id, String phase, List<String> goals) {}

    /** A {@code <profiles>/<profile>}: its id, whether {@code activation/activeByDefault} is {@code true},
     *  and its own {@code <build>/<plugins>} (not merged into the top-level {@link PomModel#plugins()}). */
    public record Profile(String id, boolean activeByDefault, List<Plugin> plugins) {}
}
