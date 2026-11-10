package com.editora.ui;

/**
 * Which of the editor's reading aids the Project Map's code cards show, as the editor's settings say (Map code browser
 * round 2): the language server's semantic highlighting, its inlay hints (all, or only the names of literal
 * arguments: {@code inlayMode}), and the pinned headers of the scopes scrolled past (sticky scroll).
 */
record MapCardAids(boolean semantic, boolean inlayHints, String inlayMode, boolean stickyScroll) {

    /** None: a Map nobody told the editor's settings. */
    static final MapCardAids NONE = new MapCardAids(false, false, "literals", false);
}
