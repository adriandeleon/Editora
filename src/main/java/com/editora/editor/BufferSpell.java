package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

import static com.editora.i18n.Messages.tr;

/**
 * One buffer's spell checking: the checker, whether it runs for this buffer's language, and everything that
 * acts on a misspelled word — the context-menu items and the keyboard commands (next/previous misspelling,
 * correct, add to the dictionary, ignore). {@link SpellCheckOverlay} only draws; this decides.
 *
 * <p>FX-thread only, except that suggestions are computed on a worker (see {@link #suggest}).
 */
public final class BufferSpell {

    /** A misspelled word: its text and absolute {@code [start, end)} offsets. */
    public record Hit(String word, int start, int end) {}

    /** How a search for the next or previous misspelling ended. */
    public enum Jump {
        FOUND,
        NONE,
        /** The document was too long to finish within {@link #SEARCH_BUDGET_NANOS}; nothing found so far. */
        GAVE_UP
    }

    /** How long the context menu waits for suggestions before opening with a placeholder (ms). */
    private static final long INLINE_WAIT_MS = 30;
    /** The longest a next/previous search may hold the FX thread. */
    private static final long SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private static final ExecutorService SUGGESTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spell-suggest");
        t.setDaemon(true);
        return t;
    });

    private final CodeArea area;
    private final SpellCheckOverlay overlay;
    private final Supplier<CodeArea> focusedView;
    private final Supplier<String> language;
    private final BooleanSupplier editable;
    private final BooleanSupplier largeFile;

    private SpellChecker checker;
    /** The master switch (Settings), before the per-language and large-file gates. */
    private boolean enabled;
    /** Language names spell check is switched off for (Settings); shared, never mutated here. */
    private Collection<String> disabledLanguages = Set.of();

    private String langId = SpellDictionaries.DEFAULT;
    /** A dictionary chosen for this buffer alone and kept only in memory (a buffer with no file yet). */
    private String languageOverride;

    private Set<String> userWords = new HashSet<>();
    private boolean userWordsEnabled = true;
    private boolean technicalEnabled = true;
    private Consumer<String> onAddToDictionary = w -> {};
    private Runnable onIgnore = () -> {};
    private Consumer<String> onDictionaryFailed = id -> {};
    /** Bumped per suggestion request, so an answer for a menu that has since been rebuilt is dropped. */
    private long suggestGen;

    BufferSpell(
            CodeArea area,
            SpellCheckOverlay overlay,
            Supplier<CodeArea> focusedView,
            Supplier<String> language,
            BooleanSupplier editable,
            BooleanSupplier largeFile) {
        this.area = area;
        this.overlay = overlay;
        this.focusedView = focusedView;
        this.language = language;
        this.editable = editable;
        this.largeFile = largeFile;
        newChecker();
        overlay.setMode(SpellMode.forLanguage(language.get()));
    }

    private void newChecker() {
        checker = new SpellChecker(langId, userWords);
        checker.setUserWordsEnabled(userWordsEnabled);
        checker.setTechnicalWordsEnabled(technicalEnabled);
        overlay.setChecker(checker);
    }

    // --- settings -----------------------------------------------------------------------------------

    /**
     * Applies the spell settings in one step. Every part is a no-op when it has not changed: applying the
     * view settings used to clear each buffer's memoized verdicts three times over, whatever had changed.
     */
    public void apply(
            String langId,
            boolean enabled,
            Collection<String> disabledLanguages,
            boolean personalDictionary,
            boolean technicalDictionary) {
        setLanguage(langId);
        this.disabledLanguages = disabledLanguages == null ? Set.of() : disabledLanguages;
        setEnabled(enabled);
        setUserDictionaryEnabled(personalDictionary);
        setTechnicalDictionaryEnabled(technicalDictionary);
    }

    /** The master switch for this buffer; whether anything is checked also depends on {@link #isActive}. */
    public void setEnabled(boolean on) {
        this.enabled = on;
        applyActive();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether spell check is switched off for this buffer's language (Settings → Spell Check). */
    public boolean isLanguageDisabled() {
        return disabledLanguages.contains(languageName());
    }

    private String languageName() {
        String name = language.get();
        return name == null ? LanguageRegistry.PLAINTEXT : name;
    }

    /** Whether this buffer is being checked: on, on for its language, and not a large file. */
    public boolean isActive() {
        return overlay.isActive();
    }

    /** Re-derives the mode and the on/off state after the buffer's language (or its size class) changed. */
    void languageChanged() {
        overlay.setMode(SpellMode.forLanguage(language.get()));
        applyActive();
    }

    /** The overlay is active only when enabled, enabled for this language, and not in large-file mode. */
    void applyActive() {
        boolean active = enabled && !isLanguageDisabled() && !largeFile.getAsBoolean();
        if (active && !overlay.isActive()) {
            SpellDictionaries.ensureBuilt(langId, this::dictionaryBuilt);
        }
        overlay.setActive(active);
    }

    /** Sets the dictionary language id (e.g. {@code en_US}); redraws when it is loaded. */
    public void setLanguage(String id) {
        if (id == null || id.equals(langId)) {
            return;
        }
        langId = id;
        checker.setLanguage(id, this::dictionaryBuilt);
        overlay.refresh();
    }

    /** The dictionary this buffer asked for finished building — or failed to, which is reported. */
    private void dictionaryBuilt() {
        overlay.refresh();
        if (overlay.isActive() && SpellDictionaries.failed(langId)) {
            onDictionaryFailed.accept(langId);
        }
    }

    /** Called with the language id when this buffer's dictionary could not be built (nothing is checked). */
    public void setOnDictionaryFailed(Consumer<String> callback) {
        onDictionaryFailed = callback == null ? id -> {} : callback;
    }

    public String getLanguage() {
        return langId;
    }

    /** The dictionary chosen for this buffer alone, or null when it follows the default. */
    public String getLanguageOverride() {
        return languageOverride;
    }

    public void setLanguageOverride(String id) {
        languageOverride = id;
    }

    /** Supplies the shared (persisted) user-dictionary word set; words in it are never flagged. */
    public void setUserWords(Set<String> words) {
        if (words == null || words == userWords) {
            return;
        }
        userWords = words;
        newChecker();
    }

    /** Enables/disables the personal dictionary (user words); off re-flags those words. */
    public void setUserDictionaryEnabled(boolean on) {
        if (userWordsEnabled != on) {
            userWordsEnabled = on;
            checker.setUserWordsEnabled(on);
            overlay.refresh();
        }
    }

    /** Enables/disables the bundled technical dictionary; off re-flags those terms. */
    public void setTechnicalDictionaryEnabled(boolean on) {
        if (technicalEnabled != on) {
            technicalEnabled = on;
            checker.setTechnicalWordsEnabled(on);
            overlay.refresh();
        }
    }

    /** Called with the word to persist when the user adds one to the dictionary. */
    public void setOnAddToDictionary(Consumer<String> callback) {
        onAddToDictionary = callback == null ? w -> {} : callback;
    }

    /** Called after a word was ignored for the session, so the other buffers can drop their squiggle too. */
    public void setOnIgnore(Runnable callback) {
        onIgnore = callback == null ? () -> {} : callback;
    }

    /** Drops this buffer's memoized verdicts and repaints: the user dictionary or the ignore set changed. */
    public void refresh() {
        overlay.refresh();
    }

    /** The font or the tab size changed: the squiggles are in new places, the verdicts are the same. */
    void geometryChanged() {
        overlay.invalidateGeometry();
    }

    // --- the word under the caret or the pointer ------------------------------------------------------

    /**
     * The misspelled word at document {@code offset} (touching it at either end counts), or null. It is the
     * word the overlay underlines there and nothing else: the menu used to offer corrections for a word
     * inside a Markdown code fence, which is not squiggled.
     */
    public Hit hitAt(int offset) {
        if (!overlay.isActive() || offset < 0 || offset > area.getLength()) {
            return null;
        }
        int paragraph =
                area.offsetToPosition(offset, TwoDimensional.Bias.Backward).getMajor();
        for (int[] span : overlay.misspellingsIn(paragraph)) {
            if (offset >= span[0] && offset <= span[1]) {
                return new Hit(area.getText(span[0], span[1]), span[0], span[1]);
            }
        }
        return null;
    }

    /** The misspelled word the caret is in or next to (in the view that has focus), or null. */
    public Hit hitAtCaret() {
        return hitAt(focusedView.get().getCaretPosition());
    }

    /**
     * Selects the next (or previous) misspelled word from the caret, wrapping round the document once.
     * Bounded: a long document whose words have never been looked up is abandoned after
     * {@link #SEARCH_BUDGET_NANOS} rather than holding the FX thread — what was looked up stays memoized, so
     * asking again gets further.
     */
    public Jump jump(boolean forward) {
        if (!overlay.isActive()) {
            return Jump.NONE;
        }
        CodeArea view = focusedView.get();
        int total = area.getParagraphs().size();
        var selection = view.getSelection();
        int from = forward ? selection.getEnd() : selection.getStart();
        int start = area.offsetToPosition(Math.min(from, area.getLength()), TwoDimensional.Bias.Backward)
                .getMajor();
        long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;
        // total + 1 lines: the caret's own line is visited twice, first for the words on the far side of the
        // caret and last, after wrapping, for those on the near side.
        for (int step = 0; step <= total; step++) {
            int p = Math.floorMod(start + (forward ? step : -step), total);
            int[] found = null;
            for (int[] span : overlay.misspellingsIn(p)) {
                boolean beyond = forward ? span[0] >= from : span[0] < from;
                if (step == 0 && !beyond || step == total && beyond) {
                    continue;
                }
                if (forward) {
                    found = span;
                    break;
                }
                found = span; // backward: the last one on the line that qualifies
            }
            if (found != null) {
                view.selectRange(found[0], found[1]);
                view.requestFollowCaret();
                return Jump.FOUND;
            }
            if ((step & 63) == 63 && System.nanoTime() > deadline) {
                return Jump.GAVE_UP;
            }
        }
        return Jump.NONE;
    }

    // --- acting on a word -------------------------------------------------------------------------

    /**
     * Computes suggestions for {@code hit} on a worker and hands them to {@code onResult} on the FX thread.
     * A search can take Hunspell's whole quarter-second limit, which is too long to spend inside a click.
     */
    public void suggest(Hit hit, Consumer<List<String>> onResult) {
        SpellChecker c = checker;
        CompletableFuture.supplyAsync(() -> c.suggest(hit.word()), SUGGESTER)
                .thenAccept(found -> Platform.runLater(() -> onResult.accept(found)));
    }

    /**
     * Replaces {@code hit} with {@code replacement}, if the buffer is editable and the word is still there
     * (suggestions arrive later than the click that asked for them). Returns whether it did.
     */
    public boolean replace(Hit hit, String replacement) {
        if (!editable.getAsBoolean()
                || hit.end() > area.getLength()
                || !hit.word().equals(area.getText(hit.start(), hit.end()))) {
            return false;
        }
        area.replaceText(hit.start(), hit.end(), replacement);
        return true;
    }

    /** Adds {@code word} to the personal dictionary (persisted by the controller) and repaints. */
    public void addToDictionary(String word) {
        // The stored form is the one the lookup uses: lower case, an ASCII apostrophe, no possessive. Storing
        // the raw text made this a no-op for "zzq’abc", and "Editora's" had to be added separately from
        // "Editora".
        String key = SpellChecker.canonical(word);
        if (key.isEmpty()) {
            return;
        }
        // Persist FIRST. The callback (ConfigManager.addUserWord) adds the word to the shared dictionary set
        // and writes dictionary.txt — but it only writes when the word is newly added to that set, and
        // userWords *is* that shared set. Adding here first would make the callback see the word as
        // already present and silently skip the file write (the word then works this session but never
        // persists). Let the callback add + persist; the local add below is a no-op when shared, and only
        // matters when no persist callback is wired.
        onAddToDictionary.accept(key);
        userWords.add(key);
        overlay.refresh();
    }

    /** Stops flagging {@code word} for the rest of the session, in every buffer and window. */
    public void ignore(String word) {
        if (checker.ignore(word)) {
            overlay.refresh();
            onIgnore.run(); // the ignore set is shared; the other buffers memoize their own verdicts
        }
    }

    // --- context menu -----------------------------------------------------------------------------

    /**
     * The spelling items for a right-click at {@code offset}: suggestions (each replaces the word), then
     * "Add to Dictionary" and "Ignore". Empty when the word there is not flagged.
     *
     * <p>Suggestions are computed off the FX thread. The menu waits {@link #INLINE_WAIT_MS} for them — most
     * arrive in that time and the menu opens complete — and otherwise opens with a placeholder row that is
     * replaced in {@code menuItems} (the open menu's own list) when they arrive.
     */
    List<MenuItem> menuItems(int offset, ObservableList<MenuItem> menuItems) {
        Hit hit = hitAt(offset);
        if (hit == null) {
            return List.of();
        }
        List<MenuItem> items = new ArrayList<>();
        long gen = ++suggestGen;
        SpellChecker c = checker;
        CompletableFuture<List<String>> search = CompletableFuture.supplyAsync(() -> c.suggest(hit.word()), SUGGESTER);
        List<String> ready = null;
        try {
            ready = search.get(INLINE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            // falls through to the placeholder
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ready = List.of();
        } catch (ExecutionException e) {
            ready = List.of();
        }
        if (ready != null) {
            items.addAll(suggestionItems(hit, ready));
        } else {
            MenuItem pending = new MenuItem(tr("editmenu.spell.searching"));
            pending.setGraphic(MenuIcons.spellcheck());
            pending.setDisable(true);
            items.add(pending);
            search.thenAccept(found -> Platform.runLater(() -> {
                int at = menuItems.indexOf(pending);
                if (gen == suggestGen && at >= 0) {
                    menuItems.remove(at);
                    menuItems.addAll(at, suggestionItems(hit, found));
                }
            }));
        }
        items.add(new SeparatorMenuItem());
        MenuItem add = new MenuItem(tr("editmenu.addToDictionary"));
        add.setGraphic(MenuIcons.add());
        add.setOnAction(e -> addToDictionary(hit.word()));
        MenuItem ignore = new MenuItem(tr("editmenu.ignore"));
        ignore.setGraphic(MenuIcons.block());
        ignore.setOnAction(e -> ignore(hit.word()));
        items.add(add);
        items.add(ignore);
        items.add(new SeparatorMenuItem());
        return items;
    }

    private List<MenuItem> suggestionItems(Hit hit, List<String> suggestions) {
        List<MenuItem> items = new ArrayList<>();
        if (suggestions.isEmpty()) {
            MenuItem none = new MenuItem(tr("editmenu.noSuggestions"));
            none.setGraphic(MenuIcons.spellcheck());
            none.setDisable(true);
            items.add(none);
            return items;
        }
        boolean canEdit = editable.getAsBoolean();
        for (String s : suggestions) {
            MenuItem mi = new MenuItem(s);
            mi.setGraphic(MenuIcons.spellcheck());
            mi.getStyleClass().add("spell-suggestion");
            mi.setDisable(!canEdit); // a read-only buffer: the item used to look live and do nothing
            mi.setOnAction(e -> replace(hit, s));
            items.add(mi);
        }
        return items;
    }
}
