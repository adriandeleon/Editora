package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link HeadingLinks#linePlace}: the {@code #L<line>[,<column>]} fragments of hover links (review 2, E7). */
class HeadingLinksLinePlaceTest {

    @Test
    void aLineFragmentNamesALineAndColumn() {
        assertArrayEquals(new int[] {27, 21}, HeadingLinks.linePlace("L28,22-L28,22"), "lemminx-maven's range");
        assertArrayEquals(new int[] {27, 21}, HeadingLinks.linePlace("L28,22"));
        assertArrayEquals(new int[] {9, 0}, HeadingLinks.linePlace("L10"), "GitHub's line link");
        assertArrayEquals(new int[] {9, 4}, HeadingLinks.linePlace("L10C5"));
        assertArrayEquals(new int[] {0, 0}, HeadingLinks.linePlace("L0"), "clamped to the first line");
    }

    @Test
    void anyOtherFragmentIsAHeadingNotALine() {
        assertNull(HeadingLinks.linePlace("installation"));
        assertNull(HeadingLinks.linePlace("l28"), "a heading slug is lower case");
        assertNull(HeadingLinks.linePlace("L28-notes"));
        assertNull(HeadingLinks.linePlace(""));
        assertNull(HeadingLinks.linePlace(null));
    }
}
