package com.editora.editor.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The previews' remembered cache keys: found for exactly the source they were worked out for, and no other. */
class SourceKeysTest {

    @Test
    void aKeyIsFoundOnlyForTheSameSourceAndTag() {
        SourceKeys keys = new SourceKeys();
        String source = "graph TD; " + "A-->B; ".repeat(40) + "end";
        assertNull(keys.knownKey("l", source), "nothing worked out yet");
        keys.remember("l", source, "k1");
        assertEquals("k1", keys.knownKey("l", new String(source.toCharArray())), "an equal source, another string");
        assertNull(keys.knownKey("d", source), "another theme is another key");
        String sameEnds = source.substring(0, 60) + "X" + source.substring(61);
        assertEquals(source.length(), sameEnds.length());
        assertNull(keys.knownKey("l", sameEnds), "same length and ends, other middle: not this key");
    }

    @Test
    void itIsBounded() {
        SourceKeys keys = new SourceKeys();
        for (int i = 0; i < 600; i++) {
            keys.remember("", "source " + i, "k" + i);
        }
        assertNull(keys.knownKey("", "source 0"), "the oldest went");
        assertEquals("k599", keys.knownKey("", "source 599"));
    }
}
