package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Next/previous change from a caret line, over {@code {line, count, kind}} triples. */
class GitHunkNavTest {

    /** Changes on lines 4-6, 10 and 20-21. */
    private static final int[] MARKS = {4, 3, 1, 10, 1, 2, 20, 2, 0};

    @Test
    void nextIsTheFirstChangeBelowTheCaret() {
        assertEquals(0, GitHunkNav.target(MARKS, 0, true));
        assertEquals(1, GitHunkNav.target(MARKS, 4, true), "from a change's first line: the one after it");
        assertEquals(1, GitHunkNav.target(MARKS, 5, true), "from inside a change too");
        assertEquals(2, GitHunkNav.target(MARKS, 10, true));
    }

    @Test
    void previousFromInsideAChangeIsTheChangeBeforeItNotItsOwnStart() {
        assertEquals(0, GitHunkNav.target(MARKS, 8, false));
        assertEquals(0, GitHunkNav.target(MARKS, 10, false));
        assertEquals(1, GitHunkNav.target(MARKS, 21, false));
        assertEquals(2, GitHunkNav.target(MARKS, 99, false));
    }

    @Test
    void theEndsWrapAndSaySo() {
        int wrappedDown = GitHunkNav.target(MARKS, 20, true);
        assertEquals(-1, wrappedDown);
        assertEquals(0, GitHunkNav.index(wrappedDown));
        int wrappedUp = GitHunkNav.target(MARKS, 5, false);
        assertEquals(-3, wrappedUp);
        assertEquals(2, GitHunkNav.index(wrappedUp));
        assertEquals(-3, GitHunkNav.target(MARKS, 0, false));
    }

    @Test
    void noChangesIsItsOwnAnswerAndLookupByLineWorks() {
        assertEquals(Integer.MIN_VALUE, GitHunkNav.target(new int[0], 3, true));
        assertEquals(3, GitHunkNav.count(MARKS));
        assertEquals(0, GitHunkNav.at(MARKS, 6));
        assertEquals(-1, GitHunkNav.at(MARKS, 7));
        assertEquals(2, GitHunkNav.at(MARKS, 21));
    }
}
