package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.editora.editops.BraceMatcher;
import com.editora.editops.PythonDefun;
import com.editora.editops.SelectOccurrences;
import com.editora.editops.SexpNav;
import com.editora.editor.UiZoom;
import com.editora.editor.lsp.OccurrenceHighlightOverlay;
import com.editora.text.LanguageRegistry;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Moving around inside a Map card's read-only text (Map code browser, card movement): Go to Line, the matching
 * bracket, the start and end of the definition around the caret, and the other uses of the name at the caret, washed
 * as the editor washes a symbol's occurrences. Typed in a card, the editor's commands act on the card's text, never on
 * the active editor tab's. The card's previous / next declaration (Alt+Page Up / Alt+Page Down) is the card's own, as
 * it needs the file's outline.
 */
final class MapCardMoves {

    static final String GO_TO_LINE = "nav.goToLine";
    static final String MATCHING_BRACKET = "nav.matchingBracket";
    static final String BEGINNING_OF_DEFUN = "nav.beginningOfDefun";
    static final String END_OF_DEFUN = "nav.endOfDefun";

    /** The editor's movement commands a focused card runs on its own text, whatever keys the keymap gives them. */
    static final Set<String> COMMANDS = Set.of(GO_TO_LINE, MATCHING_BRACKET, BEGINNING_OF_DEFUN, END_OF_DEFUN);

    /** At most this many uses of a name are washed in a card. */
    static final int MAX_USES = 2_000;

    /** How long the caret rests before the uses of the name under it are washed, as in the editor. */
    static final Duration USES_DELAY = Duration.millis(150);

    /** The style classes of words that are not names (keywords, type keywords, true / null): not worth a wash. */
    private static final Set<String> NOT_NAMES = Set.of("keyword", "constant", "number");

    /** What the moves need to know of their card. */
    interface Card {
        /** The file's 0-based line the card's text starts at: a declaration card's first line, else 0. */
        int firstFileLine();

        /** The card's file name, for its language's identifier characters and definitions; null with no file. */
        @Nullable
        String fileName();

        /** Whether the card shows text that can be moved around in (not an image, a folder or a missing file). */
        boolean textShown();
    }

    private final CodeArea editor;
    private final VBox header;
    private final Card card;
    private final HBox lineBar = UiZoom.spacing(new HBox(), 4);
    private final TextField lineField = new TextField();
    private final Label lineRange = new Label();
    private final Button lineClose = new Button("×");
    private final OccurrenceHighlightOverlay uses;
    private final PauseTransition usesIdle = new PauseTransition(USES_DELAY);

    MapCardMoves(CodeArea editor, VBox header, Card card) {
        this.editor = editor;
        this.header = header;
        this.card = card;
        this.uses = new OccurrenceHighlightOverlay(editor);
        installLineBar();
        usesIdle.setOnFinished(event -> markUses());
        editor.caretPositionProperty().addListener((obs, old, now) -> usesMoved());
        editor.selectionProperty().addListener((obs, old, now) -> usesMoved());
        editor.focusedProperty().addListener((obs, was, focused) -> usesMoved());
    }

    /** The wash of the uses of the name at the caret, laid over the card's text. */
    Region usesOverlay() {
        return uses;
    }

    /**
     * Runs a movement command typed in the card on its text: true for every command in {@link #COMMANDS}, so it never
     * falls through to the active editor tab, even when there is nothing to move to here.
     */
    boolean run(String commandId) {
        if (!COMMANDS.contains(commandId)) {
            return false;
        }
        if (!card.textShown() || editor.getLength() == 0) {
            return true;
        }
        switch (commandId) {
            case GO_TO_LINE -> showLineBar();
            case MATCHING_BRACKET ->
                moveTo(BraceMatcher.jumpTarget(
                        editor.getText(), editor.getCaretPosition(), BraceMatcher.DEFAULT_MAX_SCAN));
            default ->
                moveTo(defun(editor.getText(), editor.getCaretPosition(), python(), END_OF_DEFUN.equals(commandId)));
        }
        return true;
    }

    /** Where Beginning / End of Defun go from {@code caret} in {@code text}: Python's def / class, else column 0. */
    static int defun(String text, int caret, boolean python, boolean end) {
        if (python) {
            return end ? PythonDefun.end(text, caret) : PythonDefun.beginning(text, caret);
        }
        return end ? SexpNav.endOfDefun(text, caret) : SexpNav.beginningOfDefun(text, caret);
    }

    private boolean python() {
        String name = card.fileName();
        return name != null && "python".equals(LanguageRegistry.forFileName(name));
    }

    private @Nullable String language() {
        String name = card.fileName();
        return name == null ? null : LanguageRegistry.forFileName(name);
    }

    /** The caret to {@code offset}, the line in sight; nothing for a negative offset (no bracket at the caret). */
    private void moveTo(int offset) {
        if (offset < 0) {
            return;
        }
        editor.moveTo(Math.min(offset, editor.getLength()));
        editor.requestFollowCaret();
    }

    // --- Go to Line ------------------------------------------------------------------------------------------

    private void installLineBar() {
        lineBar.getStyleClass().addAll("project-map-preview-find", "project-map-preview-line");
        lineBar.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(lineBar, 3, 6, 3, 6);
        lineField.setPromptText(tr("project.map.preview.goToLine.prompt"));
        lineField.setAccessibleText(tr("project.map.preview.goToLine.prompt"));
        lineField.getStyleClass().addAll("project-map-preview-find-field", "project-map-preview-line-field");
        HBox.setHgrow(lineField, Priority.ALWAYS);
        UiZoom.minWidth(lineField, 60);
        lineRange.getStyleClass().add("project-map-preview-find-count");
        lineRange.setMinWidth(Region.USE_PREF_SIZE);
        lineClose.getStyleClass().add("project-map-preview-button");
        lineClose.setMinWidth(Region.USE_PREF_SIZE);
        lineClose.setFocusTraversable(false);
        Icons.name(lineClose, tr("project.map.preview.goToLine.close"));
        lineClose.setOnAction(event -> closeLineBar());
        lineField.textProperty().addListener((obs, old, now) -> lineField.pseudoClassStateChanged(MISSING, false));
        lineField.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            // Escape is the card's: it closes this bar before the find bar or the card.
            if (event.getCode() == KeyCode.ENTER) {
                goToLine(lineField.getText());
                event.consume();
            }
        });
        lineBar.getChildren().setAll(lineField, lineRange, lineClose);
    }

    private static final javafx.css.PseudoClass MISSING = javafx.css.PseudoClass.getPseudoClass("missing");

    /** Whether the card's Go to Line bar is open. */
    boolean lineBarShown() {
        return header.getChildren().contains(lineBar);
    }

    /**
     * Opens Go to Line in the card's header, the keyboard in its field: the caret's line to start from, and the lines
     * the card holds beside it, numbered as the file numbers them.
     */
    void showLineBar() {
        if (!card.textShown() || editor.getLength() == 0) {
            return;
        }
        if (!lineBarShown()) {
            header.getChildren().add(lineBar);
        }
        int first = card.firstFileLine();
        int last = first + editor.getParagraphs().size() - 1;
        lineRange.setText(tr("project.map.preview.goToLine.range", first + 1, last + 1));
        lineField.setText(String.valueOf(first + editor.getCurrentParagraph() + 1));
        lineField.pseudoClassStateChanged(MISSING, false);
        lineField.requestFocus();
        lineField.selectAll();
    }

    /** Closes Go to Line; the keyboard goes back to the text. */
    void closeLineBar() {
        if (header.getChildren().remove(lineBar)) {
            editor.requestFocus();
        }
    }

    /** The bar's field. For tests. */
    TextField lineField() {
        return lineField;
    }

    String lineRangeText() {
        return lineRange.getText();
    }

    /**
     * Goes to {@code input} — a line, or line:column, as the file numbers them — and closes the bar; a line past what
     * the card holds goes to its first or last line, as the editor's Go to Line clamps. What is not a line number
     * leaves the bar open, its field marked.
     */
    void goToLine(String input) {
        int first = card.firstFileLine();
        int paragraphs = editor.getParagraphs().size();
        int[] place = parseLine(input, first, first + paragraphs - 1);
        if (place == null || paragraphs == 0) {
            lineField.pseudoClassStateChanged(MISSING, true);
            return;
        }
        int line = place[0] - first;
        int column = Math.min(place[1], editor.getParagraph(line).length());
        closeLineBar();
        editor.moveTo(line, column);
        editor.showParagraphAtTop(Math.max(0, line - 3)); // a few lines of what leads to it above it
        editor.requestFollowCaret();
    }

    /**
     * {@code input} — {@code line} or {@code line:column}, both 1-based — as a 0-based {@code {line, column}} with the
     * line clamped to {@code first..last} (0-based) and a negative column taken as 0; null when it is not a number.
     * Pure.
     */
    static int @Nullable [] parseLine(String input, int first, int last) {
        if (input == null || last < first) {
            return null;
        }
        String[] parts = input.trim().split(":", 2);
        try {
            int line = Integer.parseInt(parts[0].trim()) - 1;
            String columnText = parts.length > 1 ? parts[1].trim() : "";
            int column = columnText.isEmpty() ? 0 : Integer.parseInt(columnText) - 1;
            return new int[] {Math.max(first, Math.min(last, line)), Math.max(0, column)};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // --- the other uses of the name at the caret ----------------------------------------------------------------

    private void usesMoved() {
        uses.setSpans(List.of());
        if (editor.isFocused() && card.textShown()) {
            usesIdle.playFromStart();
        } else {
            usesIdle.stop();
        }
    }

    /** Washes the other uses of the name at the caret, once the caret rests. */
    private void markUses() {
        if (!editor.isFocused() || !card.textShown() || editor.getLength() == 0) {
            return;
        }
        uses.setSpans(usesOf(
                editor.getText(),
                editor.getCaretPosition(),
                editor.getSelection().getStart(),
                editor.getSelection().getEnd(),
                language(),
                at -> isName(editor.getStyleOfChar(at)),
                MAX_USES));
    }

    /** Whether a character with these style classes is part of a name: not a keyword, a constant or a number. */
    static boolean isName(@Nullable Collection<String> styles) {
        if (styles == null) {
            return true;
        }
        for (String style : styles) {
            if (NOT_NAMES.contains(style)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The whole-word uses of the name at {@code caret} in {@code text}, as the overlay's {@code {start, end, 0}}
     * triples, at most {@code max}: none when the caret is not on a name ({@code name} says, at the word's first
     * character), when something other than that name is selected, or when the name is used only there. Pure.
     */
    static List<int[]> usesOf(
            String text,
            int caret,
            int selectionStart,
            int selectionEnd,
            @Nullable String language,
            Predicate<Integer> name,
            int max) {
        int[] word = SelectOccurrences.wordAt(text, caret, language);
        if (word == null || Character.isDigit(text.charAt(word[0])) || !name.test(word[0])) {
            return List.of();
        }
        if (selectionEnd > selectionStart && (selectionStart != word[0] || selectionEnd != word[1])) {
            return List.of();
        }
        Matcher matcher = ProjectMapLinkSearch.namePattern(text.substring(word[0], word[1]), false)
                .matcher(text);
        List<int[]> found = new ArrayList<>();
        while (found.size() < max && matcher.find()) {
            found.add(new int[] {matcher.start(), matcher.end(), 0});
        }
        return found.size() < 2 ? List.of() : List.copyOf(found);
    }

    /** The spans washed now. For tests. */
    boolean usesShown() {
        return uses.isVisible();
    }

    /** Clears the wash and stops waiting for the caret to rest: the card shows other text, or closes. */
    void reset() {
        usesIdle.stop();
        uses.setSpans(List.of());
        header.getChildren().remove(lineBar);
    }
}
