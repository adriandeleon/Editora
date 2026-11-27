package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Emacs {@code find-file}-style keyboard file opener: a path field with a live, prefix-autocompleted
 * listing of the current directory. Type to filter, {@code Tab} to complete the common prefix, Enter
 * to descend into a folder or open a file (in a folder picker: choose the folder typed, or the one listed) (a non-existent path opens a new buffer to be written on
 * save), ↑/↓ (or the keymap's line-up/down chords) to move, {@code Esc} (or the keymap's cancel) to cancel.
 * When {@link #setOpenAsProject} wires it, {@code C-d} opens the highlighted folder (or the folder being listed)
 * as a project. Keyboard-only; see {@link PickerKeys}.
 *
 * <p>{@link #forSave} makes the same card the keyboard Save As: it starts from the file's current path, lists
 * files and folders, completes with {@code Tab}, and Enter saves under the typed path — a folder is gone into,
 * keeping the file's name, so the next Enter saves there.
 */
public class FileFinder {

    private static final String SEP = File.separator;

    /** Height of the listing; the buffer switcher ({@code C-x b}) uses it too so the two cards match. */
    static final double LIST_HEIGHT = 280;

    private final Supplier<Path> startDir;
    private final Consumer<Path> onChoose;
    /** When true, only directories are listed and Enter chooses a folder (instead of opening a file). */
    private final boolean pickDirectory;

    private final String title;

    /** The chord that opens a folder as a project, when the keymap lets it reach the card. */
    static final String PROJECT_CHORD = "C-d";

    /** Opens a folder as a project ({@link #PROJECT_CHORD}); null when this finder offers no such key. */
    private @Nullable Consumer<Path> onOpenProject;

    /** Whether opening a project is possible right now (project support may be switched off). */
    private BooleanSupplier openProjectAvailable = () -> true;

    /** The raw chord token that opens a folder as a project in this showing, or null when there is none. */
    private @Nullable String projectChord;

    /** Save mode ({@link #forSave}): takes the typed path instead of anything being opened; null otherwise. */
    private @Nullable Consumer<String> onSave;

    /** Save mode: the field's first text — the file's current path, or a folder and a suggested name. */
    private Supplier<String> saveInitial = () -> "";

    /** Save mode: the file name kept when Enter goes into a folder, so the next Enter saves there. */
    private String saveName = "";

    /** Save mode: the field still holds the text it was shown with, so placing the caret selects the name. */
    private boolean selectNamePending;

    /** True once the user moved the highlight in the current listing (it no longer just sits on row one). */
    private boolean highlightMoved;

    // The card's nodes are made by build(), on the first show(): a finder nobody has opened holds none.
    private TextField input;
    private ListView<Path> list;
    /** The key legend under the list; rebuilt from the live keymap each time the finder is shown. */
    private Label hint;

    private boolean built;

    private final ObservableList<Path> items = FXCollections.observableArrayList();

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private @Nullable OverlayHost overlayHost;

    private VBox content;
    private boolean showing;

    /** Cached listing of {@link #currentDir}; re-read only when the directory part of the path changes. */
    private @Nullable Path currentDir;

    /** The listing of {@link #currentDir}, once its read has landed; knows which entries are folders. */
    private DirectoryListing listing = DirectoryListing.UNREADABLE;

    private long listGeneration;

    /** Reads a directory off the FX thread; replaceable so a test can hold or count the read. */
    volatile java.util.function.BiFunction<Path, Boolean, DirectoryListing> directoryReader = DirectoryListing::read;

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose) {
        this(startDir, onChoose, false, tr("filefinder.title"));
    }

    public FileFinder(Supplier<Path> startDir, Consumer<Path> onChoose, boolean pickDirectory, String title) {
        this.startDir = startDir;
        this.onChoose = onChoose;
        this.pickDirectory = pickDirectory;
        this.title = title;
    }

    /**
     * The keyboard Save As: the field starts as {@code initial} (asked at each show) with the file's name selected,
     * Tab completes folder and file names, and Enter hands the typed text to {@code onSave} — unresolved, so the
     * caller resolves a relative name or {@code ~} its own way — or goes into the folder it names, keeping the
     * name. A row the user moved onto is used as it is: a folder is gone into, a file is the target.
     */
    static FileFinder forSave(Supplier<String> initial, Consumer<String> onSave, String title) {
        FileFinder finder = new FileFinder(
                () -> {
                    Path parent = parentOf(initial.get());
                    return parent == null ? Path.of(System.getProperty("user.home", ".")) : parent;
                },
                p -> {},
                false,
                title);
        finder.onSave = onSave;
        finder.saveInitial = initial;
        return finder;
    }

    private static @Nullable Path parentOf(String text) {
        try {
            return Path.of(text).getParent();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Builds the card now if nothing has yet: the first show does, and a test that reads the card before one. */
    void ensureBuilt() {
        if (!built) {
            build();
            built = true;
        }
    }

    @Initializer
    private void build() {
        input = new TextField();
        list = new ListView<>();
        hint = new Label();
        input.setPromptText(tr("filefinder.prompt"));
        input.setAccessibleText(tr("filefinder.prompt"));
        list.setItems(items);
        list.setAccessibleText(tr("filefinder.prompt"));
        UiZoom.prefHeight(list, LIST_HEIGHT);
        list.setCellFactory(v -> new EntryCell());

        input.textProperty().addListener((obs, old, now) -> {
            selectNamePending = false; // typed: the selection is the user's now
            whenFiltered = null; // typed on after Enter or Tab: the key was for the text before
            refresh(now);
        });
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // A field that already has focus when its skin is made selects all of its text, and the skin is made on the
        // first pulse that styles the card, which can come after the caret was placed: place it again then, or the
        // first key typed replaces the whole pre-filled path.
        input.skinProperty().addListener((obs, was, now) -> {
            if (now != null && showing) {
                placeCaret();
            }
        });
        // Emacs caret movement + basic editing in the path field. Registered after onKey so the finder's own
        // list navigation (C-n/C-p/C-g) consumes those chords first and the keymap yields to it (isConsumed).
        TextInputKeymap.installShared(input);
        Label header = new Label(title);
        header.getStyleClass().add("palette-title");
        hint.getStyleClass().add("palette-hint");
        content = UiZoom.spacing(new VBox(header, input, list, hint), 6);
        content.getStyleClass().add("command-palette");
        UiZoom.prefWidth(content, 620);
        UiZoom.maxSize(content, 620, Region.USE_PREF_SIZE);
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
    }

    /**
     * Offers {@link #PROJECT_CHORD} in this finder: it hands the highlighted folder — or, when the field names
     * the listed folder itself, that folder — to {@code action}, while {@code available} says so (it is asked
     * each time the finder is shown, so the key and its legend follow a settings change). Not for a folder
     * picker, whose Enter already chooses a folder.
     */
    public void setOpenAsProject(Consumer<Path> action, BooleanSupplier available) {
        this.onOpenProject = action;
        this.openProjectAvailable = available;
    }

    /** Injects the shared overlay host used to show the picker card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /** Shows the finder as a centered in-scene overlay. {@code owner} is unused (kept for call-site
     *  compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner) {
        if (overlayHost == null) {
            return;
        }
        ensureBuilt();
        boolean saving = onSave != null;
        String start = saving ? saveInitial.get() : startText(startDir.get());
        saveName = saving ? prefixPart(start) : "";
        input.setAccessibleText(saving ? tr("dialog.saveAs.prompt") : tr("filefinder.prompt"));
        currentDir = null;
        listing = DirectoryListing.UNREADABLE;
        listGeneration++; // a read still in flight belongs to the previous showing
        KeymapManager keymap = TextInputKeymap.sharedKeymap();
        projectChord = onOpenProject != null && !pickDirectory && openProjectAvailable.getAsBoolean()
                ? PickerKeys.reachingChord(keymap, PROJECT_CHORD)
                : null;
        hint.setText(PickerKeys.legend(
                keymap,
                PickerKeys.hint("complete", "tab"),
                PickerKeys.hint(saving ? "save" : "open", "↵"),
                PickerKeys.hint(
                        "openProject",
                        projectChord == null || keymap == null ? projectChord : keymap.display(projectChord))));
        // Pre-fill with the start directory + separator so the user types a name straight away (Save As: the path).
        listNow = true;
        try {
            input.setText(start);
            input.positionCaret(input.getText().length());
            refresh(input.getText());
        } finally {
            listNow = false;
        }
        selectNamePending = saving;
        showing = true;
        overlayHost.show(
                content,
                () -> {
                    input.requestFocus();
                    // A TextField selects all its text on focus-gain (asynchronously), which would highlight the
                    // pre-filled path; clear the selection after focus settles and leave the caret at the end.
                    Platform.runLater(this::placeCaret);
                },
                () -> {
                    showing = false;
                    selectNamePending = false;
                    filtering.cancel(); // a filter landing after the card closed shows nothing and acts on nothing
                    whenFiltered = null;
                });
    }

    public void hide() {
        listPause.stop();
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public boolean isShown() {
        return showing;
    }

    /** The field's first text in a finder: the start directory and a separator, ready for a name. */
    private static String startText(Path dir) {
        return dir.toString().endsWith(SEP) ? dir.toString() : dir + SEP;
    }

    /** Caret at the end with nothing selected; in Save As, until something is typed, the file name selected. */
    private void placeCaret() {
        input.deselect();
        input.positionCaret(input.getText().length());
        if (selectNamePending) {
            selectName();
        }
    }

    /**
     * Selects the file name in the field, without its extension, so typing a new name replaces it (as a save
     * dialog does); the folder part and the extension stay.
     */
    private void selectName() {
        String text = input.getText();
        int start = dirPart(text).length();
        int dot = text.lastIndexOf('.');
        int end = dot > start ? dot : text.length();
        input.selectRange(start, end);
    }

    /**
     * {@code text} with a leading {@code ~} (alone, or followed by a separator) replaced by the home folder, so
     * the folder it names can be listed. Pure apart from reading {@code user.home}.
     */
    static String expandHome(String text) {
        if (text.equals("~") || text.startsWith("~" + SEP) || text.startsWith("~/")) {
            return System.getProperty("user.home", "~") + text.substring(1);
        }
        return text;
    }

    /** The directory part of {@code text} (up to and including the last separator). */
    private static String dirPart(String text) {
        int slash = text.lastIndexOf(SEP);
        return slash < 0 ? "" : text.substring(0, slash + 1);
    }

    /** The name part of {@code text} (after the last separator) — what we autocomplete on. */
    private static String prefixPart(String text) {
        int slash = text.lastIndexOf(SEP);
        return slash < 0 ? text : text.substring(slash + 1);
    }

    /**
     * Filters the listed entries by the typed prefix, and — when the directory part of the path changed —
     * asks for that directory's listing. The listing is a {@code readdir} plus a {@code stat} per entry, so
     * it is read on a worker: typing a path into a large or slow (network, FUSE) folder used to freeze the
     * window at the keystroke that completed the folder's name. The list is empty until the read lands.
     */
    private void refresh(String text) {
        Path dir = dirPart(text).isEmpty() ? currentDir : Path.of(expandHome(dirPart(text)));
        if (dir != null && !dir.equals(currentDir)) {
            currentDir = dir;
            listing = DirectoryListing.UNREADABLE;
            long generation = ++listGeneration;
            if (listNow) {
                listPause.stop();
                list(dir, generation);
            } else {
                // Typed: the read waits for typing to pause, so a path typed through several folders reads the
                // one it ends in and not each one it passed.
                listPause.setOnFinished(e -> list(dir, generation));
                listPause.playFromStart();
            }
        }
        applyFilter(text);
    }

    /** How long typing must pause before a directory the path now names is read. */
    static final javafx.util.Duration LIST_PAUSE = javafx.util.Duration.millis(100);

    private final javafx.animation.PauseTransition listPause = new javafx.animation.PauseTransition(LIST_PAUSE);

    /** True while the finder opens: its start directory is read at once, with no typing to wait for. */
    private boolean listNow;

    /** Reads {@code dir} on a worker and shows it, unless the path field names another directory by then. */
    private void list(Path dir, long generation) {
        java.util.function.BiFunction<Path, Boolean, DirectoryListing> reader = directoryReader;
        Thread.ofVirtual().name("file-finder-list").start(() -> {
            DirectoryListing read = reader.apply(dir, pickDirectory);
            Platform.runLater(() -> {
                if (generation != listGeneration) {
                    return; // the path field names another directory by now
                }
                listing = read;
                applyFilter(input.getText());
            });
        });
    }

    /** Where the typed prefix is matched against the folder's entries: a picker thread, never the FX thread. */
    static final String FILTER_SITE = "FileFinder.filter";

    /** The filter in flight: a newer keystroke supersedes it, and only the latest answer is shown. */
    private final LatestOnly filtering = PickerSearch.latest();

    /** What a key pressed while the rows were still coming does once they land: nothing, Enter or Tab. */
    private @Nullable Runnable whenFiltered;

    /** The longest common prefix of the rows shown, worked out with them (Tab extends the field to it). */
    private String rowsPrefix = "";

    /** The entries of a folder that match a typed prefix, and their longest common prefix. */
    private record Filtered(List<Path> matches, String prefix) {}

    /**
     * Shows the entries whose name starts with what is typed after the last separator. A folder can hold tens of
     * thousands of entries, so they are matched off the FX thread (PERF-01, Pauta 6.2.0) on the listing as read; the
     * rows shown stay until the latest answer lands, and Enter or Tab pressed meanwhile waits for it.
     */
    private void applyFilter(String text) {
        String prefix = prefixPart(text);
        boolean wantHidden = prefix.startsWith(".");
        String q = prefix.toLowerCase(Locale.ROOT);
        List<Path> entries = listing.entries();
        filtering.submit(
                () -> {
                    com.editora.perf.BlockingSites.enter(FILTER_SITE);
                    List<Path> matches = new ArrayList<>();
                    String lcp = null;
                    for (Path p : entries) {
                        String name = p.getFileName().toString();
                        if (!wantHidden && name.startsWith(".")) {
                            continue; // hide dotfiles unless the user is typing a leading dot
                        }
                        if (name.toLowerCase(Locale.ROOT).startsWith(q)) {
                            matches.add(p);
                            lcp = lcp == null ? name : commonPrefix(lcp, name);
                        }
                    }
                    return new Filtered(matches, lcp == null ? "" : lcp);
                },
                found -> {
                    items.setAll(found.matches());
                    rowsPrefix = found.prefix();
                    highlightMoved = false;
                    if (!items.isEmpty()) {
                        list.getSelectionModel().select(0);
                    }
                    Runnable then = whenFiltered;
                    whenFiltered = null;
                    if (then != null) {
                        then.run();
                    }
                });
    }

    /** Whether the rows shown are not yet those for the text typed (a test waits on it). */
    boolean filtering() {
        return filtering.pending();
    }

    /** Runs {@code key} now, or once the rows for what was typed have landed. */
    private void afterFilter(Runnable key) {
        if (filtering.pending()) {
            whenFiltered = key;
        } else {
            key.run();
        }
    }

    private void onKey(KeyEvent e) {
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> afterFilter(this::accept);
            default -> {
                if (e.getCode() == javafx.scene.input.KeyCode.TAB) {
                    afterFilter(this::autocomplete);
                } else if (projectChord != null && projectChord.equals(KeyDispatcher.chord(e))) {
                    afterFilter(this::openAsProject);
                } else if (PickerKeys.navigate(list, action)) {
                    highlightMoved = true;
                } else {
                    return;
                }
            }
        }
        e.consume();
    }

    /** Enter, or a click on a row: save under the typed path in save mode, else choose the selected entry. */
    private void accept() {
        if (onSave != null) {
            saveTyped();
        } else {
            chooseSelected();
        }
    }

    /**
     * Enter in save mode. A row the user moved onto is used as it is (a folder is gone into, a file is the target);
     * otherwise the typed text is: a folder it names is gone into, keeping the file's name, and anything else is
     * handed to {@link #onSave} as typed. The first row, highlighted only because it matches the typed prefix, is
     * not a choice: typing {@code notes} beside {@code notes.txt} saves {@code notes}.
     */
    private void saveTyped() {
        Consumer<String> save = onSave;
        if (save == null || choosing) {
            return; // a second Enter while the first one's look is under way does nothing
        }
        Path selected = list.getSelectionModel().getSelectedItem();
        if (highlightMoved && selected != null) {
            savedOrEntered(selected.toString(), listing.isDirectory(selected));
            return;
        }
        String text = input.getText();
        if (text.isBlank()) {
            savedOrEntered(text, false); // the caller says it is not a path
            return;
        }
        if (text.endsWith(SEP) || text.endsWith("/")) {
            savedOrEntered(text, true); // a folder, and no name yet
            return;
        }
        Path typed;
        try {
            typed = typedPath(expandHome(text), currentDir);
        } catch (java.nio.file.InvalidPathException e) {
            savedOrEntered(text, false); // the caller says it is not a path
            return;
        }
        if (typed == null || listing.lists(typed)) {
            savedOrEntered(text, typed != null && listing.isDirectory(typed)); // listed: known already
            return;
        }
        // A typed path: whether it is a folder is looked at off the FX thread (PERF-01, Pauta 6.2.0).
        choosing = true;
        Path target = typed;
        com.editora.perf.OffFx.then("save-as-finder-look", () -> Files.isDirectory(target), folder -> {
            choosing = false;
            if (text.equals(input.getText())) { // nothing typed meanwhile
                savedOrEntered(text, folder.or(false));
            }
        });
    }

    /** Save mode: goes into the folder {@code text} names, keeping the file's name, or saves under {@code text}. */
    private void savedOrEntered(String text, boolean folder) {
        Consumer<String> save = onSave;
        if (folder && !saveName.isEmpty()) {
            String dir = text.endsWith(SEP) || text.endsWith("/") ? text : text + SEP;
            input.setText(dir + saveName);
            input.positionCaret(input.getText().length());
            selectName();
            return;
        }
        if (folder || save == null) {
            return; // no name to save under: stay in the folder
        }
        hide();
        save.accept(text);
    }

    /**
     * Enter: in directory-pick mode, choose the target folder; otherwise descend into a directory or
     * hand a file/new path to {@code onChoose}. (In directory mode, {@code Tab} is used to descend.)
     *
     * <p>The folder a directory-pick Enter chooses ({@link #pickedFolder}): with the field ending in a separator and
     * the highlight untouched, the folder being listed, as {@code C-d} has it. Tab-completing
     * {@code projects/hello} to {@code projects/hello-fx/} and pressing Enter used to choose {@code src/}, the
     * child that happened to sit on row one (r/JavaFX dry run, 2026-11-27).
     */
    private void chooseSelected() {
        Path target = pickDirectory ? pickedFolder() : targetPath();
        if (target == null || choosing) {
            return; // a second Enter while the first one's look is under way does nothing
        }
        if (listing.lists(target)) {
            chosen(target, listing.isDirectory(target)); // listed: known already
            return;
        }
        // A typed path: whether it is a folder is looked at off the FX thread (PERF-01, Pauta 6.2.0).
        choosing = true;
        com.editora.perf.OffFx.then("file-finder-look", () -> Files.isDirectory(target), folder -> {
            choosing = false;
            if (target.equals(pickDirectory ? pickedFolder() : targetPath())) { // nothing typed meanwhile
                chosen(target, folder.or(false));
            }
        });
    }

    /** Set while {@link #chooseSelected} looks at a typed path. */
    private boolean choosing;

    private void chosen(Path target, boolean folder) {
        if (pickDirectory) {
            if (folder) {
                hide();
                onChoose.accept(target);
            }
            return;
        }
        if (folder) {
            descendInto(target);
        } else {
            hide();
            onChoose.accept(target);
        }
    }

    /** {@link #PROJECT_CHORD}: open the folder {@link #projectFolder} picks as a project and close the finder. */
    private void openAsProject() {
        Consumer<Path> action = onOpenProject;
        Path selected = list.getSelectionModel().getSelectedItem();
        Path folder = projectFolder(
                input.getText(),
                listing.readable() ? currentDir : null,
                selected,
                selected != null && listing.isDirectory(selected),
                highlightMoved);
        if (action == null || folder == null) {
            return;
        }
        hide();
        action.accept(folder);
    }

    /**
     * The folder {@link #PROJECT_CHORD} opens: the highlighted row when it is a folder the user picked — by typing
     * a name or by moving onto it — else the folder being listed ({@code listedDir}, null while it is unread or
     * unreadable). With the field ending in a separator and the highlight untouched, the user is "in" the listed
     * folder, so that is the one meant, not the first child that happens to sit on row one. Pure.
     */
    static @Nullable Path projectFolder(
            String text,
            @Nullable Path listedDir,
            @Nullable Path highlighted,
            boolean highlightedIsDir,
            boolean highlightMoved) {
        boolean picked = highlightMoved || !prefixPart(text).isEmpty();
        if (highlighted != null && highlightedIsDir && picked) {
            return highlighted;
        }
        return listedDir;
    }

    /**
     * The folder a directory-pick Enter chooses: with the field ending in a separator and the highlight untouched,
     * the folder being listed (as {@link #projectFolder} has it for {@code C-d}); else the highlighted row or the
     * typed path.
     */
    private @Nullable Path pickedFolder() {
        Path listed = listing.readable() ? currentDir : null;
        if (listed != null && prefixPart(input.getText()).isEmpty() && !highlightMoved) {
            return listed;
        }
        return targetPath();
    }

    /** The selected list entry, or the literal typed path when nothing is selected. */
    private @Nullable Path targetPath() {
        Path selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            return selected;
        }
        return typedPath(input.getText(), currentDir);
    }

    /**
     * The path a typed text names: a bare name belongs to the directory being listed, exactly as the list
     * shows it filtered — not to the process working directory, where {@code Path.of("notes.txt")} put a new
     * buffer and its first save.
     */
    static @Nullable Path typedPath(String text, @Nullable Path currentDir) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path typed = Path.of(text);
        return typed.isAbsolute() || currentDir == null ? typed : currentDir.resolve(typed);
    }

    private void descendInto(Path dir) {
        String text = dir.toString();
        input.setText(text.endsWith(SEP) ? text : text + SEP);
        input.positionCaret(input.getText().length());
    }

    /** Tab: extend the field to the longest common prefix of the matches; lone dir match → descend. */
    private void autocomplete() {
        if (items.isEmpty()) {
            return;
        }
        if (pickDirectory) {
            // Folder picker: Tab descends into the highlighted folder (Enter chooses it).
            Path selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && listing.isDirectory(selected)) {
                descendInto(selected);
            }
            return;
        }
        String lcp = rowsPrefix; // worked out with the rows, off the FX thread
        String dir = dirPart(input.getText());
        String current = prefixPart(input.getText());
        if (lcp.length() > current.length()) {
            input.setText(dir + lcp);
            input.positionCaret(input.getText().length());
        } else if (items.size() == 1 && listing.isDirectory(items.get(0))) {
            descendInto(items.get(0));
        }
    }

    private static String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return a.substring(0, i);
    }

    private final class EntryCell extends ListCell<Path> {
        private final Label label = new Label();
        private final HBox box = UiZoom.spacing(new HBox(label), 8);

        EntryCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    highlightMoved = true; // clicked: this row is the choice, also in save mode
                    accept();
                }
            });
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            boolean dir = listing.isDirectory(item); // known from the listing: no stat per rendered row
            label.setText(item.getFileName() + (dir ? SEP : ""));
            Path fileName = item.getFileName();
            box.getChildren()
                    .setAll(
                            dir
                                    ? Icons.project()
                                    : FileIcons.forFileName(fileName == null ? item.toString() : fileName.toString()),
                            label);
            setGraphic(CellNames.read(box));
        }
    }
}
