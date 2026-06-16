package com.editora.editorconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorConfigTransformTest {

    private static EditorConfigProperties props(Boolean trim, Boolean finalNl, String eol) {
        return new EditorConfigProperties(null, null, null, eol, null, trim, finalNl, null);
    }

    @Test
    void noRelevantPropsLeavesContentUntouched() {
        String s = "a  \r\nb\t\n"; // mixed EOL + trailing ws, but no trim/eol/final-newline set
        assertEquals(s, EditorConfigTransform.transform(s, EditorConfigProperties.EMPTY));
    }

    @Test
    void trimsTrailingWhitespacePerLine() {
        assertEquals("a\nb\n", EditorConfigTransform.transform("a   \nb\t\t\n", props(true, null, null)));
    }

    @Test
    void insertsFinalNewlineWhenMissing() {
        assertEquals("a\n", EditorConfigTransform.transform("a", props(null, Boolean.TRUE, null)));
        // Already present → unchanged.
        assertEquals("a\n", EditorConfigTransform.transform("a\n", props(null, Boolean.TRUE, null)));
    }

    @Test
    void stripsFinalNewlineWhenDisabled() {
        assertEquals("a", EditorConfigTransform.transform("a\n\n", props(null, Boolean.FALSE, null)));
    }

    @Test
    void normalizesEol() {
        assertEquals("a\r\nb\r\n", EditorConfigTransform.transform("a\nb\n", props(null, null, "crlf")));
        assertEquals("a\nb\n", EditorConfigTransform.transform("a\r\nb\r\n", props(null, null, "lf")));
        assertEquals("a\rb", EditorConfigTransform.transform("a\r\nb", props(null, null, "cr")));
    }

    @Test
    void combinedAndIdempotent() {
        EditorConfigProperties p = props(Boolean.TRUE, Boolean.TRUE, "lf");
        String once = EditorConfigTransform.transform("x  \r\ny   ", p);
        assertEquals("x\ny\n", once);
        assertEquals(once, EditorConfigTransform.transform(once, p)); // idempotent
    }
}
