package com.editora.terminal;

/**
 * One clickable thing found under the pointer.
 *
 * <p>{@code start} and {@code end} bound what is underlined, which is not always what is opened: a
 * stack-trace frame underlines {@code Foo.java:42:7} whole, because underlining only the file part
 * of something the eye reads as one word looks like a rendering fault, while {@code target} is the
 * file alone and the position rides beside it.
 *
 * @param kind whether this is a URL to hand to the browser or a path to resolve against a directory
 * @param text the whole run under the pointer, punctuation trimmed — what gets underlined
 * @param start index into the scanned line where {@code text} begins
 * @param end index one past its last character
 * @param target the URL, or the path with any {@code :line:column} suffix removed
 * @param line the line number from the suffix, or 0 when there was none
 * @param column the column number from the suffix, or 0 when there was none
 */
public record LinkMatch(Kind kind, String text, int start, int end, String target, int line, int column) {

    public enum Kind {
        /** Has a scheme we are willing to open. */
        URL,
        /** Might name a file. Whether it does is a question for the filesystem, not for a pattern. */
        PATH
    }

    public boolean isUrl() {
        return kind == Kind.URL;
    }
}
