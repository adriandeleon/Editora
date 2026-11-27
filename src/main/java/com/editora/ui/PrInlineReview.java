package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.UiZoom;
import com.editora.github.GhInvocation;
import com.editora.github.PrLineDraft;
import com.editora.github.PrThreads;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Line comments on a pull request, in its review tab and the file diffs opened from it: the threads already on
 * GitHub (read with {@link PrThreads}), marked in each diff's gutter and shown in a card at their line to read, reply
 * to, resolve or unresolve; and the comments the user drafts on lines of either side, kept here (in memory, with the
 * review tab) until the review is submitted, when they go with it as its line comments. Nothing reaches GitHub but
 * what the user sends: a reply, a resolve, or the review.
 */
final class PrInlineReview {

    /** What it needs from the window. */
    interface Host {
        OverlayHost overlayHost();

        void setStatus(String message);

        /** Runs one {@code gh} call in {@code dir}; the answer comes on the FX thread. */
        void gh(Path dir, GhInvocation call, Consumer<ProcessRunner.Result> done);

        void installKeys(TextInputControl control);

        void openUrl(String url);

        /** Asks a yes/no question; true for yes. */
        boolean confirm(Confirm.Question question);
    }

    /** One pull request's inline review: where it is, what it is pinned to, its drafts and threads, its diffs. */
    static final class State {
        final Path dir;
        final int number;
        String url = "";
        String head = "";
        final List<PrLineDraft> drafts = new ArrayList<>();
        List<PrThreads.Thread> threads = List.of();
        /** The open file diffs, by the file's path in the pull request. */
        final Map<String, DiffViewerPane> panes = new HashMap<>();

        @Nullable
        PrReviewPane tab;

        State(Path dir, int number) {
            this.dir = dir;
            this.number = number;
        }
    }

    private final Host host;
    private final Map<GitHubCoordinator.ReviewKey, State> states = new HashMap<>();

    PrInlineReview(Host host) {
        this.host = host;
    }

    /** The state of the review of {@code key}, made on first use. */
    State state(GitHubCoordinator.ReviewKey key) {
        return states.computeIfAbsent(key, k -> new State(k.dir(), k.number()));
    }

    /** The review tab of {@code key} closed: its drafts and threads go with it. */
    void closed(GitHubCoordinator.ReviewKey key) {
        states.remove(key);
    }

    /** The tab shows {@code state}'s review: its summary line, and a close question while drafts wait. */
    void attachTab(State state, PrReviewPane tab) {
        state.tab = tab;
        tab.setReviewState(state.drafts::size, () -> summary(state));
    }

    /** What the tab's summary line says: drafts waiting to be sent, and the threads already on GitHub. Pure. */
    static String summary(State state) {
        long open = state.threads.stream().filter(t -> !t.resolved()).count();
        if (state.drafts.isEmpty() && state.threads.isEmpty()) {
            return "";
        }
        return tr("github.review.summary", state.drafts.size(), open, state.threads.size() - open);
    }

    /** The head commit and address the review is pinned to (from the detail on screen). */
    void pinned(State state, String head, String url) {
        state.head = head == null ? "" : head;
        state.url = url == null ? "" : url;
    }

    /** Reads the pull request's threads from GitHub and marks them. */
    void loadThreads(State state) {
        GhInvocation call = PrThreads.read(state.url, state.number);
        if (call == null) {
            return; // no address to ask: the pull request's detail could not be read
        }
        host.gh(state.dir, call, r -> {
            if (r.ok()) {
                state.threads = PrThreads.parse(r.out());
                changed(state);
            } else {
                host.setStatus(tr("status.github.threadsFailed"));
            }
        });
    }

    /** A file diff opened from the review tab: its sides' threads and drafts are marked, and it takes comments. */
    void attachDiff(State state, DiffViewerPane pane, String path) {
        state.panes.put(path, pane);
        pane.setLineHook(new DiffViewerPane.LineHook() {
            @Override
            public java.util.Set<Integer> marked(boolean left) {
                java.util.Set<Integer> lines = new java.util.HashSet<>();
                for (PrThreads.Thread t : state.threads) {
                    if (t.line() > 0 && t.path().equals(path) && t.left() == left) {
                        lines.add(t.line());
                    }
                }
                for (PrLineDraft d : state.drafts) {
                    if (d.path().equals(path) && d.left() == left) {
                        lines.add(d.line());
                    }
                }
                return lines;
            }

            @Override
            public void open(boolean left, int line) {
                showCard(state, pane, path, left, line);
            }

            @Override
            public void comment(boolean left, int first, int last) {
                draftDialog(state, path, left, first, last, null);
            }
        });
    }

    private void changed(State state) {
        state.panes.values().forEach(DiffViewerPane::refreshReviewMarks);
        if (state.tab != null) {
            state.tab.reviewChanged();
        }
    }

    // --- drafts --------------------------------------------------------------------------------------

    /** The dialog that drafts (or, with {@code editing}, edits) a comment on lines {@code first..last} of a side. */
    void draftDialog(State state, String path, boolean left, int first, int last, @Nullable PrLineDraft editing) {
        TextArea editor = new TextArea(editing == null ? "" : editing.body());
        editor.setWrapText(true);
        editor.setPrefRowCount(7);
        editor.setPrefColumnCount(48);
        host.installKeys(editor);
        Label hint = new Label(tr("github.review.draftHint"));
        hint.getStyleClass().add("note-dialog-hint");
        hint.setWrapText(true);
        VBox body = UiZoom.spacing(new VBox(new Label(tr("review.dialog.prompt")), editor, hint), 6);
        String where = path + " · " + (left ? tr("diff.side.prBase") : tr("diff.side.prHead")) + " · "
                + ReviewCoordinator.lineRange(first - 1, last - 1);
        OverlayInput.show(
                host.overlayHost(),
                tr("github.review.draftTitle", where),
                body,
                editor,
                tr("github.review.saveDraft"),
                null,
                () -> {
                    String text = editor.getText().strip();
                    if (text.isEmpty()) {
                        return;
                    }
                    if (editing != null) {
                        state.drafts.replaceAll(d -> d.id().equals(editing.id()) ? d.withBody(text) : d);
                    } else {
                        state.drafts.add(PrLineDraft.create(path, left, first, last, text));
                    }
                    changed(state);
                    host.setStatus(tr("status.github.draftSaved", state.drafts.size()));
                },
                null,
                true);
    }

    /** The drafts to send with the review; cleared once it is sent. */
    List<PrLineDraft> drafts(State state) {
        return List.copyOf(state.drafts);
    }

    /** The review went through: its drafts are on GitHub now, as threads to read again. */
    void submitted(State state) {
        state.drafts.clear();
        changed(state);
        loadThreads(state);
    }

    // --- the card ------------------------------------------------------------------------------------

    /** The card for {@code line} of a side: its threads (read, reply, resolve) and drafts (edit, delete). */
    void showCard(State state, DiffViewerPane pane, String path, boolean left, int line) {
        double[] anchor = pane.lineAnchor(left, line);
        if (anchor == null) {
            return;
        }
        List<PrThreads.Thread> threads =
                state.threads.stream().filter(t -> t.on(path, left, line)).toList();
        List<PrLineDraft> drafts =
                state.drafts.stream().filter(d -> d.on(path, left, line)).toList();
        VBox card = new VBox();
        card.getStyleClass().addAll("command-palette", "review-card");
        UiZoom.spacing(card, 6);
        UiZoom.prefWidth(card, 560);
        UiZoom.maxSize(card, 560, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        Label title = new Label(path + " · " + (left ? tr("diff.side.prBase") : tr("diff.side.prHead")) + " · "
                + tr("review.line", line));
        title.getStyleClass().add("palette-title");
        card.getChildren().add(title);
        for (PrThreads.Thread t : threads) {
            card.getChildren().add(threadBox(state, t));
        }
        for (PrLineDraft d : drafts) {
            card.getChildren().add(draftBox(state, d));
        }
        Button add = small(tr("github.review.addHere"));
        add.setOnAction(e -> {
            host.overlayHost().hide();
            draftDialog(state, path, left, line, line, null);
        });
        card.getChildren().add(add);
        host.overlayHost().showAt(card, anchor[0], anchor[1], anchor[2], add::requestFocus, () -> {});
    }

    private Region threadBox(State state, PrThreads.Thread t) {
        VBox box = UiZoom.spacing(new VBox(), 4);
        box.getStyleClass().add("review-card-comment");
        String status = tr(t.resolved() ? "review.state.resolved" : "review.state.open")
                + (t.outdated() ? " · " + tr("review.outdated") : "");
        Label state1 = new Label(status);
        state1.getStyleClass().add("review-card-status");
        box.getChildren().add(state1);
        for (PrThreads.Comment c : t.comments()) {
            Label who = new Label("@" + c.author() + (c.createdAt().isEmpty() ? "" : " · " + c.createdAt()));
            who.getStyleClass().add("review-card-author");
            Label body = new Label(c.body());
            body.setWrapText(true);
            body.getStyleClass().add("review-card-body");
            box.getChildren().addAll(who, body);
        }
        TextArea reply = new TextArea();
        reply.setWrapText(true);
        reply.setPrefRowCount(2);
        reply.setPromptText(tr("review.card.replyPrompt"));
        host.installKeys(reply);
        Button send = small(tr("review.card.reply"));
        send.setOnAction(e -> {
            String text = reply.getText().strip();
            if (!text.isEmpty()) {
                host.overlayHost().hide();
                reply(state, t, text);
            }
        });
        Button resolve = small(tr(t.resolved() ? "review.card.reopen" : "review.card.resolve"));
        resolve.setOnAction(e -> {
            host.overlayHost().hide();
            resolve(state, t, !t.resolved());
        });
        Button web = small(tr("github.review.openOnGitHub"));
        String url = t.comments().isEmpty() ? "" : t.comments().get(0).url();
        web.setDisable(url.isEmpty());
        web.setOnAction(e -> {
            host.overlayHost().hide();
            host.openUrl(url);
        });
        box.getChildren().addAll(reply, new WrapRow(6, 6, send, resolve, web));
        return box;
    }

    private Region draftBox(State state, PrLineDraft d) {
        VBox box = UiZoom.spacing(new VBox(), 4);
        box.getStyleClass().add("review-card-comment");
        Label who = new Label(tr("github.review.yourDraft"));
        who.getStyleClass().add("review-card-author");
        Label body = new Label(d.body());
        body.setWrapText(true);
        Button edit = small(tr("review.card.edit"));
        edit.setOnAction(e -> {
            host.overlayHost().hide();
            draftDialog(state, d.path(), d.left(), d.startLine(), d.line(), d);
        });
        Button delete = small(tr("github.review.deleteDraft"));
        delete.setOnAction(e -> {
            host.overlayHost().hide();
            state.drafts.removeIf(x -> x.id().equals(d.id()));
            changed(state);
        });
        box.getChildren().addAll(who, body, new WrapRow(6, 6, edit, delete));
        return box;
    }

    private static Button small(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("small");
        return b;
    }

    /** Sends {@code body} as a reply to {@code t} on GitHub, then reads the threads again. */
    void reply(State state, PrThreads.Thread t, String body) {
        GhInvocation call = PrThreads.reply(state.url, state.number, t.id(), body);
        if (call == null) {
            host.setStatus(tr("status.github.threadsFailed"));
            return;
        }
        host.setStatus(tr("status.github.replying"));
        host.gh(state.dir, call, r -> {
            if (r.ok() && !PrThreads.failed(r.out())) {
                host.setStatus(tr("status.github.replied"));
                loadThreads(state);
            } else {
                host.setStatus(tr("status.github.replyFailed"));
            }
        });
    }

    /** Resolves (or unresolves) {@code t} on GitHub, then reads the threads again. */
    void resolve(State state, PrThreads.Thread t, boolean resolve) {
        GhInvocation call = PrThreads.resolve(state.url, state.number, t.id(), resolve);
        if (call == null) {
            host.setStatus(tr("status.github.threadsFailed"));
            return;
        }
        host.gh(state.dir, call, r -> {
            if (r.ok() && !PrThreads.failed(r.out())) {
                host.setStatus(tr(resolve ? "status.github.threadResolved" : "status.github.threadReopened"));
                loadThreads(state);
            } else {
                host.setStatus(tr("status.github.resolveFailed"));
            }
        });
    }
}
