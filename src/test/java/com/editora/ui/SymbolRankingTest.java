package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspManager.WorkspaceSymbolMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Go to Symbol's and Search Everywhere's class ranking (Java re-review EN2). */
class SymbolRankingTest {

    private static WorkspaceSymbolMatch library(String name, String container) {
        return new WorkspaceSymbolMatch(name, container, "class", null, 0, 0, "jdt://contents/x/" + name + ".class");
    }

    private static WorkspaceSymbolMatch project(String name) {
        return new WorkspaceSymbolMatch(name, "demo", "class", Path.of("/p/src/demo/" + name + ".java"), 0, 6);
    }

    @Test
    void theExactClassComesFirstAndANestedLibraryClassLast() {
        var nested = library("ArrayList", "java.util.Arrays");
        var cow = library("CopyOnWriteArrayList", "java.util.concurrent");
        var exact = library("ArrayList", "java.util");
        List<WorkspaceSymbolMatch> ranked = WorkspaceSymbolPopup.rankByName(List.of(nested, cow, exact), "ArrayList");
        assertEquals(List.of(exact, nested, cow), ranked, "an exact nested name above a partial match (BT3)");
        assertTrue(SymbolRanking.score(library("Assertions", "org.junit.jupiter.api"), "Assertions")
                > SymbolRanking.score(library("AssertionStatusDirectives", "java.lang"), "Assertions"));
        assertTrue(SymbolRanking.nestedLibraryType(nested));
        assertFalse(SymbolRanking.nestedLibraryType(exact));
        assertFalse(SymbolRanking.nestedLibraryType(project("Shape")), "the project's own types are not demoted");
        assertTrue(
                SymbolRanking.score(nested, "Arrays.ArrayList") > SymbolRanking.score(exact, "Arrays.ArrayList"),
                "a dotted query asks for the nested one");
    }

    @Test
    void aDottedQueryFindsTheNestedTypeFirst() {
        var entry = library("Entry", "java.util.Map");
        var entrySet = library("EntrySet", "java.util.IdentityHashMap");
        var shaded = library("Entry", "org.junit.shadow.parsers.concurrent");
        assertEquals(List.of(entry, entrySet), WorkspaceSymbolPopup.rankByName(List.of(entrySet, entry), "Map.Entry"));
        List<WorkspaceSymbolMatch> forEntry =
                WorkspaceSymbolPopup.rankByName(List.of(entrySet, entry, shaded), "Entry");
        assertTrue(
                forEntry.indexOf(entry) < forEntry.indexOf(entrySet), "Map.Entry above a partial match: " + forEntry);
        assertEquals("Map.Entry", SymbolRanking.qualifiedTail(entry));
        assertEquals("Entry", SymbolRanking.qualifiedTail(shaded));
    }

    @Test
    void theExcludedTypesLeaveTheListButTheProjectsOwnStay() {
        var internal = library("BakedArrayList", "sun.swing");
        var awt = library("List", "java.awt");
        var util = library("List", "java.util");
        var own = new WorkspaceSymbolMatch("Baked", "sun.swing", "class", Path.of("/p/sun/swing/Baked.java"), 0, 6);
        assertEquals(
                List.of(util, own),
                SymbolRanking.withoutExcluded(
                        List.of(internal, awt, util, own), List.of("java.awt.*", "com.sun.*", "sun.*", "jdk.*")));
        assertTrue(SymbolRanking.excluded("com.sun.org.apache.Foo", List.of("com.sun.*")));
        assertFalse(SymbolRanking.excluded("java.util.List", List.of("java.awt.*", " ")));
        assertTrue(SymbolRanking.excluded("javax.swing.JList", List.of("javax.swing.JList")));
    }

    @Test
    void aLibraryTypeIsFoundBelowItsPackageLineAndJavadoc() {
        String src = """
                package java.awt;

                /**
                 * A class Canvas component.
                 */
                public class Canvas extends Component implements Accessible {
                    class Canvas2 {}
                }
                """;
        assertEquals(5, LspCoordinator.typeDeclarationLine(src, "Canvas"));
        assertEquals(6, LspCoordinator.typeDeclarationLine(src, "Canvas2"));
        assertEquals(-1, LspCoordinator.typeDeclarationLine(src, "Missing"));
    }

    @Test
    void jdtlsInitializeScopesAreShownInTheUiLanguage() {
        assertEquals(
                com.editora.i18n.Messages.tr("picker.refactor.scope.method"),
                LspCoordinator.initializeScopeLabel("Current method"));
        assertEquals("Something new", LspCoordinator.initializeScopeLabel("Something new"));
    }
}
