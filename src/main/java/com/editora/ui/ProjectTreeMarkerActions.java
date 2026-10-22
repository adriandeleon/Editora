package com.editora.ui;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import com.editora.config.PersonalNote;
import com.editora.editor.notes.NoteDraft;

/**
 * The Project tree's bookmark and personal-note marks: what a file has, and the context-menu actions that add
 * or edit them, each delegated to the window's bookmark and notes coordinators. Built with the Project panel
 * on its first open (see {@code MainController.projectPanel()}).
 */
final class ProjectTreeMarkerActions implements ProjectPanel.MarkerActions {

    private final BookmarkCoordinator bookmarks;
    private final NotesCoordinator notes;

    ProjectTreeMarkerActions(BookmarkCoordinator bookmarks, NotesCoordinator notes) {
        this.bookmarks = bookmarks;
        this.notes = notes;
    }

    @Override
    public boolean personalNotesEnabled() {
        return notes.isEnabled();
    }

    @Override
    public boolean hasBookmarks(Path file) {
        return bookmarks.hasBookmarks(file);
    }

    @Override
    public boolean hasPersonalNotes(Path file) {
        return notes.hasPersonalNotes(file);
    }

    @Override
    public String personalNotesTooltip(Path path) {
        return notes.personalNotesTooltip(path);
    }

    @Override
    public void addBookmark(Path file) {
        bookmarks.addBookmark(file);
    }

    @Override
    public void addBookmark(Path file, int line) {
        bookmarks.addBookmark(file, line);
    }

    @Override
    public void addPersonalNote(Path file) {
        notes.addPersonalNote(file);
    }

    @Override
    public void addPersonalNote(Path file, NoteDraft draft) {
        notes.addPersonalNote(file, draft);
    }

    @Override
    public List<PersonalNote> personalNotes(Path path) {
        List<PersonalNote> found = notes.notesFor(path);
        return found == null ? List.of() : List.copyOf(found);
    }

    @Override
    public void updatePersonalNote(Path path, PersonalNote note, String body) {
        notes.updatePersonalNote(path, note, body);
    }

    @Override
    public Collection<Path> markedPaths() {
        return ProjectPanel.pathsOf(bookmarks.storedKeys(), notes.storedKeys());
    }
}
