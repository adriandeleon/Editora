package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's ? popover: what the Map is for, and its pointer, card, keyboard, search and filter-row models
 * (moved out of {@code ProjectMapView} in the Map's refactor stage 2). Pure: builds a fresh node each call.
 */
final class ProjectMapHelp {

    private ProjectMapHelp() {}

    /**
     * The popover's content: what the Map is for, then the pointer, cards and notes, keyboard and filter-row
     * models in two columns. It follows the doc's interaction reference (E8). Key names are spelled in the
     * user's language and modifiers as the platform shows them (⌘ ⌥ ⇧ on macOS), never English key-code names.
     */
    static Node build() {
        Label intro = new Label(tr("project.map.help.intro"));
        intro.getStyleClass().addAll("project-map-help-text", "project-map-help-intro");
        intro.setWrapText(true);
        UiZoom.maxWidth(intro, 560);

        GridPane pointer = grid();
        int row = addHeading(pointer, 0, "project.map.help.mouse");
        for (String id : List.of(
                "click",
                "doubleClick",
                "chevron",
                "preview",
                "structure",
                "symbol",
                "symbolChevron",
                "noteBadge",
                "rightClick")) {
            row = addRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = addRow(pointer, row, tr("project.map.help.wheel.gesture"), "project.map.help.wheel");
        row = addRow(
                pointer,
                row,
                tr("project.map.help.fastWheel.gesture", modifier("shortcut")),
                "project.map.help.fastWheel");
        for (String id : List.of(
                "swipe", "pinch", "panWheel", "dragCanvas", "middleDrag", "dragHeader", "lock", "close", "overview")) {
            row = addRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = addHeading(pointer, row, "project.map.help.cards");
        // What cards are for, first (Map explore round, position 12): a glance; the editor is for reading and editing.
        Label cardsIntro = new Label(tr("project.map.help.cardsIntro"));
        cardsIntro.getStyleClass().addAll("project-map-help-text", "project-map-help-cards-intro");
        cardsIntro.setWrapText(true);
        UiZoom.maxWidth(cardsIntro, 320);
        pointer.add(cardsIntro, 0, row++, 2, 1);
        for (String id :
                List.of("cardTitle", "cardGrip", "cardStructure", "cardNotes", "addNote", "sticky", "cardPin")) {
            row = addRow(pointer, row, tr("project.map.help." + id + ".gesture"), "project.map.help." + id);
        }
        row = addRow(
                pointer,
                row,
                tr(
                        "project.map.help.cardStep.gesture",
                        chord("alt", key("pageUp")) + " / " + chord("alt", key("pageDown"))),
                "project.map.help.cardStep");
        row = addRow(
                pointer,
                row,
                tr("project.map.help.cardFind.gesture", chord("shortcut", "F")),
                "project.map.help.cardFind");
        row = addRow(
                pointer,
                row,
                tr("project.map.help.cardNavigate.gesture", modifier("shortcut")),
                "project.map.help.cardNavigate");

        GridPane keys = grid();
        row = addHeading(keys, 0, "project.map.help.keyboard");
        row = addRow(keys, row, "↑ ↓ ← →", "project.map.help.arrows");
        row = addRow(keys, row, chord("control", "N") + " / " + chord("control", "P"), "project.map.help.siblings");
        row = addRow(keys, row, key("enter") + " / " + key("space"), "project.map.help.activate");
        row = addRow(keys, row, chord("shift", key("enter")), "project.map.help.shiftEnter");
        row = addRow(keys, row, key("backspace"), "project.map.help.parent");
        row = addRow(keys, row, key("home") + " / " + key("end"), "project.map.help.home");
        row = addRow(keys, row, tr("project.map.help.typeAhead.gesture"), "project.map.help.typeAhead");
        row = addRow(keys, row, key("pageUp") + " / " + key("pageDown"), "project.map.help.page");
        row = addRow(keys, row, "/", "project.map.help.columnFilter");
        row = addRow(keys, row, chord("alt", "←") + " / " + chord("alt", "→"), "project.map.help.history");
        row = addRow(keys, row, chord("control", chord("shift", "← → ↑ ↓")), "project.map.help.cardsToward");
        row = addRow(keys, row, chord("shortcut", "←") + " / " + chord("shortcut", "→"), "project.map.help.trailStep");
        row = addRow(keys, row, chord("shortcut", "0"), "project.map.help.fit");
        row = addRow(keys, row, "F2", "project.map.help.rename");
        row = addRow(keys, row, key("delete"), "project.map.help.delete");
        row = addRow(keys, row, key("menu") + " / " + chord("shift", "F10"), "project.map.help.menu");
        row = addRow(keys, row, key("escape"), "project.map.help.escape");
        // What the search field finds and how to move through its matches, and how to find where you are
        // (review 3 B19): the popover said nothing about either.
        row = addHeading(keys, row, "project.map.help.search");
        row = addRow(
                keys,
                row,
                tr("project.map.help.searchField.gesture", tr("project.map.filterPrompt")),
                "project.map.help.searchField");
        row = addRow(keys, row, "@", "project.map.help.searchSymbols");
        row = addRow(keys, row, "#", "project.map.help.searchFiles");
        row = addRow(keys, row, "%", "project.map.help.searchText");
        row = addRow(keys, row, "↓", "project.map.help.searchDown");
        row = addRow(keys, row, key("enter") + " / " + chord("shift", key("enter")), "project.map.help.searchStep");
        row = addRow(keys, row, chord("shortcut", key("enter")), "project.map.help.searchOpen");
        row = addRow(keys, row, "F3 / " + chord("shift", "F3"), "project.map.help.searchStepMap");
        row = addHeading(keys, row, "project.map.help.where");
        row = addRow(keys, row, tr("project.map.help.reveal.gesture"), "project.map.help.reveal");
        row = addRow(keys, row, tr("project.map.navigation.followActiveFile"), "project.map.help.follow");
        row = addRow(keys, row, tr("project.map.overview.open"), "project.map.help.overviewCard");
        row = addHeading(keys, row, "project.map.help.controls");
        row = addRow(keys, row, tr("project.map.help.chips.gesture"), "project.map.help.chips");
        row = addRow(keys, row, tr("project.map.filter.hideUnmatched"), "project.map.help.hideUnmatched");
        addRow(keys, row, tr("project.map.filter.clear"), "project.map.help.clear");

        HBox columns = UiZoom.spacing(new HBox(pointer, keys), 24);
        VBox help = UiZoom.spacing(new VBox(intro, columns), 8);
        help.getStyleClass().add("project-map-help");
        return help;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        UiZoom.gaps(grid, 12, 3);
        return grid;
    }

    private static int addHeading(GridPane grid, int row, String key) {
        Label heading = new Label(tr(key));
        heading.getStyleClass().add("project-map-help-heading");
        grid.add(heading, 0, row, 2, 1);
        return row + 1;
    }

    private static int addRow(GridPane grid, int row, String gesture, String descriptionKey) {
        Label keys = new Label(gesture);
        keys.getStyleClass().add("project-map-help-gesture");
        keys.setMinWidth(Region.USE_PREF_SIZE);
        Label description = new Label(tr(descriptionKey));
        description.getStyleClass().add("project-map-help-text");
        description.setWrapText(true);
        UiZoom.maxWidth(description, 250);
        grid.add(keys, 0, row);
        grid.add(description, 1, row);
        return row + 1;
    }

    /** A key's name in the user's language ("Eingabe", "Rücktaste"); these are fixed map keys, not bindings. */
    static String key(String id) {
        return tr("project.map.help.key." + id);
    }

    /**
     * A modifier as the platform writes it: ⌘ ⌥ ⇧ ⌃ on macOS, Ctrl, Alt and Shift elsewhere. The names are
     * the same in every language, as on the key caps and in the keymaps, so they are not in the catalog.
     */
    static String modifier(String id) {
        if (com.editora.command.KeymapManager.isMac()) {
            return switch (id) {
                case "shortcut" -> "⌘";
                case "alt" -> "⌥";
                case "shift" -> "⇧";
                default -> "⌃";
            };
        }
        return switch (id) {
            case "alt" -> "Alt";
            case "shift" -> "Shift";
            default -> "Ctrl";
        };
    }

    /** {@code modifier} with {@code key}: "Ctrl+N", "⌃N". */
    static String chord(String modifier, String key) {
        String name = modifier(modifier);
        return com.editora.command.KeymapManager.isMac() ? name + key : name + "+" + key;
    }
}
