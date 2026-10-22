package com.editora.editor.minimap;

import com.editora.editor.ImageCacheBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hidden-tab minimap pixel store: bounded by count and by bytes, least recently shown out first. */
class MinimapRetentionTest {

    private static MinimapRetention.Entry entry(int pixels) {
        return new MinimapRetention.Entry(new int[pixels], pixels, 1, pixels, 1);
    }

    @Test
    void keepsAnEntryUntilItIsTakenOrEvicted() {
        MinimapRetention r = new MinimapRetention(4, 1 << 20);
        Object key = new Object();
        MinimapRetention.Entry e = entry(100);
        r.put(key, e);
        assertSame(e, r.get(key));
        assertEquals(400, r.bytes());
        r.remove(key);
        assertNull(r.get(key));
        assertEquals(0, r.size());
    }

    @Test
    void theCountBoundEvictsTheLeastRecentlyUsed() {
        MinimapRetention r = new MinimapRetention(2, 1 << 20);
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        r.put(a, entry(10));
        r.put(b, entry(10));
        assertNotNull(r.get(a)); // a is now the most recently used
        r.put(c, entry(10));
        assertEquals(2, r.size());
        assertNull(r.get(b), "b was the least recently used");
        assertNotNull(r.get(a));
        assertNotNull(r.get(c));
    }

    @Test
    void theByteBoundEvictsTooButNeverTheEntryJustKept() {
        MinimapRetention r = new MinimapRetention(16, 1000); // 250 pixels in all
        Object a = new Object();
        Object b = new Object();
        Object big = new Object();
        r.put(a, entry(100));
        r.put(b, entry(100));
        assertEquals(800, r.bytes());
        r.put(big, entry(200)); // 1600 bytes: over budget, so the older entries go
        assertTrue(r.bytes() <= 1000 || r.size() == 1, "trimmed to the budget, or down to the newest entry");
        assertNotNull(r.get(big), "the entry just kept survives even alone over budget");
        assertNull(r.get(a));
    }

    @Test
    void theSharedStoreIsBoundedByTheCatalogBudget() {
        assertEquals(16L * 1024 * 1024, ImageCacheBudget.MINIMAP_BUDGET_BYTES);
        assertEquals(16, MinimapRetention.MAX_ENTRIES);
    }
}
