package com.editora.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.Settings;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The line of a problem report that says how the editor is set up: the choices that change what it shows and
 * which of its features run. A report without it leaves whoever reads it guessing the keymap, the theme and
 * whether the language servers were on.
 *
 * <p>It is a summary on purpose: choices and switches, never a path, an address, a command line or a key. The
 * only free text in it is the name of a theme, which a user theme chooses for itself; like the rest of the
 * report it goes through the {@link Redactor}.
 */
public final class SettingsSummary {

    private SettingsSummary() {}

    /** The summary of {@code s}: one {@code Settings: name=value, …} line. Pure. */
    public static String of(Settings s) {
        List<String> parts = new ArrayList<>();
        parts.add("theme=" + s.getTheme());
        parts.add("editorTheme=" + s.getEditorTheme());
        parts.add("language="
                + (s.getUiLanguage() == null || s.getUiLanguage().isBlank() ? "system" : s.getUiLanguage()));
        parts.add("keymap=" + s.getKeymap());
        parts.add("uiZoom=" + s.getUiZoom());
        parts.add("fontSize=" + s.getFontSize());
        parts.add("simpleMode=" + onOff(s.isSimpleMode()));
        parts.add("extendedWindow=" + onOff(s.isExtendedWindow()));
        parts.add("menuBar=" + onOff(s.isShowMenuBar()));
        parts.add("toolbar=" + onOff(s.isShowToolbar()));
        parts.add("statusBar=" + onOff(s.isShowStatusBar()));
        parts.add("tabBar=" + onOff(s.isShowTabBar()));
        parts.add("languageServers=" + onOff(s.isLspSupport()));
        parts.add("debugging=" + onOff(s.isDebugSupport()));
        parts.add("git=" + onOff(s.isGitSupport()));
        parts.add("github=" + onOff(s.isGithubSupport()));
        parts.add("plugins=" + onOff(s.isPluginSupport()));
        parts.add("ai=" + (s.isAiSupport() ? s.getAiProvider() : "off"));
        parts.add("agent=" + onOff(s.isAgentSupport()));
        parts.add("mcp=" + onOff(s.isMcpSupport()));
        parts.add("remoteExecution=" + onOff(s.isRemoteExecution()));
        parts.add("sync=" + onOff(s.isSyncEnabled()));
        parts.add("localHistory=" + onOff(s.isLocalHistory()));
        parts.add("spellCheck=" + onOff(s.isSpellCheck()));
        parts.add("updateCheck=" + onOff(s.isUpdateCheck()));
        return "Settings: " + String.join(", ", parts);
    }

    /**
     * The summary of the settings file, for a report made while the editor is not running ({@code --report}).
     * The file is only read: a missing one means the defaults, and one that cannot be read is said to be so,
     * which is itself what the report is for.
     */
    public static String ofFile(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return of(new Settings()) + " (no settings file: the defaults)";
        }
        try {
            ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Settings read = json.readValue(settingsFile.toFile(), Settings.class);
            return read == null ? "Settings: the settings file is empty" : of(read);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return "Settings: the settings file could not be read ("
                    + unreadable.getClass().getSimpleName() + ")";
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }
}
