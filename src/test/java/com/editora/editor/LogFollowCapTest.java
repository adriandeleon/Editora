package com.editora.editor;

import com.editora.logviewer.LogTail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The follow reader and the follow view agree on how much of a log is kept. */
class LogFollowCapTest {

    @Test
    void aFollowStepReadsNoMoreThanTheViewKeeps() {
        // The view counts characters and the reader bytes; a byte is at most one character, so reading the
        // cap in bytes can never bring more text than the view would keep.
        assertEquals(LogView.FOLLOW_CAP, LogTail.FOLLOW_BYTES);
    }
}
