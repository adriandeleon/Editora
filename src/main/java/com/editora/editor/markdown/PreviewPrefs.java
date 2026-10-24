package com.editora.editor.markdown;

/**
 * The reader's choices for every Markdown preview of the app (the in-tab one and the preview tabs): the base
 * font size, the width of the reading column, and whether front matter is shown. Set from the settings by the
 * window's preview coordinator; read on the FX thread when a preview renders.
 */
public final class PreviewPrefs {

    /** The built-in base font size in px (matches {@code .markdown-preview-wrap} in app.css). */
    static final double BASE_FONT = 15;
    /** The built-in reading column (GitHub-style): the content is capped to this and centred in the pane. */
    static final double BASE_WIDTH = 860;

    static final int MIN_FONT = 8;
    static final int MAX_FONT = 40;
    static final int MIN_WIDTH = 320;
    static final int MAX_WIDTH = 4000;

    private static volatile int fontSize;
    private static volatile int width;
    private static volatile boolean hideFrontMatter;

    private PreviewPrefs() {}

    /**
     * @param fontSize the base font size in px; 0 (or less) is the built-in one
     * @param width the reading column's width in px; 0 (or less) is the built-in one
     * @param hideFrontMatter leave a document's front matter out of its preview
     */
    public static void set(int fontSize, int width, boolean hideFrontMatter) {
        PreviewPrefs.fontSize = fontSize <= 0 ? 0 : Math.clamp(fontSize, MIN_FONT, MAX_FONT);
        PreviewPrefs.width = width <= 0 ? 0 : Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
        PreviewPrefs.hideFrontMatter = hideFrontMatter;
    }

    /** The base font size in px, before the preview's own zoom. */
    public static double baseFont() {
        return fontSize == 0 ? BASE_FONT : fontSize;
    }

    /** The reading column's width cap in px. */
    public static double contentWidth() {
        return width == 0 ? BASE_WIDTH : width;
    }

    public static boolean hideFrontMatter() {
        return hideFrontMatter;
    }

    /** The inline style that gives a preview its text size at {@code zoom} (1 = 100%); headings use em. */
    public static String fontStyle(double zoom) {
        return "-fx-font-size: " + (baseFont() * zoom) + "px;";
    }

    /** Changes when a node built before would no longer be what a render gives now. */
    static String signature() {
        return width + "|" + hideFrontMatter;
    }
}
