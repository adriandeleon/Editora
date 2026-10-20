package com.editora.maven;

import java.util.List;
import java.util.Map;

/**
 * A pom.xml, parsed literally (no default-lifecycle-binding resolution) — what {@link PomParser} reads directly
 * off one file, or what {@link PomInheritance} merges from a file and the {@code <parent>} chain found beside
 * it on disk. This is deliberately not an "effective pom": {@code Execution.phase()} is the phase text as
 * written (or {@code ""} when a plugin relies on its own implicit default phase), and a plugin declared only
 * for configuration (no {@code <executions>}) still appears with an empty {@link Plugin#executions()} list.
 *
 * <p>{@code <build>/<pluginManagement>/<plugins>} is read into {@link #managedPlugins()}, kept apart from
 * {@link #plugins()}: a managed entry contributes no bound execution, but its goals are still invokable by
 * name ({@code mvn enforcer:enforce} works wherever the plugin is managed), so the tasks tree lists them as a
 * "managed" goal group.
 *
 * @param parent the {@code <parent>} element as written, or {@code null}
 * @param properties {@code <properties>} in document order (a merged model holds the chain's, child winning)
 * @param managedPlugins {@code <build>/<pluginManagement>/<plugins>}
 * @param inheritedFrom the artifactIds of the parent poms merged into this model, nearest first; empty for a
 *     literal single-file read
 */
public record PomModel(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        List<Plugin> plugins,
        List<Profile> profiles,
        Parent parent,
        Map<String, String> properties,
        List<Plugin> managedPlugins,
        List<String> inheritedFrom) {

    /** The literal single-file shape: no parent, no properties, no managed plugins, nothing inherited. */
    public PomModel(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            List<Plugin> plugins,
            List<Profile> profiles) {
        this(groupId, artifactId, version, packaging, plugins, profiles, null, Map.of(), List.of(), List.of());
    }

    /** A model with nothing in it — what a build file that failed to parse is stood in for (its lifecycle
     *  phases are still runnable; Maven itself then says what is wrong with the file). */
    public static PomModel empty() {
        return new PomModel("", "", "", "jar", List.of(), List.of());
    }

    /** A {@code <build>/<plugins>/<plugin>} (top-level or profile-scoped). */
    public record Plugin(String groupId, String artifactId, String version, List<Execution> executions) {
        /** The {@code groupId:artifactId} key two declarations are matched on when a child pom overrides its
         *  parent's. */
        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    /** One {@code <execution>}: its id, the literal bound phase (or {@code ""}), and its goals. */
    public record Execution(String id, String phase, List<String> goals) {}

    /**
     * A {@code <profiles>/<profile>}: its id, whether {@code activation/activeByDefault} is {@code true}, its
     * own {@code <build>/<plugins>} (not merged into the top-level {@link PomModel#plugins()}), and the other
     * {@code <activation>} conditions as written (jdk, os, property, file).
     */
    public record Profile(String id, boolean activeByDefault, List<Plugin> plugins, List<Activation> activations) {
        public Profile(String id, boolean activeByDefault, List<Plugin> plugins) {
            this(id, activeByDefault, plugins, List.of());
        }
    }

    /** One {@code <activation>} condition other than {@code activeByDefault}: its kind and the value as written
     *  ({@code [25,)} for a jdk range, {@code env.CI} or {@code env.CI=true} for a property, a path for a file). */
    public record Activation(Kind kind, String value) {
        public enum Kind {
            JDK,
            OS,
            PROPERTY,
            FILE_EXISTS,
            FILE_MISSING
        }
    }

    /** The {@code <parent>} element: coordinates plus {@code <relativePath>} as written ({@code ""} = Maven's
     *  default {@code ../pom.xml}). */
    public record Parent(String groupId, String artifactId, String version, String relativePath) {}
}
