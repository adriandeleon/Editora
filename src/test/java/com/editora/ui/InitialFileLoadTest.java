package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitialFileLoadTest {

    @Test
    void textStatsFindsLineCountAndWidestLineInOnePass() {
        assertEquals(new MainController.TextStats(1, 0), MainController.textStats(""));
        assertEquals(new MainController.TextStats(3, 5), MainController.textStats("abc\n12345\nx"));
        assertEquals(new MainController.TextStats(2, 4), MainController.textStats("four\n"));
    }
}
