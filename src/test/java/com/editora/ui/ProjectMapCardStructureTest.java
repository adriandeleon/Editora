package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of a file card's Structure column: which side it takes, and what it lists. */
class ProjectMapCardStructureTest {

    private static final Path FILE = Path.of("/project/App.java");

    @Test
    void theColumnTakesThePreferredSideWithRoomElseTheOtherElseStandsOverTheCard() {
        // A card from 400 to 900 in a panel 1400 wide: 500 free on either side.
        assertEquals(ProjectMapCardStructure.Side.RIGHT, ProjectMapCardStructure.side(400, 900, 268, 1400, false));
        assertEquals(ProjectMapCardStructure.Side.LEFT, ProjectMapCardStructure.side(400, 900, 268, 1400, true));
        // Against the panel's right edge: the left, whatever was preferred.
        assertEquals(ProjectMapCardStructure.Side.LEFT, ProjectMapCardStructure.side(1000, 1350, 268, 1400, false));
        // Against its left edge: the right.
        assertEquals(ProjectMapCardStructure.Side.RIGHT, ProjectMapCardStructure.side(20, 500, 268, 1400, true));
        // No room on either side.
        assertEquals(ProjectMapCardStructure.Side.OVER, ProjectMapCardStructure.side(100, 1300, 268, 1400, false));
    }

    @Test
    void theColumnFitsItsLongestRowWithinItsBounds() {
        assertEquals(
                ProjectMapCardStructure.MIN_WIDTH, ProjectMapCardStructure.fit(0, 40, 30), "short rows: the floor");
        assertEquals(ProjectMapCardStructure.MAX_WIDTH, ProjectMapCardStructure.fit(0, 900, 30), "and a ceiling");
        double shallow = ProjectMapCardStructure.fit(0, 300, 30);
        assertTrue(shallow > 300 + 30, "room for the whole label and its line: " + shallow);
        assertTrue(ProjectMapCardStructure.fit(2, 300, 30) > shallow, "a member sits deeper");
    }

    @Test
    void theTopLevelSaysWhyThereIsNothingToList() {
        assertEquals(
                List.of(ProjectMapCardStructure.Row.message(tr("project.map.structure.loading"))),
                ProjectMapCardStructure.rows(ProjectMapStructure.View.LOADING, List.of()));
        ProjectMapStructure.View unreadable =
                new ProjectMapStructure.View(List.of(), ProjectMapStructure.Problem.UNREADABLE, false);
        assertEquals(
                List.of(ProjectMapCardStructure.Row.message(tr("project.map.structure.unreadable"))),
                ProjectMapCardStructure.rows(unreadable, unreadable.roots()));
        ProjectMapStructure.View empty =
                new ProjectMapStructure.View(List.of(), ProjectMapStructure.Problem.NONE, false);
        assertEquals(
                List.of(ProjectMapCardStructure.Row.message(tr("project.map.structure.none"))),
                ProjectMapCardStructure.rows(empty, empty.roots()));
    }

    @Test
    void aMemberlessDeclarationListsNothingAndALongLevelEndsInACount() {
        ProjectMapStructure.Node leaf = node("add()", List.of());
        ProjectMapStructure.View view =
                new ProjectMapStructure.View(List.of(leaf), ProjectMapStructure.Problem.NONE, false);
        assertEquals(List.of(), ProjectMapCardStructure.rows(view, leaf.children()), "no members: no message row");

        List<ProjectMapStructure.Node> many = new ArrayList<>();
        for (int i = 0; i < ProjectMapStructure.MAX_ROWS + 3; i++) {
            many.add(node("m" + i + "()", List.of()));
        }
        ProjectMapStructure.View big = new ProjectMapStructure.View(many, ProjectMapStructure.Problem.NONE, true);
        List<ProjectMapCardStructure.Row> rows = ProjectMapCardStructure.rows(big, big.roots());
        assertEquals(ProjectMapStructure.MAX_ROWS + 2, rows.size());
        assertEquals(
                tr("project.map.structure.more", 3),
                rows.get(ProjectMapStructure.MAX_ROWS).message());
        assertEquals(tr("project.map.structure.partial"), rows.getLast().message(), "a cut file says so");
    }

    private static ProjectMapStructure.Node node(String label, List<ProjectMapStructure.Node> children) {
        Path path = FILE.resolve(ProjectMapStructure.SEGMENT_PREFIX + label + "~0");
        return new ProjectMapStructure.Node(
                path, new ProjectMapModel.Symbol(FILE, label, "method", 3, 3, 5, null, !children.isEmpty()), children);
    }
}
