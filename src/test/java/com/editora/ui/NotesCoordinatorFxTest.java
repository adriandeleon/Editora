package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PathKeys;
import com.editora.config.PersonalNote;
import com.editora.config.Settings;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import com.editora.editor.NoteDraft;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NotesCoordinator} against a recording window and a real in-scene overlay: what each entry point
 * leaves in the note store, in an open buffer and in the status line, with the note editor answered the way
 * a user answers it (type, Save / Cancel / Delete and its confirmation).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesCoordinatorFxTest {

    @TempDir
    Path dir;

    private final Map<String, Map<String, List<PersonalNote>>> all = new LinkedHashMap<>();
    private final Map<Path, EditorBuffer> open = new LinkedHashMap<>();
    private final List<EditorBuffer> created = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Settings settings = new Settings();
    private EditorBuffer active;
    private int changed;
    private Stage stage;
    private OverlayHost overlay;
    private NotesCoordinator coordinator;

    private final CoordinatorHostStub host = new CoordinatorHostStub() {
        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            List.copyOf(created).forEach(action);
        }

        @Override
        public void setStatus(String message) {
            events.add("status: " + message);
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }
    };

    private final NotesCoordinator.Ops ops = new NotesCoordinator.Ops() {
        @Override
        public void openPath(Path file) {
            events.add("open " + file.getFileName());
        }

        @Override
        public void navigateToLine(int line) {
            events.add("navigate " + line);
        }

        @Override
        public void openInProjectWindow(String projectKey, Path file, int line) {
            events.add("openIn[" + projectKey + "] " + file.getFileName() + ":" + line);
        }

        @Override
        public String noteKey(EditorBuffer buffer) {
            return PathKeys.canonicalKey(buffer.getPath());
        }

        @Override
        public EditorBuffer bufferForKey(String fileKey) {
            return open.entrySet().stream()
                    .filter(e -> PathKeys.canonicalKey(e.getKey()).equals(fileKey))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return open.get(file);
        }

        @Override
        public void installEmacsKeys(javafx.scene.control.TextInputControl control) {}

        @Override
        public void setToolWindowAvailable(boolean available) {
            events.add("toolWindow " + available);
        }

        @Override
        public Map<String, List<PersonalNote>> notes() {
            return all.get("proj");
        }

        @Override
        public Map<String, Map<String, List<PersonalNote>>> allNotes() {
            return all;
        }

        @Override
        public String currentProjectKey() {
            return "proj";
        }

        @Override
        public String projectName(String key) {
            return key.isEmpty() ? "General" : key;
        }

        @Override
        public void saveNotes() {
            events.add("save");
        }

        @Override
        public void notesStored(Map<String, ?> bucket, String fileKey) {
            String which = bucket == all.get("proj") ? "proj" : bucket == all.get("") ? "general" : "?";
            events.add("stored " + which + " "
                    + (fileKey == null ? "*" : Path.of(fileKey).getFileName()));
        }
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            StackPane root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 800, 600));
            stage.show();
            overlay = new OverlayHost();
            overlay.install(root);
        });
    }

    @AfterAll
    void closeStage() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void setUp() throws Exception {
        all.clear();
        all.put("", new LinkedHashMap<>());
        all.put("proj", new LinkedHashMap<>());
        open.clear();
        events.clear();
        active = null;
        changed = 0;
        settings.setNotesSupport(true);
        coordinator = FxTestSupport.callOnFx(() -> new NotesCoordinator(host, ops));
        FxTestSupport.runOnFx(() -> coordinator.setOnChanged(() -> changed++));
    }

    @AfterEach
    void tidy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (overlay.isShowing()) {
                overlay.hide();
            }
            coordinator.flushPendingPersist();
            created.forEach(EditorBuffer::dispose);
            created.clear();
        });
    }

    // --- plumbing ---------------------------------------------------------------------------------------

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    private Path file(String name) {
        return dir.resolve(name);
    }

    private String key(String name) {
        return PathKeys.canonicalKey(file(name));
    }

    private Map<String, List<PersonalNote>> store() {
        return all.get("proj");
    }

    private List<String> bodies(String name) {
        List<PersonalNote> notes = store().get(key(name));
        return notes == null ? null : notes.stream().map(PersonalNote::body).toList();
    }

    private List<String> liveBodies(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() ->
                b.getNoteManager().snapshot().stream().map(PersonalNote::body).toList());
    }

    private EditorBuffer buffer(String name, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setNotesEnabled(true);
            if (name != null) {
                b.setPath(file(name));
                open.put(file(name), b);
            }
            b.setContent(text);
            created.add(b);
            return b;
        });
    }

    /** A note on {@code line} of an open buffer, added the way the editor adds one. */
    private PersonalNote liveNote(EditorBuffer b, int line, String body) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            NoteDraft draft = b.captureLineNoteDraft(line);
            PersonalNote note = PersonalNote.create(b.fileIdentity(), draft.scope(), draft.anchor(), body, List.of());
            b.getNoteManager().add(note);
            return note;
        });
    }

    private PersonalNote stored(String name, int line, String body) {
        Path path = file(name);
        FileIdentity id = new FileIdentity(path.toString(), key(name), 1, 1, "");
        return PersonalNote.create(id, NoteScope.LINE, new TextAnchor(line, 0, line, 0, "", "", ""), body, List.of());
    }

    private void put(String bucket, String name, PersonalNote... notes) {
        all.get(bucket).put(key(name), new ArrayList<>(List.of(notes)));
    }

    private Node card() {
        assertTrue(overlay.isShowing(), "the note editor is showing");
        StackPane root = FxTestSupport.field(overlay, "overlayRoot");
        return root.getChildren().get(1);
    }

    private String editorText() throws Exception {
        return FxTestSupport.callOnFx(() -> ((TextArea) card().lookup(".text-area")).getText());
    }

    private Button button(String label) {
        return (Button) card().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button on the note editor"));
    }

    /** Types {@code text} into the note editor and presses the button labelled {@code label}. */
    private void answer(String text, String label) throws Exception {
        fx(() -> {
            if (text != null) {
                ((TextArea) card().lookup(".text-area")).setText(text);
            }
            button(label).fire();
        });
    }

    private boolean editorShowing() throws Exception {
        return FxTestSupport.callOnFx(overlay::isShowing);
    }

    private NotesPanel.Actions panelActions() {
        return FxTestSupport.field(coordinator.panel(), "actions");
    }

    // --- the enable switch ------------------------------------------------------------------------------

    @Test
    void whileNotesAreOffEveryEntryPointIsInertAndCommandsSayWhy() throws Exception {
        settings.setNotesSupport(false);
        put("proj", "a.txt", stored("a.txt", 0, "kept"));
        EditorBuffer b = buffer("a.txt", "one\ntwo");

        assertFalse(coordinator.isEnabled());
        assertEquals(List.of(), List.copyOf(coordinator.storedKeys()));
        assertFalse(coordinator.hasPersonalNotes(file("a.txt")));
        assertEquals("", coordinator.personalNotesTooltip(file("a.txt")));
        fx(() -> {
            coordinator.addPersonalNote(file("a.txt"));
            coordinator.ifEnabled(() -> events.add("ran"));
            coordinator.restoreNotes(b);
            coordinator.persistNotes(b);
            coordinator.updatePersonalNote(
                    file("a.txt"), store().get(key("a.txt")).getFirst(), "changed");
            coordinator.bufferPathChanged(b, file("old.txt"));
            coordinator.storeChangedElsewhere(store(), null);
        });
        String disabled = "status: " + tr("statusbar.tip.notesDisabled");
        assertEquals(List.of(disabled, disabled), events);
        assertFalse(editorShowing());
        assertEquals(List.of("kept"), bodies("a.txt"));
        assertEquals(List.of(), liveBodies(b));
    }

    @Test
    void switchingNotesOnLoadsTheOpenBuffersNotesOnce() throws Exception {
        settings.setNotesSupport(false);
        EditorBuffer b = buffer("a.txt", "one\ntwo");
        put("proj", "a.txt", stored("a.txt", 1, "from the store"));

        fx(coordinator::applySupport);
        assertEquals(List.of("toolWindow false"), events);
        assertEquals(List.of(), liveBodies(b));

        settings.setNotesSupport(true);
        fx(coordinator::applySupport);
        assertEquals(List.of("from the store"), liveBodies(b));
        assertEquals("toolWindow true", events.get(1));

        fx(() -> {
            b.getNoteManager().clear();
            coordinator.applySupport(); // already on: nothing is re-read over the user's live edits
        });
        assertEquals(List.of(), liveBodies(b));
    }

    // --- adding -----------------------------------------------------------------------------------------

    @Test
    void aNoteForAClosedFileIsStoredAtItsFirstLineWhenSaved() throws Exception {
        Files.writeString(file("a.txt"), "one\ntwo");
        fx(() -> coordinator.addPersonalNote(null));
        assertFalse(editorShowing(), "no file, no editor");

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        assertEquals("", editorText());
        answer("   ", tr("dialog.save"));
        assertNull(bodies("a.txt"), "a blank note is not a note");

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        answer(null, tr("dialog.cancel"));
        assertNull(bodies("a.txt"));
        assertEquals(List.of(), events);

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        answer("  remember this \n", tr("dialog.save"));
        assertEquals(List.of("remember this"), bodies("a.txt"));
        PersonalNote note = store().get(key("a.txt")).getFirst();
        assertEquals(NoteScope.LINE, note.scope());
        assertEquals(0, note.anchor().line());
        assertEquals(file("a.txt").toString(), note.file().path());
        assertEquals(List.of("save", "stored proj a.txt"), events);
        assertEquals(1, changed);
        assertTrue(coordinator.hasPersonalNotes(file("a.txt")));
        assertEquals(List.of(key("a.txt")), List.copyOf(coordinator.storedKeys()));

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        answer("second", tr("dialog.save"));
        assertEquals(List.of("remember this", "second"), bodies("a.txt"), "appended to the file's list");
    }

    @Test
    void aNoteFromAPreviewKeepsThePreviewsAnchor() throws Exception {
        NoteDraft draft = new NoteDraft(NoteScope.WORD, new TextAnchor(4, 2, 4, 9, "the sel", "pre", "suf"));
        fx(() -> coordinator.addPersonalNote(file("a.txt"), draft));
        answer("about that", tr("dialog.save"));
        PersonalNote note = store().get(key("a.txt")).getFirst();
        assertEquals(NoteScope.WORD, note.scope());
        assertEquals(4, note.anchor().line());
        assertEquals("the sel", note.anchor().selectedText());
    }

    @Test
    void aFolderGetsAFolderNoteAndItsTooltipListsOnlyFolderNotes() throws Exception {
        Path folder = Files.createDirectory(file("docs"));
        fx(() -> coordinator.addPersonalNote(folder));
        answer("the docs live here", tr("dialog.save"));
        String folderKey = PathKeys.canonicalKey(folder);
        PersonalNote note = store().get(folderKey).getFirst();
        assertEquals(NoteScope.FOLDER, note.scope());

        List<PersonalNote> more = new ArrayList<>(store().get(folderKey));
        more.add(note.withBody(" ").withStatus(NoteStatus.RESOLVED));
        more.add(note.withBody("lost").withStatus(NoteStatus.ORPHANED));
        more.add(PersonalNote.create(note.file(), NoteScope.LINE, note.anchor(), "a line note", List.of()));
        store().put(folderKey, more);

        assertEquals(
                "the docs live here\n\n✓ " + tr("notes.empty") + "\n\n⚠ lost",
                coordinator.personalNotesTooltip(folder));
        assertEquals("", coordinator.personalNotesTooltip(null));
        assertEquals("", coordinator.personalNotesTooltip(file("nothing-here")));
    }

    @Test
    void aNoteForAnOpenFileGoesIntoItsBuffer() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        assertFalse(coordinator.hasPersonalNotes(file("a.txt")), "open, and its buffer has none");

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        answer("first line note", tr("dialog.save"));
        NoteDraft draft = FxTestSupport.callOnFx(() -> b.captureLineNoteDraft(2));
        fx(() -> coordinator.addPersonalNote(file("a.txt"), draft));
        answer("third line note", tr("dialog.save"));

        List<PersonalNote> live =
                FxTestSupport.callOnFx(() -> b.getNoteManager().snapshot());
        assertEquals(
                List.of("first line note", "third line note"),
                live.stream().map(PersonalNote::body).toList());
        assertEquals(List.of(0, 2), live.stream().map(n -> n.anchor().line()).toList());
        assertNull(bodies("a.txt"), "the buffer writes the store when it persists");
        assertTrue(coordinator.hasPersonalNotes(file("a.txt")));
    }

    @Test
    void notesStoredUnderAnotherSpellingOfThePathAreFoundThroughTheirFileIdentity() throws Exception {
        // Stored under a key that is not the canonical one, as an older version or another platform wrote it.
        PersonalNote note = stored("a.txt", 0, "old key");
        store().put("legacy-key", new ArrayList<>(List.of(note)));
        store().put("damaged", null);

        assertEquals(List.of(note), coordinator.notesFor(file("a.txt")));
        assertTrue(coordinator.hasPersonalNotes(file("a.txt")));
        assertNull(coordinator.notesFor(file("b.txt")));
        assertFalse(coordinator.hasPersonalNotes(file("b.txt")));

        fx(() -> coordinator.addPersonalNote(file("a.txt")));
        answer("joins the others", tr("dialog.save"));
        assertEquals(
                List.of("old key", "joins the others"),
                store().get("legacy-key").stream().map(PersonalNote::body).toList(),
                "a new note goes under the key the file's notes already use");
        assertNull(store().get(key("a.txt")));
    }

    @Test
    void addingFromTheEditorNeedsASavedFileAndAnchorsAtTheCaret() throws Exception {
        fx(coordinator::addNoteAtCaret);
        active = buffer(null, "unsaved");
        fx(coordinator::addNoteAtCaret);
        String saveFirst = "status: " + tr("notes.saveFirst");
        assertEquals(List.of(saveFirst, saveFirst), events);
        assertFalse(editorShowing());

        active = buffer("a.txt", "one\ntwo\nthree");
        fx(() -> {
            active.getArea().moveTo(1, 0);
            coordinator.addNoteAtCaret();
        });
        answer("on two", tr("dialog.save"));
        PersonalNote note =
                FxTestSupport.callOnFx(() -> active.getNoteManager().snapshot().getFirst());
        assertEquals("on two", note.body());
        assertEquals(1, note.anchor().line());
    }

    // --- editing in an open buffer ----------------------------------------------------------------------

    @Test
    void editingTheNoteAtTheCaretRewritesItOrDeletesItAfterAConfirmation() throws Exception {
        fx(coordinator::editNoteAtCaret);
        active = buffer("a.txt", "one\ntwo\nthree");
        fx(coordinator::editNoteAtCaret);
        assertFalse(editorShowing(), "no note at the caret: nothing to edit");

        liveNote(active, 1, "original");
        fx(() -> {
            active.getArea().moveTo(1, 0);
            coordinator.editNoteAtCaret();
        });
        assertEquals("original", editorText());
        answer("rewritten", tr("dialog.save"));
        assertEquals(List.of("rewritten"), liveBodies(active));

        String question = tr("dialog.note.deleteConfirm.content");
        fx(coordinator::editNoteAtCaret);
        assertTrue(FxDialogs.duringContent(
                        () -> button(tr("notes.delete")).fire(), question, ButtonBar.ButtonData.CANCEL_CLOSE)
                != null);
        assertEquals(List.of("rewritten"), liveBodies(active), "Cancel keeps the note");

        fx(() -> {
            if (!overlay.isShowing()) {
                coordinator.editNoteAtCaret();
            }
        });
        FxDialogs.duringContent(() -> button(tr("notes.delete")).fire(), question, ButtonBar.ButtonData.OK_DONE);
        assertEquals(List.of(), liveBodies(active));
    }

    @Test
    void clickingANotesMarkerOpensThatNote() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo");
        PersonalNote note = liveNote(b, 0, "clicked");
        fx(() -> coordinator.onNoteMarkerClick(b, null));
        assertFalse(editorShowing());
        fx(() -> coordinator.onNoteMarkerClick(b, note));
        assertEquals("clicked", editorText());
    }

    @Test
    void resolvingDeletingAndJumpingWorkOnTheCaretLineAndSayWhenThereIsNoNote() throws Exception {
        fx(() -> {
            coordinator.toggleResolvedAtCaret();
            coordinator.deleteNoteAtCaret();
            coordinator.jumpNote(true);
        });
        assertEquals(List.of(), events, "no buffer");

        active = buffer("a.txt", "one\ntwo\nthree\nfour");
        String none = "status: " + tr("status.noNotesInFile");
        fx(() -> {
            coordinator.toggleResolvedAtCaret();
            coordinator.deleteNoteAtCaret();
            coordinator.jumpNote(true);
        });
        assertEquals(List.of(none, none, none), events);

        events.clear();
        liveNote(active, 1, "first");
        liveNote(active, 3, "second");
        fx(() -> {
            active.getArea().moveTo(1, 0);
            coordinator.toggleResolvedAtCaret();
        });
        assertEquals(
                NoteStatus.RESOLVED,
                FxTestSupport.callOnFx(
                        () -> active.getNoteManager().snapshot().getFirst().status()));
        fx(coordinator::toggleResolvedAtCaret);
        assertEquals(
                NoteStatus.ACTIVE,
                FxTestSupport.callOnFx(
                        () -> active.getNoteManager().snapshot().getFirst().status()));
        assertEquals(List.of("status: " + tr("status.note.resolved"), "status: " + tr("status.note.reopened")), events);

        events.clear();
        fx(() -> {
            active.getArea().moveTo(2, 0);
            coordinator.jumpNote(true);
            coordinator.jumpNote(false);
        });
        assertEquals(List.of("navigate 3", "navigate 1"), events);

        fx(() -> {
            active.getArea().moveTo(3, 0);
            coordinator.deleteNoteAtCaret();
        });
        assertEquals(List.of("first"), liveBodies(active));
    }

    // --- the tool window's actions ----------------------------------------------------------------------

    @Test
    void thePanelOpensANoteWhereItsFileSaysItIsAndAFolderNoteAtNoLine() throws Exception {
        PersonalNote line = stored("a.txt", 6, "x");
        PersonalNote noIdentity = new PersonalNote(null, null, NoteScope.LINE, line.anchor(), "y", null, null, 0, 0);
        PersonalNote folder = PersonalNote.create(
                line.file(), NoteScope.FOLDER, new TextAnchor(0, 0, 0, 0, "", "", ""), "z", List.of());
        fx(() -> {
            panelActions().openAndJump("", "stale-key", line);
            panelActions().openAndJump("proj", key("b.txt"), noIdentity);
            panelActions().openAndJump("proj", key("a.txt"), folder);
        });
        assertEquals(List.of("openIn[] a.txt:6", "openIn[proj] b.txt:6", "openIn[proj] a.txt:-1"), events);
    }

    @Test
    void aClosedFilesNoteIsEditedResolvedAndDeletedInTheStore() throws Exception {
        PersonalNote first = stored("a.txt", 0, "first");
        PersonalNote second = stored("a.txt", 3, "second");
        put("proj", "a.txt", first, second);

        fx(() -> panelActions().editBody("proj", key("a.txt"), second));
        assertEquals("second", editorText());
        answer("second, rewritten", tr("dialog.save"));
        assertEquals(List.of("first", "second, rewritten"), bodies("a.txt"));
        assertEquals(List.of("save", "stored proj a.txt"), events);

        fx(() -> panelActions().setStatus("proj", key("a.txt"), first, NoteStatus.RESOLVED));
        assertEquals(NoteStatus.RESOLVED, store().get(key("a.txt")).getFirst().status());

        fx(() -> panelActions().editBody("proj", key("a.txt"), first));
        FxDialogs.duringContent(
                () -> button(tr("notes.delete")).fire(),
                tr("dialog.note.deleteConfirm.content"),
                ButtonBar.ButtonData.OK_DONE);
        assertEquals(List.of("second, rewritten"), bodies("a.txt"));

        fx(() -> panelActions().delete("proj", key("a.txt"), second));
        assertFalse(store().containsKey(key("a.txt")), "a file with no notes left is dropped from the store");

        events.clear();
        fx(() -> {
            panelActions().delete("proj", key("a.txt"), second); // already gone
            panelActions().setStatus("nowhere", key("a.txt"), second, NoteStatus.RESOLVED);
            panelActions().deleteAll("proj", key("a.txt"));
            panelActions().deleteAll("nowhere", key("a.txt"));
        });
        assertEquals(List.of(), events, "nothing to change: nothing written");
    }

    @Test
    void anOpenFilesNotesAreChangedThroughItsBuffer() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        PersonalNote first = liveNote(b, 0, "first");
        PersonalNote second = liveNote(b, 2, "second");

        fx(() -> panelActions().editBody("proj", key("a.txt"), first));
        answer("first, live", tr("dialog.save"));
        fx(() -> panelActions().setStatus("proj", key("a.txt"), second, NoteStatus.RESOLVED));
        List<PersonalNote> live =
                FxTestSupport.callOnFx(() -> b.getNoteManager().snapshot());
        assertEquals(
                List.of("first, live", "second"),
                live.stream().map(PersonalNote::body).toList());
        assertEquals(NoteStatus.RESOLVED, live.get(1).status());

        fx(() -> panelActions().delete("proj", key("a.txt"), first));
        assertEquals(List.of("second"), liveBodies(b));
        fx(() -> panelActions().deleteAll("proj", key("a.txt")));
        assertEquals(List.of(), liveBodies(b));
        assertFalse(events.contains("save"), "the buffer persists its own changes");
    }

    @Test
    void anotherProjectsNotesAreChangedInTheirOwnBucketEvenWhenTheFileIsOpenHere() throws Exception {
        PersonalNote general = stored("a.txt", 0, "general");
        put("", "a.txt", general, stored("a.txt", 1, "also general"));
        EditorBuffer b = buffer("a.txt", "one\ntwo");
        liveNote(b, 0, "this project's");

        fx(() -> panelActions().delete(null, key("a.txt"), general)); // a null key means General
        assertEquals(
                List.of("also general"),
                all.get("").get(key("a.txt")).stream().map(PersonalNote::body).toList());
        assertTrue(events.contains("stored general a.txt"), events.toString());

        fx(() -> panelActions().deleteAll("", key("a.txt")));
        assertFalse(all.get("").containsKey(key("a.txt")));
        assertEquals(List.of("this project's"), liveBodies(b));
    }

    @Test
    void updatingANoteFromAPreviewRewritesItsBodyWhereverItLives() throws Exception {
        PersonalNote closed = stored("closed.txt", 0, "closed");
        put("proj", "closed.txt", closed);
        EditorBuffer b = buffer("open.txt", "one");
        PersonalNote live = liveNote(b, 0, "open");

        fx(() -> {
            coordinator.updatePersonalNote(null, closed, "x");
            coordinator.updatePersonalNote(file("closed.txt"), null, "x");
            coordinator.updatePersonalNote(file("closed.txt"), closed, null);
            coordinator.updatePersonalNote(file("closed.txt"), closed, "  ");
        });
        assertEquals(List.of("closed"), bodies("closed.txt"));
        assertEquals(List.of(), events);

        fx(() -> {
            coordinator.updatePersonalNote(file("closed.txt"), closed, " closed, edited ");
            coordinator.updatePersonalNote(file("open.txt"), live, " open, edited ");
        });
        assertEquals(List.of("closed, edited"), bodies("closed.txt"));
        assertEquals(List.of("open, edited"), liveBodies(b));
    }

    // --- the pickers ------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void thePickersListThisProjectsNotesByFirstLineAndSearchBodyTagsAndPath() throws Exception {
        PersonalNote multi = stored("a.txt", 4, "\nfirst line\nsecond line");
        PersonalNote empty = stored("a.txt", 0, "  ");
        PersonalNote tagged = PersonalNote.create(
                stored("b.txt", 0, "").file(),
                NoteScope.FOLDER,
                new TextAnchor(0, 0, 0, 0, "", "", ""),
                "tagged",
                List.of("todo", "perf"));
        put("proj", "a.txt", multi, empty);
        put("proj", "b.txt", tagged);
        store().put("damaged", null);
        put("", "g.txt", stored("g.txt", 0, "another project's"));

        for (String name : List.of("jumpPalette", "searchPalette")) {
            QuickOpen<Object> picker = FxTestSupport.field(coordinator, name);
            Supplier<List<Object>> items = FxTestSupport.field(picker, "itemsSupplier");
            Function<Object, String> label = FxTestSupport.field(picker, "label");
            Function<Object, String> detail = FxTestSupport.field(picker, "detail");
            List<Object> rows = FxTestSupport.callOnFx(items::get);
            assertEquals(
                    List.of(
                            "first line | a.txt:5",
                            tr("notes.empty") + " | a.txt:1",
                            "tagged | " + Path.of(key("b.txt"))),
                    rows.stream()
                            .map(r -> label.apply(r) + " | " + detail.apply(r))
                            .toList(),
                    name);
        }

        QuickOpen<Object> search = FxTestSupport.field(coordinator, "searchPalette");
        Supplier<List<Object>> items = FxTestSupport.field(search, "itemsSupplier");
        Function<Object, String> searchKey = FxTestSupport.field(search, "searchKey");
        Consumer<Object> choose = FxTestSupport.field(search, "onChoose");
        List<Object> rows = FxTestSupport.callOnFx(items::get);
        assertEquals("tagged todo perf " + key("b.txt"), searchKey.apply(rows.get(2)));
        assertTrue(searchKey.apply(rows.get(0)).contains("second line"), "the whole body is searchable");

        fx(() -> choose.accept(rows.get(0)));
        assertEquals(List.of("openIn[proj] a.txt:4"), events);
    }

    // --- persistence around a buffer --------------------------------------------------------------------

    @Test
    void aNarrowedOrUntitledBufferIsNotPersisted() throws Exception {
        EditorBuffer untitled = buffer(null, "x");
        EditorBuffer narrowed = buffer("a.txt", "one\ntwo\nthree\nfour");
        put("proj", "a.txt", stored("a.txt", 3, "on four"));
        fx(() -> {
            coordinator.restoreNotes(untitled);
            coordinator.restoreNotes(narrowed);
            assertTrue(narrowed.narrowTo(4, 13));
        });
        events.clear();
        fx(() -> {
            coordinator.persistNotes(untitled);
            coordinator.persistNotes(narrowed);
        });
        assertEquals(List.of(), events);
        assertEquals(
                3, store().get(key("a.txt")).getFirst().anchor().line(), "region-relative lines never reach the store");
    }

    @Test
    void aDebouncedPersistIsWrittenWhenFlushedAndAnEmptyListDropsTheFile() throws Exception {
        EditorBuffer a = buffer("a.txt", "one\ntwo");
        EditorBuffer b = buffer("b.txt", "one\ntwo");
        liveNote(a, 0, "in a");
        liveNote(b, 1, "in b");
        fx(() -> {
            coordinator.schedulePersistNotes(a);
            coordinator.schedulePersistNotes(b);
            coordinator.schedulePersistNotes(a);
        });
        assertNull(bodies("a.txt"), "nothing is written while the edit burst is still going");
        assertEquals(3, changed);

        fx(coordinator::flushPendingPersist);
        assertEquals(List.of("in a"), bodies("a.txt"));
        assertEquals(List.of("in b"), bodies("b.txt"));
        assertEquals(2, events.stream().filter("save"::equals).count());

        fx(() -> {
            a.getNoteManager().clear();
            coordinator.persistNotes(a);
        });
        assertFalse(store().containsKey(key("a.txt")));
    }

    @Test
    void notesOfAFileRenamedOutsideTheEditorAreReattachedByContentOnOpen() throws Exception {
        Path before = Files.writeString(file("before.txt"), "the same bytes\nsecond line\n");
        FileIdentity identity = FileIdentity.of(before);
        PersonalNote note = PersonalNote.create(
                identity,
                NoteScope.LINE,
                new TextAnchor(1, 0, 1, 11, "second line", "", ""),
                "survives the move",
                List.of());
        store().put(PathKeys.canonicalKey(before), new ArrayList<>(List.of(note)));
        Path after = Files.move(before, file("after.txt"));

        EditorBuffer b = buffer("after.txt", Files.readString(after));
        fx(() -> coordinator.restoreNotes(b));

        assertEquals(List.of("survives the move"), liveBodies(b));
        assertEquals(List.of(key("after.txt")), List.copyOf(store().keySet()), "the store is re-keyed to the new path");
        assertTrue(events.contains("save"), events.toString());
    }

    @Test
    void aCopyOfAnAnnotatedFileDoesNotTakeItsNotes() throws Exception {
        Path original = Files.writeString(file("original.txt"), "identical\n");
        put(
                "proj",
                "original.txt",
                PersonalNote.create(
                        FileIdentity.of(original),
                        NoteScope.LINE,
                        new TextAnchor(0, 0, 0, 9, "identical", "", ""),
                        "mine",
                        List.of()));
        Files.copy(original, file("copy.txt"));

        EditorBuffer copy = buffer("copy.txt", "identical\n");
        fx(() -> coordinator.restoreNotes(copy));
        assertEquals(List.of(), liveBodies(copy));
        assertEquals(List.of("mine"), bodies("original.txt"), "the original still exists, so its notes stay with it");
    }

    @Test
    void aChangeFromAnotherWindowReachesTheMatchingOpenBuffers() throws Exception {
        EditorBuffer a = buffer("a.txt", "one\ntwo\nthree");
        EditorBuffer b = buffer("b.txt", "one\ntwo\nthree");
        EditorBuffer narrowed = buffer("n.txt", "one\ntwo\nthree");
        buffer(null, "untitled");
        fx(() -> assertTrue(narrowed.narrowTo(0, 3)));
        put("proj", "a.txt", stored("a.txt", 2, "for a"));
        put("proj", "b.txt", stored("b.txt", 1, "for b"));
        put("proj", "n.txt", stored("n.txt", 0, "for n"));

        fx(() -> coordinator.storeChangedElsewhere(store(), key("a.txt")));
        assertEquals(List.of("for a"), liveBodies(a));
        assertEquals(List.of(), liveBodies(b), "only the named file is re-read");

        fx(() -> coordinator.storeChangedElsewhere(store(), null));
        assertEquals(List.of("for b"), liveBodies(b));
        assertEquals(List.of(), liveBodies(narrowed), "a narrowed buffer is left alone until it widens");

        put("proj", "a.txt", stored("a.txt", 0, "replaced"));
        fx(() -> coordinator.storeChangedElsewhere(all.get(""), key("a.txt")));
        assertEquals(List.of("for a"), liveBodies(a), "another project's bucket does not touch this window's buffers");
        assertEquals(3, changed, "but the panel, which shows every project, is redrawn");
    }

    @Test
    void aRenameMovesTheStoredNotesOfEverythingBelowAFolder() throws Exception {
        Path folder = Files.createDirectory(file("src"));
        Path inside = Files.writeString(folder.resolve("A.java"), "class A {}");
        String oldKey = PathKeys.canonicalKey(inside);
        store().put(oldKey, new ArrayList<>(List.of(stored("src/A.java", 0, "moves along"))));
        put("proj", "other.txt", stored("other.txt", 0, "stays"));
        Path target = Files.move(folder, file("main"));

        fx(() -> coordinator.pathRenamed(folder, target));
        assertEquals(
                List.of("moves along"),
                store().get(PathKeys.canonicalKey(target.resolve("A.java"))).stream()
                        .map(PersonalNote::body)
                        .toList());
        assertFalse(store().containsKey(oldKey));
        assertEquals(List.of("stays"), bodies("other.txt"));
        assertEquals(List.of("save", "stored proj *"), events);

        events.clear();
        fx(() -> coordinator.pathRenamed(file("unrelated"), file("elsewhere")));
        assertEquals(List.of(), events);
    }

    @Test
    void saveAsCarriesTheNotesToTheNewPathAndDropsThoseOfAFileThatIsGone() throws Exception {
        Path kept = Files.writeString(file("kept.txt"), "one\ntwo");
        EditorBuffer b = buffer("kept.txt", "one\ntwo");
        liveNote(b, 1, "carried");
        fx(() -> {
            coordinator.persistNotes(b);
            b.setPath(file("copy.txt"));
            coordinator.bufferPathChanged(b, kept);
        });
        assertEquals(List.of("carried"), bodies("copy.txt"));
        assertEquals(List.of("carried"), bodies("kept.txt"), "the file it left is still on disk and keeps its own");

        put("proj", "ghost.txt", stored("ghost.txt", 0, "of a file never written"));
        fx(() -> coordinator.bufferPathChanged(b, file("ghost.txt")));
        assertFalse(store().containsKey(key("ghost.txt")));

        fx(() -> coordinator.bufferPathChanged(b, null));
        assertEquals(List.of("carried"), bodies("copy.txt"));
    }

    @Test
    void saveAsOfANarrowedBufferCopiesTheStoredListBecauseItsLiveLinesAreRegionRelative() throws Exception {
        Path original = Files.writeString(file("orig.txt"), "one\ntwo\nthree");
        EditorBuffer b = buffer("orig.txt", "one\ntwo\nthree");
        put("proj", "orig.txt", stored("orig.txt", 2, "on three"));
        fx(() -> {
            coordinator.restoreNotes(b);
            assertTrue(b.narrowTo(4, 13));
            b.setPath(file("copy.txt"));
            coordinator.bufferPathChanged(b, original);
        });
        assertEquals(2, store().get(key("copy.txt")).getFirst().anchor().line());
        assertEquals(List.of("on three"), bodies("orig.txt"));
    }

    @Test
    void clearingTheChangeListenerIsSafe() throws Exception {
        put("proj", "a.txt", stored("a.txt", 0, "x"));
        fx(() -> {
            coordinator.setOnChanged(null);
            panelActions().deleteAll("proj", key("a.txt"));
        });
        assertEquals(0, changed);
        assertFalse(store().containsKey(key("a.txt")));
    }
}
