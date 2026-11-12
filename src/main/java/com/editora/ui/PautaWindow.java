package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;

import com.editora.AppInfo;
import com.editora.PautaConformance;
import com.editora.PautaConformance.Conformance;
import com.editora.PautaConformance.Entry;
import com.editora.PautaConformance.Kind;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Pauta window (pauta-project.dev, the Pauta window template): the edition Editora works toward, how fully
 * it conforms and every exception it records, built like {@link AboutDialog}.
 *
 * <p>It reads {@code pauta.json} through {@link PautaConformance} and the edition through {@link AppInfo}, and
 * takes the conformance from the same helper as About (PWIN-03): no rule ID, edition number or conformance
 * name is written here. Every link runs a command, so the palette reaches the same pages.
 */
final class PautaWindow {

    /** Runs a registered command by id: the window's links and its copy button go through the registry. */
    private final Consumer<String> runCommand;

    private final Consumer<String> openUrl;
    private final List<Entry> exceptions;
    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox list = new VBox(2);
    private final Button copy = new Button(tr("pauta.copy"));

    PautaWindow(@Nullable Window owner, Consumer<String> runCommand, Consumer<String> openUrl) {
        this(owner, runCommand, openUrl, PautaConformance.exceptions());
    }

    PautaWindow(@Nullable Window owner, Consumer<String> runCommand, Consumer<String> openUrl, List<Entry> exceptions) {
        this.runCommand = runCommand;
        this.openUrl = openUrl;
        this.exceptions = PautaConformance.sorted(exceptions);
        Conformance conformance = PautaConformance.of(exceptions);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr("dialog.pauta.title"));
        dialog.getDialogPane().getStyleClass().add("about-dialog");

        Label edition = new Label(tr("pauta.edition", AppInfo.PAUTA_EDITION));
        edition.getStyleClass().add("about-name");
        Label pill = new Label(conformanceName(conformance));
        pill.getStyleClass().addAll("pauta-conformance", conformance.key());
        Label meaning = new Label(meaning(conformance, exceptions));
        meaning.getStyleClass().add("about-tagline");
        meaning.setWrapText(true);
        VBox titleBox = new VBox(4, edition, new HBox(pill), meaning);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        HBox head = new HBox(16, mark(), titleBox);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, head);
        String counts = counts(exceptions);
        if (!counts.isEmpty()) {
            Label countsLabel = new Label(counts);
            countsLabel.getStyleClass().add("about-copyright");
            content.getChildren().add(countsLabel);
        }
        fillList();
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("pauta-list");
        content.getChildren().add(scroll);

        VBox links = new VBox(
                2,
                link(tr("pauta.rules", AppInfo.PAUTA_EDITION), "help.pautaRules"),
                link(tr("pauta.docs"), "help.pautaDocs"));
        // In the content, not the button bar, like About's Copy details: pressing it keeps the window open.
        copy.setOnAction(e -> {
            runCommand.accept("help.copyPauta");
            copy.setText(tr("pauta.copied"));
        });
        content.getChildren().addAll(links, copy);
        content.getStyleClass().add("about-content");
        content.setPrefWidth(560);

        dialog.getDialogPane().setContent(content);
        // One Close button, the default: Enter and Esc both close.
        ButtonType close = new ButtonType(tr("dialog.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(close);
        if (dialog.getDialogPane().lookupButton(close) instanceof Button closeButton) {
            closeButton.setDefaultButton(true);
        }
        Dialogs.styled(dialog);
    }

    /** One group header per kind present, then its rows (PWIN-05's order); one line when there are none. */
    private void fillList() {
        if (exceptions.isEmpty()) {
            Label none = new Label(tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION));
            none.setWrapText(true);
            list.getChildren().add(none);
            return;
        }
        Kind group = null;
        for (Entry entry : exceptions) {
            if (entry.kind() != group) {
                group = entry.kind();
                Label header = new Label(tr("pauta.kind." + group.key()));
                header.getStyleClass().add("pauta-group");
                list.getChildren().add(header);
            }
            list.getChildren().add(row(entry));
        }
    }

    /** The rule's ID linked to its address in this edition, its level, the reason and the entry's link. */
    private Node row(Entry entry) {
        Hyperlink id = new Hyperlink(entry.rule());
        id.getStyleClass().add("pauta-rule");
        id.setOnAction(e -> openUrl.accept(entry.url()));
        id.setMinWidth(84);
        Label level = new Label(entry.level());
        level.getStyleClass().add("pauta-level");
        level.setMinWidth(56);
        TextFlow reason = new TextFlow(new Text(entry.reason()));
        reason.getStyleClass().add("pauta-reason");
        String target = entry.link();
        if (target != null) {
            Hyperlink link = new Hyperlink(linkText(target));
            link.setOnAction(e -> openUrl.accept(target));
            reason.getChildren().addAll(new Text(" "), link);
        }
        HBox.setHgrow(reason, Priority.ALWAYS);
        HBox row = new HBox(8, id, level, reason);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("pauta-row");
        return row;
    }

    /** A pending entry's issue number, a deviation's decision number, else a plain "Details". */
    private static String linkText(String link) {
        String issue = PautaConformance.issueNumber(link);
        if (issue != null) {
            return issue;
        }
        String decision = PautaConformance.decisionNumber(link);
        if (decision != null) {
            return tr("pauta.decision", decision);
        }
        return tr("pauta.link");
    }

    private static Node mark() {
        // Background loading: opening the window decodes no image on the FX thread.
        java.net.URL url = PautaWindow.class.getResource("/com/editora/icons/pauta-128.png");
        if (url == null) {
            return new Label();
        }
        ImageView view = new ImageView(new Image(url.toExternalForm(), 72, 72, true, true, true));
        view.setFitWidth(72);
        view.setFitHeight(72);
        return view;
    }

    private Hyperlink link(String text, String commandId) {
        Hyperlink link = new Hyperlink(text);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(e -> runCommand.accept(commandId));
        return link;
    }

    /** Complete, Core or Partial, in the active language. */
    static String conformanceName(Conformance conformance) {
        return tr("pauta.conformance." + conformance.key());
    }

    /** The sentence under the pill: what the conformance means for this app. */
    static String meaning(Conformance conformance, List<Entry> exceptions) {
        return switch (conformance) {
            case COMPLETE -> tr("pauta.conformance.complete.desc", AppInfo.NAME);
            case CORE ->
                tr("pauta.conformance.core.desc", AppInfo.NAME, PautaConformance.pending(exceptions, "SHOULD"));
            case PARTIAL ->
                tr("pauta.conformance.partial.desc", AppInfo.NAME, PautaConformance.pending(exceptions, "MUST"));
        };
    }

    /** "3 pending · 1 deviation · 1 not applicable"; a kind with none is left out, and no exceptions is empty. */
    static String counts(List<Entry> exceptions) {
        StringJoiner line = new StringJoiner(" · ");
        for (Map.Entry<Kind, Integer> count :
                PautaConformance.counts(exceptions).entrySet()) {
            line.add(tr("pauta.count." + count.getKey().key(), count.getValue()));
        }
        return line.toString();
    }

    /** Copy as text (PWIN-06): the edition, the conformance and the list as Markdown, one line per exception. */
    static String markdown(List<Entry> exceptions) {
        Conformance conformance = PautaConformance.of(exceptions);
        StringBuilder text = new StringBuilder();
        text.append("# ")
                .append(tr("pauta.edition", AppInfo.PAUTA_EDITION))
                .append(": ")
                .append(conformanceName(conformance))
                .append("\n\n")
                .append(meaning(conformance, exceptions))
                .append('\n');
        if (exceptions.isEmpty()) {
            text.append('\n')
                    .append(tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION))
                    .append('\n');
        }
        Kind group = null;
        for (Entry entry : PautaConformance.sorted(exceptions)) {
            if (entry.kind() != group) {
                group = entry.kind();
                text.append("\n## ").append(tr("pauta.kind." + group.key())).append("\n\n");
            }
            text.append("- [")
                    .append(entry.rule())
                    .append("](")
                    .append(entry.url())
                    .append(") ")
                    .append(entry.level())
                    .append(": ")
                    .append(entry.reason());
            String link = entry.link();
            if (link != null) {
                text.append(" [")
                        .append(linkText(link))
                        .append("](")
                        .append(link)
                        .append(')');
            }
            text.append('\n');
        }
        text.append('\n').append(AppInfo.pautaUrl()).append('\n');
        return text.toString();
    }

    Dialog<Void> dialog() {
        return dialog;
    }

    VBox list() {
        return list;
    }

    Button copyButton() {
        return copy;
    }

    /** Shows the window, modal to its owner, and returns when it is closed, like About. */
    void show() {
        dialog.showAndWait();
    }
}
