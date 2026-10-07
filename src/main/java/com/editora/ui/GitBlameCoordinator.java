package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import com.editora.editor.BlameInfo;
import com.editora.editor.EditorBuffer;
import com.editora.git.BlameHeatmap;
import com.editora.git.BlameOptions;
import com.editora.git.BlameParser;
import com.editora.git.GitFormat;
import com.editora.git.GitService;
import com.editora.git.RelativeTime;

import static com.editora.i18n.Messages.tr;

/**
 * The blame ("Annotate") column: fetching it for the active buffer, formatting it, opening a line's commit,
 * the per-window attribution options ({@code -w}, {@code -M -C}) and <em>annotate previous revision</em> —
 * a read-only tab of the file as of the commit before the one a line is blamed on, itself annotated, so the
 * user can walk a line back through its history.
 *
 * <p>Owned by {@link GitCoordinator}, which keeps one-line delegations for its callers and supplies the
 * repository state. The options are in-memory and per window: they live on the window's {@link GitService},
 * which includes them in its blame cache key.
 */
final class GitBlameCoordinator {

    /** What a read-only revision tab shows: {@code path} (repo-relative) as of commit {@code hash}. */
    record Revision(Path root, String hash, String path) {}

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final GitCoordinator.WindowOps ops;

    /** Per buffer: commit hash → a line blamed on it (its path there, and the parent commit and path). */
    private final Map<EditorBuffer, Map<String, BlameParser.BlameLine>> blamed = new WeakHashMap<>();

    /** The revision tabs opened by {@link #annotatePreviousRevision()}. */
    private final Map<EditorBuffer, Revision> revisions = new WeakHashMap<>();

    /** Adds a buffer as a selected tab; attached by the window once it exists. */
    private Consumer<EditorBuffer> openTab;

    /** The ignore-revs problem last shown, so one broken file is reported once and not on every refresh. */
    private String reportedProblem = "";

    GitBlameCoordinator(CoordinatorHost host, GitCoordinator git, GitCoordinator.WindowOps ops) {
        this.host = host;
        this.git = git;
        this.ops = ops;
    }

    void attach(Consumer<EditorBuffer> openTab) {
        this.openTab = openTab;
    }

    /** Whether blame annotations are effectively on (Git enabled + the setting + not Simple mode). */
    boolean isEnabled() {
        return git.isEnabled() && host.settings().isGitBlameInline();
    }

    /** The repository of a revision tab, or {@code null} for any other buffer. */
    Path revisionRoot(EditorBuffer buffer) {
        Revision revision = buffer == null ? null : revisions.get(buffer);
        return revision == null ? null : revision.root();
    }

    /** The revision {@code buffer} shows, or {@code null} when it is not a revision tab. */
    Revision revisionOf(EditorBuffer buffer) {
        return buffer == null ? null : revisions.get(buffer);
    }

    /** Pushes blame to the active buffer (and clears it everywhere else); runs on init / settings apply /
     *  tab switch / git mutation. Only the focused buffer is annotated (blame is one git call per file). */
    void apply() {
        EditorBuffer active = host.activeBuffer();
        host.forEachBuffer(b -> {
            if (b != active) {
                b.setBlame(null);
            }
        });
        refresh(active);
    }

    /** Toggles inline blame annotations (palette + {@code M-g a}); persists the setting and re-applies. */
    void toggle() {
        git.ifEnabled(() -> {
            var s = host.settings();
            s.setGitBlameInline(!s.isGitBlameInline());
            host.requestSave();
            apply();
            ops.syncBlameCheck();
            host.setStatus(tr("status.toggle.gitBlame", tr(s.isGitBlameInline() ? "common.on" : "common.off")));
        });
    }

    /** Annotates the active buffer — enables inline blame if it's off (the project-tree "Annotate" action). */
    void annotateActive() {
        git.ifEnabled(() -> {
            var s = host.settings();
            if (!s.isGitBlameInline()) {
                s.setGitBlameInline(true);
                host.requestSave();
                ops.syncBlameCheck();
            }
            apply();
        });
    }

    /** {@code git.blame.ignoreWhitespace}: blame looks through whitespace-only changes ({@code -w}). */
    void toggleIgnoreWhitespace() {
        git.ifEnabled(() -> {
            BlameOptions options = git.service().blameOptions();
            setOptions(options.withIgnoreWhitespace(!options.ignoreWhitespace()));
            host.setStatus(tr(
                    "status.toggle.gitBlameIgnoreWhitespace",
                    tr(git.service().blameOptions().ignoreWhitespace() ? "common.on" : "common.off")));
        });
    }

    /** {@code git.blame.detectMoves}: blame follows moved and copied lines ({@code -M -C}). */
    void toggleDetectMoves() {
        git.ifEnabled(() -> {
            BlameOptions options = git.service().blameOptions();
            setOptions(options.withDetectMoves(!options.detectMoves()));
            host.setStatus(tr(
                    "status.toggle.gitBlameDetectMoves",
                    tr(git.service().blameOptions().detectMoves() ? "common.on" : "common.off")));
        });
    }

    /** Sets this window's blame options and re-annotates (the cache key carries them, so git runs again). */
    void setOptions(BlameOptions options) {
        git.service().setBlameOptions(options);
        apply();
    }

    /** Opens the read-only diff of the active file at the caret line's commit vs its parent. */
    void showCommitAtCaret() {
        EditorBuffer b = host.activeBuffer();
        showCommit(b, b == null ? null : b.blameHashAtCaret());
    }

    /** Clicking a line's blame annotation opens that line's commit (IntelliJ-style). */
    void onGutterClick(EditorBuffer buffer, int line) {
        showCommit(buffer, buffer == null ? null : buffer.blameHashAt(line));
    }

    /** Opens the read-only diff of {@code b}'s file at {@code hash} vs its parent (shared by the caret
     *  command and the gutter-annotation click). */
    private void showCommit(EditorBuffer b, String hash) {
        Revision revision = revisionOf(b);
        Path root = revision != null ? revision.root() : git.repoRoot();
        if (b == null || root == null || (revision == null && b.getPath() == null)) {
            return;
        }
        if (hash == null || hash.isBlank()) {
            host.setStatus(tr("status.git.noBlameLine"));
            return;
        }
        String rel = revision != null ? revision.path() : GitService.repoRelative(root, b.getPath());
        if (rel != null) {
            // Blame follows renames: a line older than a move belongs to the file's OLD path in its commit.
            BlameParser.BlameLine line = blamed.getOrDefault(b, Map.of()).get(hash);
            String at = line == null || line.path() == null ? rel : line.path();
            ops.openCommitFileDiff(hash, at, line == null ? null : line.previousPath());
        }
    }

    /**
     * {@code git.blamePreviousRevision}: opens the file as it was in the commit <em>before</em> the one the
     * caret line is blamed on — the porcelain's {@code previous} commit and path, so a rename is followed —
     * in a read-only tab that is annotated in turn. Repeating it there walks the line further back. A line
     * whose commit added the file has nothing before it.
     */
    void annotatePreviousRevision() {
        EditorBuffer b = host.activeBuffer();
        String hash = b == null ? null : b.blameHashAtCaret();
        Revision current = revisionOf(b);
        Path root = current != null ? current.root() : git.repoRoot();
        BlameParser.BlameLine line = hash == null || hash.isBlank()
                ? null
                : blamed.getOrDefault(b, Map.of()).get(hash);
        if (line == null || root == null) {
            host.setStatus(tr(isEnabled() ? "status.git.noBlameLine" : "status.git.blameOffForPrevious"));
            return;
        }
        if (line.previousHash() == null || line.previousPath() == null) {
            host.setStatus(tr("status.git.noPreviousRevision", GitFormat.shortHash(hash)));
            return;
        }
        if (openTab == null) {
            return;
        }
        int caretLine = b.getArea().getCurrentParagraph();
        Revision previous = new Revision(root, line.previousHash(), line.previousPath());
        String sourceCharset = b.getEffectiveCharset();
        git.service().showBlob(root, previous.hash() + ":" + previous.path(), blob -> {
            if (!blob.found()) {
                host.setError(tr(
                        blob.truncated() ? "status.git.blobTooLarge" : "status.git.previousRevisionFailed",
                        previous.path(),
                        GitFormat.shortHash(previous.hash())));
                return;
            }
            openRevision(
                    previous,
                    DiffSideText.decode(blob.bytes(), null, sourceCharset).text(),
                    caretLine);
        });
    }

    /** Opens the read-only, annotated tab for {@code revision} with {@code text}, near {@code line}. */
    private void openRevision(Revision revision, String text, int line) {
        String name = revision.path().substring(revision.path().lastIndexOf('/') + 1);
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName(name); // picks the grammar from the file's own name…
        String language = buffer.getLanguage();
        buffer.setDisplayName(tr("blame.revisionTitle", name, GitFormat.shortHash(revision.hash())));
        buffer.setLanguageOverride(language); // …which the tab title, with its hash, no longer ends in
        buffer.setContent(text);
        revisions.put(buffer, revision);
        openTab.accept(buffer);
        buffer.setViewMode(true);
        buffer.jumpToLine(line); // the same line number: usually close to where the caret line was
        host.setStatus(tr("status.git.previousRevisionOpened", name, GitFormat.shortHash(revision.hash())));
        refresh(buffer);
    }

    /** Fetches blame for {@code b} off-thread and pushes formatted annotations (or clears when ineligible). */
    void refresh(EditorBuffer b) {
        if (b == null) {
            return;
        }
        Revision revision = revisions.get(b);
        Path root = revision != null ? revision.root() : git.repoRoot();
        boolean eligible = revision != null || (b.getPath() != null && !b.isLargeFile() && host.isLocalBuffer(b));
        if (!isEnabled() || !eligible || root == null) {
            b.setBlame(null);
            return;
        }
        Consumer<List<BlameParser.BlameLine>> show = lines -> {
            if (host.activeBuffer() != b) {
                return; // the user switched tabs while blame ran
            }
            Map<String, BlameParser.BlameLine> byHash = new HashMap<>();
            for (BlameParser.BlameLine line : lines) {
                byHash.putIfAbsent(line.hash(), line);
            }
            blamed.put(b, byHash);
            b.setBlame(toBlameInfos(lines));
            reportIgnoreRevsProblem();
        };
        if (revision != null) {
            git.service().blameRevisionLatest(root, revision.hash(), revision.path(), show);
        } else {
            git.service().blameLatest(root, b.getPath(), show);
        }
    }

    /** Says once why an ignore-revs file was not used (or why blame is empty because of one). */
    private void reportIgnoreRevsProblem() {
        String problem = git.service().blameIgnoreRevsProblem();
        if (!problem.isEmpty() && !problem.equals(reportedProblem)) {
            host.setError(tr("status.git.blameIgnoreRevsFailed", problem));
        }
        reportedProblem = problem;
    }

    /** Maps git blame into the per-line annotation column (author + date, full-commit tooltip, age-heatmap
     *  background). The heatmap is scaled across this file's oldest→newest committed lines and tinted for
     *  the current theme. Uncommitted lines get a label only (no heatmap, no commit to open). */
    private List<BlameInfo> toBlameInfos(List<BlameParser.BlameLine> lines) {
        long now = System.currentTimeMillis() / 1000L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (BlameParser.BlameLine bl : lines) {
            if (!bl.uncommitted()) {
                min = Math.min(min, bl.epochSeconds());
                max = Math.max(max, bl.epochSeconds());
            }
        }
        boolean dark = host.appThemeDark();
        List<BlameInfo> out = new ArrayList<>(lines.size());
        for (BlameParser.BlameLine bl : lines) {
            if (bl.uncommitted()) {
                String label = tr("blame.uncommitted");
                out.add(new BlameInfo(label, "", label, "", ""));
                continue;
            }
            String date = blameDate(bl.epochSeconds());
            String shortHash = bl.hash().substring(0, Math.min(8, bl.hash().length()));
            String tooltip = tr(
                    "blame.tooltip",
                    bl.author(),
                    date,
                    relativeTimeLabel(bl.epochSeconds(), now),
                    bl.summary(),
                    shortHash);
            double intensity = BlameHeatmap.intensity(bl.epochSeconds(), min, max);
            out.add(new BlameInfo(
                    GitFormat.shortAuthor(bl.author()),
                    date,
                    tooltip,
                    BlameHeatmap.heatmapColor(intensity, dark),
                    bl.hash()));
        }
        return out;
    }

    /** ISO {@code yyyy-MM-dd} commit date for the annotation column (technical, not localized). */
    private static String blameDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }

    /** Localized "N days ago"-style label from the pure {@link RelativeTime} bucketing. */
    static String relativeTimeLabel(long epochSeconds, long nowSeconds) {
        RelativeTime.Span span = RelativeTime.of(epochSeconds, nowSeconds);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("blame.now");
            case MINUTES -> tr("blame.minutesAgo", v);
            case HOURS -> tr("blame.hoursAgo", v);
            case DAYS -> tr("blame.daysAgo", v);
            case WEEKS -> tr("blame.weeksAgo", v);
            case MONTHS -> tr("blame.monthsAgo", v);
            case YEARS -> tr("blame.yearsAgo", v);
        };
    }
}
