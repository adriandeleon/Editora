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

    private Icons() {}

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

    static Node newFile() {
        return of("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z");
    }

    /** A folder-with-plus glyph (Material "create_new_folder") for the "New Folder" action. */
    static Node newFolder() {
        return of("M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-1 "
                + "8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z");
    }

    /** Request/response arrows (Material "swap_horiz") — the HTTP Client tool window. */
    static Node httpClient() {
        return of("M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z");
    }

    /** A robot glyph (MDI "robot") for the AI Agent chat tool window. */
    static Node agent() {
        return of("M12,2A2,2 0 0,1 14,4C14,4.74 13.6,5.39 13,5.73V7H14A7,7 0 0,1 21,14H22A1,1 0 0,1 23,15V18A1,1 0 "
                + "0,1 22,19H21V20A2,2 0 0,1 19,22H5A2,2 0 0,1 3,20V19H2A1,1 0 0,1 1,18V15A1,1 0 0,1 2,14H3A7,7 0 "
                + "0,1 10,7H11V5.73C10.4,5.39 10,4.74 10,4A2,2 0 0,1 12,2M7.5,13A2.5,2.5 0 0,0 5,15.5A2.5,2.5 0 0,0 "
                + "7.5,18A2.5,2.5 0 0,0 10,15.5A2.5,2.5 0 0,0 7.5,13M16.5,13A2.5,2.5 0 0,0 14,15.5A2.5,2.5 0 0,0 "
                + "16.5,18A2.5,2.5 0 0,0 19,15.5A2.5,2.5 0 0,0 16.5,13Z");
    }

    /** A minimal square-frame glyph for the Simple-UI-mode toggle. */
    static Node simpleMode() {
        return of("M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V5h14v14z");
    }

    /** A cloud glyph marking a remote (SFTP) file/tab. */
    static Node remote() {
        return of("M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 "
                + "3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z");
    }

    /** Source-control branch glyph (Material "account_tree"/fork) for the Git tool window stripe. */
    static Node git() {
        return of("M17 6c0-1.66-1.34-3-3-3s-3 1.34-3 3c0 1.3.84 2.4 2 2.82V11c0 1.1-.9 2-2 2H8.82C8.4 "
                + "11.84 7.3 11 6 11c-1.66 0-3 1.34-3 3s1.34 3 3 3c1.3 0 2.4-.84 2.82-2H11c2.21 0 4-1.79 "
                + "4-4V8.82C16.16 8.4 17 7.3 17 6zM6 15c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 "
                + "1zm8-8c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z");
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

    /** Clock-with-arrow "history" glyph (Material "history") for the Git Log tool window. */
    static Node gitLog() {
        return of("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 "
                + "7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 "
                + "9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z");
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

    /** Material "select_all". */
    static Node selectAll() {
        return of("M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 "
                + "0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-"
                + "2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-"
                + "2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z");
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

    /** Filled square (Material "stop") — stop a running build/task in the build-tool tree toolbar. */
    static Node stopSquare() {
        return of("M6 6h12v12H6z");
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
        return of(
                "M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 "
                        + "5 12 5c-.49 0-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05"
                        + ".33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 "
                        + "5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z");
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

    static Node palette() {
        return of("M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 "
                + "7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z");
    }

    static Node closeTab() {
        return of("M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 "
                + "19 17.59 13.41 12z");
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
        return of("M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V8h16v10z"
                + "M18 17h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z");
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

    /** A filled stencil "E" glyph (left spine + three prongs), for the Expert-mode exit button. */
    static Node expert() {
        return of("M4 4 H20 V7 H7 V10.5 H17 V13.5 H7 V17 H20 V20 H4 Z");
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

    /** Material "public" (globe) — the HTML Live Preview "open in browser" control. */
    static Node htmlPreview() {
        return of("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-"
                + "3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-"
                + "1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 "
                + "5 7.41 0 2.08-.8 3.97-2.1 5.39z");
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

    /** The Apache Maven feather logo (Simple Icons, CC0). */
    static Node maven() {
        return of(
                "M4.237.001c-.312-.013-.665.072-.828.457-.158.374-.283 1.188-.34 2.276l1.223.591c-.02-.737.007-1.43.076-2.066-.026.29"
                        + "9-.056.96.006 2.039.019.342.049.725.088 1.15.002.024.002.047.007.069a45.485 45.485 0 0 0 .309 2.412c.057.368.126.752"
                        + ".195 1.16l-.01.01c.014.01.015.018.014.023l.03.16c.03.162.06.328.093.494l.108.553.056.289a61.72 61.72 0 0 0 .457 2.06"
                        + "8c.09.382.186.78.287 1.186.098.386.199.783.309 1.193.096.362.199.735.303 1.117.003.018.012.036.015.055a145.826 145.8"
                        + "26 0 0 0 .34 1.185l.049.174c.078.261.158.533.242.805a4.2 4.2 0 0 1-.293-.135l-.19-.654c-.02-.077-.042-.148-.062-.225"
                        + "l-.002-.004-.004-.002c-.087-.3-.17-.607-.257-.916-.023-.087-.044-.173-.069-.263l-.314-1.178c-.1-.381-.194-.765-.29-1"
                        + ".154-.094-.39-.185-.78-.277-1.172-.093-.401-.181-.8-.265-1.203-.085-.396-.161-.798-.24-1.193a50.315 50.315 0 0 1-.21"
                        + "1-1.17c-.004-.013-.006-.03-.01-.041l.004-.002c-.057-.386-.116-.77-.174-1.15a60.905 60.905 0 0 1-.154-1.204 27.447 27"
                        + ".447 0 0 1-.172-2.41l-1.22-.59c-.004.074-.01.15-.013.23-.012.294-.02.605-.023.93a45.3 45.3 0 0 0 .006 1.157c.009.37."
                        + "025.755.045 1.148.02.336.042.675.07 1.022l.002.039.006.004c.003.023.007.05.006.076.033.368.064.739.107 1.115a34.493 "
                        + "34.493 0 0 0 .303 2.125c.01.064.024.131.035.195a23.418 23.418 0 0 0 .547 2.32c.07.237.14.464.21.68.063.182.13.365.19"
                        + "4.545.155.422.327.832.512 1.232l.006.004a.318.318 0 0 0 .02.05c.225.485.475.95.755 1.395.01.013.02.033.03.047-.455-."
                        + "183-1.259-.098-1.253-.097.83.288 1.557.64 2.016 1.175-.183.2-.523.352-.953.477.594.064.924-.039 1.045-.092-.31.26-.4"
                        + "83.732-.635 1.24.35-.57.696-.949 1.033-1.094.078.258.162.524.244.788A147.532 147.532 0 0 0 5.157 24a.56.56 0 0 0 .43"
                        + "-.312c.13-.282.83-1.775 1.908-3.875.413 1.303.88 2.679 1.386 4.109a.494.494 0 0 0 .076-.465 103.735 103.735 0 0 1-1."
                        + "308-3.945c.154-.299.316-.612.484-.932.125.04.255.094.389.155.203.186.352.491.482.84a1.515 1.515 0 0 0-.334-1.098c1.3"
                        + "35.258 2.547.09 3.287-.81a3.97 3.97 0 0 0 .192-.258c-.325.304-.682.404-1.313.273.996-.281 1.523-.617 2.035-1.22.12-."
                        + "145.244-.303.371-.48-.943.722-1.927.822-2.9.493l-.045-.018c.914.02 2.203-.474 3.092-1.189.41-.33.796-.73 1.17-1.21.2"
                        + "8-.359.55-.76.82-1.216.234-.393.468-.824.7-1.293a2.83 2.83 0 0 1-.74.137l-.144.008c-.048.002-.093 0-.146.002.885-.19"
                        + "8 1.5-.74 1.994-1.447-.24.117-.628.262-1.07.297-.058.006-.12.006-.182.006-.013-.002-.028 0-.047-.002.306-.078.574-.1"
                        + "78.81-.309a3.363 3.363 0 0 0 .358-.236c.044-.037.088-.07.13-.106.099-.086.193-.18.28-.287.028-.034.056-.063.08-.098."
                        + "036-.05.073-.098.104-.146a8.388 8.388 0 0 0 .51-.828c.015-.031.032-.057.046-.088.04-.084.08-.16.11-.227.042-.099.074"
                        + "-.179.092-.238a.515.515 0 0 1-.108.051c-.273.112-.727.187-1.086.201-.004 0-.008 0-.013.004h-.067c.72-.214 1.067-.45 "
                        + "1.422-.818a13.883 13.883 0 0 0 1.154-1.428c.264-.37.505-.738.692-1.072a6.5 6.5 0 0 0 .298-.592c.066-.157.122-.305.17"
                        + "2-.45-.466.01-.986.011-1.48 0 .495.01 1.015.007 1.484-.005.5-1.485.063-2.262.063-2.262s-.526-1.212-1.4-.851c-.426.17"
                        + "5-1.172.73-2.083 1.56l.514 1.45a17.561 17.561 0 0 1 1.703-1.602c-.257.22-.807.726-1.615 1.644-.256.29-.537.624-.844."
                        + "997-.017.02-.035.038-.047.06a51.435 51.435 0 0 0-1.666 2.187c-.248.34-.498.704-.765 1.088h-.016c.002.02-.004.028-.01"
                        + ".032l-.101.152c-.104.155-.213.31-.318.47l-.352.534c-.061.09-.124.181-.186.277-.184.282-.367.573-.558.873a97.351 97.3"
                        + "51 0 0 0-1.428 2.338 96.866 96.866 0 0 0-1.341 2.343c-.012.017-.02.04-.034.057a197.256 197.256 0 0 0-.668 1.223l-.09"
                        + "7.181c-.17.318-.346.642-.52.979 0 .004-.005.008-.006.013-.026.048-.05.093-.072.141-.117.222-.218.424-.45.87a1.352 1."
                        + "352 0 0 0-.233-.182l.345-.65c.047-.089.096-.177.143-.27l.04-.077.546-1.001.13-.233v-.006l-.001-.006c.169-.31.345-.62"
                        + ".52-.94.051-.087.102-.173.153-.265.224-.395.454-.794.684-1.197a91.685 91.685 0 0 1 2.135-3.504c.247-.386.503-.77.754"
                        + "-1.152.092-.138.182-.272.279-.41a72.9 72.9 0 0 1 .48-.701c.007-.012.019-.024.026-.037h.006c.26-.356.517-.713.773-1.0"
                        + "65.278-.373.554-.735.83-1.09a31.075 31.075 0 0 1 1.777-2.075l-.515-1.446c-.06.057-.126.116-.192.178a32.37 32.37 0 0 "
                        + "0-.758.729c-.295.294-.597.606-.912.935a46.032 46.032 0 0 0-1.632 1.838l-.03.033.002.008c-.017.02-.033.044-.054.064-."
                        + "266.323-.538.649-.801.985a39.105 39.105 0 0 0-1.445 1.95c-.043.06-.085.126-.127.186a26.458 26.458 0 0 0-1.403 2.303c"
                        + "-.13.247-.256.485-.37.715-.096.195-.187.395-.278.591-.21.463-.398.93-.566 1.399l.002.006a.36.36 0 0 0-.026.058c-.108"
                        + ".303-.203.608-.29.914-.14.174-.302.325-.483.46a3.505 3.505 0 0 0-.131-.153 5.148 5.148 0 0 0 .824-2.211 6.4 6.4 0 0 "
                        + "0-.016-1.488c-.046-.4-.126-.82-.238-1.274-.097-.393-.217-.81-.363-1.248-.091.185-.22.367-.379.545l-.086.094c-.029.03"
                        + "2-.06.06-.092.094.434-.674.486-1.397.358-2.148a2.722 2.722 0 0 1-.49.85c-.033.038-.072.077-.11.116-.01.007-.019.018-"
                        + ".033.028.144-.24.25-.467.318-.698a1.29 1.29 0 0 0 .04-.146 2.85 2.85 0 0 0 .038-.225l.018-.146a2.11 2.11 0 0 0-.002-"
                        + ".354c-.003-.04-.004-.076-.01-.113-.01-.055-.016-.105-.027-.154a7.416 7.416 0 0 0-.193-.84c-.01-.028-.015-.056-.026-."
                        + "084-.027-.079-.048-.149-.072-.209a2.1 2.1 0 0 0-.09-.209.455.455 0 0 1-.035.1c-.102.24-.34.57-.557.8-.003.003-.007.0"
                        + "05-.007.01l-.04.043c.318-.58.39-.946.385-1.398a12.274 12.274 0 0 0-.16-1.615 10.68 10.68 0 0 0-.232-1.104 5.853 5.85"
                        + "3 0 0 0-.18-.558 6.337 6.337 0 0 0-.172-.391 26.18 26.18 0 0 0 .002-.004C5.576.341 4.82.124 4.82.124s-.27-.11-.582-."
                        + "123zm3.38 15.783l.032.082v.002c-.06.033-.116.067-.178.097-.012.004-.024.012-.039.018a2.41 2.41 0 0 0 .186-.2zm-.603 "
                        + "1.626c.13.136.25.242.354.32l.07.227a1.866 1.866 0 0 0-.246.053l-.03-.098c-.024-.084-.048-.17-.076-.257l-.021-.073zm."
                        + "26.875a2.34 2.34 0 0 1 .271.01l.07.229a.778.778 0 0 1 .247-.004l-.326.627a127.643 127.643 0 0 1-.262-.862z");
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
        return of("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
    }

    /** Material "remove" (minus) — "Unstage" git context-menu item. */
    static Node remove() {
        return of("M19 13H5v-2h14v2z");
    }
}
