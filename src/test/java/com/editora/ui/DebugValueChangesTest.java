package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugValueChangesTest {

    private static final String MAIN = DebugValueChanges.frameKey(1, "Main.main", "/p/Main.java", 1);
    private static final String WORK = DebugValueChanges.frameKey(1, "Main.work", "/p/Main.java", 2);
    private static final List<String> X = List.of("Local", "x");

    @Test
    void aValueIsChangedWhenAnEarlierStopInTheSameFrameShowedAnotherOne() {
        DebugValueChanges changes = new DebugValueChanges();
        changes.newStop();
        changes.setFrame(MAIN);
        changes.record(X, "1");
        assertFalse(changes.changed(X, "1"), "the first stop has nothing to compare with");

        changes.newStop();
        changes.setFrame(MAIN);
        assertTrue(changes.changed(X, "2"));
        assertFalse(changes.changed(X, "1"));
        assertFalse(changes.changed(List.of("Local", "y"), "7"), "a new variable is not a changed one");
    }

    @Test
    void withinOneStopTheComparisonStaysWithTheEarlierStop() {
        DebugValueChanges changes = new DebugValueChanges();
        changes.newStop();
        changes.setFrame(MAIN);
        changes.record(X, "1");
        changes.newStop();
        changes.setFrame(MAIN);
        changes.record(X, "2");
        // Picking another frame and back, or Set Value reloading the tree, records again within this stop.
        changes.setFrame(WORK);
        changes.setFrame(MAIN);
        changes.record(X, "2");
        assertTrue(changes.changed(X, "2"), "still changed against the previous stop");
    }

    @Test
    void framesAreComparedOnlyWithThemselves() {
        DebugValueChanges changes = new DebugValueChanges();
        changes.newStop();
        changes.setFrame(MAIN);
        changes.record(X, "1");
        // Step into work(): another frame, which has its own x.
        changes.newStop();
        changes.setFrame(WORK);
        assertFalse(changes.changed(X, "9"), "work's x is not main's x");
        changes.record(X, "9");
        // Step back out: main is compared with what it showed two stops ago.
        changes.newStop();
        changes.setFrame(MAIN);
        assertTrue(changes.changed(X, "3"));
        // A recursive call of the same method is a frame of its own (its depth differs).
        changes.setFrame(DebugValueChanges.frameKey(1, "Main.main", "/p/Main.java", 2));
        assertFalse(changes.changed(X, "3"));
    }

    @Test
    void aNewSessionComparesWithNothing() {
        DebugValueChanges changes = new DebugValueChanges();
        changes.setFrame(MAIN);
        changes.record(X, "1");
        changes.newStop();
        changes.reset();
        changes.setFrame(MAIN);
        assertFalse(changes.changed(X, "2"));
    }

    @Test
    void aFrameRemembersABoundedNumberOfRows() {
        DebugValueChanges changes = new DebugValueChanges();
        changes.setFrame(MAIN);
        for (int i = 0; i < DebugValueChanges.MAX_ROWS + 10; i++) {
            changes.record(List.of("Local", "list", "[" + i + "]"), "0");
        }
        changes.newStop();
        changes.setFrame(MAIN);
        assertTrue(changes.changed(List.of("Local", "list", "[0]"), "1"));
        assertFalse(
                changes.changed(List.of("Local", "list", "[" + (DebugValueChanges.MAX_ROWS + 5) + "]"), "1"),
                "rows past the bound are not remembered");
    }
}
