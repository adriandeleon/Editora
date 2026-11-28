package com.editora.editor;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.text.LanguageRegistry;
import com.editora.text.Modeline;
import com.editora.text.Shebang;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.jspecify.annotations.Nullable;

/**
 * Which language a buffer is in and the grammar that highlights it: picked from the path or the display name, from
 * a shebang, or by the user, with the grammar loaded off the FX thread when it is not cached yet, and the
 * listeners told when it changes.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferLanguage {

    private final EditorBuffer b;

    BufferLanguage(EditorBuffer b) {
        this.b = b;
    }

    /** True once the user explicitly picked a language (status bar), so shebang detection won't fight it. */
    private boolean languageUserOverride;
    /** Bumped on every language/grammar change (FX thread only); drops a stale deferred grammar load. */
    long languageGen;
    /** Grammars {@link #applyLanguageDeferred} is still loading (FX thread only); see {@link #settlingForTest}. */
    private int grammarLoadsInFlight;

    private final List<Runnable> languageListeners = new java.util.ArrayList<>(2); // see addLanguageListener
    /** Associates this buffer with a file and selects the grammar and fold language from its extension. */
    void setPath(@Nullable Path path) {
        b.path = path;
        b.banners.writableOnDisk = path == null || path.getFileSystem() != java.nio.file.FileSystems.getDefault()
                ? b.banners.writableOnDisk
                : null;
        // The full path (not just the basename) so location-based rules resolve — e.g. ~/.ssh/config,
        // /etc/hosts, .git/config (see ConfigFileType). The registries reduce it to the basename for
        // ordinary extension lookups.
        String lookup = path == null ? null : path.toString();
        applyLanguageFor(lookup == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(lookup), lookup);
        b.runGutter.recomputeRun(); // a Save-As to a runnable file type can show the gutter Run glyph
    }
    /** Applies language {@code name} with the grammar {@code lookup} (a file name or path, or null) maps to. */
    private void applyLanguageFor(String name, @Nullable String lookup) {
        GrammarRegistry reg = GrammarRegistry.shared();
        if (lookup == null || !reg.hasGrammarFor(lookup)) {
            applyLanguage(name, null); // no bundled grammar for this type — nothing to load
        } else {
            IGrammar cached = reg.cachedForFileName(lookup);
            if (cached != null) {
                applyLanguage(name, cached); // already compiled this session — apply instantly (no flash)
            } else {
                applyLanguageDeferred(name, lookup); // first file of this type: compile off the FX thread
            }
        }
    }
    /**
     * Applies the language immediately (plain text, with fold/spell modes updated) but resolves its
     * not-yet-cached grammar <b>off the FX thread</b>, then re-highlights when it arrives — so opening the
     * first file of a given type during session restore doesn't block the UI on the Oniguruma grammar
     * compile. A {@link #languageGen} guard (bumped by every {@link #applyLanguage}) drops the result if a
     * later setPath / language override / dispose superseded this load. The brief unstyled flash matches
     * the existing deferred content-load behavior; cached files (every subsequent open) skip this path.
     */
    private void applyLanguageDeferred(String name, String fileName) {
        applyLanguage(name, null); // bumps languageGen; show plain text now
        long gen = languageGen;
        grammarLoadsInFlight++;
        EditorBuffer.HIGHLIGHT_POOL.execute(() -> {
            IGrammar g;
            try {
                g = GrammarRegistry.shared().forFileName(fileName);
            } catch (RuntimeException | LinkageError e) {
                Platform.runLater(() -> grammarLoadsInFlight--);
                throw e;
            }
            Platform.runLater(() -> {
                grammarLoadsInFlight--;
                if (gen != languageGen) {
                    return; // a newer language change superseded this load
                }
                b.grammar = g;
                b.highlighter.invalidateHighlighting();
                b.highlighter.applyHighlighting();
            });
        });
    }
    /**
     * Gives a still-unsaved buffer a suggested file name: it becomes the tab title and selects the
     * grammar/fold language by extension, while {@link EditorBuffer#path} stays null so the first Save prompts for a
     * location (Save-As). No-op once the buffer has a real path.
     */
    void setDisplayName(@Nullable String name) {
        b.displayName = name == null || name.isBlank() ? null : name;
        if (b.path == null && b.displayName != null) {
            applyLanguageFor(LanguageRegistry.forFileName(b.displayName), b.displayName);
        }
    }
    /**
     * Overrides the language/grammar regardless of the file's name (the status-bar picker); plain text turns
     * highlighting off. The Run glyph is re-decided here; the controller hears it via {@link #addLanguageListener}.
     */
    void setLanguageOverride(String name) {
        String resolved = name == null ? LanguageRegistry.plaintext() : name;
        languageUserOverride = true; // the user's pick wins; don't re-detect a shebang over it
        GrammarRegistry reg = GrammarRegistry.shared();
        IGrammar cached = reg.cachedForLanguageName(resolved);
        applyLanguage(resolved, cached); // bumps languageGen
        if (cached == null && reg.hasGrammarForLanguage(resolved)) {
            // A grammar not loaded yet is loaded off the FX thread (PERF-01, Pauta 6.2.0), highlighted when it lands.
            long gen = languageGen;
            reg.whenLanguageLoaded(resolved, g -> {
                if (gen == languageGen && g != null) {
                    b.grammar = g;
                    b.highlighter.invalidateHighlighting();
                    b.highlighter.applyHighlighting();
                }
            });
        }
        b.runGutter.recomputeRun(); // a script picked as Shell (or Python) gets its Run glyph now, not on the next edit
    }
    /** Runs {@code listener} on the FX thread each time the language changes after it is added (a picked
     *  language, a typed or loaded shebang, a Save As to another type): the status bar and LSP follow it. */
    void addLanguageListener(Runnable listener) {
        languageListeners.add(listener);
    }
    /**
     * Promotes a plain-text buffer to the language of its first-line shebang ({@code #!/usr/bin/env python3},
     * or {@code java --source N} for a compact source), via a synthetic {@code "shebang.<ext>"} name so it
     * behaves exactly like a file of that type. Never overrides a real extension or the user's pick.
     */
    void maybeApplyShebang() {
        int len = b.area.getLength(); // a shebang, else a vim/emacs modeline in the first or last lines
        String sniffed = languageUserOverride || b.path == null // a .mod/.sch/… is XML only if it reads so (C13)
                ? null
                : LanguageRegistry.sniffedXmlLanguage(b.path, b.language, () -> b.area.getText(0, Math.min(len, 1024)));
        if (sniffed != null && b.path != null) {
            applyLanguageFor(sniffed, "xml".equals(sniffed) ? b.path.toString() : null);
        }
        if (languageUserOverride || !LanguageRegistry.plaintext().equals(b.language)) {
            return; // a real extension or the user's pick already decided the language
        }
        Shebang.Result r = Modeline.detect(
                b.area.getText(0, Math.min(len, 1024)), b.area.getText(Math.max(0, len - 1024), len), b.path);
        if (r == null) {
            return;
        }
        b.shebangJavaSource = r.javaSource();
        String synthetic = "shebang." + r.extension();
        String lang = LanguageRegistry.forFileName(synthetic);
        if (lang.equals(b.language)) {
            return;
        }
        IGrammar cached = GrammarRegistry.shared().cachedForFileName(synthetic);
        if (cached != null) {
            applyLanguage(lang, cached); // grammar already compiled this session — apply instantly
        } else {
            applyLanguageDeferred(lang, synthetic); // first file of this type — compile off the FX thread
        }
    }
    /** Applies a language name + grammar: updates fold strategy and re-highlights. */
    private void applyLanguage(String name, @Nullable IGrammar g) {
        languageGen++; // supersede any in-flight deferred grammar load (see applyLanguageDeferred)
        boolean changed = !name.equals(b.language);
        b.language = name;
        b.grammar = g;
        b.folds.setLanguage(b.language);
        b.wrap.languageChanged(b.language); // prose wraps by one preference, code by another
        if (!b.rulerPlacement.languageRulers.isEmpty()) {
            b.rulerPlacement.markRulerInputsDirty(); // the language may have rulers of its own
        }
        // Which words are checked, which lines are code, and whether this language is checked at all all
        // follow the language. Must be pushed here, not only at construction: the constructor runs before
        // the language is known, which once left every Markdown buffer checking its ``` fenced code blocks.
        b.spell.languageChanged();
        // A Save As (or a language override) can turn a .http buffer into Markdown and back: the overlay they
        // share follows the new language — the {{variable}} marks stop, Markdown lint starts, or the reverse.
        b.setMarkdownLintEnabled(b.lint.markdownLintWanted);
        b.httpVariableMarks.update();
        b.highlighter.invalidateHighlighting(); // grammar changed with no text edit — re-tokenize the whole document
        b.highlighter.applyHighlighting();
        if (changed) {
            languageListeners.forEach(Runnable::run);
        }
    }
    /**
     * TEST SEAM — whether work the last edit or load set off is still to land: a settled-edit milestone (the
     * highlighting pass at 150 ms, the TODO marks, …) not fired yet, a grammar still loading, a highlighting pass
     * not back, or the spell check's code-lines scan owed. A test waits for this to turn false instead of for a
     * while. FX thread only.
     */
    boolean settlingForTest() {
        return b.settledEdits.pendingForTest()
                || grammarLoadsInFlight > 0
                || b.highlighter.passesInFlight > 0
                || b.spellOverlay.settlingForTest();
    }
}
