package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The card a review thread opens at its lines: its comments, a suggestion's text, a box to reply in, and what one
 * does to a thread — resolve or reopen it, dismiss it with a reason, apply its suggestion, edit your comment, delete
 * it. An in-scene overlay ({@link OverlayHost}) like {@link GitHunkPopup}: {@code Esc} and a click outside close it,
 * and each button's action runs after the card has closed, with the focus back in the editor.
 */
final class ReviewThreadCard {

    /** What the card's buttons do. */
    interface Actions {
        void reply(ReviewThread t, String body);

        void resolve(ReviewThread t);

        void reopen(ReviewThread t);

        void dismiss(ReviewThread t);

        void edit(ReviewThread t, ReviewComment c);

        void delete(ReviewThread t);

        void applySuggestion(ReviewThread t, String suggestion);

        /** Asks the AI Agent panel to do what the thread asks. */
        void fixWithAgent(ReviewThread t);
    }

    /** Most rows the comments take before they scroll. */
    static final double MAX_COMMENTS_HEIGHT = 320;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OverlayHost overlayHost;
    private final Label title = new Label();
    private final Label status = new Label();
    private final VBox comments = new VBox();
    private final ScrollPane commentsScroll = new ScrollPane(comments);
    private final TextArea reply = new TextArea();
    private final Button send = button("review.card.reply");
    private final Button resolve = button("review.card.resolve");
    private final Button dismiss = button("review.card.dismiss");
    private final Button apply = button("review.card.apply");
    private final Button fix = button("review.card.fix");
    private final Button edit = button("review.card.edit");
    private final Button delete = button("review.card.delete");
    private final VBox card;
    private @Nullable ReviewThread shown;
    private @Nullable Actions actions;
    private @Nullable String suggestion;
    private @Nullable ReviewComment editable;

    ReviewThreadCard(OverlayHost overlayHost, Consumer<TextInputControl> keys) {
        this.overlayHost = overlayHost;
        title.getStyleClass().add("palette-title");
        status.getStyleClass().add("review-card-status");
        status.setWrapText(true);
        comments.getStyleClass().add("review-card-comments");
        UiZoom.spacing(comments, 8);
        commentsScroll.setFitToWidth(true);
        commentsScroll.getStyleClass().add("review-card-scroll");
        commentsScroll.setMaxHeight(UiZoom.px(MAX_COMMENTS_HEIGHT));
        reply.setWrapText(true);
        reply.setPrefRowCount(3);
        reply.setPromptText(tr("review.card.replyPrompt"));
        keys.accept(reply);
        // Ctrl/Cmd+Enter sends, as in a review on the web; a plain Enter is a new line of the comment.
        reply.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                e.consume();
                send.fire();
            }
        });
        WrapRow buttons = new WrapRow(6, 6, send, resolve, dismiss, apply, fix, edit, delete);
        buttons.getStyleClass().add("git-hunk-actions");
        card = UiZoom.spacing(new VBox(title, status, commentsScroll, reply, buttons), 6);
        card.getStyleClass().addAll("command-palette", "review-card");
        UiZoom.prefWidth(card, 560);
        UiZoom.maxSize(card, 560, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        send.setOnAction(e -> {
            String body = reply.getText().strip();
            if (!body.isEmpty()) {
                closeThen((a, t) -> a.reply(t, body));
            }
        });
        resolve.setOnAction(e -> closeThen((a, t) -> {
            if (t.open()) {
                a.resolve(t);
            } else {
                a.reopen(t);
            }
        }));
        dismiss.setOnAction(e -> closeThen(Actions::dismiss));
        apply.setOnAction(e -> {
            String s = suggestion;
            if (s != null) {
                closeThen((a, t) -> a.applySuggestion(t, s));
            }
        });
        edit.setOnAction(e -> {
            ReviewComment c = editable;
            if (c != null) {
                closeThen((a, t) -> a.edit(t, c));
            }
        });
        delete.setOnAction(e -> closeThen(Actions::delete));
        fix.setOnAction(e -> closeThen(Actions::fixWithAgent));
    }

    private static Button button(String key) {
        Button b = new Button(tr(key));
        b.getStyleClass().add("small");
        return b;
    }

    /** Closes the card, then runs {@code run} on the thread it showed (read first: closing forgets it). */
    private void closeThen(java.util.function.BiConsumer<Actions, ReviewThread> run) {
        Actions a = actions;
        ReviewThread t = shown;
        overlayHost.hide();
        if (a != null && t != null) {
            run.accept(a, t);
        }
    }

    /**
     * Shows thread {@code t} on {@code path}'s lines {@code first…last} (0-based, where its text is now).
     *
     * @param anchor {@code {x, belowY, aboveY}} in scene coordinates (see {@link OverlayHost#showAt})
     */
    void show(ReviewThread t, String path, boolean outdated, int first, int last, double[] anchor, Actions actions) {
        this.shown = t;
        this.actions = actions;
        title.setText(path + " · " + ReviewCoordinator.lineRange(first, last));
        status.setText(statusText(t, outdated));
        comments.getChildren().clear();
        suggestion = null;
        editable = null;
        for (ReviewComment c : t.comments()) {
            comments.getChildren().add(comment(c));
            if (c.suggestion() != null) {
                suggestion = c.suggestion(); // the latest suggestion is the one to apply
            }
            if (c.author().kind() == ReviewAuthor.Kind.USER) {
                editable = c; // your latest comment
            }
        }
        reply.clear();
        resolve.setText(tr(t.open() ? "review.card.resolve" : "review.card.reopen"));
        dismiss.setDisable(t.state() == ReviewThread.State.DISMISSED);
        apply.setDisable(suggestion == null || outdated);
        fix.setDisable(!t.open());
        edit.setDisable(editable == null);
        overlayHost.showAt(card, anchor[0], anchor[1], anchor[2], reply::requestFocus, () -> shown = null);
    }

    /** What the line under the title says: the state, an agent's severity, and when the code moved on. Pure. */
    static String statusText(ReviewThread t, boolean outdated) {
        StringBuilder s =
                new StringBuilder(tr("review.state." + t.state().name().toLowerCase(java.util.Locale.ROOT)));
        if (t.stateNote() != null && !t.stateNote().isBlank()) {
            s.append(" — ").append(t.stateNote());
        }
        if (t.severity() != null) {
            s.append(" · ").append(t.severity().name());
            if (t.category() != null && !t.category().isBlank()) {
                s.append(" · ").append(t.category());
            }
        }
        if (outdated) {
            s.append(" · ").append(tr("review.outdated"));
        }
        return s.toString();
    }

    /** Who wrote a comment, as the card shows it. Pure. */
    static String author(ReviewAuthor a) {
        return switch (a.kind()) {
            case USER -> tr("review.author.you");
            case AGENT -> a.name().isBlank() ? tr("review.author.agent") : a.name();
            case GITHUB -> "@" + a.name();
        };
    }

    private Region comment(ReviewComment c) {
        Label who = new Label(author(c.author()) + " · "
                + WHEN.format(Instant.ofEpochMilli(c.createdAt()).atZone(ZoneId.systemDefault()))
                + (c.editedAt() > 0 ? " · " + tr("review.edited") : ""));
        who.getStyleClass().add("review-card-author");
        VBox box = new VBox(who);
        if (!c.body().isBlank()) {
            Label body = new Label(c.body());
            body.setWrapText(true);
            body.getStyleClass().add("review-card-body");
            box.getChildren().add(body);
        }
        if (c.suggestion() != null) {
            Label caption = new Label(tr("review.card.suggested"));
            caption.getStyleClass().add("git-hunk-caption");
            TextArea code = new TextArea(c.suggestion());
            code.setEditable(false);
            code.setWrapText(false);
            code.getStyleClass().addAll("git-hunk-side", "git-hunk-new");
            code.setPrefRowCount(Math.clamp(c.suggestion().split("\n", -1).length, 1, 8));
            box.getChildren().addAll(caption, code);
        }
        UiZoom.spacing(box, 2);
        box.getStyleClass().add("review-card-comment");
        return box;
    }

    boolean isShowing() {
        return shown != null;
    }

    /** The card, for tests. */
    VBox card() {
        return card;
    }

    TextArea replyBox() {
        return reply;
    }
}
