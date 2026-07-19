package com.editora.github;

import java.util.ArrayList;
import java.util.List;

import com.editora.diff.PatchParser.FilePatch;
import com.editora.git.GitFileStatus;

/**
 * Pure, toolkit-free logic behind the PR review tab: classifies each changed file (add/modify/delete/rename),
 * derives its display path, and rolls up the totals — so the {@code ui/PrReviewPane} just renders the data.
 * Reuses the {@link GitFileStatus} enum (letter + CSS class) shared with the Commit window / Project tree.
 * Unit-tested.
 */
public final class PrReviewSummary {

    private PrReviewSummary() {}

    /**
     * One file row. {@code path} is the effective file path (new side, else old) — used for the icon + as the
     * diff target; {@code displayPath} is what the row shows ({@code old → new} for a rename, else {@code path}).
     * {@code additions}/{@code deletions} are the true per-file diff stat from {@link FilePatch}.
     */
    public record FileRow(
            FilePatch patch, String path, String displayPath, GitFileStatus status, int additions, int deletions) {}

    /** Maps parsed file patches to rows, in order. */
    public static List<FileRow> rows(List<FilePatch> files) {
        List<FileRow> out = new ArrayList<>(files.size());
        for (FilePatch fp : files) {
            out.add(new FileRow(fp, effectivePath(fp), displayPath(fp), statusOf(fp), fp.additions(), fp.deletions()));
        }
        return out;
    }

    /** The file's change kind, from which side of the patch is present. */
    public static GitFileStatus statusOf(FilePatch fp) {
        boolean noOld = isAbsent(fp.oldPath());
        boolean noNew = isAbsent(fp.newPath());
        if (noOld && !noNew) {
            return GitFileStatus.ADDED;
        }
        if (noNew && !noOld) {
            return GitFileStatus.DELETED;
        }
        if (!noOld && !noNew && !clean(fp.oldPath()).equals(clean(fp.newPath()))) {
            return GitFileStatus.RENAMED;
        }
        return GitFileStatus.MODIFIED;
    }

    /** The effective file path: the new side if present, else the old side (used for the icon + diff target). */
    public static String effectivePath(FilePatch fp) {
        String newP = clean(fp.newPath());
        return !newP.isEmpty() ? newP : clean(fp.oldPath());
    }

    /** What the row shows: {@code old → new} for a rename, else the effective path. */
    public static String displayPath(FilePatch fp) {
        String oldP = clean(fp.oldPath());
        String newP = clean(fp.newPath());
        if (!oldP.isEmpty() && !newP.isEmpty() && !oldP.equals(newP)) {
            return oldP + " → " + newP;
        }
        return effectivePath(fp);
    }

    public static int totalAdditions(List<FileRow> rows) {
        int t = 0;
        for (FileRow r : rows) {
            t += r.additions();
        }
        return t;
    }

    public static int totalDeletions(List<FileRow> rows) {
        int t = 0;
        for (FileRow r : rows) {
            t += r.deletions();
        }
        return t;
    }

    /** Whether a PR description is long enough to collapse (more than {@code maxLines} lines OR {@code maxChars}
     *  characters), so the review tab shows a few lines + a "Show more" toggle. */
    public static boolean isLongDescription(String body, int maxLines, int maxChars) {
        return body != null && (body.lines().count() > maxLines || body.length() > maxChars);
    }

    /** The collapsed form of {@code body}: its first {@code maxLines} lines, further capped at {@code maxChars}
     *  characters (trailing whitespace stripped). The caller signals "there's more" via the toggle. */
    public static String collapseDescription(String body, int maxLines, int maxChars) {
        if (body == null) {
            return "";
        }
        String head = String.join("\n", body.lines().limit(maxLines).toList());
        if (head.length() > maxChars) {
            head = head.substring(0, maxChars).stripTrailing();
        }
        return head;
    }

    private static boolean isAbsent(String p) {
        return clean(p).isEmpty();
    }

    /** {@code ""} for a missing / {@code /dev/null} patch label, else the label unchanged. */
    private static String clean(String p) {
        return p == null || p.isBlank() || "/dev/null".equals(p) ? "" : p;
    }
}
