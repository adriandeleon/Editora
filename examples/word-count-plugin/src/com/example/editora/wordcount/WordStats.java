package com.example.editora.wordcount;

/** Pure text statistics. */
final class WordStats {

    final int lines;
    final int words;
    final int chars;
    final int charsNoSpace;
    final int readingSeconds; // at ~200 wpm

    private WordStats(int lines, int words, int chars, int charsNoSpace, int readingSeconds) {
        this.lines = lines;
        this.words = words;
        this.chars = chars;
        this.charsNoSpace = charsNoSpace;
        this.readingSeconds = readingSeconds;
    }

    static WordStats of(String text) {
        if (text == null) {
            text = "";
        }
        int chars = text.length();
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        String trimmed = text.strip();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        int noSpace = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                noSpace++;
            }
        }
        int readingSeconds = (int) Math.ceil(words / 200.0 * 60.0);
        return new WordStats(lines, words, chars, noSpace, readingSeconds);
    }

    String readingTime() {
        int m = readingSeconds / 60;
        int s = readingSeconds % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }
}
