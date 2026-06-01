package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** User preferences, (de)serialized to {@code settings.toml}. Session/state lives in {@link WorkspaceState}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

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
    private boolean showColumnRuler = true;
    private boolean highlightCurrentLine = true;
    private boolean showLineNumbers = true;
    private boolean showMinimap = true;
    private boolean showWhitespace = false;
    private boolean showToolbar = true;
    private boolean showStatusBar = true;
    private boolean showTabBar = true;
    private boolean showBreadcrumb = false;
    /** Auto-save mode: "off" | "afterDelay" | "onFocusChange" (parsed leniently; unknown ⇒ off). */
    private String autoSave = "off";
    private int autoSaveDelayMillis = 1000;
    /** Projects feature: off by default — hides all project UI/commands until enabled. */
    private boolean projectSupport = false;

    /** Optional per-binding overrides applied on top of the named keymap: chord -> command id. */
    private Map<String, String> keybindings = new LinkedHashMap<>();

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

    public Map<String, String> getKeybindings() {
        return keybindings;
    }

    public void setKeybindings(Map<String, String> keybindings) {
        this.keybindings = keybindings;
    }
}
