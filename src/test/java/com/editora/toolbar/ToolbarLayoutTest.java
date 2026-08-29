package com.editora.toolbar;

import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.toolbar.ToolbarCatalog.SEPARATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolbarLayoutTest {

    @Test
    void sanitizeDropsUnknownIdsKeepsSeparators() {
        List<String> in = List.of("file.new", "bogus.command", SEPARATOR, "file.save");
        assertEquals(List.of("file.new", SEPARATOR, "file.save"), ToolbarLayout.sanitize(in));
    }

    @Test
    void sanitizeCollapsesLeadingTrailingAndConsecutiveSeparators() {
        List<String> in = List.of(SEPARATOR, "file.new", SEPARATOR, SEPARATOR, "file.save", SEPARATOR);
        assertEquals(List.of("file.new", SEPARATOR, "file.save"), ToolbarLayout.sanitize(in));
    }

    @Test
    void sanitizeDeduplicatesItemsKeepingFirst() {
        List<String> in = List.of("file.new", "file.save", "file.new");
        assertEquals(List.of("file.new", "file.save"), ToolbarLayout.sanitize(in));
    }

    @Test
    void sanitizeHandlesNullAndEmpty() {
        assertEquals(List.of(), ToolbarLayout.sanitize(null));
        assertEquals(List.of(), ToolbarLayout.sanitize(List.of()));
        assertEquals(List.of(), ToolbarLayout.sanitize(List.of(SEPARATOR, SEPARATOR)));
    }

    @Test
    void defaultLayoutSanitizesToItself() {
        List<String> def = ToolbarCatalog.defaultLayout();
        assertEquals(def, ToolbarLayout.sanitize(def));
    }

    @Test
    void moveReordersWithoutMutatingInput() {
        List<String> in = List.of("a", "b", "c");
        List<String> out = ToolbarLayout.move(in, 0, 2);
        assertEquals(List.of("b", "c", "a"), out);
        assertEquals(List.of("a", "b", "c"), in); // input untouched
    }

    @Test
    void moveClampsDestinationAndIgnoresBadSource() {
        assertEquals(List.of("b", "a"), ToolbarLayout.move(List.of("a", "b"), 0, 99));
        assertEquals(List.of("a", "b"), ToolbarLayout.move(List.of("a", "b"), 5, 0));
    }

    /**
     * The upgrade path for a toolbar saved by an older build. The run-configuration group used to be four
     * catalog items in the icon cluster and is now pinned in the fixed tail, so those ids are simply gone
     * from the catalog — {@code sanitize} drops them on load, which is what moves an existing user's bar
     * without a migration step. Everything they arranged around it survives, and the separators that
     * bracketed the group collapse to one instead of leaving a double rule where it stood.
     */
    @Test
    void aLayoutSavedWithTheOldRunConfigurationGroupLosesItAndKeepsTheRest() {
        List<String> saved = List.of(
                "file.new",
                ToolbarCatalog.SEPARATOR,
                "toolbar.runConfig",
                "toolbar.runConfig.run",
                "toolbar.runConfig.debug",
                "toolbar.runConfig.stop",
                ToolbarCatalog.SEPARATOR,
                "palette.show");

        assertEquals(List.of("file.new", ToolbarCatalog.SEPARATOR, "palette.show"), ToolbarLayout.sanitize(saved));
    }

    /** A bar that was nothing but the group does not come back as a stray separator. */
    @Test
    void aLayoutOfNothingButTheOldGroupSanitizesToEmpty() {
        assertEquals(
                List.of(),
                ToolbarLayout.sanitize(
                        List.of(ToolbarCatalog.SEPARATOR, "toolbar.runConfig", "toolbar.runConfig.run")));
    }

    @Test
    void removeAndInsert() {
        assertEquals(List.of("a", "c"), ToolbarLayout.remove(List.of("a", "b", "c"), 1));
        assertEquals(List.of("a", "b", "c"), ToolbarLayout.remove(List.of("a", "b", "c"), 9));
        assertEquals(List.of("x", "a", "b"), ToolbarLayout.insertAt(List.of("a", "b"), 0, "x"));
        assertEquals(List.of("a", "b", "x"), ToolbarLayout.insertAt(List.of("a", "b"), 99, "x"));
    }
}
