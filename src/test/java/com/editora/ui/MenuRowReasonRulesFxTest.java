package com.editora.ui;

import com.editora.git.GitFileStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rules that pick a grayed row's sentence (KBD-04, Pauta 6.2.0), case by case: a reason key where the row cannot
 * run, null where it can. Pure; the toolkit is booted only because the classes they live in are JavaFX views.
 */
@Tag("fx")
class MenuRowReasonRulesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aFileTabsRows() {
        assertNull(TabContextMenu.encryptionReason(false, false, false), "Encrypt… fits any file");
        assertEquals("row.unavailable.fileLocked", TabContextMenu.encryptionReason(true, true, true));
        assertEquals("row.unavailable.unsaved", TabContextMenu.encryptionReason(true, false, false));
        assertNull(TabContextMenu.encryptionReason(true, false, true));

        assertEquals("row.unavailable.unsaved", TabContextMenu.revealReason(false, true));
        assertEquals("row.unavailable.remoteFile", TabContextMenu.revealReason(true, false));
        assertNull(TabContextMenu.revealReason(true, true));

        assertEquals("row.unavailable.nothingToRevert", TabContextMenu.revertReason(GitPathScope.ACTIVE, null));
        assertNull(TabContextMenu.revertReason(GitPathScope.ACTIVE, GitFileStatus.MODIFIED));
        assertNull(TabContextMenu.revertReason(GitPathScope.OTHER, null), "another repository's: Git decides");

        assertEquals(
                "row.unavailable.notUntracked",
                TabContextMenu.ignoreReason(GitPathScope.ACTIVE, GitFileStatus.MODIFIED));
        assertNull(TabContextMenu.ignoreReason(GitPathScope.ACTIVE, GitFileStatus.UNTRACKED));
        assertNull(TabContextMenu.ignoreReason(GitPathScope.OTHER, GitFileStatus.MODIFIED));
    }

    @Test
    void aMapCardsRows() {
        assertEquals("row.unavailable.declarationGone", ProjectMapPreview.answerReason(true, false, true));
        assertEquals("row.unavailable.emptyView", ProjectMapPreview.answerReason(false, false, true));
        assertEquals("row.unavailable.languageCannotAnswer", ProjectMapPreview.answerReason(false, true, false));
        assertNull(ProjectMapPreview.answerReason(false, true, true));

        assertEquals("row.unavailable.fileGone", ProjectMapPreview.goneReason(true, true));
        assertEquals("row.unavailable.declarationGone", ProjectMapPreview.goneReason(false, true));
        assertNull(ProjectMapPreview.goneReason(false, false));
    }

    @Test
    void theValuePopupsPinRow() {
        assertEquals("row.unavailable.noSelection", DebugValuePopup.pinReason("", "row.unavailable.noSelection", true));
        assertEquals(
                "row.unavailable.noExpression", DebugValuePopup.pinReason("", "row.unavailable.noExpression", false));
        assertEquals(
                "row.unavailable.noLineToPinTo", DebugValuePopup.pinReason("x", "row.unavailable.noSelection", false));
        assertNull(DebugValuePopup.pinReason("x", "row.unavailable.noSelection", true));
    }

    @Test
    void theBookmarksAndTerminalsMoveRows() {
        assertEquals("row.unavailable.bookmarksFiltered", BookmarksPanel.moveReason(false, true));
        assertEquals("row.unavailable.otherProjectsBookmark", BookmarksPanel.moveReason(true, false));
        assertNull(BookmarksPanel.moveReason(true, true));

        assertEquals("k", TerminalPanel.afterReason(-1, 3, "k"), "a tab not in the strip");
        assertEquals("k", TerminalPanel.afterReason(2, 3, "k"), "the last tab");
        assertNull(TerminalPanel.afterReason(0, 3, "k"));
    }
}
