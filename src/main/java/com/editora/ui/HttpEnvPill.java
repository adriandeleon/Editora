package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

import static com.editora.i18n.Messages.tr;

/**
 * The environment pill of a {@code .http} editor ("● Env: dev ▾"), floating beside its Editor/Split/Preview toggle in
 * every view mode and from the moment the file opens, so the environment a run will use is visible — and choosable —
 * before the first run (U3). Clicking it opens the file's environment menu, which the HTTP Client coordinator builds
 * ({@link HttpClientCoordinator#showEnvironmentMenu}); this control only shows the choice.
 */
final class HttpEnvPill extends Button {

    private final Circle dot = new Circle(3.5);
    private final Label text = new Label();
    private final Tooltip tip = new Tooltip();

    HttpEnvPill(Runnable onOpen) {
        getStyleClass().addAll("http-env-pill", "flat");
        dot.getStyleClass().add("http-env-dot");
        text.getStyleClass().add("http-env-pill-text");
        Label chevron = new Label("▾");
        chevron.getStyleClass().add("http-env-pill-chevron");
        HBox content = new HBox(6, dot, text, chevron);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        setGraphic(content);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE); // an overlay in a StackPane must not stretch to fill it
        setFocusTraversable(false); // like the view toggle: the keyboard reaches it through http.selectEnvironment
        setTooltip(tip);
        setOnAction(e -> onOpen.run());
        show("", "");
    }

    /**
     * Shows {@code selected} ({@code ""}: none, muted) and, in the tooltip, the env files the choices come from
     * ({@code sources}, "" when there are none).
     */
    void show(String selected, String sources) {
        boolean none = selected == null || selected.isEmpty();
        String label = HttpEnvChoice.label(selected);
        text.setText(label);
        pseudoClassStateChanged(NONE, none);
        tip.setText(sources == null || sources.isEmpty() ? tr("httpenv.tip.noFile") : tr("httpenv.tip", sources));
        setAccessibleText(label);
    }

    /** The text it shows ("Env: dev"). Test accessor. */
    String textForTest() {
        return text.getText();
    }

    /** Whether it shows "No environment" (muted). Test accessor. */
    boolean showsNoneForTest() {
        return getPseudoClassStates().contains(NONE);
    }

    private static final javafx.css.PseudoClass NONE = javafx.css.PseudoClass.getPseudoClass("none");
}
