package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the Map's code navigation: which commands ask what, its texts, and where a question stands. */
class ProjectMapNavigationTest {

    @BeforeAll
    static void messages() {
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void eachEditorNavigationCommandAsksTheMapForItsKind() {
        assertEquals(List.of(MapCardLinks.Kind.DEFINITION), MapCardLinks.NAVIGATION_COMMANDS.get("lsp.gotoDefinition"));
        assertEquals(
                List.of(MapCardLinks.Kind.DEFINITION),
                MapCardLinks.NAVIGATION_COMMANDS.get("lsp.peekDefinition"),
                "a card is already a peek");
        assertEquals(
                MapCardLinks.Kind.SUBTYPES,
                MapCardLinks.HIERARCHY_COMMANDS.get("lsp.typeHierarchy"),
                "a tree, the subtypes first");
        assertEquals(List.of(MapCardLinks.Kind.REFERENCES), MapCardLinks.NAVIGATION_COMMANDS.get("lsp.findReferences"));
        assertEquals(MapCardLinks.Kind.CALLERS, MapCardLinks.HIERARCHY_COMMANDS.get("lsp.callHierarchy"));
        assertNull(MapCardLinks.NAVIGATION_COMMANDS.get("lsp.callHierarchy"), "not cards");
        assertEquals(
                List.of(MapCardLinks.Kind.CALLEES),
                MapCardLinks.NAVIGATION_COMMANDS.get(MapCardLinks.CALLEES_COMMAND),
                "the Map's own command");
        assertNull(MapCardLinks.NAVIGATION_COMMANDS.get("lsp.rename"), "an edit is the editor's");
    }

    @Test
    void everyKindHasItsTextsInTheCatalog() {
        for (MapCardLinks.Kind kind : MapCardLinks.Kind.values()) {
            for (String text : List.of(
                    MapCardLinks.menuLabel(kind),
                    MapCardLinks.findingText(kind, "area"),
                    MapCardLinks.findingText(kind, null),
                    MapCardLinks.noneText(kind, "area"),
                    MapCardLinks.noneText(kind, null),
                    MapCardLinks.foundText(kind, 3, "area", 2, 1),
                    MapCardLinks.moreText(kind, 4, "area"),
                    MapCardLinks.edgeText(kind, MapCardLinks.Via.SERVER, 1),
                    MapCardLinks.edgeDescription(kind, MapCardLinks.Via.SERVER, "Circle", "Shape.java", 1))) {
                assertFalse(text.isBlank() || text.contains("project.map."), kind + ": " + text);
            }
        }
        assertEquals(
                "area: 2 definitions; 1 more listed by the card’s “+1 more” button.",
                MapCardLinks.foundText(MapCardLinks.Kind.DEFINITION, 3, "area", 2, 1));
        assertEquals("area: 1 subtype.", MapCardLinks.foundText(MapCardLinks.Kind.SUBTYPES, 1, "area", 1, 0));
        assertEquals(
                "implements · 2", MapCardLinks.edgeText(MapCardLinks.Kind.IMPLEMENTATIONS, MapCardLinks.Via.SERVER, 2));
    }

    @Test
    void theGoToKindsLeadAndTheKeyboardFollowsThemButNotTheTypeHierarchy() {
        assertTrue(MapCardLinks.Kind.DEFINITION.goesTo());
        assertTrue(MapCardLinks.Kind.SUPER_METHOD.goesTo());
        assertTrue(MapCardLinks.Kind.SUPERTYPES.leads, "drawn from the card asked from");
        assertFalse(MapCardLinks.Kind.SUPERTYPES.goesTo(), "asked with the subtypes: the keyboard stays");
        assertTrue(MapCardLinks.Kind.CALLEES.leads, "drawn from the caller");
        assertFalse(MapCardLinks.Kind.CALLEES.goesTo(), "many calls: the keyboard stays");
        assertFalse(MapCardLinks.Kind.CALLEES.sameName(), "a callee is selected by its own name");
        assertFalse(ProjectMapLinkSearch.byNameKinds(MapCardLinks.Kind.CALLEES), "needs a server");
        assertTrue(MapCardLinks.MENU_KINDS.contains(MapCardLinks.Kind.CALLEES));
        assertEquals(
                "area: calls 2 declarations at 3 call sites.",
                MapCardLinks.foundText(MapCardLinks.Kind.CALLEES, 3, "area", 2, 0));
        assertFalse(MapCardLinks.Kind.REFERENCES.leads);
        assertFalse(MapCardLinks.Kind.IMPLEMENTATIONS.goesTo());
        assertTrue(ProjectMapLinkSearch.byNameKinds(MapCardLinks.Kind.DEFINITION));
        assertFalse(ProjectMapLinkSearch.byNameKinds(MapCardLinks.Kind.IMPLEMENTATIONS), "needs a server");
    }

    @Test
    void aResultsMarkIsTheAskedNameOrItsOwn() {
        MapCardLinks.Group circle = new MapCardLinks.Group(Path.of("Circle.java"), null, "Circle", 2, 1, "Circle");
        java.util.regex.Pattern subtype = MapCardLinks.markOf(MapCardLinks.Kind.SUBTYPES, circle, "Shape");
        assertNotNull(subtype);
        assertTrue(subtype.matcher("class Circle implements Shape").find());
        assertFalse(subtype.matcher("interface Shape").find(), "a subtype's own name, not the asked one");
        java.util.regex.Pattern call = MapCardLinks.markOf(MapCardLinks.Kind.DEFINITION, circle, "area");
        assertNotNull(call);
        assertTrue(call.matcher("double area()").find(), "a definition has the asked name");
        assertNull(
                MapCardLinks.markOf(
                        MapCardLinks.Kind.TYPE_DEFINITION,
                        new MapCardLinks.Group(Path.of("T.java"), null, "T", 0, 1),
                        "x"),
                "nothing known: the line");
    }

    @Test
    void aQuestionIsSettledOnTheNameAtItsPlace() {
        String text = "class Use {\n    double total(Shape shape) {\n        return shape.area();\n    }\n}\n";
        Path file = Path.of("Use.java");
        MapCardLinks.Request at = ProjectMapLinkSearch.settle(
                new MapCardLinks.Request(MapCardLinks.Kind.DEFINITION, file, 2, 23, null, null), text);
        assertNotNull(at);
        assertEquals("area", at.name());
        assertEquals(23, at.column());
        MapCardLinks.Request named = ProjectMapLinkSearch.settle(
                new MapCardLinks.Request(MapCardLinks.Kind.REFERENCES, file, 1, -1, "total", null), text);
        assertNotNull(named);
        assertEquals(11, named.column(), "a negative column: the name on the line");
        assertNull(
                ProjectMapLinkSearch.settle(
                        new MapCardLinks.Request(MapCardLinks.Kind.DEFINITION, file, 1, 0, null, null), text),
                "no name in the indentation");
        assertEquals(text.indexOf("area"), ProjectMapLinkSearch.offsetOf(text, 2, 21));
        assertArrayEquals(new int[] {2, 21}, ProjectMapLinkSearch.placeOf(text, text.indexOf("area")));
        assertEquals(text.indexOf("\n", text.indexOf("area")), ProjectMapLinkSearch.offsetOf(text, 2, 500), "clamped");
    }
}
