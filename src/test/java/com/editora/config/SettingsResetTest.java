package com.editora.config;

import java.util.List;
import java.util.Map;

import com.editora.externaltool.ExternalTool;
import com.editora.todo.TodoPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → Advanced → "Reset to Defaults" must restore <em>every</em> preference. It used to be a
 * hand-written list of setters that had gone stale — 23 of 181 fields — so it silently left most of the
 * user's settings exactly as they were.
 */
class SettingsResetTest {

    @Test
    void resetRestoresSettingsTheOldHandWrittenListNeverTouched() {
        Settings s = new Settings();
        // A sample across the features added since the reset list was last updated.
        s.setAiSupport(true);
        s.setAiApiKey("sk-ant-api03-should-not-survive-a-reset");
        s.setLspSupport(true);
        s.setDebugSupport(true);
        s.setSimpleMode(true);
        s.setShowToolStripe(false);
        s.setKeymap("vscode");
        s.setUiLanguage("fr");
        s.setTodoHighlight(false);
        s.setMermaidSupport(false);
        s.setPluginSupport(true);
        s.setFillColumn(42);
        s.setUpdateCheck(false);
        s.setCsvPreview(false);

        Settings.resetToDefaults(s);

        Settings d = new Settings();
        assertEquals(d.isAiSupport(), s.isAiSupport());
        assertEquals("", s.getAiApiKey(), "a reset must not leave a credential behind");
        assertEquals(d.isLspSupport(), s.isLspSupport());
        assertEquals(d.isDebugSupport(), s.isDebugSupport());
        assertEquals(d.isSimpleMode(), s.isSimpleMode());
        assertEquals(d.isShowToolStripe(), s.isShowToolStripe());
        assertEquals(d.getKeymap(), s.getKeymap());
        assertEquals(d.getUiLanguage(), s.getUiLanguage());
        assertEquals(d.isTodoHighlight(), s.isTodoHighlight());
        assertEquals(d.isMermaidSupport(), s.isMermaidSupport());
        assertEquals(d.isPluginSupport(), s.isPluginSupport());
        assertEquals(d.getFillColumn(), s.getFillColumn());
        assertEquals(d.isUpdateCheck(), s.isUpdateCheck());
        assertEquals(d.isCsvPreview(), s.isCsvPreview());
    }

    @Test
    void resetStillRestoresTheFieldsTheOldListDidCover() {
        Settings s = new Settings();
        s.setFontSize(31);
        s.setTabSize(7);
        s.setTheme("Dracula");
        s.setShowMinimap(false);
        s.setSpellCheck(false);
        s.setShowToolbar(false);
        s.setEditorThemeUserSet(true);

        Settings.resetToDefaults(s);

        Settings d = new Settings();
        assertEquals(d.getFontSize(), s.getFontSize());
        assertEquals(d.getTabSize(), s.getTabSize());
        assertEquals(d.getTheme(), s.getTheme());
        assertEquals(d.isShowMinimap(), s.isShowMinimap());
        assertEquals(d.isSpellCheck(), s.isSpellCheck());
        assertEquals(d.isShowToolbar(), s.isShowToolbar());
        assertFalse(s.isEditorThemeUserSet());
    }

    @Test
    void resetPreservesTextZoomAndKeybindings() {
        // The two things the Advanced page doesn't own: the editor's Ctrl-+/- zoom, and the Keymaps page's
        // own "Reset all".
        Settings s = new Settings();
        s.setFontZoom(1.4);
        s.setKeybindings(Map.of("C-x C-s", "file.save"));

        Settings.resetToDefaults(s);

        assertEquals(1.4, s.getFontZoom());
        assertEquals(Map.of("C-x C-s", "file.save"), s.getKeybindings());
    }

    @Test
    void resetEmptiesUserBuiltListsRatherThanMergingThem() {
        // A list-valued setting must be REPLACED by the default, not merged into it.
        Settings s = new Settings();
        s.setExternalTools(List.of(new ExternalTool()));
        s.setTodoPatterns(List.of(new TodoPattern()));
        int defaultTodoPatterns = new Settings().getTodoPatterns().size();

        Settings.resetToDefaults(s);

        assertTrue(s.getExternalTools().isEmpty(), "external tools default to none");
        assertEquals(defaultTodoPatterns, s.getTodoPatterns().size(), "TODO patterns are replaced, not merged");
    }

    @Test
    void resetMutatesInPlaceBecauseEveryWindowHoldsThatInstance() {
        Settings s = new Settings();
        Settings sameRef = s;
        s.setFontSize(31);

        Settings.resetToDefaults(s);

        assertSame(s, sameRef);
        assertEquals(new Settings().getFontSize(), sameRef.getFontSize());
    }
}
