package com.editora.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Toolbar icons as Material Design 24dp single-path glyphs, returned wrapped in a {@link Group}
 * and scaled down. The Group's layout bounds reflect the scaled size, so buttons sized to their
 * graphic also shrink. Color is controlled via the {@code toolbar-icon} style class on the inner
 * {@link SVGPath} (see app.css).
 *
 * <p>Each call returns a fresh node — a JavaFX node can only have one parent.
 */
final class Icons {

    /** Scale applied to every toolbar/stripe icon. */
    private static final double ICON_SCALE = 0.8;

    private Icons() {
    }

    private static Node of(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Group(svg);
    }

    static Node newFile() {
        return of("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z");
    }

    /** Source-control branch glyph (Material "account_tree"/fork) for the Git tool window stripe. */
    static Node git() {
        return of("M17 6c0-1.66-1.34-3-3-3s-3 1.34-3 3c0 1.3.84 2.4 2 2.82V11c0 1.1-.9 2-2 2H8.82C8.4 "
                + "11.84 7.3 11 6 11c-1.66 0-3 1.34-3 3s1.34 3 3 3c1.3 0 2.4-.84 2.82-2H11c2.21 0 4-1.79 "
                + "4-4V8.82C16.16 8.4 17 7.3 17 6zM6 15c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 "
                + "1zm8-8c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z");
    }

    /** Circular-arrow "refresh" (Material). */
    static Node refresh() {
        return of("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 "
                + "6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 "
                + "3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z");
    }

    /** Up-arrow "push" (Material arrow_upward). */
    static Node gitPush() {
        return of("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
    }

    /** Stacked sheets with a plus — "stage all" (Material library_add). */
    static Node stageAll() {
        return of("M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 "
                + "2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9h-4v4h-2v-4H9V9h4V5h2v4h4v2z");
    }

    static Node open() {
        return of("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z");
    }

    static Node save() {
        return of("M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 "
                + "0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z");
    }

    static Node saveAs() {
        return of("M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7h-2zm-6 .67 2.59-2.58L17 "
                + "11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2v9.67z");
    }

    static Node undo() {
        return of("M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 "
                + "3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z");
    }

    static Node redo() {
        return of("M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 "
                + "4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z");
    }

    static Node cut() {
        return of("M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 "
                + "1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 "
                + "4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 "
                + "0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 "
                + "2-.9 2-2 2zm6-7.5c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5zM19 3l-6 6 2 "
                + "2 7-7V3z");
    }

    static Node copy() {
        return of("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 "
                + "0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z");
    }

    static Node paste() {
        return of("M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 "
                + "2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 "
                + ".45-1 1-1zm7 18H5V4h2v3h10V4h2v16z");
    }

    static Node find() {
        return of("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 "
                + "9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 "
                + "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
    }

    static Node findInFiles() {
        return of("M20 19.59V8l-6-6H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c.45 0 .85-.15 "
                + "1.19-.4l-4.43-4.43c-.8.52-1.74.83-2.76.83-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 "
                + "5c0 1.02-.31 1.96-.83 2.75L20 19.59zM9 13c0 1.66 1.34 3 3 3s3-1.34 3-3-1.34-3-3-3-3 "
                + "1.34-3 3z");
    }

    static Node tools() {
        return of("M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 "
                + "10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z");
    }

    /** Problems / diagnostics: a warning triangle with an exclamation mark. */
    static Node problems() {
        return of("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z");
    }

    /** Run: a play triangle (Material "play_arrow") for running a compact source file. */
    static Node run() {
        return of("M8 5v14l11-7z");
    }

    static Node palette() {
        return of("M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 "
                + "7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z");
    }

    static Node closeTab() {
        return of("M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 "
                + "19 17.59 13.41 12z");
    }

    static Node settings() {
        return of("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41."
                + "12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-"
                + "2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-"
                + "1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-."
                + "05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22."
                + "37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 ."
                + "44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-"
                + "3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 "
                + "3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z");
    }

    static Node trash() {
        return of("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z");
    }

    static Node recent() {
        // Material "history" — a clock with a counter-clockwise arrow for recent files.
        return of("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 "
                + "7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 "
                + "0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z");
    }

    static Node quit() {
        return of("M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 "
                + "7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 "
                + "4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z");
    }

    static Node project() {
        // Material "account_tree" — a tree-like icon for the Project tool window.
        return of("M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z");
    }

    static Node openFolder() {
        // Material "folder_open" — opening a project folder.
        return of("M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 "
                + "2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z");
    }

    static Node bookmark() {
        return of("M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z");
    }

    static Node notes() {
        // Material "comment" — a speech bubble, used for the Personal Notes tool window.
        return of("M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z");
    }

    static Node structure() {
        // Material "device_hub" — a node/tree graph, used for the Structure tool window.
        return of("M17 16l-4-4V8.82C14.16 8.4 15 7.3 15 6c0-1.66-1.34-3-3-3S9 4.34 9 6c0 1.3.84 "
                + "2.4 2 2.82V12l-4 4H3v5h5v-3.05l4-4.2 4 4.2V21h5v-5h-4z");
    }

    /** A filled "Z" glyph (top + bottom bars joined by a diagonal), for the Zen-mode exit button. */
    static Node zen() {
        return of("M4 4 H20 V7 L8 17 H20 V20 H4 V17 L16 7 H4 Z");
    }

    static Node closeSmall() {
        // Same outline as closeTab() — used by the tool window header.
        return of("M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 "
                + "19 17.59 13.41 12z");
    }

    static Node fileSheet() {
        return of("M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v"
                + "-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z");
    }

    static Node outline() {
        return of("M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-"
                + "6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-"
                + "1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-"
                + "6h14v-2H7v2zm0-8v2h14V5H7z");
    }

    static Node warning() {
        return of("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z");
    }

    static Node splitVertical() {
        // Two panes side by side (a vertical divider) — the "split side by side" action.
        return of("M4 5h6v14H4V5zm10 0h6v14h-6V5z");
    }

    static Node splitHorizontal() {
        // Two panes stacked (a horizontal divider) — the "split stacked" action.
        return of("M5 4h14v6H5V4zm0 10h14v6H5v-6z");
    }

    static Node pin() {
        // Material "push_pin" — a thumbtack, used to mark pinned tabs.
        return of("M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z");
    }

    static Node about() {
        return of("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 "
                + "2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z");
    }

    /** Markdown view: Editor only (Material "subject" — text lines). */
    static Node previewEditor() {
        return of("M14 17H4v2h10v-2zm6-8H4v2h16V9zM4 15h16v-2H4v2zM4 5v2h16V5H4z");
    }

    /** Markdown view: Editor + Preview (two side-by-side panes). */
    static Node previewSplit() {
        return of("M4 5h6v14H4V5zm10 0h6v14h-6V5z");
    }

    /** Markdown view: Preview only (Material "visibility" — eye). */
    static Node previewOnly() {
        return of("M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-"
                + "11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 "
                + "1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z");
    }

    /** Material "edit" (pencil) — rename / edit-note context-menu items. */
    static Node edit() {
        return of("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-"
                + "2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
    }

    /** Material "arrow_upward" — "Move Up" context-menu item. */
    static Node arrowUp() {
        return of("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
    }

    /** Material "arrow_downward" — "Move Down" context-menu item. */
    static Node arrowDown() {
        return of("M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z");
    }

    /** Material "check" / done — "Resolve" note context-menu item. */
    static Node check() {
        return of("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
    }

    /** Material "remove" (minus) — "Unstage" git context-menu item. */
    static Node remove() {
        return of("M19 13H5v-2h14v2z");
    }
}
