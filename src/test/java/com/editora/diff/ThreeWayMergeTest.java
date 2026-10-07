package com.editora.diff;

import java.util.Collections;
import java.util.List;

import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeWayMergeTest {

    @Test
    void automaticallyCombinesIndependentChanges() {
        var result = ThreeWayMerge.merge(
                "alpha\nbeta\ngamma\ndelta\n", "alpha\nBETA\ngamma\ndelta\n", "alpha\nbeta\ngamma\nDELTA\n");

        assertFalse(result.file().hasConflicts());
        assertEquals(2, result.automaticallyMergedChanges());
        assertEquals(
                List.of("alpha", "BETA", "gamma", "DELTA"),
                ConflictParser.resolve(result.file(), Collections.emptyList()));
    }

    @Test
    void automaticallyCombinesTheSameOverlappingEditOnce() {
        var result = ThreeWayMerge.merge("before\nold\nafter", "before\nnew\nafter", "before\nnew\nafter");

        assertFalse(result.file().hasConflicts());
        assertEquals(List.of("before", "new", "after"), ConflictParser.resolve(result.file(), List.of()));
    }

    @Test
    void exposesActualAncestorForDivergentEdit() {
        var result = ThreeWayMerge.merge("before\nold\nafter", "before\nours\nafter", "before\ntheirs\nafter");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertTrue(conflict.hasBase());
        assertEquals(List.of("old"), conflict.base());
        assertEquals(List.of("ours"), conflict.ours());
        assertEquals(List.of("theirs"), conflict.theirs());
        assertEquals(List.of("before", "old", "after"), ConflictParser.resolve(result.file(), List.of(Choice.BASE)));
    }

    @Test
    void representsCompetingInsertionsWithAnEmptyButPresentBase() {
        var result = ThreeWayMerge.merge("before\nafter", "before\nours\nafter", "before\ntheirs\nafter");

        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertTrue(conflict.hasBase());
        assertTrue(conflict.base().isEmpty());
        assertEquals(List.of("ours"), conflict.ours());
        assertEquals(List.of("theirs"), conflict.theirs());
    }

    @Test
    void deletionConflictsWithAnEditInsideDeletedRegion() {
        var result = ThreeWayMerge.merge("before\none\ntwo\nafter", "before\nafter", "before\none\nTWO\nafter");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertEquals(List.of("one", "two"), conflict.base());
        assertTrue(conflict.ours().isEmpty());
        assertEquals(List.of("one", "TWO"), conflict.theirs());
    }

    /** Git reports changes that touch as one conflict; merging them unseen hid a region Git had flagged. */
    @Test
    void touchingEditsAreOneConflictAsInGit() {
        var result = ThreeWayMerge.merge("one\ntwo\nthree", "ONE\ntwo\nthree", "one\nTWO\nthree");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(0)).conflict();
        assertEquals(List.of("ONE", "two"), conflict.ours());
        assertEquals(List.of("one", "TWO"), conflict.theirs());
        assertEquals(List.of("one", "two"), conflict.base());
    }

    @Test
    void editsSeparatedByAnUntouchedLineStayIndependent() {
        var result = ThreeWayMerge.merge("one\ntwo\nthree", "ONE\ntwo\nthree", "one\ntwo\nTHREE");

        assertFalse(result.file().hasConflicts());
        assertEquals(List.of("ONE", "two", "THREE"), ConflictParser.resolve(result.file(), List.of()));
    }

    /** Each side deleted a different one of two identical neighbours: neither meant to delete both. */
    @Test
    void deletingDifferentCopiesOfARepeatedLineIsNotMergedIntoDeletingBoth() {
        var result = ThreeWayMerge.merge("a\na\nd\na\na\nc", "a\nd\nd\na\nc", "a\na\nd\na\nc\nc");

        assertTrue(result.file().hasConflicts());
    }

    @Test
    void aFileStillHoldingGitsMarkersAgreesWithTheStageMerge() {
        var merged = ThreeWayMerge.merge("a\nb\nc\nd\ne", "a\nB1\nc\nd\nE", "a\nB2\nc\nd\ne")
                .file();
        List<String> written = List.of("a", "<<<<<<< HEAD", "B1", "=======", "B2", ">>>>>>> topic", "c", "d", "E");
        assertTrue(ThreeWayMerge.agreesWith(merged, ConflictParser.parse(written)));

        // The conflict was resolved by hand, or the file edited elsewhere: no longer the same merge.
        assertFalse(ThreeWayMerge.agreesWith(merged, ConflictParser.parse(List.of("a", "mine", "c", "d", "E"))));
        List<String> edited = new java.util.ArrayList<>(written);
        edited.set(7, "d edited");
        assertFalse(ThreeWayMerge.agreesWith(merged, ConflictParser.parse(edited)));
    }

    // --- runs of identical lines: the raw differ's deltas are not where the edit was made -------------------

    @Test
    void aReplacedLineInARunOfIdenticalLinesIsNotMistakenForTheOtherSidesDeletion() {
        // The differ reports theirs as "insert X before the run, delete the run's last line"; that deletion
        // looked identical to ours and the merge came back clean as theirs, ours' deletion gone.
        var result = ThreeWayMerge.merge("x\nx\n", "x\n", "X\nx\n");

        List<String> merged = ConflictParser.resolve(result.file(), List.of(Choice.OURS));
        assertFalse(
                !result.file().hasConflicts() && merged.equals(List.of("X", "x")),
                "a clean merge must contain both edits");
        if (!result.file().hasConflicts()) {
            assertEquals(List.of("X"), merged, "theirs replaced one line and ours deleted the other");
        }
    }

    @Test
    void aDeletionAndAnEditInOneRunAgreeWithGit() {
        // git merge-file is clean here: one pop commented, one pop deleted.
        String base = "stack.pop();\nstack.pop();\nstack.pop();\nreturn stack;\n";
        String ours = "stack.pop(); // frame marker\nstack.pop();\nstack.pop();\nreturn stack;\n";
        String theirs = "stack.pop();\nstack.pop();\nreturn stack;\n";

        var result = ThreeWayMerge.merge(base, ours, theirs);

        assertFalse(result.file().hasConflicts());
        assertEquals(
                List.of("stack.pop(); // frame marker", "stack.pop();", "return stack;"),
                ConflictParser.resolve(result.file(), List.of()));
        assertEquals(
                List.of("stack.pop(); // frame marker", "stack.pop();", "return stack;"),
                ConflictParser.resolve(ThreeWayMerge.merge(base, theirs, ours).file(), List.of()),
                "and with the sides exchanged");
    }

    @Test
    void twoEditsInOneRunDoNotDuplicateALine() {
        String base = "pop\npop\npop\npop\nend\n";

        var result =
                ThreeWayMerge.merge(base, "pop // ours\npop\npop\npop\nend\n", "pop\npop\npop // theirs\npop\nend\n");

        assertFalse(result.file().hasConflicts());
        assertEquals(
                List.of("pop // ours", "pop", "pop // theirs", "pop", "end"),
                ConflictParser.resolve(result.file(), List.of()));
    }

    @Test
    void anInsertionBesideAnEditedLineOfARunStillConflicts() {
        var result = ThreeWayMerge.merge(
                "pop\npop\npop\nend\n", "pop\nlog\npop\npop\nend\n", "pop // theirs\npop\npop\nend\n");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(0)).conflict();
        assertEquals(List.of("pop"), conflict.base());
        assertEquals(List.of("pop", "log"), conflict.ours());
        assertEquals(List.of("pop // theirs"), conflict.theirs());
    }

    @Test
    void everyCleanMergeOfTwoSingleLineEditsContainsBothEdits() {
        // Exhaustive over short texts on a two-letter alphabet, where runs of equal lines are the norm. Each
        // side makes one edit that introduces a line found nowhere else, so a clean result that lacks either
        // marker has lost that side's edit.
        List<List<String>> bases = new java.util.ArrayList<>();
        for (int length = 1; length <= 5; length++) {
            for (int bits = 0; bits < 1 << length; bits++) {
                List<String> base = new java.util.ArrayList<>();
                for (int i = 0; i < length; i++) {
                    base.add((bits >> i & 1) == 0 ? "a" : "b");
                }
                bases.add(base);
            }
        }
        for (List<String> base : bases) {
            for (List<String> ours : singleEdits(base, "OURS")) {
                for (List<String> theirs : singleEdits(base, "THEIRS")) {
                    var result = ThreeWayMerge.merge(
                            String.join("\n", base), String.join("\n", ours), String.join("\n", theirs));
                    if (result.file().hasConflicts()) {
                        continue;
                    }
                    List<String> merged = ConflictParser.resolve(result.file(), List.of());
                    String scenario = base + " ours=" + ours + " theirs=" + theirs + " merged=" + merged;
                    assertEquals(1, java.util.Collections.frequency(merged, "OURS"), scenario);
                    assertEquals(1, java.util.Collections.frequency(merged, "THEIRS"), scenario);
                    assertEquals(
                            base.size() + (ours.size() - base.size()) + (theirs.size() - base.size()),
                            merged.size(),
                            scenario);
                }
            }
        }
    }

    /** Every text reached from {@code base} by replacing one line with {@code marker} or inserting it. */
    private static List<List<String>> singleEdits(List<String> base, String marker) {
        List<List<String>> edits = new java.util.ArrayList<>();
        for (int i = 0; i <= base.size(); i++) {
            List<String> inserted = new java.util.ArrayList<>(base);
            inserted.add(i, marker);
            edits.add(inserted);
            if (i < base.size()) {
                List<String> replaced = new java.util.ArrayList<>(base);
                replaced.set(i, marker);
                edits.add(replaced);
            }
        }
        return edits;
    }

    // --- the file's own conflict regions outrank the recomputed merge (V8) -----------------------------

    /** The reviewer's case: Git's merge marks a conflict where this merge combines both sides cleanly. */
    @Test
    void aConflictGitWroteIsShownEvenWhenTheRecomputedMergeIsClean() {
        ThreeWayMerge.Result merged = ThreeWayMerge.merge("a\nb\na\nb\n", "X\na\nb\na\nY\n", "a\na\nb\n");
        ConflictFile written = ConflictParser.parse(
                List.of("<<<<<<< ours", "X", "a", "b", "=======", "a", ">>>>>>> theirs", "a", "Y"));
        if (!merged.file().hasConflicts()) {
            assertFalse(ThreeWayMerge.agreesWith(merged.file(), written));
        }
        ThreeWayMerge.Source source = ThreeWayMerge.sourceFor(merged.file(), written);
        ConflictFile shown = source == ThreeWayMerge.Source.MERGE ? merged.file() : written;
        assertTrue(shown.hasConflicts(), "a region Git reported as a conflict is never merged unseen");
        assertTrue(source != ThreeWayMerge.Source.ASK, "an alignment difference is not a changed file");
    }

    @Test
    void sourceIsTheMergeWhenItAgreesTheMarkersWhenItDoesNotAndAQuestionOnlyWithoutMarkers() {
        ConflictFile merged = ThreeWayMerge.merge("a\nb\nc\n", "a\nours\nc\n", "a\ntheirs\nc\n")
                .file();
        List<String> asGitWroteIt = List.of("a", "<<<<<<< HEAD", "ours", "=======", "theirs", ">>>>>>> br", "c");
        assertEquals(ThreeWayMerge.Source.MERGE, ThreeWayMerge.sourceFor(merged, ConflictParser.parse(asGitWroteIt)));

        // Another line was edited by hand since: the file's regions (and that edit) are what is resolved.
        List<String> editedElsewhere =
                List.of("a edited", "<<<<<<< HEAD", "ours", "=======", "theirs", ">>>>>>> br", "c");
        assertEquals(
                ThreeWayMerge.Source.FILE_MARKERS,
                ThreeWayMerge.sourceFor(merged, ConflictParser.parse(editedElsewhere)));

        // Nothing left to resolve in the file: only now is there a real question.
        assertEquals(
                ThreeWayMerge.Source.ASK,
                ThreeWayMerge.sourceFor(merged, ConflictParser.parse(List.of("a", "mine", "c"))));
    }
}
