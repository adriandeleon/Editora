package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover is as wide as its code needs (Python review B12): Pyright's signature block scrolled sideways in a
 * popup sized to the docstring's prose.
 */
class LspHoverWidthTest {

    @Test
    void theLongestCodeLineSetsTheWidthUpToTheMaximum() {
        String hover =
                "```python\n(function) def helper(\n    count: int,\n    label: str = \"x\"\n) -> str\n```\n---\n"
                        + "Return the label repeated with some long prose that wraps.";
        double width = LspCoordinator.hoverCodeWidth(hover);
        assertEquals(22 * LspCoordinator.HOVER_CODE_CHAR_WIDTH + LspCoordinator.HOVER_CODE_CHROME, width, 0.001);
        assertTrue(width > 240, "wider than the prose-sized popup the signature was cut in");
        assertEquals(0, LspCoordinator.hoverCodeWidth("just prose, no code"), 0.0);
        assertEquals(
                LspCoordinator.HOVER_MAX_WIDTH, LspCoordinator.hoverCodeWidth("```\n" + "x".repeat(400) + "\n```"));
    }
}
