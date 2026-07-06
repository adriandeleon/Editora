package com.editora.ui;

/**
 * Pure helper: which files look like a unified diff ({@code .patch}/{@code .diff}) — used to gate the tab
 * context menu's "Open in Diff Viewer" item. Toolkit-free and unit-tested (mirrors {@link ImageFormats}).
 */
final class PatchFiles {

    private PatchFiles() {}

    /** True when {@code fileName}'s extension is {@code patch} or {@code diff} (case-insensitive). */
    static boolean isPatchFile(String fileName) {
        String ext = ImageFormats.extension(fileName);
        return "patch".equals(ext) || "diff".equals(ext);
    }
}
