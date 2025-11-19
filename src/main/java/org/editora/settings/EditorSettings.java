package org.editora.settings;

import java.io.*;
import java.util.Properties;

/**
 * Manages editor settings and preferences.
 * Settings are persisted to a properties file.
 */
public class EditorSettings {
    
    private static final String SETTINGS_FILE = "editora.properties";
    private static EditorSettings instance;
    
    private final Properties properties;
    
    // Default values
    private static final String DEFAULT_FONT_FAMILY = "Consolas";
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final boolean DEFAULT_WORD_WRAP = false;
    private static final String DEFAULT_TAB_SIZE = "4";
    private static final boolean DEFAULT_SHOW_LINE_NUMBERS = true;

    // Property keys
    public static final String FONT_FAMILY = "editor.font.family";
    public static final String FONT_SIZE = "editor.font.size";
    public static final String WORD_WRAP = "editor.wordwrap";
    public static final String TAB_SIZE = "editor.tabsize";
    public static final String SHOW_LINE_NUMBERS = "editor.show.line.numbers";
    
    private EditorSettings() {
        properties = new Properties();
        loadSettings();
    }
    
    public static EditorSettings getInstance() {
        if (instance == null) {
            instance = new EditorSettings();
        }
        return instance;
    }
    
    private void loadSettings() {
        File settingsFile = new File(SETTINGS_FILE);
        if (settingsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(settingsFile)) {
                properties.load(fis);
            } catch (IOException e) {
                System.err.println("Failed to load settings: " + e.getMessage());
            }
        }
        
        // Set defaults if not present
        properties.putIfAbsent(FONT_FAMILY, DEFAULT_FONT_FAMILY);
        properties.putIfAbsent(FONT_SIZE, String.valueOf(DEFAULT_FONT_SIZE));
        properties.putIfAbsent(WORD_WRAP, String.valueOf(DEFAULT_WORD_WRAP));
        properties.putIfAbsent(TAB_SIZE, DEFAULT_TAB_SIZE);
        properties.putIfAbsent(SHOW_LINE_NUMBERS, String.valueOf(DEFAULT_SHOW_LINE_NUMBERS));
    }
    
    public void saveSettings() {
        try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
            properties.store(fos, "Editora Settings");
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }
    
    public String getFontFamily() {
        return properties.getProperty(FONT_FAMILY, DEFAULT_FONT_FAMILY);
    }
    
    public void setFontFamily(String fontFamily) {
        properties.setProperty(FONT_FAMILY, fontFamily);
    }
    
    public int getFontSize() {
        return Integer.parseInt(properties.getProperty(FONT_SIZE, String.valueOf(DEFAULT_FONT_SIZE)));
    }
    
    public void setFontSize(int fontSize) {
        properties.setProperty(FONT_SIZE, String.valueOf(fontSize));
    }
    
    public boolean isWordWrap() {
        return Boolean.parseBoolean(properties.getProperty(WORD_WRAP, String.valueOf(DEFAULT_WORD_WRAP)));
    }
    
    public void setWordWrap(boolean wordWrap) {
        properties.setProperty(WORD_WRAP, String.valueOf(wordWrap));
    }
    
    public int getTabSize() {
        return Integer.parseInt(properties.getProperty(TAB_SIZE, DEFAULT_TAB_SIZE));
    }

    public void setTabSize(int tabSize) {
        properties.setProperty(TAB_SIZE, String.valueOf(tabSize));
    }

    public boolean isShowLineNumbers() {
        return Boolean.parseBoolean(properties.getProperty(SHOW_LINE_NUMBERS, String.valueOf(DEFAULT_SHOW_LINE_NUMBERS)));
    }

    public void setShowLineNumbers(boolean showLineNumbers) {
        properties.setProperty(SHOW_LINE_NUMBERS, String.valueOf(showLineNumbers));
    }

    public String getFontStyle() {
        return String.format("-fx-font-family: '%s', 'Monaco', 'Courier New', monospace; -fx-font-size: %dpx;",
            getFontFamily(), getFontSize());
    }
}
