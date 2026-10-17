package com.editora.ui;

import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.command.TextInputKeymap;

import static com.editora.i18n.Messages.tr;

/**
 * The response view's card for a request that got no response, in the Body tab: a title naming the kind of failure
 * ({@link HttpFailureKind}: "Connection refused", "Timed out"…), the explanation the engine gave (selectable, so a
 * host name or a certificate detail can be copied), and what to do next — Run again, Copy as cURL (enabled as the
 * strip's icon is), and for a pre-request script that did not run, its output in the Tests tab. It replaced a
 * "⚠ text" line in the body's place, which looked like a header dump and suggested nothing.
 */
final class HttpErrorCard extends VBox {

    private final Label title = new Label();
    private final TextArea explanation = new TextArea();
    private final Button runAgain = new Button(tr("httppanel.error.runAgain"));
    private final Button copyCurl = new Button(tr("httppanel.copyAsCurl"));
    private final Button showTests = new Button(tr("httppanel.error.showTests"));
    private HttpFailureKind kind = HttpFailureKind.OTHER;

    HttpErrorCard(Runnable onRunAgain, Runnable onCopyCurl, ObservableBooleanValue copyDisabled, Runnable onShowTests) {
        getStyleClass().add("http-error-card");
        setSpacing(8);
        setPadding(new Insets(12, 14, 12, 14));
        setMaxHeight(Region.USE_PREF_SIZE);
        title.getStyleClass().add("http-error-title");
        title.setWrapText(true);
        explanation.getStyleClass().add("http-error-text");
        explanation.setEditable(false);
        explanation.setWrapText(true);
        explanation.setFocusTraversable(true);
        runAgain.getStyleClass().addAll("accent", "http-error-run");
        runAgain.setOnAction(e -> onRunAgain.run());
        copyCurl.getStyleClass().add("http-error-curl");
        copyCurl.setOnAction(e -> onCopyCurl.run());
        copyCurl.disableProperty().bind(copyDisabled);
        showTests.getStyleClass().add("http-error-tests");
        showTests.setOnAction(e -> onShowTests.run());
        for (Button b : new Button[] {runAgain, copyCurl, showTests}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setMnemonicParsing(false);
        }
        HBox actions = new HBox(8, runAgain, copyCurl, showTests);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(title, explanation, actions);
        TextInputKeymap.installSharedReadOnly(explanation);
    }

    /** Shows a failure of {@code kind} explained by {@code text}. */
    void show(HttpFailureKind kind, String text) {
        this.kind = kind == null ? HttpFailureKind.OTHER : kind;
        title.setText(this.kind.title());
        String shown = text == null ? "" : text.strip();
        explanation.setText(shown);
        explanation.positionCaret(0);
        // Tall enough for the text (a long URL wraps), never a tall empty box.
        int lines = 1;
        for (String l : shown.split("\n", -1)) {
            lines += l.length() / 90;
        }
        explanation.setPrefRowCount(Math.max(1, Math.min(8, lines + shown.split("\n", -1).length - 1)));
        boolean script = this.kind == HttpFailureKind.SCRIPT;
        showTests.setVisible(script);
        showTests.setManaged(script);
    }

    /** A remote file's requests do not run from here. */
    void setRunBlocked(boolean blocked) {
        runAgain.setDisable(blocked);
    }

    // --- test accessors ---

    HttpFailureKind kindForTest() {
        return kind;
    }

    String titleForTest() {
        return title.getText();
    }

    String explanationForTest() {
        return explanation.getText();
    }

    Button runAgainForTest() {
        return runAgain;
    }

    Button copyCurlForTest() {
        return copyCurl;
    }
}
