package com.editora.ui;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;

/**
 * Ranked file and folder matching over a project's file list, listed once and kept current from the Project
 * watcher (the project index seam of the Map's refactor stage 2, review 3 D). The Project Map's search, Search
 * Everywhere's file results and the Project tree's filter all ask it, so the three find the same files and rank
 * them the same way ({@link ProjectMapSearch}); each keeps its own UI. {@link ProjectFileIndex} is the one
 * implementation, over {@link ProjectFileList}.
 *
 * <p>A root is listed on first use, with the Project settings of the moment (respect {@code .gitignore}, show
 * hidden files); a list for other settings is replaced.
 */
interface ProjectIndex {

    /** What a search may answer with. */
    enum Kinds {
        /** Files and folders: the Map, where a folder match is opened. */
        FILES_AND_FOLDERS,
        /** Files only: Search Everywhere and the Project tree's filter, which open what they find. */
        FILES
    }

    /** How current a blocking answer must be. */
    enum Freshness {
        /**
         * The list as held, even when something may have changed that no event reported (a focus regain): it is
         * listed again in the background afterwards. The Map answers every keystroke this way.
         */
        AS_LISTED,
        /** A list marked out of date is listed again first (the tree's filter, which used to walk every time). */
        CURRENT
    }

    /**
     * The best {@code max} matches of {@code query} under {@code root}, best first, out of how many matched;
     * {@code partial} when the list does not cover the whole project. Lists {@code root} first when it has no
     * list yet. Blocking: call off the JavaFX thread. Null when {@code cancelled} said so part-way, or the root
     * cannot be listed (gone, or the index was closed).
     */
    @Nullable
    ProjectMapSearchResult search(
            Path root, String query, Kinds kinds, int max, Freshness freshness, BooleanSupplier cancelled);

    /**
     * As {@link #search}, from the list already held only: never lists, so it may run on the JavaFX thread. An
     * empty answer when {@code root} has not been listed yet (see {@link #whenListed}).
     */
    ProjectMapSearchResult searchListed(Path root, String query, Kinds kinds, int max);

    /**
     * Runs {@code then} on the JavaFX thread once {@code root} has a list: at once when it has one, else after it
     * was listed in the background. Always runs it, exactly once (also when listing failed).
     */
    void whenListed(Path root, Runnable then);

    /** The list of {@code root}, listed first when there is none. Blocking; null when it cannot be listed. */
    ProjectFileList.@Nullable Snapshot snapshot(Path root);
}
