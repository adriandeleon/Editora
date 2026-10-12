package com.editora.editor;

import java.util.function.Consumer;

/**
 * Whether one buffer soft-wraps its lines. The preference is per kind of file — one answer for prose
 * (Markdown, plain text), one for code — and a buffer's kind is its language, which is known only after the
 * buffer exists and can change (Save As, <em>Set Language</em>). So the buffer keeps both answers and picks
 * again whenever its language does; the window only hands over the two preferences.
 *
 * <p>A buffer nobody has given a preference to (a test's bare buffer, a tooltip's) does not wrap, as before.
 * {@link #setSuppressed} holds wrapping off for a file whose lines are too long to wrap cheaply.
 */
public final class WordWrap {

    private final Consumer<Boolean> apply;
    private boolean code;
    private boolean prose;
    private boolean suppressed;
    private String language;

    WordWrap(Consumer<Boolean> apply) {
        this.apply = apply;
    }

    /** Sets the two preferences and applies the one for this buffer's language. */
    public void setPreference(boolean code, boolean prose) {
        this.code = code;
        this.prose = prose;
        apply();
    }

    void languageChanged(String language) {
        this.language = language;
        if (code != prose) {
            apply(); // only a change of kind can change the answer
        }
    }

    /** The preference for this buffer's language, whether or not it is held off. */
    public boolean preferred() {
        return isProse(language) ? prose : code;
    }

    /** A load found a line too long to wrap cheaply: the preference is held off for this buffer. */
    public boolean isSuppressed() {
        return suppressed;
    }

    /** Holds the preference off (or lets it apply again); the caller sets the wrap state it wants now. */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
    }

    /**
     * The longest run of non-whitespace a wrapped buffer is allowed to receive in one piece. Wrapping breaks
     * at whitespace, and a token with none in it is laid out pathologically: measured in a real window, a
     * plain-text file whose one line is 10,000 unbroken characters took 14 s to open wrapped and one of 20,000
     * took 79 s, against half a second unwrapped (lines of words stay fast to 60,000 characters). Prose has
     * no such token; a pasted {@code data:} image or a minified blob does.
     */
    public static final int MAX_UNBROKEN_RUN = 2_000;

    /**
     * Text is going into the buffer (a load, a paste, a reload). A piece holding an unbroken run of
     * {@link #MAX_UNBROKEN_RUN} characters holds wrapping off for this buffer before the next layout — the
     * same hold a file with a giant line gets — and <em>Toggle Word Wrap</em> remains the way to ask for it
     * anyway. Costs nothing for ordinary typing: only a piece that long is scanned.
     */
    void inserted(String text) {
        if (!suppressed && text.length() >= MAX_UNBROKEN_RUN && hasLongUnbrokenRun(text)) {
            suppressed = true;
            apply();
        }
    }

    /** Whether {@code text} contains {@link #MAX_UNBROKEN_RUN} consecutive non-whitespace characters. Pure. */
    public static boolean hasLongUnbrokenRun(CharSequence text) {
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                run = 0;
            } else if (++run >= MAX_UNBROKEN_RUN) {
                return true;
            }
        }
        return false;
    }

    /** Markdown and plain text: the languages whose lines are paragraphs. */
    public static boolean isProse(String language) {
        return com.editora.config.Settings.isProseLanguage(language);
    }

    private void apply() {
        apply.accept(preferred() && !suppressed);
    }
}
