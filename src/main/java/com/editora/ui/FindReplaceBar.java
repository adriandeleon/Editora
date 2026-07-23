package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import com.editora.editops.PreserveCase;
import com.editora.editor.EditorBuffer;
import com.editora.editor.NoteAnchors;
import com.editora.editor.SearchMatcher;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.Subscription;

import static com.editora.i18n.Messages.tr;

/**
 * A non-modal find/replace bar operating on the currently active {@link EditorBuffer}. Searches
 * incrementally (as you type, debounced), highlights <b>all</b> matches via the buffer's search
 * overlay, supports case-sensitive / regex / whole-word, and shows a "{n} of {total}" count.
 */
public class FindReplaceBar extends HBox {

    private final Supplier<EditorBuffer> activeBuffer;
    private final Consumer<String> status;

    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox caseSensitive = new CheckBox("Aa");
    private final CheckBox regex = new CheckBox(".*");
    private final CheckBox wholeWord = new CheckBox("W");
    private final CheckBox preserveCase = new CheckBox("AB");
    private final CheckBox inSelection = new CheckBox("Sel");
    private final Label countLabel = new Label();

    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    /** Debounced re-highlight after the buffer is edited while the bar is open (separate from the query
     *  debounce so an edit re-highlights without moving the caret/selection). */
    private final PauseTransition editDebounce = new PauseTransition(Duration.millis(150));
    /** Subscription to the searched buffer's text changes (live while the bar is shown); null when hidden. */
    private Subscription textSub;

    private List<int[]> matches = List.of();
    private int activeIndex = -1;
    private int searchAnchor; // caret offset when the search started — incremental jumps anchor here

    /**
     * Find-in-selection scope as {@code [scopeStart, scopeEnd)}, or -1/-1 for the whole document. Kept
     * accurate across edits (ours and the user's) by {@link NoteAnchors#shiftRange} in the text-change
     * subscription, so the scope tracks its content rather than freezing to stale offsets.
     */
    private int scopeStart = -1;

    private int scopeEnd = -1;

    public FindReplaceBar(Supplier<EditorBuffer> activeBuffer, Consumer<String> status) {
        this.activeBuffer = activeBuffer;
        this.status = status;
        getStyleClass().add("find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);
        build();
    }

    private void build() {
        findField.setPromptText(tr("find.prompt"));
        replaceField.setPromptText(tr("find.replacePrompt"));
        wholeWord.setTooltip(new Tooltip(tr("find.wholeWord")));
        preserveCase.setTooltip(new Tooltip(tr("find.preserveCase")));
        inSelection.setTooltip(new Tooltip(tr("find.inSelection")));
        countLabel.getStyleClass().add("find-count");

        Button next = new Button(tr("find.next"));
        Button prev = new Button(tr("find.prev"));
        Button replace = new Button(tr("find.replace"));
        Button replaceAll = new Button(tr("find.all"));
        Button close = new Button("✕");
        // Trailing clear ("✕") buttons inside each text field (shown only while the field has text).
        Button findClear = ClearableField.clearButton(findField);
        Button replaceClear = ClearableField.clearButton(replaceField);

        next.setOnAction(e -> findNext());
        prev.setOnAction(e -> findPrevious());
        replace.setOnAction(e -> replaceCurrent());
        replaceAll.setOnAction(e -> replaceAll());
        close.setOnAction(e -> hideBar());

        // Disable everything that acts on the find query while the Find field is empty — navigation
        // (Prev/Next), replace (Replace/All), and the match-option toggles (case/regex/whole-word) all
        // need something to search for.
        javafx.beans.binding.BooleanBinding noQuery = findField.textProperty().isEmpty();
        prev.disableProperty().bind(noQuery);
        next.disableProperty().bind(noQuery);
        replace.disableProperty().bind(noQuery);
        replaceAll.disableProperty().bind(noQuery);
        caseSensitive.disableProperty().bind(noQuery);
        regex.disableProperty().bind(noQuery);
        wholeWord.disableProperty().bind(noQuery);
        preserveCase.disableProperty().bind(noQuery);
        // "Sel" is a scope control rather than a match option, so it stays usable with an empty query.

        findField.setOnAction(e -> findNext());
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
            }
        });
        // Incremental: re-search (debounced) on query or option changes.
        findField.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        caseSensitive.selectedProperty().addListener((o, a, b) -> recompute());
        regex.selectedProperty().addListener((o, a, b) -> recompute());
        wholeWord.selectedProperty().addListener((o, a, b) -> recompute());
        // Preserve-case only affects the replacement text, so it needs no re-search.
        inSelection.selectedProperty().addListener((o, a, b) -> onInSelectionToggled(b));
        debounce.setOnFinished(e -> recompute());
        // A buffer edit while the bar is open re-runs the search so the highlights track the new text,
        // but without selecting/scrolling to a match (that would fight the user's typing).
        editDebounce.setOnFinished(e -> recomputeHighlightsOnly());

        getChildren()
                .addAll(
                        new Label(tr("find.label")),
                        findField,
                        findClear,
                        countLabel,
                        prev,
                        next,
                        new Label(tr("find.replaceLabel")),
                        replaceField,
                        replaceClear,
                        replace,
                        replaceAll,
                        caseSensitive,
                        regex,
                        wholeWord,
                        preserveCase,
                        inSelection,
                        close);
    }

    public void show(boolean backward) {
        setVisible(true);
        setManaged(true);
        CodeArea area = area();
        searchAnchor = area == null ? 0 : area.getCaretPosition();
        captureScope(area); // a multi-line selection becomes the search scope — must run before the
        // seed/recompute below, which replace the selection with the first match
        seedFromSelection(area); // a single-line selection pre-fills the find field
        subscribeToEdits(area); // keep the highlights in sync as the buffer is edited
        findField.requestFocus();
        findField.selectAll();
        recompute();
        if (backward) {
            status.accept(tr("find.reverseSearch"));
        }
    }

    /**
     * If the editor has a non-empty selection that stays on a single line, use it as the search query —
     * matching the VS Code / browser convention of opening Find on the selected text. A multi-line
     * selection is left alone (it isn't a sensible find term), keeping whatever query was already there.
     */
    private void seedFromSelection(CodeArea area) {
        if (area == null) {
            return;
        }
        String sel = area.getSelectedText();
        if (sel == null || sel.isEmpty() || sel.indexOf('\n') >= 0 || sel.indexOf('\r') >= 0) {
            return;
        }
        findField.setText(sel);
    }

    /**
     * Captures a <b>multi-line</b> selection as the find-in-selection scope and switches the "Sel" toggle
     * on — VS Code's {@code editor.find.autoFindInSelection: "multiline"} behaviour.
     *
     * <p>Single-line selections are deliberately left alone: those are seeded into the query instead (see
     * {@link #seedFromSelection}), so the two features never compete for the same gesture. Capturing here
     * rather than when the toggle is clicked is what makes the scope useful at all — by the time the user
     * could click it, {@code recompute()} has already replaced their selection with the first match.
     */
    private void captureScope(CodeArea area) {
        clearScope();
        if (area == null) {
            return;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        if (end <= start) {
            return;
        }
        String sel = area.getText(start, end);
        if (sel.indexOf('\n') < 0 && sel.indexOf('\r') < 0) {
            return; // single-line → seeded as the query instead
        }
        scopeStart = start;
        scopeEnd = end;
        inSelection.setSelected(true);
    }

    /**
     * Handles the "Sel" toggle. Turning it on without a captured scope falls back to whatever is selected
     * now; with nothing selected there is no scope to define, so the toggle reverts and says so rather
     * than silently behaving like a whole-document search.
     */
    private void onInSelectionToggled(boolean on) {
        if (!on) {
            clearScope();
            recompute();
            return;
        }
        if (hasScope()) {
            recompute();
            return;
        }
        CodeArea area = area();
        int start = area == null ? 0 : area.getSelection().getStart();
        int end = area == null ? 0 : area.getSelection().getEnd();
        if (area == null || end <= start) {
            status.accept(tr("find.noSelection"));
            inSelection.setSelected(false); // re-enters with on=false, which clears and recomputes
            return;
        }
        scopeStart = start;
        scopeEnd = end;
        recompute();
    }

    private boolean hasScope() {
        return scopeStart >= 0 && scopeEnd > scopeStart;
    }

    private void clearScope() {
        scopeStart = -1;
        scopeEnd = -1;
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        unsubscribeFromEdits();
        editDebounce.stop();
        EditorBuffer buffer = activeBuffer.get();
        if (buffer != null) {
            buffer.clearSearchMatches();
            buffer.getFocusedArea().requestFocus();
        }
        matches = List.of();
        activeIndex = -1;
        clearScope();
        inSelection.setSelected(false);
    }

    /**
     * (Re)subscribes to {@code area}'s text changes so edits re-run the search (debounced) and the
     * find-in-selection scope follows its content.
     *
     * <p>The scope shift happens here rather than in the replace methods on purpose: this fires
     * synchronously for <em>every</em> edit, ours included, so one place keeps the range correct and there
     * is no way to double-apply it.
     */
    private void subscribeToEdits(CodeArea area) {
        unsubscribeFromEdits();
        if (area != null) {
            textSub = area.plainTextChanges().subscribe(c -> {
                if (hasScope()) {
                    int[] r = NoteAnchors.shiftRange(
                            scopeStart,
                            scopeEnd,
                            c.getPosition(),
                            c.getRemoved().length(),
                            c.getInserted().length());
                    scopeStart = r[0];
                    scopeEnd = r[1];
                    if (scopeEnd <= scopeStart) {
                        clearScope(); // the scoped text was deleted outright
                    }
                }
                if (isVisible() && !findField.getText().isEmpty()) {
                    editDebounce.playFromStart();
                }
            });
        }
    }

    private void unsubscribeFromEdits() {
        if (textSub != null) {
            textSub.unsubscribe();
            textSub = null;
        }
    }

    public boolean isShown() {
        return isVisible();
    }

    private CodeArea area() {
        EditorBuffer buffer = activeBuffer.get();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    /** Recomputes the full match set for the current query/options, highlights all, and selects the nearest. */
    private void recompute() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            matches = List.of();
            activeIndex = -1;
            if (buffer != null) {
                buffer.clearSearchMatches();
            }
            countLabel.setText("");
            return;
        }
        if (regex.isSelected()) {
            String err = SearchMatcher.regexError(query);
            if (err != null) {
                matches = List.of();
                activeIndex = -1;
                buffer.clearSearchMatches();
                countLabel.setText("");
                status.accept(tr("find.badRegex", err));
                return;
            }
        }
        matches = computeMatches(area, query);
        if (matches.isEmpty()) {
            activeIndex = -1;
            buffer.clearSearchMatches();
            countLabel.setText("");
            status.accept(tr("find.notFound", query));
            return;
        }
        activeIndex = SearchMatcher.nextIndex(matches, searchAnchor, true);
        applyActive(buffer, area, false);
    }

    /**
     * Re-runs the search against the (just-edited) buffer and re-highlights all matches at their new
     * offsets, <em>without</em> selecting/scrolling to a match — so the highlights track edits but don't
     * fight the user's typing. Fired (debounced) from the buffer's text-change subscription.
     */
    private void recomputeHighlightsOnly() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            return;
        }
        matches = computeMatches(area, query);
        if (matches.isEmpty()) {
            activeIndex = -1;
            buffer.clearSearchMatches();
            countLabel.setText("");
            return;
        }
        // Keep the "active" (boxed) match near the caret, but never move the caret/selection here.
        activeIndex = SearchMatcher.nextIndex(matches, area.getCaretPosition(), true);
        buffer.setSearchMatches(matches, activeIndex);
        countLabel.setText(tr("find.count", activeIndex + 1, matches.size()));
    }

    /**
     * The current match set for {@code query}, restricted to the find-in-selection scope when one is
     * active (empty on an invalid regex; no UI side effects).
     */
    private List<int[]> computeMatches(CodeArea area, String query) {
        if (regex.isSelected() && SearchMatcher.regexError(query) != null) {
            return List.of();
        }
        List<int[]> all = SearchMatcher.matches(
                area.getText(), query, caseSensitive.isSelected(), regex.isSelected(), wholeWord.isSelected());
        return scoped(all);
    }

    /**
     * Keeps only matches lying wholly inside the scope. Filtering absolute offsets — rather than searching
     * a substring and mapping offsets back — keeps anchors ({@code ^}, {@code $}, {@code \b}) resolving
     * against the real document, and leaves every offset already correct for the buffer.
     */
    private List<int[]> scoped(List<int[]> all) {
        if (!hasScope() || all.isEmpty()) {
            return all;
        }
        List<int[]> out = new ArrayList<>();
        for (int[] m : all) {
            if (m[0] >= scopeStart && m[1] <= scopeEnd) {
                out.add(m);
            }
        }
        return out;
    }

    /** True when {@code [start,end)} lies wholly inside an active scope (or there is no scope). */
    private boolean inScope(int start, int end) {
        return !hasScope() || (start >= scopeStart && end <= scopeEnd);
    }

    /** Cycles to the next match (a repeated C-s). */
    public void findNext() {
        navigate(true);
    }

    /** Cycles to the previous match (C-r while the bar is open). */
    public void findPrevious() {
        navigate(false);
    }

    /** Moves focus to the replace field (the bar is already showing the find/replace inputs). */
    public void focusReplace() {
        replaceField.requestFocus();
    }

    /** Replaces the current match (palette command). */
    public void replaceCurrentMatch() {
        replaceCurrent();
    }

    /** Replaces every match (palette command). */
    public void replaceAllMatches() {
        replaceAll();
    }

    private void navigate(boolean forward) {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        if (matches.isEmpty()) {
            recompute();
            if (matches.isEmpty()) {
                return;
            }
        }
        int from = forward ? area.getSelection().getEnd() : area.getSelection().getStart();
        activeIndex = SearchMatcher.nextIndex(matches, from, forward);
        applyActive(buffer, area, true);
    }

    /** Selects/scrolls to the active match, refreshes the overlay + count. */
    private void applyActive(EditorBuffer buffer, CodeArea area, boolean focusEditor) {
        int[] m = matches.get(activeIndex);
        area.selectRange(m[0], m[1]);
        area.requestFollowCaret();
        buffer.setSearchMatches(matches, activeIndex);
        countLabel.setText(tr("find.count", activeIndex + 1, matches.size()));
        if (focusEditor) {
            // navigation via buttons/Enter keeps the find field focused for further typing
            findField.requestFocus();
        }
    }

    private void replaceCurrent() {
        CodeArea area = area();
        if (area == null) {
            return;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        if (end > start && inScope(start, end)) {
            String matched = area.getText(start, end);
            String repl;
            try {
                repl = replacementForSingle(matched);
            } catch (RuntimeException badReference) {
                status.accept(tr("find.badReplacement", describe(badReference)));
                return;
            }
            area.replaceSelection(repl);
        }
        recompute();
        navigate(true);
    }

    /**
     * The replacement text for one already-selected match: group references expanded (regex mode) and the
     * result recased (preserve-case mode).
     *
     * <p>Groups are expanded by re-running the pattern against the matched text alone, which is exact
     * because the selection <em>is</em> one whole match. A pattern needing surrounding context
     * (lookbehind/lookahead) will not match in isolation — that falls back to the literal replacement
     * rather than guessing, since Replace All handles those correctly via a full-text walk.
     *
     * @throws RuntimeException if the replacement names a group the pattern does not have
     */
    private String replacementForSingle(String matched) {
        String replacement = replaceField.getText();
        String expanded = replacement;
        if (regex.isSelected()) {
            Pattern p =
                    SearchMatcher.compileRegex(findField.getText(), caseSensitive.isSelected(), wholeWord.isSelected());
            if (p != null && p.matcher(matched).matches()) {
                expanded = p.matcher(matched).replaceFirst(replacement);
            }
        }
        return preserveCase.isSelected() ? PreserveCase.apply(matched, expanded) : expanded;
    }

    private void replaceAll() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            return;
        }
        if (regex.isSelected()) {
            String err = SearchMatcher.regexError(query);
            if (err != null) {
                status.accept(tr("find.badRegex", err));
                return;
            }
        }
        String text = area.getText();
        List<int[]> spans = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        try {
            collectReplacements(area, text, query, spans, replacements);
        } catch (SearchMatcher.MatchBudgetExceededException timeout) {
            // A valid-but-pathological pattern blew the backtracking budget mid-walk — abandon the whole
            // replace (a half-collected set would splice a corrupt document) rather than freeze the UI.
            status.accept(tr("find.replaceTimeout"));
            return;
        } catch (RuntimeException badReference) {
            // An invalid $-group reference — leave the buffer untouched rather than half-rewrite it.
            status.accept(tr("find.badReplacement", describe(badReference)));
            return;
        }
        if (spans.isEmpty()) {
            status.accept(tr("find.replaced", 0));
            return;
        }
        // Splice only the span from the first to the last match, not the whole document: one ranged edit
        // keeps the untouched remainder out of the undo entry and leaves the caret where it was.
        int from = spans.get(0)[0];
        int to = spans.get(spans.size() - 1)[1];
        StringBuilder sb = new StringBuilder();
        int i = from;
        for (int k = 0; k < spans.size(); k++) {
            int[] m = spans.get(k);
            sb.append(text, i, m[0]).append(replacements.get(k));
            i = m[1];
        }
        area.replaceText(from, to, sb.toString());
        recompute();
        status.accept(tr("find.replaced", spans.size()));
    }

    /**
     * Fills {@code spans}/{@code replacements} with each in-scope match and the text that should replace
     * it — group references expanded in regex mode, then recased when preserve-case is on.
     *
     * <p>Regex mode walks the pattern over the full document rather than reusing {@link #computeMatches},
     * because expanding {@code $1} needs live {@link Matcher} state. Expansion is delegated to
     * {@link Matcher#appendReplacement} (writing into a scratch buffer we then slice) so the JDK's own
     * {@code $}/{@code \} handling is used verbatim instead of being reimplemented here.
     *
     * @throws RuntimeException from {@code appendReplacement} if the replacement references a missing group
     */
    private void collectReplacements(
            CodeArea area, String text, String query, List<int[]> spans, List<String> replacements) {
        String replacement = replaceField.getText();
        boolean recase = preserveCase.isSelected();
        if (!regex.isSelected()) {
            for (int[] m : computeMatches(area, query)) {
                spans.add(m);
                replacements.add(recase ? PreserveCase.apply(text.substring(m[0], m[1]), replacement) : replacement);
            }
            return;
        }
        // Whole-word is wrapped as \b(?:…)\b with a non-capturing group, so user group numbers survive.
        Pattern p = SearchMatcher.compileRegex(query, caseSensitive.isSelected(), wholeWord.isSelected());
        if (p == null) {
            return;
        }
        // Wrap the document in the same wall-clock backtracking budget the incremental search uses, so a
        // pathological-but-valid pattern aborts (via MatchBudgetExceededException) instead of hanging the FX
        // thread. text.substring(...)/computeMatches below still read the raw String, so offsets are exact.
        Matcher m = p.matcher(SearchMatcher.budgetedSequence(text));
        StringBuffer scratch = new StringBuffer();
        int lastEnd = 0;
        while (m.find()) {
            // appendReplacement writes text[lastEnd, m.start()) followed by the expansion, so the
            // expansion begins that many characters into what it just appended.
            int insertStart = scratch.length() + (m.start() - lastEnd);
            m.appendReplacement(scratch, replacement);
            lastEnd = m.end();
            if (!inScope(m.start(), m.end())) {
                continue;
            }
            String expanded = scratch.substring(insertStart);
            spans.add(new int[] {m.start(), m.end()});
            replacements.add(recase ? PreserveCase.apply(m.group(), expanded) : expanded);
        }
    }

    /** A short, user-facing description of a replacement-expansion failure. */
    private static String describe(RuntimeException e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
