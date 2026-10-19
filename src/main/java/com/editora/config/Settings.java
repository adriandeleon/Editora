package com.editora.config;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/** User preferences, (de)serialized to {@code settings.json}. Session/state lives in {@link WorkspaceState}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    /**
     * Restores every preference on {@code live} to its default, in place — {@code live} is the one instance
     * every window holds, so it must be mutated, not replaced. Backs Settings → Advanced → "Reset to
     * Defaults". Deliberately preserves the two things that page doesn't own: {@link #getFontZoom() fontZoom}
     * (the editor's Ctrl-+/- text zoom) and {@link #getKeybindings() keybindings} (the Keymaps page's own
     * "Reset all"). The reset does put the keymap back to the default, and key-binding overrides belong to the
     * keymap they were made in — so the preserved overrides are parked under the old keymap, not applied to
     * the default one.
     *
     * <p>Driven off the serialized form rather than a hand-written list of setters. The list version had gone
     * stale: it restored <b>23 of 181</b> fields, so "Reset to Defaults" silently left ~87% of preferences
     * alone — every setting added since it was written (all of AI, LSP, Debug, Mermaid, TODO, the previews,
     * the build tools, Simple mode, the keymap, the UI language…) was simply missed. Copying the properties
     * Jackson already persists means a new field is covered the day it is added.
     */
    public static void resetToDefaults(Settings live) {
        double fontZoom = live.getFontZoom();
        String keymap = live.getKeymap();
        Map<String, String> keybindings = live.getKeybindings();
        Map<String, String> keybindingsMac = live.getKeybindingsMac();
        Map<String, Map<String, String>> parked = live.getKeymapKeybindings();
        Map<String, Map<String, String>> parkedMac = live.getKeymapKeybindingsMac();
        ObjectMapper mapper = new ObjectMapper();
        try {
            mapper.readerForUpdating(live).readValue(mapper.writeValueAsBytes(new Settings()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not reset settings to their defaults", e);
        }
        live.setFontZoom(fontZoom);
        String defaultKeymap = live.getKeymap();
        live.setKeymap(keymap);
        live.setKeybindings(keybindings);
        live.setKeybindingsMac(keybindingsMac);
        live.setKeymapKeybindings(parked);
        live.setKeymapKeybindingsMac(parkedMac);
        live.switchKeymap(defaultKeymap);
    }

    /** Current on-disk schema version of {@code settings.json}; bump when the format changes (+ a migration). */
    public static final int SCHEMA_VERSION = 139;

    private int schemaVersion = SCHEMA_VERSION;

    /** Default plugin-registry index URL (a curated {@code index.json} on GitHub); user-overridable. */
    public static final String DEFAULT_PLUGIN_REGISTRY =
            "https://raw.githubusercontent.com/adriandeleon/editora-plugins/main/index.json";

    /** Default Maven archetype catalog (Maven Central's); user-overridable. */
    public static final String DEFAULT_MAVEN_ARCHETYPE_CATALOG =
            "https://repo.maven.apache.org/maven2/archetype-catalog.xml";

    /**
     * Bounds applied by the numeric setters, so a hand-edited {@code settings.json} cannot load a value the
     * editor's arithmetic cannot use ({@code "tabSize": 0} divided by zero in the indent helpers). Each range
     * is the widest one any UI path offers: the Settings spinners and the palette prompts.
     */
    public static final int MIN_FONT_SIZE = 6;

    public static final int MAX_FONT_SIZE = 72;
    public static final double MIN_FONT_ZOOM = 0.5;
    public static final double MAX_FONT_ZOOM = 3.0;
    public static final int MIN_TAB_SIZE = 1;
    public static final int MAX_TAB_SIZE = 16;

    // One range per numeric preference, shared by its Settings spinner and its palette prompt: a control with
    // a narrower range than the command shows a value that is not the one in force.
    public static final int MIN_FILL_COLUMN = 1;
    public static final int MAX_FILL_COLUMN = 1000;
    public static final int MIN_AUTO_SAVE_DELAY_SECONDS = 1;
    public static final int MAX_AUTO_SAVE_DELAY_SECONDS = 3600;
    public static final int MAX_HISTORY_PER_FILE = 1000;
    public static final int MAX_HISTORY_AGE_DAYS = 3650;
    public static final int MAX_HISTORY_TOTAL_MB = 10_000;
    public static final int MAX_LARGE_FILE_THRESHOLD = 10_000_000;

    /** Author name used by file templates' {@code ${author}}; blank = the OS user (see getter). */
    private String authorName = "";

    private String fontFamily = "JetBrains Mono";
    private int fontSize = 14;
    /** Text zoom factor applied on top of {@link #fontSize} (1.0 = 100%). A quick, persisted zoom that
     *  is intentionally NOT shown in the Settings window; effective size = round(fontSize * fontZoom). */
    private double fontZoom = 1.0;

    /** App (AtlantaFX) theme. First-run default is the "Caret & Ink" flagship — see {@code Themes.DEFAULT}
     *  (kept as a literal here so config stays free of a {@code ui} dependency). An existing install keeps
     *  whatever is already in {@code settings.json}: Jackson persists every modeled field, so only a fresh
     *  config dir picks up a changed default. */
    private String theme = "Editora Light";
    /** Editor color theme (syntax + surface). Follows {@link #theme} until the user picks one. */
    private String editorTheme = "Editora Light";
    /** True once the user explicitly picks an editor theme; stops it auto-following the app theme. */
    private boolean editorThemeUserSet;

    private int tabSize = 4;
    /** Line count above which a file enters the intermediate "large source file" tier: the minimap and
     *  LSP are auto-disabled (highlighting + editing stay) so a very long single file (e.g. a 13k-line
     *  source) stays responsive. {@code 0} disables the tier. Distinct from the 5 MB hard large-file mode. */
    private int largeFileThreshold = 10_000;
    /**
     * Global indent unit for Tab/Enter: {@code "detect"} (per-file auto-detection, default), {@code "space"}
     * (force {@link #tabSize} spaces), or {@code "tab"}. A file's {@code .editorconfig} {@code indent_style}
     * still wins over this when EditorConfig is enabled.
     */
    private String indentStyle = "detect";
    /** Target column for the Emacs-style fill commands (M-q / fill-region); Emacs default. */
    private int fillColumn = com.editora.editops.Filler.DEFAULT_FILL_COLUMN;

    private String keymap = "emacs";
    /** UI language code (e.g. {@code "es"}); empty = auto (system language if bundled, else English). */
    private String uiLanguage = "";

    private boolean showColumnRuler = true;
    /** The ruler columns (see {@code editor/RulerColumns}); {@code .editorconfig max_line_length} replaces the first. */
    private java.util.List<Integer> rulers = new java.util.ArrayList<>(java.util.List.of(80));
    /** Ruler columns per language id, used instead of {@link #rulers} for that language; an empty list = none. */
    private java.util.Map<String, java.util.List<Integer>> languageRulers = new java.util.LinkedHashMap<>();
    /** Faint vertical lines at each indentation level of the text. */
    private boolean showIndentGuides = true;

    private boolean highlightCurrentLine = true;
    private boolean showLineNumbers = true;
    private boolean showMinimap = true;
    private boolean showWhitespace = false;
    /** Soft-wrap long lines in code (every language but Markdown and plain text). Off by default. */
    private boolean wordWrap = false;
    /** Soft-wrap long lines in Markdown and plain text, where a line is a paragraph. On by default. */
    private boolean wordWrapProse = true;
    /** Save-as-administrator: offer to write a non-writable file via the OS auth agent (pkexec, Linux). Off. */
    private boolean adminSave = false;
    /** Personal Notes feature: on by default — the tool window, commands, gutter/highlight, and the editor
     *  "Add Personal Note" menu items. */
    private boolean notesSupport = true;
    /** Personal Notes gutter markers + in-editor highlight; on by default (only effective when
     *  {@link #notesSupport} is on). */
    private boolean showNoteIndicators = true;
    /** Markdown preview color theme, independent of the app/editor theme: "" = follow app (until first
     *  toggled), then "light" or "dark". Toggled via the preview's floating sun/moon control. */
    private String markdownPreviewTheme = "";
    /** Show the floating format bar on a text selection in Markdown buffers. */
    private boolean markdownFormatBar = true;
    /** When the Markdown preview loads remote ({@code http}/{@code https}) images: "always", "ask" (a bar in
     *  the preview offers to load them for this document) or "never" (alt text, nothing fetched). It replaced
     *  the boolean {@code markdownRemoteImages} at schema 121; {@link #isMarkdownRemoteImages()} is a view of it. */
    private String markdownRemoteImagePolicy = "always";
    /** Recognise wiki links ({@code [[note]]}, {@code [[note|label]]}, {@code [[note#heading]]}) in Markdown. */
    private boolean markdownWikiLinks = true;
    /** On save, move to the trash an image pasted in this session that the document no longer references. */
    private boolean markdownTrashUnusedPastedImages = true;
    /** Folder, relative to the document, that pasted and dropped images are written to. */
    private String markdownAssetsFolder = "assets";
    /** Shallowest and deepest heading level listed by "Markdown: Insert/Update Table of Contents" (1–6). */
    private int markdownTocMinLevel = 1;

    private int markdownTocMaxLevel = 6;
    /** View a Markdown file opens in when it has no remembered mode: "editor", "split" or "preview". */
    private String markdownDefaultViewMode = "editor";
    /** Base font size of the Markdown preview in px; 0 = the built-in size. */
    private int markdownPreviewFontSize = 0;
    /** Maximum width of the preview's text column in px; 0 = the built-in width. */
    private int markdownPreviewWidth = 0;
    /** Keep the editor and the preview aligned in Split. */
    private boolean markdownScrollSync = true;
    /** Hide the front-matter block in the preview. */
    private boolean markdownHideFrontMatter = false;
    /** Type curly quotes, dashes and an ellipsis in Markdown prose. */
    private boolean markdownSmartTypography = false;
    /** Leave the document title out of a generated table of contents. */
    private boolean markdownTocSkipTitle = false;
    /** Generate the table of contents as a numbered list. */
    private boolean markdownTocOrdered = false;
    /** Regenerate an existing table of contents when the file is saved. */
    private boolean markdownTocUpdateOnSave = false;
    /** Base file name for pasted images (a counter is added on a clash). */
    private String markdownImageNamePattern = "pasted-image";
    /** Ask for alt text when an image is pasted or dropped. */
    private boolean markdownImageAltPrompt = false;
    /** Run "Markdown Lint: Fix Issues" when a Markdown file is saved. */
    private boolean markdownLintFixOnSave = false;
    /** Markdown-lint rules that are off by default and the user has turned on. */
    private java.util.List<String> markdownLintEnabledRules = new java.util.ArrayList<>();
    /** Per-rule severity ({@code MDxxx} to "error", "warning" or "hint"); absent = warning. */
    private java.util.Map<String, String> markdownLintSeverities = new java.util.LinkedHashMap<>();
    /** Update Markdown links when a file is renamed or moved: "ask", "always" or "never". */
    private String markdownUpdateLinksOnRename = "ask";
    /** Keep the caret line vertically centred while typing. */
    private boolean typewriterMode = false;
    /** Show the word count of a Markdown or plain-text file in the status bar. */
    private boolean statusBarWordCount = true;
    /** Embed local images in an exported HTML page. */
    private boolean htmlExportEmbedImages = true;
    /** Syntax-highlight code blocks in an exported HTML page. */
    private boolean htmlExportHighlightCode = true;
    /** Multiple cursors + Alt+drag column/box selection (RichTextFX fork). Transparent with one caret. */
    private boolean multiCaret = true;
    /** With no selection, Copy/Cut act on the whole current line (VS Code {@code editor.emptySelectionClipboard}). */
    private boolean copyLineWhenNoSelection = true;
    /** Also put a syntax-highlighted HTML flavor on the clipboard when copying (VS Code
     *  {@code editor.copyWithSyntaxHighlighting}), so a paste into Slack/a doc keeps colors. */
    private boolean copyWithSyntaxHighlighting = true;
    /** Check GitHub for a newer release on startup (at most once/day). Contacts the GitHub API over HTTPS. */
    private boolean updateCheck = true;
    /** Epoch millis of the last background update check (throttles to once/day); 0 = never checked. */
    private long lastUpdateCheckEpoch = 0;
    /** The version whose update notice the user dismissed (so it isn't re-shown); "" = none dismissed. */
    private String dismissedUpdateVersion = "";

    private boolean spellCheck = true;
    /** Honor the personal dictionary ({@code dictionary.txt}) during spell check; off re-flags those words. */
    private boolean personalDictionary = true;
    /** Honor the bundled technical-terms dictionary during spell check; off re-flags those terms. */
    private boolean technicalDictionary = true;
    /** Default spell-check dictionary language id (e.g. {@code en_US}); per-file overrides live in WorkspaceState. */
    private String spellLanguage = "en_US";
    /**
     * Languages (as {@code LanguageRegistry} names them) that spell check is switched off for while
     * {@link #spellCheck} is on. Data and configuration formats are off out of the box: their "words" are
     * keys, package names and identifiers, and checking them only produced noise.
     */
    public static final java.util.List<String> DEFAULT_SPELL_DISABLED_LANGUAGES = java.util.List.of(
            "json",
            "yaml",
            "toml",
            "xml",
            "csv",
            "ini",
            "properties",
            "dotenv",
            "systemd",
            "desktop",
            "ssh-config",
            "git-config",
            "gitattributes",
            "ignore",
            "hosts",
            "fstab",
            "crontab",
            "apt",
            "apt-sources",
            "interfaces",
            "network",
            "etc",
            "debian",
            "log",
            "diff");

    private java.util.List<String> spellDisabledLanguages = new java.util.ArrayList<>(DEFAULT_SPELL_DISABLED_LANGUAGES);
    /** Autocomplete master switch (gates all sources); on by default. */
    private boolean autocomplete = true;
    /** Per-source autocomplete toggles (gated by {@link #autocomplete}); on by default. */
    private boolean autocompleteProse = true;

    private boolean autocompleteSnippets = true;
    /** Tab expands the snippet trigger before the caret; off leaves snippets to the popup and the picker. */
    private boolean snippetTabExpansion = true;

    /**
     * Whether the tools Editora drives may run on the host of a remote folder. On by default since v122; each
     * host is still asked once (the connection store keeps the answer) before anything runs there.
     */
    private boolean remoteExecution = true;

    /**
     * The container engine's command, for a folder whose tools run in a container: blank is {@code docker};
     * {@code podman} takes the same arguments. A command line is allowed ({@code flatpak-spawn --host docker}).
     */
    private String containerEngine = "";

    /**
     * Folders whose tools run in a container the user named, by the folder's path: container name or id. A
     * folder not listed is matched to a running dev container started for it, when there is one.
     */
    private java.util.Map<String, String> containerBindings = new java.util.LinkedHashMap<>();
    /** Mermaid keyword + snippet autocomplete in .mmd buffers; on by default but only effective when
     *  Mermaid support is enabled and the tools are detected. */
    private boolean autocompleteMermaid = true;
    /** Auto-show the IntelliJ-style documentation popup beside the completion list (Ctrl+Q toggles it). */
    private boolean completionDoc = true;
    /** Overlay LSP semantic tokens on the syntax highlight (resolved types/params/deprecated, …); on by
     *  default but only effective when LSP is enabled and the server advertises range semantic tokens. */
    private boolean semanticHighlight = true;
    /** LSP inlay hints (parameter names / inferred types) drawn after each line; default off (#681). */
    private boolean inlayHints = false;
    /** How aggressively parameter-name hints are suppressed: {@code literals} (keep only those labelling a
     *  literal argument) or {@code all}. Only effective while {@link #inlayHints} is on, and type hints are
     *  never filtered. Defaults to {@code literals}, matching VS Code's Java extension: a hint on every
     *  argument of every call is mostly noise, the more so while hints render at end-of-line (#823). */
    private String inlayHintMode = "literals";
    /** LSP code lenses — reference and implementation counts drawn after a declaration; default off. */
    private boolean codeLens = false;
    /** Re-indent the current line when a language server's trigger character is typed ({@code ;}, {@code }},
     *  Enter). Default <b>off</b>: it acts on very common keystrokes and overlaps the local auto-indent
     *  assists, so it is opt-in until it has proven itself in the field (#740). */
    private boolean lspOnTypeFormatting = false;
    /** Auto-import for pasted code via jdtls's paste event (#742): on by default, as in VS Code. */
    private boolean lspPasteImports = true;
    /** Smart-semicolon detection (#746): OFF by default, as in VS Code — it moves a typed character. */
    private boolean lspSmartSemicolon;
    /** Honor a project's {@code .editorconfig} (indent, EOL, charset, trim/final-newline, max line length). */
    private boolean editorConfigSupport = true;
    /** Highlight configured patterns (TODO/FIXME/…) in the editor + list them in the TODO tool window. */
    private boolean todoHighlight = true;
    /** The highlight patterns (regex + color); defaults to TODO (amber) + FIXME (red). */
    private java.util.List<com.editora.todo.TodoPattern> todoPatterns = com.editora.todo.TodoPatterns.defaults();
    /** The TODO tool window's "Group by" dimension (FILE/PRIORITY/TAG/KEYWORD); persisted across sessions. */
    private String todoGroupBy = "FILE";
    /** Per-part highlight colors for a structured TODO comment (web hex); defaults from {@code TodoColors}. */
    private String todoTagColor = com.editora.todo.TodoColors.TAG_COLOR;

    private String todoPriorityCriticalColor = com.editora.todo.TodoColors.priorityColor("critical");
    private String todoPriorityHighColor = com.editora.todo.TodoColors.priorityColor("high");
    private String todoPriorityMediumColor = com.editora.todo.TodoColors.priorityColor("medium");
    private String todoPriorityLowColor = com.editora.todo.TodoColors.priorityColor("low");

    private java.util.List<com.editora.externaltool.ExternalTool> externalTools = new java.util.ArrayList<>();
    private boolean abbrevMode = false; // auto-expand abbreviations as you type on a word terminator
    /**
     * The user's customized main-toolbar layout (item ids + {@code "|"} separator tokens from {@code
     * com.editora.toolbar.ToolbarCatalog}). Empty ⇒ use {@code ToolbarCatalog.defaultLayout()}, so the
     * shipped default needs no stored value and Reset-to-Defaults restores it for free.
     */
    private java.util.List<String> toolbarLayout = new java.util.ArrayList<>();
    /** Lint Markdown buffers (squiggles + the Markdown Lint tool window). */
    private boolean markdownLint = true;
    /** Markdown-lint rule codes ({@code MDxxx}) the user has turned off; empty = all rules enabled. */
    private java.util.List<String> markdownLintDisabledRules = new java.util.ArrayList<>();
    /** Render LaTeX math ({@code $…$} / {@code $$…$$}) in the Markdown preview/PDF. On by default. */
    private boolean mathSupport = true;

    private boolean showMenuBar = true;
    private boolean showToolbar = true;
    private boolean showStatusBar = true;
    private boolean showTabBar = true;
    private boolean showBreadcrumb = true;
    /** Show the tool stripes (the side icon bars). UI only — tool windows still open via keys/palette.
     *  Hiding the stripe takes precedence over each tool window's individual visibility. */
    private boolean showToolStripe = true;
    /** Simple UI mode: hides a curated set of chrome (extra toolbar groups, project selector, line-number
     *  gutter, minimap, and several status-bar segments) for a minimal editing surface. Persisted; the
     *  {@code --simple} CLI flag is a session-only override on top of this. */
    private boolean simpleMode = false;
    /** Auto-save mode: "off" | "afterDelay" | "onFocusChange" (parsed leniently; unknown ⇒ off). */
    private String autoSave = "off";

    private int autoSaveDelayMillis = 1000;
    /**
     * Projects feature: <b>on</b>. Off would hide all project UI/commands until enabled.
     *
     * <p>It shipped opt-in, which turned out to be the wrong default: most of what Editora has grown since —
     * the project tree, per-project bookmarks and notes, find-in-files scope, run configurations, per-project
     * settings — is anchored to a project, and a user who never found the checkbox never met any of it.
     */
    private boolean projectSupport = true;
    /** Show hidden (dot) files and folders in the Project tool window's tree + filter search. Off by default. */
    private boolean projectShowHidden = false;
    /** Git integration: on by default — the status-bar VCS segment, Commit tool window, gutter change bars,
     *  and Git commands/keybindings. Self-gates on detection: when {@code git} isn't on PATH it stays inert
     *  (so this is effectively "on when Git is installed"). */
    private boolean gitSupport = true;
    /** GitHub integration via the {@code gh} CLI: on by default — PR checkout, PR diff review, create PR,
     *  open-on-GitHub. Self-gates on detection: inert until {@code gh} is on PATH and authenticated
     *  ({@code gh auth status}). Rides on the Git integration + the user's own {@code gh} credentials —
     *  Editora never handles a token. */
    private boolean githubSupport = true;
    /** Path/command for the GitHub CLI; blank = resolve {@code gh} on PATH. */
    private String ghPath = "";
    /** git command/path override; blank = the {@code git} found on the (augmented) PATH. */
    private String gitPath = "";
    /**
     * How {@code git pull} integrates the remote's commits: {@code ff-only} (the default, and what every
     * version before this setting did), {@code rebase} or {@code merge}; see {@code git.GitPullMode}.
     */
    private String gitPullMode = "ff-only";
    /**
     * "Fetch automatically": a background {@code git fetch --prune} of the active repository every
     * {@link #gitAutoFetchMinutes} minutes. Off by default — it is the only network traffic Editora would
     * start on its own, and it only ever runs in a trusted folder or a repository the user has fetched,
     * pulled or pushed in during the session (see ADR 0002).
     */
    private boolean gitAutoFetch = false;
    /** Minutes between automatic fetches; at least 1. */
    private int gitAutoFetchMinutes = 10;
    /**
     * Settings sync: keeps snippets, abbreviations, templates and the personal dictionary in step with the
     * Git repository at {@link #syncRepoUrl}. Off by default. Every {@code sync*} key describes <em>this</em>
     * installation's connection and is never itself synced.
     */
    private boolean syncEnabled = false;
    /** The repository settings sync pushes to and fetches from; blank = not set up. */
    private String syncRepoUrl = "";
    /** The branch of {@link #syncRepoUrl} that holds the data. */
    private String syncBranch = "main";
    /** Which kinds of data are synced; all four by default. */
    private boolean syncSnippets = true;

    private boolean syncAbbreviations = true;
    private boolean syncTemplates = true;
    private boolean syncDictionary = true;
    /** Sync on startup, shortly after a local change, and every {@link #syncIntervalMinutes} minutes. */
    private boolean syncAuto = true;
    /** Minutes between automatic syncs; at least 1. */
    private int syncIntervalMinutes = 15;
    /** Inline git blame: off by default — when Git is on, paints a GitLens-style annotation
     *  ("author, N days ago • summary") after the caret line. */
    private boolean gitBlameInline = false;
    /** Local File History: on by default — silently snapshots local files on save/auto-save/external
     *  reload so prior versions can be browsed, diffed, and restored independently of VCS. */
    private boolean localHistory = true;
    /** Crash recovery: on by default — while a buffer has unsaved changes a copy of its text is kept under
     *  {@code <config>/recovery/} and offered back on the next launch if Editora did not close normally. */
    private boolean crashRecovery = true;
    /** Max revisions kept per file (oldest pruned beyond this); ≤0 = unbounded. */
    private int historyMaxPerFile = 50;
    /** Max age in days for a revision (older pruned, newest always kept); ≤0 = no age limit. */
    private int historyMaxAgeDays = 30;
    /** Max total uncompressed history bytes kept per project (oldest evicted beyond this); ≤0 = unbounded. */
    private int historyMaxTotalMb = 50;
    /** Plugin support: off by default — plugins run full-trust, untrusted code, so they only load when
     *  this master gate is on (and the individual plugin is enabled in {@code plugins.json}). */
    private boolean pluginSupport = false;
    /** Registry index URL for browsing/installing plugins (HTTPS); overridable, defaults to
     *  {@link #DEFAULT_PLUGIN_REGISTRY}. */
    private String pluginRegistryUrl = "";
    /** Require the registry index to be signed by the bundled key before installing (default on; turn off
     *  to use an unsigned or custom registry). */
    private boolean pluginRequireSignature = true;
    /** Mermaid diagram support: on by default — renders .mmd files and ```mermaid Markdown blocks in the
     *  preview. Self-gates on detection: needs the external mmdc (render/export) and maid (validation) CLIs,
     *  so it stays inert until mmdc is found (effectively "on when the mermaid CLI is found"). */
    private boolean mermaidSupport = true;
    /** Path to the mmdc (mermaid-cli) executable; blank = resolve "mmdc" on PATH. */
    private String mmdcPath = "";
    /** Path to the maid (probelabs/maid linter) executable; blank = resolve "maid" on PATH. */
    private String maidPath = "";
    /** Diagram-as-code preview (Graphviz DOT + PlantUML): on by default — renders .dot/.gv and
     *  .puml/.plantuml files in the 3-mode preview via external CLIs. Self-gates on detection, so it stays
     *  inert until the tool (dot/plantuml) is found. Mirrors {@link #mermaidSupport}. */
    private boolean diagramSupport = true;
    /** Path to the Graphviz dot executable; blank = resolve "dot" on PATH. */
    private String dotPath = "";
    /** Path to the plantuml executable/command; blank = resolve "plantuml" on PATH. */
    private String plantumlPath = "";
    /** Typst document support: a 3-mode (multi-page) rendered preview of .typ files via the typst CLI.
     *  On by default; self-gates on detection (inert until typst is found). Its own seam (not a diagram) since
     *  a Typst document paginates — see {@code editor/TypstImages}. */
    private boolean typstSupport = true;
    /** Path to the typst executable/command; blank = resolve "typst" on PATH. */
    private String typstPath = "";
    /** Maven support (a toolbar icon + actions popup parsed from the active project's pom.xml, streaming
     *  goal/phase runs to a console): on by default — self-gates on detection, so it stays inert (the
     *  toolbar button hidden) until a pom.xml is actually found for the current project/file. */
    private boolean mavenSupport = true;
    /** Override command for launching Maven; blank = auto (prefer the project's ./mvnw wrapper, else
     *  resolve "mvn" on PATH). */
    private String mavenCommand = "";

    /** JDK home used by Maven project runs/debugs and Maven invocations; blank = system/default Java. */
    private String mavenJdkHome = "";

    /** Where "Load full catalog…" in the New Maven Project wizard fetches archetypes from; blank =
     *  {@link #DEFAULT_MAVEN_ARCHETYPE_CATALOG}. */
    private String mavenArchetypeCatalogUrl = "";
    /** npm support (a toolbar icon + actions popup of package.json scripts, streaming runs to a console): on
     *  by default — self-gates on detection, so the toolbar button stays hidden until a package.json is
     *  actually found for the current project/file. */
    private boolean npmSupport = true;
    /** Override command for launching npm; blank = auto (the detected package manager — npm/yarn/pnpm/bun). */
    private String npmCommand = "";
    /** Cargo support (a toolbar icon + actions popup of Cargo.toml subcommands/targets, streaming to a
     *  console): on by default — self-gates on detection (the button stays hidden until a Cargo.toml is found). */
    private boolean cargoSupport = true;
    /** Override command for launching Cargo; blank = resolve "cargo" on PATH. */
    private String cargoCommand = "";
    /** Go support (a toolbar icon + actions popup of standard go subcommands, streaming to a console): on by
     *  default — self-gates on detection (the button stays hidden until a go.mod/go.work is found). */
    private boolean goSupport = true;
    /** Override command for launching Go; blank = resolve "go" on PATH. */
    private String goCommand = "";
    /** Gradle support (a toolbar icon + actions popup of common tasks + an on-demand full task list, streaming
     *  to a console): on by default — self-gates on detection (hidden until a build.gradle[.kts] is found). */
    private boolean gradleSupport = true;
    /** Override command for launching Gradle; blank = auto (prefer the project's ./gradlew wrapper, else
     *  resolve "gradle" on PATH). */
    private String gradleCommand = "";
    /** Clojure CLI support (a toolbar icon + actions popup of a deps.edn's aliases, streaming to a console): on
     *  by default — self-gates on detection (hidden until a deps.edn is found). */
    private boolean clojureCliSupport = true;
    /** Override command for launching the Clojure CLI; blank = resolve "clojure" on PATH. */
    private String clojureCliCommand = "";
    /** Leiningen support (a toolbar icon + actions popup of the common lein tasks): on by default — self-gates
     *  on detection (hidden until a project.clj is found). */
    private boolean leiningenSupport = true;
    /** Override command for launching Leiningen; blank = resolve "lein" on PATH. */
    private String leiningenCommand = "";
    /** Babashka support (a toolbar icon + actions popup of a bb.edn's tasks): on by default — self-gates on
     *  detection (hidden until a bb.edn is found). */
    private boolean babashkaSupport = true;
    /** Override command for launching Babashka; blank = resolve "bb" on PATH. */
    private String babashkaCommand = "";
    /** Test Results window: intercept a build tool's {@code test} run and show an IntelliJ-style results tree
     *  (per-test pass/fail/skip + stack traces) instead of only the raw Output console. On by default;
     *  suppressed in Simple UI mode. */
    private boolean testRunner = true;
    /** Use ripgrep to accelerate Find in Files when it's detected on PATH (default on; falls back otherwise). */
    private boolean ripgrepSearch = true;
    /** Path/command for ripgrep; blank = resolve "rg" on PATH. */
    private String ripgrepCommand = "";

    /** Path/command for GnuPG; blank = resolve "gpg" on PATH and in the installers' folders. */
    private String encryptionGpgPath = "";
    /** Encrypting a file also encrypts it for the user's own key, so they can open it again. */
    private boolean encryptionIncludeSelf = true;
    /** Fingerprint of the user's own key for "include me" and signing; blank = their first secret key. */
    private String encryptionDefaultKey = "";
    /** Who asks for a secret key's passphrase: {@code "agent"} (gpg-agent's pinentry) or {@code "editora"}. */
    private String encryptionPassphrasePrompt = PASSPHRASE_PROMPT_AGENT;
    /** Language servers may read encrypted files' decrypted text (off: their text stays in Editora). */
    private boolean encryptionLanguageServers = false;
    /** Fingerprints of the people most recently encrypted for, newest first: the recipient picker lists them early. */
    private java.util.List<String> encryptionRecentRecipients = new java.util.ArrayList<>();
    /** Exclude files/folders matched by the project's {@code .gitignore} from Find in Files (default on). */
    private boolean searchRespectGitignore = true;
    /** HTTP Client support (run {@code .http} requests via the built-in JDK HTTP client): on by default. */
    private boolean httpClientSupport = true;
    /**
     * Run the JavaScript response handlers ({@code > {% … %}}, {@code > ./check.js}) of {@code .http} requests on
     * Node.js — only from trusted folders: on by default.
     */
    private boolean httpScriptsEnabled = true;
    /** The Node.js that runs HTTP response handlers: blank = {@code node} on the PATH. */
    private String httpNodePath = "";
    /**
     * The Node.js Run, run configurations, Debug, npm scripts and the TypeScript server use (and HTTP response
     * handlers when {@link #httpNodePath} is blank): blank = automatic — a {@code .nvmrc}/{@code .node-version}
     * version nvm has, else {@code node} on the PATH (see {@code run/NodeRuntimes}).
     */
    private String nodePath = "";
    /**
     * Let the TypeScript server download type definitions for plain JavaScript (automatic type acquisition: runs
     * {@code npm install} against the registry into {@code ~/.cache/typescript}): off by default.
     */
    private boolean typescriptTypeAcquisition = false;
    /** Seconds one HTTP response handler may run before it is stopped (1–600). */
    private int httpScriptTimeoutSeconds = 10;
    /**
     * Show each {@code .http} request's last result after its method/URL line, and colour its ▶ by the outcome: on
     * by default.
     */
    private boolean httpInlineResults = true;
    /** HTML Live Preview (serve an HTML file over a loopback HttpServer + open it in a browser): on by default. */
    private boolean htmlPreviewSupport = true;
    /** The last-used browser id for the HTML preview ({@code ""} until the user picks one). */
    private String htmlPreviewBrowser = "";
    /** Server-log viewer (level highlighting + tail-follow + huge-log tail load) for {@code .log} files: on by default. */
    private boolean logViewer = true;
    /** CSV/TSV grid preview tool window (read-only spreadsheet view of {@code .csv}/{@code .tsv}): on by default. */
    private boolean csvPreview = true;
    /** Rainbow per-column coloring in the editor for {@code .csv}/{@code .tsv} files: on by default. */
    private boolean csvRainbow = true;
    /** Tint each bracket by nesting depth (VS Code's bracket-pair colorization): on by default, as there. */
    private boolean bracketColors = true;
    /** Structured-data preview (JSON/YAML/TOML tree + OpenAPI/Swagger docs) in the 3-mode view: on by default. */
    private boolean structuredPreview = true;
    /** Rendered image preview for .svg files in the 3-mode view: on by default (JSVG, no external tool). */
    private boolean svgPreview = true;
    /** Crontab schedule preview (English decode + next run times) in the 3-mode view: on by default. */
    private boolean crontabPreview = true;
    /** fstab mount preview (per-line plain-English decode of /etc/fstab) in the 3-mode view: on by default. */
    private boolean fstabPreview = true;
    /** systemd unit preview (directive glosses + OnCalendar decode) in the 3-mode view: on by default. */
    private boolean systemdPreview = true;
    /** SSH client-config preview (per-Host connection summary) in the 3-mode view: on by default. */
    private boolean sshConfigPreview = true;
    /** Dockerfile stage preview (per-stage digest) in the 3-mode view: on by default. */
    private boolean dockerfilePreview = true;
    /** GitHub Actions workflow preview (triggers + jobs digest, content-detected YAML): on by default. */
    private boolean githubActionsPreview = true;
    /** Maven pom.xml summary preview (properties/dependencies/plugins, instead of the XML tree): on by default. */
    private boolean pomPreview = true;
    /**
     * Draw the menu bar inside the window's own title bar ({@code StageStyle.EXTENDED}), reclaiming a
     * whole row of chrome. Off by default and <b>experimental</b>: it changes how the window is decorated,
     * which is the most platform-specific thing an app does, and it is currently gated to Linux — see
     * {@code ui/ExtendedWindow}. Read once per window at construction, so it applies on restart.
     */
    private boolean extendedWindow = false;
    /**
     * Pin the enclosing scope headers above the viewport as you scroll (VS Code's sticky scroll): on by
     * default. Suppressed in large-file mode along with the other per-viewport work.
     */
    private boolean stickyScroll = true;
    /**
     * The server-free project symbol index behind "Go to Symbol in Project": on by default, but built
     * lazily on first use, so an install that never asks it anything never pays for it.
     */
    private boolean symbolIndex = true;
    /**
     * Make the command palette's chord open Search Everywhere instead: off by default. Search Everywhere
     * is a superset of the palette, but which picker {@code M-x} opens is muscle memory, so the swap is
     * something a user opts into rather than something a release does to them.
     */
    private boolean paletteUsesSearchEverywhere = false;
    /** Auto-rename the paired HTML/XML tag when a tag name is edited: on by default. */
    private boolean autoRenameTag = true;

    private boolean autoFill = false; // Emacs auto-fill-mode: break prose lines at the fill column as you type
    /** Auto-close HTML/XML tags (typing an open tag's {@code >} inserts the closer): on by default. */
    private boolean autoCloseTags = true;
    /** MCP server (expose live editor state + the command registry to an LLM agent over a loopback
     *  HTTP JSON-RPC endpoint, gated by a bearer token): off by default. */
    private boolean mcpSupport = false;
    /** Master AI kill switch: off by default. When off, {@link #agentSupport}/{@link #aiSupport} (and
     *  everything they gate — the AI Agent chat window, commit-message generation, explain/rewrite
     *  selection, inline ghost-text completion) are effectively disabled regardless of their own
     *  settings, mirroring the autocomplete master/sub-toggle pattern. Deliberately does not cover
     *  {@link #mcpSupport} — that lets an *external* agent drive Editora, not Editora calling out to AI. */
    private boolean aiEnabled = false;
    /** Embedded AI agent (an ACP agent — e.g. Claude Code — in the AI Agent chat tool window): off by
     *  default. The agent is an external, user-installed CLI, never bundled. */
    private boolean agentSupport = false;
    /** The ACP agent command (tokenized, quote-aware); blank = {@code claude-code-acp} on PATH — specifically
     *  CLAUDE's command override (kept under its original name to avoid migrating existing user data). */
    private String agentCommand = "";
    // Per-client command overrides for the other ACP agents (blank = that agent's registry default).
    // (Claude's override stays the pre-existing agentCommand field above, to avoid migrating existing data.)
    private String geminiAgentCommand = "";
    private String copilotAgentCommand = "";
    private String codexAgentCommand = "";
    private String qwenAgentCommand = "";
    private String opencodeAgentCommand = "";
    private String lmstudioAgentCommand = "";
    /** The persisted active ACP agent client id (blank = "claude" via AcpAgentRegistry.from). Mirrors aiProvider. */
    private String agentClient = "";
    /** Prefix each agent prompt with a one-line "Context: &lt;path&gt;, cursor at line N[, selected:
     *  &quot;…&quot;]" header (not shown as if the user typed it) so the agent knows the active buffer
     *  without asking: on by default. */
    private boolean agentIncludeContext = true;
    /** AI actions via an API provider or Codex (commit-message generation, explain/rewrite): off by default. */
    private boolean aiSupport = false;
    /** AI action model id; blank = provider default (claude-opus-4-8 for Anthropic, Codex default for Codex). */
    private String aiModel = "";
    /** Anthropic API key override; blank = the ANTHROPIC_API_KEY environment variable. Stored in
     *  settings.json as plain text — prefer the environment variable on shared machines. Per-provider:
     *  this is the Anthropic key only, so switching to the OpenAI provider never sends it to that endpoint
     *  (see {@link #getApiKeyFor}). */
    private String aiApiKey = "";
    /** The OpenAI-compatible provider's API key override (a hosted OpenAI-compatible host — OpenRouter,
     *  Together, Groq — needs one; a local LM Studio/Ollama server does not). Kept separate from
     *  {@link #aiApiKey} so a key configured for one provider is never disclosed to the other's endpoint. */
    private String aiApiKeyOpenai = "";
    /** AI inline ghost-text completion (a short continuation after a typing pause): off by default;
     *  effective only when {@link #aiSupport} is on and an API key is available. */
    private boolean aiInlineCompletion = false;
    /** The model for inline completion; blank = the built-in default (claude-haiku-4-5 — latency). */
    private String aiCompletionModel = "";
    /** The AI provider: {@code "anthropic"} (default), {@code "openai"} for a compatible HTTP server,
     *  {@code "lmstudio"} for isolated Bionic settings, or {@code "codex"} for the ACP adapter. */
    private String aiProvider = "";
    /** The Anthropic (and Codex) endpoint URL; blank = Anthropic's API. */
    private String aiEndpoint = "";
    /** The OpenAI-compatible provider's endpoint, model and inline model, kept apart from the Anthropic ones
     *  ({@link #aiEndpoint}, {@link #aiModel}, {@link #aiCompletionModel}): with one shared endpoint, switching
     *  provider sent the Anthropic key to the OpenAI-compatible server (and the other way round). */
    private String aiEndpointOpenai = "";

    private String aiModelOpenai = "";
    private String aiCompletionModelOpenai = "";
    /** LM Studio / Bionic configuration shared by AI actions and the OpenCode ACP preset.
     * Kept separate so selecting local inference never reuses a cloud endpoint, model, or credential. */
    private String aiLmStudioEndpoint = "";

    private String aiLmStudioModel = "";
    private String aiLmStudioCompletionModel = "";
    private String aiApiKeyLmstudio = "";
    /** Codex's model id (blank = the Codex default). Its own field: it shared the Anthropic one. */
    private String aiModelCodex = "";
    /**
     * Whether the AI keys were moved to the system keychain ({@link ApiKeyVault}). Kept so that a session in which
     * the keychain does not answer can say the keys are there, not lost.
     */
    private boolean aiApiKeysInKeychain = false;
    /** Where the AI keys live this session; null (no keychain asked) means the fields above. Not persisted. */
    @JsonIgnore
    private transient ApiKeyVault apiKeyVault;
    /** Java debugging (DAP) support: off by default. Layered on the Java LSP server (jdtls) + the
     *  Microsoft java-debug plugin; effective only when LSP is on, the java server is enabled/detected,
     *  and the plugin jar is found. */
    private boolean debugSupport = false;
    /**
     * Java debugging: Editora starts the debugged program itself (as the Run console does) instead of
     * leaving that to the debug adapter inside jdtls, so the program has a standard input — lines typed in
     * the Debug console reach it. On by default; off restores the adapter-started program, which cannot be
     * typed to. Only a launch is affected, not an attach, and not the Python/JavaScript adapters.
     */
    private boolean debugProgramConsole = true;
    /**
     * With {@link #debugProgramConsole}: the program Editora starts for a debug session runs in a Terminal tab (a
     * real terminal, for programs that draw on the screen or read keys) instead of the Debug console. Off by default.
     */
    private boolean debugProgramInTerminal = false;
    /**
     * Java debugging: when a saved change reaches the running program (hot code replace) — {@code auto} on
     * every save during a session, {@code manual} only on Debug: Apply Code Changes, {@code off} never on its
     * own (the command still applies). Read by {@code DapManager.CodeReplaceMode.of}.
     */
    private String debugHotCodeReplace = "auto";
    /** Integrated terminal: the shell a new tab runs (blank = the login shell, {@code $SHELL -l}). */
    private String terminalShell = "";
    /** Integrated terminal: lines of history kept above the screen (applies from the next tab). */
    private int terminalScrollback = 5000;
    /** Integrated terminal: text size, 0 = the editor's. The face is always the editor's. */
    private int terminalFontSize = 0;
    /** Integrated terminal: the cursor shape a program has not asked to change ({@code block|underline|bar}). */
    private String terminalCursor = "block";
    /** Integrated terminal: flash on the bell character. */
    private boolean terminalBell = true;
    /** Integrated terminal: ask before closing a tab whose shell is running a program. */
    private boolean terminalConfirmClose = true;
    /**
     * Integrated terminal: start bash and zsh with Editora's shell integration, so the terminal knows the shell's
     * directory (links, New Terminal Here) and where each command's output is. Users' own files are not changed.
     */
    private boolean terminalShellIntegration = true;
    /** Integrated terminal: paint keystrokes before a slow shell echoes them (only once echoes take over 100 ms). */
    private boolean terminalTypeAhead = true;
    /** "Open in Terminal" starts the system's terminal application instead of a tab in the Terminal tool window. */
    private boolean terminalUseExternal = false;
    /**
     * macOS: the Option key is Meta in a terminal (ESC + key, readline's M-b/M-f) instead of composing characters.
     * Off by default, as in Terminal.app: on many layouts @ [ ] { } | ~ are Option chords. Alt is always Meta on
     * Linux and Windows, where AltGr composes.
     */
    private boolean terminalMacOptionIsMeta = false;
    /** Ask before pasting several lines into a program that has not turned on bracketed paste. */
    private boolean terminalConfirmMultilinePaste = true;

    /** The most scrollback a terminal keeps: about a megabyte per thousand lines, per tab. */
    public static final int TERMINAL_SCROLLBACK_MAX = 50_000;

    /**
     * The commands a focused terminal still lets through; every other key goes to the shell. Exact ids or
     * {@code prefix.*}. Mirrors {@code TerminalKeys.DEFAULT_IDE_COMMANDS} (a test keeps them equal).
     */
    public static final java.util.List<String> DEFAULT_TERMINAL_IDE_COMMANDS =
            java.util.List.of("tool.*", "palette.show", "search.everywhere", "terminal.*");

    private java.util.List<String> terminalIdeCommands = new java.util.ArrayList<>(DEFAULT_TERMINAL_IDE_COMMANDS);
    /**
     * Java debugging: Step Into skips JDK classes and synthetic methods (java-debug's step filters) — what
     * IntelliJ and VS Code do by default. Off steps into everything, JDK internals included.
     */
    private boolean debugStepFilters = true;
    /** Java debugging: a class's static fields are shown among the variables (java-debug hides them otherwise). */
    private boolean debugShowStatics = true;
    /**
     * Java debugging: the exception classes the exception breakpoints are limited to, separated by commas
     * (e.g. {@code java.lang.IllegalStateException}); blank = every exception.
     */
    private String debugExceptionTypes = "";
    /** Path to the {@code com.microsoft.java.debug.plugin-*.jar} (a jar, or a dir to scan); blank =
     *  auto-detect common install locations (VS Code java extension, mason, …). */
    private String javaDebugPluginPath = "";
    /** Python debugging (debugpy over stdio): on by default, only effective when the master
     *  {@code debugSupport} is on and debugpy is importable by the configured/PATH python. */
    private boolean pythonDebugEnabled = true;
    /** Python interpreter that runs {@code -m debugpy.adapter}; blank = resolve "python3" on PATH. */
    private String pythonDebugCommand = "";
    /** JavaScript (Node) debugging (vscode-js-debug over a socket): on by default, only effective when
     *  {@code debugSupport} is on and the js-debug {@code dapDebugServer.js} + node are found. */
    private boolean jsDebugEnabled = true;
    /** Path to vscode-js-debug's {@code dapDebugServer.js} (or a dir to scan); blank = auto-detect
     *  (Editora's plugins dir, mason, VS Code js-debug extension). */
    private String jsDebugPath = "";
    /** Language Server Protocol support: off by default — needs an external language server. Phase 1
     *  covers Java (Eclipse JDT LS): diagnostics, hover/go-to-definition/references, completion. */
    private boolean lspSupport = false;
    /** Show the in-editor "install language support?" banner when an enabled language's server is missing
     *  (Java/Python/JS LSP, or the Mermaid CLI). Default on; the user can silence the nudge. */
    private boolean lspInstallPrompts = true;
    /** Command to launch the Java language server (JDT LS); blank = resolve "jdtls" on PATH. */
    private String javaLspCommand = "";

    private String typescriptLspCommand = "";
    private String pythonLspCommand = "";
    private String xmlLspCommand = "";
    private String jsonLspCommand = "";
    private String bashLspCommand = "";
    private String yamlLspCommand = "";
    private String goLspCommand = "";
    private String rustLspCommand = "";
    private String phpLspCommand = "";
    private String rubyLspCommand = "";
    private String clangdLspCommand = "";
    private String htmlLspCommand = "";
    private String cssLspCommand = "";
    private String kotlinLspCommand = "";
    private String groovyLspCommand = "";
    private String clojureLspCommand = "";
    private String luaLspCommand = "";
    private String dockerfileLspCommand = "";
    private String sqlLspCommand = "";
    private String terraformLspCommand = "";
    private String tomlLspCommand = "";
    private String csharpLspCommand = "";
    private String typstLspCommand = "";
    private String astroLspCommand = "";
    /** The Maven-aware pom.xml server (JVM lemminx + lemminx-maven). Blank until the install recipe writes the
     *  {@code java -cp "<dir>/*" org.eclipse.lemminx.XMLServerLauncher} command; blank ⇒ not installed. */
    private String mavenPomLspCommand = "";

    // --- bash-language-server's own settings (its "bashIde" configuration section) ------------------------
    /** ShellCheck executable; blank = the one Editora installed (plugins/tools/shellcheck), else "shellcheck". */
    private String bashShellcheckPath = "";
    /** Extra ShellCheck arguments (e.g. {@code -x --severity=warning}); the server adds --shell/--format. */
    private String bashShellcheckArguments = "";
    /** shfmt executable; blank = the one Editora installed (plugins/tools/shfmt), else "shfmt". */
    private String bashShfmtPath = "";
    /** shfmt's dialect: auto, bash, posix, mksh or bats. */
    private String bashShfmtDialect = "auto";

    private boolean bashShfmtSimplify;
    private boolean bashShfmtBinaryNextLine;
    private boolean bashShfmtCaseIndent;
    private boolean bashShfmtFuncNextLine;
    private boolean bashShfmtSpaceRedirects;
    private boolean bashShfmtKeepPadding;
    /** Use only these options, even when an .editorconfig carries shfmt properties. */
    private boolean bashShfmtIgnoreEditorconfig;
    /** Most workspace scripts the server reads in the background; 0 turns background analysis off. */
    private int bashBackgroundAnalysisMaxFiles = BASH_BACKGROUND_ANALYSIS_DEFAULT;
    /** Symbols from every workspace script, not only from sourced files (completion, rename, …). */
    private boolean bashIncludeAllWorkspaceSymbols;
    /** explainshell server for hover help on flags; blank = off (it receives the command under the caret). */
    private String bashExplainshellEndpoint = "";

    private boolean javaLspEnabled = true;
    private boolean typescriptLspEnabled = true;
    private boolean pythonLspEnabled = true;
    private boolean xmlLspEnabled = true;
    private boolean jsonLspEnabled = true;
    private boolean bashLspEnabled = true;
    private boolean yamlLspEnabled = true;
    private boolean goLspEnabled = true;
    private boolean rustLspEnabled = true;
    private boolean phpLspEnabled = true;
    private boolean rubyLspEnabled = true;
    private boolean clangdLspEnabled = true;
    private boolean htmlLspEnabled = true;
    private boolean cssLspEnabled = true;
    private boolean kotlinLspEnabled = true;
    private boolean groovyLspEnabled = true;
    private boolean clojureLspEnabled = true;
    private boolean luaLspEnabled = true;
    private boolean dockerfileLspEnabled = true;
    private boolean sqlLspEnabled = true;
    private boolean terraformLspEnabled = true;
    private boolean tomlLspEnabled = true;
    private boolean csharpLspEnabled = true;
    private boolean typstLspEnabled = true;
    private boolean astroLspEnabled = true;
    /** Whether the Maven-aware pom.xml server is enabled (only effective once installed — see
     *  {@link #mavenPomLspCommand} — and under the global LSP enable). */
    private boolean mavenPomLspEnabled = true;
    /** PDF export: include the line-number gutter (code PDFs). */
    private boolean pdfLineNumbers = true;
    /** PDF export: apply syntax-highlighting colors (code PDFs); off = plain monospace. */
    private boolean pdfSyntaxHighlighting = true;
    /** Print and PDF export: the footer of each page — the document name and "Page n of N". */
    private boolean pdfPageFooter = true;
    /**
     * PDF export page size: "letter" or "a4". A new installation starts with the paper of its region (see
     * {@link PdfPageSizes}); a stored value always wins.
     */
    private String pdfPageSize = PdfPageSizes.systemDefault();
    /** PDF export orientation: "portrait" (default) or "landscape". Text PDFs only — see the getter. */
    private String pdfOrientation = "portrait";
    /**
     * PDF export margins: "normal" (default — each writer's own margin, 40 pt for source text and 50 pt for
     * Markdown and tables), "narrow" or "wide". Text PDFs only.
     */
    private String pdfMargins = "normal";
    /** PDF export: the monospace size of a source-text PDF in points, 7–12 (default 9). */
    private int pdfCodeFontSize = DEFAULT_PDF_CODE_FONT_SIZE;

    /**
     * Optional per-binding overrides applied on top of the named keymap: chord -&gt; command id. <b>Split per
     * platform</b> because a chord is modifier-specific (Cmd on macOS, Ctrl elsewhere) and a rebind's UNBIND
     * suppressors key to the <em>resolved</em> chord — one shared map double-binds a command across OSes when a
     * config is synced (a mac-written {@code Cmd-S UNBIND} matches nothing on Windows, leaving the Ctrl-S default
     * live alongside the new chord). {@link #keybindings} holds the Ctrl-based (Windows/Linux) overrides;
     * {@link #keybindingsMac} the Cmd-based (macOS) ones. Read the running platform's via {@link #keybindingsFor}
     * (#439).
     *
     * <p>Both maps are the overrides of the <b>active</b> {@link #keymap} only. A rebind also stores a blank
     * suppressor for each chord the keymap bound to that command by default, and the same chord means something
     * else in another keymap (CUA's {@code C-f} is Find, Emacs' is forward-char) — so overrides carried across a
     * keymap switch unbound unrelated keys. {@link #switchKeymap} parks them in {@link #keymapKeybindings} /
     * {@link #keymapKeybindingsMac} (keymap id -&gt; overrides) and brings back the ones made in the keymap
     * being switched to.
     */
    private Map<String, String> keybindings = new LinkedHashMap<>();

    private Map<String, String> keybindingsMac = new LinkedHashMap<>();

    /** Overrides parked for the keymaps that are not active (see {@link #keybindings}); never the active one. */
    private Map<String, Map<String, String>> keymapKeybindings = new LinkedHashMap<>();

    private Map<String, Map<String, String>> keymapKeybindingsMac = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    public double getFontZoom() {
        return fontZoom;
    }

    public void setFontZoom(double fontZoom) {
        this.fontZoom = Double.isNaN(fontZoom) ? 1.0 : Math.clamp(fontZoom, MIN_FONT_ZOOM, MAX_FONT_ZOOM);
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getEditorTheme() {
        return editorTheme;
    }

    public void setEditorTheme(String editorTheme) {
        this.editorTheme = editorTheme;
    }

    public boolean isEditorThemeUserSet() {
        return editorThemeUserSet;
    }

    public void setEditorThemeUserSet(boolean editorThemeUserSet) {
        this.editorThemeUserSet = editorThemeUserSet;
    }

    public int getTabSize() {
        return tabSize;
    }

    public int getLargeFileThreshold() {
        return largeFileThreshold;
    }

    public void setLargeFileThreshold(int largeFileThreshold) {
        this.largeFileThreshold = Math.max(0, largeFileThreshold);
    }

    public void setTabSize(int tabSize) {
        this.tabSize = Math.clamp(tabSize, MIN_TAB_SIZE, MAX_TAB_SIZE);
    }

    /** {@code "detect"} (default), {@code "space"}, or {@code "tab"}. */
    public String getIndentStyle() {
        return indentStyle == null ? "detect" : indentStyle;
    }

    public void setIndentStyle(String indentStyle) {
        this.indentStyle = indentStyle;
    }

    public int getFillColumn() {
        return fillColumn < 1 ? com.editora.editops.Filler.DEFAULT_FILL_COLUMN : fillColumn;
    }

    public void setFillColumn(int fillColumn) {
        this.fillColumn = Math.min(fillColumn, MAX_FILL_COLUMN); // below 1 still reads back as the default
    }

    public String getKeymap() {
        return keymap;
    }

    public void setKeymap(String keymap) {
        this.keymap = keymap;
    }

    public String getUiLanguage() {
        return uiLanguage == null ? "" : uiLanguage;
    }

    public void setUiLanguage(String uiLanguage) {
        this.uiLanguage = uiLanguage == null ? "" : uiLanguage;
    }

    /**
     * The configured author name, or the OS user name when blank (used by template {@code ${author}}).
     *
     * <p>Not serialized: Jackson writes through getters, so persisting this resolved value stored the OS user
     * name on the first save and ended the "blank = follow the OS user" mode. {@link #getAuthorNameRaw()} is
     * the persisted form.
     */
    @JsonIgnore
    public String getAuthorName() {
        return authorName == null || authorName.isBlank() ? System.getProperty("user.name", "") : authorName;
    }

    /** The raw configured author name (may be blank, meaning "follow the OS user"). */
    @JsonProperty("authorName")
    public String getAuthorNameRaw() {
        return authorName == null ? "" : authorName;
    }

    @JsonProperty("authorName")
    public void setAuthorName(String authorName) {
        this.authorName = authorName == null ? "" : authorName;
    }

    public boolean isHttpClientSupport() {
        return httpClientSupport;
    }

    public void setHttpClientSupport(boolean httpClientSupport) {
        this.httpClientSupport = httpClientSupport;
    }

    public boolean isHttpScriptsEnabled() {
        return httpScriptsEnabled;
    }

    public void setHttpScriptsEnabled(boolean httpScriptsEnabled) {
        this.httpScriptsEnabled = httpScriptsEnabled;
    }

    public boolean isHttpInlineResults() {
        return httpInlineResults;
    }

    public void setHttpInlineResults(boolean httpInlineResults) {
        this.httpInlineResults = httpInlineResults;
    }

    public String getNodePath() {
        return nodePath == null ? "" : nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath == null ? "" : nodePath.strip();
    }

    public boolean isTypescriptTypeAcquisition() {
        return typescriptTypeAcquisition;
    }

    public void setTypescriptTypeAcquisition(boolean typescriptTypeAcquisition) {
        this.typescriptTypeAcquisition = typescriptTypeAcquisition;
    }

    public String getHttpNodePath() {
        return httpNodePath;
    }

    public void setHttpNodePath(String httpNodePath) {
        this.httpNodePath = httpNodePath == null ? "" : httpNodePath.strip();
    }

    public int getHttpScriptTimeoutSeconds() {
        return httpScriptTimeoutSeconds;
    }

    public void setHttpScriptTimeoutSeconds(int seconds) {
        this.httpScriptTimeoutSeconds = Math.max(1, Math.min(600, seconds));
    }

    public boolean isHtmlPreviewSupport() {
        return htmlPreviewSupport;
    }

    public boolean isCsvPreview() {
        return csvPreview;
    }

    public void setCsvPreview(boolean csvPreview) {
        this.csvPreview = csvPreview;
    }

    public boolean isStructuredPreview() {
        return structuredPreview;
    }

    public void setStructuredPreview(boolean structuredPreview) {
        this.structuredPreview = structuredPreview;
    }

    public boolean isSvgPreview() {
        return svgPreview;
    }

    public void setSvgPreview(boolean svgPreview) {
        this.svgPreview = svgPreview;
    }

    public boolean isCrontabPreview() {
        return crontabPreview;
    }

    public void setCrontabPreview(boolean crontabPreview) {
        this.crontabPreview = crontabPreview;
    }

    public boolean isFstabPreview() {
        return fstabPreview;
    }

    public void setFstabPreview(boolean fstabPreview) {
        this.fstabPreview = fstabPreview;
    }

    public boolean isSystemdPreview() {
        return systemdPreview;
    }

    public void setSystemdPreview(boolean systemdPreview) {
        this.systemdPreview = systemdPreview;
    }

    public boolean isSshConfigPreview() {
        return sshConfigPreview;
    }

    public void setSshConfigPreview(boolean sshConfigPreview) {
        this.sshConfigPreview = sshConfigPreview;
    }

    public boolean isDockerfilePreview() {
        return dockerfilePreview;
    }

    public void setDockerfilePreview(boolean dockerfilePreview) {
        this.dockerfilePreview = dockerfilePreview;
    }

    public boolean isGithubActionsPreview() {
        return githubActionsPreview;
    }

    public void setGithubActionsPreview(boolean githubActionsPreview) {
        this.githubActionsPreview = githubActionsPreview;
    }

    public boolean isSymbolIndex() {
        return symbolIndex;
    }

    public void setSymbolIndex(boolean symbolIndex) {
        this.symbolIndex = symbolIndex;
    }

    public boolean isPaletteUsesSearchEverywhere() {
        return paletteUsesSearchEverywhere;
    }

    public void setPaletteUsesSearchEverywhere(boolean paletteUsesSearchEverywhere) {
        this.paletteUsesSearchEverywhere = paletteUsesSearchEverywhere;
    }

    public boolean isExtendedWindow() {
        return extendedWindow;
    }

    public void setExtendedWindow(boolean extendedWindow) {
        this.extendedWindow = extendedWindow;
    }

    public boolean isStickyScroll() {
        return stickyScroll;
    }

    public void setStickyScroll(boolean stickyScroll) {
        this.stickyScroll = stickyScroll;
    }

    public boolean isPomPreview() {
        return pomPreview;
    }

    public void setPomPreview(boolean pomPreview) {
        this.pomPreview = pomPreview;
    }

    public boolean isCsvRainbow() {
        return csvRainbow;
    }

    public void setCsvRainbow(boolean csvRainbow) {
        this.csvRainbow = csvRainbow;
    }

    public boolean isBracketColors() {
        return bracketColors;
    }

    public void setBracketColors(boolean bracketColors) {
        this.bracketColors = bracketColors;
    }

    public boolean isAutoRenameTag() {
        return autoRenameTag;
    }

    public void setAutoRenameTag(boolean autoRenameTag) {
        this.autoRenameTag = autoRenameTag;
    }

    public boolean isAutoFill() {
        return autoFill;
    }

    public void setAutoFill(boolean autoFill) {
        this.autoFill = autoFill;
    }

    public boolean isAutoCloseTags() {
        return autoCloseTags;
    }

    public void setAutoCloseTags(boolean autoCloseTags) {
        this.autoCloseTags = autoCloseTags;
    }

    public boolean isLogViewer() {
        return logViewer;
    }

    public void setLogViewer(boolean logViewer) {
        this.logViewer = logViewer;
    }

    public void setHtmlPreviewSupport(boolean htmlPreviewSupport) {
        this.htmlPreviewSupport = htmlPreviewSupport;
    }

    public String getHtmlPreviewBrowser() {
        return htmlPreviewBrowser == null ? "" : htmlPreviewBrowser;
    }

    public void setHtmlPreviewBrowser(String htmlPreviewBrowser) {
        this.htmlPreviewBrowser = htmlPreviewBrowser == null ? "" : htmlPreviewBrowser;
    }

    public boolean isMcpSupport() {
        return mcpSupport;
    }

    public void setMcpSupport(boolean mcpSupport) {
        this.mcpSupport = mcpSupport;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    public boolean isAgentSupport() {
        return agentSupport;
    }

    public void setAgentSupport(boolean agentSupport) {
        this.agentSupport = agentSupport;
    }

    public String getAgentCommand() {
        return agentCommand == null ? "" : agentCommand;
    }

    public void setAgentCommand(String agentCommand) {
        this.agentCommand = agentCommand;
    }

    public String getGeminiAgentCommand() {
        return geminiAgentCommand == null ? "" : geminiAgentCommand;
    }

    public void setGeminiAgentCommand(String v) {
        this.geminiAgentCommand = v;
    }

    public String getCopilotAgentCommand() {
        return copilotAgentCommand == null ? "" : copilotAgentCommand;
    }

    public void setCopilotAgentCommand(String v) {
        this.copilotAgentCommand = v;
    }

    public String getCodexAgentCommand() {
        return codexAgentCommand == null ? "" : codexAgentCommand;
    }

    public void setCodexAgentCommand(String v) {
        this.codexAgentCommand = v;
    }

    public String getQwenAgentCommand() {
        return qwenAgentCommand == null ? "" : qwenAgentCommand;
    }

    public void setQwenAgentCommand(String v) {
        this.qwenAgentCommand = v;
    }

    public String getOpencodeAgentCommand() {
        return opencodeAgentCommand == null ? "" : opencodeAgentCommand;
    }

    public void setOpencodeAgentCommand(String v) {
        this.opencodeAgentCommand = v;
    }

    public String getLmstudioAgentCommand() {
        return lmstudioAgentCommand == null ? "" : lmstudioAgentCommand;
    }

    public void setLmstudioAgentCommand(String value) {
        lmstudioAgentCommand = value;
    }

    public String getAgentClient() {
        return agentClient == null ? "" : agentClient;
    }

    public void setAgentClient(String agentClient) {
        this.agentClient = agentClient;
    }

    public boolean isAgentIncludeContext() {
        return agentIncludeContext;
    }

    public void setAgentIncludeContext(boolean agentIncludeContext) {
        this.agentIncludeContext = agentIncludeContext;
    }

    public boolean isAiSupport() {
        return aiSupport;
    }

    public void setAiSupport(boolean aiSupport) {
        this.aiSupport = aiSupport;
    }

    public String getAiModel() {
        return aiModel == null ? "" : aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getAiApiKey() {
        return aiApiKey == null ? "" : aiApiKey;
    }

    public void setAiApiKey(String aiApiKey) {
        this.aiApiKey = aiApiKey;
    }

    public String getAiApiKeyOpenai() {
        return aiApiKeyOpenai == null ? "" : aiApiKeyOpenai;
    }

    public void setAiApiKeyOpenai(String aiApiKeyOpenai) {
        this.aiApiKeyOpenai = aiApiKeyOpenai;
    }

    /**
     * The API key for {@code provider}: from the system keychain when the keys live there ({@link ApiKeyVault}),
     * else from this provider's own field. Codex always has none. Kept per provider so switching provider never
     * sends one provider's credential to another's (user-supplied) endpoint. {@code provider} null → Anthropic.
     */
    public String getApiKeyFor(com.editora.ai.AiProvider provider) {
        if (provider == com.editora.ai.AiProvider.CODEX) {
            return "";
        }
        ApiKeyVault vault = apiKeyVault;
        return vault != null && vault.inKeychain() ? vault.get(aiSlot(provider)) : rawApiKey(aiSlot(provider));
    }

    /** Sets the API key for {@code provider}: into the keychain when the keys live there, else into its field. */
    public void setApiKeyFor(com.editora.ai.AiProvider provider, String key) {
        if (provider == com.editora.ai.AiProvider.CODEX) {
            return;
        }
        ApiKeyVault vault = apiKeyVault;
        if (vault != null && vault.inKeychain() && vault.put(aiSlot(provider), key)) {
            return;
        }
        setRawApiKey(aiSlot(provider), key); // no keychain, or it just refused (the vault fell back)
    }

    /** The key field of {@code provider} as {@code settings.json} holds it (blank once moved to the keychain). */
    String rawApiKey(com.editora.ai.AiProvider provider) {
        return switch (provider) {
            case LMSTUDIO -> getAiApiKeyLmstudio();
            case OPENAI -> getAiApiKeyOpenai();
            default -> getAiApiKey();
        };
    }

    void setRawApiKey(com.editora.ai.AiProvider provider, String key) {
        switch (provider) {
            case LMSTUDIO -> setAiApiKeyLmstudio(key);
            case OPENAI -> setAiApiKeyOpenai(key);
            default -> setAiApiKey(key);
        }
    }

    /** Attaches the vault the keys are read from and written to (see {@link ApiKeyVault#open}). */
    void useApiKeyVault(ApiKeyVault vault) {
        this.apiKeyVault = vault;
        if (vault != null) {
            vault.bind(this);
        }
    }

    /** Where the AI keys live this session (the fields, when no keychain was asked). */
    public ApiKeyVault.Storage apiKeyStorage() {
        ApiKeyVault vault = apiKeyVault;
        return vault == null ? ApiKeyVault.Storage.SETTINGS_FILE : vault.storage();
    }

    /** The keychain's name, for the AI Actions page ("macOS Keychain", …); empty without a vault. */
    public String apiKeyStoreName() {
        ApiKeyVault vault = apiKeyVault;
        return vault == null ? "" : vault.storeName();
    }

    public boolean isAiApiKeysInKeychain() {
        return aiApiKeysInKeychain;
    }

    public void setAiApiKeysInKeychain(boolean aiApiKeysInKeychain) {
        this.aiApiKeysInKeychain = aiApiKeysInKeychain;
    }

    public String getAiModelCodex() {
        return aiModelCodex == null ? "" : aiModelCodex;
    }

    public void setAiModelCodex(String value) {
        aiModelCodex = value;
    }

    public boolean isAiInlineCompletion() {
        return aiInlineCompletion;
    }

    public void setAiInlineCompletion(boolean aiInlineCompletion) {
        this.aiInlineCompletion = aiInlineCompletion;
    }

    public String getAiCompletionModel() {
        return aiCompletionModel == null ? "" : aiCompletionModel;
    }

    public void setAiCompletionModel(String aiCompletionModel) {
        this.aiCompletionModel = aiCompletionModel;
    }

    public String getAiProvider() {
        return aiProvider == null ? "" : aiProvider;
    }

    public void setAiProvider(String aiProvider) {
        this.aiProvider = aiProvider;
    }

    public String getAiEndpoint() {
        return aiEndpoint == null ? "" : aiEndpoint;
    }

    public void setAiEndpoint(String aiEndpoint) {
        this.aiEndpoint = aiEndpoint;
    }

    public String getAiLmStudioEndpoint() {
        return aiLmStudioEndpoint == null ? "" : aiLmStudioEndpoint;
    }

    public void setAiLmStudioEndpoint(String value) {
        aiLmStudioEndpoint = value;
    }

    public String getAiLmStudioModel() {
        return aiLmStudioModel == null ? "" : aiLmStudioModel;
    }

    public void setAiLmStudioModel(String value) {
        aiLmStudioModel = value;
    }

    public String getAiLmStudioCompletionModel() {
        return aiLmStudioCompletionModel == null ? "" : aiLmStudioCompletionModel;
    }

    public void setAiLmStudioCompletionModel(String value) {
        aiLmStudioCompletionModel = value;
    }

    public String getAiApiKeyLmstudio() {
        return aiApiKeyLmstudio == null ? "" : aiApiKeyLmstudio;
    }

    public void setAiApiKeyLmstudio(String value) {
        aiApiKeyLmstudio = value;
    }

    public String getAiEndpointOpenai() {
        return aiEndpointOpenai == null ? "" : aiEndpointOpenai;
    }

    public void setAiEndpointOpenai(String value) {
        aiEndpointOpenai = value;
    }

    public String getAiModelOpenai() {
        return aiModelOpenai == null ? "" : aiModelOpenai;
    }

    public void setAiModelOpenai(String value) {
        aiModelOpenai = value;
    }

    public String getAiCompletionModelOpenai() {
        return aiCompletionModelOpenai == null ? "" : aiCompletionModelOpenai;
    }

    public void setAiCompletionModelOpenai(String value) {
        aiCompletionModelOpenai = value;
    }

    /** {@code provider}'s own endpoint: each provider keeps one, so a switch never reuses another's server. */
    public String getAiEndpointFor(com.editora.ai.AiProvider provider) {
        return switch (aiSlot(provider)) {
            case LMSTUDIO -> getAiLmStudioEndpoint();
            case OPENAI -> getAiEndpointOpenai();
            default -> getAiEndpoint();
        };
    }

    public void setAiEndpointFor(com.editora.ai.AiProvider provider, String value) {
        switch (aiSlot(provider)) {
            case LMSTUDIO -> setAiLmStudioEndpoint(value);
            case OPENAI -> setAiEndpointOpenai(value);
            default -> setAiEndpoint(value);
        }
    }

    public String getAiModelFor(com.editora.ai.AiProvider provider) {
        return switch (aiSlot(provider)) {
            case LMSTUDIO -> getAiLmStudioModel();
            case OPENAI -> getAiModelOpenai();
            case CODEX -> getAiModelCodex();
            default -> getAiModel();
        };
    }

    public void setAiModelFor(com.editora.ai.AiProvider provider, String value) {
        switch (aiSlot(provider)) {
            case LMSTUDIO -> setAiLmStudioModel(value);
            case OPENAI -> setAiModelOpenai(value);
            case CODEX -> setAiModelCodex(value);
            default -> setAiModel(value);
        }
    }

    public String getAiCompletionModelFor(com.editora.ai.AiProvider provider) {
        return switch (aiSlot(provider)) {
            case LMSTUDIO -> getAiLmStudioCompletionModel();
            case OPENAI -> getAiCompletionModelOpenai();
            default -> getAiCompletionModel();
        };
    }

    public void setAiCompletionModelFor(com.editora.ai.AiProvider provider, String value) {
        switch (aiSlot(provider)) {
            case LMSTUDIO -> setAiLmStudioCompletionModel(value);
            case OPENAI -> setAiCompletionModelOpenai(value);
            default -> setAiCompletionModel(value);
        }
    }

    /** The provider whose fields hold {@code provider}'s values (null = Anthropic, the default). */
    private static com.editora.ai.AiProvider aiSlot(com.editora.ai.AiProvider provider) {
        return provider == null ? com.editora.ai.AiProvider.ANTHROPIC : provider;
    }

    public java.util.List<Integer> getRulers() {
        return rulers;
    }

    public void setRulers(java.util.List<Integer> rulers) {
        this.rulers = rulers == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(rulers);
    }

    public java.util.Map<String, java.util.List<Integer>> getLanguageRulers() {
        return languageRulers;
    }

    public void setLanguageRulers(java.util.Map<String, java.util.List<Integer>> languageRulers) {
        this.languageRulers = languageRulers == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(languageRulers);
    }

    public boolean isShowIndentGuides() {
        return showIndentGuides;
    }

    public void setShowIndentGuides(boolean showIndentGuides) {
        this.showIndentGuides = showIndentGuides;
    }

    public boolean isShowColumnRuler() {
        return showColumnRuler;
    }

    public void setShowColumnRuler(boolean showColumnRuler) {
        this.showColumnRuler = showColumnRuler;
    }

    public boolean isHighlightCurrentLine() {
        return highlightCurrentLine;
    }

    public void setHighlightCurrentLine(boolean highlightCurrentLine) {
        this.highlightCurrentLine = highlightCurrentLine;
    }

    public boolean isShowLineNumbers() {
        return showLineNumbers;
    }

    public void setShowLineNumbers(boolean showLineNumbers) {
        this.showLineNumbers = showLineNumbers;
    }

    public boolean isShowMinimap() {
        return showMinimap;
    }

    public void setShowMinimap(boolean showMinimap) {
        this.showMinimap = showMinimap;
    }

    public boolean isWordWrap() {
        return wordWrap;
    }

    public void setWordWrap(boolean wordWrap) {
        this.wordWrap = wordWrap;
    }

    public boolean isWordWrapProse() {
        return wordWrapProse;
    }

    public void setWordWrapProse(boolean wordWrapProse) {
        this.wordWrapProse = wordWrapProse;
    }

    /** Whether lines wrap in a buffer of {@code language} (a {@code LanguageRegistry} name; null = plain text). */
    public boolean wrapsLanguage(String language) {
        return isProseLanguage(language) ? wordWrapProse : wordWrap;
    }

    /** Markdown and plain text: the languages whose lines are paragraphs, governed by {@link #wordWrapProse}. */
    public static boolean isProseLanguage(String language) {
        return language == null || language.equals("plaintext") || language.equals("markdown");
    }

    public boolean isAdminSave() {
        return adminSave;
    }

    public void setAdminSave(boolean adminSave) {
        this.adminSave = adminSave;
    }

    public boolean isShowWhitespace() {
        return showWhitespace;
    }

    public void setShowWhitespace(boolean showWhitespace) {
        this.showWhitespace = showWhitespace;
    }

    public boolean isNotesSupport() {
        return notesSupport;
    }

    public void setNotesSupport(boolean notesSupport) {
        this.notesSupport = notesSupport;
    }

    public boolean isShowNoteIndicators() {
        return showNoteIndicators;
    }

    public void setShowNoteIndicators(boolean showNoteIndicators) {
        this.showNoteIndicators = showNoteIndicators;
    }

    public boolean isSpellCheck() {
        return spellCheck;
    }

    public void setSpellCheck(boolean spellCheck) {
        this.spellCheck = spellCheck;
    }

    public boolean isPersonalDictionary() {
        return personalDictionary;
    }

    public void setPersonalDictionary(boolean personalDictionary) {
        this.personalDictionary = personalDictionary;
    }

    public boolean isTechnicalDictionary() {
        return technicalDictionary;
    }

    public void setTechnicalDictionary(boolean technicalDictionary) {
        this.technicalDictionary = technicalDictionary;
    }

    public boolean isAutocomplete() {
        return autocomplete;
    }

    public void setAutocomplete(boolean autocomplete) {
        this.autocomplete = autocomplete;
    }

    public boolean isAutocompleteProse() {
        return autocompleteProse;
    }

    public void setAutocompleteProse(boolean autocompleteProse) {
        this.autocompleteProse = autocompleteProse;
    }

    public boolean isAutocompleteSnippets() {
        return autocompleteSnippets;
    }

    public void setAutocompleteSnippets(boolean autocompleteSnippets) {
        this.autocompleteSnippets = autocompleteSnippets;
    }

    /**
     * Whether commands for a remote (SFTP) folder may run on its host. Off by default: connecting to a
     * server to edit its files must not, by itself, let the editor start programs there.
     */
    public boolean isRemoteExecution() {
        return remoteExecution;
    }

    public String getContainerEngine() {
        return containerEngine;
    }

    public void setContainerEngine(String containerEngine) {
        this.containerEngine = containerEngine == null ? "" : containerEngine.strip();
    }

    public java.util.Map<String, String> getContainerBindings() {
        return java.util.Map.copyOf(containerBindings);
    }

    public void setContainerBindings(java.util.Map<String, String> containerBindings) {
        java.util.Map<String, String> kept = new java.util.LinkedHashMap<>();
        if (containerBindings != null) {
            containerBindings.forEach((folder, container) -> {
                if (folder != null && !folder.isBlank() && container != null && !container.isBlank()) {
                    kept.put(folder.strip(), container.strip());
                }
            });
        }
        this.containerBindings = kept;
    }

    public void setRemoteExecution(boolean remoteExecution) {
        this.remoteExecution = remoteExecution;
    }

    public boolean isSnippetTabExpansion() {
        return snippetTabExpansion;
    }

    public void setSnippetTabExpansion(boolean snippetTabExpansion) {
        this.snippetTabExpansion = snippetTabExpansion;
    }

    public boolean isAutocompleteMermaid() {
        return autocompleteMermaid;
    }

    public void setAutocompleteMermaid(boolean autocompleteMermaid) {
        this.autocompleteMermaid = autocompleteMermaid;
    }

    public boolean isCompletionDoc() {
        return completionDoc;
    }

    public void setCompletionDoc(boolean completionDoc) {
        this.completionDoc = completionDoc;
    }

    public boolean isSemanticHighlight() {
        return semanticHighlight;
    }

    public void setSemanticHighlight(boolean semanticHighlight) {
        this.semanticHighlight = semanticHighlight;
    }

    public boolean isLspOnTypeFormatting() {
        return lspOnTypeFormatting;
    }

    public boolean isLspPasteImports() {
        return lspPasteImports;
    }

    public void setLspPasteImports(boolean lspPasteImports) {
        this.lspPasteImports = lspPasteImports;
    }

    public boolean isLspSmartSemicolon() {
        return lspSmartSemicolon;
    }

    public void setLspSmartSemicolon(boolean lspSmartSemicolon) {
        this.lspSmartSemicolon = lspSmartSemicolon;
    }

    public void setLspOnTypeFormatting(boolean lspOnTypeFormatting) {
        this.lspOnTypeFormatting = lspOnTypeFormatting;
    }

    public boolean isCodeLens() {
        return codeLens;
    }

    public void setCodeLens(boolean codeLens) {
        this.codeLens = codeLens;
    }

    public boolean isInlayHints() {
        return inlayHints;
    }

    public void setInlayHints(boolean inlayHints) {
        this.inlayHints = inlayHints;
    }

    public String getInlayHintMode() {
        return inlayHintMode == null ? "literals" : inlayHintMode;
    }

    public void setInlayHintMode(String inlayHintMode) {
        this.inlayHintMode = inlayHintMode;
    }

    public boolean isEditorConfigSupport() {
        return editorConfigSupport;
    }

    public void setEditorConfigSupport(boolean editorConfigSupport) {
        this.editorConfigSupport = editorConfigSupport;
    }

    public boolean isTodoHighlight() {
        return todoHighlight;
    }

    public void setTodoHighlight(boolean todoHighlight) {
        this.todoHighlight = todoHighlight;
    }

    public boolean isMarkdownLint() {
        return markdownLint;
    }

    public void setMarkdownLint(boolean markdownLint) {
        this.markdownLint = markdownLint;
    }

    public java.util.List<String> getMarkdownLintDisabledRules() {
        return markdownLintDisabledRules;
    }

    public void setMarkdownLintDisabledRules(java.util.List<String> markdownLintDisabledRules) {
        this.markdownLintDisabledRules =
                markdownLintDisabledRules == null ? new java.util.ArrayList<>() : markdownLintDisabledRules;
    }

    public boolean isMathSupport() {
        return mathSupport;
    }

    public void setMathSupport(boolean mathSupport) {
        this.mathSupport = mathSupport;
    }

    public java.util.List<com.editora.todo.TodoPattern> getTodoPatterns() {
        return todoPatterns == null ? java.util.List.of() : todoPatterns;
    }

    public void setTodoPatterns(java.util.List<com.editora.todo.TodoPattern> todoPatterns) {
        this.todoPatterns = todoPatterns;
    }

    public String getTodoGroupBy() {
        return todoGroupBy == null ? "FILE" : todoGroupBy;
    }

    public void setTodoGroupBy(String todoGroupBy) {
        this.todoGroupBy = todoGroupBy;
    }

    public String getTodoTagColor() {
        return todoTagColor;
    }

    public void setTodoTagColor(String todoTagColor) {
        this.todoTagColor = todoTagColor;
    }

    public String getTodoPriorityCriticalColor() {
        return todoPriorityCriticalColor;
    }

    public void setTodoPriorityCriticalColor(String c) {
        this.todoPriorityCriticalColor = c;
    }

    public String getTodoPriorityHighColor() {
        return todoPriorityHighColor;
    }

    public void setTodoPriorityHighColor(String c) {
        this.todoPriorityHighColor = c;
    }

    public String getTodoPriorityMediumColor() {
        return todoPriorityMediumColor;
    }

    public void setTodoPriorityMediumColor(String c) {
        this.todoPriorityMediumColor = c;
    }

    public String getTodoPriorityLowColor() {
        return todoPriorityLowColor;
    }

    public void setTodoPriorityLowColor(String c) {
        this.todoPriorityLowColor = c;
    }

    public java.util.List<com.editora.externaltool.ExternalTool> getExternalTools() {
        return externalTools == null ? java.util.List.of() : externalTools;
    }

    public void setExternalTools(java.util.List<com.editora.externaltool.ExternalTool> externalTools) {
        this.externalTools = externalTools;
    }

    public boolean isAbbrevMode() {
        return abbrevMode;
    }

    public void setAbbrevMode(boolean abbrevMode) {
        this.abbrevMode = abbrevMode;
    }

    public java.util.List<String> getToolbarLayout() {
        return toolbarLayout == null ? java.util.List.of() : toolbarLayout;
    }

    public void setToolbarLayout(java.util.List<String> toolbarLayout) {
        this.toolbarLayout =
                toolbarLayout == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(toolbarLayout);
    }

    public String getSpellLanguage() {
        return spellLanguage == null || spellLanguage.isBlank() ? "en_US" : spellLanguage;
    }

    public void setSpellLanguage(String spellLanguage) {
        this.spellLanguage = spellLanguage == null || spellLanguage.isBlank() ? "en_US" : spellLanguage;
    }

    public java.util.List<String> getSpellDisabledLanguages() {
        return spellDisabledLanguages;
    }

    /** Null is "nothing is switched off"; the defaults apply only to a file that has no such key at all. */
    public void setSpellDisabledLanguages(java.util.List<String> languages) {
        this.spellDisabledLanguages = languages == null ? new java.util.ArrayList<>() : languages;
    }

    /** The values of {@link #getMarkdownPreviewTheme()}: follow the app theme, light, dark. */
    public static final java.util.List<String> MARKDOWN_PREVIEW_THEMES = java.util.List.of("", "light", "dark");

    /** Default of {@link #getMarkdownAssetsFolder()}. */
    public static final String DEFAULT_MARKDOWN_ASSETS_FOLDER = "assets";

    /**
     * "" (follow app theme), "light", or "dark" — the Markdown preview's independent color theme. Any other
     * stored value reads as "" (a hand-edited file must not leave the preview without a theme).
     */
    public String getMarkdownPreviewTheme() {
        return markdownPreviewTheme != null && MARKDOWN_PREVIEW_THEMES.contains(markdownPreviewTheme)
                ? markdownPreviewTheme
                : "";
    }

    public void setMarkdownPreviewTheme(String markdownPreviewTheme) {
        this.markdownPreviewTheme = markdownPreviewTheme == null ? "" : markdownPreviewTheme;
    }

    public boolean isMarkdownFormatBar() {
        return markdownFormatBar;
    }

    public void setMarkdownFormatBar(boolean markdownFormatBar) {
        this.markdownFormatBar = markdownFormatBar;
    }

    /** The values of {@link #getMarkdownRemoteImagePolicy()}; the first is the default. */
    public static final java.util.List<String> REMOTE_IMAGE_POLICIES = java.util.List.of("always", "ask", "never");

    /**
     * Whether remote images may be fetched at all — everything but "never". A view of the policy, not a
     * stored key (it was one until schema 121).
     */
    @JsonIgnore
    public boolean isMarkdownRemoteImages() {
        return !"never".equals(getMarkdownRemoteImagePolicy());
    }

    /** The old on/off switch: off is "never", on is "always" (leaving "ask" alone when it is already on). */
    @JsonIgnore
    public void setMarkdownRemoteImages(boolean on) {
        if (on != isMarkdownRemoteImages()) {
            this.markdownRemoteImagePolicy = on ? "always" : "never";
        }
    }

    /** One of {@link #REMOTE_IMAGE_POLICIES}; a hand-edited value that is none of them reads as the default. */
    public String getMarkdownRemoteImagePolicy() {
        String p = markdownRemoteImagePolicy == null
                ? ""
                : markdownRemoteImagePolicy.strip().toLowerCase(java.util.Locale.ROOT);
        return REMOTE_IMAGE_POLICIES.contains(p) ? p : REMOTE_IMAGE_POLICIES.get(0);
    }

    public void setMarkdownRemoteImagePolicy(String markdownRemoteImagePolicy) {
        this.markdownRemoteImagePolicy = markdownRemoteImagePolicy;
    }

    public boolean isMarkdownWikiLinks() {
        return markdownWikiLinks;
    }

    public void setMarkdownWikiLinks(boolean markdownWikiLinks) {
        this.markdownWikiLinks = markdownWikiLinks;
    }

    public boolean isMarkdownTrashUnusedPastedImages() {
        return markdownTrashUnusedPastedImages;
    }

    public void setMarkdownTrashUnusedPastedImages(boolean markdownTrashUnusedPastedImages) {
        this.markdownTrashUnusedPastedImages = markdownTrashUnusedPastedImages;
    }

    /**
     * The folder pasted and dropped images go to, relative to the document, with {@code /} separators. A
     * value that is blank or would leave the document's folder (absolute, a drive, a {@code ..} segment)
     * reads as the default: an image is never written somewhere a typo or a hand-edited file points to.
     */
    public String getMarkdownAssetsFolder() {
        return normalizeAssetsFolder(markdownAssetsFolder);
    }

    public void setMarkdownAssetsFolder(String markdownAssetsFolder) {
        this.markdownAssetsFolder = normalizeAssetsFolder(markdownAssetsFolder);
    }

    /** See {@link #getMarkdownAssetsFolder()}. Pure. */
    public static String normalizeAssetsFolder(String folder) {
        if (folder == null) {
            return DEFAULT_MARKDOWN_ASSETS_FOLDER;
        }
        String f = folder.strip().replace('\\', '/');
        while (f.startsWith("./")) {
            f = f.substring(2);
        }
        while (f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        boolean escapes = f.isEmpty()
                || f.startsWith("/")
                || f.startsWith("~")
                || f.matches("^[A-Za-z]:.*")
                || f.chars().anyMatch(c -> c < 0x20 || "<>:\"|?*".indexOf(c) >= 0);
        for (String segment : f.split("/", -1)) {
            escapes |= segment.isBlank() || segment.equals("..") || segment.equals(".");
        }
        return escapes ? DEFAULT_MARKDOWN_ASSETS_FOLDER : f;
    }

    /** 1–6, and never deeper than {@link #getMarkdownTocMaxLevel()}. */
    public int getMarkdownTocMinLevel() {
        return Math.min(Math.clamp(markdownTocMinLevel, 1, 6), Math.clamp(markdownTocMaxLevel, 1, 6));
    }

    public void setMarkdownTocMinLevel(int markdownTocMinLevel) {
        this.markdownTocMinLevel = Math.clamp(markdownTocMinLevel, 1, 6);
    }

    /** 1–6, and never shallower than the stored minimum. */
    public int getMarkdownTocMaxLevel() {
        return Math.clamp(markdownTocMaxLevel, 1, 6);
    }

    public void setMarkdownTocMaxLevel(int markdownTocMaxLevel) {
        this.markdownTocMaxLevel = Math.clamp(markdownTocMaxLevel, 1, 6);
    }

    /** The values of {@link #getMarkdownDefaultViewMode()}. */
    public static final java.util.List<String> MARKDOWN_VIEW_MODES = java.util.List.of("editor", "split", "preview");

    /** The values of {@link #getMarkdownUpdateLinksOnRename()}. */
    public static final java.util.List<String> LINK_UPDATE_MODES = java.util.List.of("ask", "always", "never");

    /** The values of {@link #markdownLintSeverity}; the first is the default. */
    public static final java.util.List<String> LINT_SEVERITIES = java.util.List.of("warning", "error", "hint");

    /** Markdown-lint rules that are off until the user turns them on ({@link #markdownLintEnabledRules}). */
    public static final java.util.Set<String> MARKDOWN_LINT_DEFAULT_OFF = java.util.Set.of("MD013", "MD033");

    public static final String DEFAULT_MARKDOWN_IMAGE_NAME = "pasted-image";
    public static final int MIN_PREVIEW_FONT_SIZE = 8;
    public static final int MAX_PREVIEW_FONT_SIZE = 40;
    public static final int MIN_PREVIEW_WIDTH = 320;
    public static final int MAX_PREVIEW_WIDTH = 4000;

    /** "editor", "split" or "preview"; any other stored value reads as "editor". */
    public String getMarkdownDefaultViewMode() {
        return markdownDefaultViewMode != null && MARKDOWN_VIEW_MODES.contains(markdownDefaultViewMode)
                ? markdownDefaultViewMode
                : "editor";
    }

    public void setMarkdownDefaultViewMode(String markdownDefaultViewMode) {
        this.markdownDefaultViewMode = markdownDefaultViewMode;
    }

    /** 0 (the built-in size) or {@link #MIN_PREVIEW_FONT_SIZE}–{@link #MAX_PREVIEW_FONT_SIZE} px. */
    public int getMarkdownPreviewFontSize() {
        return markdownPreviewFontSize <= 0
                ? 0
                : Math.clamp(markdownPreviewFontSize, MIN_PREVIEW_FONT_SIZE, MAX_PREVIEW_FONT_SIZE);
    }

    public void setMarkdownPreviewFontSize(int markdownPreviewFontSize) {
        this.markdownPreviewFontSize = markdownPreviewFontSize;
    }

    /** 0 (the built-in width) or {@link #MIN_PREVIEW_WIDTH}–{@link #MAX_PREVIEW_WIDTH} px. */
    public int getMarkdownPreviewWidth() {
        return markdownPreviewWidth <= 0 ? 0 : Math.clamp(markdownPreviewWidth, MIN_PREVIEW_WIDTH, MAX_PREVIEW_WIDTH);
    }

    public void setMarkdownPreviewWidth(int markdownPreviewWidth) {
        this.markdownPreviewWidth = markdownPreviewWidth;
    }

    public boolean isMarkdownScrollSync() {
        return markdownScrollSync;
    }

    public void setMarkdownScrollSync(boolean markdownScrollSync) {
        this.markdownScrollSync = markdownScrollSync;
    }

    public boolean isMarkdownHideFrontMatter() {
        return markdownHideFrontMatter;
    }

    public void setMarkdownHideFrontMatter(boolean markdownHideFrontMatter) {
        this.markdownHideFrontMatter = markdownHideFrontMatter;
    }

    public boolean isMarkdownSmartTypography() {
        return markdownSmartTypography;
    }

    public void setMarkdownSmartTypography(boolean markdownSmartTypography) {
        this.markdownSmartTypography = markdownSmartTypography;
    }

    public boolean isMarkdownTocSkipTitle() {
        return markdownTocSkipTitle;
    }

    public void setMarkdownTocSkipTitle(boolean markdownTocSkipTitle) {
        this.markdownTocSkipTitle = markdownTocSkipTitle;
    }

    public boolean isMarkdownTocOrdered() {
        return markdownTocOrdered;
    }

    public void setMarkdownTocOrdered(boolean markdownTocOrdered) {
        this.markdownTocOrdered = markdownTocOrdered;
    }

    public boolean isMarkdownTocUpdateOnSave() {
        return markdownTocUpdateOnSave;
    }

    public void setMarkdownTocUpdateOnSave(boolean markdownTocUpdateOnSave) {
        this.markdownTocUpdateOnSave = markdownTocUpdateOnSave;
    }

    /**
     * The base file name (no extension, no folder) of a pasted image. A value that is blank or not a plain
     * file name — a path separator, a character a file system refuses, a leading dot — reads as the default.
     */
    public String getMarkdownImageNamePattern() {
        return normalizeImageName(markdownImageNamePattern);
    }

    public void setMarkdownImageNamePattern(String markdownImageNamePattern) {
        this.markdownImageNamePattern = normalizeImageName(markdownImageNamePattern);
    }

    /** See {@link #getMarkdownImageNamePattern()}. Pure. */
    public static String normalizeImageName(String name) {
        String n = name == null ? "" : name.strip();
        boolean bad = n.isEmpty()
                || n.length() > 80
                || n.startsWith(".")
                || n.endsWith(".")
                || n.chars().anyMatch(c -> c < 0x20 || "/\\<>:\"|?*".indexOf(c) >= 0);
        return bad ? DEFAULT_MARKDOWN_IMAGE_NAME : n;
    }

    public boolean isMarkdownImageAltPrompt() {
        return markdownImageAltPrompt;
    }

    public void setMarkdownImageAltPrompt(boolean markdownImageAltPrompt) {
        this.markdownImageAltPrompt = markdownImageAltPrompt;
    }

    public boolean isMarkdownLintFixOnSave() {
        return markdownLintFixOnSave;
    }

    public void setMarkdownLintFixOnSave(boolean markdownLintFixOnSave) {
        this.markdownLintFixOnSave = markdownLintFixOnSave;
    }

    public java.util.List<String> getMarkdownLintEnabledRules() {
        if (markdownLintEnabledRules == null) {
            markdownLintEnabledRules = new java.util.ArrayList<>();
        }
        return markdownLintEnabledRules;
    }

    public void setMarkdownLintEnabledRules(java.util.List<String> markdownLintEnabledRules) {
        this.markdownLintEnabledRules =
                markdownLintEnabledRules == null ? new java.util.ArrayList<>() : markdownLintEnabledRules;
    }

    public java.util.Map<String, String> getMarkdownLintSeverities() {
        if (markdownLintSeverities == null) {
            markdownLintSeverities = new java.util.LinkedHashMap<>();
        }
        return markdownLintSeverities;
    }

    public void setMarkdownLintSeverities(java.util.Map<String, String> markdownLintSeverities) {
        this.markdownLintSeverities =
                markdownLintSeverities == null ? new java.util.LinkedHashMap<>() : markdownLintSeverities;
    }

    /**
     * Whether the user's settings have Markdown-lint rule {@code code} on: a rule that is on by default
     * unless it is in the disabled list, one that is off by default ({@link #MARKDOWN_LINT_DEFAULT_OFF}) only
     * when it is in the enabled list. Codes compare without regard to case. (A project's own config is
     * applied on top of this by the linter.)
     */
    public boolean isMarkdownLintRuleOn(String code) {
        String c = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        java.util.function.Predicate<java.util.List<String>> lists =
                list -> list != null && list.stream().anyMatch(x -> x != null && c.equalsIgnoreCase(x.strip()));
        return MARKDOWN_LINT_DEFAULT_OFF.contains(c)
                ? lists.test(markdownLintEnabledRules)
                : !lists.test(getMarkdownLintDisabledRules());
    }

    /** Turns rule {@code code} on or off in whichever of the two lists governs it. */
    public void setMarkdownLintRuleOn(String code, boolean on) {
        String c = code.strip().toUpperCase(java.util.Locale.ROOT);
        boolean optIn = MARKDOWN_LINT_DEFAULT_OFF.contains(c);
        java.util.List<String> list =
                new java.util.ArrayList<>(optIn ? getMarkdownLintEnabledRules() : getMarkdownLintDisabledRules());
        list.removeIf(x -> x == null || c.equalsIgnoreCase(x.strip()));
        if (on == optIn) {
            list.add(c);
        }
        if (optIn) {
            setMarkdownLintEnabledRules(list);
        } else {
            setMarkdownLintDisabledRules(list);
        }
    }

    /** "warning" (the default), "error" or "hint" for rule {@code code}; an unknown stored value is "warning". */
    public String markdownLintSeverity(String code) {
        if (code != null && markdownLintSeverities != null) {
            for (java.util.Map.Entry<String, String> e : markdownLintSeverities.entrySet()) {
                if (e.getKey() != null
                        && code.strip().equalsIgnoreCase(e.getKey().strip())
                        && e.getValue() != null) {
                    String v = e.getValue().strip().toLowerCase(java.util.Locale.ROOT);
                    return LINT_SEVERITIES.contains(v) ? v : LINT_SEVERITIES.get(0);
                }
            }
        }
        return LINT_SEVERITIES.get(0);
    }

    /** Sets rule {@code code}'s severity; the default ("warning") is stored as no entry at all. */
    public void setMarkdownLintSeverity(String code, String severity) {
        String c = code.strip().toUpperCase(java.util.Locale.ROOT);
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>(getMarkdownLintSeverities());
        map.keySet().removeIf(k -> k == null || c.equalsIgnoreCase(k.strip()));
        String v = severity == null ? "" : severity.strip().toLowerCase(java.util.Locale.ROOT);
        if (LINT_SEVERITIES.contains(v) && !v.equals(LINT_SEVERITIES.get(0))) {
            map.put(c, v);
        }
        setMarkdownLintSeverities(map);
    }

    /** "ask", "always" or "never"; any other stored value reads as "ask". */
    public String getMarkdownUpdateLinksOnRename() {
        return markdownUpdateLinksOnRename != null && LINK_UPDATE_MODES.contains(markdownUpdateLinksOnRename)
                ? markdownUpdateLinksOnRename
                : "ask";
    }

    public void setMarkdownUpdateLinksOnRename(String markdownUpdateLinksOnRename) {
        this.markdownUpdateLinksOnRename = markdownUpdateLinksOnRename;
    }

    public boolean isTypewriterMode() {
        return typewriterMode;
    }

    public void setTypewriterMode(boolean typewriterMode) {
        this.typewriterMode = typewriterMode;
    }

    public boolean isStatusBarWordCount() {
        return statusBarWordCount;
    }

    public void setStatusBarWordCount(boolean statusBarWordCount) {
        this.statusBarWordCount = statusBarWordCount;
    }

    public boolean isHtmlExportEmbedImages() {
        return htmlExportEmbedImages;
    }

    public void setHtmlExportEmbedImages(boolean htmlExportEmbedImages) {
        this.htmlExportEmbedImages = htmlExportEmbedImages;
    }

    public boolean isHtmlExportHighlightCode() {
        return htmlExportHighlightCode;
    }

    public void setHtmlExportHighlightCode(boolean htmlExportHighlightCode) {
        this.htmlExportHighlightCode = htmlExportHighlightCode;
    }

    public boolean isMultiCaret() {
        return multiCaret;
    }

    public void setMultiCaret(boolean multiCaret) {
        this.multiCaret = multiCaret;
    }

    public boolean isCopyWithSyntaxHighlighting() {
        return copyWithSyntaxHighlighting;
    }

    public void setCopyWithSyntaxHighlighting(boolean copyWithSyntaxHighlighting) {
        this.copyWithSyntaxHighlighting = copyWithSyntaxHighlighting;
    }

    public boolean isCopyLineWhenNoSelection() {
        return copyLineWhenNoSelection;
    }

    public void setCopyLineWhenNoSelection(boolean copyLineWhenNoSelection) {
        this.copyLineWhenNoSelection = copyLineWhenNoSelection;
    }

    public boolean isUpdateCheck() {
        return updateCheck;
    }

    public void setUpdateCheck(boolean updateCheck) {
        this.updateCheck = updateCheck;
    }

    public long getLastUpdateCheckEpoch() {
        return lastUpdateCheckEpoch;
    }

    public void setLastUpdateCheckEpoch(long lastUpdateCheckEpoch) {
        this.lastUpdateCheckEpoch = lastUpdateCheckEpoch;
    }

    public String getDismissedUpdateVersion() {
        return dismissedUpdateVersion == null ? "" : dismissedUpdateVersion;
    }

    public void setDismissedUpdateVersion(String dismissedUpdateVersion) {
        this.dismissedUpdateVersion = dismissedUpdateVersion == null ? "" : dismissedUpdateVersion;
    }

    public boolean isShowMenuBar() {
        return showMenuBar;
    }

    public void setShowMenuBar(boolean showMenuBar) {
        this.showMenuBar = showMenuBar;
    }

    public boolean isShowToolbar() {
        return showToolbar;
    }

    public void setShowToolbar(boolean showToolbar) {
        this.showToolbar = showToolbar;
    }

    public boolean isShowStatusBar() {
        return showStatusBar;
    }

    public void setShowStatusBar(boolean showStatusBar) {
        this.showStatusBar = showStatusBar;
    }

    public boolean isShowTabBar() {
        return showTabBar;
    }

    public void setShowTabBar(boolean showTabBar) {
        this.showTabBar = showTabBar;
    }

    public boolean isShowBreadcrumb() {
        return showBreadcrumb;
    }

    public void setShowBreadcrumb(boolean showBreadcrumb) {
        this.showBreadcrumb = showBreadcrumb;
    }

    public boolean isShowToolStripe() {
        return showToolStripe;
    }

    public void setShowToolStripe(boolean showToolStripe) {
        this.showToolStripe = showToolStripe;
    }

    public boolean isSimpleMode() {
        return simpleMode;
    }

    public void setSimpleMode(boolean simpleMode) {
        this.simpleMode = simpleMode;
    }

    public String getAutoSave() {
        return autoSave;
    }

    public void setAutoSave(String autoSave) {
        this.autoSave = autoSave;
    }

    public int getAutoSaveDelayMillis() {
        return autoSaveDelayMillis;
    }

    public void setAutoSaveDelayMillis(int autoSaveDelayMillis) {
        this.autoSaveDelayMillis = autoSaveDelayMillis;
    }

    public boolean isProjectSupport() {
        return projectSupport;
    }

    public void setProjectSupport(boolean projectSupport) {
        this.projectSupport = projectSupport;
    }

    public boolean isProjectShowHidden() {
        return projectShowHidden;
    }

    public void setProjectShowHidden(boolean projectShowHidden) {
        this.projectShowHidden = projectShowHidden;
    }

    public boolean isGitSupport() {
        return gitSupport;
    }

    public void setGitSupport(boolean gitSupport) {
        this.gitSupport = gitSupport;
    }

    public boolean isGithubSupport() {
        return githubSupport;
    }

    public void setGithubSupport(boolean githubSupport) {
        this.githubSupport = githubSupport;
    }

    public String getGhPath() {
        return ghPath;
    }

    public void setGhPath(String ghPath) {
        this.ghPath = ghPath == null ? "" : ghPath;
    }

    public String getGitPath() {
        return gitPath;
    }

    public void setGitPath(String gitPath) {
        this.gitPath = gitPath == null ? "" : gitPath;
    }

    public String getGitPullMode() {
        return gitPullMode;
    }

    public void setGitPullMode(String gitPullMode) {
        this.gitPullMode = gitPullMode == null || gitPullMode.isBlank() ? "ff-only" : gitPullMode.strip();
    }

    public boolean isGitAutoFetch() {
        return gitAutoFetch;
    }

    public void setGitAutoFetch(boolean gitAutoFetch) {
        this.gitAutoFetch = gitAutoFetch;
    }

    public int getGitAutoFetchMinutes() {
        return gitAutoFetchMinutes;
    }

    public void setGitAutoFetchMinutes(int gitAutoFetchMinutes) {
        this.gitAutoFetchMinutes = Math.max(1, Math.min(MAX_GIT_AUTO_FETCH_MINUTES, gitAutoFetchMinutes));
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getSyncRepoUrl() {
        return syncRepoUrl;
    }

    public void setSyncRepoUrl(String syncRepoUrl) {
        this.syncRepoUrl = syncRepoUrl == null ? "" : syncRepoUrl.strip();
    }

    public String getSyncBranch() {
        return syncBranch;
    }

    public void setSyncBranch(String syncBranch) {
        this.syncBranch = syncBranch == null || syncBranch.isBlank() ? "main" : syncBranch.strip();
    }

    public boolean isSyncSnippets() {
        return syncSnippets;
    }

    public void setSyncSnippets(boolean syncSnippets) {
        this.syncSnippets = syncSnippets;
    }

    public boolean isSyncAbbreviations() {
        return syncAbbreviations;
    }

    public void setSyncAbbreviations(boolean syncAbbreviations) {
        this.syncAbbreviations = syncAbbreviations;
    }

    public boolean isSyncTemplates() {
        return syncTemplates;
    }

    public void setSyncTemplates(boolean syncTemplates) {
        this.syncTemplates = syncTemplates;
    }

    public boolean isSyncDictionary() {
        return syncDictionary;
    }

    public void setSyncDictionary(boolean syncDictionary) {
        this.syncDictionary = syncDictionary;
    }

    public boolean isSyncAuto() {
        return syncAuto;
    }

    public void setSyncAuto(boolean syncAuto) {
        this.syncAuto = syncAuto;
    }

    public int getSyncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    public void setSyncIntervalMinutes(int syncIntervalMinutes) {
        this.syncIntervalMinutes = Math.max(1, Math.min(MAX_GIT_AUTO_FETCH_MINUTES, syncIntervalMinutes));
    }

    /** A day: beyond that "automatically" means nothing. */
    public static final int MAX_GIT_AUTO_FETCH_MINUTES = 24 * 60;

    public boolean isGitBlameInline() {
        return gitBlameInline;
    }

    public void setGitBlameInline(boolean gitBlameInline) {
        this.gitBlameInline = gitBlameInline;
    }

    public boolean isLocalHistory() {
        return localHistory;
    }

    public void setLocalHistory(boolean localHistory) {
        this.localHistory = localHistory;
    }

    public boolean isCrashRecovery() {
        return crashRecovery;
    }

    public void setCrashRecovery(boolean crashRecovery) {
        this.crashRecovery = crashRecovery;
    }

    public int getHistoryMaxPerFile() {
        return historyMaxPerFile;
    }

    public void setHistoryMaxPerFile(int historyMaxPerFile) {
        this.historyMaxPerFile = historyMaxPerFile;
    }

    public int getHistoryMaxAgeDays() {
        return historyMaxAgeDays;
    }

    public void setHistoryMaxAgeDays(int historyMaxAgeDays) {
        this.historyMaxAgeDays = historyMaxAgeDays;
    }

    public int getHistoryMaxTotalMb() {
        return historyMaxTotalMb;
    }

    public void setHistoryMaxTotalMb(int historyMaxTotalMb) {
        this.historyMaxTotalMb = historyMaxTotalMb;
    }

    public boolean isPluginSupport() {
        return pluginSupport;
    }

    public void setPluginSupport(boolean pluginSupport) {
        this.pluginSupport = pluginSupport;
    }

    /** The plugin-registry index URL; falls back to {@link #DEFAULT_PLUGIN_REGISTRY} when blank. */
    /** The registry index URL in force: the configured one, or {@link #DEFAULT_PLUGIN_REGISTRY} when blank. */
    @JsonIgnore
    public String getPluginRegistryUrl() {
        return pluginRegistryUrl == null || pluginRegistryUrl.isBlank() ? DEFAULT_PLUGIN_REGISTRY : pluginRegistryUrl;
    }

    /** The persisted form: the URL as configured, blank meaning "use the built-in registry". */
    @JsonProperty("pluginRegistryUrl")
    public String getPluginRegistryUrlRaw() {
        return pluginRegistryUrl == null ? "" : pluginRegistryUrl;
    }

    /**
     * Sets the registry URL. The built-in URL is stored as blank ("follow the default"), whether it comes
     * from the Settings field (which shows the URL in force and hands it back) or from a file an earlier
     * build froze it into — otherwise such an install would keep the old address after the default moves.
     *
     * <p>Anything that is not blank and not an {@code https://} address with a host is refused here, for every
     * caller: the registry in use stays. The Settings field checked this itself, but the palette's "Plugins: Set
     * Registry URL…" saved {@code http://…} or plain text, which Browse then refused.
     */
    @JsonProperty("pluginRegistryUrl")
    public void setPluginRegistryUrl(String pluginRegistryUrl) {
        if (!isAcceptableRegistryUrl(pluginRegistryUrl)) {
            return;
        }
        this.pluginRegistryUrl = pluginRegistryUrl != null && DEFAULT_PLUGIN_REGISTRY.equals(pluginRegistryUrl.strip())
                ? ""
                : pluginRegistryUrl;
    }

    /** Whether {@link #setPluginRegistryUrl} takes {@code url}: blank (the default), or https with a host. */
    public static boolean isAcceptableRegistryUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String u = url.strip();
        if (!u.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            return false;
        }
        try {
            String host = java.net.URI.create(u).getHost();
            return host != null && !host.isBlank();
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
    }

    public boolean isPluginRequireSignature() {
        return pluginRequireSignature;
    }

    public void setPluginRequireSignature(boolean pluginRequireSignature) {
        this.pluginRequireSignature = pluginRequireSignature;
    }

    public boolean isMermaidSupport() {
        return mermaidSupport;
    }

    public void setMermaidSupport(boolean mermaidSupport) {
        this.mermaidSupport = mermaidSupport;
    }

    public String getMmdcPath() {
        return mmdcPath == null ? "" : mmdcPath;
    }

    public void setMmdcPath(String mmdcPath) {
        this.mmdcPath = mmdcPath == null ? "" : mmdcPath;
    }

    public String getMaidPath() {
        return maidPath == null ? "" : maidPath;
    }

    public void setMaidPath(String maidPath) {
        this.maidPath = maidPath == null ? "" : maidPath;
    }

    public boolean isDiagramSupport() {
        return diagramSupport;
    }

    public void setDiagramSupport(boolean diagramSupport) {
        this.diagramSupport = diagramSupport;
    }

    public String getDotPath() {
        return dotPath == null ? "" : dotPath;
    }

    public void setDotPath(String dotPath) {
        this.dotPath = dotPath == null ? "" : dotPath;
    }

    public String getPlantumlPath() {
        return plantumlPath == null ? "" : plantumlPath;
    }

    public void setPlantumlPath(String plantumlPath) {
        this.plantumlPath = plantumlPath == null ? "" : plantumlPath;
    }

    public boolean isTypstSupport() {
        return typstSupport;
    }

    public void setTypstSupport(boolean typstSupport) {
        this.typstSupport = typstSupport;
    }

    public String getTypstPath() {
        return typstPath == null ? "" : typstPath;
    }

    public void setTypstPath(String typstPath) {
        this.typstPath = typstPath == null ? "" : typstPath;
    }

    public boolean isMavenSupport() {
        return mavenSupport;
    }

    public void setMavenSupport(boolean mavenSupport) {
        this.mavenSupport = mavenSupport;
    }

    public boolean isTestRunner() {
        return testRunner;
    }

    public void setTestRunner(boolean testRunner) {
        this.testRunner = testRunner;
    }

    public String getMavenCommand() {
        return mavenCommand == null ? "" : mavenCommand;
    }

    public void setMavenCommand(String mavenCommand) {
        this.mavenCommand = mavenCommand == null ? "" : mavenCommand;
    }

    public String getMavenJdkHome() {
        return mavenJdkHome == null ? "" : mavenJdkHome;
    }

    public void setMavenJdkHome(String mavenJdkHome) {
        this.mavenJdkHome = mavenJdkHome == null ? "" : mavenJdkHome;
    }

    /** The catalog URL in force: the configured one, or {@link #DEFAULT_MAVEN_ARCHETYPE_CATALOG} when blank. */
    @JsonIgnore
    public String getMavenArchetypeCatalogUrl() {
        return mavenArchetypeCatalogUrl == null || mavenArchetypeCatalogUrl.isBlank()
                ? DEFAULT_MAVEN_ARCHETYPE_CATALOG
                : mavenArchetypeCatalogUrl;
    }

    /** The persisted form: the URL as configured, blank meaning "use the built-in catalog". */
    @JsonProperty("mavenArchetypeCatalogUrl")
    public String getMavenArchetypeCatalogUrlRaw() {
        return mavenArchetypeCatalogUrl == null ? "" : mavenArchetypeCatalogUrl;
    }

    /**
     * Sets the catalog URL. The built-in URL is stored as blank ("follow the default"), as for
     * {@link #setPluginRegistryUrl}: the Settings field shows the URL in force and hands it back.
     */
    @JsonProperty("mavenArchetypeCatalogUrl")
    public void setMavenArchetypeCatalogUrl(String mavenArchetypeCatalogUrl) {
        this.mavenArchetypeCatalogUrl = mavenArchetypeCatalogUrl == null
                        || DEFAULT_MAVEN_ARCHETYPE_CATALOG.equals(mavenArchetypeCatalogUrl.strip())
                ? ""
                : mavenArchetypeCatalogUrl;
    }

    public boolean isNpmSupport() {
        return npmSupport;
    }

    public void setNpmSupport(boolean npmSupport) {
        this.npmSupport = npmSupport;
    }

    public String getNpmCommand() {
        return npmCommand == null ? "" : npmCommand;
    }

    public void setNpmCommand(String npmCommand) {
        this.npmCommand = npmCommand == null ? "" : npmCommand;
    }

    public boolean isCargoSupport() {
        return cargoSupport;
    }

    public void setCargoSupport(boolean cargoSupport) {
        this.cargoSupport = cargoSupport;
    }

    public String getCargoCommand() {
        return cargoCommand == null ? "" : cargoCommand;
    }

    public void setCargoCommand(String cargoCommand) {
        this.cargoCommand = cargoCommand == null ? "" : cargoCommand;
    }

    public boolean isGoSupport() {
        return goSupport;
    }

    public void setGoSupport(boolean goSupport) {
        this.goSupport = goSupport;
    }

    public String getGoCommand() {
        return goCommand == null ? "" : goCommand;
    }

    public void setGoCommand(String goCommand) {
        this.goCommand = goCommand == null ? "" : goCommand;
    }

    public boolean isGradleSupport() {
        return gradleSupport;
    }

    public void setGradleSupport(boolean gradleSupport) {
        this.gradleSupport = gradleSupport;
    }

    public String getGradleCommand() {
        return gradleCommand == null ? "" : gradleCommand;
    }

    public void setGradleCommand(String gradleCommand) {
        this.gradleCommand = gradleCommand == null ? "" : gradleCommand;
    }

    public boolean isClojureCliSupport() {
        return clojureCliSupport;
    }

    public void setClojureCliSupport(boolean clojureCliSupport) {
        this.clojureCliSupport = clojureCliSupport;
    }

    public String getClojureCliCommand() {
        return clojureCliCommand == null ? "" : clojureCliCommand;
    }

    public void setClojureCliCommand(String clojureCliCommand) {
        this.clojureCliCommand = clojureCliCommand == null ? "" : clojureCliCommand;
    }

    public boolean isLeiningenSupport() {
        return leiningenSupport;
    }

    public void setLeiningenSupport(boolean leiningenSupport) {
        this.leiningenSupport = leiningenSupport;
    }

    public String getLeiningenCommand() {
        return leiningenCommand == null ? "" : leiningenCommand;
    }

    public void setLeiningenCommand(String leiningenCommand) {
        this.leiningenCommand = leiningenCommand == null ? "" : leiningenCommand;
    }

    public boolean isBabashkaSupport() {
        return babashkaSupport;
    }

    public void setBabashkaSupport(boolean babashkaSupport) {
        this.babashkaSupport = babashkaSupport;
    }

    public String getBabashkaCommand() {
        return babashkaCommand == null ? "" : babashkaCommand;
    }

    public void setBabashkaCommand(String babashkaCommand) {
        this.babashkaCommand = babashkaCommand == null ? "" : babashkaCommand;
    }

    /** gpg-agent's own pinentry asks for passphrases (Editora never sees them). */
    public static final String PASSPHRASE_PROMPT_AGENT = "agent";
    /** Editora asks, and hands the passphrase to gpg by loopback (it is never stored). */
    public static final String PASSPHRASE_PROMPT_EDITORA = "editora";

    public String getEncryptionGpgPath() {
        return encryptionGpgPath == null ? "" : encryptionGpgPath;
    }

    public void setEncryptionGpgPath(String encryptionGpgPath) {
        this.encryptionGpgPath = encryptionGpgPath == null ? "" : encryptionGpgPath.strip();
    }

    public boolean isEncryptionIncludeSelf() {
        return encryptionIncludeSelf;
    }

    public void setEncryptionIncludeSelf(boolean encryptionIncludeSelf) {
        this.encryptionIncludeSelf = encryptionIncludeSelf;
    }

    public String getEncryptionDefaultKey() {
        return encryptionDefaultKey == null ? "" : encryptionDefaultKey;
    }

    public void setEncryptionDefaultKey(String encryptionDefaultKey) {
        this.encryptionDefaultKey = encryptionDefaultKey == null ? "" : encryptionDefaultKey.strip();
    }

    public String getEncryptionPassphrasePrompt() {
        return PASSPHRASE_PROMPT_EDITORA.equals(encryptionPassphrasePrompt)
                ? PASSPHRASE_PROMPT_EDITORA
                : PASSPHRASE_PROMPT_AGENT;
    }

    public void setEncryptionPassphrasePrompt(String encryptionPassphrasePrompt) {
        this.encryptionPassphrasePrompt = PASSPHRASE_PROMPT_EDITORA.equals(encryptionPassphrasePrompt)
                ? PASSPHRASE_PROMPT_EDITORA
                : PASSPHRASE_PROMPT_AGENT;
    }

    public boolean isEncryptionLanguageServers() {
        return encryptionLanguageServers;
    }

    public void setEncryptionLanguageServers(boolean encryptionLanguageServers) {
        this.encryptionLanguageServers = encryptionLanguageServers;
    }

    public java.util.List<String> getEncryptionRecentRecipients() {
        return encryptionRecentRecipients == null ? java.util.List.of() : encryptionRecentRecipients;
    }

    public void setEncryptionRecentRecipients(java.util.List<String> recent) {
        this.encryptionRecentRecipients =
                recent == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(recent);
    }

    /** Puts {@code used} first in the recent-recipients list, keeping at most 20. */
    public void rememberEncryptionRecipients(java.util.List<String> used) {
        java.util.List<String> next = new java.util.ArrayList<>(used);
        for (String id : getEncryptionRecentRecipients()) {
            if (!next.contains(id)) {
                next.add(id);
            }
        }
        encryptionRecentRecipients = new java.util.ArrayList<>(next.subList(0, Math.min(20, next.size())));
    }

    public boolean isRipgrepSearch() {
        return ripgrepSearch;
    }

    public void setRipgrepSearch(boolean ripgrepSearch) {
        this.ripgrepSearch = ripgrepSearch;
    }

    public String getRipgrepCommand() {
        return ripgrepCommand == null ? "" : ripgrepCommand;
    }

    public void setRipgrepCommand(String ripgrepCommand) {
        this.ripgrepCommand = ripgrepCommand == null ? "" : ripgrepCommand;
    }

    public boolean isSearchRespectGitignore() {
        return searchRespectGitignore;
    }

    public void setSearchRespectGitignore(boolean searchRespectGitignore) {
        this.searchRespectGitignore = searchRespectGitignore;
    }

    public boolean isLspSupport() {
        return lspSupport;
    }

    public void setLspSupport(boolean lspSupport) {
        this.lspSupport = lspSupport;
    }

    public boolean isLspInstallPrompts() {
        return lspInstallPrompts;
    }

    public void setLspInstallPrompts(boolean lspInstallPrompts) {
        this.lspInstallPrompts = lspInstallPrompts;
    }

    public boolean isDebugSupport() {
        return debugSupport;
    }

    public void setDebugSupport(boolean debugSupport) {
        this.debugSupport = debugSupport;
    }

    public boolean isDebugStepFilters() {
        return debugStepFilters;
    }

    public void setDebugStepFilters(boolean debugStepFilters) {
        this.debugStepFilters = debugStepFilters;
    }

    public boolean isDebugShowStatics() {
        return debugShowStatics;
    }

    public void setDebugShowStatics(boolean debugShowStatics) {
        this.debugShowStatics = debugShowStatics;
    }

    public String getDebugExceptionTypes() {
        return debugExceptionTypes == null ? "" : debugExceptionTypes;
    }

    public void setDebugExceptionTypes(String debugExceptionTypes) {
        this.debugExceptionTypes = debugExceptionTypes == null ? "" : debugExceptionTypes.strip();
    }

    public boolean isDebugProgramConsole() {
        return debugProgramConsole;
    }

    public void setDebugProgramConsole(boolean debugProgramConsole) {
        this.debugProgramConsole = debugProgramConsole;
    }

    public String getDebugHotCodeReplace() {
        return debugHotCodeReplace == null || debugHotCodeReplace.isBlank() ? "auto" : debugHotCodeReplace;
    }

    public void setDebugHotCodeReplace(String debugHotCodeReplace) {
        this.debugHotCodeReplace = debugHotCodeReplace;
    }

    public String getTerminalShell() {
        return terminalShell == null ? "" : terminalShell;
    }

    public void setTerminalShell(String terminalShell) {
        this.terminalShell = terminalShell == null ? "" : terminalShell.strip();
    }

    /** Clamped on read too: a hand-edited million-line scrollback would exhaust memory at the next tab. */
    public int getTerminalScrollback() {
        return Math.clamp(terminalScrollback, 0, TERMINAL_SCROLLBACK_MAX);
    }

    public void setTerminalScrollback(int terminalScrollback) {
        this.terminalScrollback = Math.clamp(terminalScrollback, 0, TERMINAL_SCROLLBACK_MAX);
    }

    public int getTerminalFontSize() {
        return terminalFontSize <= 0 ? 0 : Math.clamp(terminalFontSize, 6, 72);
    }

    public void setTerminalFontSize(int terminalFontSize) {
        this.terminalFontSize = terminalFontSize <= 0 ? 0 : Math.clamp(terminalFontSize, 6, 72);
    }

    public String getTerminalCursor() {
        return switch (terminalCursor == null ? "" : terminalCursor) {
            case "underline", "bar" -> terminalCursor;
            default -> "block";
        };
    }

    public void setTerminalCursor(String terminalCursor) {
        this.terminalCursor = terminalCursor;
    }

    public boolean isTerminalBell() {
        return terminalBell;
    }

    public void setTerminalBell(boolean terminalBell) {
        this.terminalBell = terminalBell;
    }

    public boolean isTerminalConfirmClose() {
        return terminalConfirmClose;
    }

    public void setTerminalConfirmClose(boolean terminalConfirmClose) {
        this.terminalConfirmClose = terminalConfirmClose;
    }

    public boolean isTerminalShellIntegration() {
        return terminalShellIntegration;
    }

    public void setTerminalShellIntegration(boolean terminalShellIntegration) {
        this.terminalShellIntegration = terminalShellIntegration;
    }

    public boolean isTerminalTypeAhead() {
        return terminalTypeAhead;
    }

    public void setTerminalTypeAhead(boolean terminalTypeAhead) {
        this.terminalTypeAhead = terminalTypeAhead;
    }

    public boolean isDebugProgramInTerminal() {
        return debugProgramInTerminal;
    }

    public void setDebugProgramInTerminal(boolean debugProgramInTerminal) {
        this.debugProgramInTerminal = debugProgramInTerminal;
    }

    public boolean isTerminalUseExternal() {
        return terminalUseExternal;
    }

    public void setTerminalUseExternal(boolean terminalUseExternal) {
        this.terminalUseExternal = terminalUseExternal;
    }

    public boolean isTerminalMacOptionIsMeta() {
        return terminalMacOptionIsMeta;
    }

    public void setTerminalMacOptionIsMeta(boolean terminalMacOptionIsMeta) {
        this.terminalMacOptionIsMeta = terminalMacOptionIsMeta;
    }

    public boolean isTerminalConfirmMultilinePaste() {
        return terminalConfirmMultilinePaste;
    }

    public void setTerminalConfirmMultilinePaste(boolean terminalConfirmMultilinePaste) {
        this.terminalConfirmMultilinePaste = terminalConfirmMultilinePaste;
    }

    public java.util.List<String> getTerminalIdeCommands() {
        return terminalIdeCommands == null ? DEFAULT_TERMINAL_IDE_COMMANDS : terminalIdeCommands;
    }

    public void setTerminalIdeCommands(java.util.List<String> terminalIdeCommands) {
        this.terminalIdeCommands = terminalIdeCommands == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(terminalIdeCommands);
    }

    public String getJavaDebugPluginPath() {
        return javaDebugPluginPath == null ? "" : javaDebugPluginPath;
    }

    public void setJavaDebugPluginPath(String javaDebugPluginPath) {
        this.javaDebugPluginPath = javaDebugPluginPath == null ? "" : javaDebugPluginPath;
    }

    public boolean isPythonDebugEnabled() {
        return pythonDebugEnabled;
    }

    public void setPythonDebugEnabled(boolean pythonDebugEnabled) {
        this.pythonDebugEnabled = pythonDebugEnabled;
    }

    public String getPythonDebugCommand() {
        return pythonDebugCommand == null ? "" : pythonDebugCommand;
    }

    public void setPythonDebugCommand(String pythonDebugCommand) {
        this.pythonDebugCommand = pythonDebugCommand == null ? "" : pythonDebugCommand;
    }

    public boolean isJsDebugEnabled() {
        return jsDebugEnabled;
    }

    public void setJsDebugEnabled(boolean jsDebugEnabled) {
        this.jsDebugEnabled = jsDebugEnabled;
    }

    public String getJsDebugPath() {
        return jsDebugPath == null ? "" : jsDebugPath;
    }

    public void setJsDebugPath(String jsDebugPath) {
        this.jsDebugPath = jsDebugPath == null ? "" : jsDebugPath;
    }

    public String getJavaLspCommand() {
        return javaLspCommand == null ? "" : javaLspCommand;
    }

    public void setJavaLspCommand(String javaLspCommand) {
        this.javaLspCommand = javaLspCommand == null ? "" : javaLspCommand;
    }

    public String getTypescriptLspCommand() {
        return typescriptLspCommand == null ? "" : typescriptLspCommand;
    }

    public void setTypescriptLspCommand(String typescriptLspCommand) {
        this.typescriptLspCommand = typescriptLspCommand == null ? "" : typescriptLspCommand;
    }

    public boolean isJavaLspEnabled() {
        return javaLspEnabled;
    }

    public void setJavaLspEnabled(boolean javaLspEnabled) {
        this.javaLspEnabled = javaLspEnabled;
    }

    public boolean isTypescriptLspEnabled() {
        return typescriptLspEnabled;
    }

    public void setTypescriptLspEnabled(boolean typescriptLspEnabled) {
        this.typescriptLspEnabled = typescriptLspEnabled;
    }

    public String getPythonLspCommand() {
        return pythonLspCommand == null ? "" : pythonLspCommand;
    }

    public void setPythonLspCommand(String pythonLspCommand) {
        this.pythonLspCommand = pythonLspCommand == null ? "" : pythonLspCommand;
    }

    public boolean isPythonLspEnabled() {
        return pythonLspEnabled;
    }

    public void setPythonLspEnabled(boolean pythonLspEnabled) {
        this.pythonLspEnabled = pythonLspEnabled;
    }

    public String getXmlLspCommand() {
        return xmlLspCommand == null ? "" : xmlLspCommand;
    }

    public void setXmlLspCommand(String xmlLspCommand) {
        this.xmlLspCommand = xmlLspCommand == null ? "" : xmlLspCommand;
    }

    public boolean isXmlLspEnabled() {
        return xmlLspEnabled;
    }

    public void setXmlLspEnabled(boolean xmlLspEnabled) {
        this.xmlLspEnabled = xmlLspEnabled;
    }

    public String getJsonLspCommand() {
        return jsonLspCommand == null ? "" : jsonLspCommand;
    }

    public void setJsonLspCommand(String jsonLspCommand) {
        this.jsonLspCommand = jsonLspCommand == null ? "" : jsonLspCommand;
    }

    public boolean isJsonLspEnabled() {
        return jsonLspEnabled;
    }

    public void setJsonLspEnabled(boolean jsonLspEnabled) {
        this.jsonLspEnabled = jsonLspEnabled;
    }

    public String getBashLspCommand() {
        return bashLspCommand == null ? "" : bashLspCommand;
    }

    public void setBashLspCommand(String bashLspCommand) {
        this.bashLspCommand = bashLspCommand == null ? "" : bashLspCommand;
    }

    public boolean isBashLspEnabled() {
        return bashLspEnabled;
    }

    public void setBashLspEnabled(boolean bashLspEnabled) {
        this.bashLspEnabled = bashLspEnabled;
    }

    /** bash-language-server's own default for its background-analysis file cap. */
    public static final int BASH_BACKGROUND_ANALYSIS_DEFAULT = 500;

    /** The largest background-analysis cap accepted. */
    public static final int BASH_BACKGROUND_ANALYSIS_MAX = 100_000;

    /** The shfmt dialects bash-language-server accepts. */
    public static final java.util.List<String> BASH_SHFMT_DIALECTS =
            java.util.List.of("auto", "bash", "posix", "mksh", "bats");

    public String getBashShellcheckPath() {
        return bashShellcheckPath == null ? "" : bashShellcheckPath;
    }

    public void setBashShellcheckPath(String path) {
        this.bashShellcheckPath = path == null ? "" : path.strip();
    }

    public String getBashShellcheckArguments() {
        return bashShellcheckArguments == null ? "" : bashShellcheckArguments;
    }

    public void setBashShellcheckArguments(String arguments) {
        this.bashShellcheckArguments = arguments == null ? "" : arguments.strip();
    }

    public String getBashShfmtPath() {
        return bashShfmtPath == null ? "" : bashShfmtPath;
    }

    public void setBashShfmtPath(String path) {
        this.bashShfmtPath = path == null ? "" : path.strip();
    }

    public String getBashShfmtDialect() {
        return bashShfmtDialect != null && BASH_SHFMT_DIALECTS.contains(bashShfmtDialect) ? bashShfmtDialect : "auto";
    }

    public void setBashShfmtDialect(String dialect) {
        this.bashShfmtDialect = dialect != null && BASH_SHFMT_DIALECTS.contains(dialect) ? dialect : "auto";
    }

    public boolean isBashShfmtSimplify() {
        return bashShfmtSimplify;
    }

    public void setBashShfmtSimplify(boolean on) {
        this.bashShfmtSimplify = on;
    }

    public boolean isBashShfmtBinaryNextLine() {
        return bashShfmtBinaryNextLine;
    }

    public void setBashShfmtBinaryNextLine(boolean on) {
        this.bashShfmtBinaryNextLine = on;
    }

    public boolean isBashShfmtCaseIndent() {
        return bashShfmtCaseIndent;
    }

    public void setBashShfmtCaseIndent(boolean on) {
        this.bashShfmtCaseIndent = on;
    }

    public boolean isBashShfmtFuncNextLine() {
        return bashShfmtFuncNextLine;
    }

    public void setBashShfmtFuncNextLine(boolean on) {
        this.bashShfmtFuncNextLine = on;
    }

    public boolean isBashShfmtSpaceRedirects() {
        return bashShfmtSpaceRedirects;
    }

    public void setBashShfmtSpaceRedirects(boolean on) {
        this.bashShfmtSpaceRedirects = on;
    }

    public boolean isBashShfmtKeepPadding() {
        return bashShfmtKeepPadding;
    }

    public void setBashShfmtKeepPadding(boolean on) {
        this.bashShfmtKeepPadding = on;
    }

    public boolean isBashShfmtIgnoreEditorconfig() {
        return bashShfmtIgnoreEditorconfig;
    }

    public void setBashShfmtIgnoreEditorconfig(boolean on) {
        this.bashShfmtIgnoreEditorconfig = on;
    }

    public int getBashBackgroundAnalysisMaxFiles() {
        return Math.clamp(bashBackgroundAnalysisMaxFiles, 0, BASH_BACKGROUND_ANALYSIS_MAX);
    }

    public void setBashBackgroundAnalysisMaxFiles(int max) {
        this.bashBackgroundAnalysisMaxFiles = Math.clamp(max, 0, BASH_BACKGROUND_ANALYSIS_MAX);
    }

    public boolean isBashIncludeAllWorkspaceSymbols() {
        return bashIncludeAllWorkspaceSymbols;
    }

    public void setBashIncludeAllWorkspaceSymbols(boolean on) {
        this.bashIncludeAllWorkspaceSymbols = on;
    }

    public String getBashExplainshellEndpoint() {
        return bashExplainshellEndpoint == null ? "" : bashExplainshellEndpoint;
    }

    public void setBashExplainshellEndpoint(String endpoint) {
        this.bashExplainshellEndpoint = endpoint == null ? "" : endpoint.strip();
    }

    public String getYamlLspCommand() {
        return yamlLspCommand == null ? "" : yamlLspCommand;
    }

    public void setYamlLspCommand(String yamlLspCommand) {
        this.yamlLspCommand = yamlLspCommand == null ? "" : yamlLspCommand;
    }

    public boolean isYamlLspEnabled() {
        return yamlLspEnabled;
    }

    public void setYamlLspEnabled(boolean yamlLspEnabled) {
        this.yamlLspEnabled = yamlLspEnabled;
    }

    public String getGoLspCommand() {
        return goLspCommand == null ? "" : goLspCommand;
    }

    public void setGoLspCommand(String goLspCommand) {
        this.goLspCommand = goLspCommand == null ? "" : goLspCommand;
    }

    public boolean isGoLspEnabled() {
        return goLspEnabled;
    }

    public void setGoLspEnabled(boolean goLspEnabled) {
        this.goLspEnabled = goLspEnabled;
    }

    public String getRustLspCommand() {
        return rustLspCommand == null ? "" : rustLspCommand;
    }

    public void setRustLspCommand(String rustLspCommand) {
        this.rustLspCommand = rustLspCommand == null ? "" : rustLspCommand;
    }

    public boolean isRustLspEnabled() {
        return rustLspEnabled;
    }

    public void setRustLspEnabled(boolean rustLspEnabled) {
        this.rustLspEnabled = rustLspEnabled;
    }

    public String getPhpLspCommand() {
        return phpLspCommand == null ? "" : phpLspCommand;
    }

    public void setPhpLspCommand(String phpLspCommand) {
        this.phpLspCommand = phpLspCommand == null ? "" : phpLspCommand;
    }

    public boolean isPhpLspEnabled() {
        return phpLspEnabled;
    }

    public void setPhpLspEnabled(boolean phpLspEnabled) {
        this.phpLspEnabled = phpLspEnabled;
    }

    public String getRubyLspCommand() {
        return rubyLspCommand == null ? "" : rubyLspCommand;
    }

    public void setRubyLspCommand(String rubyLspCommand) {
        this.rubyLspCommand = rubyLspCommand == null ? "" : rubyLspCommand;
    }

    public boolean isRubyLspEnabled() {
        return rubyLspEnabled;
    }

    public void setRubyLspEnabled(boolean rubyLspEnabled) {
        this.rubyLspEnabled = rubyLspEnabled;
    }

    public String getClangdLspCommand() {
        return clangdLspCommand == null ? "" : clangdLspCommand;
    }

    public void setClangdLspCommand(String clangdLspCommand) {
        this.clangdLspCommand = clangdLspCommand == null ? "" : clangdLspCommand;
    }

    public boolean isClangdLspEnabled() {
        return clangdLspEnabled;
    }

    public void setClangdLspEnabled(boolean clangdLspEnabled) {
        this.clangdLspEnabled = clangdLspEnabled;
    }

    public String getHtmlLspCommand() {
        return htmlLspCommand == null ? "" : htmlLspCommand;
    }

    public void setHtmlLspCommand(String htmlLspCommand) {
        this.htmlLspCommand = htmlLspCommand == null ? "" : htmlLspCommand;
    }

    public boolean isHtmlLspEnabled() {
        return htmlLspEnabled;
    }

    public void setHtmlLspEnabled(boolean htmlLspEnabled) {
        this.htmlLspEnabled = htmlLspEnabled;
    }

    public String getCssLspCommand() {
        return cssLspCommand == null ? "" : cssLspCommand;
    }

    public void setCssLspCommand(String cssLspCommand) {
        this.cssLspCommand = cssLspCommand == null ? "" : cssLspCommand;
    }

    public boolean isCssLspEnabled() {
        return cssLspEnabled;
    }

    public void setCssLspEnabled(boolean cssLspEnabled) {
        this.cssLspEnabled = cssLspEnabled;
    }

    public String getKotlinLspCommand() {
        return kotlinLspCommand == null ? "" : kotlinLspCommand;
    }

    public void setKotlinLspCommand(String kotlinLspCommand) {
        this.kotlinLspCommand = kotlinLspCommand == null ? "" : kotlinLspCommand;
    }

    public boolean isKotlinLspEnabled() {
        return kotlinLspEnabled;
    }

    public void setKotlinLspEnabled(boolean kotlinLspEnabled) {
        this.kotlinLspEnabled = kotlinLspEnabled;
    }

    public String getGroovyLspCommand() {
        return groovyLspCommand == null ? "" : groovyLspCommand;
    }

    public void setGroovyLspCommand(String groovyLspCommand) {
        this.groovyLspCommand = groovyLspCommand == null ? "" : groovyLspCommand;
    }

    public boolean isGroovyLspEnabled() {
        return groovyLspEnabled;
    }

    public void setGroovyLspEnabled(boolean groovyLspEnabled) {
        this.groovyLspEnabled = groovyLspEnabled;
    }

    public String getClojureLspCommand() {
        return clojureLspCommand == null ? "" : clojureLspCommand;
    }

    public void setClojureLspCommand(String clojureLspCommand) {
        this.clojureLspCommand = clojureLspCommand == null ? "" : clojureLspCommand;
    }

    public boolean isClojureLspEnabled() {
        return clojureLspEnabled;
    }

    public void setClojureLspEnabled(boolean clojureLspEnabled) {
        this.clojureLspEnabled = clojureLspEnabled;
    }

    public String getLuaLspCommand() {
        return luaLspCommand == null ? "" : luaLspCommand;
    }

    public void setLuaLspCommand(String luaLspCommand) {
        this.luaLspCommand = luaLspCommand == null ? "" : luaLspCommand;
    }

    public boolean isLuaLspEnabled() {
        return luaLspEnabled;
    }

    public void setLuaLspEnabled(boolean luaLspEnabled) {
        this.luaLspEnabled = luaLspEnabled;
    }

    public String getDockerfileLspCommand() {
        return dockerfileLspCommand == null ? "" : dockerfileLspCommand;
    }

    public void setDockerfileLspCommand(String dockerfileLspCommand) {
        this.dockerfileLspCommand = dockerfileLspCommand == null ? "" : dockerfileLspCommand;
    }

    public boolean isDockerfileLspEnabled() {
        return dockerfileLspEnabled;
    }

    public void setDockerfileLspEnabled(boolean dockerfileLspEnabled) {
        this.dockerfileLspEnabled = dockerfileLspEnabled;
    }

    public String getSqlLspCommand() {
        return sqlLspCommand == null ? "" : sqlLspCommand;
    }

    public void setSqlLspCommand(String sqlLspCommand) {
        this.sqlLspCommand = sqlLspCommand == null ? "" : sqlLspCommand;
    }

    public boolean isSqlLspEnabled() {
        return sqlLspEnabled;
    }

    public void setSqlLspEnabled(boolean sqlLspEnabled) {
        this.sqlLspEnabled = sqlLspEnabled;
    }

    public String getTerraformLspCommand() {
        return terraformLspCommand == null ? "" : terraformLspCommand;
    }

    public void setTerraformLspCommand(String terraformLspCommand) {
        this.terraformLspCommand = terraformLspCommand == null ? "" : terraformLspCommand;
    }

    public boolean isTerraformLspEnabled() {
        return terraformLspEnabled;
    }

    public void setTerraformLspEnabled(boolean terraformLspEnabled) {
        this.terraformLspEnabled = terraformLspEnabled;
    }

    public String getTomlLspCommand() {
        return tomlLspCommand == null ? "" : tomlLspCommand;
    }

    public void setTomlLspCommand(String tomlLspCommand) {
        this.tomlLspCommand = tomlLspCommand == null ? "" : tomlLspCommand;
    }

    public boolean isTomlLspEnabled() {
        return tomlLspEnabled;
    }

    public void setTomlLspEnabled(boolean tomlLspEnabled) {
        this.tomlLspEnabled = tomlLspEnabled;
    }

    public String getCsharpLspCommand() {
        return csharpLspCommand == null ? "" : csharpLspCommand;
    }

    public void setCsharpLspCommand(String csharpLspCommand) {
        this.csharpLspCommand = csharpLspCommand == null ? "" : csharpLspCommand;
    }

    public boolean isCsharpLspEnabled() {
        return csharpLspEnabled;
    }

    public void setCsharpLspEnabled(boolean csharpLspEnabled) {
        this.csharpLspEnabled = csharpLspEnabled;
    }

    public String getTypstLspCommand() {
        return typstLspCommand == null ? "" : typstLspCommand;
    }

    public void setTypstLspCommand(String typstLspCommand) {
        this.typstLspCommand = typstLspCommand == null ? "" : typstLspCommand;
    }

    public boolean isTypstLspEnabled() {
        return typstLspEnabled;
    }

    public void setTypstLspEnabled(boolean typstLspEnabled) {
        this.typstLspEnabled = typstLspEnabled;
    }

    public String getAstroLspCommand() {
        return astroLspCommand == null ? "" : astroLspCommand;
    }

    public void setAstroLspCommand(String astroLspCommand) {
        this.astroLspCommand = astroLspCommand == null ? "" : astroLspCommand;
    }

    public boolean isAstroLspEnabled() {
        return astroLspEnabled;
    }

    public void setAstroLspEnabled(boolean astroLspEnabled) {
        this.astroLspEnabled = astroLspEnabled;
    }

    public String getMavenPomLspCommand() {
        return mavenPomLspCommand == null ? "" : mavenPomLspCommand;
    }

    public void setMavenPomLspCommand(String mavenPomLspCommand) {
        this.mavenPomLspCommand = mavenPomLspCommand == null ? "" : mavenPomLspCommand;
    }

    public boolean isMavenPomLspEnabled() {
        return mavenPomLspEnabled;
    }

    public void setMavenPomLspEnabled(boolean mavenPomLspEnabled) {
        this.mavenPomLspEnabled = mavenPomLspEnabled;
    }

    public boolean isPdfLineNumbers() {
        return pdfLineNumbers;
    }

    public void setPdfLineNumbers(boolean pdfLineNumbers) {
        this.pdfLineNumbers = pdfLineNumbers;
    }

    public boolean isPdfSyntaxHighlighting() {
        return pdfSyntaxHighlighting;
    }

    public void setPdfSyntaxHighlighting(boolean pdfSyntaxHighlighting) {
        this.pdfSyntaxHighlighting = pdfSyntaxHighlighting;
    }

    public boolean isPdfPageFooter() {
        return pdfPageFooter;
    }

    public void setPdfPageFooter(boolean pdfPageFooter) {
        this.pdfPageFooter = pdfPageFooter;
    }

    /** "letter" or "a4"; unknown values normalize to "letter". */
    public String getPdfPageSize() {
        return "a4".equalsIgnoreCase(pdfPageSize) ? "a4" : "letter";
    }

    public void setPdfPageSize(String pdfPageSize) {
        this.pdfPageSize = "a4".equalsIgnoreCase(pdfPageSize) ? "a4" : "letter";
    }

    /** The orientations a text PDF can have, default first. */
    public static final java.util.List<String> PDF_ORIENTATIONS = java.util.List.of("portrait", "landscape");
    /** The margin presets of a text PDF, default first. */
    public static final java.util.List<String> PDF_MARGINS = java.util.List.of("normal", "narrow", "wide");
    /** The point sizes offered for the source-text PDF. */
    public static final java.util.List<Integer> PDF_CODE_FONT_SIZES = java.util.List.of(7, 8, 9, 10, 11, 12);

    public static final int DEFAULT_PDF_CODE_FONT_SIZE = 9;

    /**
     * "portrait" or "landscape"; unknown values normalize to "portrait". Applies to the PDFs laid out as
     * text (source text, Markdown, CSV tables). Printing takes its orientation from Page Setup, and an
     * image or tree PDF turns each page to fit its picture.
     */
    public String getPdfOrientation() {
        return oneOf(PDF_ORIENTATIONS, pdfOrientation);
    }

    public void setPdfOrientation(String pdfOrientation) {
        this.pdfOrientation = oneOf(PDF_ORIENTATIONS, pdfOrientation);
    }

    /** "normal", "narrow" or "wide"; unknown values normalize to "normal". Text PDFs only. */
    public String getPdfMargins() {
        return oneOf(PDF_MARGINS, pdfMargins);
    }

    public void setPdfMargins(String pdfMargins) {
        this.pdfMargins = oneOf(PDF_MARGINS, pdfMargins);
    }

    /** One of {@link #PDF_CODE_FONT_SIZES}; any other value normalizes to 9. Source-text PDFs only. */
    public int getPdfCodeFontSize() {
        return PDF_CODE_FONT_SIZES.contains(pdfCodeFontSize) ? pdfCodeFontSize : DEFAULT_PDF_CODE_FONT_SIZE;
    }

    public void setPdfCodeFontSize(int pdfCodeFontSize) {
        this.pdfCodeFontSize =
                PDF_CODE_FONT_SIZES.contains(pdfCodeFontSize) ? pdfCodeFontSize : DEFAULT_PDF_CODE_FONT_SIZE;
    }

    /** {@code value} in the spelling of {@code allowed} when it is one of them, else the first (the default). */
    private static String oneOf(java.util.List<String> allowed, String value) {
        String v = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        return allowed.contains(v) ? v : allowed.get(0);
    }

    public Map<String, String> getKeybindings() {
        return keybindings;
    }

    public void setKeybindings(Map<String, String> keybindings) {
        this.keybindings = keybindings;
    }

    public Map<String, String> getKeybindingsMac() {
        return keybindingsMac;
    }

    public void setKeybindingsMac(Map<String, String> keybindingsMac) {
        this.keybindingsMac = keybindingsMac;
    }

    public Map<String, Map<String, String>> getKeymapKeybindings() {
        return keymapKeybindings;
    }

    public void setKeymapKeybindings(Map<String, Map<String, String>> keymapKeybindings) {
        this.keymapKeybindings = keymapKeybindings == null ? new LinkedHashMap<>() : keymapKeybindings;
    }

    public Map<String, Map<String, String>> getKeymapKeybindingsMac() {
        return keymapKeybindingsMac;
    }

    public void setKeymapKeybindingsMac(Map<String, Map<String, String>> keymapKeybindingsMac) {
        this.keymapKeybindingsMac = keymapKeybindingsMac == null ? new LinkedHashMap<>() : keymapKeybindingsMac;
    }

    /**
     * Changes the keymap, taking the key-binding overrides with it: the current ones (both platforms) are
     * parked under the keymap being left, and those made earlier in {@code next} become the active ones.
     * {@link #setKeymap} is the plain property setter (it is what loading a file calls) and moves nothing.
     */
    public void switchKeymap(String next) {
        if (java.util.Objects.equals(next, keymap)) {
            return;
        }
        keybindings = swapOverrides(keymapKeybindings, keymap, keybindings, next);
        keybindingsMac = swapOverrides(keymapKeybindingsMac, keymap, keybindingsMac, next);
        keymap = next;
    }

    private static Map<String, String> swapOverrides(
            Map<String, Map<String, String>> parked, String from, Map<String, String> active, String to) {
        if (active != null && !active.isEmpty()) {
            parked.put(String.valueOf(from), active);
        } else {
            parked.remove(String.valueOf(from));
        }
        Map<String, String> restored = parked.remove(String.valueOf(to));
        return restored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(restored);
    }

    /** The keybinding overrides for the running platform (Cmd map on macOS, Ctrl map elsewhere); never null. */
    public Map<String, String> keybindingsFor(boolean mac) {
        Map<String, String> m = mac ? keybindingsMac : keybindings;
        if (m == null) {
            m = new LinkedHashMap<>();
            setKeybindingsFor(mac, m);
        }
        return m;
    }

    /** Replaces the running platform's keybinding overrides (see {@link #keybindingsFor}). */
    public void setKeybindingsFor(boolean mac, Map<String, String> overrides) {
        if (mac) {
            this.keybindingsMac = overrides;
        } else {
            this.keybindings = overrides;
        }
    }
}
