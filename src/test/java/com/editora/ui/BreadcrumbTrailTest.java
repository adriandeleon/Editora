package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BreadcrumbTrailTest {

    private static List<String> labels(Path file, Path home) {
        return BreadcrumbTrail.of(file, home).stream()
                .map(BreadcrumbTrail.Crumb::label)
                .toList();
    }

    @Test
    void aPathOutsideHomeIsSplitSegmentByStartingAfterTheRoot() {
        assertEquals(
                List.of("etc", "hosts"),
                labels(Path.of("/etc/hosts"), Path.of("/home/adl")),
                "the filesystem root is not a crumb of its own");
    }

    @Test
    void homeCollapsesToASingleTildeCrumb() {
        assertEquals(
                List.of("~", "src", "Editora", "pom.xml"),
                labels(Path.of("/home/adl/src/Editora/pom.xml"), Path.of("/home/adl")));
    }

    @Test
    void theTildeCrumbStillNavigatesToTheHomeDirectoryItself() {
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(Path.of("/home/adl/src/notes.md"), Path.of("/home/adl"));
        assertEquals(Path.of("/home/adl"), trail.get(0).path(), "clicking ~ must open the home folder");
        assertEquals(Path.of("/home/adl/src"), trail.get(1).path(), "later crumbs keep their real paths");
    }

    @Test
    void homeItselfIsJustTheTildeCrumb() {
        assertEquals(List.of("~"), labels(Path.of("/home/adl"), Path.of("/home/adl")));
    }

    @Test
    void aSiblingWhoseNameMerelyStartsWithHomeIsNotCollapsed() {
        // Component-wise containment, not a string prefix: /home/adloff is not inside /home/adl.
        assertEquals(
                List.of("home", "adloff", "notes.md"), labels(Path.of("/home/adloff/notes.md"), Path.of("/home/adl")));
    }

    @Test
    void noHomeMeansNoCollapse() {
        assertEquals(List.of("home", "adl", "notes.md"), labels(Path.of("/home/adl/notes.md"), null));
    }

    @Test
    void aBareRootStillYieldsOneCrumb() {
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(Path.of("/"), Path.of("/home/adl"));
        assertEquals(1, trail.size(), "a root has no name elements to walk, but the bar must show something");
        assertEquals(Path.of("/"), trail.get(0).path());
        assertEquals("/", trail.get(0).label());
    }

    private static List<String> labels(Path file, Path home, Path project) {
        return BreadcrumbTrail.of(file, home, project).stream()
                .map(BreadcrumbTrail.Crumb::label)
                .toList();
    }

    @Test
    void aFileInsideTheProjectStartsAtTheProjectRoot() {
        Path home = Path.of("/home/adl");
        Path project = Path.of("/home/adl/src/Editora");
        assertEquals(
                List.of("Editora", "src", "main", "App.java"),
                labels(Path.of("/home/adl/src/Editora/src/main/App.java"), home, project),
                "the trail starts at the project folder, not at ~");
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(Path.of("/home/adl/src/Editora/pom.xml"), home, project);
        assertEquals(project, trail.get(0).path(), "the first crumb navigates to the project root itself");
        assertEquals(Path.of("/home/adl/src/Editora/pom.xml"), trail.get(1).path());
        assertEquals(List.of("Editora"), labels(project, home, project), "the root itself is one crumb");
    }

    @Test
    void aFileOutsideTheProjectKeepsTheFullTrail() {
        Path home = Path.of("/home/adl");
        Path project = Path.of("/home/adl/src/Editora");
        assertEquals(List.of("etc", "hosts"), labels(Path.of("/etc/hosts"), home, project));
        assertEquals(
                List.of("~", "src", "Editora-notes", "todo.md"),
                labels(Path.of("/home/adl/src/Editora-notes/todo.md"), home, project),
                "a sibling whose name merely starts with the project's is outside it");
        assertEquals(
                List.of("~", "src", "Editora", "pom.xml"),
                labels(Path.of("/home/adl/src/Editora/pom.xml"), home, null),
                "no project: today's behaviour");
    }
}
