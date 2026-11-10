package com.editora.ui;

import javafx.scene.control.ToggleButton;

/**
 * A toggle of a Map card's bar that stays on when pressed again, so one of its group always is: the Dependencies card's
 * Around / Inside and Files / Folders, the Code Metrics card's Files / Parts.
 */
final class MapStayOnToggle extends ToggleButton {

    MapStayOnToggle(String text) {
        super(text);
        setMnemonicParsing(false);
    }

    @Override
    public void fire() {
        if (!isSelected()) {
            super.fire();
        }
    }
}
