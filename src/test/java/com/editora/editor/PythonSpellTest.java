package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Spell check in Python source (review finding A11). */
class PythonSpellTest {

    private static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        for (int[] s : SpellChecker.checkableWords(line, SpellChecker.Syntax.PLAIN)) {
            out.add(line.substring(s[0], s[1]));
        }
        return out;
    }

    @Test
    void theFirstWordOfAPrefixedStringIsCheckedWithoutItsPrefix() {
        // It used to be skipped (`f"Recieved` read as a structured token) or checked with `f'` glued on.
        assertEquals(List.of("msg", "Recieved", "itmes"), words("msg = f\"Recieved {len(data)} itmes\""));
        assertEquals(List.of("msg", "Recieved", "itmes"), words("msg = f'Recieved itmes'"));
        assertEquals(List.of("Recieved", "docstring"), words("rb\"\"\"Recieved docstring\"\"\""));
        // Words that only look like a prefix keep their apostrophe.
        assertEquals(List.of("it's", "fine"), words("it's fine"));
    }

    @Test
    void commonPythonTermsAreKnownWords() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of());
        for (String w :
                List.of("kwargs", "asyncio", "argparse", "subparsers", "noqa", "pyright", "dataclass", "isinstance")) {
            assertFalse(c.isMisspelled(w), w);
        }
        assertTrue(c.isMisspelled("recieve"));
    }
}
