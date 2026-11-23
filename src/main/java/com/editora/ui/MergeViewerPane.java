package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.diff.ConflictParser;
import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import com.editora.diff.ConflictParser.PlainSegment;
import com.editora.diff.ConflictParser.Segment;
import com.editora.diff.DiffText;
import com.editora.diff.LossyEdit;
import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A merge-conflict resolution tab ({@link TabContent}) for a file containing Git conflict markers. Each
 * conflict is shown as a Base/Ours/Theirs card with per-conflict acceptance actions. The lower Result
 * editor updates from those choices and remains directly editable for a hand-crafted resolution. A toolbar
 * tracks the resolved count and applies the exact Result text through the injected callback. Unchanged
 * regions are summarized so the conflicts stay prominent. Pure view over a parsed {@link ConflictFile}.
 *
 * <p>A card holds two or three text areas, so a file of hundreds of conflicts (a lock file, a generated file) shows
 * them {@link #PAGE_SIZE} at a time, with a line that says which and buttons to the pages before and after (PERF-08,
 * Pauta 6.2.0). A choice is kept with its conflict, not its card: a page left and come back to shows it again, and
 * the Result and Save always cover the whole file.
 */
public final class MergeViewerPane implements TabContent {

    private final String title;
    private final ConflictFile file;
    private final java.util.function.BiConsumer<String, Consumer<Boolean>> onSave;
    /** Set while a save waits for its answer (the document may be read off the FX thread first). */
    private boolean saving;

    /** How many conflict cards are shown at once. */
    static final int PAGE_SIZE = 20;

    private final List<Choice> choices;
    private final VBox cards = UiZoom.spacing(new VBox(), 8);
    private final ScrollPane cardScroll = new ScrollPane(cards);
    private final Label pageLabel = new Label();
    private final Button previousPage = new Button(tr("merge.pagePrevious"));
    private final Button nextPage = new Button(tr("merge.pageNext"));
    private final HBox pager = UiZoom.spacing(new HBox(), 8);
    /** The index of the first conflict on the page shown. */
    private int pageStart;

    private final String lineSeparator;
    private final boolean finalNewline;

    private final BorderPane root = new BorderPane();
    private final Label status = new Label();
    private final TextArea resultArea = new TextArea();
    private final String fontStyle;
    private boolean updatingResult;
    /** The Result was edited since {@link #refreshResult} filled it; whether it differs is asked at a choice. */
    private boolean manuallyEdited;

    private boolean draftDirty;
    private String generatedResult = "";
    /** What the Result area showed for {@link #generatedResult}: a TextArea drops control characters. */
    private String shownResult = "";

    public MergeViewerPane(
            String title,
            ConflictFile file,
            String fontFamily,
            int fontSize,
            String lineSeparator,
            boolean finalNewline,
            Consumer<String> onSave) {
        this(title, file, fontFamily, fontSize, lineSeparator, finalNewline, (text, done) -> {
            if (onSave != null) {
                onSave.accept(text);
            }
            done.accept(true);
        });
    }

    MergeViewerPane(
            String title,
            ConflictFile file,
            String fontFamily,
            int fontSize,
            String lineSeparator,
            boolean finalNewline,
            java.util.function.BiConsumer<String, Consumer<Boolean>> onSave) {
        this.title = title;
        this.file = file;
        this.onSave = onSave == null ? (text, done) -> done.accept(false) : onSave;
        this.lineSeparator = lineSeparator == null || lineSeparator.isEmpty() ? "\n" : lineSeparator;
        this.finalNewline = finalNewline;
        this.fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        this.choices = new ArrayList<>(java.util.Collections.nCopies(file.conflictCount(), Choice.UNRESOLVED));

        root.getStyleClass().add("merge-viewer");
        root.setTop(buildToolbar());
        root.setCenter(buildBody());
        refreshResult();
        draftDirty = false;
        refreshStatus();
    }

    private HBox buildToolbar() {
        status.getStyleClass().add("merge-status");
        Button save = new Button(tr("merge.save"));
        save.getStyleClass().addAll("merge-save", "success");
        save.setOnAction(e -> saveResult(null));
        FocusCommands.press(root, "merge.saveResult", save);
        HBox bar = UiZoom.spacing(new HBox(status, spacer(), save), 8);
        bar.getStyleClass().add("merge-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(bar, 4, 8, 4, 8);
        return bar;
    }

    private SplitPane buildBody() {
        UiZoom.padding(cards, 8);
        pageLabel.getStyleClass().add("merge-page");
        previousPage.setOnAction(e -> showPage(pageStart - PAGE_SIZE));
        nextPage.setOnAction(e -> showPage(pageStart + PAGE_SIZE));
        pager.getChildren().setAll(pageLabel, spacer(), previousPage, nextPage);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("merge-pager");
        showPage(0);
        ScrollPane scroll = cardScroll;
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("merge-scroll");

        Label resultLabel = new Label(tr("merge.result"));
        resultLabel.getStyleClass().add("merge-result-label");
        resultArea.setId("merge-result");
        // The configured keymap's caret/editing chords act on the result (the KeyDispatcher leaves them to it).
        com.editora.command.TextInputKeymap.installShared(resultArea);
        resultArea.setWrapText(false);
        resultArea.setStyle(fontStyle);
        resultArea.getStyleClass().add("merge-result");
        resultArea.setAccessibleText(tr("merge.resultDescription"));
        // An invalidation listener: a change listener had the area join its whole text on every keystroke (PERF-01).
        resultArea.textProperty().addListener(ignored -> {
            if (!updatingResult) {
                manuallyEdited = true; // until the text is looked at: a choice compares it with what was shown
                draftDirty = true;
            }
        });
        VBox result = UiZoom.spacing(new VBox(resultLabel, resultArea), 4);
        result.getStyleClass().add("merge-result-box");
        UiZoom.padding(result, 6, 8, 8, 8);
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(scroll, result);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        split.getStyleClass().add("merge-split");
        return split;
    }

    /**
     * Shows the page of conflicts that starts at {@code start}: its cards, and the unchanged stretches between them.
     * A file with no more conflicts than a page holds has no pager.
     */
    private void showPage(int start) {
        int count = file.conflictCount();
        pageStart = Math.max(0, Math.min(start, (Math.max(count, 1) - 1) / PAGE_SIZE * PAGE_SIZE));
        int end = Math.min(count, pageStart + PAGE_SIZE);
        List<Node> shown = new ArrayList<>();
        if (count > PAGE_SIZE) {
            pageLabel.setText(tr("merge.page", pageStart + 1, end, count));
            previousPage.setDisable(pageStart == 0);
            nextPage.setDisable(end >= count);
            shown.add(pager);
        }
        int conflictIndex = 0;
        for (Segment seg : file.segments()) {
            if (seg instanceof PlainSegment p) {
                // The stretch before a conflict of the page, or after the file's last one when the page ends there.
                boolean onPage =
                        conflictIndex >= pageStart && conflictIndex < end || conflictIndex == count && end == count;
                if (onPage && !p.lines().isEmpty()) {
                    Label note = new Label(tr("merge.unchanged", p.lines().size()));
                    note.getStyleClass().add("merge-context");
                    shown.add(note);
                }
            } else if (seg instanceof ConflictSegment cs) {
                if (conflictIndex >= pageStart && conflictIndex < end) {
                    shown.add(buildConflictCard(conflictIndex, cs.conflict()));
                }
                conflictIndex++;
            }
        }
        cards.getChildren().setAll(shown);
        cardScroll.setVvalue(0);
    }

    /** What a card says of the choice made for its conflict, or nothing while it is unresolved. */
    private static String keptText(Choice choice) {
        return switch (choice) {
            case OURS -> tr("merge.kept.ours");
            case THEIRS -> tr("merge.kept.theirs");
            case BOTH -> tr("merge.kept.both");
            case BASE -> tr("merge.kept.base");
            case UNRESOLVED -> "";
        };
    }

    private VBox buildConflictCard(int index, Conflict c) {
        Label header = new Label(tr("merge.conflictN", index + 1, file.conflictCount()));
        header.getStyleClass().add("merge-conflict-header");

        VBox ours = sideBox(tr("merge.ours", c.oursLabel().isEmpty() ? "HEAD" : c.oursLabel()), c.ours(), "merge-ours");
        VBox theirs = sideBox(
                tr("merge.theirs", c.theirsLabel().isEmpty() ? "incoming" : c.theirsLabel()),
                c.theirs(),
                "merge-theirs");
        HBox.setHgrow(ours, Priority.ALWAYS);
        HBox.setHgrow(theirs, Priority.ALWAYS);
        // 3-way (diff3/zdiff3) markers carry the common ancestor — show it as a middle column when present.
        HBox sides;
        if (c.hasBase()) {
            VBox base =
                    sideBox(tr("merge.base", c.baseLabel().isEmpty() ? "base" : c.baseLabel()), c.base(), "merge-base");
            HBox.setHgrow(base, Priority.ALWAYS);
            sides = UiZoom.spacing(new HBox(ours, base, theirs), 8);
        } else {
            sides = UiZoom.spacing(new HBox(ours, theirs), 8);
        }

        Label chosen = new Label(keptText(choices.get(index)));
        chosen.getStyleClass().add("merge-chosen");
        Button acceptOurs = new Button(tr("merge.acceptOurs"));
        Button acceptTheirs = new Button(tr("merge.acceptTheirs"));
        Button acceptBoth = new Button(tr("merge.acceptBoth"));
        acceptOurs.setOnAction(e -> choose(index, Choice.OURS, chosen, tr("merge.kept.ours")));
        acceptTheirs.setOnAction(e -> choose(index, Choice.THEIRS, chosen, tr("merge.kept.theirs")));
        acceptBoth.setOnAction(e -> choose(index, Choice.BOTH, chosen, tr("merge.kept.both")));
        HBox actions = UiZoom.spacing(new HBox(acceptOurs, acceptTheirs, acceptBoth), 6);
        if (c.hasBase()) {
            Button acceptBase = new Button(tr("merge.acceptBase"));
            acceptBase.setOnAction(e -> choose(index, Choice.BASE, chosen, tr("merge.kept.base")));
            actions.getChildren().add(acceptBase);
        }
        actions.getChildren().addAll(spacer(), chosen);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = UiZoom.spacing(new VBox(header, sides, actions), 4);
        card.getStyleClass().add("merge-conflict-card");
        // The Accept commands act on the conflict the keyboard is in, as its buttons do.
        FocusCommands.press(card, "merge.acceptOurs", acceptOurs);
        FocusCommands.press(card, "merge.acceptTheirs", acceptTheirs);
        FocusCommands.press(card, "merge.acceptBoth", acceptBoth);
        for (Node action : actions.getChildren()) {
            if (action instanceof Button base
                    && !List.of(acceptOurs, acceptTheirs, acceptBoth).contains(base)) {
                FocusCommands.press(card, "merge.acceptBase", base);
            }
        }
        FocusCommands.focusOnly(card);
        UiZoom.padding(card, 6);
        return card;
    }

    private VBox sideBox(String label, List<String> lines, String styleClass) {
        Label l = new Label(label);
        l.getStyleClass().add("merge-side-label");
        TextArea area = new TextArea(String.join("\n", lines));
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("merge-side", styleClass);
        area.setStyle(fontStyle);
        area.setPrefRowCount(Math.min(Math.max(lines.size(), 1), 12));
        VBox box = UiZoom.spacing(new VBox(l, area), 2);
        return box;
    }

    // --- test reads ---

    /** The page's conflict cards. Test accessor. */
    List<Node> cardsForTest() {
        return cards.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("merge-conflict-card"))
                .toList();
    }

    /** The pager's line, or null when the file fits a page. Test accessor. */
    @Nullable
    String pageTextForTest() {
        return cards.getChildren().contains(pager) ? pageLabel.getText() : null;
    }

    /** Goes to the next page, as its button does. Test accessor. */
    void nextPageForTest() {
        nextPage.fire();
    }

    private void choose(int index, Choice choice, Label chosen, String text) {
        if (manuallyEdited && !resultArea.getText().equals(shownResult)) {
            status.setText(tr("merge.manualEditsBlockChoices"));
            return;
        }
        choices.set(index, choice);
        chosen.setText(text);
        refreshResult();
        draftDirty = true;
        refreshStatus();
    }

    /** Where the Result's text is put together from the choices: heard off the FX thread (PERF-01). */
    public static final String RESULT_SITE = "MergeViewerPane.result";

    /** Counts the choices made: a Result put together for earlier ones that lands late is dropped. */
    private long resultGeneration;

    /** Set while the Result for the choices made is put together; a save waits for it. */
    private boolean composing;

    private @Nullable Runnable afterComposed;

    /** The Result for the choices made, put together off the FX thread (PERF-01, Pauta 6.2.0) and shown when it lands. */
    private void refreshResult() {
        List<Choice> now = List.copyOf(choices);
        long mine = ++resultGeneration;
        composing = true;
        com.editora.perf.OffFx.then(RESULT_SITE, () -> resultText(file, now, lineSeparator, finalNewline), a -> {
            if (mine != resultGeneration) {
                return;
            }
            composing = false;
            String text = a.value();
            if (text == null) {
                // Not put together: a save waiting for it would wait forever, and would save the Result
                // for earlier choices. Drop it, and say so — the next choice tries again.
                afterComposed = null;
                status.setText(resultFailedText(a.failure()));
                return;
            }
            showResult(text);
            Runnable after = afterComposed;
            afterComposed = null;
            if (after != null) {
                after.run();
            }
        });
    }

    /** What the status line says when the Result could not be put together or turned into text. Pure. */
    static String resultFailedText(@Nullable Throwable failure) {
        String why = failure == null
                ? ""
                : failure.getMessage() != null
                        ? failure.getMessage()
                        : failure.getClass().getSimpleName();
        return tr("merge.resultFailed", why);
    }

    /** Pure: the text {@code choices} resolve {@code file} to. */
    static String resultText(ConflictFile file, List<Choice> choices, String lineSeparator, boolean finalNewline) {
        List<String> lines = ConflictParser.resolve(file, choices);
        String text = String.join(lineSeparator, lines);
        return finalNewline && !lines.isEmpty() ? text + lineSeparator : text;
    }

    private void showResult(String text) {
        generatedResult = text;
        updatingResult = true;
        try {
            resultArea.setText(text);
            shownResult = resultArea.getText();
        } finally {
            updatingResult = false;
        }
        manuallyEdited = false;
    }

    /** Where the hand-edited Result is turned back into the document's text: heard off the FX thread (PERF-01). */
    public static final String SAVE_SITE = "MergeViewerPane.resultText";

    /**
     * Applies the result. Its text is worked out off the FX thread (PERF-01, Pauta 6.2.0): an edited Result is
     * compared and diffed against the generated one there; the answer may come later still (a closed document is
     * read again off the FX thread first). A press while a save is under way does nothing. {@code whenSaved} runs
     * once the result has been applied.
     */
    private void saveResult(@Nullable Runnable whenSaved) {
        if (saving) {
            return;
        }
        if (composing) {
            afterComposed = () -> saveResult(whenSaved); // the Result for the last choice first
            return;
        }
        saving = true;
        String current = resultArea.getText();
        String generated = generatedResult;
        String shown = shownResult;
        String separator = lineSeparator;
        com.editora.perf.OffFx.then(SAVE_SITE, () -> resultTextForSave(current, generated, shown, separator), a -> {
            String text = a.value();
            if (!a.ok() || text == null) {
                saving = false;
                status.setText(resultFailedText(a.failure()));
                return;
            }
            apply(text, whenSaved);
        });
    }

    /** Hands {@code text} to the save callback; true when it was applied before this returns. */
    private boolean apply(String text, @Nullable Runnable whenSaved) {
        boolean[] applied = {false};
        onSave.accept(text, ok -> {
            saving = false;
            if (ok) {
                draftDirty = false;
                applied[0] = true;
                if (whenSaved != null) {
                    whenSaved.run();
                }
            }
        });
        return applied[0];
    }

    /** Whether a save is under way (its text being worked out, or its answer awaited). */
    boolean saving() {
        return saving;
    }

    /** Pure: the text a Save applies, from what the Result area shows now. */
    static String resultTextForSave(String current, String generatedResult, String shownResult, String lineSeparator) {
        if (current.equals(shownResult)) {
            // Not edited by hand: apply the generated text itself. The TextArea's copy has lost every control
            // character (form feed page separators, ESC, …), in regions no conflict touched.
            return generatedResult;
        }
        // JavaFX TextArea normalizes entered CRLF/CR to LF. Restore the source document's separator while
        // retaining the user's current choice about whether the result ends with a newline — and take the
        // lines the user did not change from the generated text, so they keep their control characters.
        DiffText edited = DiffText.parse(current);
        List<String> lines = LossyEdit.restore(
                DiffText.parse(generatedResult).lines(),
                DiffText.parse(shownResult).lines(),
                edited.lines());
        return new DiffText(lines, lineSeparator, edited.finalNewline()).compose(lines);
    }

    private void refreshStatus() {
        long resolved = choices.stream().filter(c -> c != Choice.UNRESOLVED).count();
        status.setText(tr("merge.status", resolved, choices.size()));
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return Icons.merge();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return draftDirty;
    }

    @Override
    public @Nullable Object unsavedStateToken() {
        return draftDirty ? resultArea.getText() : null;
    }

    /** The quit's close question, which may wait: the text is worked out in place. */
    @Override
    public boolean saveBeforeClose() {
        if (saving) {
            return false;
        }
        if (composing) { // the quit may wait: put together in place
            resultGeneration++;
            composing = false;
            showResult(resultText(file, List.copyOf(choices), lineSeparator, finalNewline));
        }
        saving = true;
        return apply(resultTextForSave(resultArea.getText(), generatedResult, shownResult, lineSeparator), null);
    }

    /** A tab's close question: the save goes on off the FX thread and {@code whenSaved} asks for the close again. */
    @Override
    public boolean saveBeforeClose(@Nullable Runnable whenSaved) {
        if (whenSaved == null) {
            return saveBeforeClose();
        }
        saveResult(whenSaved);
        return false;
    }

    @Override
    public String closeSaveActionKey() {
        return "merge.save";
    }
}
