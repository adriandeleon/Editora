package com.editora.editor.markdown;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parsed-SVG cache is bounded in bytes (estimated from the source size), not only in entries. */
class SvgImagesBudgetTest {

    private static SvgImages.Cached entryOf(int sourceChars) {
        var doc = PreviewImageLoader.parseSvg(
                "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>".getBytes(StandardCharsets.UTF_8));
        assertNotNull(doc);
        return SvgImages.cachedFor(doc, sourceChars);
    }

    @Test
    void aFewLargeDocumentsEvictTheOlderOnesLongBeforeTheEntryCap() {
        int chars = (int) (ImageCacheBudget.SVG_BUDGET_BYTES / ImageCacheBudget.SVG_BYTES_PER_SOURCE_CHAR / 2) + 1;
        SvgImages.Cached big = entryOf(chars); // just over half the budget each: two do not fit together
        assertTrue(big.weight() * 2 > ImageCacheBudget.SVG_BUDGET_BYTES);
        SvgImages.remember("budget-test-1", big);
        SvgImages.remember("budget-test-2", big);
        assertFalse(SvgImages.isCachedKey("budget-test-1"), "the eldest goes when the bytes do not fit");
        assertTrue(SvgImages.isCachedKey("budget-test-2"), "the newest is always kept");
        SvgImages.remember("budget-test-3", entryOf(10));
        assertTrue(SvgImages.isCachedKey("budget-test-2") && SvgImages.isCachedKey("budget-test-3"));
    }

    @Test
    void aFailedParseWeighsNothingAndTheEstimateIsLinearInTheSource() {
        assertEquals(0, SvgImages.cachedFor(null, 1_000_000).weight());
        assertEquals(8_000, ImageCacheBudget.svgFootprint(1_000));
        assertEquals(0, ImageCacheBudget.svgFootprint(-5));
    }
}
