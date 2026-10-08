package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.editora.config.PathKeys;
import com.editora.config.Settings;
import com.editora.editor.BufferSpell;
import com.editora.editor.EditorBuffer;
import com.editora.editor.SpellDictionaries;

import static com.editora.i18n.Messages.tr;

/**
 * The window's side of spell checking: which dictionary and which file types a buffer is checked with, and
 * the commands that act on the misspelled word at the caret. The checking itself is {@link BufferSpell}.
 */
final class SpellCoordinator {

    /** The picker row that clears a file's own dictionary choice. Never a language id. */
    private static final String USE_DEFAULT = "";

    private final EditorSettingsCoordinator.Host host;
    private final EditorSettingsCoordinator settings;
    /** Dictionary problems already reported in this window, so a session restore says each one once. */
    private final Set<String> reported = new HashSet<>();

    SpellCoordinator(EditorSettingsCoordinator.Host host, EditorSettingsCoordinator settings) {
        this.host = host;
        this.settings = settings;
    }

    // --- per-buffer setup ---------------------------------------------------------------------------

    /** Shares the user dictionary with a new buffer and routes its dictionary changes to every window. */
    void wire(EditorBuffer buffer) {
        BufferSpell spell = buffer.spell();
        spell.setUserWords(host.config().getUserDictionary());
        spell.setOnAddToDictionary(this::addUserWordAndRefreshAll);
        spell.setOnIgnore(this::refreshEveryWindow);
        spell.setOnDictionaryFailed(this::dictionaryFailed);
    }

    /** Applies the spell settings to {@code buffer}: its dictionary, the switches, the file-type list. */
    void apply(EditorBuffer buffer, Settings s) {
        buffer.spell()
                .apply(
                        languageFor(buffer),
                        s.isSpellCheck(),
                        s.getSpellDisabledLanguages(),
                        s.isPersonalDictionary(),
                        s.isTechnicalDictionary());
    }

    /** The dictionary for a buffer: its own choice if it has a valid one, else the default. */
    String languageFor(EditorBuffer buffer) {
        String own = override(buffer);
        return own != null ? own : defaultLanguage();
    }

    /** The buffer's own dictionary — remembered per file, or in memory for a buffer with no file — or null. */
    private String override(EditorBuffer buffer) {
        Path p = buffer.getPath();
        String own = p == null
                ? buffer.spell().getLanguageOverride()
                : host.config().getWorkspaceState().getSpellLanguages().get(p.toString());
        return own != null && SpellDictionaries.isAvailable(own) ? own : null;
    }

    /**
     * The default dictionary from Settings. An id no dictionary is bundled for — a hand-edited
     * {@code "spellLanguage": "de"} — used to switch checking off without a word; it now falls back to the
     * built-in default and says so once.
     */
    private String defaultLanguage() {
        String id = host.config().getSettings().getSpellLanguage();
        if (SpellDictionaries.isAvailable(id)) {
            return id;
        }
        if (reported.add("unknown:" + id)) {
            host.setStatus(tr("status.spell.languageInvalid", id, languageName(SpellDictionaries.DEFAULT)));
        }
        return SpellDictionaries.DEFAULT;
    }

    private void dictionaryFailed(String langId) {
        if (reported.add("failed:" + langId)) {
            host.setStatus(tr("status.spell.dictionaryFailed", languageName(langId)));
        }
    }

    // --- names --------------------------------------------------------------------------------------

    /** The localized name of a dictionary ("English (US)"); the id itself when it has none. */
    static String languageName(String id) {
        if (id == null || !SpellDictionaries.isAvailable(id)) {
            return String.valueOf(id);
        }
        return tr("spell.lang." + id);
    }

    /** A dictionary id as a language tag ({@code en-US}): short enough for the status bar. */
    static String languageTag(String id) {
        return id == null ? "" : id.replace('_', '-');
    }

    // --- language picker ------------------------------------------------------------------------------

    /**
     * Opens a picker to set the dictionary for the active file. It names the languages as Settings does,
     * opens on the one in force, says which that is, and leads with "use the default", which clears the
     * file's own choice — a per-file choice could be replaced before but never removed.
     */
    void chooseLanguage() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        String own = override(buffer);
        String def = defaultLanguage();
        List<String> rows = new ArrayList<>();
        rows.add(USE_DEFAULT);
        rows.addAll(SpellDictionaries.available());
        QuickOpen<String> picker = new QuickOpen<>(
                tr("palette.spellLanguage.title"),
                tr("palette.spellLanguage.prompt"),
                () -> rows,
                id -> id.equals(USE_DEFAULT)
                        ? tr("palette.spellLanguage.default", languageName(def))
                        : languageName(id),
                id -> id.equals(own == null ? USE_DEFAULT : own) ? tr("palette.spellLanguage.current") : "",
                id -> {
                    if (id != null) {
                        setLanguage(buffer, id.equals(USE_DEFAULT) ? null : id);
                    }
                });
        picker.setCurrentItem(() -> own == null ? USE_DEFAULT : own);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Gives {@code buffer} its own dictionary, or with a null {@code langId} returns it to the default. */
    void setLanguage(EditorBuffer buffer, String langId) {
        Path p = buffer.getPath();
        if (p == null) {
            buffer.spell().setLanguageOverride(langId); // kept for as long as the buffer is open
        } else if (langId == null) {
            host.config().getWorkspaceState().getSpellLanguages().remove(p.toString());
            host.requestSave();
        } else {
            host.config().getWorkspaceState().getSpellLanguages().put(p.toString(), langId);
            host.requestSave();
        }
        String now = languageFor(buffer);
        buffer.setSpellLanguage(now);
        refreshStatusBar();
        host.setStatus(tr(langId == null ? "status.spellLanguageDefault" : "status.spellLanguage", languageName(now)));
    }

    // --- per-file-type switch -------------------------------------------------------------------------

    /** Turns spell check on or off for the active buffer's file type, everywhere. */
    void toggleForLanguage() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        Settings s = host.config().getSettings();
        String language = buffer.getLanguage();
        List<String> disabled = new ArrayList<>(s.getSpellDisabledLanguages());
        boolean nowOn = disabled.remove(language);
        if (!nowOn) {
            disabled.add(language);
        }
        s.setSpellDisabledLanguages(disabled);
        host.requestSave();
        settings.applyViewSettingsToAllBuffers(s);
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncSpellFileTypes();
        }
        host.setStatus(tr("status.spell.forLanguage", language, tr(nowOn ? "common.on" : "common.off")));
    }

    // --- commands on misspelled words -----------------------------------------------------------------

    /** The active buffer if it is being spell-checked; otherwise says why not and returns null. */
    private EditorBuffer checkedBuffer() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return null;
        }
        if (!buffer.spell().isActive()) {
            host.setStatus(tr("status.spell.off"));
            return null;
        }
        return buffer;
    }

    /** The misspelled word at the caret of a checked buffer; otherwise says there is none and returns null. */
    private BufferSpell.Hit hitAtCaret(EditorBuffer buffer) {
        BufferSpell.Hit hit = buffer.spell().hitAtCaret();
        if (hit == null) {
            host.setStatus(tr("status.spell.noWordAtCaret"));
        }
        return hit;
    }

    void nextMisspelling() {
        jump(true);
    }

    void previousMisspelling() {
        jump(false);
    }

    private void jump(boolean forward) {
        EditorBuffer buffer = checkedBuffer();
        if (buffer == null) {
            return;
        }
        switch (buffer.spell().jump(forward)) {
            case NONE -> host.setStatus(tr("status.spell.none"));
            case GAVE_UP -> host.setStatus(tr("status.spell.searchLimit"));
            default -> {}
        }
    }

    /** Lists the suggestions for the misspelled word at the caret; choosing one replaces the word. */
    void correctWord() {
        EditorBuffer buffer = checkedBuffer();
        BufferSpell.Hit hit = buffer == null ? null : hitAtCaret(buffer);
        if (hit == null) {
            return;
        }
        if (!buffer.isEditable()) {
            host.setStatus(tr("status.lsp.readOnly"));
            return;
        }
        buffer.spell().suggest(hit, suggestions -> {
            if (suggestions.isEmpty()) {
                host.setStatus(tr("status.spell.noSuggestions", hit.word()));
                return;
            }
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("palette.spellCorrect.title", hit.word()),
                    tr("palette.spellCorrect.prompt"),
                    () -> suggestions,
                    s -> s,
                    s -> "",
                    s -> {
                        if (s != null) {
                            buffer.spell().replace(hit, s);
                        }
                    });
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.stage());
        });
    }

    void addWordToDictionary() {
        EditorBuffer buffer = checkedBuffer();
        BufferSpell.Hit hit = buffer == null ? null : hitAtCaret(buffer);
        if (hit != null) {
            buffer.spell().addToDictionary(hit.word());
            host.setStatus(tr("status.spell.added", hit.word()));
        }
    }

    void ignoreWord() {
        EditorBuffer buffer = checkedBuffer();
        BufferSpell.Hit hit = buffer == null ? null : hitAtCaret(buffer);
        if (hit != null) {
            buffer.spell().ignore(hit.word());
            host.setStatus(tr("status.spell.ignored", hit.word()));
        }
    }

    // --- the personal dictionary ----------------------------------------------------------------------

    /** Persists a word to the shared personal dictionary, then drops every open buffer's memoized spell
     *  verdicts — the word set is shared, but each buffer's overlay caches its own results, so "Add to
     *  Dictionary" in one tab used to leave the word squiggled in the others for the rest of the session. */
    void addUserWordAndRefreshAll(String word) {
        host.config().addUserWord(word);
        refreshEveryWindow();
    }

    /**
     * Re-runs the spell pass in EVERY window's tabs: the user dictionary and the ignore set are shared
     * app-wide, so another window's buffers otherwise keep a stale squiggle until they happen to apply a
     * setting (#443). Mirrors broadcastSettingsApplied/broadcastMacrosChanged.
     */
    private void refreshEveryWindow() {
        if (host.windowManager() != null) {
            host.windowManager().broadcastUserDictionaryChanged();
        } else {
            host.refreshSpellAllTabs();
        }
    }

    /** Re-reads {@code dictionary.txt} (edited by hand, or by another program) and applies it everywhere. */
    void reloadDictionary() {
        if (host.config().reloadUserDictionary()) {
            refreshEveryWindow();
        }
        host.setStatus(tr("status.spell.dictionaryReloaded"));
    }

    /** A file was saved in this window: when it is the personal dictionary, its words apply at once. */
    void fileSaved(Path file) {
        if (PathKeys.sameNormalized(file, host.config().getUserDictionaryFile())
                && host.config().reloadUserDictionary()) {
            refreshEveryWindow();
        }
    }

    private void refreshStatusBar() {
        if (host.statusBar() != null) {
            host.statusBar().refresh();
        }
    }
}
