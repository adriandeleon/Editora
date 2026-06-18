package com.editora.externaltool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** $Name$ macro expansion: each token, unknown left literal, empty selection, $$ escape. */
class ToolMacrosTest {

    private static ToolContext ctx() {
        return new ToolContext(
                "/home/me/proj/src/App.java",
                "/home/me/proj/src",
                "App.java",
                "App",
                "selected bit",
                12,
                5,
                "/home/me/proj",
                "the whole buffer");
    }

    @Test
    void expandsEachMacro() {
        ToolContext c = ctx();
        assertEquals("/home/me/proj/src/App.java", ToolMacros.expand("$FilePath$", c));
        assertEquals("/home/me/proj/src", ToolMacros.expand("$FileDir$", c));
        assertEquals("App.java", ToolMacros.expand("$FileName$", c));
        assertEquals("App", ToolMacros.expand("$FileNameWithoutExtension$", c));
        assertEquals("selected bit", ToolMacros.expand("$SelectedText$", c));
        assertEquals("12", ToolMacros.expand("$LineNumber$", c));
        assertEquals("5", ToolMacros.expand("$ColumnNumber$", c));
        assertEquals("/home/me/proj", ToolMacros.expand("$ProjectFileDir$", c));
    }

    @Test
    void mixesLiteralsAndMacros() {
        assertEquals("-l /home/me/proj/src/App.java now", ToolMacros.expand("-l $FilePath$ now", ctx()));
    }

    @Test
    void unknownMacroLeftLiteral() {
        assertEquals("$Nope$ x", ToolMacros.expand("$Nope$ x", ctx()));
    }

    @Test
    void doubleDollarIsLiteralDollar() {
        assertEquals("cost $5", ToolMacros.expand("cost $$5", ctx()));
    }

    @Test
    void emptyValuesAndDanglingDollar() {
        ToolContext empty = new ToolContext("", "", "", "", "", 1, 1, "", "");
        assertEquals("", ToolMacros.expand("$SelectedText$", empty));
        assertEquals("a $", ToolMacros.expand("a $", ctx())); // dangling $ copied verbatim
        assertEquals("", ToolMacros.expand(null, ctx()));
    }
}
