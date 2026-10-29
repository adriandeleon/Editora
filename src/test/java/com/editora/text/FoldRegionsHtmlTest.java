package com.editora.text;

import java.util.List;

import com.editora.text.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTML folding without the server: names in any case, raw-text content not read as tags (HTML review C11). */
class FoldRegionsHtmlTest {

    @Test
    void anUppercaseElementFoldsToItsLowercaseEndTag() {
        assertEquals(List.of(new Region(0, 2)), FoldRegions.detect("<DIV>\n  x\n</div>", "html"));
    }

    @Test
    void aScriptWithAComparisonStillFolds() {
        String html = "<body>\n<script>\n  if (a<b) {\n    x = '<span>';\n  }\n</script>\n<p>\n</p>\n</body>";
        List<Region> regions = FoldRegions.detect(html, "html");
        assertTrue(regions.contains(new Region(1, 5)), regions.toString());
        assertTrue(regions.contains(new Region(6, 7)), regions.toString());
        assertTrue(regions.contains(new Region(0, 8)), regions.toString());
    }

    @Test
    void voidElementsOpenNothingAndTemplatesFoldAsHtml() {
        String html = "<ul>\n<li><br>\n</li>\n</ul>";
        assertEquals(FoldRegions.detect(html, "html"), FoldRegions.detect(html, "html-template"));
        assertTrue(FoldRegions.detect(html, "html").contains(new Region(0, 3)));
        assertTrue(FoldRegions.detect(html, "handlebars").contains(new Region(1, 2)));
    }

    @Test
    void xmlStaysCaseSensitive() {
        assertEquals(List.of(), FoldRegions.detect("<A>\n</a>", "xml"));
    }
}
