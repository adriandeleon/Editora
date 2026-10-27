package com.editora.editor.markdown;

import java.nio.charset.StandardCharsets;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An SVG the preview cannot draw says why and where (XML review C15): it used to say only "Could not render this
 * SVG" while the language server pointed at line 2.
 */
class SvgImagesErrorTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void aMalformedSvgNamesTheParserMessageAndItsLine() {
        String bad = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">\n"
                + "  <circle cx=\"5\" cy=\"5\" r=\"4\">\n"
                + "</svg>\n";
        SvgImages.Cached c =
                SvgImages.cachedFor(PreviewImageLoader.parseSvg(bad.getBytes(StandardCharsets.UTF_8)), bad);
        assertNull(c.document());
        assertTrue(c.error() != null && c.error().startsWith("Could not render this SVG\n"), c.error());
        assertTrue(c.error().length() > "Could not render this SVG\n".length(), "the parser's message follows");
        assertEquals(3, c.errorLine());
        assertTrue(c.errorColumn() > 0);
    }

    @Test
    void aWellFormedSvgThatStillFailsKeepsTheGenericMessage() {
        SvgImages.Cached c = SvgImages.cachedFor(null, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        assertEquals("Could not render this SVG", c.error());
        assertEquals(0, c.errorLine());
    }
}
