package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Node.js's own frames fold into one row of the call stack (review D105). */
class StackFoldingTest {

    private static DapModels.StackFrameInfo user(int id) {
        return new DapModels.StackFrameInfo(id, "main", Path.of("/p/app.js"), id, 0, null, "");
    }

    private static DapModels.StackFrameInfo internal(int id) {
        return new DapModels.StackFrameInfo(id, "<anonymous>", null, id, 0, null, DapModels.HINT_SUBTLE);
    }

    private static DapModels.StackFrameInfo await(int id) {
        return new DapModels.StackFrameInfo(id, "await", null, 0, 0, null, DapModels.HINT_LABEL);
    }

    private static List<String> describe(List<DapModels.StackFrameInfo> rows) {
        List<String> out = new ArrayList<>();
        for (DapModels.StackFrameInfo f : rows) {
            out.add(StackFolding.isFold(f) ? "fold" + f.line() : String.valueOf(f.id()));
        }
        return out;
    }

    @Test
    void runsOfRuntimeFramesAndAwaitsFoldIntoOneRow() {
        List<DapModels.StackFrameInfo> frames =
                List.of(user(1), user(2), internal(3), internal(4), await(5), internal(6), user(7), internal(8));
        StackFolding.Shown shown = StackFolding.fold(frames, 0, false);
        assertEquals(List.of("1", "2", "fold3", "7", "8"), describe(shown.rows()));
        assertEquals(0, shown.selectedRow());
    }

    @Test
    void theSelectedFrameIsNeverFoldedAway() {
        List<DapModels.StackFrameInfo> frames =
                List.of(internal(1), internal(2), internal(3), internal(4), internal(5), user(6));
        StackFolding.Shown shown = StackFolding.fold(frames, 2, false);
        assertEquals(List.of("fold2", "3", "fold2", "6"), describe(shown.rows()));
        assertSame(frames.get(2), shown.rows().get(shown.selectedRow()));
    }

    @Test
    void anUnfoldedStackOrOneWithoutRuntimeFramesIsListedAsItCame() {
        List<DapModels.StackFrameInfo> frames = List.of(user(1), internal(2), internal(3));
        assertEquals(frames, StackFolding.fold(frames, 0, true).rows());
        List<DapModels.StackFrameInfo> plain = List.of(user(1), await(2), user(3));
        assertEquals(plain, StackFolding.fold(plain, 2, false).rows());
        assertTrue(StackFolding.fold(List.of(), 0, false).rows().isEmpty());
    }
}
