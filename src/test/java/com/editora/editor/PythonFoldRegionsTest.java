package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Python folds by indentation (review finding A3): it used to have no fold regions at all. */
class PythonFoldRegionsTest {

    private static final String SOURCE = String.join(
            "\n",
            "\"\"\"Module.", // 0
            "", // 1
            "More.\"\"\"", // 2
            "import os", // 3
            "", // 4
            "", // 5
            "@dataclass", // 6
            "class Point:", // 7
            "    x: int", // 8
            "", // 9
            "    def norm(", // 10
            "        self,", // 11
            "    ) -> float:", // 12
            "        if self.x:", // 13
            "            return 1.0", // 14
            "# a comment at column 0 inside the method", // 15
            "        return (", // 16
            "            2.0)", // 17
            "", // 18
            "    def one(self): return 1", // 19
            "", // 20
            "", // 21
            "TABLE = {", // 22
            "    'a': 1,", // 23
            "}", // 24
            "s = '''if x:", // 25
            "    not code'''", // 26
            "");

    @Test
    void blocksFoldOverTheirBodyAndMultiLineStatementsOverTheirLines() {
        List<Region> regions = FoldRegions.detect(SOURCE, "python");
        assertEquals(
                List.of(
                        new Region(0, 2), // the module docstring
                        new Region(13, 14), // if
                        new Region(16, 17), // a wrapped return
                        new Region(10, 17), // def norm: a wrapped header, through the body, past the comment
                        new Region(7, 19), // class Point, through the one-line def; trailing blank lines stay outside
                        new Region(22, 24), // a multi-line literal
                        new Region(25, 26)), // a string that spans lines — its `if x:` is not a block
                regions);
    }

    @Test
    void stickyScrollNowHasPythonScopesToPin() {
        List<Region> regions = FoldRegions.detect(SOURCE, "python");
        // Scrolled to the `if` body: the class and the method stay pinned above it.
        assertEquals(List.of(7, 10, 13), StickyScroll.headerLines(regions, 14, 5));
    }

    @Test
    void aTabIndentedFileAndAFileWithoutBlocks() {
        assertEquals(List.of(new Region(0, 2)), FoldRegions.detect("if x:\n\ta = 1\n\tb = 2\nc = 3\n", "python"));
        assertTrue(FoldRegions.detect("a = 1\nb = 2\n", "python").isEmpty());
        assertTrue(FoldRegions.detect("if x: pass\n", "python").isEmpty(), "a one-line block folds nothing");
    }
}
