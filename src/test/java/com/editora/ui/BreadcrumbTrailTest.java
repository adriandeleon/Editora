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
}
