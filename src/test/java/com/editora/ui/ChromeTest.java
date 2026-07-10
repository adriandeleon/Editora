package com.editora.ui;

import java.util.Set;

import com.editora.ui.Chrome.PaletteGates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure chrome decisions: effective visibility under Zen/Simple overlays + palette command gating. */
class ChromeTest {

    // --- effective visibility: a saved pref shows only when neither overlay hides it ---

    @Test
    void zenHidesEveryChromeAndViewOption() {
        boolean zen = true;
        boolean simple = false;
        assertFalse(Chrome.toolbar(true, zen));
        assertFalse(Chrome.statusBar(true, zen));
        assertFalse(Chrome.tabBar(true, zen));
        assertFalse(Chrome.breadcrumb(true, zen, simple));
        assertFalse(Chrome.toolStripes(true, zen, simple));
        assertFalse(Chrome.columnRuler(true, zen));
        assertFalse(Chrome.lineHighlight(true, zen));
        assertFalse(Chrome.whitespace(true, zen));
        assertFalse(Chrome.lineNumbers(true, zen, simple));
        assertFalse(Chrome.minimap(true, zen, simple));
    }

    @Test
    void simpleHidesItsSubsetButLeavesTheBars() {
        boolean zen = false;
        boolean simple = true;
        // Simple keeps the toolbar / status bar / tab bar (only Zen hides those)...
        assertTrue(Chrome.toolbar(true, zen));
        assertTrue(Chrome.statusBar(true, zen));
        assertTrue(Chrome.tabBar(true, zen));
        // ...but hides the breadcrumb, tool stripes, line numbers, minimap, and the whole gutter.
        assertFalse(Chrome.breadcrumb(true, zen, simple));
        assertFalse(Chrome.toolStripes(true, zen, simple));
        assertFalse(Chrome.lineNumbers(true, zen, simple));
        assertFalse(Chrome.minimap(true, zen, simple));
        assertFalse(Chrome.gutter(simple));
        // Ruler / line highlight / whitespace are Zen-only, so Simple leaves them on.
        assertTrue(Chrome.columnRuler(true, zen));
        assertTrue(Chrome.lineHighlight(true, zen));
        assertTrue(Chrome.whitespace(true, zen));
    }

    @Test
    void withNoOverlayTheSavedPrefPassesThrough() {
        assertTrue(Chrome.toolbar(true, false));
        assertFalse(Chrome.toolbar(false, false)); // user turned the toolbar off → still off
        assertTrue(Chrome.lineNumbers(true, false, false));
        assertFalse(Chrome.lineNumbers(false, false, false));
        assertTrue(Chrome.gutter(false)); // gutter present unless Simple mode
    }

    // --- palette command gating ---

    private static PaletteGates allOn() {
        return new PaletteGates(
                true, true, true, true, true, true, Set.of(), true, true, true, true, true, true, true, true);
    }

    private static PaletteGates allOff() {
        return new PaletteGates(
                false,
                false,
                false,
                false,
                false,
                false,
                Set.of("maven", "npm", "cargo", "go", "gradle"),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    @Test
    void everythingVisibleWhenAllFeaturesEnabled() {
        PaletteGates g = allOn();
        for (String id : new String[] {
            "git.commit",
            "tool.commit",
            "tool.gitLog",
            "lsp.gotoDefinition",
            "tool.problems",
            "http.runRequest",
            "tool.http",
            "externalTool.run",
            "tool.externalTools",
            "project.open",
            "tool.project",
            "notes.add",
            "tool.notes",
            "mermaid.export",
            "htmlPreview.open",
            "tool.fileHistory",
            "mcp.start",
            "plugins.browse",
            "file.save",
            "maven.showActions",
            "tool.maven"
        }) {
            assertTrue(Chrome.paletteVisible(id, g), id + " should be visible when all features are on");
        }
    }

    @Test
    void disabledFeatureHidesItsCommandsByPrefixAndExactToolId() {
        PaletteGates off = allOff();
        // prefix-matched
        assertFalse(Chrome.paletteVisible("git.push", off));
        assertFalse(Chrome.paletteVisible("lsp.findReferences", off));
        assertFalse(Chrome.paletteVisible("externalTool.run.jq", off));
        assertFalse(Chrome.paletteVisible("plugins.browse", off));
        assertFalse(Chrome.paletteVisible("maven.showActions", off));
        assertFalse(Chrome.paletteVisible("cargo.showActions", off));
        assertFalse(Chrome.paletteVisible("go.runCustom", off));
        assertFalse(Chrome.paletteVisible("tool.gradle", off));
        // exact tool-window ids
        assertFalse(Chrome.paletteVisible("tool.commit", off));
        assertFalse(Chrome.paletteVisible("tool.gitLog", off));
        assertFalse(Chrome.paletteVisible("tool.problems", off));
        assertFalse(Chrome.paletteVisible("tool.http", off));
        assertFalse(Chrome.paletteVisible("tool.externalTools", off));
        assertFalse(Chrome.paletteVisible("tool.fileHistory", off));
        assertFalse(Chrome.paletteVisible("tool.project", off));
        assertFalse(Chrome.paletteVisible("tool.notes", off));
        assertFalse(Chrome.paletteVisible("tool.maven", off));
    }

    @Test
    void unrelatedAndOtherToolCommandsStayVisibleWhenAFeatureIsOff() {
        PaletteGates off = allOff();
        assertTrue(Chrome.paletteVisible("file.save", off));
        assertTrue(Chrome.paletteVisible("edit.cut", off));
        assertTrue(Chrome.paletteVisible("tool.run", off)); // not a gated tool window
        assertTrue(Chrome.paletteVisible("tool.bookmarks", off));
    }

    @Test
    void onlyTheDisabledFeatureIsFiltered() {
        // git off, everything else on → only git.* / commit / gitLog hidden
        PaletteGates gitOff = new PaletteGates(
                true, false, true, true, true, true, Set.of(), true, true, true, true, true, true, true, true);
        assertFalse(Chrome.paletteVisible("git.commit", gitOff));
        assertFalse(Chrome.paletteVisible("tool.gitLog", gitOff));
        assertTrue(Chrome.paletteVisible("lsp.hover", gitOff));
        assertTrue(Chrome.paletteVisible("tool.http", gitOff));
        assertTrue(Chrome.paletteVisible("externalTool.run", gitOff));
    }

    @Test
    void buildToolCommandsHiddenOnlyForTheDisabledTool() {
        // Maven disabled, npm still enabled → only maven.* / tool.maven hidden; npm.* stays visible.
        PaletteGates mavenOff = new PaletteGates(
                true, true, true, true, true, true, Set.of("maven"), true, true, true, true, true, true, true, true);
        assertFalse(Chrome.paletteVisible("maven.showActions", mavenOff));
        assertFalse(Chrome.paletteVisible("maven.runCustom", mavenOff));
        assertFalse(Chrome.paletteVisible("tool.maven", mavenOff));
        assertTrue(Chrome.paletteVisible("npm.showActions", mavenOff)); // other build tool unaffected
        assertTrue(Chrome.paletteVisible("tool.npm", mavenOff));
        assertTrue(Chrome.paletteVisible("git.commit", mavenOff));
        assertTrue(Chrome.paletteVisible("tool.http", mavenOff));

        // npm disabled, Maven still enabled → the mirror case.
        PaletteGates npmOff = new PaletteGates(
                true, true, true, true, true, true, Set.of("npm"), true, true, true, true, true, true, true, true);
        assertFalse(Chrome.paletteVisible("npm.showActions", npmOff));
        assertFalse(Chrome.paletteVisible("tool.npm", npmOff));
        assertTrue(Chrome.paletteVisible("maven.showActions", npmOff));
        assertTrue(Chrome.paletteVisible("tool.maven", npmOff));
    }

    @Test
    void logCommandsHiddenWhenLogViewerOff() {
        // log off, everything else on → only log.* hidden; the master toggle (view.*) stays visible.
        PaletteGates logOff = new PaletteGates(
                true, true, true, true, true, true, Set.of(), true, true, true, true, true, true, true, false);
        assertFalse(Chrome.paletteVisible("log.toggleFollow", logOff));
        assertFalse(Chrome.paletteVisible("log.setLevelFilter", logOff));
        assertTrue(Chrome.paletteVisible("view.toggleLogViewer", logOff)); // enable toggle is never gated
        assertTrue(Chrome.paletteVisible("log.toggleFollow", allOn()));
    }

    @Test
    void diagramCommandsHiddenWhenDiagramOff() {
        // diagram off (position 5), everything else on → only diagram.* hidden; view.* master toggle stays.
        PaletteGates diagramOff = new PaletteGates(
                true, true, true, true, false, true, Set.of(), true, true, true, true, true, true, true, true);
        assertFalse(Chrome.paletteVisible("diagram.export", diagramOff));
        assertFalse(Chrome.paletteVisible("diagram.setDotCommand", diagramOff));
        assertTrue(Chrome.paletteVisible("view.toggleDiagramSupport", diagramOff)); // enable toggle never gated
        assertTrue(Chrome.paletteVisible("typst.export", diagramOff)); // typst unaffected by diagram gate
        assertTrue(Chrome.paletteVisible("mermaid.export", diagramOff)); // Mermaid unaffected
        assertTrue(Chrome.paletteVisible("diagram.export", allOn()));
    }

    @Test
    void typstCommandsHiddenWhenTypstOff() {
        // typst off (position 5), everything else on → only typst.* hidden; view.* master toggle stays.
        PaletteGates typstOff = new PaletteGates(
                true, true, true, true, true, false, Set.of(), true, true, true, true, true, true, true, true);
        assertFalse(Chrome.paletteVisible("typst.export", typstOff));
        assertFalse(Chrome.paletteVisible("typst.setCommand", typstOff));
        assertTrue(Chrome.paletteVisible("view.toggleTypstSupport", typstOff)); // enable toggle never gated
        assertTrue(Chrome.paletteVisible("diagram.export", typstOff)); // diagram unaffected by typst gate
        assertTrue(Chrome.paletteVisible("typst.export", allOn()));
    }
}
