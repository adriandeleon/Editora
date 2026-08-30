package com.editora.editor;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Glyphs for the editor's right-click context menus (the editor surface menu + the Markdown preview
 * menu). A package-local mirror of {@code com.editora.ui.Icons} so the {@code editor} package keeps its
 * independence from {@code ui} — the editor must not depend on the UI package, yet its menus still want
 * icons.
 *
 * <p><b>Two families, mirroring {@code ui/Icons} exactly.</b> {@link #line} renders the UI Kit's 16-unit
 * outline glyphs (stroked, {@code icon-line} class); {@link #of} renders the older Material 24dp filled
 * single-path glyphs ({@code toolbar-icon} class). Which family an action uses is <b>not</b> a local
 * choice: an action reached from both the main menu / toolbar (which draw it through {@code ui/Icons})
 * and this menu must look the same in both places, so every glyph here that has a twin in
 * {@code ui/Icons} copies that twin's family and path data verbatim. Undo, Redo, Cut, Copy, Paste,
 * Bookmark and Personal Note were filled here while their toolbar and menu-bar counterparts had already
 * moved to the line family, so the same action wore two different icons depending on how it was reached.
 * The glyphs with no {@code ui/Icons} twin (bold, table, spellcheck, …) stay Material, which is the
 * documented mid-migration state of the icon set as a whole.
 *
 * <p>Each call returns a fresh {@link Node} (a JavaFX node can only have one parent). A filled glyph is
 * coloured through {@code -fx-fill} on {@code .toolbar-icon}; a line glyph through {@code -fx-stroke} on
 * {@code .icon-line} — which is why a line glyph must never also carry {@code toolbar-icon}, or a
 * {@code X .toolbar-icon} fill rule would paint the outline solid (see {@code Icons.line}).
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

    /** Scale for a kit line glyph: its 16-unit box rendered at the same visual size as a 24dp Material one. */
    private static final double LINE_SCALE = ICON_SCALE * 24.0 / 16.0;

    /**
     * A UI Kit line glyph — the {@code editor}-package twin of {@code Icons.line}, kept byte-identical to it
     * so a glyph shared with the toolbar renders the same on both surfaces.
     *
     * <p>Deliberately <em>not</em> tagged {@code toolbar-icon}: that class is the fill-based colouring
     * convention, and an outline glyph must be stroked with the colour and filled with nothing. The base
     * {@code .icon-line} rule in app.css already strokes it {@code -color-fg-muted}, which is the same
     * colour a context-menu {@code .toolbar-icon} takes, so this needs no context-specific CSS.
     */
    private static Node line(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("icon-line");
        svg.setScaleX(LINE_SCALE);
        svg.setScaleY(LINE_SCALE);
        return new Group(svg);
    }

    // ---- Cut / Copy / Paste / Undo / Redo / Select All ----

    /** UI Kit line "scissors" — mirrors {@code Icons.cut} (toolbar + Edit menu). */
    static Node cut() {
        return line(
                "M2.5000000000000004 11.6a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0M9.7 11.6a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0M5.8 10.2L12.6 2.4M10.2 10.2L3.4 2.4");
    }

    /** UI Kit line "copy" — mirrors {@code Icons.copy} (toolbar + Edit menu). */
    static Node copy() {
        return line(
                "M7.0 5.5h5.0a1.5 1.5 0 0 1 1.5 1.5v5.0a1.5 1.5 0 0 1 -1.5 1.5h-5.0a1.5 1.5 0 0 1 -1.5 -1.5v-5.0a1.5 1.5 0 0 1 1.5 -1.5zM3 10.5V3.6A1.1 1.1 0 0 1 4.1 2.5H11");
    }

    /** UI Kit line "paste" — mirrors {@code Icons.paste} (toolbar + Edit menu). */
    static Node paste() {
        return line(
                "M5.0 3.0h6.0a1.5 1.5 0 0 1 1.5 1.5v8.0a1.5 1.5 0 0 1 -1.5 1.5h-6.0a1.5 1.5 0 0 1 -1.5 -1.5v-8.0a1.5 1.5 0 0 1 1.5 -1.5zM7.0 1.6h2.0a1.0 1.0 0 0 1 1.0 1.0v0.7999999999999998a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-0.7999999999999998a1.0 1.0 0 0 1 1.0 -1.0z");
    }

    /** UI Kit line "undo" — mirrors {@code Icons.undo} (toolbar + Edit menu). */
    static Node undo() {
        return line("M6 3.2L3.2 6 6 8.8M3.2 6h6.3a3.7 3.7 0 0 1 0 7.4H7");
    }

    /** UI Kit line "redo" — mirrors {@code Icons.redo} (toolbar + Edit menu). */
    static Node redo() {
        return line("M10 3.2L12.8 6 10 8.8M12.8 6H6.5a3.7 3.7 0 0 0 0 7.4H9");
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

    /** Material "subdirectory_arrow_right" — "Go to Implementation" (down into the concrete override). */
    static Node gotoImplementation() {
        return of("M19 15l-6 6-1.42-1.42L15.17 16H4V4h2v10h9.17l-3.59-3.58L13 9z");
    }

    /** Material "category" (shapes) — "Go to Type Definition". */
    static Node gotoTypeDefinition() {
        return of("M12 2l-5.5 9h11zM17.5 17m-4.5 0a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0M3 13.5h8v8H3z");
    }

    /** Material "edit" (pencil) — "Rename". */
    static Node rename() {
        return of("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 "
                + "0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z");
    }

    /** Material "lightbulb_outline" — "Code Actions" (quick fixes). */
    static Node codeAction() {
        return of("M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 "
                + "4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86"
                + "-3.14-7-7-7zm2.85 11.1l-.85.6V16h-4v-2.3l-.85-.6C7.8 12.16 7 10.63 7 9c0-2.76 2.24-5 "
                + "5-5s5 2.24 5 5c0 1.63-.8 3.16-2.15 4.1z");
    }

    /** UI Kit line "search" — "Find References"; mirrors {@code Icons.find}. */
    static Node find() {
        return line("M2.5999999999999996 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14");
    }

    /** UI Kit line "info" — "Show Documentation" (hover); mirrors {@code Icons.about}. */
    static Node about() {
        return line("M1.7000000000000002 8.0a6.3 6.3 0 1 0 12.6 0a6.3 6.3 0 1 0 -12.6 0M8 7.4V11M8 5.1v.2");
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

    /** UI Kit line "bug" — "Debug Main Class"; mirrors {@code Icons.debug} (toolbar + Run menu). */
    static Node debug() {
        return line("M5.2 8.4 C5.2 6.2 6.5 4.8 8 4.8 C9.5 4.8 10.8 6.2 10.8 8.4"
                + " L10.8 10.2 C10.8 12.1 9.5 13.3 8 13.3 C6.5 13.3 5.2 12.1 5.2 10.2 Z"
                + "M6.5 5.2 L5.3 3.4M9.5 5.2 L10.7 3.4"
                + "M5.3 7.8 L3.1 6.6M5.2 9.9 L2.9 9.9M5.3 11.8 L3.1 13.1"
                + "M10.7 7.8 L12.9 6.6M10.8 9.9 L13.1 9.9M10.7 11.8 L12.9 13.1"
                + "M6.0 7.0 L10.0 7.0");
    }

    /**
     * Material "text_format" — the markup-formatting submenus (Markdown, Typst).
     *
     * <p>Deliberately not either language's brand mark: the submenu already names the language, and what the
     * glyph has to say is what is <em>inside</em> it, which for both is text formatting. It is also the one
     * choice that stays honest as more markup languages get a submenu.
     */
    static Node textFormat() {
        return of("M5 17v2h14v-2H5zm4.5-4.2h5l.9 2.2h2.1L12.75 4h-1.5L6.5 15h2.1l.9-2.2zM12 5.98L13.87 11h-3.74L12 "
                + "5.98z");
    }

    /** UI Kit line "note" — "Add Personal Note"; mirrors {@code Icons.notes} (tool stripe). */
    static Node note() {
        return line("M3 2.5h10v8l-3 3H3zM10 13.5v-3h3");
    }

    /** UI Kit line "bookmark" — "Add/Remove Bookmark"; mirrors {@code Icons.bookmark} (tool stripe). */
    static Node bookmark() {
        return line("M4.5 2h7v12L8 10.6 4.5 14z");
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
