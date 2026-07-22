package com.editora.ui;

import java.util.Set;

import com.editora.ui.Chrome.PaletteGates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure chrome decisions: effective visibility under the Zen/Expert/Simple overlays + palette command gating. */
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
    void expertStripsOnlyTheSurroundingChromeAndKeepsTheEditorView() {
        // Expert is a "focus mode" (focusMode = zen || expert = true) but is NOT Zen (zen = false).
        boolean focus = true;
        boolean zen = false;
        boolean simple = false;
        // Hidden, exactly like Zen — the surrounding window chrome + whitespace guides:
        assertFalse(Chrome.toolbar(true, focus));
        assertFalse(Chrome.tabBar(true, focus));
        assertFalse(Chrome.breadcrumb(true, focus, simple));
        assertFalse(Chrome.toolStripes(true, focus, simple));
        assertFalse(Chrome.whitespace(true, focus));
        // KEPT — Expert leaves the whole editor view + the status bar (all keyed on the real zen flag):
        assertTrue(Chrome.statusBar(true, zen));
        assertTrue(Chrome.lineNumbers(true, zen, simple));
        assertTrue(Chrome.columnRuler(true, zen));
        assertTrue(Chrome.lineHighlight(true, zen));
        assertTrue(Chrome.minimap(true, zen, simple));
        assertTrue(Chrome.gutter(simple));
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

    // --- palette command gating: feature dimension ---

    /**
     * Gates with every feature either on or off, except that the named features are inverted. Named rather
     * than positional so adding a {@link PaletteGates} component is a single edit here instead of rewriting
     * every literal — the arity churn that made the gate list hard to extend in the first place.
     */
    private static PaletteGates gates(boolean base, boolean simpleMode, String... inverted) {
        return gates(base, simpleMode, base ? Set.of() : Set.of("maven", "npm", "cargo", "go", "gradle"), inverted);
    }

    /** As {@link #gates(boolean, boolean, String...)}, with an explicit set of disabled build tools. */
    private static PaletteGates gates(
            boolean base, boolean simpleMode, Set<String> disabledBuildTools, String... inverted) {
        java.util.Set<String> flip = Set.of(inverted);
        java.util.function.Predicate<String> on = name -> flip.contains(name) != base;
        return new PaletteGates(
                on.test("projects"),
                on.test("git"),
                on.test("github"),
                on.test("notes"),
                on.test("mermaid"),
                on.test("diagram"),
                on.test("typst"),
                disabledBuildTools,
                on.test("lsp"),
                on.test("http"),
                on.test("htmlPreview"),
                on.test("localHistory"),
                on.test("mcp"),
                on.test("plugins"),
                on.test("externalTools"),
                on.test("log"),
                on.test("testRunner"),
                on.test("debug"),
                on.test("ai"),
                on.test("agent"),
                on.test("todo"),
                on.test("spell"),
                on.test("csv"),
                on.test("structured"),
                on.test("markdownLint"),
                on.test("editorConfig"),
                simpleMode);
    }

    /** All features on except {@code disabledFeature} (the empty string = everything on). */
    private static PaletteGates only(String disabledFeature) {
        return disabledFeature.isEmpty() ? gates(true, false) : gates(true, false, disabledFeature);
    }

    private static PaletteGates allOn() {
        return only("");
    }

    private static PaletteGates allOff() {
        return gates(false, false);
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
            "tool.maven",
            "debug.start",
            "tool.debug",
            "ai.explainSelection",
            "agent.newSession",
            "todo.refresh",
            "spell.setLanguage",
            "csv.align",
            "structured.toggleView",
            "markdownLint.fix",
            "editorConfig.openActive"
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
        assertFalse(Chrome.paletteVisible("http.runRequest", off));
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
        PaletteGates gitOff = only("git");
        assertFalse(Chrome.paletteVisible("git.commit", gitOff));
        assertFalse(Chrome.paletteVisible("tool.gitLog", gitOff));
        assertFalse(Chrome.paletteVisible("github.checkoutPr", only("github")));
        assertTrue(Chrome.paletteVisible("lsp.hover", gitOff));
        assertTrue(Chrome.paletteVisible("externalTool.run", gitOff));
        assertTrue(Chrome.paletteVisible("http.runRequest", gitOff));
    }

    @Test
    void buildToolCommandsHiddenOnlyForTheDisabledTool() {
        // Maven disabled, npm still enabled -> only maven.* / tool.maven hidden; npm.* stays visible.
        PaletteGates mavenOff = gates(true, false, Set.of("maven")); // only Maven disabled
        assertFalse(Chrome.paletteVisible("maven.showActions", mavenOff));
        assertFalse(Chrome.paletteVisible("maven.runCustom", mavenOff));
        assertFalse(Chrome.paletteVisible("tool.maven", mavenOff));
        assertTrue(Chrome.paletteVisible("npm.showActions", mavenOff)); // other build tool unaffected
        assertTrue(Chrome.paletteVisible("tool.npm", mavenOff));
        assertTrue(Chrome.paletteVisible("git.commit", mavenOff));
        assertTrue(Chrome.paletteVisible("http.runRequest", mavenOff));
    }

    /**
     * Every feature gate: turning exactly one feature off grays that feature's commands and nothing else,
     * and never the {@code view.*} master toggle that turns it back on.
     */
    @Test
    void eachFeatureGatesOnlyItsOwnCommands() {
        record Case(String feature, String gatedId, String unrelatedId) {}
        for (Case c : new Case[] {
            new Case("projects", "project.open", "git.commit"),
            new Case("git", "git.push", "lsp.hover"),
            new Case("github", "github.createPr", "git.push"),
            new Case("notes", "notes.add", "bookmarks.toggle"),
            new Case("mermaid", "mermaid.export", "diagram.export"),
            new Case("diagram", "diagram.export", "mermaid.export"),
            new Case("typst", "typst.export", "diagram.export"),
            new Case("lsp", "lsp.gotoDefinition", "git.push"),
            new Case("http", "http.runRequest", "git.push"),
            new Case("htmlPreview", "htmlPreview.open", "git.push"),
            new Case("localHistory", "history.putLabel", "git.push"),
            new Case("mcp", "mcp.copyEndpoint", "git.push"),
            new Case("plugins", "plugins.browse", "git.push"),
            new Case("externalTools", "externalTool.run", "git.push"),
            new Case("log", "log.toggleFollow", "git.push"),
            new Case("testRunner", "test.run", "run.rerun"),
            new Case("debug", "debug.start", "run.rerun"),
            new Case("ai", "ai.explainSelection", "agent.newSession"),
            new Case("agent", "agent.newSession", "ai.explainSelection"),
            new Case("todo", "todo.refresh", "bookmarks.toggle"),
            new Case("spell", "spell.setLanguage", "edit.cut"),
            new Case("csv", "csv.align", "markdown.bold"),
            new Case("structured", "structured.toggleView", "csv.align"),
            new Case("markdownLint", "markdownLint.fix", "markdown.bold"),
            new Case("editorConfig", "editorConfig.openActive", "file.save"),
        }) {
            PaletteGates g = only(c.feature());
            assertFalse(Chrome.paletteVisible(c.gatedId(), g), c.gatedId() + " should be gated by " + c.feature());
            assertTrue(
                    Chrome.paletteVisible(c.unrelatedId(), g),
                    c.unrelatedId() + " should be unaffected by the " + c.feature() + " gate");
            assertTrue(Chrome.paletteVisible(c.gatedId(), allOn()), c.gatedId() + " should be visible when on");
        }
    }

    @Test
    void masterEnableTogglesAreNeverGated() {
        PaletteGates off = allOff();
        for (String id : new String[] {
            "view.toggleLogViewer",
            "view.toggleDiagramSupport",
            "view.toggleTypstSupport",
            "view.toggleTestRunner",
            "view.toggleProjects",
            "view.toggleLsp",
            "view.toggleGithub",
            "view.toggleMcp",
            "view.togglePlugins",
            "view.toggleTodoHighlight",
            "view.toggleSpellCheck",
            "view.toggleCsvGrid",
            "view.toggleEditorConfig"
        }) {
            assertTrue(Chrome.paletteVisible(id, off), id + " must stay reachable so the feature can be re-enabled");
        }
    }

    // --- palette command gating: context dimension ---

    private static Chrome.PaletteContext ctx() {
        return Chrome.PaletteContext.all();
    }

    private static Chrome.PaletteContext noBuffer() {
        return new Chrome.PaletteContext(false, false, false, false, false, false, false, false, false);
    }

    @Test
    void everythingEnabledWhenEverythingIsAvailable() {
        for (String id : new String[] {
            "markdown.bold",
            "csv.align",
            "http.runRequest",
            "typst.export",
            "preview.exportPdf",
            "git.push",
            "debug.stepOver",
            "edit.cut",
            "nav.lineUp",
            "file.save"
        }) {
            assertTrue(Chrome.contextEnabled(id, ctx()), id + " should be enabled with full context");
        }
    }

    @Test
    void fileTypeCommandsNeedABufferOfThatType() {
        Chrome.PaletteContext none = noBuffer();
        assertFalse(Chrome.contextEnabled("markdown.bold", none));
        assertFalse(Chrome.contextEnabled("csv.align", none));
        assertFalse(Chrome.contextEnabled("http.runRequest", none));
        assertFalse(Chrome.contextEnabled("typst.export", none));
        assertFalse(Chrome.contextEnabled("preview.exportPdf", none));
        // A Markdown buffer enables the Markdown family but not the CSV/HTTP ones.
        Chrome.PaletteContext markdown =
                new Chrome.PaletteContext(true, false, true, false, false, false, true, false, false);
        assertTrue(Chrome.contextEnabled("markdown.bold", markdown));
        assertTrue(Chrome.contextEnabled("preview.exportPdf", markdown));
        assertFalse(Chrome.contextEnabled("csv.align", markdown));
        assertFalse(Chrome.contextEnabled("http.runRequest", markdown));
    }

    @Test
    void markdownEditingCommandsAlsoServeTypstBuffers() {
        // The Markdown editing commands dispatch on isTypst() too, so a Typst buffer must not gray them.
        Chrome.PaletteContext typst =
                new Chrome.PaletteContext(true, false, true, false, false, true, true, false, false);
        assertTrue(Chrome.contextEnabled("markdown.bold", typst));
        assertTrue(Chrome.contextEnabled("typst.export", typst));
    }

    @Test
    void gitCommandsNeedARepoExceptClone() {
        Chrome.PaletteContext none = noBuffer();
        assertFalse(Chrome.contextEnabled("git.push", none));
        assertFalse(Chrome.contextEnabled("git.commit", none));
        assertFalse(Chrome.contextEnabled("tool.commit", none));
        assertFalse(Chrome.contextEnabled("tool.gitLog", none));
        // Clone is exactly what you run when there is no repo yet.
        assertTrue(Chrome.contextEnabled("git.clone", none));
    }

    @Test
    void debugSessionCommandsNeedALiveOrSuspendedSession() {
        Chrome.PaletteContext noSession =
                new Chrome.PaletteContext(true, true, true, true, true, true, true, false, false);
        Chrome.PaletteContext running =
                new Chrome.PaletteContext(true, true, true, true, true, true, true, true, false);
        Chrome.PaletteContext suspended =
                new Chrome.PaletteContext(true, true, true, true, true, true, true, true, true);

        // Starting/attaching/toggling a breakpoint is always available.
        assertTrue(Chrome.contextEnabled("debug.start", noSession));
        assertTrue(Chrome.contextEnabled("debug.attach", noSession));
        assertTrue(Chrome.contextEnabled("debug.toggleBreakpoint", noSession));

        // Session controls need a session; stepping/inspection needs it suspended.
        assertFalse(Chrome.contextEnabled("debug.stop", noSession));
        assertTrue(Chrome.contextEnabled("debug.stop", running));
        assertFalse(Chrome.contextEnabled("debug.stepOver", running));
        assertTrue(Chrome.contextEnabled("debug.stepOver", suspended));
        assertFalse(Chrome.contextEnabled("debug.evaluate", running));
        assertTrue(Chrome.contextEnabled("debug.evaluate", suspended));
    }

    @Test
    void bufferFamiliesNeedAnEditorBuffer() {
        Chrome.PaletteContext none = noBuffer();
        assertFalse(Chrome.contextEnabled("edit.cut", none));
        assertFalse(Chrome.contextEnabled("nav.lineUp", none));
        // Commands that make sense with no buffer open stay enabled.
        assertTrue(Chrome.contextEnabled("file.new", none));
        assertTrue(Chrome.contextEnabled("file.open", none));
        assertTrue(Chrome.contextEnabled("view.welcome", none));
        assertTrue(Chrome.contextEnabled("palette.show", none));
        assertTrue(Chrome.contextEnabled("window.new", none));
    }

    // --- why a command is grayed (the tooltip) ---

    @Test
    void anEnabledCommandHasNoReason() {
        assertNull(Chrome.disabledReason("git.push", allOn(), ctx()));
        assertNull(Chrome.disabledReason("file.save", allOff(), ctx()));
    }

    @Test
    void aSwitchedOffFeatureNamesTheCommandThatTurnsItBackOn() {
        Chrome.DisabledReason r = Chrome.disabledReason("git.push", only("git"), ctx());
        assertEquals("palette.disabled.featureOff", r.messageKey());
        assertEquals("view.toggleGit", r.commandArg());

        Chrome.DisabledReason debug = Chrome.disabledReason("debug.start", only("debug"), ctx());
        assertEquals("view.toggleDebug", debug.commandArg());
    }

    /**
     * While Simple UI mode is forcing a feature off, its own toggle would not help — running "Toggle Git
     * Support" changes a setting that Simple mode overrides anyway. The reason must name Simple mode.
     */
    @Test
    void simpleModeIsNamedInsteadOfADeadEndFeatureToggle() {
        PaletteGates simple = gates(true, true, "git", "lsp", "debug", "externalTools");
        for (String id : new String[] {"git.push", "lsp.gotoDefinition", "debug.start", "externalTool.run"}) {
            Chrome.DisabledReason r = Chrome.disabledReason(id, simple, ctx());
            assertEquals("palette.disabled.simpleMode", r.messageKey(), id + " is suppressed by Simple mode");
            assertEquals("view.toggleSimpleMode", r.commandArg(), id + " must point at the Simple-mode toggle");
        }
        // A feature Simple mode does NOT suppress still points at its own toggle, even in Simple mode.
        Chrome.DisabledReason notes = Chrome.disabledReason("notes.add", gates(true, true, "notes"), ctx());
        assertEquals("palette.disabled.featureOff", notes.messageKey());
        assertEquals("view.toggleNotes", notes.commandArg());
    }

    @Test
    void everyFeatureRuleCanExplainItselfWithARealToggleCommand() {
        // A rule that grays commands without naming a way back would leave the user stuck.
        for (String id : new String[] {
            "project.open",
            "git.push",
            "github.createPr",
            "notes.add",
            "mermaid.export",
            "diagram.export",
            "typst.export",
            "lsp.gotoDefinition",
            "http.runRequest",
            "htmlPreview.open",
            "history.putLabel",
            "mcp.copyEndpoint",
            "plugins.browse",
            "externalTool.run",
            "log.toggleFollow",
            "test.run",
            "debug.start",
            "ai.explainSelection",
            "agent.newSession",
            "todo.refresh",
            "spell.setLanguage",
            "csv.align",
            "structured.toggleView",
            "markdownLint.fix",
            "editorConfig.openActive"
        }) {
            Chrome.DisabledReason r = Chrome.disabledReason(id, allOff(), ctx());
            assertNotNull(r, id + " should have a reason when everything is off");
            assertNotNull(r.commandArg(), id + " should name a command that re-enables it");
            assertTrue(
                    r.commandArg().startsWith("view.toggle"),
                    id + " should point at a never-gated view.toggle* command, got " + r.commandArg());
        }
    }

    @Test
    void contextReasonsAreSpecificAboutWhatIsMissing() {
        Chrome.PaletteContext none = noBuffer();
        assertEquals(
                "palette.disabled.needsMarkdown",
                Chrome.disabledReason("markdown.bold", allOn(), none).messageKey());
        assertEquals(
                "palette.disabled.needsCsv",
                Chrome.disabledReason("csv.align", allOn(), none).messageKey());
        assertEquals(
                "palette.disabled.needsRepo",
                Chrome.disabledReason("git.push", allOn(), none).messageKey());
        assertEquals(
                "palette.disabled.needsBuffer",
                Chrome.disabledReason("edit.cut", allOn(), none).messageKey());
        assertEquals(
                "palette.disabled.needsPreview",
                Chrome.disabledReason("preview.exportPdf", allOn(), none).messageKey());

        Chrome.PaletteContext running =
                new Chrome.PaletteContext(true, true, true, true, true, true, true, true, false);
        assertEquals(
                "palette.disabled.needsSuspended",
                Chrome.disabledReason("debug.stepOver", allOn(), running).messageKey());
        Chrome.PaletteContext noSession =
                new Chrome.PaletteContext(true, true, true, true, true, true, true, false, false);
        assertEquals(
                "palette.disabled.needsDebugSession",
                Chrome.disabledReason("debug.stop", allOn(), noSession).messageKey());
    }

    /**
     * A switched-off feature outranks a missing context: telling someone to open a Markdown file when the
     * Markdown-lint feature itself is off would send them nowhere.
     */
    @Test
    void theFeatureReasonWinsOverTheContextReason() {
        Chrome.DisabledReason r = Chrome.disabledReason("csv.align", only("csv"), noBuffer());
        assertEquals("palette.disabled.featureOff", r.messageKey());
        assertEquals("view.toggleCsvGrid", r.commandArg());
    }

    @Test
    void paletteEnabledCombinesBothDimensions() {
        // Feature on + context present -> enabled.
        assertTrue(Chrome.paletteEnabled("git.push", allOn(), ctx()));
        // Feature off, context fine -> disabled.
        assertFalse(Chrome.paletteEnabled("git.push", only("git"), ctx()));
        // Feature on, context missing -> disabled.
        assertFalse(Chrome.paletteEnabled("git.push", allOn(), noBuffer()));
        // Neither -> disabled.
        assertFalse(Chrome.paletteEnabled("git.push", only("git"), noBuffer()));
    }
}
