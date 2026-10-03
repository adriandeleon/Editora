package com.editora.ui;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.CheckBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import atlantafx.base.controls.ToggleSwitch;

/**
 * The Settings on/off control: an AtlantaFX {@link ToggleSwitch} that a keyboard and a screen reader can
 * actually use. The stock switch only reacts to a mouse release and reports the accessible role
 * {@code PARENT}, so a row built from it could be tabbed to but never flipped, and was announced as an
 * anonymous group. This one toggles on Space and Enter, reports {@code TOGGLE_BUTTON} with its selected
 * state, and fires through the accessibility API.
 */
final class SettingSwitch extends ToggleSwitch {

    SettingSwitch() {
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);
        setFocusTraversable(true);
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (isToggleKey(e.getCode(), e.isShortcutDown() || e.isAltDown() || e.isControlDown() || e.isMetaDown())) {
                toggle();
                e.consume();
            }
        });
        selectedProperty().addListener((o, was, now) -> notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED));
    }

    /**
     * A switch that is a <em>view</em> of {@code check}: every Settings on/off value lives on a
     * {@link CheckBox} (listeners, {@code syncAll}, palette toggles), so the switch binds to its selected
     * and disabled state while the checkbox itself stays out of the scene graph.
     */
    static SettingSwitch boundTo(CheckBox check) {
        SettingSwitch sw = new SettingSwitch();
        if (check.getText() != null && !check.getText().isBlank()) {
            sw.setAccessibleText(check.getText()); // a row title replaces this when the row supplies one
        }
        sw.selectedProperty().bindBidirectional(check.selectedProperty());
        sw.disableProperty().bind(check.disableProperty());
        return sw;
    }

    /** Whether a key press flips the switch: an unmodified Space or Enter. Pure. */
    static boolean isToggleKey(KeyCode code, boolean modified) {
        return !modified && (code == KeyCode.SPACE || code == KeyCode.ENTER);
    }

    private void toggle() {
        if (!isDisabled()) {
            setSelected(!isSelected());
        }
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.SELECTED) {
            return isSelected();
        }
        return super.queryAccessibleAttribute(attribute, parameters);
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) {
            toggle();
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }
}
