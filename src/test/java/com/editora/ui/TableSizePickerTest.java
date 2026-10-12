package com.editora.ui;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TableSizePickerTest {

    @Test
    void arrowKeysGrowAndShrinkTheSizeInsideTheGrid() {
        assertArrayEquals(new int[] {3, 4}, TableSizePicker.step(3, 3, KeyCode.RIGHT, 8, 8));
        assertArrayEquals(new int[] {3, 2}, TableSizePicker.step(3, 3, KeyCode.LEFT, 8, 8));
        assertArrayEquals(new int[] {4, 3}, TableSizePicker.step(3, 3, KeyCode.DOWN, 8, 8));
        assertArrayEquals(new int[] {2, 3}, TableSizePicker.step(3, 3, KeyCode.UP, 8, 8));
        assertArrayEquals(new int[] {8, 8}, TableSizePicker.step(8, 8, KeyCode.RIGHT, 8, 8), "clamped");
        assertArrayEquals(new int[] {1, 1}, TableSizePicker.step(1, 1, KeyCode.UP, 8, 8));
        assertArrayEquals(new int[] {1, 1}, TableSizePicker.step(5, 5, KeyCode.HOME, 8, 8));
        assertArrayEquals(new int[] {8, 8}, TableSizePicker.step(1, 1, KeyCode.END, 8, 8));
        assertNull(TableSizePicker.step(3, 3, KeyCode.A, 8, 8), "any other key is not the picker's");
    }
}
