package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

import com.editora.config.Settings;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings → Spell Check list of file types: one tick box per language, ticked when spell check runs
 * for it. Unticking writes the language to {@code Settings.spellDisabledLanguages}; the master switch above
 * it still turns everything off at once.
 */
final class SpellFileTypesEditor extends VBox {

    private final Supplier<Settings> settings;
    private final Runnable apply;
    private final List<String> languages;
    private final Map<String, BooleanProperty> ticked = new HashMap<>();
    private final ListView<String> list = new ListView<>();
    private final TextField filter = new TextField();
    /** True while {@link #sync} moves the ticks to the stored values, so that is not taken for an edit. */
    private boolean syncing;

    SpellFileTypesEditor(Supplier<Settings> settings, Runnable apply) {
        super(6);
        this.settings = settings;
        this.apply = apply;
        this.languages = languageNames(settings.get());
        for (String language : languages) {
            BooleanProperty on = new SimpleBooleanProperty();
            on.addListener((o, was, now) -> store(language, now));
            ticked.put(language, on);
        }
        filter.setPromptText(tr("settings.spell.fileTypes.filter"));
        filter.setAccessibleText(tr("settings.spell.fileTypes.filter"));
        filter.textProperty().addListener((o, was, now) -> showMatching(now)); // narrows the list; stores nothing
        list.setCellFactory(CheckBoxListCell.forListView(ticked::get));
        list.setPrefSize(300, 220);
        list.setAccessibleText(tr("settings.spell.fileTypes.title"));
        // A tick box inside a list cell answers the mouse only; Space on the selected row does the same.
        list.setOnKeyPressed(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.SPACE && selected != null) {
                BooleanProperty on = ticked.get(selected);
                on.set(!on.get());
                e.consume();
            }
        });
        getChildren().addAll(filter, list);
        setMaxWidth(440);
        sync();
        showMatching("");
    }

    /** Every language a buffer can have: the grammars, the file-name rules, and whatever is already listed. */
    static List<String> languageNames(Settings s) {
        TreeSet<String> names = new TreeSet<>();
        names.add(LanguageRegistry.plaintext());
        names.addAll(GrammarRegistry.shared().availableLanguageNames());
        names.addAll(LanguageRegistry.names());
        names.addAll(Settings.DEFAULT_SPELL_DISABLED_LANGUAGES);
        names.addAll(s.getSpellDisabledLanguages());
        return new ArrayList<>(names);
    }

    private void showMatching(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();
        for (String language : languages) {
            if (q.isEmpty() || language.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(language);
            }
        }
        list.getItems().setAll(shown);
    }

    private void store(String language, boolean on) {
        if (syncing) {
            return;
        }
        List<String> disabled = new ArrayList<>(settings.get().getSpellDisabledLanguages());
        disabled.remove(language);
        if (!on) {
            disabled.add(language);
        }
        settings.get().setSpellDisabledLanguages(disabled);
        apply.run();
    }

    /** Moves the ticks to the stored list (a command or another window changed it). */
    void sync() {
        syncing = true;
        try {
            List<String> disabled = settings.get().getSpellDisabledLanguages();
            ticked.forEach((language, on) -> on.set(!disabled.contains(language)));
        } finally {
            syncing = false;
        }
    }

    /** Whether {@code language} is ticked — for tests. */
    boolean isTicked(String language) {
        BooleanProperty on = ticked.get(language);
        return on != null && on.get();
    }

    /** Ticks or unticks {@code language} as a click would — for tests. */
    void setTicked(String language, boolean on) {
        ticked.get(language).set(on);
    }
}
