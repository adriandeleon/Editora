package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
    /** The buffer {@link #matches}, the edit subscription and the scope belong to; null while hidden. */
    private EditorBuffer boundBuffer;
    /** True when the last search was abandoned part-way (time budget or regex stack depth). */
    private boolean searchIncomplete;
    /** True while {@link #resetScope} switches "Sel" off, so its listener does not re-search. */
    private boolean resettingScope;

    private int searchAnchor; // caret offset when the search started — incremental jumps anchor here
    /** Invoked on Alt+Enter in the find field to turn all matches into carets (wired by MainController). */
    private Runnable onSelectAllMatches;

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

        Button next = iconButton(Icons.findNext(), tr("find.next"));
        Button prev = iconButton(Icons.findPrevious(), tr("find.prev"));
        Button replace = new Button(tr("find.replace"));
        Button replaceAll = new Button(tr("find.all"));
        Button close = iconButton(Icons.closeTab(), tr("find.close"));
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
            } else if (e.getCode() == KeyCode.ENTER && e.isAltDown() && onSelectAllMatches != null) {
                // Alt+Enter: turn every match into a caret (VS Code selectAllMatches).
                onSelectAllMatches.run();
                e.consume();
            }
        });
        // The configured keymap's caret/editing chords (C-a/C-e/C-k/C-y, Ctrl+V/X/C/Z/A…) act on these
        // fields. The scene-level KeyDispatcher leaves every such chord to a focused text field instead of
        // running it against the document; this is what then handles it. Find's own chords are find.*
        // commands (and C-g is edit.cancel), which stay global, so next/previous/replace/close still work.
        com.editora.command.TextInputKeymap.installShared(findField);
        com.editora.command.TextInputKeymap.installShared(replaceField);
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

        layoutBar(next, prev, replace, replaceAll, close, findClear, replaceClear);
    }

    /**
     * Lays the bar out as three groups — find, replace, options — in a {@link WrapRow}: the two fields
     * share the spare width (instead of sitting at ~160px beside an empty bar), labels and buttons keep
     * their full width in every language, and a narrow window wraps the groups onto a second line rather
     * than ellipsizing them. The option checkboxes stay the state holders; what is shown are compact
     * toggle buttons bound to them, each named by a tooltip and accessible text.
     */
    private void layoutBar(
            Button next,
            Button prev,
            Button replace,
            Button replaceAll,
            Button close,
            Button findClear,
            Button replaceClear) {
        Label findLabel = fixed(new Label(tr("find.label")));
        Label replaceLabel = fixed(new Label(tr("find.replaceLabel")));
        findLabel.setLabelFor(findField);
        replaceLabel.setLabelFor(replaceField);
        fixed(replace);
        fixed(replaceAll);
        fixed(countLabel);
        fixed(findClear);
        fixed(replaceClear);
        for (TextField field : List.of(findField, replaceField)) {
            field.setMinWidth(FIELD_MIN_WIDTH);
            field.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        }
        HBox findGroup = group(findLabel, findField, findClear, countLabel, prev, next);
        HBox replaceGroup = group(replaceLabel, replaceField, replaceClear, replace, replaceAll);
        HBox options = group(
                OptionToggle.viewOf(caseSensitive, tr("search.caseTip")),
                OptionToggle.viewOf(regex, tr("search.regexTip")),
                OptionToggle.viewOf(wholeWord, tr("find.wholeWord")),
                OptionToggle.viewOf(preserveCase, tr("find.preserveCase")),
                OptionToggle.viewOf(inSelection, tr("find.inSelection")));
        options.setSpacing(2);
        options.getStyleClass().add("find-options");
        WrapRow row = new WrapRow(10, 4, WrapRow.setGrow(findGroup), WrapRow.setGrow(replaceGroup), options);
        HBox.setHgrow(row, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox closeBox = new javafx.scene.layout.VBox(close); // stays top-right when wrapped
        closeBox.setAlignment(Pos.TOP_RIGHT);
        getChildren().addAll(row, closeBox);
    }

    /** Narrowest a find/replace field gets before the bar wraps instead. */
    private static final double FIELD_MIN_WIDTH = 140;

    private static HBox group(Node... nodes) {
        HBox box = new HBox(6, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Text that must always be readable in full: its minimum width is its preferred width. */
    private static <T extends javafx.scene.layout.Region> T fixed(T node) {
        node.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return node;
    }

    /** A compact icon-only action that remains named for tooltips and assistive technology. */
    private static Button iconButton(Node graphic, String accessibleName) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(accessibleName));
        button.setAccessibleText(accessibleName);
        button.getStyleClass().addAll("button-icon", "flat", "find-bar-icon");
        return button;
    }

    public void show(boolean backward) {
        setVisible(true);
        setManaged(true);
        CodeArea area = area();
        searchAnchor = area == null ? 0 : area.getCaretPosition();
        releaseBoundBuffer();
        boundBuffer = activeBuffer.get();
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
        if (resettingScope) {
            return; // reset by hideBar() or a tab switch: nothing to search or select here
        }
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

    /** Drops the scope and switches "Sel" off without the toggle's usual re-search. */
    private void resetScope() {
        resettingScope = true;
        try {
            clearScope();
            inSelection.setSelected(false);
        } finally {
            resettingScope = false;
        }
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        unsubscribeFromEdits();
        // Both debounces: a query typed just before Esc must not re-search (and re-highlight, and move
        // the selection) 150 ms after the bar has gone.
        debounce.stop();
        editDebounce.stop();
        resetScope();
        releaseBoundBuffer(); // the buffer that was searched, which need not be the active one any more
        EditorBuffer buffer = activeBuffer.get();
        if (buffer != null) {
            buffer.clearSearchMatches();
            buffer.getFocusedArea().requestFocus();
        }
        matches = List.of();
        activeIndex = -1;
    }

    /** Drops the searched buffer's highlights and forgets it. */
    private void releaseBoundBuffer() {
        if (boundBuffer != null) {
            boundBuffer.clearSearchMatches();
            boundBuffer = null;
        }
    }

    /**
     * Re-targets the open bar at the buffer that is active now. The match list, the edit subscription and
     * the find-in-selection scope all belong to one buffer; after a tab switch they would otherwise be
     * applied — as raw offsets — to a different document, while the old tab kept its highlights. The new
     * buffer is searched and highlighted without moving its caret or selection.
     *
     * <p>Called by the window when the active tab changes, and again before every action that uses the
     * match list, so the bar is correct even where no such notification arrives.
     */
    public void onActiveBufferChanged() {
        EditorBuffer now = activeBuffer.get();
        if (!isVisible() || now == boundBuffer) {
            return;
        }
        releaseBoundBuffer();
        boundBuffer = now;
        debounce.stop();
        editDebounce.stop();
        matches = List.of();
        activeIndex = -1;
        countLabel.setText("");
        resetScope();
        CodeArea area = area();
        searchAnchor = area == null ? 0 : area.getCaretPosition();
        subscribeToEdits(area);
        recomputeHighlightsOnly();
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

    /** The current match ranges for the live query (empty when nothing matches / the bar is closed). */
    public List<int[]> currentMatches() {
        onActiveBufferChanged();
        return matches;
    }

    /** Wires the Alt+Enter "select all matches" action (turns every match into a caret). */
    public void setOnSelectAllMatches(Runnable action) {
        this.onSelectAllMatches = action;
    }

    private CodeArea area() {
        EditorBuffer buffer = activeBuffer.get();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    /** Recomputes the full match set for the current query/options, highlights all, and selects the nearest. */
    private void recompute() {
        recompute(searchAnchor);
    }

    /** As {@link #recompute()}, selecting the first match at/after {@code anchor} instead of the open-time caret. */
    private void recompute(int anchor) {
        if (!isVisible()) {
            return; // a late debounce or a toggle reset must not search for a bar that is closed
        }
        onActiveBufferChanged();
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
            status.accept(searchIncomplete ? tr("find.tooComplex") : tr("find.notFound", query));
            return;
        }
        activeIndex = SearchMatcher.nextIndex(matches, anchor, true);
        applyActive(buffer, area, false);
        if (searchIncomplete) {
            status.accept(tr("find.tooComplex")); // what is highlighted is not every match
        }
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
        searchIncomplete = false;
        if (regex.isSelected() && SearchMatcher.regexError(query) != null) {
            return List.of();
        }
        SearchMatcher.Result found = SearchMatcher.search(
                area.getText(), query, caseSensitive.isSelected(), regex.isSelected(), wholeWord.isSelected());
        searchIncomplete = !found.complete();
        return scoped(found.matches());
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
        onActiveBufferChanged();
        if (matches.isEmpty()) {
            recompute();
            if (matches.isEmpty()) {
                return;
            }
        }
        int selStart = area.getSelection().getStart();
        int selEnd = area.getSelection().getEnd();
        if (forward && isActiveMatch(selStart, selEnd)) {
            // Step by index: searching from the selection's end would land on a zero-width match (^, $,
            // ^$) again and again, since such a match ends where it starts.
            activeIndex = (activeIndex + 1) % matches.size();
        } else {
            activeIndex = SearchMatcher.nextIndex(matches, forward ? selEnd : selStart, forward);
        }
        applyActive(buffer, area, true);
    }

    /** True when {@code [start,end)} is exactly the match the bar currently shows as active. */
    private boolean isActiveMatch(int start, int end) {
        if (activeIndex < 0 || activeIndex >= matches.size()) {
            return false;
        }
        int[] m = matches.get(activeIndex);
        return m[0] == start && m[1] == end;
    }

    /** Selects/scrolls to the active match, refreshes the overlay + count. */
    private void applyActive(EditorBuffer buffer, CodeArea area, boolean focusEditor) {
        int[] m = matches.get(activeIndex);
        // Reveal a match hidden in a collapsed fold first: selecting hidden text shows nothing, and the
        // selection then spans from the fold header into lines the user cannot see.
        int firstLine = area.offsetToPosition(m[0], org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        int lastLine = m[1] == m[0]
                ? firstLine
                : area.offsetToPosition(m[1], org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                        .getMajor();
        buffer.getFoldManager().unfoldContaining(firstLine);
        buffer.getFoldManager().unfoldContaining(lastLine);
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
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null || !buffer.isEditable()) {
            return;
        }
        onActiveBufferChanged();
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        // Validate the selection against a fresh match list WITHOUT recompute(): that re-selects the first
        // match after the open-time anchor, and the replace below then rewrote that match instead of this one.
        String query = findField.getText();
        boolean currentMatch =
                !query.isEmpty() && computeMatches(area, query).stream().anyMatch(m -> m[0] == start && m[1] == end);
        int next = start;
        // A zero-width match (^, $, a lookahead) is replaceable too: it is an insertion at that spot.
        if (inScope(start, end) && currentMatch) {
            String matched = area.getText(start, end);
            String repl;
            try {
                repl = replacementForSingle(area.getText(), start, end, matched);
            } catch (RuntimeException badReference) {
                status.accept(tr("find.badReplacement", describe(badReference)));
                return;
            } catch (StackOverflowError tooDeep) {
                status.accept(tr("find.tooComplex"));
                return;
            }
            area.replaceText(start, end, repl); // by range: never whatever happens to be selected by now
            // Past a zero-width match step one further, or the same assertion is found at the same spot.
            next = start + repl.length() + (end > start ? 0 : 1);
        }
        // Move on to the first match after the replaced text (or, when the selection was not a match, the
        // first one at/after it) — the next match in document order, not one relative to the stale anchor.
        recompute(next);
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
    private String replacementForSingle(String fullText, int start, int end, String matched) {
        String replacement = replaceField.getText();
        String expanded = replacement;
        if (regex.isSelected()) {
            Pattern p = SearchMatcher.compileDocumentRegex(
                    findField.getText(), caseSensitive.isSelected(), wholeWord.isSelected());
            java.util.regex.Matcher matcher = p == null ? null : p.matcher(fullText);
            if (matcher != null && matcher.find(start) && matcher.start() == start && matcher.end() == end) {
                StringBuffer replacedPrefix = new StringBuffer();
                matcher.appendReplacement(replacedPrefix, replacement);
                expanded = replacedPrefix.substring(start);
            }
        }
        return preserveCase.isSelected() ? PreserveCase.apply(matched, expanded) : expanded;
    }

    private void replaceAll() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty() || !buffer.isEditable()) {
            return;
        }
        onActiveBufferChanged();
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
        } catch (StackOverflowError tooDeep) {
            // java.util.regex recurses per repetition of a group; a long match ran it out of stack.
            status.accept(tr("find.tooComplex"));
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
        // Whole-word wraps the query in a non-capturing group, so user group numbers survive.
        Pattern p = SearchMatcher.compileDocumentRegex(query, caseSensitive.isSelected(), wholeWord.isSelected());
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
