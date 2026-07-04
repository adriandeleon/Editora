package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** User preferences, (de)serialized to {@code settings.toml}. Session/state lives in {@link WorkspaceState}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    /** Current on-disk schema version of {@code settings.toml}; bump when the format changes (+ a migration). */
    public static final int SCHEMA_VERSION = 53;

    private int schemaVersion = SCHEMA_VERSION;

    /** Default plugin-registry index URL (a curated {@code index.json} on GitHub); user-overridable. */
    public static final String DEFAULT_PLUGIN_REGISTRY =
            "https://raw.githubusercontent.com/adriandeleon/editora-plugins/main/index.json";

    /** Author name used by file templates' {@code ${author}}; blank = the OS user (see getter). */
    private String authorName = "";

    private String fontFamily = "JetBrains Mono";
    private int fontSize = 14;
    /** Text zoom factor applied on top of {@link #fontSize} (1.0 = 100%). A quick, persisted zoom that
     *  is intentionally NOT shown in the Settings window; effective size = round(fontSize * fontZoom). */
    private double fontZoom = 1.0;

    private String theme = "Primer Light";
    /** Editor color theme (syntax + surface). Follows {@link #theme} until the user picks one. */
    private String editorTheme = "Primer Light";
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
    private boolean highlightCurrentLine = true;
    private boolean showLineNumbers = true;
    private boolean showMinimap = true;
    private boolean showWhitespace = false;
    private boolean wordWrap = false;
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
    /** Multiple cursors + Alt+drag column/box selection (RichTextFX fork). Transparent with one caret. */
    private boolean multiCaret = true;

    private boolean spellCheck = true;
    /** Honor the personal dictionary ({@code dictionary.txt}) during spell check; off re-flags those words. */
    private boolean personalDictionary = true;
    /** Honor the bundled technical-terms dictionary during spell check; off re-flags those terms. */
    private boolean technicalDictionary = true;
    /** Default spell-check dictionary language id (e.g. {@code en_US}); per-file overrides live in WorkspaceState. */
    private String spellLanguage = "en_US";
    /** Autocomplete master switch (gates all sources); on by default. */
    private boolean autocomplete = true;
    /** Per-source autocomplete toggles (gated by {@link #autocomplete}); on by default. */
    private boolean autocompleteProse = true;

    private boolean autocompleteSnippets = true;
    /** Mermaid keyword + snippet autocomplete in .mmd buffers; on by default but only effective when
     *  Mermaid support is enabled and the tools are detected. */
    private boolean autocompleteMermaid = true;
    /** Auto-show the IntelliJ-style documentation popup beside the completion list (Ctrl+Q toggles it). */
    private boolean completionDoc = true;
    /** Overlay LSP semantic tokens on the syntax highlight (resolved types/params/deprecated, …); on by
     *  default but only effective when LSP is enabled and the server advertises range semantic tokens. */
    private boolean semanticHighlight = true;
    /** Honor a project's {@code .editorconfig} (indent, EOL, charset, trim/final-newline, max line length). */
    private boolean editorConfigSupport = true;
    /** Highlight configured patterns (TODO/FIXME/…) in the editor + list them in the TODO tool window. */
    private boolean todoHighlight = true;
    /** The highlight patterns (regex + color); defaults to TODO (amber) + FIXME (red). */
    private java.util.List<com.editora.todo.TodoPattern> todoPatterns = com.editora.todo.TodoPatterns.defaults();

    private java.util.List<com.editora.externaltool.ExternalTool> externalTools = new java.util.ArrayList<>();
    /** Lint Markdown buffers (squiggles + the Markdown Lint tool window). */
    private boolean markdownLint = true;
    /** Markdown-lint rule codes ({@code MDxxx}) the user has turned off; empty = all rules enabled. */
    private java.util.List<String> markdownLintDisabledRules = new java.util.ArrayList<>();
    /** Render LaTeX math ({@code $…$} / {@code $$…$$}) in the Markdown preview/PDF. On by default. */
    private boolean mathSupport = true;

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
    /** Projects feature: off by default — hides all project UI/commands until enabled. */
    private boolean projectSupport = false;
    /** Show hidden (dot) files and folders in the Project tool window's tree + filter search. Off by default. */
    private boolean projectShowHidden = false;
    /** Git integration: on by default — the status-bar VCS segment, Commit tool window, gutter change bars,
     *  and Git commands/keybindings. Self-gates on detection: when {@code git} isn't on PATH it stays inert
     *  (so this is effectively "on when Git is installed"). */
    private boolean gitSupport = true;
    /** Inline git blame: off by default — when Git is on, paints a GitLens-style annotation
     *  ("author, N days ago • summary") after the caret line. */
    private boolean gitBlameInline = false;
    /** Local File History: on by default — silently snapshots local files on save/auto-save/external
     *  reload so prior versions can be browsed, diffed, and restored independently of VCS. */
    private boolean localHistory = true;
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
    private String pluginRegistryUrl = DEFAULT_PLUGIN_REGISTRY;
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
    /** Use ripgrep to accelerate Find in Files when it's detected on PATH (default on; falls back otherwise). */
    private boolean ripgrepSearch = true;
    /** Path/command for ripgrep; blank = resolve "rg" on PATH. */
    private String ripgrepCommand = "";
    /** Exclude files/folders matched by the project's {@code .gitignore} from Find in Files (default on). */
    private boolean searchRespectGitignore = true;
    /** HTTP Client support (run {@code .http} requests via the built-in JDK HTTP client): on by default. */
    private boolean httpClientSupport = true;
    /** The {@code ijhttp} command/path; blank = resolve {@code ijhttp} on PATH. */
    private String ijhttpCommand = "";
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
    /** Auto-rename the paired HTML/XML tag when a tag name is edited: on by default. */
    private boolean autoRenameTag = true;
    /** Auto-close HTML/XML tags (typing an open tag's {@code >} inserts the closer): on by default. */
    private boolean autoCloseTags = true;
    /** MCP server (expose live editor state + the command registry to an LLM agent over a loopback
     *  HTTP JSON-RPC endpoint, gated by a bearer token): off by default. */
    private boolean mcpSupport = false;
    /** Embedded AI agent (an ACP agent — e.g. Claude Code — in the AI Agent chat tool window): off by
     *  default. The agent is an external, user-installed CLI, never bundled. */
    private boolean agentSupport = false;
    /** The ACP agent command (tokenized, quote-aware); blank = {@code claude-code-acp} on PATH. */
    private String agentCommand = "";
    /** Java debugging (DAP) support: off by default. Layered on the Java LSP server (jdtls) + the
     *  Microsoft java-debug plugin; effective only when LSP is on, the java server is enabled/detected,
     *  and the plugin jar is found. */
    private boolean debugSupport = false;
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
    private String luaLspCommand = "";
    private String dockerfileLspCommand = "";
    private String sqlLspCommand = "";
    private String terraformLspCommand = "";
    private String tomlLspCommand = "";
    private String csharpLspCommand = "";
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
    private boolean luaLspEnabled = true;
    private boolean dockerfileLspEnabled = true;
    private boolean sqlLspEnabled = true;
    private boolean terraformLspEnabled = true;
    private boolean tomlLspEnabled = true;
    private boolean csharpLspEnabled = true;
    /** PDF export: include the line-number gutter (code PDFs). */
    private boolean pdfLineNumbers = true;
    /** PDF export: apply syntax-highlighting colors (code PDFs); off = plain monospace. */
    private boolean pdfSyntaxHighlighting = true;
    /** PDF export page size: "letter" (default) or "a4". */
    private String pdfPageSize = "letter";

    /** Optional per-binding overrides applied on top of the named keymap: chord -> command id. */
    private Map<String, String> keybindings = new LinkedHashMap<>();

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
        this.fontSize = fontSize;
    }

    public double getFontZoom() {
        return fontZoom;
    }

    public void setFontZoom(double fontZoom) {
        this.fontZoom = fontZoom;
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
        this.tabSize = tabSize;
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
        this.fillColumn = fillColumn;
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

    /** The configured author name, or the OS user name when blank (used by template {@code ${author}}). */
    public String getAuthorName() {
        return authorName == null || authorName.isBlank() ? System.getProperty("user.name", "") : authorName;
    }

    /** The raw configured author name (may be blank, meaning "follow the OS user"). */
    public String getAuthorNameRaw() {
        return authorName == null ? "" : authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName == null ? "" : authorName;
    }

    public boolean isHttpClientSupport() {
        return httpClientSupport;
    }

    public void setHttpClientSupport(boolean httpClientSupport) {
        this.httpClientSupport = httpClientSupport;
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

    public boolean isCsvRainbow() {
        return csvRainbow;
    }

    public void setCsvRainbow(boolean csvRainbow) {
        this.csvRainbow = csvRainbow;
    }

    public boolean isAutoRenameTag() {
        return autoRenameTag;
    }

    public void setAutoRenameTag(boolean autoRenameTag) {
        this.autoRenameTag = autoRenameTag;
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

    public String getIjhttpCommand() {
        return ijhttpCommand == null ? "" : ijhttpCommand;
    }

    public void setIjhttpCommand(String ijhttpCommand) {
        this.ijhttpCommand = ijhttpCommand == null ? "" : ijhttpCommand;
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

    public java.util.List<com.editora.externaltool.ExternalTool> getExternalTools() {
        return externalTools == null ? java.util.List.of() : externalTools;
    }

    public void setExternalTools(java.util.List<com.editora.externaltool.ExternalTool> externalTools) {
        this.externalTools = externalTools;
    }

    public String getSpellLanguage() {
        return spellLanguage == null || spellLanguage.isBlank() ? "en_US" : spellLanguage;
    }

    public void setSpellLanguage(String spellLanguage) {
        this.spellLanguage = spellLanguage == null || spellLanguage.isBlank() ? "en_US" : spellLanguage;
    }

    /** "" (follow app theme), "light", or "dark" — the Markdown preview's independent color theme. */
    public String getMarkdownPreviewTheme() {
        return markdownPreviewTheme == null ? "" : markdownPreviewTheme;
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

    public boolean isMultiCaret() {
        return multiCaret;
    }

    public void setMultiCaret(boolean multiCaret) {
        this.multiCaret = multiCaret;
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
    public String getPluginRegistryUrl() {
        return pluginRegistryUrl == null || pluginRegistryUrl.isBlank() ? DEFAULT_PLUGIN_REGISTRY : pluginRegistryUrl;
    }

    public void setPluginRegistryUrl(String pluginRegistryUrl) {
        this.pluginRegistryUrl = pluginRegistryUrl;
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

    /** "letter" (default) or "a4"; unknown values normalize to "letter". */
    public String getPdfPageSize() {
        return "a4".equalsIgnoreCase(pdfPageSize) ? "a4" : "letter";
    }

    public void setPdfPageSize(String pdfPageSize) {
        this.pdfPageSize = "a4".equalsIgnoreCase(pdfPageSize) ? "a4" : "letter";
    }

    public Map<String, String> getKeybindings() {
        return keybindings;
    }

    public void setKeybindings(Map<String, String> keybindings) {
        this.keybindings = keybindings;
    }
}
