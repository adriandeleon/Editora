package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure decisions behind a preview card's sticky notes: which notes, their headers, side and stacking. */
class ProjectMapStickyNotesTest {

    private static PersonalNote note(NoteScope scope, int line, int endLine, long created, String body) {
        return new PersonalNote(
                UUID.randomUUID(),
                null,
                scope,
                new TextAnchor(line, 0, endLine, 0, "", "", ""),
                body,
                List.of(),
                NoteStatus.ACTIVE,
                created,
                created);
    }

    @Test
    void aCardShowsItsFilesLineNotesInLineOrderAndADeclarationCardOnlyThoseOnItsLines() {
        PersonalNote folder = note(NoteScope.FOLDER, 0, 0, 1, "folder");
        PersonalNote late = note(NoteScope.LINE, 30, 30, 2, "late");
        PersonalNote early = note(NoteScope.WORD, 4, 4, 3, "early");
        PersonalNote sameLineFirst = note(NoteScope.LINE, 12, 12, 4, "twelve a");
        PersonalNote sameLineSecond = note(NoteScope.RANGE, 12, 15, 5, "twelve b");
        List<PersonalNote> all = List.of(folder, late, sameLineSecond, early, sameLineFirst);

        assertEquals(
                List.of("early", "twelve a", "twelve b", "late"),
                ProjectMapStickyNotes.notesFor(all, null).stream()
                        .map(PersonalNote::body)
                        .toList(),
                "a folder's note is not about any line; same-line notes keep the order they were written in");
        assertEquals(
                List.of("twelve a", "twelve b"),
                ProjectMapStickyNotes.notesFor(all, new ProjectMapPreview.Range("add()", 11, 10, 20)).stream()
                        .map(PersonalNote::body)
                        .toList(),
                "a declaration card shows the notes that start on its lines, its first and last included");
        assertEquals(
                1,
                ProjectMapStickyNotes.notesFor(all, new ProjectMapPreview.Range("x", 30, 30, 30))
                        .size());
        assertEquals(List.of(), ProjectMapStickyNotes.notesFor(null, null));

        List<PersonalNote> many = new ArrayList<>();
        for (int i = 0; i < ProjectMapStickyNotes.MAX_NOTES + 5; i++) {
            many.add(note(NoteScope.LINE, i, i, i, "n" + i));
        }
        assertEquals(
                ProjectMapStickyNotes.MAX_NOTES,
                ProjectMapStickyNotes.notesFor(many, null).size());
    }

    @Test
    void aSheetsHeaderNamesItsLineOrItsLinesOneBased() {
        assertEquals(tr("project.map.sticky.line", 5), ProjectMapStickyNotes.where(note(NoteScope.LINE, 4, 4, 1, "")));
        assertEquals(tr("project.map.sticky.line", 5), ProjectMapStickyNotes.where(note(NoteScope.WORD, 4, 4, 1, "")));
        assertEquals(
                tr("project.map.sticky.lines", 5, 9), ProjectMapStickyNotes.where(note(NoteScope.RANGE, 4, 8, 1, "")));
        assertEquals(
                tr("project.map.sticky.line", 5),
                ProjectMapStickyNotes.where(note(NoteScope.RANGE, 4, 4, 1, "")),
                "a range that ends on its own line is one line");
        assertEquals("Lines 5–9", tr("project.map.sticky.lines", 5, 9));
    }

    @Test
    void sheetsGoRightOfTheCardThenLeftThenOnItsRightEdge() {
        double sheet = ProjectMapStickyNotes.WIDTH;
        double gaps = ProjectMapStickyNotes.OFFSET + ProjectMapStickyNotes.GAP;
        assertEquals(ProjectMapStickyNotes.Side.RIGHT, ProjectMapStickyNotes.side(0, 600, sheet, 600 + sheet + gaps));
        assertEquals(
                ProjectMapStickyNotes.Side.LEFT,
                ProjectMapStickyNotes.side(sheet + gaps, 900, sheet, 900 + sheet + gaps - 1));
        assertEquals(ProjectMapStickyNotes.Side.OVER, ProjectMapStickyNotes.side(14, 886, sheet, 900));
    }

    @Test
    void sheetsStackNearTheirLinesWithoutOverlappingAndWrapPastTheCardsFoot() {
        double h = ProjectMapStickyNotes.HEIGHT;
        double step = h + ProjectMapStickyNotes.GAP;
        List<double[]> placed = ProjectMapStickyNotes.stack(List.of(-5.0, 40.0, 400.0, 410.0), 600);
        assertArrayEquals(new double[] {0, 0}, placed.get(0), "never above the card's top");
        assertArrayEquals(new double[] {0, step}, placed.get(1), 1e-9, "pushed below the sheet above");
        assertArrayEquals(new double[] {0, 400}, placed.get(2), "at its own line when there is room");
        assertArrayEquals(new double[] {1, 410}, placed.get(3), "past the foot: a further column, at its line");

        List<double[]> tiny = ProjectMapStickyNotes.stack(List.of(0.0, 0.0), 50);
        assertArrayEquals(new double[] {0, 0}, tiny.get(0), "a card shorter than a sheet still takes one");
        assertArrayEquals(new double[] {1, 0}, tiny.get(1));
    }
}
