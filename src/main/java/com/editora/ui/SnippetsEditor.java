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

import com.editora.editor.UiZoom;
import com.editora.snippet.SnippetManager;
import com.editora.snippet.SnippetManager.Entry;
import com.editora.snippet.SnippetManager.Problem;
import com.editora.snippet.SnippetManager.Source;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The master-detail editor of Settings → Snippets: a language picker, a filter and the language's snippets
 * on the left (bundled ones, the user's edits of them, and the user's own), a form on the right.
 *
 * <p>The list works on {@link SnippetManager.Entry} — a snippet as its file holds it, with <em>every</em>
 * trigger — and each action writes one entry ({@link SnippetManager#saveUserEntry} and friends) rather than
 * the whole file. That is what keeps the page and the editor in agreement: an edited bundled snippet is the
 * same snippet under the same name, all its triggers included, and a rename keeps the entry's place and its
 * {@code scope}. The file is written whole, so a write asks first when the file has comments (they are dropped).
 */
final class SnippetsEditor {

    /** What the editor borrows from the Settings window it sits in. */
    record Hooks(
            Consumer<String> warn,
            Predicate<String> confirm,
            BiConsumer<CodeArea, String> highlight,
            Consumer<CodeArea> editorKeys,
            Predicate<String> confirmDelete) {}

    private final @Nullable SnippetManager manager;
    private final Hooks hooks;
    private final ObservableList<Entry> items = FXCollections.observableArrayList();
    private final FilteredList<Entry> shown = new FilteredList<>(items);
    private String language = "global";
    /** Why the language's user file cannot be used at all, or null. While set, nothing here writes it. */
    private @Nullable Problem fileProblem;

    private boolean loading;
    private Runnable reload = () -> {};

    SnippetsEditor(@Nullable SnippetManager manager, Hooks hooks) {
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
    static String @Nullable [] sharedTrigger(Entry entry, List<Entry> all) {
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

    /** The first trigger of {@code entry} with whitespace in it — one Tab can never expand — or null. Pure. */
    static @Nullable String spacedTrigger(Entry entry) {
        return spacedTrigger(entry.prefixes());
    }

    /** The first of {@code triggers} with whitespace in it, or null. Pure. */
    static @Nullable String spacedTrigger(List<String> triggers) {
        for (String t : triggers) {
            if (t.chars().anyMatch(Character::isWhitespace)) {
                return t;
            }
        }
        return null;
    }

    /**
     * The languages the picker offers: {@code global}, then every language that has snippets or that Editora
     * knows — a language with no snippets yet used to be unreachable from here. Pure.
     */
    static List<String> languages(List<String> withSnippets, java.util.Collection<String> known) {
        java.util.TreeSet<String> rest = new java.util.TreeSet<>(known);
        rest.addAll(withSnippets);
        rest.remove("global");
        List<String> out = new ArrayList<>();
        out.add("global");
        out.addAll(rest);
        return out;
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
                manager == null
                        ? List.of("global")
                        : languages(manager.languagesWithSnippets(), com.editora.text.LanguageRegistry.names())));
        languageBox.setValue(language);

        TextField filter = new TextField();
        filter.setPromptText(tr("settings.snippet.filterPrompt"));
        filter.setAccessibleText(tr("settings.snippet.filterPrompt"));
        filter.textProperty().addListener((o, was, now) -> shown.setPredicate(e -> matches(e, now)));

        ListView<Entry> list = new ListView<>(shown);
        list.setAccessibleText(tr("settings.cat.snippets"));
        UiZoom.prefSize(list, 240, 400);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(lv -> new EntryCell());

        TextField name = new TextField();
        TextField prefix = new TextField();
        prefix.setPromptText(tr("settings.snippet.prefixPrompt"));
        TextField description = new TextField();
        CodeArea body = AreaUndo.bounded(new CodeArea());
        body.getStyleClass().addAll("editor-area", "snippet-body");
        body.setWrapText(false); // a body is code: a wrapped line reads as two
        UiZoom.prefHeight(body, 180);
        hooks.editorKeys().accept(body);
        body.plainTextChanges().subscribe(c -> hooks.highlight().accept(body, language));

        GridPane form = new GridPane();
        UiZoom.gaps(form, 8, 6);
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
            String spaced = cur == null ? null : spacedTrigger(cur);
            String text = (spaced == null ? "" : tr("settings.snippet.triggerSpace", spaced))
                    + (spaced != null && shared != null ? "\n" : "")
                    + (shared == null ? "" : tr("settings.snippet.triggerShared", shared[0], shared[1]));
            clash.setText(text);
            clash.setVisible(!text.isEmpty());
            clash.setManaged(!text.isEmpty());
        };

        Consumer<String> fill = keep -> {
            fileProblem = manager == null ? null : manager.wholeFileProblem(language);
            List<Problem> entryProblems = new ArrayList<>();
            if (manager != null && fileProblem == null) {
                entryProblems.addAll(manager.problemsOf(language));
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

        // The language's files are read on the snippet thread (PERF-01, Pauta 6.0.1); the page fills when they land.
        Consumer<String> load = keep -> {
            if (manager == null) {
                fill.accept(keep);
            } else {
                String asked = language;
                manager.whenLoaded(asked, () -> {
                    if (asked.equals(language)) { // not another language picked meanwhile
                        fill.accept(keep);
                    }
                });
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
            String spaced = spacedTrigger(parseTriggers(prefix.getText()));
            flag(prefix, spaced == null ? null : tr("settings.snippet.triggerSpace", spaced));
            if (spaced != null) {
                // Refused, not saved: Tab could never expand it. The typed text stays for the user to fix, and
                // the form stays "edited", so the next commit tries again.
                clash.setText(tr("settings.snippet.triggerSpace", spaced));
                clash.setVisible(true);
                clash.setManaged(true);
                return;
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
            String keep = newName;
            write(m -> m.saveUserEntry(language, cur.name(), updated), () -> {
                reloadKeepingForm(list, keep, filter);
                showClash.run();
            });
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
        // Live: the other fields apply once typing pauses. A name is applied on Enter or leaving it, never
        // half typed — each pause would rename the snippet on disk.
        Runnable typed = PauseCommit.watch(
                () -> {
                    if (!name.isFocused()) {
                        commit.run();
                    }
                },
                prefix.textProperty(),
                description.textProperty());
        body.plainTextChanges().subscribe(c -> typed.run()); // not textProperty(): it builds the body per key

        // Load the form when a different row is selected.
        list.getSelectionModel().selectedItemProperty().addListener((o, was, s) -> {
            if (loading) {
                return;
            }
            loading = true;
            try {
                flag(prefix, null); // a refused trigger belongs to the row being left
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

        Button add = FocusCommands.press(new Button(tr("settings.snippet.add")), "settings.addSnippet");
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
            write(m -> m.saveUserEntry(language, null, Entry.user(created, List.of(), "", "")), () -> {
                filter.clear();
                fill.accept(created); // the write left the language loaded
                name.requestFocus();
                name.selectAll();
            });
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
        ListRowCommands.because(remove, () -> {
            Entry s = list.getSelectionModel().getSelectedItem();
            return fileProblem != null
                    ? "row.unavailable.snippetsFileBroken"
                    : s != null && s.source() != Source.USER ? "row.unavailable.bundledSnippet" : null;
        });
        remove.setOnAction(e -> {
            Entry s = list.getSelectionModel().getSelectedItem();
            if (s != null && s.source() == Source.USER && hooks.confirmDelete().test(s.name())) {
                // a removed edit reverts to its bundled snippet
                write(m -> m.removeUserEntry(language, s.name()), () -> fill.accept(s.name()));
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
            if (s != null) {
                write(m -> m.setDisabled(language, s.name(), !s.disabled()), () -> fill.accept(s.name()));
            }
        });
        ListRowCommands.because(toggle, () -> fileProblem != null ? "row.unavailable.snippetsFileBroken" : null);
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
        // The buttons wrap onto a second line rather than hold the list column wider than a narrow window
        // allows (German at the 900 px minimum scrolled the whole page sideways).
        // Its right-click menu: Copy, and the Disable / Enable and Remove buttons' actions (MENU-05).
        ListRowCommands.install(
                list,
                ListRowCommands.copyRow(list, () -> ListRowCommands.selectedText(list, Entry::name)),
                ListRowCommands.buttonRow(list, ListRowCommands.TOGGLE, toggle, null),
                new javafx.scene.control.SeparatorMenuItem(),
                ListRowCommands.danger(ListRowCommands.buttonRow(list, ListRowCommands.REMOVE, remove, null)));
        WrapRow buttons = new WrapRow(6, 6, add, remove, toggle);
        // The picker shares the list's width: boxed with a 130px label column it was squeezed to an arrow.
        Label languageLabel = new Label(tr("settings.snippet.language"));
        languageLabel.setLabelFor(languageBox);
        languageBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(languageBox, Priority.ALWAYS);
        HBox languageRow = UiZoom.spacing(new HBox(languageLabel, languageBox), 10);
        languageRow.setAlignment(Pos.CENTER_LEFT);
        VBox left = UiZoom.spacing(new VBox(languageRow, filter, list, buttons), 6);
        for (Region r : List.of(languageLabel, add, remove, toggle)) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        VBox.setVgrow(left, Priority.ALWAYS);

        VBox right = UiZoom.spacing(new VBox(problem, form, clash), 8);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        load.accept(null);
        HBox box = UiZoom.spacing(new HBox(left, right), 12);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** After a save: the list is re-read (tags, order, the renamed row) without reloading the form under the user. */
    private void reloadKeepingForm(ListView<Entry> list, String keep, TextField filter) {
        loading = true;
        try {
            items.setAll(manager == null ? List.of() : manager.entries(language)); // set: a write just succeeded
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
        void run(SnippetManager manager) throws IOException;
    }

    /**
     * Runs one write of the user file on the snippet thread (PERF-01, Pauta 6.0.1); once it is written and the
     * language read again (also there), runs {@code written} on the FX thread. A failure is shown and leaves the
     * page as it was.
     *
     * <p>The file is written whole, through Jackson (PLAT-14), so comments in it are dropped. When the file has any,
     * the user is asked first, once: a file saved from here has none left to ask about. A "no" writes nothing.
     */
    private void write(Write action, Runnable written) {
        SnippetManager m = manager;
        if (m == null || fileProblem != null) {
            return; // never write back over a file that could not be parsed
        }
        String lang = language;
        m.userFileHasCommentsLater(lang, has -> {
            if (has && !hooks.confirm().test(commentsLostMessage(m.userFile(lang)))) {
                m.whenLoaded(lang, written); // kept as it was: the page shows the file again
                return;
            }
            m.later(action::run, failure -> {
                if (failure != null) {
                    hooks.warn().accept(tr("settings.snippet.saveFailed", failure.getMessage()));
                } else {
                    m.whenLoaded(lang, written);
                }
            });
        });
    }

    /** What saving from here loses in {@code file}: its comments. */
    static String commentsLostMessage(java.nio.file.Path file) {
        return tr("settings.snippet.commentsLost", String.valueOf(file.getFileName()));
    }

    /** Marks {@code field} red with {@code why} as its tooltip and accessible help, or clears that when null. */
    private static void flag(TextField field, @Nullable String why) {
        field.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, why != null);
        field.setTooltip(why == null ? null : com.editora.editor.ControlNames.tooltip(why));
        field.setAccessibleHelp(why);
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
            UiZoom.minWidth(r, SettingRowPane.FIELD_MIN_WIDTH);
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
            HBox cell = UiZoom.spacing(new HBox(nm), 6);
            cell.setAlignment(Pos.CENTER_LEFT);
            if (!s.prefixes().isEmpty() && !s.prefixText().equalsIgnoreCase(s.name())) {
                Label trigger = new Label(s.prefixText());
                trigger.getStyleClass().add("snippet-trigger");
                UiZoom.maxWidth(trigger, 110); // the name gives way first, then the trigger ellipsizes
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
            setGraphic(CellNames.read(cell));
        }
    }
}
