package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/**
 * A merge-conflict resolution tab ({@link TabContent}) for a file containing Git conflict markers. Each
 * conflict is shown as a card with the "ours" and "theirs" sides side-by-side and per-conflict
 * Accept&nbsp;Ours / Theirs / Both buttons; a toolbar tracks the resolved count and a Save action writes
 * the {@link ConflictParser#resolve resolved} text back through the injected callback. Unchanged regions
 * are summarized so the conflicts stay prominent. Pure view over a parsed {@link ConflictFile}.
 */
public final class MergeViewerPane implements TabContent {

    private final String title;
    private final ConflictFile file;
    private final Consumer<List<String>> onSave;
    private final List<Choice> choices;

    private final BorderPane root = new BorderPane();
    private final Label status = new Label();
    private final String fontStyle;

    public MergeViewerPane(
            String title, ConflictFile file, String fontFamily, int fontSize, Consumer<List<String>> onSave) {
        this.title = title;
        this.file = file;
        this.onSave = onSave == null ? lines -> {} : onSave;
        this.fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        this.choices = new ArrayList<>(java.util.Collections.nCopies(file.conflictCount(), Choice.UNRESOLVED));

        root.getStyleClass().add("merge-viewer");
        root.setTop(buildToolbar());
        root.setCenter(buildBody());
        refreshStatus();
    }

    private HBox buildToolbar() {
        status.getStyleClass().add("merge-status");
        Button save = new Button(tr("merge.save"));
        save.getStyleClass().add("merge-save");
        save.setOnAction(e -> onSave.accept(ConflictParser.resolve(file, choices)));
        HBox bar = new HBox(8, status, spacer(), save);
        bar.getStyleClass().add("merge-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        return bar;
    }

    private ScrollPane buildBody() {
        VBox list = new VBox(8);
        list.setPadding(new Insets(8));
        int conflictIndex = 0;
        for (Segment seg : file.segments()) {
            if (seg instanceof PlainSegment p) {
                if (!p.lines().isEmpty()) {
                    Label note = new Label(tr("merge.unchanged", p.lines().size()));
                    note.getStyleClass().add("merge-context");
                    list.getChildren().add(note);
                }
            } else if (seg instanceof ConflictSegment cs) {
                list.getChildren().add(buildConflictCard(conflictIndex, cs.conflict()));
                conflictIndex++;
            }
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("merge-scroll");
        return scroll;
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
            sides = new HBox(8, ours, base, theirs);
        } else {
            sides = new HBox(8, ours, theirs);
        }

        Label chosen = new Label();
        chosen.getStyleClass().add("merge-chosen");
        Button acceptOurs = new Button(tr("merge.acceptOurs"));
        Button acceptTheirs = new Button(tr("merge.acceptTheirs"));
        Button acceptBoth = new Button(tr("merge.acceptBoth"));
        acceptOurs.setOnAction(e -> choose(index, Choice.OURS, chosen, tr("merge.kept.ours")));
        acceptTheirs.setOnAction(e -> choose(index, Choice.THEIRS, chosen, tr("merge.kept.theirs")));
        acceptBoth.setOnAction(e -> choose(index, Choice.BOTH, chosen, tr("merge.kept.both")));
        HBox actions = new HBox(6, acceptOurs, acceptTheirs, acceptBoth);
        if (c.hasBase()) {
            Button acceptBase = new Button(tr("merge.acceptBase"));
            acceptBase.setOnAction(e -> choose(index, Choice.BASE, chosen, tr("merge.kept.base")));
            actions.getChildren().add(acceptBase);
        }
        actions.getChildren().addAll(spacer(), chosen);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(4, header, sides, actions);
        card.getStyleClass().add("merge-conflict-card");
        card.setPadding(new Insets(6));
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
        VBox box = new VBox(2, l, area);
        return box;
    }

    private void choose(int index, Choice choice, Label chosen, String text) {
        choices.set(index, choice);
        chosen.setText(text);
        refreshStatus();
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
}
