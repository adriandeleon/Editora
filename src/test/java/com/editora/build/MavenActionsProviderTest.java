package com.editora.build;

import java.util.List;
import java.util.Set;

import com.editora.maven.PomModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of the Maven action provider: the lifecycle/profile/plugin sections, the profile-reveal of
 *  nested goals, and the merged {@code -Pa,b} toggle args. */
class MavenActionsProviderTest {

    private static PomModel pom() {
        PomModel.Plugin spotless = new PomModel.Plugin(
                "com.diffplug.spotless",
                "spotless-maven-plugin",
                "2.0",
                List.of(new PomModel.Execution("spotless-check", "verify", List.of("check"))));
        PomModel.Plugin distPlugin = new PomModel.Plugin(
                "org.apache.maven.plugins",
                "maven-antrun-plugin",
                "3.0",
                List.of(new PomModel.Execution("stamp", "package", List.of("run"))));
        PomModel.Profile dist = new PomModel.Profile("dist", false, List.of(distPlugin));
        return new PomModel("g", "a", "1.0", "jar", List.of(spotless), List.of(dist));
    }

    private static List<String> taskLabels(List<BuildAction.Section> sections) {
        return sections.stream()
                .flatMap(s -> s.rows().stream())
                .filter(r -> r instanceof BuildAction.Task)
                .map(r -> ((BuildAction.Task) r).label())
                .toList();
    }

    @Test
    void lifecyclePhasesProfilesAndPluginGoalsAreOffered() {
        var p = new MavenActionsProvider(pom());
        var sections = p.sections(Set.of());
        List<String> labels = taskLabels(sections);
        assertTrue(labels.contains("package"), "lifecycle phase present");
        assertTrue(labels.contains("install"), "lifecycle phase present");
        assertTrue(labels.contains("spotless:check"), "top-level plugin goal (prefix:goal) present");
        // The dist profile is a Toggle, not a Task.
        boolean hasDistToggle = sections.stream()
                .flatMap(s -> s.rows().stream())
                .anyMatch(r -> r instanceof BuildAction.Toggle t && t.id().equals("dist"));
        assertTrue(hasDistToggle, "profile rendered as a toggle");
        // The dist profile's nested goal (antrun:run) is hidden until the profile is checked.
        assertTrue(!labels.contains("antrun:run"), "nested profile goal hidden when profile inactive");
    }

    @Test
    void checkingAProfileRevealsItsNestedGoals() {
        var p = new MavenActionsProvider(pom());
        int before = p.sections(Set.of()).size();
        var after = p.sections(Set.of("dist"));
        assertTrue(after.size() > before, "an extra section appears when the profile is active");
        assertTrue(taskLabels(after).contains("antrun:run"), "the profile's nested goal is now offered");
    }

    @Test
    void activeProfilesMergeIntoASingleDashPFlag() {
        var p = new MavenActionsProvider(pom());
        assertEquals(List.of(), p.toggleArgs(Set.of()));
        assertEquals(List.of("-Pdist"), p.toggleArgs(Set.of("dist")));
        assertEquals(
                List.of("-Pdist,release"), p.toggleArgs(new java.util.LinkedHashSet<>(List.of("dist", "release"))));
    }
}
