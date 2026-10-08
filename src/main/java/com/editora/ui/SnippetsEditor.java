package com.editora.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.snippet.SnippetManager;
import com.editora.snippet.SnippetManager.Entry;
import com.editora.snippet.SnippetManager.Problem;
import com.editora.snippet.SnippetManager.Source;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The master-detail editor of Settings → Snippets: a language picker, a filter and the language's snippets
 * on the left (bundled ones, the user's edits of them, and the user's own), a form on the right.
 *
 * <p>The list works on {@link SnippetManager.Entry} — a snippet as its file holds it, with <em>every</em>
 * trigger — and each action writes one entry ({@link SnippetManager#saveUserEntry} and friends) rather than
 * the whole file. That is what keeps the page and the editor in agreement: an edited bundled snippet is the
 * same snippet under the same name, all its triggers included, and a rename keeps the entry's place, its
 * {@code scope} and the comments around it.
 */
final class SnippetsEditor {

    /** What the editor borrows from the Settings window it sits in. */
    record Hooks(
            Consumer<String> warn,
            Predicate<String> confirm,
            BiConsumer<CodeArea, String> highlight,
            Consumer<CodeArea> editorKeys) {}

    private final SnippetManager manager;
    private final Hooks hooks;
    private final ObservableList<Entry> items = FXCollections.observableArrayList();
    private final FilteredList<Entry> shown = new FilteredList<>(items);
    private String language = "global";
    /** Why the language's user file cannot be used at all, or null. While set, nothing here writes it. */
    private Problem fileProblem;

    private boolean loading;
    private Runnable reload = () -> {};

    SnippetsEditor(SnippetManager manager, Hooks hooks) {
        this.manager = manager;
        this.hooks = hooks;
    }

    /** Re-reads the current language from disk, keeping the selected row (another window may have saved). */
    void reload() {
        reload.run();
    }

    /** The triggers typed into the field: comma-separated, blanks dropped, duplicates once. Pure. */
    static List<String> parseTriggers(String text) {
        List<String> out = new ArrayList<>();
        for (String part : (text == null ? "" : text).split(",")) {
            String t = part.strip();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The other snippet of {@code all} that shares a trigger with {@code entry}, as {@code {trigger, name}},
     * or null. Disabled snippets expand nothing and so clash with nothing. Pure.
     */
    static String[] sharedTrigger(Entry entry, List<Entry> all) {
        if (entry.disabled()) {
            return null;
        }
        for (Entry other : all) {
            if (other.disabled() || Objects.equals(other.name(), entry.name())) {
                continue;
            }
            for (String t : entry.prefixes()) {
                if (other.prefixes().contains(t)) {
                    return new String[] {t, other.name()};
                }
            }
        }
        return null;
    }

    /** Whether {@code entry} matches the list filter (name, any trigger or description, ignoring case). Pure. */
    static boolean matches(Entry entry, String filter) {
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            return true;
        }
        return contains(entry.name(), f) || contains(entry.prefixText(), f) || contains(entry.description(), f);
    }

    private static boolean contains(String text, String lowerNeedle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    Node node() {
        ComboBox<String> languageBox = new ComboBox<>(FXCollections.observableArrayList(
                manager == null ? List.of("global") : manager.languagesWithSnippets()));
        languageBox.setValue(language);

        TextField filter = new TextField();
        filter.setPromptText(tr("settings.snippet.filterPrompt"));
        filter.setAccessibleText(tr("settings.snippet.filterPrompt"));
        filter.textProperty().addListener((o, was, now) -> shown.setPredicate(e -> matches(e, now)));

        ListView<Entry> list = new ListView<>(shown);
        list.setPrefSize(240, 400);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new EntryCell());

        TextField name = new TextField();
        TextField prefix = new TextField();
        prefix.setPromptText(tr("settings.snippet.prefixPrompt"));
        TextField description = new TextField();
        CodeArea body = AreaUndo.bounded(new CodeArea());
        body.getStyleClass().addAll("editor-area", "snippet-body");
        body.setWrapText(false); // a body is code: a wrapped line reads as two
        body.setPrefHeight(180);
        hooks.editorKeys().accept(body);
        body.plainTextChanges().subscribe(c -> hooks.highlight().accept(body, language));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.snippet.name"), name);
        formRow(form, 1, tr("settings.snippet.prefix"), prefix);
        formRow(form, 2, tr("settings.snippet.description"), description);
        formRow(form, 3, tr("settings.snippet.body"), body);
        GridPane.setHgrow(body, Priority.ALWAYS);
        GridPane.setVgrow(body, Priority.ALWAYS);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);
        Label problem = wrapped("settings-hint");
        Label clash = wrapped("settings-hint");

        // The form's texts now, and as they were when the selected row was loaded (or last committed).
        java.util.function.Supplier<List<String>> formText =
                () -> List.of(name.getText(), prefix.getText(), description.getText(), body.getText());
        List<List<String>> loaded = new ArrayList<>(List.of(formText.get()));

        Runnable showClash = () -> {
            Entry cur = list.getSelectionModel().getSelectedItem();
            String[] shared = cur == null ? null : sharedTrigger(cur, items);
            clash.setText(shared == null ? "" : tr("settings.snippet.triggerShared", shared[0], shared[1]));
            clash.setVisible(shared != null);
            clash.setManaged(shared != null);
        };

        Consumer<String> load = keep -> {
            fileProblem = manager == null ? null : manager.wholeFileProblem(language);
            List<Problem> entryProblems = new ArrayList<>();
            if (manager != null && fileProblem == null) {
                for (Problem p : manager.checkUserFiles()) {
                    if (p.file().equals(manager.userFile(language))) {
                        entryProblems.add(p);
                    }
                }
            }
            Problem say = fileProblem != null ? fileProblem : entryProblems.isEmpty() ? null : entryProblems.get(0);
            problem.setText(
                    say == null
                            ? ""
                            : SnippetCoordinator.describe(say)
                                    + (fileProblem == null ? "" : " " + tr("settings.snippet.unreadableHint")));
            problem.setVisible(say != null);
            problem.setManaged(say != null);
            loading = true;
            try {
                items.setAll(manager == null ? List.of() : manager.entries(language));
            } finally {
                loading = false;
            }
            list.getSelectionModel().clearSelection();
            int select = shown.isEmpty() ? -1 : 0;
            for (int k = 0; keep != null && k < shown.size(); k++) {
                if (keep.equals(shown.get(k).name())) {
                    select = k;
                    break;
                }
            }
            if (select >= 0) {
                list.getSelectionModel().select(select);
            } else {
                form.setDisable(true);
            }
        };

        Runnable commit = () -> {
            Entry cur = list.getSelectionModel().getSelectedItem();
            if (cur == null || loading || fileProblem != null) {
                return;
            }
            // "Nothing was edited" is judged against what the form was loaded with, not against the model:
            // a single-line field drops the line breaks of a bundled multi-line description, so a rebuilt
            // snippet never equalled the original and a mere focus loss wrote a user override.
            if (formText.get().equals(loaded.get(0))) {
                return; // a field merely lost focus — never rewrite the file for that
            }
            String newName = name.getText().trim();
            if (newName.isEmpty()) {
                // The file is keyed by name: committing a blank one would delete the snippet. Keep the name
                // it has; the other fields still save.
                newName = cur.name();
                name.setText(newName);
                if (newName == null || newName.isBlank()) {
                    return;
                }
            }
            if (!newName.equals(cur.name())) {
                for (Entry other : items) {
                    if (other != cur && newName.equals(other.name())) {
                        // Two rows with one name collapse to a single entry on disk — refuse, as the
                        // External Tools page does for a colliding command id.
                        name.setText(cur.name());
                        hooks.warn().accept(tr("settings.snippet.nameExists", newName));
                        return;
                    }
                }
            }
            boolean descriptionEdited =
                    !description.getText().equals(loaded.get(0).get(2));
            Entry updated = cur.withFields(
                    newName,
                    parseTriggers(prefix.getText()),
                    body.getText(),
                    descriptionEdited ? description.getText().trim() : cur.description());
            loaded.set(0, formText.get());
            if (updated.equals(cur)) {
                return; // only whitespace the commit trims away
            }
            if (cur.source() == Source.USER
                    && manager.userEntryHasComments(language, cur.name())
                    && !hooks.confirm().test(tr("settings.snippet.commentsLost", cur.name()))) {
                return; // comments inside this one entry cannot be kept; the user chose to keep them
            }
            String keep = newName;
            if (write(() -> manager.saveUserEntry(language, cur.name(), updated))) {
                reloadKeepingForm(list, keep, filter);
                showClash.run();
            }
        };
        // Single-line fields commit on Enter / focus-loss; the body commits on focus-loss (Enter = newline).
        for (TextField tf : List.of(name, prefix, description)) {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        }
        body.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });

        // Load the form when a different row is selected.
        list.getSelectionModel().selectedItemProperty().addListener((o, was, s) -> {
            if (loading) {
                return;
            }
            loading = true;
            try {
                form.setDisable(s == null || fileProblem != null);
                name.setText(s == null ? "" : s.name());
                prefix.setText(s == null ? "" : s.prefixText());
                description.setText(s == null ? "" : s.description());
                body.replaceText(s == null ? "" : s.body()); // CodeArea has no setText
                loaded.set(0, formText.get());
            } finally {
                loading = false;
            }
            showClash.run();
        });

        languageBox.valueProperty().addListener((o, a, v) -> {
            language = v == null || v.isBlank() ? "global" : v.trim();
            filter.clear();
            load.accept(null);
        });
        reload = () -> {
            Entry sel = list.getSelectionModel().getSelectedItem();
            load.accept(sel == null ? null : sel.name());
        };

        Button add = new Button(tr("settings.snippet.add"));
        add.setOnAction(e -> {
            // The file is keyed by name: a second "New Snippet" would replace the first on disk.
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (Entry other : items) {
                taken.add(other.name());
            }
            String newName = tr("settings.snippet.newName");
            for (int n = 2; taken.contains(newName); n++) {
                newName = tr("settings.snippet.newName") + " " + n;
            }
            String created = newName;
            if (write(() -> manager.saveUserEntry(language, null, Entry.user(created, List.of(), "", "")))) {
                filter.clear();
                load.accept(created);
                name.requestFocus();
                name.selectAll();
            }
        });
        Button remove = new Button(tr("settings.snippet.remove"));
        // Remove only affects the user's file: a pristine bundled row is switched off instead (it is shipped).
        remove.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> {
                            Entry s = list.getSelectionModel().getSelectedItem();
                            return s == null || s.source() != Source.USER || fileProblem != null;
                        },
                        list.getSelectionModel().selectedItemProperty(),
                        problem.visibleProperty()));
        remove.setOnAction(e -> {
            Entry s = list.getSelectionModel().getSelectedItem();
            if (s != null && s.source() == Source.USER && write(() -> manager.removeUserEntry(language, s.name()))) {
                load.accept(s.name()); // a removed edit reverts to its bundled snippet
            }
        });
        Button toggle = new Button(tr("settings.snippet.disable"));
        toggle.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> {
                            Entry s = list.getSelectionModel().getSelectedItem();
                            return tr(
                                    s != null && s.disabled() ? "settings.snippet.enable" : "settings.snippet.disable");
                        },
                        list.getSelectionModel().selectedItemProperty()));
        toggle.setOnAction(e -> {
            Entry s = list.getSelectionModel().getSelectedItem();
            if (s != null && write(() -> manager.setDisabled(language, s.name(), !s.disabled()))) {
                load.accept(s.name());
            }
        });
        for (Button b : List.of(add, toggle)) { // a file that cannot be parsed is never written: say so by state
            if (b == add) {
                b.disableProperty().bind(fileBroken(problem));
            } else {
                b.disableProperty()
                        .bind(fileBroken(problem)
                                .or(list.getSelectionModel()
                                        .selectedItemProperty()
                                        .isNull()));
            }
        }
        HBox buttons = new HBox(6, add, remove, toggle);
        // The picker shares the list's width: boxed with a 130px label column it was squeezed to an arrow.
        Label languageLabel = new Label(tr("settings.snippet.language"));
        languageLabel.setLabelFor(languageBox);
        languageBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(languageBox, Priority.ALWAYS);
        HBox languageRow = new HBox(10, languageLabel, languageBox);
        languageRow.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(6, languageRow, filter, list, buttons);
        for (Region r : List.of(languageLabel, add, remove, toggle, left)) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        VBox.setVgrow(left, Priority.ALWAYS);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, problem, form, clash, saveRow);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        load.accept(null);
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** After a save: the list is re-read (tags, order, the renamed row) without reloading the form under the user. */
    private void reloadKeepingForm(ListView<Entry> list, String keep, TextField filter) {
        loading = true;
        try {
            items.setAll(manager.entries(language));
            if (shown.stream().noneMatch(e -> keep.equals(e.name()))) {
                filter.clear(); // the edit took the row out of the filter: show it rather than lose it
            }
            for (int k = 0; k < shown.size(); k++) {
                if (keep.equals(shown.get(k).name())) {
                    list.getSelectionModel().select(k);
                    break;
                }
            }
        } finally {
            loading = false;
        }
    }

    private javafx.beans.binding.BooleanBinding fileBroken(Label problem) {
        return Bindings.createBooleanBinding(() -> fileProblem != null, problem.textProperty());
    }

    private interface Write {
        void run() throws IOException;
    }

    /** Runs one write of the user file; a failure is shown and leaves the page as it was. */
    private boolean write(Write action) {
        if (manager == null || fileProblem != null) {
            return false; // never write back over a file that could not be parsed
        }
        try {
            action.run();
            return true;
        } catch (IOException e) {
            hooks.warn().accept(tr("settings.snippet.saveFailed", e.getMessage()));
            return false;
        }
    }

    private static Label wrapped(String styleClass) {
        Label l = new Label("");
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        l.setVisible(false);
        l.setManaged(false);
        return l;
    }

    private static void formRow(GridPane form, int rowIndex, String labelText, Node field) {
        Label l = new Label(labelText);
        // A Label's computed min width is just an ellipsis, so a tight GridPane collapses it to "…".
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setLabelFor(field);
        form.add(l, 0, rowIndex);
        form.add(field, 1, rowIndex);
        if (field instanceof Region r) {
            r.setMinWidth(180);
        }
        if (field instanceof CodeArea) { // a tall body would otherwise centre its label
            GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        }
    }

    /** A row: the name, its triggers beside it, and what kind of row it is. */
    private static final class EntryCell extends ListCell<Entry> {
        EntryCell() {
            // A ListCell reports its graphic's intrinsic width as its preferred width, so a long row made
            // the list demand more than its viewport and grow a horizontal scrollbar. Asking for nothing
            // lets the row fit the viewport and the name ellipsize instead.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Entry s, boolean empty) {
            super.updateItem(s, empty);
            setText(null);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            Label nm = new Label(s.name() == null || s.name().isBlank() ? tr("settings.snippet.unnamed") : s.name());
            HBox.setHgrow(nm, Priority.ALWAYS);
            nm.setMaxWidth(Double.MAX_VALUE);
            HBox cell = new HBox(6, nm);
            cell.setAlignment(Pos.CENTER_LEFT);
            if (!s.prefixes().isEmpty() && !s.prefixText().equalsIgnoreCase(s.name())) {
                Label trigger = new Label(s.prefixText());
                trigger.getStyleClass().add("snippet-trigger");
                trigger.setMaxWidth(110); // the name gives way first, then the trigger ellipsizes
                cell.getChildren().add(trigger);
            }
            String tag = s.disabled()
                    ? tr("settings.snippet.disabledTag")
                    : s.source() == Source.BUNDLED ? tr("settings.snippet.bundledTag") : null;
            if (tag != null) {
                Label t = new Label(tag);
                t.getStyleClass().add("snippet-bundled-tag");
                t.setMinWidth(Region.USE_PREF_SIZE); // the name gives way, not the tag ("bund…")
                cell.getChildren().add(t);
            }
            setGraphic(cell);
        }
    }
}
