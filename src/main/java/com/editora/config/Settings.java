package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** User preferences, (de)serialized to {@code settings.toml}. Session/state lives in {@link WorkspaceState}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    /** Current on-disk schema version of {@code settings.toml}; bump when the format changes (+ a migration). */
    public static final int SCHEMA_VERSION = 18;
    private int schemaVersion = SCHEMA_VERSION;

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
    private String keymap = "emacs";
    /** UI language code (e.g. {@code "es"}); empty = auto (system language if bundled, else English). */
    private String uiLanguage = "";
    private boolean showColumnRuler = true;
    private boolean highlightCurrentLine = true;
    private boolean showLineNumbers = true;
    private boolean showMinimap = true;
    private boolean showWhitespace = false;
    /** Personal Notes feature: off by default — hides the tool window, commands, gutter/highlight, and
     *  the editor "Add Personal Note" menu items until enabled. */
    private boolean notesSupport = false;
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
    private boolean showToolbar = true;
    private boolean showStatusBar = true;
    private boolean showTabBar = true;
    private boolean showBreadcrumb = false;
    /** Show the tool stripes (the side icon bars). UI only — tool windows still open via keys/palette.
     *  Hiding the stripe takes precedence over each tool window's individual visibility. */
    private boolean showToolStripe = true;
    /** Auto-save mode: "off" | "afterDelay" | "onFocusChange" (parsed leniently; unknown ⇒ off). */
    private String autoSave = "off";
    private int autoSaveDelayMillis = 1000;
    /** Projects feature: off by default — hides all project UI/commands until enabled. */
    private boolean projectSupport = false;
    /** Git integration: off by default — hides the status-bar VCS segment, Commit tool window, gutter
     *  change bars, and Git commands/keybindings until enabled. */
    private boolean gitSupport = false;
    /** Mermaid diagram support: off by default — needs the external mmdc (render/export) and maid
     *  (validation) CLIs. Renders .mmd files and ```mermaid Markdown blocks in the preview. */
    private boolean mermaidSupport = false;
    /** Path to the mmdc (mermaid-cli) executable; blank = resolve "mmdc" on PATH. */
    private String mmdcPath = "";
    /** Path to the maid (probelabs/maid linter) executable; blank = resolve "maid" on PATH. */
    private String maidPath = "";
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

    public void setTabSize(int tabSize) {
        this.tabSize = tabSize;
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
        return authorName == null || authorName.isBlank()
                ? System.getProperty("user.name", "") : authorName;
    }

    /** The raw configured author name (may be blank, meaning "follow the OS user"). */
    public String getAuthorNameRaw() {
        return authorName == null ? "" : authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName == null ? "" : authorName;
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

    public boolean isGitSupport() {
        return gitSupport;
    }

    public void setGitSupport(boolean gitSupport) {
        this.gitSupport = gitSupport;
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

    public boolean isLspSupport() {
        return lspSupport;
    }

    public void setLspSupport(boolean lspSupport) {
        this.lspSupport = lspSupport;
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
