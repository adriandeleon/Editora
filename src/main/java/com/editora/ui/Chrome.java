package com.editora.ui;

/**
 * Pure, unit-tested decision logic for window chrome — extracted from {@link MainController} so it can be
 * tested without the JavaFX toolkit. Two concerns:
 *
 * <ul>
 *   <li><b>Effective visibility</b> of the chrome + editor view options under the two per-window overlays,
 *       <b>Zen</b> and <b>Simple UI</b> mode. Both hide UI <em>without mutating the saved prefs</em>, so the
 *       effective value is "saved pref AND not overridden". Centralizing the truth table here keeps the Zen
 *       and Simple semantics consistent and guards against regressions (Zen used to desync — see the Zen
 *       per-window fix).
 *   <li><b>Command-palette availability</b>: which command ids are <em>actionable</em>, along two independent
 *       dimensions — the {@link PaletteGates} (a feature is switched off) and the {@link PaletteContext}
 *       (the feature is on but there is nothing to act on right now). A command failing either is still
 *       listed, but grayed out and inert; {@link #disabledReason} says why, so the row can explain itself.
 * </ul>
 *
 * <p>No JavaFX dependency — {@link MainController} evaluates the live booleans and delegates the decisions.
 */
final class Chrome {

    private Chrome() {}

    // --- Effective chrome visibility. The "focus modes" (Zen and Expert) hide all of these; Simple
    //     additionally hides the breadcrumb + tool stripes (the toolbar/status/tab bar stay under Simple).
    //     {@code focusMode} = Zen OR Expert. The exceptions are {@link #statusBar} and {@link #lineNumbers},
    //     which Zen hides but Expert deliberately KEEPS, so those two stay keyed on the real {@code zen}. ---

    static boolean toolbar(boolean showToolbar, boolean focusMode) {
        return showToolbar && !focusMode;
    }

    /** The status bar is hidden by Zen but KEPT by Expert, so it stays keyed on the real {@code zen} flag. */
    static boolean statusBar(boolean showStatusBar, boolean zen) {
        return showStatusBar && !zen;
    }

    static boolean tabBar(boolean showTabBar, boolean focusMode) {
        return showTabBar && !focusMode;
    }

    static boolean breadcrumb(boolean showBreadcrumb, boolean focusMode, boolean simple) {
        return showBreadcrumb && !focusMode && !simple;
    }

    static boolean toolStripes(boolean showToolStripe, boolean focusMode, boolean simple) {
        return showToolStripe && !focusMode && !simple;
    }

    // --- Effective editor view options. Expert keeps the whole editor view (line numbers, ruler,
    //     current-line highlight, minimap), so only Zen (not focus) hides those — Expert strips just the
    //     surrounding window chrome. Whitespace guides are the one editor decoration a focus mode still
    //     hides. Simple additionally hides line numbers + minimap and removes the whole gutter. ---

    /** The column ruler is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag. */
    static boolean columnRuler(boolean show, boolean zen) {
        return show && !zen;
    }

    /** Current-line highlight is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag. */
    static boolean lineHighlight(boolean on, boolean zen) {
        return on && !zen;
    }

    static boolean whitespace(boolean show, boolean focusMode) {
        return show && !focusMode;
    }

    /** Line numbers are hidden by Zen but KEPT by Expert, so they stay keyed on the real {@code zen} flag. */
    static boolean lineNumbers(boolean show, boolean zen, boolean simple) {
        return show && !zen && !simple;
    }

    /** The minimap is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag (Simple also
     *  hides it). */
    static boolean minimap(boolean show, boolean zen, boolean simple) {
        return show && !zen && !simple;
    }

    /** The whole gutter strip (line numbers, fold chevrons, bookmark/note/run/breakpoint/git slots). */
    static boolean gutter(boolean simple) {
        return !simple;
    }

    // --- Command-palette visibility ---

    /**
     * The optional features whose commands are grayed out in the palette while the feature is off. Each
     * flag is the feature's <em>effective</em> enabled state (already folding in Simple-UI mode where
     * applicable), as evaluated by {@code MainController}.
     *
     * <p>Adding a feature is two edits: a component here and a {@link #FEATURE_RULES} row — the rules are a
     * table rather than a chain of ifs precisely so a new feature can't be half-added (a component with no
     * rule would be unused, which the compiler flags via the record accessor never being called).
     */
    record PaletteGates(
            boolean projects,
            boolean git,
            boolean github,
            boolean notes,
            boolean mermaid,
            boolean diagram,
            boolean typst,
            java.util.Set<String> disabledBuildToolIds,
            boolean lsp,
            boolean http,
            boolean htmlPreview,
            boolean localHistory,
            boolean mcp,
            boolean plugins,
            boolean externalTools,
            boolean log,
            boolean testRunner,
            boolean debug,
            boolean ai,
            boolean agent,
            boolean todo,
            boolean spell,
            boolean csv,
            boolean structured,
            boolean markdownLint,
            boolean editorConfig,
            /** Simple UI mode, which forces several features off on top of their own setting. Not a gate of
             *  its own — it only redirects the tooltip to the Simple-mode toggle, which is the switch that
             *  would actually help. */
            boolean simpleMode) {}

    /**
     * One feature's palette rule: while {@code enabled} is false, every command whose id starts with
     * {@code prefix} — plus any {@code extraIds} (tool-window toggles, which live under {@code tool.}) — is
     * grayed out.
     *
     * @param toggleId the command that switches this feature back on, named in the grayed row's tooltip so
     *     the tooltip is actionable rather than merely explanatory
     * @param simpleSuppressed whether Simple UI mode forces this feature off regardless of its own setting —
     *     if so, while Simple mode is on the tooltip must point at Simple mode instead of {@code toggleId},
     *     which would be a dead end
     */
    private record FeatureRule(
            java.util.function.Predicate<PaletteGates> enabled,
            String prefix,
            java.util.Set<String> extraIds,
            String toggleId,
            boolean simpleSuppressed) {

        boolean covers(String id) {
            return id.startsWith(prefix) || extraIds.contains(id);
        }
    }

    private static FeatureRule rule(
            java.util.function.Predicate<PaletteGates> enabled, String prefix, String toggleId, String... extraIds) {
        return new FeatureRule(enabled, prefix, java.util.Set.of(extraIds), toggleId, false);
    }

    /** As {@link #rule}, for a feature that Simple UI mode forces off on top of its own setting. */
    private static FeatureRule simpleRule(
            java.util.function.Predicate<PaletteGates> enabled, String prefix, String toggleId, String... extraIds) {
        return new FeatureRule(enabled, prefix, java.util.Set.of(extraIds), toggleId, true);
    }

    /**
     * Every optional feature's palette rule. Master enable toggles deliberately live under the never-gated
     * {@code view.}/{@code appearance.}/{@code debug.toggle*} prefixes, so turning a feature off can never
     * gray out the command that turns it back on.
     */
    private static final java.util.List<FeatureRule> FEATURE_RULES = java.util.List.of(
            rule(PaletteGates::projects, "project.", "view.toggleProjects", "tool.project"),
            simpleRule(PaletteGates::git, "git.", "view.toggleGit", "tool.commit", "tool.gitLog"),
            simpleRule(PaletteGates::github, "github.", "view.toggleGithub", "tool.github"),
            rule(PaletteGates::notes, "notes.", "view.toggleNotes", "tool.notes"),
            rule(PaletteGates::mermaid, "mermaid.", "view.toggleMermaid"),
            rule(PaletteGates::diagram, "diagram.", "view.toggleDiagramSupport"),
            rule(PaletteGates::typst, "typst.", "view.toggleTypstSupport"),
            simpleRule(PaletteGates::lsp, "lsp.", "view.toggleLsp", "tool.problems"),
            simpleRule(PaletteGates::http, "http.", "view.toggleHttpClient"),
            rule(PaletteGates::htmlPreview, "htmlPreview.", "view.toggleHtmlPreview"),
            simpleRule(PaletteGates::localHistory, "history.", "view.toggleLocalHistory", "tool.fileHistory"),
            simpleRule(PaletteGates::mcp, "mcp.", "view.toggleMcp"),
            simpleRule(PaletteGates::plugins, "plugins.", "view.togglePlugins"),
            // External Tools has no setting of its own — Simple UI mode is the only thing that turns it off.
            simpleRule(PaletteGates::externalTools, "externalTool.", "view.toggleSimpleMode", "tool.externalTools"),
            simpleRule(PaletteGates::log, "log.", "view.toggleLogViewer"),
            simpleRule(PaletteGates::testRunner, "test.", "view.toggleTestRunner", "tool.testResults"),
            simpleRule(PaletteGates::debug, "debug.", "view.toggleDebug", "tool.debug"),
            simpleRule(PaletteGates::ai, "ai.", "view.toggleAi"),
            simpleRule(PaletteGates::agent, "agent.", "view.toggleAgent", "tool.agent"),
            rule(PaletteGates::todo, "todo.", "view.toggleTodoHighlight", "tool.todo"),
            rule(PaletteGates::spell, "spell.", "view.toggleSpellCheck"),
            rule(PaletteGates::csv, "csv.", "view.toggleCsvGrid"),
            rule(PaletteGates::structured, "structured.", "view.toggleStructuredPreview"),
            rule(PaletteGates::markdownLint, "markdownLint.", "view.toggleMarkdownLint"),
            rule(PaletteGates::editorConfig, "editorConfig.", "view.toggleEditorConfig"));

    /**
     * Why a command is grayed out, as an i18n key plus an optional argument. Kept as keys rather than
     * resolved text so {@link Chrome} stays a pure decision class with no message-catalog dependency —
     * {@code CommandPalette} does the {@code tr()}.
     *
     * @param messageKey the {@code palette.disabled.*} catalog key
     * @param commandArg a command id whose <em>localized title</em> fills the message's {@code {0}} (the
     *     toggle that would re-enable the feature), or null for a message with no argument
     */
    record DisabledReason(String messageKey, String commandArg) {}

    /**
     * Which feature gate — if any — currently covers {@code id}, along with why. Null when no gate applies.
     * Both {@link #paletteVisible} and the tooltip read this, so a rule can never gray a command without
     * also being able to explain it.
     */
    private static DisabledReason featureReason(String id, PaletteGates g) {
        for (FeatureRule r : FEATURE_RULES) {
            if (!r.enabled().test(g) && r.covers(id)) {
                // Pointing at the feature's own toggle would be a dead end while Simple UI mode is forcing
                // it off, so name Simple mode instead.
                return r.simpleSuppressed() && g.simpleMode()
                        ? new DisabledReason("palette.disabled.simpleMode", "view.toggleSimpleMode")
                        : new DisabledReason("palette.disabled.featureOff", r.toggleId());
            }
        }
        for (String buildToolId : g.disabledBuildToolIds()) {
            if (id.startsWith(buildToolId + ".") || id.equals("tool." + buildToolId)) {
                return new DisabledReason("palette.disabled.buildToolOff", null);
            }
        }
        return null;
    }

    /**
     * Whether command {@code id} belongs to a feature that is currently switched off. Split out of
     * {@link #paletteEnabled} so the feature dimension can be tested independently of context.
     */
    static boolean paletteVisible(String id, PaletteGates g) {
        return featureReason(id, g) == null;
    }

    // --- Command-palette context (a command whose feature is ON but which can't act right now) ---

    /**
     * What the window can act on <em>right now</em>. Distinct from {@link PaletteGates}, which is about a
     * feature being switched off: these commands are fully enabled features that simply have nothing to
     * operate on (a Git command outside a repo, a Markdown command in a Java file, a step command with no
     * suspended debug session) and would no-op with a status message if run.
     *
     * <p>Deliberately conservative — graying a command that would in fact have worked is worse than leaving
     * one lit, so only families where <em>every</em> member needs the same context are listed in
     * {@link #contextEnabled}.
     */
    record PaletteContext(
            boolean hasBuffer,
            boolean inRepo,
            boolean markdownLike,
            boolean csvFile,
            boolean httpFile,
            boolean typstFile,
            boolean hasPreview,
            boolean debugActive,
            boolean debugSuspended) {

        /** Everything available — the neutral value for tests and for callers with no window context. */
        static PaletteContext all() {
            return new PaletteContext(true, true, true, true, true, true, true, true, true);
        }
    }

    /** Debug commands that drive a live session (as opposed to starting/attaching/setting breakpoints). */
    private static final java.util.Set<String> DEBUG_NEEDS_SESSION =
            java.util.Set.of("debug.stop", "debug.restart", "debug.pause");

    /** Debug commands that only mean anything while a thread is suspended at a stop. */
    private static final java.util.Set<String> DEBUG_NEEDS_SUSPENDED = java.util.Set.of(
            "debug.continue",
            "debug.stepOver",
            "debug.stepInto",
            "debug.stepOut",
            "debug.runToCursor",
            "debug.jumpToLine",
            "debug.evaluate",
            "debug.addWatch",
            "debug.setValue");

    /**
     * Why {@code id} has nothing to act on, or null when it does. See {@link PaletteContext} for why this
     * list is short and conservative. {@link #contextEnabled} is derived from this, so a command can never
     * be grayed for a reason the tooltip can't state.
     */
    private static DisabledReason contextReason(String id, PaletteContext c) {
        // File-type families: every member edits or exports the active buffer of that type.
        if (id.startsWith("markdown.")) {
            // The Markdown editing commands serve Typst buffers too.
            return c.markdownLike() ? null : new DisabledReason("palette.disabled.needsMarkdown", null);
        }
        if (id.startsWith("csv.")) {
            return c.csvFile() ? null : new DisabledReason("palette.disabled.needsCsv", null);
        }
        if (id.startsWith("http.")) {
            return c.httpFile() ? null : new DisabledReason("palette.disabled.needsHttp", null);
        }
        if (id.startsWith("typst.")) {
            return c.typstFile() ? null : new DisabledReason("palette.disabled.needsTypst", null);
        }
        if (id.startsWith("preview.")) {
            return c.hasPreview() ? null : new DisabledReason("palette.disabled.needsPreview", null);
        }
        // Git acts on the repo the active file lives in — except cloning, which is what you run when you
        // have no repo yet.
        if (id.startsWith("git.") || id.equals("tool.commit") || id.equals("tool.gitLog")) {
            return c.inRepo() || id.equals("git.clone") ? null : new DisabledReason("palette.disabled.needsRepo", null);
        }
        if (DEBUG_NEEDS_SUSPENDED.contains(id)) {
            return c.debugSuspended() ? null : new DisabledReason("palette.disabled.needsSuspended", null);
        }
        if (DEBUG_NEEDS_SESSION.contains(id)) {
            return c.debugActive() ? null : new DisabledReason("palette.disabled.needsDebugSession", null);
        }
        // Whole-family buffer requirements: these do nothing on the Welcome page or an image/hex/PDF tab.
        if (id.startsWith("edit.") || id.startsWith("nav.") || id.startsWith("markwhen.")) {
            return c.hasBuffer() ? null : new DisabledReason("palette.disabled.needsBuffer", null);
        }
        return null;
    }

    /**
     * Whether command {@code id} can act on the current context, assuming its feature is enabled. See
     * {@link PaletteContext} for why this list is short and conservative.
     */
    static boolean contextEnabled(String id, PaletteContext c) {
        return contextReason(id, c) == null;
    }

    /**
     * The command palette's enabled predicate: a command is actionable when its feature is on <em>and</em>
     * it has something to act on. A command failing either test is still listed — grayed out and inert —
     * so the user sees that it exists and what its keybinding is (#532).
     */
    static boolean paletteEnabled(String id, PaletteGates g, PaletteContext c) {
        return paletteVisible(id, g) && contextEnabled(id, c);
    }

    /**
     * Why the palette is graying {@code id}, or null when it is actionable. The feature gate is reported in
     * preference to the context: a switched-off feature is the more fundamental reason, and telling someone
     * to open a Markdown file when the Markdown-lint feature is off would send them nowhere.
     */
    static DisabledReason disabledReason(String id, PaletteGates g, PaletteContext c) {
        DisabledReason feature = featureReason(id, g);
        return feature != null ? feature : contextReason(id, c);
    }
}
