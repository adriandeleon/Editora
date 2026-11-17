package com.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.ConfigManager;
import com.editora.editor.UiZoom;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Snippets and Templates: the two pages that edit text inserted with placeholders — the Snippets list
 * ({@link SnippetsEditor}) and the Templates list-and-form with its author row — and the snippet body editor's
 * syntax colouring and Emacs-style keys they share.
 */
final class SettingsSnippetPages {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsSnippetPages(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        templateAuthorField = new TextField();
        templateAuthorField.setPromptText(System.getProperty("user.name", ""));
        w.commitOnEnterOrBlur(templateAuthorField, () -> config.getSettings().getAuthorNameRaw(), now -> {
            config.getSettings().setAuthorName(now);
            w.apply();
        });
    }

    /** Re-read the Snippets / Templates lists from disk, keeping the selected row (set when the page is built). */
    private @Nullable Runnable reloadSnippets;

    private @Nullable Runnable reloadTemplates;

    /** Shared snippet manager (injected after construction); backs the Snippets management page. */
    private com.editora.snippet.@Nullable SnippetManager snippetManager;
    /** The Snippets page's list-and-form editor (built with the page). */
    private @Nullable SnippetsEditor snippetsEditor;

    /** Shared template registry (injected after construction); backs the Templates management page. */
    private com.editora.template.@Nullable TemplateRegistry templateRegistry;
    /** Working copy of the templates (bundled + user) shown on the Templates page. */
    private final javafx.collections.ObservableList<com.editora.template.Template> templateItems =
            javafx.collections.FXCollections.observableArrayList();
    /** Ids of the shown templates that are user-owned (writable / removable); the rest are read-only bundled. */
    private final java.util.Set<String> templateUserIds = new java.util.HashSet<>();

    private boolean loadingTemplate = false;

    TextField templateAuthorField;

    /** Injects the shared {@link com.editora.snippet.SnippetManager} backing the Snippets management page. */
    void setSnippetManager(com.editora.snippet.SnippetManager snippetManager) {
        this.snippetManager = snippetManager;
    }

    /** Injects the shared {@link com.editora.template.TemplateRegistry} backing the Templates page. */
    void setTemplateRegistry(com.editora.template.TemplateRegistry registry) {
        this.templateRegistry = registry;
    }

    /** Asks before Templates → Remove deletes a user template file. Replaceable so a test can answer. */
    private java.util.function.Predicate<com.editora.template.Template> confirmTemplateRemoval =
            this::confirmTemplateRemovalDialog;

    private boolean confirmTemplateRemovalDialog(com.editora.template.Template t) {
        return w.askBeforeLoss(removeTemplateQuestion(t.name(), t.id()));
    }

    /**
     * The words of the question before the user template {@code id} is removed: its name (its id when it has
     * none) and its file.
     */
    static Confirm.Question removeTemplateQuestion(@Nullable String name, String id) {
        return new Confirm.Question(
                tr("settings.template.remove"),
                null,
                tr("settings.template.removeConfirm", name == null || name.isBlank() ? id : name, id + ".json"),
                tr("confirm.action.remove"));
    }

    VBox snippetsPage() {
        VBox p = w.page(tr("settings.cat.snippets"), tr("settings.snippets.subtitle"));
        Card expansion = w.card(p, null);
        w.checkRow(
                expansion,
                Category.SNIPPETS,
                w.snippetTabExpansionCheck,
                tr("settings.snippet.tabExpansion.desc"),
                "snippets tab expansion expand trigger prefix key");
        Card mainCard = w.card(p, null);
        snippetsEditor = new SnippetsEditor(
                snippetManager,
                new SnippetsEditor.Hooks(
                        w.keymapsPage::macroWarn,
                        this::confirmSnippetSave,
                        SettingsSnippetPages::highlightSnippetBodySoon,
                        SettingsSnippetPages::installEmacsKeys,
                        name -> w.askBeforeLoss(SettingsWindow.deleteEntryQuestion(name))));
        reloadSnippets = snippetsEditor::reload;
        w.cardRow(
                mainCard,
                Category.SNIPPETS,
                snippetsEditor.node(),
                "snippets user prefix trigger expansion tab stops body disable bundled filter");
        Label help = SettingsWindow.note(tr("settings.snippet.help"));
        help.setWrapText(true);
        UiZoom.maxWidth(help, 460);
        w.cardRow(mainCard, Category.SNIPPETS, help, "snippets help tab stop placeholder variable");
        return p;
    }

    /**
     * The words of the question before a snippet is saved although {@code message} says what the save loses. Its
     * answer: Save (0). It has no title of its own: the dialog keeps the one JavaFX gives a confirmation.
     */
    static Confirm.Choice snippetSaveQuestion(String message) {
        return Confirm.choice(null, null, message, tr("confirm.action.save"));
    }

    private boolean confirmSnippetSave(String message) {
        Confirm.Choice words = snippetSaveQuestion(message);
        ButtonType save = Dialogs.action(words.answer(0));
        Alert a = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, words.message(), save, Dialogs.cancel()));
        a.initOwner(w.stage);
        a.setHeaderText(words.header());
        Dialogs.destructive(a, save);
        return Dialogs.showAndWait(a).orElse(Dialogs.cancelOf(a)) == save;
    }

    /** Another window (or a save in the editor) changed a user snippet file: the open Snippets page re-reads it. */
    void reloadSnippetsPage() {
        if (reloadSnippets != null) {
            reloadSnippets.run();
        }
    }

    VBox templatesPage() {
        VBox p = w.page(tr("settings.cat.templates"), tr("settings.templates.subtitle"));
        Card author = w.card(p, tr("settings.section.templates"));
        w.controlRow(
                author,
                Category.TEMPLATES,
                tr("settings.authorName"),
                null,
                templateAuthorField,
                "author name file templates new from template variable");
        Card list = w.card(p, tr("settings.section.templatesList"));
        w.cardRow(list, Category.TEMPLATES, templatesEditor(), "templates file new from template scaffold bundled");
        Label help = SettingsWindow.note(tr("settings.template.help"));
        help.setWrapText(true);
        UiZoom.maxWidth(help, 460);
        w.cardRow(list, Category.TEMPLATES, help, "templates help variable cursor placeholder");
        return p;
    }

    /** Master-detail editor: the templates (bundled + user) on the left, a form for the selected one. */
    private javafx.scene.Node templatesEditor() {
        ListView<com.editora.template.Template> list = new ListView<>(templateItems);
        list.setAccessibleText(tr("settings.cat.templates"));
        UiZoom.prefSize(list, 250, 420); // "Java Compact Source" + the bundled tag
        VBox.setVgrow(list, Priority.ALWAYS); // grow the list to fill the page height
        list.setCellFactory(lv -> new ListCell<>() {
            {
                // A ListCell reports its graphic's intrinsic width as its preferred width, so a name
                // plus the "bundled" tag made the list demand more than its viewport and grow a
                // horizontal scrollbar — which then stole the height the last row needed, producing a
                // vertical one too. Asking for nothing lets the row fit the viewport and the name
                // ellipsize instead.
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(com.editora.template.Template t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label nm = new Label(t.name() == null || t.name().isBlank() ? t.id() : t.name());
                HBox.setHgrow(nm, Priority.ALWAYS);
                nm.setMaxWidth(Double.MAX_VALUE);
                HBox cell = UiZoom.spacing(new HBox(nm), 6);
                cell.setAlignment(Pos.CENTER_LEFT);
                if (!templateUserIds.contains(t.id())) {
                    // Not the user's own file: say whose it is (editing it saves a user copy that overrides it).
                    Label tag = new Label(tr(
                            t.origin() == com.editora.template.Template.Origin.PLUGIN
                                    ? "settings.template.pluginTag"
                                    : "settings.template.bundledTag"));
                    tag.getStyleClass().add("snippet-bundled-tag");
                    tag.setMinWidth(Region.USE_PREF_SIZE); // the name gives way, not the tag ("bund…")
                    cell.getChildren().add(tag);
                }
                setText(null);
                setGraphic(CellNames.read(cell));
            }
        });

        TextField id = new TextField();
        TextField name = new TextField();
        TextField description = new TextField();
        TextField language = new TextField();
        language.setPromptText(tr("settings.template.languagePrompt"));
        TextField fileName = new TextField();
        fileName.setPromptText(tr("settings.template.fileNamePrompt"));
        CodeArea body = AreaUndo.bounded(new CodeArea());
        body.getStyleClass().addAll("editor-area", "snippet-body");
        // A template body is a file: its lines are shown as they will be written (scrolling sideways), not
        // re-wrapped into something that no longer looks like the code it creates.
        body.setWrapText(false);
        UiZoom.prefWidth(body, 480);
        // Modest preferred height so the page fits the window; GridPane Vgrow lets it expand when there's room.
        UiZoom.prefHeight(body, 180);
        installEmacsKeys(body); // basic Emacs caret movement in the settings scene
        body.plainTextChanges().subscribe(c -> highlightSnippetBodySoon(body, language.getText()));
        // Re-highlight when the template's language changes (even without a body edit).
        language.textProperty().addListener((o, a, b) -> highlightSnippetBodySoon(body, language.getText()));

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        UiZoom.gaps(form, 8, 6);
        formRow(form, 0, tr("settings.template.id"), id);
        formRow(form, 1, tr("settings.template.name"), name);
        formRow(form, 2, tr("settings.template.description"), description);
        formRow(form, 3, tr("settings.template.language"), language);
        formRow(form, 4, tr("settings.template.fileName"), fileName);
        // The body gets the form's full width (its label on the line above) rather than the field column:
        // beside the list, that column is too narrow to read a line of code in.
        Label bodyLabel = new Label(tr("settings.template.body"));
        bodyLabel.setLabelFor(body);
        form.add(bodyLabel, 0, 5, 2, 1);
        form.add(body, 0, 6, 2, 1);
        UiZoom.minWidth(body, SettingRowPane.FIELD_MIN_WIDTH);
        javafx.scene.layout.GridPane.setHgrow(body, Priority.ALWAYS);
        javafx.scene.layout.GridPane.setVgrow(body, Priority.ALWAYS);
        form.setDisable(true);

        Label multiFileNote = SettingsWindow.note(tr("settings.template.multiFileNote"));
        multiFileNote.setWrapText(true);
        UiZoom.maxWidth(multiFileNote, 440);
        multiFileNote.setVisible(false);
        multiFileNote.setManaged(false);
        VBox right = UiZoom.spacing(new VBox(form, multiFileNote), 6);
        HBox.setHgrow(right, Priority.ALWAYS);

        // The form's texts now, and as they were when the selected row was loaded (or last committed).
        java.util.function.Supplier<java.util.List<String>> templateFormText = () -> java.util.Arrays.asList(
                id.getText(),
                name.getText(),
                description.getText(),
                language.getText(),
                fileName.getText(),
                body.getText());
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> templateFormLoaded =
                new java.util.concurrent.atomic.AtomicReference<>(templateFormText.get());
        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i < 0 || loadingTemplate) {
                return;
            }
            com.editora.template.Template cur = templateItems.get(i);
            if (cur.isMultiFile()) {
                return; // multi-file templates are read-only here
            }
            if (id.getText().trim().isEmpty()) {
                id.setText(cur.id()); // the id names the file: a blank one keeps the old, and the rest still saves
            }
            if (templateFormText.get().equals(templateFormLoaded.get())) {
                return; // a field merely lost focus — a bundled template must not become a user override for that
            }
            String newId = id.getText().trim();
            if (!newId.equals(cur.id())) {
                // The id is the file stem: one that is not a plain file name would write outside the
                // templates folder, and one another row uses would overwrite that template's file.
                String problem = null;
                if (com.editora.template.TemplateRegistry.isReservedId(newId)) {
                    problem = tr("settings.template.idReserved", newId); // index.json is the bundled list
                } else if (!com.editora.template.TemplateRegistry.isValidId(newId)) {
                    problem = tr("settings.template.idInvalid", newId);
                } else {
                    for (com.editora.template.Template other : templateItems) {
                        if (other != cur && newId.equals(other.id())) {
                            problem = tr("settings.template.idExists", newId);
                            break;
                        }
                    }
                }
                if (problem != null) {
                    id.setText(cur.id()); // put the field back
                    w.keymapsPage.macroWarn(problem);
                    return;
                }
            }
            // A field the user did not touch keeps the template's own value: the single-line description field
            // shows a multi-line bundled description flattened, and saving that back would flatten the file.
            java.util.List<String> loaded = templateFormLoaded.get();
            java.util.List<String> typed = templateFormText.get();
            java.util.function.BiFunction<Integer, String, String> value =
                    (k, original) -> typed.get(k).equals(loaded.get(k)) && original != null
                            ? original
                            : typed.get(k).trim();
            templateFormLoaded.set(typed);
            com.editora.template.Template updated = new com.editora.template.Template(
                    newId,
                    value.apply(1, cur.name()),
                    value.apply(2, cur.description()),
                    value.apply(3, cur.language()),
                    value.apply(4, cur.fileName()),
                    body.getText(),
                    null);
            if (updated.equals(new com.editora.template.Template(
                    cur.id(), cur.name(), cur.description(), cur.language(), cur.fileName(), cur.body(), null))) {
                return; // only whitespace the commit trims away: not an edit, so not a user override either
            }
            String oldId = cur.id();
            // Save under the new id first: a rename that deleted the old file and then failed to write the
            // new one lost the template. The wizard labels are not in this form, so they are carried over.
            com.editora.template.Template toSave = updated.withLabels(cur.labels());
            // The row shows the edit at once; the file is written on the template thread (PERF-01, Pauta 6.0.1).
            templateUserIds.add(newId); // editing a bundled or plugin template makes it a user override
            loadingTemplate = true;
            try {
                templateItems.set(i, toSave);
            } finally {
                loadingTemplate = false;
            }
            list.refresh();
            boolean renamedOverride = !oldId.equals(newId) && templateUserIds.contains(oldId);
            if (renamedOverride) {
                templateUserIds.remove(oldId);
            }
            saveTemplate(toSave, saved -> {
                if (!saved) {
                    id.setText(oldId);
                    if (renamedOverride) {
                        templateUserIds.add(oldId); // its old file is still there
                    }
                    return;
                }
                com.editora.template.TemplateRegistry registry = templateRegistry;
                if (renamedOverride && registry != null) {
                    // renamed: drop the old override file, best-effort (the template is safe under its new id)
                    registry.later(
                            r -> {
                                r.deleteUserTemplate(oldId);
                                return Boolean.TRUE;
                            },
                            (done, failure) -> {
                                if (failure == null) {
                                    w.markSyncDirty();
                                }
                            });
                }
            });
        };
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(id);
        wire.accept(name);
        wire.accept(description);
        wire.accept(language);
        wire.accept(fileName);
        body.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });
        PauseCommit.watch( // live, but never a half-typed id (it is the file name)
                () -> {
                    if (!id.isFocused()) {
                        commit.run();
                    }
                },
                name.textProperty(),
                description.textProperty(),
                language.textProperty(),
                fileName.textProperty(),
                body.textProperty());

        list.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> {
            int i = now == null ? -1 : now.intValue();
            com.editora.template.Template t = i >= 0 && i < templateItems.size() ? templateItems.get(i) : null;
            boolean multi = t != null && t.isMultiFile();
            loadingTemplate = true;
            try {
                form.setDisable(t == null || multi); // multi-file templates are display-only in this form
                id.setText(t == null ? "" : t.id());
                name.setText(t == null ? "" : t.name());
                description.setText(t == null ? "" : t.description());
                language.setText(t == null ? "" : t.language());
                fileName.setText(t == null || multi ? "" : t.fileName());
                body.replaceText(t == null || multi ? "" : t.body()); // CodeArea has no setText
                multiFileNote.setVisible(multi);
                multiFileNote.setManaged(multi);
                templateFormLoaded.set(templateFormText.get());
            } finally {
                loadingTemplate = false;
            }
        });

        // Read on the template thread (PERF-01, Pauta 6.0.1); the list fills when it lands, then {@code after} runs.
        java.util.function.Consumer<Runnable> loadTemplatesThen = after -> readTemplates(merged -> {
            loadingTemplate = true;
            try {
                templateItems.setAll(merged);
            } finally {
                loadingTemplate = false;
            }
            list.getSelectionModel().clearSelection();
            if (!templateItems.isEmpty()) {
                list.getSelectionModel().select(0);
            } else {
                form.setDisable(true);
            }
            after.run();
        });
        Runnable loadTemplates = () -> loadTemplatesThen.accept(() -> {});

        reloadTemplates = () -> { // another window's Settings may have added or removed a template
            com.editora.template.Template sel = list.getSelectionModel().getSelectedItem();
            loadTemplatesThen.accept(() -> {
                for (int k = 0; sel != null && k < templateItems.size(); k++) {
                    if (java.util.Objects.equals(sel.id(), templateItems.get(k).id())) {
                        list.getSelectionModel().select(k);
                        break;
                    }
                }
            });
        };

        Button add = FocusCommands.press(new Button(tr("settings.template.add")), "settings.addTemplate");
        add.setOnAction(e -> {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (com.editora.template.Template t : templateItems) {
                existing.add(t.id());
            }
            String base = "new-template";
            String nid = base;
            for (int n = 2; existing.contains(nid); n++) {
                nid = base + "-" + n;
            }
            com.editora.template.Template t = new com.editora.template.Template(
                    nid, tr("settings.template.newName"), "", "", "${name}.txt", "${cursor}", null);
            templateUserIds.add(nid);
            templateItems.add(t);
            saveTemplate(t, saved -> {});
            list.getSelectionModel().select(templateItems.size() - 1);
            id.requestFocus();
            id.selectAll();
        });
        Button remove = new Button(tr("settings.template.remove"));
        // Only user templates/overrides can be removed; a pristine bundled template can't be deleted.
        remove.disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> {
                            com.editora.template.Template t =
                                    list.getSelectionModel().getSelectedItem();
                            return t == null || !templateUserIds.contains(t.id());
                        },
                        list.getSelectionModel().selectedItemProperty()));
        ListRowCommands.because(remove, () -> {
            com.editora.template.Template t = list.getSelectionModel().getSelectedItem();
            return t != null && !templateUserIds.contains(t.id()) ? "row.unavailable.bundledTemplate" : null;
        });
        remove.setOnAction(e -> {
            com.editora.template.Template t = list.getSelectionModel().getSelectedItem();
            if (t == null || !templateUserIds.contains(t.id()) || templateRegistry == null) {
                return;
            }
            // The file is deleted outright, and a multi-file template cannot be rebuilt from this form.
            if (!confirmTemplateRemoval.test(t)) {
                return;
            }
            templateRegistry.later(
                    r -> {
                        r.deleteUserTemplate(t.id());
                        return Boolean.TRUE;
                    },
                    (done, ex) -> {
                        if (ex != null) {
                            Alert failed = Dialogs.styled(new Alert(
                                    Alert.AlertType.ERROR,
                                    tr("settings.template.saveFailed", ex.getMessage()),
                                    ButtonType.OK));
                            failed.initOwner(w.stage);
                            Dialogs.showAndWait(failed);
                            return;
                        }
                        w.markSyncDirty();
                        templateUserIds.remove(t.id());
                        templateItems.remove(t);
                        loadTemplates.run(); // re-derive: a removed override reverts to its bundled template
                    });
        });
        // Its right-click menu: Copy, and the Remove button's action (MENU-05).
        ListRowCommands.install(
                list,
                ListRowCommands.copyRow(
                        list,
                        () -> ListRowCommands.selectedText(
                                list, t -> t.name() == null || t.name().isBlank() ? t.id() : t.name())),
                new javafx.scene.control.SeparatorMenuItem(),
                ListRowCommands.danger(ListRowCommands.buttonRow(list, ListRowCommands.REMOVE, remove, null)));
        HBox buttons = UiZoom.spacing(new HBox(add, remove), 6);
        VBox left = UiZoom.spacing(new VBox(list, buttons), 6);
        SettingsWindow.keepWidth(add, remove, left);
        VBox.setVgrow(left, Priority.ALWAYS);

        VBox.setVgrow(form, Priority.ALWAYS);

        loadTemplates.run();
        HBox box = UiZoom.spacing(new HBox(left, right), 12);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /**
     * The templates shown, in the order they override one another: bundled (shipped) ones, then plugins'
     * (replacing a bundled one of the same id, else appended), then the user's own files, which win over
     * both. Rebuilds {@link #templateUserIds} (the ids that are user-owned: writable and removable); every
     * other row is tagged with where it comes from.
     */
    private void readTemplates(java.util.function.Consumer<java.util.List<com.editora.template.Template>> then) {
        com.editora.template.TemplateRegistry registry = templateRegistry;
        if (registry == null) {
            templateUserIds.clear();
            then.accept(java.util.List.of());
            return;
        }
        registry.later(
                r -> new TemplateSources(r.userTemplates(), r.bundledTemplates(), r.pluginTemplates()),
                (sources, failure) -> then.accept(mergedTemplates(
                        sources == null
                                ? new TemplateSources(java.util.List.of(), java.util.List.of(), java.util.List.of())
                                : sources)));
    }

    /** The three sources the Templates page shows, as read on the template thread. */
    private record TemplateSources(
            java.util.List<com.editora.template.Template> user,
            java.util.List<com.editora.template.Template> bundled,
            java.util.List<com.editora.template.Template> plugin) {}

    private java.util.List<com.editora.template.Template> mergedTemplates(TemplateSources sources) {
        templateUserIds.clear();
        java.util.LinkedHashMap<String, com.editora.template.Template> userById = new java.util.LinkedHashMap<>();
        for (com.editora.template.Template u : sources.user()) {
            userById.put(u.id(), u);
            templateUserIds.add(u.id());
        }
        java.util.LinkedHashMap<String, com.editora.template.Template> merged = new java.util.LinkedHashMap<>();
        for (com.editora.template.Template b : sources.bundled()) {
            merged.put(b.id(), b);
        }
        for (com.editora.template.Template plugin : sources.plugin()) {
            merged.put(plugin.id(), plugin);
        }
        merged.putAll(userById); // the user's own file always wins
        return new java.util.ArrayList<>(merged.values());
    }

    /**
     * Writes {@code t} to the user's templates folder on the template thread (PERF-01, Pauta 6.0.1), then tells
     * {@code then} whether it was written; says why when it was not.
     */
    private void saveTemplate(com.editora.template.Template t, java.util.function.Consumer<Boolean> then) {
        com.editora.template.TemplateRegistry registry = templateRegistry;
        if (registry == null) {
            then.accept(false);
            return;
        }
        registry.later(
                r -> {
                    r.saveUserTemplate(t);
                    return Boolean.TRUE;
                },
                (done, e) -> {
                    if (e != null) {
                        Alert failed = Dialogs.styled(new Alert(
                                Alert.AlertType.ERROR,
                                tr("settings.template.saveFailed", e.getMessage()),
                                ButtonType.OK));
                        failed.initOwner(w.stage);
                        Dialogs.showAndWait(failed);
                        then.accept(false);
                        return;
                    }
                    w.markSyncDirty();
                    then.accept(true);
                });
    }

    private void formRow(javafx.scene.layout.GridPane form, int rowIndex, String labelText, javafx.scene.Node field) {
        Label l = new Label(labelText);
        // A Label's computed min width is just an ellipsis, so a tight GridPane collapses it to "…".
        // Pin its min to its preferred (text) width so the field-row labels stay readable.
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setLabelFor(field);
        form.add(l, 0, rowIndex);
        form.add(field, 1, rowIndex);
        if (field instanceof javafx.scene.layout.Region r) {
            UiZoom.minWidth(r, SettingRowPane.FIELD_MIN_WIDTH);
        }
        // A tall multi-line body would otherwise centre its label; align it to the top of the field instead.
        if (field instanceof CodeArea) {
            javafx.scene.layout.GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        }
    }

    /** The place a snippet or template body is tokenized, heard off the FX thread ({@code BlockingSites}). */
    public static final String SNIPPET_HIGHLIGHT_SITE = "SettingsSnippetPages.highlight";

    /** How long typing in a body must pause before it is highlighted again. */
    static final int HIGHLIGHT_PAUSE_MS = 150;

    /** Where a body keeps its pending highlight: the typing pause and the latest ask. */
    private static final Object HIGHLIGHT_STATE = new Object();

    /** One body's highlighting: the pause typing waits for, and the generation of the latest ask. */
    private static final class BodyHighlight {
        final javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(HIGHLIGHT_PAUSE_MS));
        long generation;
    }

    private static BodyHighlight state(CodeArea area) {
        return (BodyHighlight) area.getProperties().computeIfAbsent(HIGHLIGHT_STATE, k -> new BodyHighlight());
    }

    /** As {@link #highlightSnippetBody}, once typing in the body pauses: for the body's change listeners. */
    static void highlightSnippetBodySoon(CodeArea area, String languageName) {
        BodyHighlight state = state(area);
        state.generation++; // an answer still under way is for text that is changing
        state.pause.setOnFinished(e -> highlightSnippetBody(area, languageName));
        state.pause.playFromStart();
    }

    /**
     * Re-highlights a snippet/template body {@link CodeArea} for {@code languageName} (plain for global/unknown).
     * The grammar is loaded and the text tokenized off the FX thread on a snapshot (PERF-01, Pauta 6.0.1); the styles
     * land when the body still holds that text and no later ask superseded this one.
     */
    static void highlightSnippetBody(CodeArea area, String languageName) {
        BodyHighlight state = state(area);
        state.pause.stop();
        long mine = ++state.generation;
        String text = area.getText();
        boolean styled = languageName != null && !languageName.isBlank() && !"global".equals(languageName);
        if (!styled || !GrammarRegistry.shared().hasGrammarForLanguage(languageName)) {
            if (!text.isEmpty()) {
                area.clearStyle(0, text.length());
            }
            return;
        }
        com.editora.perf.OffFx.then(
                SNIPPET_HIGHLIGHT_SITE,
                () -> {
                    IGrammar g = GrammarRegistry.shared().forLanguageName(languageName);
                    return g == null ? null : TextMateHighlighter.compute(text, g);
                },
                answer -> {
                    org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> spans = answer.value();
                    if (mine != state.generation || area.getLength() != text.length()) {
                        return; // a later ask (or an edit) supersedes it
                    }
                    if (spans == null) {
                        if (!text.isEmpty()) {
                            area.clearStyle(0, text.length());
                        }
                        return;
                    }
                    try {
                        area.setStyleSpans(0, spans);
                    } catch (RuntimeException ignored) {
                        // Tokenizer hiccup: keep the last styling rather than crash the editor.
                    }
                });
    }

    /** Installs basic Emacs caret movement on a settings-scene {@link CodeArea} (no global KeyDispatcher there).
     *  Uses absolute-offset {@code moveTo}/{@code deleteText} (robust across RichTextFX versions); each action is
     *  guarded so the key is always consumed (no fall-through to the default behaviour) even at a boundary. */
    static void installEmacsKeys(CodeArea area) {
        installFocusEscape(area);
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            com.editora.command.KeymapManager keymap = com.editora.command.TextInputKeymap.sharedKeymap();
            if (keymap != null && !com.editora.command.KeymapManager.DEFAULT.equals(keymap.activeName())) {
                return; // Emacs keys under the Emacs keymap only: under CUA, Ctrl+A selects all, Ctrl+D is not delete
            }
            boolean ctrl = e.isControlDown() && !e.isAltDown() && !e.isMetaDown() && !e.isShiftDown();
            boolean alt = e.isAltDown() && !e.isControlDown() && !e.isMetaDown() && !e.isShiftDown();
            if (!ctrl && !alt) {
                return;
            }
            int caret = area.getCaretPosition();
            int len = area.getLength();
            String text = area.getText();
            if (ctrl) {
                switch (e.getCode()) {
                    case A -> consume(e, () -> area.moveTo(area.getCurrentParagraph(), 0));
                    case E ->
                        consume(e, () -> {
                            int par = area.getCurrentParagraph();
                            area.moveTo(par, area.getParagraphLength(par));
                        });
                    case F ->
                        consume(e, () -> {
                            if (caret < len) {
                                area.moveTo(caret + 1);
                            }
                        });
                    case B ->
                        consume(e, () -> {
                            if (caret > 0) {
                                area.moveTo(caret - 1);
                            }
                        });
                    case N -> consume(e, () -> emacsMoveLine(area, 1));
                    case P -> consume(e, () -> emacsMoveLine(area, -1));
                    case D ->
                        consume(e, () -> {
                            if (caret < len) {
                                area.deleteText(caret, caret + 1);
                            }
                        });
                    case K -> consume(e, () -> emacsKillLine(area));
                    default -> {}
                }
            } else {
                switch (e.getCode()) {
                    case F -> consume(e, () -> area.moveTo(nextWordBoundary(text, caret)));
                    case B -> consume(e, () -> area.moveTo(prevWordBoundary(text, caret)));
                    default -> {}
                }
            }
        });
    }

    /**
     * Lets the keyboard leave a body editor. Tab types a tab there (a snippet body needs it), which made
     * the editor a trap: nothing moved focus on, so the Save button after it was out of reach. As in a
     * JavaFX {@code TextArea}: Shift+Tab goes back, Ctrl+Tab goes on (Ctrl+Shift+Tab back).
     */
    private static void installFocusEscape(CodeArea area) {
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            javafx.scene.TraversalDirection direction =
                    focusEscape(e.getCode(), e.isShiftDown(), e.isControlDown(), e.isAltDown() || e.isMetaDown());
            if (direction != null) {
                e.consume();
                area.requestFocusTraversal(direction);
            }
        });
    }

    /** Where a key takes the focus out of a body editor, or {@code null} when the editor keeps the key. Pure. */
    static javafx.scene.@Nullable TraversalDirection focusEscape(
            javafx.scene.input.KeyCode code, boolean shift, boolean ctrl, boolean otherModifier) {
        if (code != javafx.scene.input.KeyCode.TAB || otherModifier || (!shift && !ctrl)) {
            return null;
        }
        return shift ? javafx.scene.TraversalDirection.PREVIOUS : javafx.scene.TraversalDirection.NEXT;
    }

    private static void consume(javafx.scene.input.KeyEvent e, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // A move/delete at a boundary must not crash key handling.
        }
        e.consume();
    }

    private static void emacsMoveLine(CodeArea area, int delta) {
        int target = area.getCurrentParagraph() + delta;
        if (target < 0 || target >= area.getParagraphs().size()) {
            return;
        }
        area.moveTo(target, Math.min(area.getCaretColumn(), area.getParagraphLength(target)));
    }

    private static void emacsKillLine(CodeArea area) {
        int caret = area.getCaretPosition();
        int rest = area.getParagraphLength(area.getCurrentParagraph()) - area.getCaretColumn();
        if (rest > 0) {
            area.deleteText(caret, caret + rest); // kill to end of line
        } else if (caret < area.getLength()) {
            area.deleteText(caret, caret + 1); // at end-of-line: join the next line up
        }
    }

    /** Offset of the end of the next word at/after {@code i} (Emacs M-f). Pure. */
    private static int nextWordBoundary(String s, int i) {
        int n = s.length();
        while (i < n && !Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        while (i < n && Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Offset of the start of the previous word at/before {@code i} (Emacs M-b). Pure. */
    private static int prevWordBoundary(String s, int i) {
        while (i > 0 && !Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** The Snippets and Templates pages read files; refreshed when Settings is opened, not on every load. */
    void reload() {
        if (reloadSnippets != null) {
            reloadSnippets.run();
        }
        if (reloadTemplates != null) {
            reloadTemplates.run();
        }
    }
}
