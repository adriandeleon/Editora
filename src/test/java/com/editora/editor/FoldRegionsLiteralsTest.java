package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brace folding must survive the quote characters real code contains: one unpaired {@code '} or {@code "}
 * used to open a "string" that ran across lines and swallowed every brace after it.
 */
class FoldRegionsLiteralsTest {

    private static List<Region> sorted(String text, String language) {
        return FoldRegions.detect(text, language).stream()
                .sorted(java.util.Comparator.comparingInt(Region::startLine))
                .toList();
    }

    @Test
    void rustLifetimesAndLoopLabelsAreNotCharLiterals() {
        String rust =
                "mod m {\n    struct Parser<'a> {\n        input: &'a str,\n    }\n    fn other() {\n        x();\n    }\n}\n";
        assertEquals(List.of(new Region(0, 7), new Region(1, 3), new Region(4, 6)), sorted(rust, "rust"));
        String label =
                "fn main() {\n    'outer: for i in 0..3 {\n        let c = '{';\n        let q = '\\'';\n    }\n}\n";
        assertEquals(List.of(new Region(0, 5), new Region(1, 4)), sorted(label, "rust"));
    }

    @Test
    void anApostropheInATemplateLiteralOrRawString() {
        String ts =
                "function f(name) {\n    const msg = `Don't panic, ${name}`;\n    if (name) {\n        g();\n    }\n}\n";
        assertEquals(List.of(new Region(0, 5), new Region(2, 4)), sorted(ts, "typescript"));
        String go = "func f() {\n\tq := `it's {`\n\tif q != \"\" {\n\t\tg()\n\t}\n}\n";
        assertEquals(List.of(new Region(0, 5), new Region(2, 4)), sorted(go, "go"));
    }

    @Test
    void anUnpairedQuoteOnlyCostsItsOwnLine() {
        String tsx =
                "function A() {\n  return (\n    <p>Don't have an account?</p>\n  );\n}\nfunction B() {\n  b();\n}\n";
        assertEquals(List.of(new Region(0, 4), new Region(5, 7)), sorted(tsx, "typescriptreact"));
        String js = "function a() {\n  return s.replace(/'/g, x);\n}\nfunction b() {\n  c();\n}\n";
        assertEquals(List.of(new Region(0, 2), new Region(3, 5)), sorted(js, "javascript"));
        String c = "#error don't build this\nint main() {\n    return 0;\n}\n";
        assertEquals(List.of(new Region(1, 3)), sorted(c, "c"));
    }

    @Test
    void hashCommentsWhereTheLanguageHasThem() {
        String tf = "resource \"a\" \"b\" {\n  # don't change this {\n  x = 1\n}\nlocals {\n  y = 2\n}\n";
        assertEquals(List.of(new Region(0, 3), new Region(4, 6)), sorted(tf, "terraform"));
        String php =
                "<?php\nclass A {\n    #[Pure]\n    function f() {\n        # don't touch }\n        return 1;\n    }\n}\n";
        assertEquals(List.of(new Region(1, 7), new Region(3, 6)), sorted(php, "php"));
    }

    @Test
    void javaTextBlockIsOneLiteral() {
        String java =
                "class A {\n    String s = \"\"\"\n        {\"it's\": \"{\"}\n        \"\"\";\n    void m() {\n        x();\n    }\n}\n";
        assertEquals(List.of(new Region(0, 7), new Region(4, 6)), sorted(java, "java"));
    }

    @Test
    void cssUrlIsNotALineComment() {
        String css = "@media print {\n  .a { background: url(http://x/a.png); }\n  .b {\n    color: red;\n  }\n}\n";
        assertEquals(List.of(new Region(0, 5), new Region(2, 4)), sorted(css, "css"));
    }

    @Test
    void ordinaryStringsAndCommentsStillHideTheirBraces() {
        String java = "class A {\n    String s = \"{\"; // {\n    char c = '{';\n    /* { */\n}\n";
        assertEquals(List.of(new Region(0, 4)), sorted(java, "java"));
        assertTrue(FoldRegions.blockComments("a = `/*\n*/`;\n/*\n x\n*/\n", "javascript")
                .equals(List.of(new Region(2, 4))));
    }
}
