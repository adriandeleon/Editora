package com.editora.editor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageCacheBudgetTest {

    /** An access-ordered cache like the real ones, weighed by the Integer value itself. */
    private static Map<String, Integer> accessOrdered() {
        return Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true));
    }

    @Test
    void underBudgetEvictsNothing() {
        Map<String, Integer> c = accessOrdered();
        c.put("a", 10);
        c.put("b", 20);
        assertEquals(30, ImageCacheBudget.trim(c, Integer::longValue, 100));
        assertEquals(2, c.size());
    }

    @Test
    void evictsEldestFirstUntilItFits() {
        Map<String, Integer> c = accessOrdered();
        c.put("a", 40);
        c.put("b", 40);
        c.put("c", 40);
        assertEquals(80, ImageCacheBudget.trim(c, Integer::longValue, 100));
        assertFalse(c.containsKey("a"), "the eldest should go first");
        assertTrue(c.containsKey("b") && c.containsKey("c"));
    }

    @Test
    void oneHugeEntryEvictsSeveralSmallOnes() {
        // The count cap can only drop one entry per insert; a single 200-unit entry must be able to
        // push out every small one behind it in the same pass.
        Map<String, Integer> c = accessOrdered();
        for (int i = 0; i < 6; i++) {
            c.put("small" + i, 10);
        }
        c.put("huge", 200);
        ImageCacheBudget.trim(c, Integer::longValue, 100);
        assertEquals(1, c.size());
        assertTrue(c.containsKey("huge"), "the newest entry is never the one evicted");
    }

    @Test
    void keepsTheNewestEvenWhenItAloneExceedsTheBudget() {
        Map<String, Integer> c = accessOrdered();
        c.put("old", 10);
        c.put("enormous", 5_000);
        assertEquals(5_000, ImageCacheBudget.trim(c, Integer::longValue, 100));
        assertEquals(1, c.size());
        assertTrue(c.containsKey("enormous"));
    }

    @Test
    void recentlyReadEntriesSurviveAheadOfOlderOnes() {
        Map<String, Integer> c = accessOrdered();
        c.put("a", 40);
        c.put("b", 40);
        c.put("c", 40);
        c.get("a"); // touch: "a" is now the most recently used, "b" the eldest
        ImageCacheBudget.trim(c, Integer::longValue, 100);
        assertTrue(c.containsKey("a"), "a recently used entry must outlive an older one");
        assertFalse(c.containsKey("b"));
    }

    @Test
    void footprintIsFourBytesPerPixelAndSafeOnNull() {
        assertEquals(0, ImageCacheBudget.footprint(null));
    }

    @Test
    void budgetsAreOrderedByHowRiskyEachCacheIs() {
        // Preview images are arbitrary user content, formulas are tiny — if this ever inverts, someone
        // has mixed the constants up.
        assertTrue(ImageCacheBudget.PREVIEW_BUDGET_BYTES > ImageCacheBudget.DIAGRAM_BUDGET_BYTES);
        assertTrue(ImageCacheBudget.DIAGRAM_BUDGET_BYTES > ImageCacheBudget.MATH_BUDGET_BYTES);
    }
}
