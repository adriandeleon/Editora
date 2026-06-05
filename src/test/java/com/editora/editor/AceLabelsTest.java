package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class AceLabelsTest {

    @Test
    void singleCharWhileTheyFit() {
        assertEquals(List.of("a", "b", "c"), AceLabels.labels(3, "abcdef"));
        assertEquals(6, AceLabels.labels(6, "abcdef").size());
    }

    @Test
    void uniformTwoCharWhenOverCapacity() {
        assertEquals(List.of("aa", "ab", "ba", "bb"), AceLabels.labels(4, "ab"));
    }

    @Test
    void cappedAtAlphabetSquared() {
        // alphabet "ab" → at most 4 labels even when more targets are requested
        assertEquals(4, AceLabels.labels(99, "ab").size());
    }

    @Test
    void labelsAreUnique() {
        List<String> labels = AceLabels.labels(50, AceLabels.DEFAULT_ALPHABET);
        assertEquals(50, labels.size());
        assertEquals(50, new HashSet<>(labels).size());
        // none is a prefix of another (all 1-char, or all 2-char)
        int len = labels.get(0).length();
        assertTrue(labels.stream().allMatch(s -> s.length() == len));
    }

    @Test
    void edgeCases() {
        assertTrue(AceLabels.labels(0, "abc").isEmpty());
        assertTrue(AceLabels.labels(5, "").isEmpty());
    }
}
