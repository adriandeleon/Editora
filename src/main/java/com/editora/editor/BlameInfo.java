package com.editora.editor;

/**
 * Neutral per-line blame annotation for {@link BlameOverlay}: the already-formatted, localized
 * {@code text} to paint (e.g. "Adrian, 3 days ago • Fix gutter") and the {@code hash} of the commit
 * that last touched the line (for click-to-open). Kept in {@code editor} so the package stays free of
 * any {@code com.editora.git} dependency — {@code MainController} maps git blame into these.
 */
public record BlameInfo(String text, String hash) { }
