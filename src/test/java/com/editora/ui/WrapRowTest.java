package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Line breaking of {@link WrapRow} (pure). */
class WrapRowTest {

    @Test
    void everythingFitsOnOneLine() {
        assertArrayEquals(new int[] {3}, WrapRow.lineEnds(new double[] {300, 370, 200}, 1000, 10));
    }

    @Test
    void trailingItemsWrapWhenTheRowIsNarrow() {
        assertArrayEquals(new int[] {2, 3}, WrapRow.lineEnds(new double[] {300, 370, 200}, 700, 10));
        assertArrayEquals(new int[] {1, 2, 3}, WrapRow.lineEnds(new double[] {300, 370, 200}, 400, 10));
    }

    @Test
    void gapsCountTowardsTheWidth() {
        assertArrayEquals(new int[] {2}, WrapRow.lineEnds(new double[] {100, 100}, 210, 10));
        assertArrayEquals(new int[] {1, 2}, WrapRow.lineEnds(new double[] {100, 100}, 205, 10));
    }

    @Test
    void anItemWiderThanTheRowGetsItsOwnLine() {
        assertArrayEquals(new int[] {1, 2}, WrapRow.lineEnds(new double[] {500, 100}, 300, 10));
        assertArrayEquals(new int[] {1, 2}, WrapRow.lineEnds(new double[] {100, 500}, 300, 10));
    }

    @Test
    void emptyRowHasNoLines() {
        assertArrayEquals(new int[] {}, WrapRow.lineEnds(new double[] {}, 300, 10));
    }
}
