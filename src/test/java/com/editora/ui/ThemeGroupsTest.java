package com.editora.ui;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeGroupsTest {

    @Test
    void themesAreGroupedLightThenDarkThenTheUsersOwnInCatalogueOrder() {
        List<String> names = List.of("A Light", "A Dark", "Dracula", "B Light", "B Dark", "Yacht", "Mine", "Mine Dark");
        Set<String> dark = Set.of("A Dark", "Dracula", "B Dark", "Mine Dark");
        assertEquals(
                List.of(
                        ThemeGroups.LIGHT,
                        "A Light",
                        "B Light",
                        "Yacht",
                        ThemeGroups.DARK,
                        "A Dark",
                        "Dracula",
                        "B Dark",
                        ThemeGroups.USER,
                        "Mine",
                        "Mine Dark"),
                ThemeGroups.grouped(names, dark::contains, Set.of("Mine", "Mine Dark")));
        assertEquals(
                List.of(ThemeGroups.DARK, "Dracula"),
                ThemeGroups.grouped(List.of("Dracula"), n -> true, Set.of()),
                "an empty group has no heading");
        assertTrue(ThemeGroups.isHeading(ThemeGroups.USER));
        assertFalse(ThemeGroups.isHeading("Light"));
        assertFalse(ThemeGroups.isHeading(null));
    }
}
