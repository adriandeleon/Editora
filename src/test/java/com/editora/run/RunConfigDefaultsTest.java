package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigDefaultsTest {

    @Test
    void namesAConfigurationAfterItsClass() {
        assertEquals("App", RunConfigDefaults.nameFor("com.example.App", "New"));
        assertEquals("App", RunConfigDefaults.nameFor("App", "New"), "a default-package class");
    }

    @Test
    void fallsBackWhenThereIsNothingToNameItAfter() {
        assertEquals("New", RunConfigDefaults.nameFor(null, "New"));
        assertEquals("New", RunConfigDefaults.nameFor("", "New"));
        assertEquals("New", RunConfigDefaults.nameFor("   ", "New"));
        assertEquals("New", RunConfigDefaults.nameFor("com.example.", "New"), "a trailing dot names nothing");
    }

    @Test
    void leavesAnUncontestedNameAlone() {
        assertEquals("App", RunConfigDefaults.uniqueName("App", List.of("Server", "Client")));
        assertEquals("App", RunConfigDefaults.uniqueName("App", List.of()));
        assertEquals("App", RunConfigDefaults.uniqueName("App", null));
    }

    /**
     * The collision that matters: each configuration registers a {@code run.config.<slug>} command keyed on
     * its name, so a duplicate name silently overwrites the other's command.
     */
    @Test
    void disambiguatesACollidingName() {
        assertEquals("App (2)", RunConfigDefaults.uniqueName("App", List.of("App")));
        assertEquals("App (3)", RunConfigDefaults.uniqueName("App", List.of("App", "App (2)")));
    }

    /** The slug is case-insensitive, so the uniqueness check has to be too, or the ids still collide. */
    @Test
    void comparesCaseInsensitivelyBecauseTheSlugDoes() {
        assertEquals("App (2)", RunConfigDefaults.uniqueName("App", List.of("app")));
        assertEquals("app (2)", RunConfigDefaults.uniqueName("app", List.of("APP")));
    }

    @Test
    void ignoresSurroundingWhitespaceOnBothSides() {
        assertEquals("App (2)", RunConfigDefaults.uniqueName("  App  ", List.of(" App ")));
    }

    /**
     * With a Java file open, Add produces something you can actually run — the shape #795 was about.
     */
    @Test
    void addProducesARunnableConfigurationWhenThereIsSomethingToSuggest() {
        var c = RunConfigDefaults.newConfiguration("com.example.App", List.of(), "New Configuration");
        assertEquals("App", c.name(), "named after the class, not \"New Configuration\"");
        assertEquals("com.example.App", c.mainClass());
        assertEquals("java", c.type());
        assertFalse(c.missingMainClass(), "runnable straight away");
    }

    /** Nothing to suggest: blank as before, so Add still works with no Java file in front. */
    @Test
    void addStillProducesABlankConfigurationWithNothingToSuggest() {
        var c = RunConfigDefaults.newConfiguration(null, List.of(), "New Configuration");
        assertEquals("New Configuration", c.name());
        assertEquals("", c.mainClass());
        assertTrue(c.missingMainClass(), "and the #795 guard still reports it clearly");
    }

    /** Adding twice from the same file is the case that would otherwise collide two command ids into one. */
    @Test
    void addingTwiceFromTheSameFileDoesNotCollide() {
        var first = RunConfigDefaults.newConfiguration("com.example.App", List.of(), "New Configuration");
        var second = RunConfigDefaults.newConfiguration("com.example.App", List.of(first.name()), "New Configuration");
        assertEquals("App", first.name());
        assertEquals("App (2)", second.name());
        assertNotEquals(
                com.editora.config.RunConfiguration.commandIdFor(first.name()),
                com.editora.config.RunConfiguration.commandIdFor(second.name()),
                "distinct command ids, so neither overwrites the other in the palette");
    }

    @Test
    void toleratesNullsInTheExistingNames() {
        assertEquals("App", RunConfigDefaults.uniqueName("App", java.util.Arrays.asList("Server", null)));
    }
}
