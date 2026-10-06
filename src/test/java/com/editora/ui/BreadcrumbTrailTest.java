package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BreadcrumbTrailTest {

    /**
     * An absolute path on every platform. {@code /proj/a.txt} is absolute on Unix; on Windows a leading slash
     * alone names no drive, and the code under test works with (and returns) absolute paths.
     */
    private static Path p(String path) {
        return Path.of(path).toAbsolutePath();
    }

    private static List<String> labels(Path file, Path home) {
        return BreadcrumbTrail.of(file, home).stream()
                .map(BreadcrumbTrail.Crumb::label)
                .toList();
    }

    @Test
    void aPathOutsideHomeIsSplitSegmentByStartingAfterTheRoot() {
        assertEquals(
                List.of("etc", "hosts"),
                labels(p("/etc/hosts"), p("/home/adl")),
                "the filesystem root is not a crumb of its own");
    }

    @Test
    void homeCollapsesToASingleTildeCrumb() {
        assertEquals(
                List.of("~", "src", "Editora", "pom.xml"), labels(p("/home/adl/src/Editora/pom.xml"), p("/home/adl")));
    }

    @Test
    void theTildeCrumbStillNavigatesToTheHomeDirectoryItself() {
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(p("/home/adl/src/notes.md"), p("/home/adl"));
        assertEquals(p("/home/adl"), trail.get(0).path(), "clicking ~ must open the home folder");
        assertEquals(p("/home/adl/src"), trail.get(1).path(), "later crumbs keep their real paths");
    }

    @Test
    void homeItselfIsJustTheTildeCrumb() {
        assertEquals(List.of("~"), labels(p("/home/adl"), p("/home/adl")));
    }

    @Test
    void aSiblingWhoseNameMerelyStartsWithHomeIsNotCollapsed() {
        // Component-wise containment, not a string prefix: /home/adloff is not inside /home/adl.
        assertEquals(List.of("home", "adloff", "notes.md"), labels(p("/home/adloff/notes.md"), p("/home/adl")));
    }

    @Test
    void noHomeMeansNoCollapse() {
        assertEquals(List.of("home", "adl", "notes.md"), labels(p("/home/adl/notes.md"), null));
    }

    @Test
    void aBareRootStillYieldsOneCrumb() {
        Path root = p("/"); // "/" on Unix, the current drive's root on Windows
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(root, p("/home/adl"));
        assertEquals(1, trail.size(), "a root has no name elements to walk, but the bar must show something");
        assertEquals(root, trail.get(0).path());
        assertEquals(root.toString(), trail.get(0).label());
    }

    private static List<String> labels(Path file, Path home, Path project) {
        return BreadcrumbTrail.of(file, home, project).stream()
                .map(BreadcrumbTrail.Crumb::label)
                .toList();
    }

    @Test
    void aFileInsideTheProjectStartsAtTheProjectRoot() {
        Path home = p("/home/adl");
        Path project = p("/home/adl/src/Editora");
        assertEquals(
                List.of("Editora", "src", "main", "App.java"),
                labels(p("/home/adl/src/Editora/src/main/App.java"), home, project),
                "the trail starts at the project folder, not at ~");
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(p("/home/adl/src/Editora/pom.xml"), home, project);
        assertEquals(project, trail.get(0).path(), "the first crumb navigates to the project root itself");
        assertEquals(p("/home/adl/src/Editora/pom.xml"), trail.get(1).path());
        assertEquals(List.of("Editora"), labels(project, home, project), "the root itself is one crumb");
    }

    @Test
    void aFileOutsideTheProjectKeepsTheFullTrail() {
        Path home = p("/home/adl");
        Path project = p("/home/adl/src/Editora");
        assertEquals(List.of("etc", "hosts"), labels(p("/etc/hosts"), home, project));
        assertEquals(
                List.of("~", "src", "Editora-notes", "todo.md"),
                labels(p("/home/adl/src/Editora-notes/todo.md"), home, project),
                "a sibling whose name merely starts with the project's is outside it");
        assertEquals(
                List.of("~", "src", "Editora", "pom.xml"),
                labels(p("/home/adl/src/Editora/pom.xml"), home, null),
                "no project: today's behaviour");
    }
}
