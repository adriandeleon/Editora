package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the user-theme loader offers from a config directory: which {@code .css} files become themes, under
 * what name, and the colours the editor derives from them — including the fallbacks for a file that names
 * few or none of the tokens. No FX toolkit: {@link Color} needs none.
 */
class UserThemesLoadTest {

    @TempDir
    Path config;

    @AfterEach
    void forget() {
        UserThemes.load(null); // the loader's state is process-wide
    }

    private Path theme(String folder, String file, String css) throws IOException {
        Path dir = Files.createDirectories(config.resolve(folder));
        return Files.writeString(dir.resolve(file), css);
    }

    @Test
    void aDarkControlThemeIsReadFromItsTokens() throws Exception {
        Path css = theme(
                "themes",
                "midnight-ocean.css",
                ".root { -color-bg-default: #101820; -color-fg-default: #e0e6ee; -color-bg-subtle: #18222c;\n"
                        + " -color-fg-muted: #8899aa; -color-accent-emphasis: #3388ff; }");
        UserThemes.load(config);

        assertEquals(
                List.of("Midnight Ocean"),
                List.copyOf(UserThemes.controlThemes().keySet()));
        assertEquals(Map.of(), UserThemes.editorThemes());
        UserThemes.Entry e = UserThemes.controlThemes().get("Midnight Ocean");
        assertEquals(css.toUri().toString(), e.stylesheetUrl());
        assertTrue(e.dark());
        assertEquals(Color.web("#101820"), e.background());
        assertEquals(Color.web("#e0e6ee"), e.foreground());
        assertEquals(Color.web("#18222c"), e.lineHighlight());
        assertEquals(Color.web("#8899aa"), e.minimapText());
        assertEquals(Color.web("#3388ff").deriveColor(0, 1, 1, 0.25), e.minimapViewport());
    }

    @Test
    void anEditorThemeWithOnlySurfacePropertiesDerivesTheRest() throws Exception {
        theme(
                "editor-themes",
                "paper.CSS",
                ".editor-area { -fx-background-color: #fafafa; }\n.text { -fx-fill: #222222; }");
        UserThemes.load(config);

        assertEquals(Map.of(), UserThemes.controlThemes());
        UserThemes.Entry e = UserThemes.editorThemes().get("Paper");
        assertFalse(e.dark());
        assertEquals(Color.web("#fafafa"), e.background());
        assertEquals(Color.web("#222222"), e.foreground());
        assertEquals(Color.web("#fafafa").interpolate(Color.BLACK, 0.08), e.lineHighlight(), "a light theme darkens");
        assertEquals(Color.web("#222222").interpolate(Color.web("#fafafa"), 0.45), e.minimapText());
        assertEquals(Color.web("#0969da24"), e.minimapViewport());
    }

    @Test
    void aDarkThemeWithoutAForegroundGetsALightOneAndALighterCurrentLine() throws Exception {
        theme("editor-themes", "ink.css", ".editor-area { -fx-background-color: #0b0b0b; }");
        UserThemes.load(config);
        UserThemes.Entry e = UserThemes.editorThemes().get("Ink");
        assertTrue(e.dark());
        assertEquals(Color.web("#dddddd"), e.foreground());
        assertEquals(Color.web("#0b0b0b").interpolate(Color.WHITE, 0.08), e.lineHighlight());
        assertEquals(Color.web("#58a6ff40"), e.minimapViewport());
    }

    @Test
    void aStylesheetThatNamesNoColourIsTreatedAsALightTheme() throws Exception {
        theme("themes", "spacing_only.css", ".button { -fx-padding: 4; }");
        UserThemes.load(config);
        UserThemes.Entry e = UserThemes.controlThemes().get("Spacing Only");
        assertFalse(e.dark());
        assertEquals(Color.web("#ffffff"), e.background());
        assertEquals(Color.web("#24292f"), e.foreground());
    }

    @Test
    void onlyRealStylesheetsWithANameOfTheirOwnAreOffered() throws Exception {
        theme("themes", "empty.css", "");
        theme("themes", "notes.css", "just some text, no declarations");
        theme("themes", "readme.txt", ".root { -color-bg-default: #000000; }");
        theme("themes", "---.css", ".root { -color-bg-default: #000000; }"); // no name left after cleaning
        theme("themes", Themes.NAMES.getFirst().toLowerCase().replace(' ', '-') + ".css", ".root { -fx-padding: 1; }");
        theme("themes", "real.css", ".root { -color-bg-default: #000000; }");
        Files.createDirectories(config.resolve("themes").resolve("folder.css"));
        UserThemes.load(config);
        assertEquals(
                List.of("Real"), List.copyOf(UserThemes.controlThemes().keySet()), "a built-in's name is not taken");
    }

    @Test
    void twoFilesThatCleanToTheSameNameYieldOneThemeTheFirstByFileName() throws Exception {
        Path first = theme("themes", "my-theme.css", ".root { -color-bg-default: #111111; }");
        theme("themes", "my_theme.css", ".root { -color-bg-default: #eeeeee; }");
        UserThemes.load(config);
        assertEquals(1, UserThemes.controlThemes().size());
        assertEquals(
                first.toUri().toString(),
                UserThemes.controlThemes().get("My Theme").stylesheetUrl());
    }

    @Test
    void anAbsurdlyLargeFileIsIgnored() throws Exception {
        String rule = ".root { -color-bg-default: #000000; }\n";
        theme("themes", "huge.css", rule.repeat(4 * 1024 * 1024 / rule.length() + 2));
        UserThemes.load(config);
        assertEquals(Map.of(), UserThemes.controlThemes());
    }

    @Test
    void aConfigDirectoryWithNoThemeFoldersOffersNothingAndNullForgetsWhatWasLoaded() throws Exception {
        UserThemes.load(config);
        assertEquals(Map.of(), UserThemes.controlThemes());
        assertEquals(Map.of(), UserThemes.editorThemes());

        Files.writeString(config.resolve("themes"), "a file where the folder would be");
        UserThemes.load(config);
        assertEquals(Map.of(), UserThemes.controlThemes());

        Files.delete(config.resolve("themes"));
        theme("themes", "real.css", ".root { -color-bg-default: #000000; }");
        UserThemes.load(config);
        assertEquals(1, UserThemes.controlThemes().size());
        UserThemes.load(null);
        assertEquals(Map.of(), UserThemes.controlThemes());
    }

    @Test
    void aTokenIsNotMistakenForALongerOneAndAReferenceCycleEnds() {
        String css = ".root { -color-bg-default-hover: #111111; -color-bg-default: #222222; }";
        assertEquals(Color.web("#222222"), UserThemes.parseColor(css, "-color-bg-default"));

        assertNull(UserThemes.parseColor("-a: -b; -b: -a;", "-a"), "two tokens that name each other");
        assertNull(UserThemes.parseColor("-a: -a;", "-a"), "a token that names itself");
        assertNull(
                UserThemes.parseColor("-a: -b; -b: -c; -c: -d; -d: -e; -e: -f; -f: #010203;", "-a"), "too many hops");
        assertEquals(
                Color.web("#010203"),
                UserThemes.parseColor("-a: -b; -b: -c; -c: -d; -d: #010203;", "-a"),
                "three hops");
        assertEquals(
                Color.web("#040506"),
                UserThemes.parseColor(".x { -a: -missing; } .y { -a: #040506; }", "-a"),
                "a dead reference does not hide a later literal");
    }
}
