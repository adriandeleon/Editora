package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeFrameTest {

    @Test
    void theSameInputsDrawOnceAndAnythingElseDrawsAgain() {
        StripeFrame frame = new StripeFrame();
        assertFalse(frame.unchanged(true, 6, 400, 100, 1600), "nothing drawn yet");
        assertTrue(frame.unchanged(true, 6, 400, 100, 1600), "a keystroke inside a line");
        assertFalse(frame.unchanged(true, 6, 400, 101, 1616), "a new line moves the marks");
        assertFalse(frame.unchanged(true, 6, 420, 101, 1616), "a taller stripe");
        assertFalse(frame.unchanged(true, 6, 420, 101, 1632), "a line that wraps anew");
        assertTrue(frame.unchanged(true, 6, 420, 101, 1632));

        frame.forget(); // new marks or colours
        assertFalse(frame.unchanged(true, 6, 420, 101, 1632));
    }

    @Test
    void aBlankStripeIgnoresTheLinesItDoesNotDraw() {
        StripeFrame frame = new StripeFrame();
        assertFalse(frame.unchanged(false, 1, 1, 100, 1600));
        assertTrue(frame.unchanged(false, 1, 1, 250, 4000), "no marks: the line count changes nothing");
        assertFalse(frame.unchanged(true, 1, 1, 250, 4000), "marks to draw");
    }
}
