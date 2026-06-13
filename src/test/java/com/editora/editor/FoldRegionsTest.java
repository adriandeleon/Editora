package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.editor.FoldRegions.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoldRegionsTest {

    private static boolean hasRegion(List<Region> regions, int start, int end) {
        return regions.contains(new Region(start, end));
    }

    @Test
    void emptyTextHasNoRegions() {
        assertTrue(FoldRegions.detect("", "java").isEmpty());
        assertTrue(FoldRegions.detect(null, "java").isEmpty());
    }

    @Test
    void plaintextNeverFolds() {
        assertTrue(FoldRegions.detect("{\n}\n", "plaintext").isEmpty());
    }

    @Test
    void bracesFoldFromOpenToCloseLine() {
        String java = "class A {\n    void m() {\n        x();\n    }\n}\n";
        List<Region> regions = FoldRegions.detect(java, "java");
        assertTrue(hasRegion(regions, 0, 4), "outer class braces");
        assertTrue(hasRegion(regions, 1, 3), "inner method braces");
    }

    @Test
    void bracesOnSameLineDoNotFold() {
        List<Region> regions = FoldRegions.detect("int[] a = {1, 2, 3};\n", "java");
        assertTrue(regions.isEmpty());
    }

    @Test
    void bracesInStringsAndCommentsAreIgnored() {
        String java = "String s = \"{\";\n// }\nint x = 1;\n";
        assertTrue(FoldRegions.detect(java, "java").isEmpty());
    }

    @Test
    void jsonBracketsFold() {
        String json = "{\n  \"a\": [\n    1,\n    2\n  ]\n}\n";
        List<Region> regions = FoldRegions.detect(json, "json");
        assertTrue(hasRegion(regions, 0, 5), "object braces");
        assertTrue(hasRegion(regions, 1, 4), "array brackets");
    }

    @Test
    void xmlFoldsMatchingElements() {
        String xml = "<root>\n  <child>\n    text\n  </child>\n</root>\n";
        List<Region> regions = FoldRegions.detect(xml, "xml");
        assertTrue(hasRegion(regions, 0, 4), "root element");
        assertTrue(hasRegion(regions, 1, 3), "child element");
    }

    @Test
    void xmlSelfClosingTagsDoNotFold() {
        String xml = "<root>\n  <item/>\n</root>\n";
        List<Region> regions = FoldRegions.detect(xml, "xml");
        assertTrue(hasRegion(regions, 0, 2));
        assertFalse(hasRegion(regions, 1, 1));
    }

    @Test
    void markdownHeadingsFoldTheirSections() {
        String md = "# Title\n\nintro\n\n## Section\n\nbody\n";
        List<Region> regions = FoldRegions.detect(md, "markdown");
        // "# Title" (level 1) encompasses the nested "## Section" down to the last non-blank line (6).
        assertTrue(hasRegion(regions, 0, 6), "top heading section");
        // "## Section" (line 4) folds its own subsection to the last non-blank line (6).
        assertTrue(hasRegion(regions, 4, 6), "subsection");
    }

    @Test
    void markdownFencedCodeBlocksFold() {
        String md = "text\n```\ncode line\nmore code\n```\n";
        List<Region> regions = FoldRegions.detect(md, "markdown");
        assertTrue(hasRegion(regions, 1, 4), "fenced code block");
    }
}
