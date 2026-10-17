package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.SingleSelectionModel;

import static com.editora.i18n.Messages.tr;

/**
 * The Light / Dark / Your themes groups of the app-theme and editor-theme pickers. A heading is an item of
 * its own in the combo (a marker string no theme can be named), shown as a group title that cannot be chosen:
 * its cell is disabled, so the mouse passes it by, and a selection that lands on it — arrow keys, Home/End,
 * a programmatic select — moves on to the theme next to it in the direction of travel.
 *
 * <p>Within a group the built-in themes keep their catalogue order, so a family's members stay together
 * (Primer, Nord, Cupertino…). Themes from the config folder get a group of their own at the end — that is
 * where someone looks for the theme they just added.
 */
final class ThemeGroups {

    /** Heading markers: they start with a control character, which no theme file name can. */
    static final String LIGHT = "\u0000light";

    static final String DARK = "\u0000dark";
    static final String USER = "\u0000user";

    private ThemeGroups() {}

    static boolean isHeading(String item) {
        return item != null && item.startsWith("\u0000");
    }

    /**
     * {@code names} split into a Light group and a Dark group (by {@code isDark}), each in {@code names}' own
     * order, then the {@code user} themes under their heading. A group with no themes has no heading. Pure.
     */
    static List<String> grouped(List<String> names, Predicate<String> isDark, Collection<String> user) {
        List<String> light = new ArrayList<>();
        List<String> dark = new ArrayList<>();
        List<String> own = new ArrayList<>();
        for (String name : names) {
            if (user.contains(name)) {
                own.add(name);
            } else if (isDark.test(name)) {
                dark.add(name);
            } else {
                light.add(name);
            }
        }
        List<String> out = new ArrayList<>();
        addGroup(out, LIGHT, light);
        addGroup(out, DARK, dark);
        addGroup(out, USER, own);
        return out;
    }

    private static void addGroup(List<String> out, String heading, List<String> themes) {
        if (!themes.isEmpty()) {
            out.add(heading);
            out.addAll(themes);
        }
    }

    /** A heading's title. */
    static String headingText(String heading) {
        return switch (heading) {
            case LIGHT -> tr("settings.theme.group.light");
            case DARK -> tr("settings.theme.group.dark");
            default -> tr("settings.theme.group.user");
        };
    }

    /**
     * Shows {@code combo}'s headings as group titles that cannot be chosen, and every other item as
     * {@code label} says.
     */
    static void install(ComboBox<String> combo, Function<String, String> label) {
        combo.setCellFactory(v -> cell(label));
        SingleSelectionModel<String> selection = combo.getSelectionModel();
        selection.selectedIndexProperty().addListener((obs, was, now) -> {
            int at = now.intValue();
            if (at < 0
                    || at >= combo.getItems().size()
                    || !isHeading(combo.getItems().get(at))) {
                return;
            }
            int step = was == null || was.intValue() <= at ? 1 : -1;
            int target = nextTheme(combo.getItems(), at, step);
            if (target < 0) {
                target = nextTheme(combo.getItems(), at, -step);
            }
            if (target >= 0) {
                selection.select(target);
            } else {
                selection.clearSelection();
            }
        });
    }

    /** The first non-heading index from {@code at} in {@code step}'s direction, or -1. */
    private static int nextTheme(List<String> items, int at, int step) {
        for (int i = at + step; i >= 0 && i < items.size(); i += step) {
            if (!isHeading(items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static ListCell<String> cell(Function<String, String> label) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                boolean heading = !empty && isHeading(item);
                setText(empty || item == null ? null : heading ? headingText(item) : label.apply(item));
                setDisable(heading); // not clickable, not choosable
                getStyleClass().remove("settings-section");
                if (heading) {
                    getStyleClass().add("settings-section"); // the small bold group title used across Settings
                }
                // A disabled cell is drawn faded; a heading is a title, not a greyed-out choice.
                setStyle(heading ? "-fx-opacity: 1;" : null);
            }
        };
    }
}
