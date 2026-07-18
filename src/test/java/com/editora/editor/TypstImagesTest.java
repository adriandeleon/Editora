package com.editora.editor;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #461: the Typst preview cache must be bounded by total <em>pages</em> (each page pins a
 * decoded {@link javafx.scene.image.Image}), not by entry count — one entry is a whole multi-page document,
 * so an 8-entry LRU let a 40-page doc retain ~570 MB. {@link TypstImages#keysToEvict} is the pure eviction
 * decision.
 */
class TypstImagesTest {

    private static Map.Entry<String, Integer> e(String k, int pages) {
        return Map.entry(k, pages);
    }

    @Test
    void nothingEvictedWhenUnderBudget() {
        assertEquals(List.of(), TypstImages.keysToEvict(List.of(e("a", 10), e("b", 10)), 60));
    }

    @Test
    void evictsOldestUntilWithinThePageBudget() {
        // Three 40-page docs = 120 pages, budget 60. Drop the two oldest, keep the newest "c" (40 ≤ 60).
        List<String> out = TypstImages.keysToEvict(List.of(e("a", 40), e("b", 40), e("c", 40)), 60);
        assertEquals(List.of("a", "b"), out);
    }

    @Test
    void neverEvictsTheNewestEvenWhenItAloneExceedsTheBudget() {
        // The just-rendered (visible) document must always stay cached.
        assertEquals(List.of("a"), TypstImages.keysToEvict(List.of(e("a", 10), e("big", 100)), 60));
        assertEquals(List.of(), TypstImages.keysToEvict(List.of(e("big", 100)), 60), "the only entry is kept");
    }
}
