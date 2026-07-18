package com.editora.ui;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link ScopeGroups#visibleKeys} — which project buckets the Bookmarks/Notes panels show, and in what
 * order, given the "Show all projects" toggle. Names: {@code "" → General}, {@code p1 → Zebra}, {@code p2 → Apple}.
 */
class ScopeGroupsTest {

    private static final Function<String, String> NAME = k -> switch (k) {
        case "" -> "General";
        case "p1" -> "Zebra";
        case "p2" -> "Apple";
        default -> k;
    };

    @Test
    void defaultShowsGeneralThenCurrentProject() {
        // In project p1: General + current project, in that order. Other projects (p2) hidden until "show all".
        assertEquals(List.of("", "p1"), ScopeGroups.visibleKeys(List.of("", "p1", "p2"), "p1", false, NAME));
    }

    @Test
    void noProjectWindowShowsGeneralOnceAsCurrent() {
        // currentKey "" — General IS the current bucket; it must appear exactly once.
        assertEquals(List.of(""), ScopeGroups.visibleKeys(List.of("", "p1"), "", false, NAME));
    }

    @Test
    void showAllAppendsOtherProjectsAlphabeticalByName() {
        // p2 ("Apple") sorts before p1 ("Zebra") by display name, after General + current.
        assertEquals(List.of("", "p2", "p1"), ScopeGroups.visibleKeys(List.of("", "p1", "p2"), "", true, NAME));
    }

    @Test
    void showAllInAProjectKeepsGeneralAndCurrentFirst() {
        assertEquals(List.of("", "p1", "p2"), ScopeGroups.visibleKeys(List.of("", "p1", "p2"), "p1", true, NAME));
    }

    @Test
    void emptyGeneralOrCurrentIsOmitted() {
        // General has no content → only the current project shows.
        assertEquals(List.of("p1"), ScopeGroups.visibleKeys(List.of("p1", "p2"), "p1", false, NAME));
        // Current project has no content → only General shows.
        assertEquals(List.of(""), ScopeGroups.visibleKeys(List.of(""), "p1", false, NAME));
    }
}
