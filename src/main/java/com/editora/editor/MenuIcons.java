package com.editora.editor;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Material Design 24dp single-path glyphs for the editor's right-click context menus (the editor
 * surface menu + the Markdown preview menu). A package-local mirror of {@code com.editora.ui.Icons}
 * so the {@code editor} package keeps its independence from {@code ui} — the editor must not depend
 * on the UI package, yet its menus still want icons.
 *
 * <p>Each call returns a fresh {@link Node} (a JavaFX node can only have one parent). Color is
 * controlled via the {@code toolbar-icon} style class on the inner {@link SVGPath} (see app.css),
 * matching every other icon in the app.
 */
final class MenuIcons {

    private static final double ICON_SCALE = 0.8;

    private MenuIcons() {}

    private static Node of(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Group(svg);
    }

    // ---- Cut / Copy / Paste / Undo / Redo / Select All ----

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

    static Node undo() {
        return of("M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 "
                + "3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z");
    }

    static Node redo() {
        return of("M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 "
                + "4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z");
    }

    /** Material "select_all". */
    static Node selectAll() {
        return of("M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 "
                + "0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-"
                + "2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-"
                + "2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z");
    }

    // ---- Markdown inline format ----

    /** Material "format_bold". */
    static Node bold() {
        return of("M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 "
                + "3.71-3.79 0-1.52-.86-2.82-2.15-3.42zM10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-"
                + "3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z");
    }

    /** Material "format_italic". */
    static Node italic() {
        return of("M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z");
    }

    /** Material "strikethrough_s". */
    static Node strikethrough() {
        return of("M10 19h4v-3h-4v3zM5 4v3h5v3h4V7h5V4H5zM3 14h18v-2H3v2z");
    }

    /** Material "code". */
    static Node code() {
        return of("M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z");
    }

    /** Two slashes "//" — comment / uncomment. */
    static Node comment() {
        return of("M4 18 8 6 10 6 6 18z M12 18 16 6 18 6 14 18z");
    }

    /** Material "insert_link". */
    static Node link() {
        return of("M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 "
                + "0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 "
                + "3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z");
    }

    /** Material "format_list_bulleted" — a bulleted list. */
    static Node bulletList() {
        return of("M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 "
                + "0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 "
                + "1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z");
    }

    /** Material "checklist" — a task list (checked items), for the GFM {@code - [ ]} checkbox button. */
    static Node taskList() {
        return of("M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11 2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 "
                + "1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z");
    }

    // ---- Markdown tables ----

    /** Material "grid_on" — a table grid (insert/format table). */
    static Node table() {
        return of("M20 2H4c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM8 20H4v-4h4v4zm0"
                + "-6H4v-4h4v4zm0-6H4V4h4v4zm6 12h-4v-4h4v4zm0-6h-4v-4h4v4zm0-6h-4V4h4v4zm6 12h-4v-4h4v4zm0"
                + "-6h-4v-4h4v4zm0-6h-4V4h4v4z");
    }

    /** Material "remove" — a minus (delete row/column). */
    static Node remove() {
        return of("M19 13H5v-2h14v2z");
    }

    /** Material "format_align_left". */
    static Node alignLeft() {
        return of("M15 15H3v2h12v-2zm0-8H3v2h12V7zM3 13h18v-2H3v2zm0 8h18v-2H3v2zM3 3v2h18V3H3z");
    }

    /** Material "format_align_center". */
    static Node alignCenter() {
        return of("M7 15v2h10v-2H7zm-4 6h18v-2H3v2zm0-8h18v-2H3v2zm4-6v2h10V7H7zM3 3v2h18V3H3z");
    }

    /** Material "format_align_right". */
    static Node alignRight() {
        return of("M3 21h18v-2H3v2zm6-4h12v-2H9v2zm-6-4h18v-2H3v2zm6-4h12V7H9v2zM3 3v2h18V3H3z");
    }

    // ---- LSP navigation ----

    /** Material "north_east" arrow — "Go to Definition". */
    static Node gotoDefinition() {
        return of("M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5z");
    }

    /** Material "lightbulb_outline" — "Code Actions" (quick fixes). */
    static Node codeAction() {
        return of("M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 "
                + "4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86"
                + "-3.14-7-7-7zm2.85 11.1l-.85.6V16h-4v-2.3l-.85-.6C7.8 12.16 7 10.63 7 9c0-2.76 2.24-5 "
                + "5-5s5 2.24 5 5c0 1.63-.8 3.16-2.15 4.1z");
    }

    /** Material "search" — "Find References". */
    static Node find() {
        return of("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 "
                + "9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 "
                + "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
    }

    /** Material "info" — "Show Documentation" (hover). */
    static Node about() {
        return of("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 "
                + "2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z");
    }

    // ---- Spell check ----

    /** Material "spellcheck" — a spelling suggestion. */
    static Node spellcheck() {
        return of("M12.45 16h2.09L9.43 3H7.57L2.46 16h2.09l1.12-3h5.64l1.14 3zm-6.02-5L8.5 5.48 10.57 "
                + "11H6.43zm15.16.59l-8.09 8.09L9.83 16l-1.41 1.41 5.09 5.09L23 13l-1.41-1.41z");
    }

    /** Material "add" (plus) — "Add to Dictionary". */
    static Node add() {
        return of("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z");
    }

    /** Material "block" — "Ignore". */
    static Node block() {
        return of("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 "
                + "3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 "
                + "0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z");
    }

    // ---- Misc ----

    /** Material "play_arrow" — "Run File" (matches the gutter Run glyph; see {@link FoldManager#runGlyph}). */
    static Node run() {
        return FoldManager.runGlyph();
    }

    /** Material "comment" — "Add Personal Note". */
    static Node note() {
        return of("M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z");
    }

    /** Material "bookmark" — "Add/Remove Bookmark" (matches the gutter bookmark marker). */
    static Node bookmark() {
        return of("M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z");
    }

    /** Material "file_download" — "Export to PDF". */
    static Node download() {
        return of("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z");
    }

    /** Material "print" — "Print". */
    static Node print() {
        return of("M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 "
                + "0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z");
    }

    // ---- AI selection actions ----

    /** Material "help" (circle + "?") — "Explain Selection". Reuses the {@link #about()} outline. */
    static Node explain() {
        return of("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 "
                + "8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 "
                + "3-2.5 3-5 0-2.21-1.79-4-4-4zm-1 12h2v2h-2z");
    }

    /** Material "edit" (pencil) — "Rewrite Selection…". */
    static Node rewrite() {
        return of("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c"
                + "-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
    }
}
