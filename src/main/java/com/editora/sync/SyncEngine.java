package com.editora.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.editora.git.GitOutcome;
import com.editora.git.GitSafety;
import com.editora.git.QuietGit;
import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One settings sync between this machine's config directory and the user's repository. Blocking and free of
 * JavaFX; the caller picks the thread.
 *
 * <p>Git only carries the data. Editora keeps a clone of its own ({@code sync/repo} in the config directory)
 * that nothing else reads, and never asks git to merge: the working tree is set to what the remote has, the
 * merged files ({@link SyncMerge}) are written over it, and that is committed and pushed. So there are no
 * conflict markers and no half-finished rebase to recover from.
 *
 * <p>The three sides of the merge are the live files (mine), the remote branch (theirs) and
 * {@value #BASE_REF} — the commit this machine last finished a sync at (base). The base is moved only after
 * the push succeeded and the live files were written, so a sync interrupted anywhere is simply repeated by
 * the next one: until then this machine's edits still differ from the base and are sent again.
 */
public final class SyncEngine {

    /** The repository layout this build reads and writes ({@code formatVersion} in {@value #FORMAT_FILE}). */
    public static final int FORMAT_VERSION = 1;

    static final String FORMAT_FILE = "editora-sync.json";
    static final String BASE_REF = "refs/editora/base";

    /** A category at least this large is guarded against losing more than half of its entries in one sync. */
    static final int GUARDED_SIZE = 10;

    private static final int ATTEMPTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuietGit git;
    private final Path clone;
    private final String url;
    private final String branch;
    private final SyncTarget target;
    private final String machine;

    /**
     * @param git a runner whose directory is {@code clone}
     * @param machine this computer's name, for the commit message
     */
    public SyncEngine(QuietGit git, String url, String branch, SyncTarget target, String machine) {
        this.git = git;
        this.clone = git.dir();
        this.url = url == null ? "" : url.strip();
        this.branch = branch == null || branch.isBlank() ? "main" : branch.strip();
        this.target = target;
        this.machine = machine == null || machine.isBlank() ? "this computer" : machine.strip();
    }

    /** Whether {@code url} can be handed to git as a remote: not blank, and not something git reads as an option. */
    public static boolean isUsableUrl(String url) {
        return url != null && !url.isBlank() && !url.strip().startsWith("-") && GitSafety.isSafeRevision(url.strip());
    }

    /** Whether {@code branch} is a plain branch name. */
    public static boolean isUsableBranch(String branch) {
        return branch != null
                && GitSafety.isSafeRevision(branch)
                && branch.matches("[A-Za-z0-9._/-]+")
                && !branch.contains("..")
                && !branch.startsWith("/")
                && !branch.endsWith("/")
                && !branch.endsWith(".lock");
    }

    /**
     * Syncs {@code categories}.
     *
     * @param allowLargeRemoval run even when the merge removes most of a category
     *     ({@link SyncReport.Status#NEEDS_CONFIRMATION} otherwise)
     */
    public SyncReport run(Set<SyncCategory> categories, boolean allowLargeRemoval) {
        return run(categories, allowLargeRemoval, false);
    }

    /**
     * What a sync of {@code categories} would do, without writing a file here or pushing anything: the
     * repository is fetched and merged in memory only. For showing the user what connecting a computer that
     * already has data to a repository that already has data will bring.
     */
    public SyncReport preview(Set<SyncCategory> categories) {
        return run(categories, true, true);
    }

    private SyncReport run(Set<SyncCategory> categories, boolean allowLargeRemoval, boolean previewOnly) {
        if (!isUsableUrl(url) || !isUsableBranch(branch)) {
            return SyncReport.failure(SyncReport.Status.FAILED, "invalid repository URL or branch");
        }
        if (!QuietGit.available()) {
            return SyncReport.failure(SyncReport.Status.NO_GIT, "");
        }
        Set<SyncCategory> enabled =
                categories.isEmpty() ? EnumSet.noneOf(SyncCategory.class) : EnumSet.copyOf(categories);
        List<SyncReport.Change> received = new ArrayList<>();
        try {
            prepareClone();
            for (int attempt = 1; ; attempt++) {
                SyncReport report = cycle(enabled, allowLargeRemoval, previewOnly, received);
                if (report != null) {
                    return report;
                }
                if (attempt == ATTEMPTS) {
                    return SyncReport.failure(SyncReport.Status.PUSH_FAILED, "the repository kept changing");
                }
            }
        } catch (GitFailure e) {
            return SyncReport.failure(SyncReport.Status.FAILED, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return SyncReport.failure(SyncReport.Status.FAILED, String.valueOf(e.getMessage()));
        }
    }

    /** One fetch-merge-push round; null when the push lost a race and the round must be repeated. */
    private SyncReport cycle(
            Set<SyncCategory> enabled, boolean allowLargeRemoval, boolean previewOnly, List<SyncReport.Change> received)
            throws IOException, GitFailure {
        ProcessRunner.Result fetch = git.network("fetch", "--prune", "--quiet", "origin");
        if (!fetch.ok()) {
            return SyncReport.failure(SyncReport.Status.FETCH_FAILED, GitOutcome.transcript(fetch));
        }
        String remoteRef = "refs/remotes/origin/" + branch;
        boolean remoteHas = exists(remoteRef);
        Map<String, String> theirs;
        if (remoteHas) {
            must(git.run("checkout", "--quiet", "--force", "-B", branch, remoteRef));
            must(git.run("clean", "-fdq"));
            if (formatVersion(clone.resolve(FORMAT_FILE)) > FORMAT_VERSION) {
                return SyncReport.failure(SyncReport.Status.NEWER_FORMAT, "");
            }
            theirs = FileSyncTarget.readTree(clone, enabled);
        } else {
            // An empty repository (or a new branch). Whatever an earlier, unpushed attempt left in the clone
            // is not the remote's: start from nothing.
            must(git.run("symbolic-ref", "HEAD", "refs/heads/" + branch));
            if (exists("HEAD")) {
                must(git.run("update-ref", "-d", "refs/heads/" + branch));
            }
            must(git.run("read-tree", "--empty"));
            must(git.run("clean", "-fdxq"));
            theirs = Map.of();
        }
        Map<String, String> base = baseFiles(enabled, remoteHas ? remoteRef : null, theirs);
        Map<String, String> mine = target.read(enabled);

        Set<String> paths = new TreeSet<>(mine.keySet());
        paths.addAll(theirs.keySet());
        paths.addAll(base.keySet());
        List<SyncMerge.FileResult> results = new ArrayList<>();
        for (String path : paths) {
            SyncCategory category = SyncCategory.of(path);
            if (category != null && enabled.contains(category)) {
                results.add(SyncMerge.merge(category, path, base.get(path), mine.get(path), theirs.get(path)));
            }
        }

        if (!allowLargeRemoval) {
            SyncCategory emptied = largeRemoval(results);
            if (emptied != null) {
                return SyncReport.failure(SyncReport.Status.NEEDS_CONFIRMATION, emptied.name());
            }
        }

        List<SyncReport.Change> sent = new ArrayList<>();
        List<SyncReport.Change> conflicts = new ArrayList<>();
        List<SyncReport.Skipped> skipped = new ArrayList<>();
        Map<String, String> liveChanges = new LinkedHashMap<>();
        for (SyncMerge.FileResult r : results) {
            SyncCategory category = SyncCategory.of(r.path());
            if (r.skipped()) {
                skipped.add(new SyncReport.Skipped(r.path(), r.skip()));
                continue;
            }
            r.received().forEach(label -> received.add(new SyncReport.Change(category, label)));
            r.sent().forEach(label -> sent.add(new SyncReport.Change(category, label)));
            r.conflicts().forEach(label -> conflicts.add(new SyncReport.Change(category, label)));
            if (!Objects.equals(r.text(), SyncMerge.normalize(category, mine.get(r.path())))) {
                liveChanges.put(r.path(), r.text());
            }
        }
        if (previewOnly) {
            return new SyncReport(SyncReport.Status.OK, List.copyOf(received), sent, conflicts, skipped, "");
        }
        if (!liveChanges.isEmpty() && !target.apply(mine, liveChanges)) {
            return SyncReport.failure(SyncReport.Status.LOCAL_BUSY, "");
        }

        for (SyncMerge.FileResult r : results) {
            if (!r.skipped()) {
                FileSyncTarget.writeOrDelete(clone.resolve(r.path()), r.text());
            }
        }
        writeIfAbsent(FORMAT_FILE, "{\n  \"formatVersion\" : " + FORMAT_VERSION + "\n}\n");
        writeIfAbsent(".gitattributes", "* -text\n");
        writeIfAbsent("README.md", README);
        must(git.run("add", "--all"));
        if (!must(git.run("status", "--porcelain")).out().isBlank()) {
            List<String> commit = new ArrayList<>();
            if (!git.run("config", "--get", "user.email").ok()) {
                commit.addAll(List.of("-c", "user.name=Editora", "-c", "user.email=editora@localhost"));
            }
            commit.addAll(List.of("commit", "--quiet", "--no-verify", "-m", "Sync from " + machine));
            must(git.run(commit.toArray(String[]::new)));
        }
        if (!remoteHas || !rev("HEAD").equals(rev(remoteRef))) {
            ProcessRunner.Result push = git.network("push", "origin", "HEAD:refs/heads/" + branch);
            if (GitOutcome.of(push) == GitOutcome.NON_FAST_FORWARD) {
                return null; // another machine pushed first; its commit is merged on the next round
            }
            if (!push.ok()) {
                // The live files already hold the merge; the base stays, so the next sync sends again.
                return new SyncReport(
                        SyncReport.Status.PUSH_FAILED,
                        List.copyOf(received),
                        List.of(),
                        conflicts,
                        skipped,
                        GitOutcome.transcript(push));
            }
        }
        must(git.run("update-ref", BASE_REF, "HEAD"));
        return new SyncReport(SyncReport.Status.OK, List.copyOf(received), sent, conflicts, skipped, "");
    }

    /** Creates the clone on first use, and points it at {@link #url}; a different repository forgets the base. */
    private void prepareClone() throws IOException, GitFailure {
        Files.createDirectories(clone);
        if (!Files.exists(clone.resolve(".git"))) {
            must(git.run("init", "--quiet"));
        }
        // The URL as it was stored: "remote get-url" would apply the user's url.<base>.insteadOf rewriting, and
        // a rewritten URL never equals the configured one.
        ProcessRunner.Result current = git.run("config", "--local", "--get", "remote.origin.url");
        if (!current.ok()) {
            must(git.run("remote", "add", "origin", url));
        } else if (!current.out().strip().equals(url)) {
            // What was synced with the old repository says nothing about this one: start as a first sync
            // (everything on both sides is kept).
            must(git.run("remote", "set-url", "origin", url));
            if (exists(BASE_REF)) {
                must(git.run("update-ref", "-d", BASE_REF));
            }
            git.run("config", "--local", "--unset", SyncAccount.KEY); // nor does the account that could read it
        }
    }

    /** The files of the base commit; empty when this machine has not finished a sync with this repository. */
    private Map<String, String> baseFiles(Set<SyncCategory> enabled, String remoteRef, Map<String, String> theirs)
            throws GitFailure {
        if (!exists(BASE_REF)) {
            return Map.of();
        }
        Map<String, String> baseBlobs = blobs(BASE_REF);
        Map<String, String> theirBlobs = remoteRef == null ? Map.of() : blobs(remoteRef);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> blob : baseBlobs.entrySet()) {
            String path = blob.getKey();
            SyncCategory category = SyncCategory.of(path);
            if (category == null || !enabled.contains(category)) {
                continue;
            }
            if (blob.getValue().equals(theirBlobs.get(path)) && theirs.containsKey(path)) {
                out.put(path, theirs.get(path)); // unchanged on the remote since: the working tree has it
            } else {
                out.put(path, must(git.run("show", BASE_REF + ":" + path)).out());
            }
        }
        return out;
    }

    /** Path to blob id for every file of {@code ref}. */
    private Map<String, String> blobs(String ref) throws GitFailure {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : must(git.run("ls-tree", "-r", "-z", ref)).out().split("\0")) {
            int tab = line.indexOf('\t');
            String[] meta = tab < 0 ? new String[0] : line.substring(0, tab).split(" ");
            if (meta.length == 3 && "blob".equals(meta[1])) {
                out.put(line.substring(tab + 1), meta[2]);
            }
        }
        return out;
    }

    /**
     * The category that would lose more than half of what either side has, or null. A sync of two healthy
     * machines never does that; an emptied or damaged file on one of them does, and would otherwise be
     * carried to every other machine.
     */
    static SyncCategory largeRemoval(List<SyncMerge.FileResult> results) {
        Map<SyncCategory, int[]> counts = new EnumMap<>(SyncCategory.class);
        for (SyncMerge.FileResult r : results) {
            if (r.skipped()) {
                continue;
            }
            int[] c = counts.computeIfAbsent(SyncCategory.of(r.path()), k -> new int[3]);
            c[0] += r.mineEntries();
            c[1] += r.theirEntries();
            c[2] += r.resultEntries();
        }
        for (Map.Entry<SyncCategory, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            if ((c[0] >= GUARDED_SIZE && c[2] * 2 < c[0]) || (c[1] >= GUARDED_SIZE && c[2] * 2 < c[1])) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The format version a repository declares; 0 when it declares none (or the file is not readable JSON). */
    static int formatVersion(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            JsonNode tree = MAPPER.readTree(Files.readAllBytes(file));
            return tree == null ? 0 : tree.path("formatVersion").asInt(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private void writeIfAbsent(String name, String text) throws IOException {
        Path file = clone.resolve(name);
        if (!Files.exists(file)) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
    }

    private boolean exists(String ref) {
        return git.run("rev-parse", "--verify", "--quiet", ref + "^{commit}").ok();
    }

    private String rev(String ref) throws GitFailure {
        return must(git.run("rev-parse", "--verify", ref + "^{commit}")).out().strip();
    }

    private static ProcessRunner.Result must(ProcessRunner.Result result) throws GitFailure {
        if (!result.ok()) {
            throw new GitFailure(GitOutcome.transcript(result));
        }
        return result;
    }

    private static final class GitFailure extends Exception {
        private static final long serialVersionUID = 1L;

        GitFailure(String message) {
            super(message);
        }
    }

    private static final String README = """
            # Editora settings sync

            This repository is written by the Editora text editor's settings sync. It holds the
            snippets, abbreviations, templates and personal dictionary words of the computers connected to it.

            Editing a file here by hand is fine: every connected computer picks the change up on its next sync.
            Keep this repository private - snippets and templates can contain private text.
            """;
}
