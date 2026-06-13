package com.example.editora.lorem;

import java.util.Random;

/**
 * Pure lorem-ipsum text generator: builds random sentences from the classic word bank and assembles them
 * into paragraphs. No editor/JavaFX dependency, so it's trivial to reason about (and unit-test).
 */
final class LoremIpsum {

    private static final String[] WORDS = {
        "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
        "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "enim",
        "ad", "minim", "veniam", "quis", "nostrud", "exercitation", "ullamco", "laboris", "nisi", "aliquip",
        "ex", "ea", "commodo", "consequat", "duis", "aute", "irure", "in", "reprehenderit", "voluptate",
        "velit", "esse", "cillum", "eu", "fugiat", "nulla", "pariatur", "excepteur", "sint", "occaecat",
        "cupidatat", "non", "proident", "sunt", "culpa", "qui", "officia", "deserunt", "mollit", "anim",
        "id", "est", "laborum", "at", "vero", "eos", "accusamus", "iusto", "odio", "dignissimos"
    };

    /** The canonical opening, used as the first sentence of the first paragraph. */
    private static final String OPENER = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";

    private final Random random;

    LoremIpsum(Random random) {
        this.random = random;
    }

    LoremIpsum() {
        this(new Random());
    }

    /** A single sentence of {@code wordCount} words: capitalized first word, optional commas, trailing period. */
    String sentence(int wordCount) {
        int n = Math.max(2, wordCount);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = WORDS[random.nextInt(WORDS.length)];
            if (i == 0) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            sb.append(w);
            // an occasional comma mid-sentence (never on the last two words)
            if (i < n - 2 && random.nextInt(8) == 0) {
                sb.append(',');
            }
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        sb.append('.');
        return sb.toString();
    }

    /** A paragraph of {@code sentences} sentences (each 6–14 words). */
    String paragraph(int sentences) {
        return paragraph(sentences, false);
    }

    /** As {@link #paragraph(int)}, but {@code classicOpener} forces the canonical first sentence. */
    String paragraph(int sentences, boolean classicOpener) {
        int n = Math.max(1, sentences);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i == 0 && classicOpener) {
                sb.append(OPENER);
            } else {
                sb.append(sentence(6 + random.nextInt(9)));
            }
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** A standard paragraph (4–6 sentences) that opens with the canonical line. */
    String paragraph() {
        return paragraph(4 + random.nextInt(3), true);
    }
}
