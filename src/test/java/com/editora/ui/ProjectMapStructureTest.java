package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.text.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the Project Map's structure columns: which files, which lines, which paths. */
class ProjectMapStructureTest {

    @Test
    void filesWithAnOutlineAreThoseTheStructureWindowCanOutline() {
        assertTrue(StructureOutline.supports("App.java"));
        assertTrue(StructureOutline.supports("README.md"));
        assertTrue(StructureOutline.supports("script.py"));
        assertTrue(StructureOutline.supports("core.clj"));
        assertTrue(StructureOutline.supports("api.http"));
        assertTrue(StructureOutline.supports("app.js"), "JavaScript reuses the TSX grammar");
        assertFalse(StructureOutline.supports("notes.txt"));
        assertFalse(StructureOutline.supports("server.log"));
        assertFalse(StructureOutline.supports("logo.png"));
        assertFalse(StructureOutline.supports("Secret.java.gpg"), "an encrypted file cannot be read without its key");
    }

    @Test
    void aPathSegmentIsTheNameUpToItsParametersMadeSafeForAnyFileSystem() {
        assertEquals("main", ProjectMapStructure.segmentStem("main(String[])"));
        assertEquals("Map_K__V_", ProjectMapStructure.segmentStem("Map<K, V>"));
        assertEquals("GET__slow", ProjectMapStructure.segmentStem("GET /slow"));
        assertEquals("_", ProjectMapStructure.segmentStem(""));
        assertEquals(60, ProjectMapStructure.segmentStem("x".repeat(200)).length());
    }

    @Test
    void theDocCommentAndAnnotationsAboveADeclarationAreItsLinesToo() {
        List<String> lines = List.of(
                "class A {", // 0
                "", // 1
                "    /**", // 2
                "     * Adds.", // 3
                "     */", // 4
                "    @Override", // 5
                "    @SuppressWarnings(\"x\")", // 6
                "    int add(int a) {", // 7
                "        return a;", // 8
                "    }", // 9
                "    // a line comment", // 10
                "    // that goes on", // 11
                "    void b() {}", // 12
                "    #[derive(Debug)]", // 13
                "    struct S;"); // 14
        assertEquals(2, ProjectMapStructure.leadingDocStart(lines, 7));
        assertEquals(10, ProjectMapStructure.leadingDocStart(lines, 12));
        assertEquals(13, ProjectMapStructure.leadingDocStart(lines, 14));
        assertEquals(0, ProjectMapStructure.leadingDocStart(lines, 0), "nothing above the first line");
        assertEquals(9, ProjectMapStructure.leadingDocStart(lines, 9), "a closing brace is not a comment");
    }

    @Test
    void aBlockRunsFromItsDeclarationToTheEndOfItsFoldRegion() {
        List<String> lines = List.of("class A {", "  /** Doc. */", "  void m(", "      int x) {", "  }", "}");
        // A wrapped signature: the region starts on the line that closes the parameter list.
        StructureNode method = new StructureNode(new Region(3, 4), "m", "method", 2);
        assertArrayEquals(new int[] {1, 4}, ProjectMapStructure.range(method, lines, -1, 5));
    }

    @Test
    void aHeadingRunsToTheLineBeforeTheNextHeadingWithoutTheBlankLinesBetween() {
        List<String> lines = List.of("# A", "text", "## B", "more", "", "", "# C", "end");
        StructureNode a = new StructureNode(null, "A", "heading", 0);
        a.children().add(new StructureNode(null, "B", "heading", 2));
        StructureNode c = new StructureNode(null, "C", "heading", 6);
        assertArrayEquals(new int[] {0, 3}, ProjectMapStructure.range(a, lines, 6, 7));
        assertArrayEquals(new int[] {6, 7}, ProjectMapStructure.range(c, lines, -1, 7), "the last runs to the end");
    }

    @Test
    void aDeclarationWithNoBlockIsItsOwnLine() {
        List<String> lines = List.of("class A {", "  int x = 1;", "  int y = 2;", "}");
        StructureNode field = new StructureNode(null, "x", "field", 1);
        assertArrayEquals(new int[] {1, 1}, ProjectMapStructure.range(field, lines, 2, 3));
    }

    @Test
    void declarationPathsAreRecognisedByTheirLastSegment() {
        Path file = Path.of("/p/App.java");
        assertTrue(ProjectMapStructure.isSymbolPath(file.resolve(ProjectMapStructure.SEGMENT_PREFIX + "main~0")));
        assertFalse(ProjectMapStructure.isSymbolPath(file));
        assertFalse(ProjectMapStructure.isSymbolPath(file.resolve(ProjectMapModel.PLACEHOLDER_NAME)));
    }

    @Test
    void aStructureColumnKeepsTheOrderItsDeclarationsAreWrittenIn() {
        Path root = Path.of("/p").toAbsolutePath();
        Path file = root.resolve("App.java");
        ProjectMapModel.Entry project = new ProjectMapModel.Entry(root, null, 0, true);
        ProjectMapModel.Entry app = new ProjectMapModel.Entry(file, root, 1, false);
        ProjectMapModel.Entry zeta = symbol(file, "zeta", 2, 0);
        ProjectMapModel.Entry alpha = symbol(file, "alpha", 2, 5);
        List<ProjectMapModel.Column> columns =
                ProjectMapModel.columnsById(List.of(project, app, zeta, alpha), Map.of(), Map.of());
        ProjectMapModel.Column structure = columns.getLast();
        assertEquals(file, structure.parent());
        assertEquals(
                List.of("zeta()", "alpha()"),
                structure.entries().stream().map(ProjectMapModel.Entry::name).toList());
        assertTrue(structure.entries().stream().noneMatch(ProjectMapModel.Entry::isFile));
    }

    @Test
    void aDeclarationRowIsNeitherAFileNorAFolder() {
        Path file = Path.of("/p/App.java").toAbsolutePath();
        ProjectMapModel.Entry row = symbol(file, "main", 2, 3);
        assertTrue(row.isSymbol());
        assertFalse(row.isFile());
        assertFalse(row.directory());
        assertFalse(ProjectMapModel.matchesType(row, ProjectMapModel.TypeFilter.SOURCE));
    }

    private static ProjectMapModel.Entry symbol(Path file, String name, int depth, int line) {
        ProjectMapModel.Symbol symbol = new ProjectMapModel.Symbol(
                file, name + "()", "method", line, line, line, new Region(line, line), false);
        return ProjectMapModel.Entry.symbol(
                file.resolve(ProjectMapStructure.SEGMENT_PREFIX + name + "~0"), file, depth, symbol);
    }
}
