package com.editora.text;

import java.util.List;

import com.editora.text.FoldRegions.Region;
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
        // (1, 3) is the `return ( … )` itself, which folds since JS/TS review A107.
        assertEquals(List.of(new Region(0, 4), new Region(1, 3), new Region(5, 7)), sorted(tsx, "typescriptreact"));
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

    /** Dart: a ''' string spans lines, and in a raw string r'C:\' the backslash escapes nothing. */
    @Test
    void dartTripleQuotedAndRawStrings() {
        String triple = "class A {\n  final s = '''\n  it's {\n  ''';\n  void m() {\n    x();\n  }\n}\n";
        assertEquals(List.of(new Region(0, 7), new Region(4, 6)), sorted(triple, "dart"));
        String raw = "void main() {\n  final p = r'C:\\'; final q = '{';\n  if (p.isEmpty) {\n    x();\n  }\n}\n";
        assertEquals(List.of(new Region(0, 5), new Region(2, 4)), sorted(raw, "dart"));
    }

    /**
     * Elixir: do … end and fn … end blocks, multi-line brackets and heredocs fold; the keyword and atom forms
     * ({@code do:}, {@code :end}), strings, sigils, character literals and comments open and close nothing.
     */
    @Test
    void elixirDoEndBlocks() {
        String src = """
                defmodule A do
                  @moduledoc \"""
                  Has do and end inside.
                  \"""
                  def f(x), do: x
                  def g(list) do
                    Enum.map(list, fn x ->
                      x + 1
                    end)
                  end
                  def h, do: {:end, "end", ~s(do), ?", "#{"end"}"} # do
                  @map %{
                    a: 1
                  }
                end
                """;
        assertEquals(
                List.of(new Region(0, 14), new Region(1, 3), new Region(5, 9), new Region(6, 8), new Region(11, 13)),
                sorted(src, "elixir"));
    }

    /** An Elixir heredoc sigil (~H\"""…\""") folds, and the do/end inside it is markup, not a block. */
    @Test
    void elixirHeredocSigil() {
        String src = """
                def render(assigns) do
                  ~H\"""
                  <%= if @ok do %>
                    yes
                  <% end %>
                  \"""
                end
                """;
        assertEquals(List.of(new Region(0, 6), new Region(1, 5)), sorted(src, "elixir"));
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
