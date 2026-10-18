package com.editora.ui;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What one window re-applies after a Settings change ({@link MainController#reapplyAfterSharedSettingsChange}):
 * only what the changed settings reach. Each live change in the Settings window — a switch, one spinner step —
 * re-applied everything to every window: the toolbar rebuilt, every chord tooltip, the ~25 feature gates of
 * {@link EditorSettingsCoordinator#applyViewSettingsToAllBuffers}, build-tool detection, the project's LSP
 * settings. When every setting that changed since this window last re-applied is a per-buffer view setting (line
 * numbers, the minimap, tab size, rulers, wrapping …), only the buffers' view is re-applied. Anything else — a
 * feature switch, the theme, the keymap, or a re-apply where no setting changed (a keymap or plugin reload, a
 * sync) — re-applies everything, as before.
 */
final class SettingsReapply {

    /**
     * Settings read only by {@link EditorSettingsCoordinator#applyViewSettings} (and by toggle commands, which
     * apply them themselves). Fonts, the editor theme and anything another coordinator reads are not here.
     */
    static final Set<String> VIEW_ONLY = Set.of(
            "typewriterMode",
            "rulers",
            "languageRulers",
            "showColumnRuler",
            "showIndentGuides",
            "showNoteIndicators",
            "highlightCurrentLine",
            "showLineNumbers",
            "showMinimap",
            "wordWrap",
            "wordWrapProse",
            "showWhitespace",
            "tabSize",
            "fillColumn",
            "autoFill",
            "csvRainbow",
            "bracketColors",
            "stickyScroll",
            "autoRenameTag",
            "autoCloseTags",
            "lspOnTypeFormatting",
            "lspPasteImports",
            "lspSmartSemicolon",
            "markdownSmartTypography",
            "markdownTocSkipTitle",
            "markdownTocOrdered",
            "markdownTocMinLevel",
            "markdownTocMaxLevel");

    private final MainController window;
    private final ObjectMapper json = new ObjectMapper();
    /** The settings as this window last re-applied them; null before the first re-apply. */
    private JsonNode last;

    SettingsReapply(MainController window) {
        this.window = window;
    }

    /** Re-applies {@code settings} to this window: its buffers' view only when nothing else changed. */
    void run(Settings settings) {
        JsonNode now = json.valueToTree(settings);
        Set<String> changed = last == null ? null : changedKeys(last, now);
        last = now;
        if (viewOnly(changed)) {
            window.editorSettings.applyPerBufferViewToVisible(settings); // hidden tabs catch up when shown
            window.terminalCoordinator.applySettings(); // the terminal follows the text zoom too
            window.statusBar.refresh(); // it shows the tab size
            return;
        }
        everything(settings);
    }

    /** Whether {@code changed} is known, not empty, and holds per-buffer view settings only. Pure. */
    static boolean viewOnly(Set<String> changed) {
        return changed != null && !changed.isEmpty() && VIEW_ONLY.containsAll(changed);
    }

    /** The top-level settings whose value differs between the two snapshots. Pure. */
    static Set<String> changedKeys(JsonNode before, JsonNode now) {
        Set<String> out = new HashSet<>();
        for (Iterator<String> it = now.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!now.get(key).equals(before.get(key))) {
                out.add(key);
            }
        }
        for (Iterator<String> it = before.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!now.has(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private void everything(Settings settings) {
        MainController w = window;
        w.lspCoordinator.reloadProjectSettings(); // first: the buffers below take a just-edited project file's values
        w.editorSettings.applyViewSettingsToAllBuffers(settings);
        w.updateBufferToolWindows(); // a feature toggle (Markdown lint, external tools, …) may re-gate a window
        // A build tool may have just been switched back on in Settings. Its cached detection was cleared the
        // last time refresh() ran while it was off, and every other apply path only re-derives the stripe from
        // that cache — so without a real re-detect the tool stays "not detected" (no stripe, no tasks tree)
        // until an unrelated tab switch or focus-regain happens to run one. The palette toggle command always
        // did this; the Settings checkbox did not.
        w.refreshBuildTools();
        // The keymap may have switched (it's shared); refresh every chord hint so none stays frozen to the
        // old keymap. Cheap (~25 tooltips + one palette/welcome relabel) and only on a settings/keymap apply.
        w.refreshToolbarTooltips();
        if (w.toolbarCoordinator != null) {
            w.toolbarCoordinator.rebuild(); // the toolbar layout is a shared setting — rebuild from it
        }
        if (w.palette != null) {
            w.palette.refreshBindings();
        }
        if (w.toolWindows != null) {
            w.toolWindows.refreshTooltips();
        }
        if (w.welcomePane != null) {
            w.welcomePane.refresh();
        }
        w.installPrompts.maybeOfferInstall(w.activeBuffer()); // the install-prompts toggle / a gate may have changed
        w.fileWorkflows.applyAdminSaveSupport(); // the admin-save toggle may have flipped
        w.runConfigurations.refreshRunConfigs(); // the Settings page edits the same list this selector shows
        w.remoteExecSettingsMayHaveChanged();
    }
}
