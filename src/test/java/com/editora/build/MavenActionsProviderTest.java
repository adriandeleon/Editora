package com.editora.build;

import java.util.List;
import java.util.Set;

import com.editora.maven.MavenPluginGoals;
import com.editora.maven.PomModel;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The gap this closes: {@code javafx-maven-plugin} is declared for direct invocation (configuration only,
     * no {@code <executions>}), so the pom-derived Plugins section can offer nothing for it — the tasks tree
     * had no way at all to run {@code javafx:run}. Its descriptor's goals become their own group.
     */
    @Test
    void directlyInvokedPluginGoalsAreOfferedEvenWithNoBoundExecution() {
        PomModel.Plugin javafx = new PomModel.Plugin("org.openjfx", "javafx-maven-plugin", "0.0.8", List.of());
        PomModel model = new PomModel("g", "a", "1.0", "jar", List.of(javafx), List.of());
        var descriptor = new MavenPluginGoals.Descriptor(
                "javafx",
                List.of(new MavenPluginGoals.Goal("run", "Runs the project"), new MavenPluginGoals.Goal("jlink", "")));

        assertFalse(
                taskLabels(new MavenActionsProvider(model).sections(Set.of())).contains("javafx:run"));

        var sections = new MavenActionsProvider(model, List.of(descriptor)).sections(Set.of());
        assertTrue(taskLabels(sections).contains("javafx:run"));
        BuildAction.Section group = sections.get(sections.size() - 1);
        assertEquals("javafx", group.title());
        assertTrue(group.collapsed(), "a full goal list must not bury the lifecycle above it");
        assertEquals(List.of("javafx:run"), ((BuildAction.Task) group.rows().get(0)).args());
        assertEquals("Runs the project", ((BuildAction.Task) group.rows().get(0)).tooltip());
    }

    /** No local repository / nothing downloaded: the tree is exactly what it was before this feature. */
    @Test
    void aModuleOfAReactorGetsTheReactorToggleFirstAndItsArgsBeforeTheProfiles() {
        // An absolute root on every OS (C:\\repo on Windows); the label is compared through tr() on both sides,
        // so the test does not depend on whether another test initialised the message catalog in this JVM.
        java.nio.file.Path repo =
                java.nio.file.Path.of("/repo").toAbsolutePath().normalize();
        var reactor = new com.editora.maven.MavenReactor.Reactor(repo, repo.resolve("pom.xml"), "services/api");
        MavenActionsProvider p = new MavenActionsProvider(pom(), List.of(), reactor, null);
        var sections = p.sections(Set.of());
        assertEquals(tr("mavenpopup.reactor"), sections.get(0).title());
        var toggle = (BuildAction.Toggle) sections.get(0).rows().get(0);
        assertEquals(MavenActionsProvider.REACTOR_TOGGLE, toggle.id());
        assertEquals(tr("mavenpopup.reactorToggle", "repo", "services/api"), toggle.label());
        assertEquals(tr("mavenpopup.lifecycle"), sections.get(1).title(), "the lifecycle follows");

        String rootPom = repo.resolve("pom.xml").toString();
        assertEquals(List.of(), p.toggleArgs(Set.of()));
        assertEquals(
                List.of("-f", rootPom, "-pl", "services/api", "-am"),
                p.toggleArgs(Set.of(MavenActionsProvider.REACTOR_TOGGLE)));
        assertEquals(
                List.of("-f", rootPom, "-pl", "services/api", "-am", "-Pdist"),
                p.toggleArgs(new java.util.LinkedHashSet<>(List.of(MavenActionsProvider.REACTOR_TOGGLE, "dist"))),
                "the reactor switch never leaks into -P");
        // The root pom is named as the machine the build runs on names it (a remote host's path).
        assertEquals(
                List.of("-f", "/srv/repo/pom.xml", "-pl", "services/api", "-am"),
                new MavenActionsProvider(pom(), List.of(), reactor, "/srv/repo/pom.xml")
                        .toggleArgs(Set.of(MavenActionsProvider.REACTOR_TOGGLE)));
    }

    @Test
    void aTopLevelProjectHasNoReactorSection() {
        MavenActionsProvider p = new MavenActionsProvider(pom(), List.of(), null, null);
        assertEquals(tr("mavenpopup.lifecycle"), p.sections(Set.of()).get(0).title());
        assertEquals(
                List.of("-Pdist"),
                p.toggleArgs(Set.of(MavenActionsProvider.REACTOR_TOGGLE, "dist")),
                "an id no provider offered is ignored, not turned into -f/-pl");
    }

    @Test
    void noResolvedDescriptorsLeavesTheSectionsUnchanged() {
        assertEquals(
                new MavenActionsProvider(pom()).sections(Set.of()),
                new MavenActionsProvider(pom(), List.of()).sections(Set.of()));
    }
}
