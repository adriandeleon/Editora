package com.editora.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Toolbar icons, returned wrapped in a {@link Group} and scaled down. The Group's layout bounds reflect
 * the scaled size, so buttons sized to their graphic also shrink.
 *
 * <p>Two families, mid-migration to the UI Kit:
 *
 * <ul>
 *   <li>{@link #line} — the kit's 16-unit outline glyphs, <b>stroked</b> and coloured through the
 *       {@code icon-line} style class. The chrome the kit actually draws (toolbar, tool stripe, tabs)
 *       uses these.
 *   <li>{@link #of} — the older Material Design 24dp single-path glyphs, <b>filled</b> and coloured
 *       through {@code toolbar-icon}. Still used everywhere the kit specifies no glyph (file-type icons,
 *       context menus, build-tool and browser brand marks).
 * </ul>
 *
 * <p>The two are not interchangeable: one is coloured by {@code -fx-fill} and the other by
 * {@code -fx-stroke}, so a glyph moving between families needs its CSS moved too (see app.css).
 *
 * <p>Each call returns a fresh node — a JavaFX node can only have one parent.
 */
final class Icons {

    /** Scale applied to every toolbar/stripe icon. */
    private static final double ICON_SCALE = 0.8;

    private Icons() {}

    /** Scale for a kit line glyph: its 16-unit box rendered at the same visual size as a 24dp Material one. */
    private static final double LINE_SCALE = ICON_SCALE * 24.0 / 16.0;

    /**
     * A UI Kit line glyph (16-unit box, stroked not filled) — see {@code .icon-line} in app.css.
     *
     * <p>Deliberately <em>not</em> tagged {@code toolbar-icon}: that class is Editora's fill-based
     * coloring convention ({@code -fx-fill} in ~49 rules), and an outline glyph must be stroked with the
     * color and filled with nothing. Wearing both classes would let any {@code X .toolbar-icon} rule
     * (higher specificity than a bare {@code .icon-line}) paint the outline solid. The contexts a line
     * glyph actually appears in carry their own {@code -fx-stroke} rules instead.
     */
    static Node line(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("icon-line");
        svg.setScaleX(LINE_SCALE);
        svg.setScaleY(LINE_SCALE);
        return new Group(svg);
    }

    static Node of(String content) {
        return of(content, (String[]) null);
    }

    /** As {@link #of(String)}, but also adds {@code extraClasses} to the inner path (e.g. a semantic color). */
    static Node of(String content, String... extraClasses) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        if (extraClasses != null) {
            svg.getStyleClass().addAll(extraClasses);
        }
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Group(svg);
    }

    /**
     * New File. Drawn to the same 11-unit height as {@link #save()} and the Material glyphs beside it —
     * it and {@link #fileSheet()} used to span 13 of the 16-unit box, which rendered them visibly taller
     * than every other icon on the bar.
     */
    static Node newFile() {
        return line("M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9M8.25 8.2v3.2M6.65 9.8h3.2");
    }

    /** A folder-with-plus glyph for the "New Folder" action. */
    static Node newFolder() {
        return line("M1.5 3.5h4.2l1.5 2h7.3v7H1.5zM8 7.7v3M6.5 9.2h3");
    }

    /** A robot glyph (MDI "robot") for the AI Agent chat tool window. */
    static Node agent() {
        return of("M12,2A2,2 0 0,1 14,4C14,4.74 13.6,5.39 13,5.73V7H14A7,7 0 0,1 21,14H22A1,1 0 0,1 23,15V18A1,1 0 "
                + "0,1 22,19H21V20A2,2 0 0,1 19,22H5A2,2 0 0,1 3,20V19H2A1,1 0 0,1 1,18V15A1,1 0 0,1 2,14H3A7,7 0 "
                + "0,1 10,7H11V5.73C10.4,5.39 10,4.74 10,4A2,2 0 0,1 12,2M7.5,13A2.5,2.5 0 0,0 5,15.5A2.5,2.5 0 0,0 "
                + "7.5,18A2.5,2.5 0 0,0 10,15.5A2.5,2.5 0 0,0 7.5,13M16.5,13A2.5,2.5 0 0,0 14,15.5A2.5,2.5 0 0,0 "
                + "16.5,18A2.5,2.5 0 0,0 19,15.5A2.5,2.5 0 0,0 16.5,13Z");
    }

    /**
     * Simple-UI mode: a window frame whose side rail and bottom bar are drawn as short dashes, i.e. chrome
     * being stripped back to the document. The previous glyph was a bare rounded square — accurate in that
     * Simple mode is "less", but it named nothing and read as an empty checkbox.
     */
    static Node simpleMode() {
        return line("M1.8 2.6h12.4a1 1 0 0 1 1 1v8.8a1 1 0 0 1 -1 1h-12.4a1 1 0 0 1 -1 -1v-8.8a1 1 0 0 1 1 -1z"
                + "M1.0 5.6h14M4.4 5.6v7.8M2.6 8.2h0.6M2.6 10.2h0.6");
    }

    /** A cloud glyph marking a remote (SFTP) file/tab. */
    static Node remote() {
        return of("M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 "
                + "3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z");
    }

    /** Source-control branch glyph for the Git tool window stripe. */
    static Node git() {
        return line("M5.4 8.0a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0M8 1.5v4M8 10.5v4");
    }

    /** The GitHub "octocat" mark (Simple Icons, CC0) — the GitHub tool window stripe. */
    static Node github() {
        return of("M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 "
                + "0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 "
                + "17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 "
                + "1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 "
                + "0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 "
                + "1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 "
                + "2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 "
                + "2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 "
                + "12.297c0-6.627-5.373-12-12-12");
    }

    /** Circular-arrow "refresh" (Material). */
    static Node refresh() {
        return of("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 "
                + "6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 "
                + "3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z");
    }

    /** Medical bag with a cross (Material "medical_services") — the Doctor tool-health screen. */
    static Node doctor() {
        return of("M20 6h-4V4c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2h16c1.1 0 "
                + "2-.9 2-2V8c0-1.1-.9-2-2-2zm-10-2h4v2h-4V4zm6 11h-3v3h-2v-3H8v-2h3v-3h2v3h3v2z");
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

    /** A 4-point "sparkle" star (AI-generated content indicator) — the Commit panel's Generate Commit
     *  Message with AI button. Thin, near-center waist (unlike a rotated square) so it reads as a spiky
     *  sparkle rather than a diamond at toolbar size. */
    static Node aiGenerate() {
        return of("M12,2 L13,11 L22,12 L13,13 L12,22 L11,13 L2,12 L11,11 Z");
    }

    /** Clock-with-arrow "history" glyph for the Git Log tool window. */
    static Node gitLog() {
        return line(
                "M2.7 4.0a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0M2.7 12.0a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0M9.7 8.0a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0M4.5 5.8v4.4M6.3 4H9a2.5 2.5 0 0 1 2.5 2.5");
    }

    /** Clock-face "schedule" glyph (Material) for the Local File History tool window — distinct from the
     *  clock-with-back-arrow {@link #gitLog()}. */
    static Node history() {
        return of("M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 "
                + "2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 "
                + "3.15.75-1.23-4.5-2.67z");
    }

    /** Person-with-lines "annotate/blame" glyph (Material "person") for inline blame. */
    static Node blame() {
        return of("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 "
                + "4v2h16v-2c0-2.66-5.33-4-8-4z");
    }

    /** Puzzle-piece "extension/plugin" glyph (Material "extension"). */
    static Node plugin() {
        return of(
                "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 "
                        + "2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V19c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 "
                        + "2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V21H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z");
    }

    /** Inbox/archive "stash" glyph (Material "inbox"). */
    static Node stash() {
        return of("M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 "
                + "12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H5V5h14v10z");
    }

    static Node open() {
        return of("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z");
    }

    static Node save() {
        return line("M2.5 2.5h9l2 2v9h-11zM5 2.5v3.5h5V2.5M5 13.5V9.5h6v4");
    }

    static Node saveAs() {
        return of("M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7h-2zm-6 .67 2.59-2.58L17 "
                + "11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2v9.67z");
    }

    static Node undo() {
        return line("M6 3.2L3.2 6 6 8.8M3.2 6h6.3a3.7 3.7 0 0 1 0 7.4H7");
    }

    static Node redo() {
        return line("M10 3.2L12.8 6 10 8.8M12.8 6H6.5a3.7 3.7 0 0 0 0 7.4H9");
    }

    static Node cut() {
        return line(
                "M2.5000000000000004 11.6a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0M9.7 11.6a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0M5.8 10.2L12.6 2.4M10.2 10.2L3.4 2.4");
    }

    static Node copy() {
        return line(
                "M7.0 5.5h5.0a1.5 1.5 0 0 1 1.5 1.5v5.0a1.5 1.5 0 0 1 -1.5 1.5h-5.0a1.5 1.5 0 0 1 -1.5 -1.5v-5.0a1.5 1.5 0 0 1 1.5 -1.5zM3 10.5V3.6A1.1 1.1 0 0 1 4.1 2.5H11");
    }

    /** Material "select_all". */
    static Node selectAll() {
        return of("M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 "
                + "0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-"
                + "2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-"
                + "2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z");
    }

    static Node paste() {
        return line(
                "M5.0 3.0h6.0a1.5 1.5 0 0 1 1.5 1.5v8.0a1.5 1.5 0 0 1 -1.5 1.5h-6.0a1.5 1.5 0 0 1 -1.5 -1.5v-8.0a1.5 1.5 0 0 1 1.5 -1.5zM7.0 1.6h2.0a1.0 1.0 0 0 1 1.0 1.0v0.7999999999999998a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-0.7999999999999998a1.0 1.0 0 0 1 1.0 -1.0z");
    }

    static Node find() {
        return line("M2.5999999999999996 7.0a4.4 4.4 0 1 0 8.8 0a4.4 4.4 0 1 0 -8.8 0M10.4 10.4L14 14");
    }

    static Node findInFiles() {
        return line(
                "M4.6 6.6a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0M11.7 9.7L14.5 12.5M1.5 4.5h3M1.5 7.5h2M1.5 10.5h3.5");
    }

    static Node tools() {
        return of("M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 "
                + "10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z");
    }

    /** Problems / diagnostics: a warning triangle with an exclamation mark. */
    static Node problems() {
        return of("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z");
    }

    /** Run: a play triangle for running a compact source file. */
    static Node run() {
        return line("M5.2 3.4v9.2l7.4-4.6z");
    }

    /** Filled square — stop a running build/task in the build-tool tree toolbar. */
    static Node stopSquare() {
        return line(
                "M5.2 4.0h5.6a1.2 1.2 0 0 1 1.2 1.2v5.6a1.2 1.2 0 0 1 -1.2 1.2h-5.6a1.2 1.2 0 0 1 -1.2 -1.2v-5.6a1.2 1.2 0 0 1 1.2 -1.2z");
    }

    /** Test Results: Material Design "test-tube" (beaker) — the test-runner tool window. */
    static Node testResults() {
        return of("M7 2v2h1v14a4 4 0 0 0 4 4 4 4 0 0 0 4-4V4h1V2H7zm5 14c-.6 0-1-.4-1-1s.4-1 1-1 "
                + "1 .4 1 1-.4 1-1 1zm1-4c-.6 0-1-.4-1-1s.4-1 1-1 1 .4 1 1-.4 1-1 1zm1-5h-4V4h4v3z");
    }

    /** Test passed: Material "check_circle", tinted green via {@code .test-icon-pass}. */
    static Node testPassed() {
        return of(
                "M12 2a10 10 0 0 0-10 10 10 10 0 0 0 10 10 10 10 0 0 0 10-10A10 10 0 0 0 12 2m-2 15-5-5 1.41-1.41"
                        + "L10 14.17l7.59-7.59L19 8l-9 9z",
                "test-icon-pass");
    }

    /** Test failed/errored: Material "cancel" (x-circle), tinted red via {@code .test-icon-fail}. */
    static Node testFailed() {
        return of(
                "M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2m3.59 5L12 10.59 8.41 7 7 8.41"
                        + "L10.59 12 7 15.59 8.41 17 12 13.41 15.59 17 17 15.59 13.41 12 17 8.41z",
                "test-icon-fail");
    }

    /** Test skipped/ignored: Material "remove_circle", tinted amber via {@code .test-icon-skip}. */
    static Node testSkipped() {
        return of("M17 13H7v-2h10m-5-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z", "test-icon-skip");
    }

    /** Test running/pending: Material "circle-outline" — a hollow ring. */
    static Node testRunning() {
        return of("M12 20a8 8 0 0 1-8-8 8 8 0 0 1 8-8 8 8 0 0 1 8 8 8 8 0 0 1-8 8m0-18A10 10 0 0 0 2 12a10 10 "
                + "0 0 0 10 10 10 10 0 0 0 10-10A10 10 0 0 0 12 2z");
    }

    /** TODO: Material "checklist" — a checklist for the TODO/highlight tool window. */
    static Node todo() {
        return of("M22 7l-1.41-1.41L13 13.17l-2.59-2.58L9 12l4 4 9-9zM3 5h11v2H3V5zm0 6h7v2H3v-2zm0 6h7v2H3v-2z");
    }

    /** Table / grid: Material "table_chart" — for the CSV grid preview tool window. */
    static Node table() {
        return of("M10 10.02h5V21h-5zM17 21h3c1.1 0 2-.9 2-2v-9h-5v11zm3-18H5c-1.1 0-2 .9-2 "
                + "2v3h19V5c0-1.1-.9-2-2-2zM3 19c0 1.1.9 2 2 2h3V10H3v9z");
    }

    /** Debug: Material "bug_report" — for the Debug tool window + status segment. */
    static Node debug() {
        return line(
                "M4.2 9.0a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0M8 5.2V3.4M4.8 6.4L3.3 4.9M11.2 6.4l1.5-1.5M4.2 9H2M14 9h-2.2M4.8 11.6l-1.5 1.5M11.2 11.6l1.5 1.5");
    }

    /** Debug "stop" — a filled square (Material "stop"). */
    static Node debugStop() {
        return of("M6 6h12v12H6z");
    }

    /** Debug "pause" — two bars (Material "pause"). */
    static Node debugPause() {
        return of("M6 19h4V5H6v14zm8-14v14h4V5h-4z");
    }

    /** Debug "run to cursor" — an arrow into a bar (Material "keyboard_tab"). */
    static Node debugRunToCursor() {
        return of("M11.59 7.41 15.17 11H1v2h14.17l-3.58 3.59L13 18l6-6-6-6-1.41 1.41zM20 6v12h2V6h-2z");
    }

    /** Debug "step over" — a forward arc arrow (Material "redo"). */
    static Node debugStepOver() {
        return of("M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 "
                + "4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z");
    }

    /** Debug "step into" — a downward arrow (Material "arrow_downward"). */
    static Node debugStepInto() {
        return of("M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z");
    }

    /** Debug "step out" — an upward arrow (Material "arrow_upward"). */
    static Node debugStepOut() {
        return of("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
    }

    /**
     * A {@code >_} terminal prompt — the conventional mark for a command palette.
     *
     * <p>Was a lightning bolt, which says "fast" or "power" but not "type a command here"; on a bar that
     * also carries a run ▶ and a debug bug, "energy" is the one thing it isn't about.
     */
    static Node palette() {
        return line("M2.5 2.5h11a1 1 0 0 1 1 1v9a1 1 0 0 1 -1 1h-11a1 1 0 0 1 -1 -1v-9a1 1 0 0 1 1 -1z"
                + "M4.6 6.1l2.1 2L4.6 10.1M8.6 10.4h3.2");
    }

    static Node closeTab() {
        return line("M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6");
    }

    /**
     * The close-variant glyphs below keep the {@link #closeTab()} X (scaled to ~0.82 to leave margin) as the
     * shared anchor and add one distinguishing mark, so the six tab close-menu items read as a family yet
     * stay distinct at menu size: one square = "other(s)", three squares = "all", a check = "unmodified
     * (saved)", and a left/right triangle = direction.
     */
    private static final String CLOSE_X = "M17.74 7.42 16.58 6.26 12 10.84 7.42 6.26 6.26 7.42 10.84 12 "
            + "6.26 16.58 7.42 17.74 12 13.16 16.58 17.74 17.74 16.58 13.16 12z";

    /** Close other tabs: the X plus one small square (the tab kept) at the top-right. */
    static Node closeOtherTabs() {
        return of(CLOSE_X + " M18.5 2h4v4h-4z");
    }

    /** Close all tabs: the X plus three small squares (all tabs) along the bottom. */
    static Node closeAllTabs() {
        return of(CLOSE_X + " M6 20h3v3H6z M10.5 20h3v3h-3z M15 20h3v3h-3z");
    }

    /** Close unmodified tabs: the X plus a small check (the saved/clean tabs) at the bottom-right. */
    static Node closeUnmodifiedTabs() {
        return of(CLOSE_X + " M17.91 21.32 16.49 19.9 16.01 20.38 17.91 22.28 21.99 18.2 21.51 17.72z");
    }

    /** Close tabs to the left: the X plus a left-pointing triangle. */
    static Node closeTabsLeft() {
        return of(CLOSE_X + " M5 8 5 16 1 12z");
    }

    /** Close tabs to the right: the X plus a right-pointing triangle. */
    static Node closeTabsRight() {
        return of(CLOSE_X + " M19 8 19 16 23 12z");
    }

    /** Material "folder" — reveal a file/folder in the OS file manager. */
    static Node revealInFiles() {
        return of("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z");
    }

    /** Material "terminal" — open a terminal at a folder. */
    static Node terminal() {
        return line(
                "M3.3 2.5h9.4a1.8 1.8 0 0 1 1.8 1.8v7.4a1.8 1.8 0 0 1 -1.8 1.8h-9.4a1.8 1.8 0 0 1 -1.8 -1.8v-7.4a1.8 1.8 0 0 1 1.8 -1.8zM4.3 6l2.4 2-2.4 2M8.6 10.5h3.2");
    }

    /**
     * A cog. Deliberately a toothed outline rather than the circle-plus-eight-rays this used to be — that
     * shape is the universal "brightness/sun" glyph, so it read as a theme toggle sitting on a toolbar
     * that also has one. The teeth are a 32-point polygon (8 teeth, outer r 6.6 / root r 4.8 in the kit's
     * 16-unit box) so it stays square-shouldered at icon size instead of blurring back into a star.
     */
    static Node settings() {
        return line("M6.16 3.57L6.63 1.54L9.37 1.54L9.84 3.57L11.59 2.46L13.54 4.41L12.43 6.16L14.46 6.63L14.46 "
                + "9.37L12.43 9.84L13.54 11.59L11.59 13.54L9.84 12.43L9.37 14.46L6.63 14.46L6.16 12.43L4.41 "
                + "13.54L2.46 11.59L3.57 9.84L1.54 9.37L1.54 6.63L3.57 6.16L2.46 4.41L4.41 2.46Z"
                + "M5.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0");
    }

    static Node trash() {
        return of("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z");
    }

    static Node recent() {
        return line("M2.0 8.0a6.0 6.0 0 1 0 12.0 0a6.0 6.0 0 1 0 -12.0 0M8 4.6V8l2.4 1.5");
    }

    static Node quit() {
        return line("M8 1.8V7M4.6 3.9a5.6 5.6 0 1 0 6.8 0");
    }

    static Node project() {
        return line("M1.5 3.5h4.2l1.5 2h7.3v7H1.5z");
    }

    static Node openFolder() {
        // Material "folder_open" — opening a project folder.
        return of("M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 "
                + "2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z");
    }

    static Node bookmark() {
        return line("M4.5 2h7v12L8 10.6 4.5 14z");
    }

    static Node notes() {
        return line("M3 2.5h10v8l-3 3H3zM10 13.5v-3h3");
    }

    static Node structure() {
        return line("M3 4h10M5.5 8h7.5M8 12h5");
    }

    /** A filled "Z" glyph (top + bottom bars joined by a diagonal), for the Zen-mode exit button. */
    static Node zen() {
        return of("M4 4 H20 V7 L8 17 H20 V20 H4 V17 L16 7 H4 Z");
    }

    /** A filled stencil "E" glyph (left spine + three prongs), for the Expert-mode exit button. */
    static Node expert() {
        return of("M4 4 H20 V7 H7 V10.5 H17 V13.5 H7 V17 H20 V20 H4 Z");
    }

    /** A Material "open in new window" glyph (a frame with an out-arrow), for the detach / pop-out button. */
    static Node detach() {
        return of("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z"
                + "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z");
    }

    /** Material "input" (an arrow into a frame) — put a floating tool window back in the dock. */
    static Node dock() {
        return of("M21 3.01H3c-1.1 0-2 .9-2 2V9h2V4.99h18v14.03H3V15H1v4.01c0 1.1.9 1.98 2 1.98h18c1.1 0 2-.88 "
                + "2-1.98v-14c0-1.11-.9-2-2-2zM11 16l4-4-4-4v3H1v2h10v3z");
    }

    /** Material "fullscreen" (four outward corners) — the tool window header's Maximize button. */
    static Node maximize() {
        return of("M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z");
    }

    /** Material "fullscreen_exit" (four inward corners) — the same button once the window is maximized. */
    static Node restoreSize() {
        return of("M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z");
    }

    static Node closeSmall() {
        // Same outline as closeTab() — used by the tool window header.
        return of("M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 "
                + "19 17.59 13.41 12z");
    }

    /** A plain page. Same 11-unit height as the other toolbar glyphs — see {@link #newFile()}. */
    static Node fileSheet() {
        return line("M4.5 2.5h4.6l2.9 2.9v8.1h-7.5zM9.1 2.5v2.9h2.9");
    }

    /** Diff/compare glyph (Material "compare_arrows") — diff viewer tab + the vs-HEAD command. */
    static Node diff() {
        return of("M9.01 14H2v2h7.01v3L13 15l-3.99-4v3zm5.98-1v-3H22V8h-7.01V5L11 9l3.99 4z");
    }

    /** Merge glyph (Material "merge_type") — the conflict-resolution (merge) viewer tab. */
    static Node merge() {
        return of("M17 20.41L18.41 19 15 15.59 13.59 17 17 20.41zM7.5 8H11v5.59L5.59 19 7 20.41l6-6V8h"
                + "3.5L12 3.5 7.5 8z");
    }

    /** Chevron pointing left (Material "chevron_left") — diff "apply change" arrow toward a left pane. */
    static Node chevronLeft() {
        return of("M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z");
    }

    /** Chevron pointing right (Material "chevron_right") — diff "apply change" arrow toward a right pane. */
    static Node chevronRight() {
        return of("M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z");
    }

    /** Double chevron left (Material "keyboard_double_arrow_left") — diff "apply whole hunk" toward left. */
    static Node doubleChevronLeft() {
        return of("M17.59 18 19 16.59 14.42 12 19 7.41 17.59 6l-6 6 6 6zm-6 0 1.41-1.41L8.42 "
                + "12 13 7.41 11.59 6l-6 6 6 6z");
    }

    /** Double chevron right (Material "keyboard_double_arrow_right") — diff "apply whole hunk" toward right. */
    static Node doubleChevronRight() {
        return of(
                "M6.41 6 5 7.41 9.58 12 5 16.59 6.41 18l6-6-6-6zm6 0L11 7.41 15.58 12 11 " + "16.59 12.41 18l6-6-6-6z");
    }

    static Node outline() {
        return of("M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-"
                + "6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-"
                + "1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-"
                + "6h14v-2H7v2zm0-8v2h14V5H7z");
    }

    static Node warning() {
        return line("M8 2.2L14.6 13.4H1.4zM8 6.8v3M8 11.6v.2");
    }

    static Node splitVertical() {
        return line(
                "M3.5 3.0h9.0a1.5 1.5 0 0 1 1.5 1.5v7.0a1.5 1.5 0 0 1 -1.5 1.5h-9.0a1.5 1.5 0 0 1 -1.5 -1.5v-7.0a1.5 1.5 0 0 1 1.5 -1.5zM8 3v10");
    }

    static Node splitHorizontal() {
        return line(
                "M3.5 3.0h9.0a1.5 1.5 0 0 1 1.5 1.5v7.0a1.5 1.5 0 0 1 -1.5 1.5h-9.0a1.5 1.5 0 0 1 -1.5 -1.5v-7.0a1.5 1.5 0 0 1 1.5 -1.5zM2 8h12");
    }

    static Node pin() {
        return line("M9.5 2l4.5 4.5-2.6.6-2.5 2.5.3 3.4-2.7-2.7L3 14M6.5 7.3L4 4.8l3-.5z");
    }

    static Node about() {
        return line("M1.7000000000000002 8.0a6.3 6.3 0 1 0 12.6 0a6.3 6.3 0 1 0 -12.6 0M8 7.4V11M8 5.1v.2");
    }

    /** Markdown view: Preview only (Material "visibility" — eye). */
    static Node previewOnly() {
        return of("M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-"
                + "11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 "
                + "1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z");
    }

    /** Material "public" (globe) — the HTML Live Preview "open in browser" control. */
    static Node htmlPreview() {
        return line("M1.7999999999999998 8.0a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0M1.8 8h12.4");
    }

    /** A compass (Material "explore") — Safari. */
    static Node browserSafari() {
        return of(
                "M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.4"
                        + "8 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z");
    }

    /** The Google Chrome logo (Simple Icons, CC0). */
    static Node browserChrome() {
        return of(
                "M12 0C8.21 0 4.831 1.757 2.632 4.501l3.953 6.848A5.454 5.454 0 0 1 12 6.545h10.691A12 12 0 0 0 12 0zM1.9"
                        + "31 5.47A11.943 11.943 0 0 0 0 12c0 6.012 4.42 10.991 10.189 11.864l3.953-6.847a5.45 5.45 0 0 1-6.865-2.2"
                        + "9zm13.342 2.166a5.446 5.446 0 0 1 1.45 7.09l.002.001h-.002l-5.344 9.257c.206.01.413.016.621.016 6.627 0 "
                        + "12-5.373 12-12 0-1.54-.29-3.011-.818-4.364zM12 16.364a4.364 4.364 0 1 1 0-8.728 4.364 4.364 0 0 1 0 8.72"
                        + "8Z");
    }

    /** The Firefox logo (Simple Icons, CC0). */
    static Node browserFirefox() {
        return of(
                "M8.824 7.287c.008 0 .004 0 0 0zm-2.8-1.4c.006 0 .003 0 0 0zm16.754 2.161c-.505-1.215-1.53-2.528-2.333-2."
                        + "943.654 1.283 1.033 2.57 1.177 3.53l.002.02c-1.314-3.278-3.544-4.6-5.366-7.477-.091-.147-.184-.292-.273-"
                        + ".446a3.545 3.545 0 01-.13-.24 2.118 2.118 0 01-.172-.46.03.03 0 00-.027-.03.038.038 0 00-.021 0l-.006.00"
                        + "1a.037.037 0 00-.01.005L15.624 0c-2.585 1.515-3.657 4.168-3.932 5.856a6.197 6.197 0 00-2.305.587.297.297"
                        + " 0 00-.147.37c.057.162.24.24.396.17a5.622 5.622 0 012.008-.523l.067-.005a5.847 5.847 0 011.957.222l.095."
                        + "03a5.816 5.816 0 01.616.228c.08.036.16.073.238.112l.107.055a5.835 5.835 0 01.368.211 5.953 5.953 0 012.0"
                        + "34 2.104c-.62-.437-1.733-.868-2.803-.681 4.183 2.09 3.06 9.292-2.737 9.02a5.164 5.164 0 01-1.513-.292 4."
                        + "42 4.42 0 01-.538-.232c-1.42-.735-2.593-2.121-2.74-3.806 0 0 .537-2 3.845-2 .357 0 1.38-.998 1.398-1.287"
                        + "-.005-.095-2.029-.9-2.817-1.677-.422-.416-.622-.616-.8-.767a3.47 3.47 0 00-.301-.227 5.388 5.388 0 01-.0"
                        + "32-2.842c-1.195.544-2.124 1.403-2.8 2.163h-.006c-.46-.584-.428-2.51-.402-2.913-.006-.025-.343.176-.389.2"
                        + "06-.406.29-.787.616-1.136.974-.397.403-.76.839-1.085 1.303a9.816 9.816 0 00-1.562 3.52c-.003.013-.11.487"
                        + "-.19 1.073-.013.09-.026.181-.037.272a7.8 7.8 0 00-.069.667l-.002.034-.023.387-.001.06C.386 18.795 5.593 "
                        + "24 12.016 24c5.752 0 10.527-4.176 11.463-9.661.02-.149.035-.298.052-.448.232-1.994-.025-4.09-.753-5.844z");
    }

    /** The Microsoft Edge logo (Simple Icons, CC0). */
    static Node browserEdge() {
        return of(
                "M21.86 17.86q.14 0 .25.12.1.13.1.25t-.11.33l-.32.46-.43.53-.44.5q-.21.25-.38.42l-.22.23q-.58.53-1.34 1.0"
                        + "4-.76.51-1.6.91-.86.4-1.74.64t-1.67.24q-.9 0-1.69-.28-.8-.28-1.48-.78-.68-.5-1.22-1.17-.53-.66-.92-1.44-"
                        + ".38-.77-.58-1.6-.2-.83-.2-1.67 0-1 .32-1.96.33-.97.87-1.8.14.95.55 1.77.41.82 1.02 1.5.6.68 1.38 1.21.78"
                        + ".54 1.64.9.86.36 1.77.56.92.2 1.8.2 1.12 0 2.18-.24 1.06-.23 2.06-.72l.2-.1.2-.05zm-15.5-1.27q0 1.1.27 2"
                        + ".15.27 1.06.78 2.03.51.96 1.24 1.77.74.82 1.66 1.4-1.47-.2-2.8-.74-1.33-.55-2.48-1.37-1.15-.83-2.08-1.9-"
                        + ".92-1.07-1.58-2.33T.36 14.94Q0 13.54 0 12.06q0-.81.32-1.49.31-.68.83-1.23.53-.55 1.2-.96.66-.4 1.35-.66."
                        + "74-.27 1.5-.39.78-.12 1.55-.12.7 0 1.42.1.72.12 1.4.35.68.23 1.32.57.63.35 1.16.83-.35 0-.7.07-.33.07-.6"
                        + "5.23v-.02q-.63.28-1.2.74-.57.46-1.05 1.04-.48.58-.87 1.26-.38.67-.65 1.39-.27.71-.42 1.44-.15.72-.15 1.3"
                        + "8zM11.96.06q1.7 0 3.33.39 1.63.38 3.07 1.15 1.43.77 2.62 1.93 1.18 1.16 1.98 2.7.49.94.76 1.96.28 1 .28 "
                        + "2.08 0 .89-.23 1.7-.24.8-.69 1.48-.45.68-1.1 1.22-.64.53-1.45.88-.54.24-1.11.36-.58.13-1.16.13-.42 0-.97"
                        + "-.03-.54-.03-1.1-.12-.55-.1-1.05-.28-.5-.19-.84-.5-.12-.09-.23-.24-.1-.16-.1-.33 0-.15.16-.35.16-.2.35-."
                        + "5.2-.28.36-.68.16-.4.16-.95 0-1.06-.4-1.96-.4-.91-1.06-1.64-.66-.74-1.52-1.28-.86-.55-1.79-.89-.84-.3-1."
                        + "72-.44-.87-.14-1.76-.14-1.55 0-3.06.45T.94 7.55q.71-1.74 1.81-3.13 1.1-1.38 2.52-2.35Q6.68 1.1 8.37.58q1"
                        + ".7-.52 3.58-.52Z");
    }

    /**
     * The Maven feather (Material Design Icons "feather", Apache-2.0).
     *
     * <p>Deliberately <em>not</em> the official Simple Icons "Apache Maven" mark, which this used to be:
     * that logo is a two-feather illustration whose quills are hairline strokes, and at the stripe's ~19px
     * it collapses into an unreadable blob rather than reading as a feather at all. A single bold feather
     * keeps the Maven/Apache identity legible at icon size and sits at the same visual weight as the npm,
     * Cargo, Go and Gradle glyphs beside it.
     */
    static Node maven() {
        return of("M22,2C22,2 14.36,1.63 8.34,9.88C3.72,16.21 2,22 2,22L3.94,21C5.38,18.5 6.13,17.47 7.54,16C10.07,"
                + "16.74 12.71,16.65 15,14C13,13.44 11.4,13.57 9.04,13.81C11.69,12 13.5,11.6 16,12L17,10C15.2,9.66 14,"
                + "9.63 12.22,10.04C14.19,8.65 15.56,7.87 18,8L19.21,6.07C17.65,5.96 16.71,6.13 14.92,6.57C16.53,5.11 "
                + "18,4.45 20.14,4.32C20.14,4.32 21.19,2.43 22,2Z");
    }

    /** The npm logo (Simple Icons, CC0). */
    static Node npm() {
        return of("M1.763 0C.786 0 0 .786 0 1.763v20.474C0 23.214.786 24 1.763 24h20.474c.977 0 1.763-.786 1.763-1"
                + ".763V1.763C24 .786 23.214 0 22.237 0zM5.13 5.323l13.837.019-.009 13.836h-3.464l.01-10.382h-3.456L1"
                + "2.04 19.17H5.113z");
    }

    /** A shipping box/package glyph (Material "inventory_2") for the Cargo (Rust) build tool. */
    static Node cargo() {
        return of("M20 2H4c-1.1 0-2 .9-2 2v3.01c0 .72.43 1.34 1 1.69V20c0 1.1.9 2 2 2h14c1.1 0 2-.9 "
                + "2-2V8.7c.57-.35 1-.97 1-1.69V4c0-1.1-.9-2-2-2zm-5 12H9v-2h6v2zm5-7H4V4l16-.02V7z");
    }

    /** A running-figure glyph (Material "directions_run") for the Go build tool ("go"/speed). */
    static Node go() {
        return of("M13.49 5.48c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm-3.6 13.9l1-4.4 2.1 2v6h2v-7.5l"
                + "-2.1-2 .6-3c1.3 1.5 3.3 2.5 5.5 2.5v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 "
                + "0-.5.1-.8.1l-5.2 2.2v4.7h2v-3.4l1.8-.7-1.6 8.1-4.9-1-.4 2 7 1.4z");
    }

    /** A stacked-layers glyph (Material "layers") for the Gradle build tool (assemble/build). */
    static Node gradle() {
        return of("M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 "
                + "7 1.63 1.27L12 16z");
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
        return line("M3 8.6l3.2 3.2L13 4.6");
    }

    /** Material "remove" (minus) — "Unstage" git context-menu item. */
    static Node remove() {
        return of("M19 13H5v-2h14v2z");
    }
}
