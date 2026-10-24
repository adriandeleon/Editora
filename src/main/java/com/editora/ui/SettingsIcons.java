package com.editora.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

/**
 * One glyph per Settings sidebar category, drawn left of its name. Every glyph is a UI Kit line glyph
 * ({@link Icons#line}, stroked through {@code icon-line}) except GitHub, which keeps its filled brand mark.
 * A category that already has an icon elsewhere in the app reuses it — Git, Terminal, Debugging and Search
 * look the same here as on the tool stripe and toolbar — and the rest are drawn below in the kit's 16-unit
 * box.
 *
 * <p>The {@link Icons} families render at toolbar size (19.2 px); a sidebar row is set in 13 px text, so
 * {@link #small} brings each glyph back to its 16-unit box. Each call returns a fresh node.
 */
final class SettingsIcons {

    /** Toolbar size (16-unit box × {@code Icons.LINE_SCALE}) → the 16 px sidebar size. */
    private static final double SIDEBAR_SCALE = 16.0 / 19.2;

    private static final double BOX = 16;

    private SettingsIcons() {}

    // --- General -----------------------------------------------------------------------------------

    /** A painter's palette — themes, colours and fonts. */
    static Node appearance() {
        return line("M8 1.8C4.5 1.8 1.8 4.4 1.8 7.8c0 3.3 2.7 6.4 6 6.4 1.1 0 1.7-.6 1.7-1.4 0-.9-.8-1.2-.8-2"
                + " 0-.7.6-1.2 1.4-1.2h1.4c1.7 0 2.7-1.1 2.7-2.7 0-2.9-2.8-5.1-6.2-5.1z"
                + "M4.9 7.6h.25M6.8 4.8h.25M9.9 4.8h.25M11.7 7.3h.25");
    }

    /** A window with its title bar — the app's chrome. */
    static Node interfaceWindow() {
        return line(WINDOW + "M1.5 5.8h13M3.8 4.15h.25M5.6 4.15h.25");
    }

    /** A bar of buttons with a separator. */
    static Node toolbar() {
        return line("M2.8 4.5h10.4a1.3 1.3 0 0 1 1.3 1.3v4.4a1.3 1.3 0 0 1 -1.3 1.3H2.8a1.3 1.3 0 0 1 -1.3 -1.3"
                + "V5.8a1.3 1.3 0 0 1 1.3 -1.3zM3.2 7h2v2h-2zM6.2 7h2v2h-2zM9.8 6.5v3M10.8 7h2v2h-2z");
    }

    /** The project folder — sessions and trusted folders. */
    static Node workspace() {
        return small(Icons.project());
    }

    /** A window with a docked side rail and bottom panel. */
    static Node toolWindows() {
        return line(WINDOW + "M5.5 2.5v11M5.5 9.5h9");
    }

    // --- Editor ------------------------------------------------------------------------------------

    /** A pencil. */
    static Node editor() {
        return line("M10.6 2.4l3 3-7.9 7.9-3.7.7.7-3.7zM9.1 3.9l3 3M9.5 13.6h4.5");
    }

    /** A text caret with a suggestion popup under it. */
    static Node completion() {
        return line("M3 1.8v4.4M2.1 1.8h1.8M2.1 6.2h1.8"
                + "M5 7.5h8.2a1 1 0 0 1 1 1v4.5a1 1 0 0 1 -1 1H5a1 1 0 0 1 -1 -1V8.5a1 1 0 0 1 1 -1z"
                + "M6.2 9.6h5.6M6.2 11.9h3.4");
    }

    /** Angle brackets around a slash — a piece of code. */
    static Node snippets() {
        return line("M5 4.5L1.8 8 5 11.5M11 4.5L14.2 8 11 11.5M9.3 3L6.7 13");
    }

    /** The file-with-lines glyph the New From Template action already uses. */
    static Node templates() {
        return small(Icons.template());
    }

    /** A checklist: a ticked item over an open one. */
    static Node todo() {
        return line("M2 4.2l1.3 1.3L5.8 3M8 4.5h6M2.3 9.3h3v3h-3zM8 10.8h6");
    }

    /** A letter A with a tick. */
    static Node spellCheck() {
        return line("M1.8 12L4.8 3.8 7.8 12M2.9 9.2h3.8M8.8 10.4l1.9 1.9 3.6-4.2");
    }

    static Node search() {
        return small(Icons.find());
    }

    /** Two arrows pushing apart between bars — short text expanding to long. */
    static Node abbreviations() {
        return line("M2.5 4.5v7M13.5 4.5v7M5.2 8h5.6M6.7 6.5L5.2 8l1.5 1.5M9.3 6.5L10.8 8l-1.5 1.5");
    }

    // --- AI ----------------------------------------------------------------------------------------

    /** A four-point sparkle with a small one beside it. */
    static Node ai() {
        return line("M7 2c.5 3 2.2 4.7 5.2 5.2-3 .5-4.7 2.2-5.2 5.2-.5-3-2.2-4.7-5.2-5.2 3-.5 4.7-2.2 5.2-5.2z"
                + "M12.6 11v3M11.1 12.5h3");
    }

    /** A robot head — the same idea as the Agent tool window's glyph, as an outline. */
    static Node agent() {
        return line("M4 5.5h8a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1 -1.5 1.5H4a1.5 1.5 0 0 1 -1.5 -1.5V7"
                + "a1.5 1.5 0 0 1 1.5 -1.5zM8 5.5V3.4M7.3 2.7a0.7 0.7 0 1 0 1.4 0a0.7 0.7 0 1 0 -1.4 0"
                + "M6 8.6v1.2M10 8.6v1.2M1 8.5v2.5M15 8.5v2.5");
    }

    /** A magic wand with sparks — actions run on the selection. */
    static Node aiActions() {
        return line("M2.2 13.8L9.8 6.2M8.6 5l2.4 2.4M12 1.8v2.4M10.8 3h2.4M13.6 6.6v1.8M12.7 7.5h1.8"
                + "M5.2 2.2v1.6M4.4 3h1.6");
    }

    // --- Languages & Tools -------------------------------------------------------------------------

    /** Curly braces — code intelligence. */
    static Node lsp() {
        return small(Icons.foldRegion());
    }

    static Node debug() {
        return small(Icons.debug());
    }

    /** The Markdown mark: an M and a down arrow in a frame. */
    static Node markdown() {
        return line("M2.5 3.5h11a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-11a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1z"
                + "M3.8 10.2V5.8l1.9 2.3 1.9-2.3v4.4M11.3 5.8v4.2M9.7 8.6l1.6 1.6 1.6-1.6");
    }

    /** A flowchart: one box branching to two. */
    static Node mermaid() {
        return line("M5.5 2.6h5v3h-5zM8 5.6V8M4 8h8M4 8v2.2M12 8v2.2M2 10.2h4v3.2H2zM10 10.2h4v3.2h-4z");
    }

    /** Three connected nodes — a graph. */
    static Node diagrams() {
        return line("M2.2 4.2a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"
                + "M10.2 5.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"
                + "M4.7 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"
                + "M5.78 4.49L10.22 5.21M4.55 5.91L5.95 10.29M10.84 6.88L7.66 10.62");
    }

    /** A lowercase t — Typst's own mark. */
    static Node typst() {
        return line("M6.8 2.8v8.4c0 1.4.7 2 1.9 2 .8 0 1.5-.3 2.1-.7M4.5 6h5.6");
    }

    /** A hammer. */
    static Node buildTools() {
        return line("M2.5 2.5h8.5l2.5 1.5-2.5 1.5H2.5zM7 5.5v7.8a1 1 0 0 0 2 0V5.5");
    }

    /** A globe. */
    static Node web() {
        return line("M1.8 8a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0M1.8 8h12.4"
                + "M8 1.8c-1.7 1.7-2.5 3.8-2.5 6.2s.8 4.5 2.5 6.2c1.7-1.7 2.5-3.8 2.5-6.2S9.7 3.5 8 1.8z");
    }

    /** A frame with an arrow out of it — a program outside Editora. */
    static Node externalTools() {
        return small(Icons.openInTab());
    }

    static Node terminal() {
        return small(Icons.terminal());
    }

    // --- Version control ---------------------------------------------------------------------------

    static Node git() {
        return small(Icons.git());
    }

    /** GitHub's filled brand mark, as on the GitHub tool window stripe. */
    static Node github() {
        return small(Icons.github());
    }

    // --- System ------------------------------------------------------------------------------------

    /** A keyboard (the Command Palette's glyph). */
    static Node keymaps() {
        return small(Icons.palette());
    }

    /** A record button — recorded keystrokes. */
    static Node macros() {
        return line("M1.8 8a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0M5.8 8a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0");
    }

    /** A rack of two servers — SSH hosts. */
    static Node remote() {
        return line("M3 2.5h10a1 1 0 0 1 1 1V6a1 1 0 0 1 -1 1H3a1 1 0 0 1 -1 -1V3.5a1 1 0 0 1 1 -1z"
                + "M3 9h10a1 1 0 0 1 1 1v2.5a1 1 0 0 1 -1 1H3a1 1 0 0 1 -1 -1V10a1 1 0 0 1 1 -1z"
                + "M4.6 4.75h.25M4.6 11.25h.25M9 4.75h2.6M9 11.25h2.6");
    }

    /** A padlock. */
    static Node encryption() {
        return line("M4.2 7.2h7.6a1.2 1.2 0 0 1 1.2 1.2v4.4a1.2 1.2 0 0 1 -1.2 1.2H4.2a1.2 1.2 0 0 1 -1.2 -1.2"
                + "V8.4a1.2 1.2 0 0 1 1.2 -1.2zM5.3 7.2V5a2.7 2.7 0 0 1 5.4 0v2.2M8 9.8v1.6");
    }

    /** A jigsaw piece. */
    static Node plugins() {
        return line("M2.75 5.25h2.5a1.5 1.5 0 1 1 3 0h2.5v2.5a1.5 1.5 0 1 1 0 3v2.5h-8z");
    }

    /** Two arrows chasing round a circle. */
    static Node sync() {
        return line("M13.2 6.6A5.4 5.4 0 0 0 3.3 5.2M2.8 9.4a5.4 5.4 0 0 0 9.9 1.4M3.1 2.6v2.8h2.8"
                + "M12.9 13.4v-2.8h-2.8");
    }

    /** A plug — a client connecting to Editora's MCP server. */
    static Node mcp() {
        return line("M6 1.8v3M10 1.8v3M4 4.8h8v2.4a4 4 0 0 1 -8 0zM8 11.2v3");
    }

    /** Two sliders. */
    static Node advanced() {
        return line("M2 4.5h6.5M12.5 4.5H14M2 11.5h1.5M7.5 11.5H14M8.5 4.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"
                + "M3.5 11.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0");
    }

    // ------------------------------------------------------------------------------------------------

    /** A rounded window frame filling the 16-unit box; glyphs add their own panes. */
    private static final String WINDOW = "M2.8 2.5h10.4a1.3 1.3 0 0 1 1.3 1.3v8.4a1.3 1.3 0 0 1 -1.3 1.3H2.8"
            + "a1.3 1.3 0 0 1 -1.3 -1.3V3.8a1.3 1.3 0 0 1 1.3 -1.3z";

    private static Node line(String content) {
        return small(Icons.line(content));
    }

    /**
     * Scales a toolbar-size icon to the sidebar's and centres it in a fixed 16 px box, so every name starts
     * at the same x whatever its glyph's width. The scale goes on the icon's own Group and a new Group wraps
     * it: a node's own transform does not change its layout bounds, which the box centres by; its parent's do.
     */
    private static Node small(Node icon) {
        icon.setScaleX(SIDEBAR_SCALE);
        icon.setScaleY(SIDEBAR_SCALE);
        StackPane box = new StackPane(new Group(icon));
        box.setMinSize(BOX, BOX);
        box.setPrefSize(BOX, BOX);
        box.setMaxSize(BOX, BOX);
        box.getStyleClass().add("settings-sidebar-icon");
        return box;
    }
}
