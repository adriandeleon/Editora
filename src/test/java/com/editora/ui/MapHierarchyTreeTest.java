package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of a hierarchy tree on the Map: its turns, the question a row asks, recursion, a row's text. */
class MapHierarchyTreeTest {

    private final Path file = Path.of("/p/src/Use.java").toAbsolutePath();

    @Test
    void eachHierarchyTurnsTheOtherWay() {
        assertEquals(MapCardLinks.Kind.CALLEES, MapHierarchyTree.opposite(MapCardLinks.Kind.CALLERS));
        assertEquals(MapCardLinks.Kind.CALLERS, MapHierarchyTree.opposite(MapCardLinks.Kind.CALLEES));
        assertEquals(MapCardLinks.Kind.SUPERTYPES, MapHierarchyTree.opposite(MapCardLinks.Kind.SUBTYPES));
        assertEquals(MapCardLinks.Kind.SUBTYPES, MapHierarchyTree.opposite(MapCardLinks.Kind.SUPERTYPES));
        assertEquals(MapCardLinks.Kind.REFERENCES, MapHierarchyTree.opposite(MapCardLinks.Kind.REFERENCES));
        for (MapCardLinks.Kind kind : List.of(
                MapCardLinks.Kind.CALLERS,
                MapCardLinks.Kind.CALLEES,
                MapCardLinks.Kind.SUBTYPES,
                MapCardLinks.Kind.SUPERTYPES)) {
            assertEquals(tr("project.map.hierarchy.title." + kind.stem, "x"), MapHierarchyTree.titleText(kind, "x"));
            assertEquals(tr("project.map.hierarchy.turn." + kind.stem), MapHierarchyTree.turnText(kind));
        }
    }

    @Test
    void aRowAsksTheSameQuestionAtItsDeclarationsName() {
        ProjectMapStructure.Node twice = node("twice()", 7);
        MapCardLinks.Group declared = new MapCardLinks.Group(file, twice, "twice()", 8, 2, "total");
        MapCardLinks.Request asked =
                MapHierarchyTree.requestOf(declared, MapCardLinks.Kind.CALLERS, MapCardLinks.Via.NAME);
        assertEquals(new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, file, 7, -1, "twice", null), asked);

        MapCardLinks.Group server = new MapCardLinks.Group(file, null, "twice", 7, 1, "twice");
        assertEquals(
                new MapCardLinks.Request(MapCardLinks.Kind.CALLEES, file, 7, -1, "twice", null),
                MapHierarchyTree.requestOf(server, MapCardLinks.Kind.CALLEES, MapCardLinks.Via.SERVER),
                "a server's caller outside the outline read: asked at its name");
        assertNull(
                MapHierarchyTree.requestOf(
                        new MapCardLinks.Group(file, null, "Use.java", 3, 1, "total"),
                        MapCardLinks.Kind.CALLERS,
                        MapCardLinks.Via.NAME),
                "a use matched by name outside any declaration: nothing to ask about");
        assertNull(MapHierarchyTree.requestOf(
                new MapCardLinks.Group(file, null, "Use.java", 3, 1),
                MapCardLinks.Kind.CALLERS,
                MapCardLinks.Via.SERVER));
    }

    @Test
    void aRowIsItsOwnAncestorByItsDeclarationsFileAndLine() {
        MapCardLinks.Request twice = new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, file, 7, -1, "twice", null);
        assertTrue(MapHierarchyTree.sameDeclaration(
                twice, new MapCardLinks.Group(file, node("twice()", 7), "twice()", 8, 1, null)));
        assertTrue(MapHierarchyTree.sameDeclaration(twice, new MapCardLinks.Group(file, null, "twice", 7, 1, null)));
        assertFalse(MapHierarchyTree.sameDeclaration(
                twice, new MapCardLinks.Group(file, node("total()", 3), "total()", 4, 1, null)));
        assertFalse(MapHierarchyTree.sameDeclaration(
                twice, new MapCardLinks.Group(file.resolveSibling("Other.java"), null, "twice", 7, 1, null)));
    }

    @Test
    void aRowSaysItsDeclarationFileLineAndCalls() {
        MapCardLinks.Group once = new MapCardLinks.Group(file, null, "total()", 3, 1);
        assertEquals(tr("project.map.hierarchy.row", "total()", "Use.java", "4"), MapHierarchyTree.rowText(once));
        MapCardLinks.Group often = new MapCardLinks.Group(file, null, "total()", 1233, 3);
        assertEquals(
                tr(
                        "project.map.hierarchy.rowCalls",
                        tr("project.map.hierarchy.row", "total()", "Use.java", "1234"),
                        "3"),
                MapHierarchyTree.rowText(often));
        assertEquals(List.of(MapCardLinks.Kind.CALLERS, MapCardLinks.Kind.SUBTYPES), MapCardLinks.HIERARCHY_MENU_KINDS);
        assertEquals(tr("project.map.hierarchy.showCalls"), MapCardLinks.hierarchyMenuLabel(MapCardLinks.Kind.CALLEES));
        assertEquals(
                tr("project.map.hierarchy.showTypes"), MapCardLinks.hierarchyMenuLabel(MapCardLinks.Kind.SUBTYPES));
    }

    private ProjectMapStructure.Node node(String label, int line) {
        return new ProjectMapStructure.Node(
                file.resolve(ProjectMapStructure.SEGMENT_PREFIX + label),
                new ProjectMapModel.Symbol(file, label, "method", line, line, line + 2, null, false),
                List.of());
    }
}
