package com.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.spell.SpellDictionaries;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Spell Check: the switch, the language, the per-file-type ticks, the technical and personal dictionaries
 * and the personal dictionary's word list. The page's controls, its load and its re-syncs live here.
 */
final class SettingsSpellPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsSpellPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        spellCheckBox = new CheckBox(tr("settings.enableSpell"));
        spellCheckBox.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setSpellCheck(now);
            w.apply();
        });
        dictEnableCheck = new CheckBox(tr("settings.dict.enable"));
        dictEnableCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPersonalDictionary(now);
            w.apply();
        });
        techDictEnableCheck = new CheckBox(tr("settings.dict.technical"));
        techDictEnableCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setTechnicalDictionary(now);
            w.apply();
        });
        spellLanguageCombo = new ComboBox<>();
        spellLanguageCombo.getItems().setAll(SpellDictionaries.available());
        spellLanguageCombo.setPrefWidth(220);
        spellLanguageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : spellLanguageName(id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        spellLanguageCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null || w.scopeUi.write("editor.spellLanguage", now)) {
                return;
            }
            config.getSettings().setSpellLanguage(now);
            w.apply();
        });
    }

    /** Spell Check page: open the bundled technical dictionary (read-only) / the personal dictionary file. */
    private @Nullable Runnable onOpenTechnicalDictionary;

    private @Nullable Runnable onOpenPersonalDictionary;

    CheckBox spellCheckBox;
    ComboBox<String> spellLanguageCombo;
    /** The Personal Dictionary list on the Spell Check page; refreshed from {@code dictionary.txt} on show. */
    private @Nullable ListView<String> dictionaryList;
    /** The add field of the Personal Dictionary editor; what is typed in it also narrows the list. */
    private @Nullable TextField dictionaryInput;
    /** The Spell Check page's per-file-type tick list ({@code Settings.spellDisabledLanguages}). */
    private @Nullable SpellFileTypesEditor spellFileTypes;
    /** "Enable personal dictionary" checkbox (Settings.personalDictionary). */
    private CheckBox dictEnableCheck;
    /** "Enable technical dictionary" checkbox (Settings.technicalDictionary). */
    private CheckBox techDictEnableCheck;

    /** Injects the Spell Check page's "open dictionary file" actions (bundled technical / personal). */
    void setDictionaryActions(Runnable openTechnical, Runnable openPersonal) {
        this.onOpenTechnicalDictionary = openTechnical;
        this.onOpenPersonalDictionary = openPersonal;
    }

    VBox build() {
        VBox p = w.page(tr("settings.cat.spellCheck"), tr("settings.spellCheck.subtitle"));
        Card main = w.card(p, null);
        w.checkRow(main, Category.SPELL_CHECK, spellCheckBox, null, "spell check spelling enable");
        main.body()
                .getChildren()
                .add(SettingsDependents.note(spellCheckBox.selectedProperty(), tr("settings.enableSpell")));
        int firstSpellRow = w.rows.size();
        w.controlRow(
                main,
                Category.SPELL_CHECK,
                tr("settings.language"),
                tr("settings.spell.languageNote"),
                spellLanguageCombo,
                "spell language dictionary english spanish french default per file override");
        Card types = w.card(p, tr("settings.spell.fileTypes.title"));
        spellFileTypes = new SpellFileTypesEditor(config::getSettings, w::apply);
        Label typesNote = new Label(tr("settings.spell.fileTypes.note"));
        typesNote.getStyleClass().add("settings-hint");
        typesNote.setWrapText(true);
        w.cardRow(
                types,
                Category.SPELL_CHECK,
                new VBox(6, typesNote, spellFileTypes),
                "spell file types languages per language json yaml toml xml csv markdown html typst code comments");
        // The dictionaries get a card of their own: both switches, both file links, then the word list.
        Card dict = w.card(p, tr("settings.dict.title"));
        Hyperlink techLink = new Hyperlink(tr("settings.dict.openTechnical"));
        techLink.setTooltip(new Tooltip(tr("settings.dict.openTechnicalTip")));
        techLink.setOnAction(e -> {
            if (onOpenTechnicalDictionary != null) {
                onOpenTechnicalDictionary.run();
            }
        });
        Hyperlink personalLink = new Hyperlink(tr("settings.dict.openPersonal"));
        personalLink.setTooltip(new Tooltip(tr("settings.dict.openPersonalTip")));
        personalLink.setOnAction(e -> {
            if (onOpenPersonalDictionary != null) {
                onOpenPersonalDictionary.run();
            }
        });
        HBox dictLinks = new HBox(16, techLink, personalLink);
        dictLinks.setAlignment(Pos.CENTER_LEFT);
        w.checkRow(
                dict,
                Category.SPELL_CHECK,
                techDictEnableCheck,
                tr("settings.dict.technicalNote"),
                "technical dictionary terms programming code config async kubernetes enable on off");
        w.checkRow(
                dict,
                Category.SPELL_CHECK,
                dictEnableCheck,
                tr("settings.dict.note"),
                "personal dictionary enable on off honor words dictionary.txt file location global");
        w.cardRow(
                dict,
                Category.SPELL_CHECK,
                dictLinks,
                "dictionary open technical personal file dictionary.txt bundled terms");
        w.cardRow(dict, Category.SPELL_CHECK, dictionaryEditor(), "personal dictionary words add remove custom ignore");
        SettingsDependents.bind(spellCheckBox.selectedProperty(), w.rowsSince(firstSpellRow));
        return p;
    }

    /** A simple editor for the global personal dictionary ({@code dictionary.txt}): list + add/remove. */
    private javafx.scene.Node dictionaryEditor() {
        ListView<String> words = new ListView<>();
        dictionaryList = words;
        dictionaryList.setPrefSize(300, 380);
        dictionaryList.setPlaceholder(new Label(tr("settings.dict.empty")));
        refreshDictionaryList();

        TextField input = new TextField();
        dictionaryInput = input;
        input.setPromptText(tr("settings.dict.prompt"));
        input.setAccessibleText(tr("settings.dict.prompt"));
        HBox.setHgrow(input, Priority.ALWAYS);
        // The list has no search of its own: what is being typed narrows it, which also shows at once
        // whether the word is already there. Filtering only — nothing is stored until Add.
        input.textProperty().addListener((o, was, now) -> refreshDictionaryList());
        Button add = new Button(tr("settings.dict.add"));
        Runnable doAdd = () -> {
            // One entry per word, in the form the checker looks words up by. A phrase used to be stored
            // whole ("foo bar"), which no single word can ever match.
            String last = null;
            for (String part : input.getText().strip().split("\\s+")) {
                String w = com.editora.editor.spell.SpellChecker.canonical(part);
                if (!w.isEmpty()) {
                    config.addUserWord(w);
                    last = w;
                }
            }
            if (last != null) {
                w.markSyncDirty();
                input.clear();
                refreshDictionaryList();
                words.getSelectionModel().select(last);
                w.apply(); // live-apply like every other control here: re-runs the spell overlays' caches
            }
            input.requestFocus();
        };
        add.setOnAction(e -> doAdd.run());
        input.setOnAction(e -> doAdd.run());

        Button remove = new Button(tr("settings.dict.remove"));
        remove.disableProperty()
                .bind(dictionaryList.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            String sel = words.getSelectionModel().getSelectedItem();
            if (sel != null) {
                config.removeUserWord(sel);
                w.markSyncDirty();
                refreshDictionaryList();
                w.apply(); // live-apply: the removed word must start being flagged again right away
            }
        });

        HBox addRow = new HBox(6, input, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, dictionaryList, addRow, new HBox(6, remove));
        box.setMaxWidth(440);
        return box;
    }

    /** Reloads the Personal Dictionary list from the shared user-word set (sorted), preserving selection. */
    void refreshDictionaryList() {
        if (dictionaryList == null) {
            return;
        }
        java.util.List<String> words = new java.util.ArrayList<>(config.getUserDictionary());
        String typed = dictionaryInput == null || dictionaryInput.getText() == null
                ? ""
                : dictionaryInput.getText().strip().toLowerCase(java.util.Locale.ROOT);
        if (!typed.isEmpty() && typed.indexOf(' ') < 0) {
            words.removeIf(w -> !w.contains(typed));
        }
        java.util.Collections.sort(words);
        String sel = dictionaryList.getSelectionModel().getSelectedItem();
        dictionaryList.getItems().setAll(words);
        if (sel != null && words.contains(sel)) {
            dictionaryList.getSelectionModel().select(sel);
        }
    }

    /** Re-reads the Personal Dictionary list: a word was added, ignored or reloaded while the page is open. */
    void syncDictionaryList() {
        refreshDictionaryList();
    }

    /** Re-syncs the file-type ticks after {@code spell.toggleForLanguage} (or another window) changed them. */
    void syncSpellFileTypes() {
        if (spellFileTypes != null) {
            spellFileTypes.sync();
        }
    }

    /** Re-syncs the "Enable personal dictionary" checkbox after a palette toggle. */
    void syncPersonalDictionaryCheck() {
        if (dictEnableCheck != null) {
            w.quietly(() -> dictEnableCheck.setSelected(config.getSettings().isPersonalDictionary()));
        }
    }

    /** Re-syncs the "Enable technical dictionary" checkbox after a palette toggle. */
    void syncTechnicalDictionaryCheck() {
        if (techDictEnableCheck != null) {
            w.quietly(() -> techDictEnableCheck.setSelected(config.getSettings().isTechnicalDictionary()));
        }
    }

    private static String spellLanguageName(String id) {
        return SpellCoordinator.languageName(id);
    }

    void load(Settings settings) {
        spellCheckBox.setSelected(settings.isSpellCheck());
        dictEnableCheck.setSelected(settings.isPersonalDictionary());
        techDictEnableCheck.setSelected(settings.isTechnicalDictionary());
        spellLanguageCombo.setValue(settings.getSpellLanguage());
        syncSpellFileTypes();
    }
}
