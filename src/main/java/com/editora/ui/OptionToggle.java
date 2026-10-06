package com.editora.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/**
 * A compact toggle button that is a <em>view</em> of a search-option {@link CheckBox} (match case,
 * regex, whole word, …). The checkbox stays the state holder — every listener and reader keeps working —
 * but stays out of the scene: on screen the option is a short-label button whose meaning is spelled out
 * in its tooltip and its accessible name, instead of a checkbox captioned "Aa" or "W".
 */
final class OptionToggle {

    private OptionToggle() {}

    /** A toggle showing {@code state}'s short caption, named {@code name} for tooltips and screen readers. */
    static ToggleButton viewOf(CheckBox state, String name) {
        ToggleButton button = new ToggleButton(state.getText());
        button.selectedProperty().bindBidirectional(state.selectedProperty());
        button.disableProperty().bind(state.disableProperty());
        button.setTooltip(new Tooltip(name));
        button.setAccessibleText(name);
        button.getStyleClass().add("option-toggle");
        button.setMinWidth(Region.USE_PREF_SIZE); // never ellipsized to "…"
        return button;
    }
}
