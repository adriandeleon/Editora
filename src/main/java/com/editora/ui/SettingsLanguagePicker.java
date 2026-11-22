package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The "Language" picker of a Settings page with one card (or a few) per language — Language Servers (27 servers)
 * and Debugging (9 adapters) grew into walls of cards. Each language's cards go into a {@link #holder} of their own
 * and only the picked language's holder shows; the page's shared cards sit above it (Language Servers) or below it
 * (Debugging, whose shared cards are long).
 *
 * <p>A search ignores the pick: while one runs, every language's holder with a hit shows (the picker's own card has
 * no rows, so the search hides it), and when the search is cleared the pick moves to the first language it found if
 * the picked one had no hit — where the user just looked.
 */
final class SettingsLanguagePicker {

    private final ComboBox<String> combo = new ComboBox<>();
    private final Map<String, VBox> holders = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private boolean searching;
    /** The first language whose holder showed a hit in the last search, else null. */
    private @Nullable String firstHit;

    /** Adds the picker's card to {@code page}; the holders follow it, in the order they are made. */
    SettingsLanguagePicker(SettingsWindow w, VBox page, String description) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(@Nullable String id) {
                return id == null ? "" : names.getOrDefault(id, id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        combo.setVisibleRowCount(16);
        combo.valueProperty().addListener((o, was, now) -> showPicked());
        String title = tr("settings.languagePicker");
        combo.setAccessibleText(title);
        SettingsWindow.Card card = w.card(page, null);
        // Straight into the card's body, not a search row: a search hides the card (no row of it matches) and
        // showing everything again brings it back — the pick means nothing while a search lists every language.
        card.body().getChildren().add(SettingsWindow.settingRow(title, description, combo));
    }

    /** A new holder for language {@code id}, shown as {@code name}, added to {@code page}: its cards go in it. */
    VBox holder(VBox page, String id, String name) {
        VBox holder = UiZoom.spacing(new VBox(), 10); // the page's own spacing between cards
        holders.put(id, holder);
        names.put(id, name);
        combo.getItems().add(id);
        page.getChildren().add(holder);
        if (combo.getValue() == null) {
            combo.setValue(id);
        }
        showPicked();
        return holder;
    }

    /** Picks language {@code id} (a link from another page to one language's card); unknown ids are ignored. */
    void show(String id) {
        if (holders.containsKey(id)) {
            combo.setValue(id);
        }
    }

    /** The picked language's id. */
    @Nullable
    String picked() {
        return combo.getValue();
    }

    ComboBox<String> combo() {
        return combo;
    }

    /**
     * Follows the window's search, called after it has shown and hidden the rows: {@code active} while a query or the
     * Modified filter is on.
     */
    void searched(boolean active) {
        if (active) {
            searching = true;
            firstHit = null;
            for (Map.Entry<String, VBox> e : holders.entrySet()) {
                boolean hit = e.getValue().getChildren().stream().anyMatch(Node::isManaged);
                SettingsSearch.setShown(e.getValue(), hit); // an empty holder would still add the page's spacing
                if (hit && firstHit == null) {
                    firstHit = e.getKey();
                }
            }
            return;
        }
        boolean wasSearching = searching;
        searching = false;
        String picked = combo.getValue();
        if (wasSearching && firstHit != null && picked != null && !firstHit.equals(picked)) {
            VBox mine = holders.get(picked);
            if (mine != null && !mine.isManaged()) { // the picked language had no hit: go where the search went
                combo.setValue(firstHit); // shows it
                return;
            }
        }
        showPicked();
    }

    private void showPicked() {
        if (searching) {
            return;
        }
        String picked = combo.getValue();
        holders.forEach((id, holder) -> SettingsSearch.setShown(holder, id.equals(picked)));
    }
}
