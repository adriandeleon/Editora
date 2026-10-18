package com.editora.terminal;

import java.nio.file.Path;

/**
 * What the view does once it knows what was clicked.
 *
 * <p>An interface so the view knows nothing about desktop openers, settings or subprocesses, and so
 * a test can click a link and assert what would have been opened without opening it.
 */
public interface LinkActions {

    /** Hand a URL to the desktop. Only ever a scheme from {@link LinkScanner#SCHEMES}. */
    void openUrl(String url);

    /**
     * Open a file that has already been resolved and found to exist.
     *
     * <p>The resolved path, never the token that produced it: whatever was on screen is untrusted
     * text, and a resolved path is a file this machine has.
     *
     * @param line 1-based, or 0 when the link carried no position
     */
    void openFile(Path file, int line, int column);
}
