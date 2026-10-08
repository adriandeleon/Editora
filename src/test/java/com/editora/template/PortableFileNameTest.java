package com.editora.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one definition of "a file name every platform accepts". */
class PortableFileNameTest {

    @Test
    void ordinaryNamesAreFine() {
        for (String ok : new String[] {"notes.md", ".gitignore", "My File (2).txt", "a b", "console", "com10", "x~"}) {
            assertEquals(PortableFileName.Problem.OK, PortableFileName.check(ok), ok);
        }
    }

    @Test
    void eachProblemIsToldApart() {
        assertEquals(PortableFileName.Problem.NOT_A_NAME, PortableFileName.check(""));
        assertEquals(PortableFileName.Problem.NOT_A_NAME, PortableFileName.check(".."));
        assertEquals(PortableFileName.Problem.NOT_A_NAME, PortableFileName.check(null));
        assertEquals(PortableFileName.Problem.HOME_SHORTHAND, PortableFileName.check("~"));
        assertEquals(PortableFileName.Problem.ILLEGAL_CHARACTER, PortableFileName.check("a/b"));
        assertEquals(PortableFileName.Problem.ILLEGAL_CHARACTER, PortableFileName.check("a\u0001b"));
        assertEquals(PortableFileName.Problem.ILLEGAL_CHARACTER, PortableFileName.check("why?"));
        assertEquals(PortableFileName.Problem.TRAILING_DOT_OR_SPACE, PortableFileName.check("name."));
        assertEquals(PortableFileName.Problem.TRAILING_DOT_OR_SPACE, PortableFileName.check("name "));
        assertEquals(PortableFileName.Problem.RESERVED_NAME, PortableFileName.check("NUL"));
        assertEquals(PortableFileName.Problem.RESERVED_NAME, PortableFileName.check("lpt1.log"));
    }

    @Test
    void aKnownExtensionIsALanguageACatalogTypeOrACommonDocumentType() {
        for (String known : new String[] {"java", "md", "JSON", "txt", "sh", "yaml", "typ", "csv", "png"}) {
            assertTrue(PortableFileName.isKnownExtension(known), known);
        }
        for (String unknown : new String[] {"", "2", "10", "v2", "final", "a b", null}) {
            assertFalse(PortableFileName.isKnownExtension(unknown), String.valueOf(unknown));
        }
    }
}
