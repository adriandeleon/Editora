package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordCountTest {

    @Test
    void countsWordsNotMarkup() {
        assertEquals(0, WordCount.words(null));
        assertEquals(0, WordCount.words(""));
        assertEquals(0, WordCount.words("  \n\t ## ** --- | ` "));
        assertEquals(2, WordCount.words("hello world"));
        assertEquals(4, WordCount.words("# A **bold** claim, twice."));
        assertEquals(3, WordCount.words("don't well-known it’s"));
        assertEquals(2, WordCount.words("trailing- 'quoted'"));
        assertEquals(3, WordCount.words("über 2024 naïve"));
        assertEquals(2, WordCount.words("- [ ] task one"));
        assertEquals(1, WordCount.words("one two three", 4, 7));
    }

    @Test
    void readingTimeRoundsUp() {
        assertEquals(0, WordCount.readingMinutes(0));
        assertEquals(1, WordCount.readingMinutes(1));
        assertEquals(1, WordCount.readingMinutes(200));
        assertEquals(2, WordCount.readingMinutes(201));
        assertEquals(10, WordCount.readingMinutes(2000));
    }
}
