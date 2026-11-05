package com.editora.editor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The completion documentation panel is as wide as its code needs, as the hover card is: Pyright's
 * {@code os.path.abspath} overloads were cut behind a sideways scroll bar at 420 px (Python review 2, C106).
 */
class CompletionDocWidthTest {

    private static final String OVERLOADS = "```python\n"
            + "def abspath(path: PathLike[AnyStr@abspath]) -> AnyStr@abspath: ...\n"
            + "def abspath(path: AnyStr@abspath) -> AnyStr@abspath: ...\n"
            + "```\n\nReturn an absolute path.";

    @Test
    void proseAndShortCodeKeepTheUsualWidth() {
        assertEquals(420, CompletionDocPopup.widthFor(null), 0.0);
        assertEquals(420, CompletionDocPopup.widthFor("Return an absolute path."), 0.0);
        assertEquals(420, CompletionDocPopup.widthFor("```python\ndef f() -> int\n```"), 0.0);
    }

    @Test
    void aLongSignatureWidensThePanelUpToItsMaximum() {
        String fifty = "```python\n" + "x".repeat(50) + "\n```";
        assertEquals(50 * 9.0 + 72, CompletionDocPopup.widthFor(fifty), 0.001);
        assertEquals(66 * 9.0 + 72, CompletionDocPopup.widthFor(OVERLOADS), 0.001, "the overloads uncut");
        String hundred = "```python\n" + "x".repeat(100) + "\n```";
        assertEquals(CompletionDocPopup.MAX_WIDTH, CompletionDocPopup.widthFor(hundred), 0.0);
    }

    /** Beside the list it narrows rather than flipping over it; it flips only when the left has the room. */
    @Test
    void itNarrowsBesideTheListBeforeFlipping() {
        assertEquals(600, CompletionDocPopup.fitWidth(600, 900, 100), 0.0, "fits on the right");
        assertEquals(500, CompletionDocPopup.fitWidth(600, 500, 100), 0.0, "narrower on the right");
        assertEquals(600, CompletionDocPopup.fitWidth(600, 200, 700), 0.0, "on the left, where it fits");
        assertEquals(450, CompletionDocPopup.fitWidth(600, 200, 450), 0.0, "narrower on the left");
        assertEquals(420, CompletionDocPopup.fitWidth(600, 100, 100), 0.0, "no room: the usual width");
        assertEquals(420, CompletionDocPopup.fitWidth(420, 100, 100), 0.0);
    }
}
