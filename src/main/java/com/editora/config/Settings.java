package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** User-configurable settings, (de)serialized to {@code config.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    private String fontFamily = "monospace";
    private int fontSize = 14;
    private String theme = "dark";
    private int tabSize = 4;
    private String keymap = "emacs";

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

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
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

    public Map<String, String> getKeybindings() {
        return keybindings;
    }

    public void setKeybindings(Map<String, String> keybindings) {
        this.keybindings = keybindings;
    }
}
