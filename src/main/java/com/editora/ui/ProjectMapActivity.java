package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.git.GitRecentChanges;
import com.editora.git.GitService;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the Project Map shows of a project's activity (Map explore positions 3 and 4): how often each file and folder
 * changed in the last 90 days ("Show recent changes", read through {@link GitService#recentChanges}) and who owns it
 * ({@link ProjectMapOwners}). The Git read runs on the service's own lane; the derived figures (each depth's busiest
 * folder, the most changed folders) and the owner files are worked out on one worker thread of this class. Results
 * are applied on the FX thread and announced with {@code onChanged}. Everything else is FX-thread only.
 */
final class ProjectMapActivity {

    /** Heat steps above "no change"; a row's tint and meter grow with the step. */
    static final int LEVELS = 4;
    /**
     * How far each heat step mixes the heat ({@code -state-amber}) into a row's fill; index 0 is no change. The hottest
     * keeps the row's label at 4.5:1 or more and its muted ink at 3:1 or more in every bundled theme
     * (StyleContrastTest).
     */
    static final double[] HEAT_TINT = {0, 0.05, 0.09, 0.135, 0.18};
    /** Folders the Overview card lists as most changed. */
    static final int MOST_CHANGED = 6;

    /** A folder and its recent history, for the Overview card. */
    record Hot(String folder, GitRecentChanges.Stat stat) {}

    /** A folder ({@code ""}: the project) and who owns it, for the Overview card. */
    record Owned(String folder, ProjectMapOwners.Owners owners) {}

    /**
     * What the Overview card shows: whether the history is still being read, what was read, the most changed folders
     * (by commits to their own files), and the owners of the project and its top-level folders.
     */
    record Summary(boolean reading, GitRecentChanges.Result changes, List<Hot> mostChanged, List<Owned> owners) {
        Summary {
            mostChanged = List.copyOf(mostChanged);
            owners = List.copyOf(owners);
        }

        boolean hasHistory() {
            return reading || !changes.head().isEmpty();
        }
    }

    /**
     * What the worker derives from one read: each depth's busiest folder and the busiest file, each folder's heat step,
     * and the most changed folders.
     */
    private record Derived(
            GitRecentChanges.Result changes,
            int maxFile,
            Map<Integer, Integer> maxFolder,
            Map<String, Integer> folderLevels,
            List<Hot> hot) {}

    private final Runnable onChanged;
    private final Executor ui;
    private @Nullable ExecutorService worker;
    /** Test seam: runs work instead of the worker thread when set. */
    private @Nullable Executor workerOverride;

    private @Nullable GitService git;
    private BiConsumer<Path, Consumer<GitRecentChanges.Result>> reader = this::readWithService;
    private @Nullable Path root;
    private long generation;
    private boolean heatOn;
    private boolean overviewWanted;
    /** The Code Metrics card is open (its Changes and Hotspot columns): the history is wanted (gap 15). */
    private boolean metricsWanted;

    private boolean reading;
    private boolean readAgain;
    private @Nullable Derived derived;

    /** Read on the worker only. */
    private @Nullable ProjectMapOwners rules;

    private Function<Path, ProjectMapOwners> ownerSource = ProjectMapOwners::load;
    /** Which of a root's folders the Overview may list as most changed (Map review 4, B2 and B4); worker only. */
    private Function<Path, BiPredicate<String, List<String>>> mostChangedFilter = ProjectMapActivity::activityListable;

    private final Map<Path, ProjectMapOwners.Owners> owners = new HashMap<>();
    /** The rows whose owners were asked for, and whether each is a folder. */
    private final Map<Path, Boolean> ownersAsked = new HashMap<>();
    /** Told why "Show recent changes" shows nothing, or shows a cut window, once it is turned on (B11). */
    private Consumer<String> onExplain = message -> {};

    private boolean explainPending;
    private List<Owned> overviewOwners = List.of();
    private List<String> overviewOwnerFolders = List.of();

    ProjectMapActivity(Runnable onChanged) {
        this(onChanged, Platform::runLater);
    }

    ProjectMapActivity(Runnable onChanged, Executor ui) {
        this.onChanged = onChanged;
        this.ui = ui;
    }

    /** The window's Git service (the history is read on its activity lane); null: no history is read. */
    void setGit(@Nullable GitService service) {
        git = service;
        if (service != null && wanted()) {
            read();
        }
    }

    /** For tests: where the history comes from instead of the Git service; and where owner files come from. */
    void setSourcesForTest(
            @Nullable BiConsumer<Path, Consumer<GitRecentChanges.Result>> history,
            @Nullable Function<Path, ProjectMapOwners> ownerFiles,
            @Nullable Executor work) {
        reader = history == null ? this::readWithService : history;
        ownerSource = ownerFiles == null ? ProjectMapOwners::load : ownerFiles;
        workerOverride = work;
    }

    /** For tests: which folders of a root the Overview may list as most changed; null for the real check. */
    void setMostChangedFilterForTest(@Nullable Function<Path, BiPredicate<String, List<String>>> filter) {
        mostChangedFilter = filter == null ? ProjectMapActivity::activityListable : filter;
    }

    /** Where to say why the turned-on option shows no heat, or a cut window (the Map's status line). */
    void setOnExplain(@Nullable Consumer<String> sink) {
        onExplain = sink == null ? message -> {} : sink;
    }

    /** Forgets everything about the previous root; the history is read again when it is wanted. */
    void setRoot(@Nullable Path next) {
        generation++;
        root = ProjectMapModel.normalize(next); // as the rows' paths are (ProjectMapModel.Entry), on every platform
        derived = null;
        reading = false;
        readAgain = false;
        owners.clear();
        ownersAsked.clear();
        overviewOwners = List.of();
        overviewOwnerFolders = List.of();
        if (wanted()) {
            read();
        }
    }

    /** "Show recent changes": rows are tinted by how often they changed; turning it on reads the history. */
    void setHeat(boolean on) {
        if (heatOn == on) {
            return;
        }
        heatOn = on;
        explainPending = on;
        if (on) {
            read(); // the HEAD may have moved while it was off: one rev-parse when it did not
        }
        onChanged.run();
    }

    boolean heatOn() {
        return heatOn;
    }

    /** The Overview card is open (it lists the most changed folders and the owners): the history is wanted. */
    void setOverviewWanted(boolean wanted) {
        boolean was = overviewWanted;
        overviewWanted = wanted;
        if (wanted && !was) {
            read();
        }
    }

    /** The repository may have moved (a Git refresh): asked again, which costs a {@code rev-parse} when it did not. */
    void headMaybeMoved() {
        if (wanted() && derived != null) {
            read();
        }
    }

    /** The Code Metrics card is open (gap 15): the history is wanted, for its Changes and Hotspot columns. */
    void setMetricsWanted(boolean wanted) {
        boolean was = metricsWanted;
        metricsWanted = wanted;
        if (wanted && !was) {
            read();
        }
    }

    private boolean wanted() {
        return heatOn || overviewWanted || metricsWanted;
    }

    /** Whether the history is being read. */
    boolean reading() {
        return reading;
    }

    /** The history read for the current root, or {@link GitRecentChanges.Result#NONE} before it is. */
    GitRecentChanges.Result changes() {
        Derived known = derived;
        return known == null ? GitRecentChanges.Result.NONE : known.changes();
    }

    private void read() {
        Path base = root;
        if (base == null) {
            return;
        }
        if (reading) {
            readAgain = true;
            return;
        }
        reading = true;
        long expected = generation;
        Function<Path, BiPredicate<String, List<String>>> filter = mostChangedFilter;
        reader.accept(base, result -> {
            if (generation != expected) {
                return;
            }
            Derived known = derived;
            if (known != null && known.changes() == result) {
                finishRead(); // the same HEAD: the service answered from its cache
                return;
            }
            work(() -> {
                Derived computed = derive(result, filter.apply(base));
                ui.execute(() -> {
                    if (generation != expected) {
                        return;
                    }
                    derived = computed;
                    finishRead();
                    onChanged.run();
                });
            });
        });
    }

    private void finishRead() {
        reading = false;
        if (readAgain) {
            readAgain = false;
            read();
        }
        if (!reading && explainPending) {
            explainPending = false;
            String why = explain(changes());
            if (heatOn && why != null) {
                onExplain.accept(why);
            }
        }
    }

    /**
     * Why "Show recent changes" shows no heat for {@code changes} — no history, a failed read, a shallow clone, a quiet
     * window — or that its window was cut short; null when it shows the whole window (Map review 4, B11).
     */
    static @Nullable String explain(GitRecentChanges.Result changes) {
        if (changes.head().isEmpty()) {
            return tr("project.map.activity.noHistory");
        }
        if (!changes.problem().isEmpty()) {
            return tr("project.map.activity.failed", changes.problem());
        }
        if (changes.commits() == 0) {
            return changes.shallow() && changes.boundaryEpochSeconds() > 0
                    ? tr(
                            "project.map.activity.shallowEmpty",
                            ProjectMapOverviewCard.activityDate(changes.boundaryEpochSeconds()))
                    : tr("project.map.activity.noChanges");
        }
        return changes.truncated()
                ? tr("project.map.activity.truncated", ProjectMapOverviewCard.count(changes.commits()))
                : null;
    }

    private void readWithService(Path base, Consumer<GitRecentChanges.Result> then) {
        GitService service = git;
        if (service == null) {
            ui.execute(() -> then.accept(GitRecentChanges.Result.NONE));
            return;
        }
        service.recentChanges(base, then);
    }

    /**
     * Each depth's busiest folder and the busiest file, each folder's heat step, and the folders whose own files
     * changed most (those {@code listable} allows). A folder's step is never above its parent's (Map review 4, B5,
     * position 7): each depth is scaled against its own busiest folder, and deep, sparse levels have tiny maxima, so a
     * one-commit folder far down would otherwise show full heat beside cooler parents.
     */
    private static Derived derive(GitRecentChanges.Result changes, BiPredicate<String, List<String>> listable) {
        int maxFile = 0;
        for (GitRecentChanges.Stat stat : changes.files().values()) {
            maxFile = Math.max(maxFile, stat.commits());
        }
        Map<Integer, Integer> maxFolder = new HashMap<>();
        changes.folders().forEach((rel, stat) -> {
            if (!rel.isEmpty()) {
                maxFolder.merge(depth(rel), stat.commits(), Math::max);
            }
        });
        List<String> folders = new ArrayList<>(changes.folders().keySet());
        folders.sort(Comparator.comparingInt(ProjectMapActivity::depth));
        Map<String, Integer> levels = new HashMap<>(folders.size() * 2);
        for (String rel : folders) {
            GitRecentChanges.Stat stat = changes.folders().get(rel);
            if (!rel.isEmpty() && stat != null) {
                int own = level(stat.commits(), maxFolder.getOrDefault(depth(rel), 0));
                levels.put(rel, Math.min(own, activityParentLevel(levels, rel)));
            }
        }
        return new Derived(
                changes,
                maxFile,
                Map.copyOf(maxFolder),
                Map.copyOf(levels),
                mostChanged(changes, MOST_CHANGED, listable));
    }

    /** The heat step of {@code rel}'s parent folder in {@code levels}; the top for a top-level folder. */
    private static int activityParentLevel(Map<String, Integer> levels, String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? LEVELS : levels.getOrDefault(rel.substring(0, slash), LEVELS);
    }

    /** {@link #mostChanged(GitRecentChanges.Result, int, Predicate)} with every folder listable. Pure. */
    static List<Hot> mostChanged(GitRecentChanges.Result changes, int max) {
        return mostChanged(changes, max, (rel, files) -> true);
    }

    /**
     * The folders whose own files (not their subfolders') were changed by the most commits, the project's root files
     * and vendored or dependency folders left out (a dependency bump touches thousands of files: it is not where the
     * work is), and those {@code listable} refuses (asked with the folder and its own changed files, only for the
     * candidates, best first); ties go to the more recent. Pure but for {@code listable}.
     */
    static List<Hot> mostChanged(GitRecentChanges.Result changes, int max, BiPredicate<String, List<String>> listable) {
        List<Hot> all = new ArrayList<>();
        changes.direct().forEach((rel, stat) -> {
            if (!rel.isEmpty() && !vendored(rel)) {
                all.add(new Hot(rel, stat));
            }
        });
        all.sort(Comparator.comparingInt((Hot hot) -> hot.stat().commits())
                .reversed()
                .thenComparing(Comparator.comparingLong((Hot hot) -> hot.stat().lastEpochSeconds())
                        .reversed())
                .thenComparing(Hot::folder));
        Map<String, List<String>> own = new HashMap<>();
        if (!all.isEmpty()) {
            for (String file : changes.files().keySet()) {
                int slash = file.lastIndexOf('/');
                own.computeIfAbsent(slash < 0 ? "" : file.substring(0, slash), key -> new ArrayList<>())
                        .add(file);
            }
        }
        List<Hot> kept = new ArrayList<>(max);
        for (Hot hot : all) {
            if (kept.size() >= max) {
                break;
            }
            if (listable.test(hot.folder(), own.getOrDefault(hot.folder(), List.of()))) {
                kept.add(hot);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Which folders of {@code base} the Overview may list as most changed: those that still exist (a moved folder's
     * history went with its files, B2) and that are not dimmed — generated, vendored or ignored, by name or by the
     * root's {@code .gitattributes} and {@code .gitignore} — nor hold only such files among those that changed (B4).
     * Touches the disk: worker only.
     */
    private static BiPredicate<String, List<String>> activityListable(Path base) {
        ProjectMapIgnored ignored = ProjectMapIgnored.load(base);
        return (rel, files) -> {
            Path folder = base.resolve(rel);
            try {
                if (!java.nio.file.Files.isDirectory(folder) || ignored.classify(folder, true) != null) {
                    return false;
                }
                for (String file : files) {
                    if (ignored.classify(base.resolve(file), false) == null) {
                        return true; // one file of its own is the project's code
                    }
                }
                return files.isEmpty();
            } catch (RuntimeException unreadable) {
                return false;
            }
        };
    }

    /**
     * How hot the row at {@code path} is, 0 (no change in the window, or "Show recent changes" off) to
     * {@link #LEVELS}: its commits against the busiest file (for a file) or the busiest folder at its depth (for a
     * folder), on a log scale so one busy file does not leave every other row cold.
     */
    int heatLevel(Path path, boolean directory) {
        Derived known = derived;
        if (!heatOn || known == null) {
            return 0;
        }
        String rel = relative(root, path);
        if (rel == null || rel.isEmpty()) {
            return 0;
        }
        if (directory) {
            Integer folder = known.folderLevels().get(rel);
            if (folder != null) {
                return folder;
            }
        }
        GitRecentChanges.Stat stat = known.changes().stat(rel, directory);
        if (stat == null) {
            return 0;
        }
        if (directory) { // a submodule, which git counts as one entry (B12): a folder's scale, under its parent's step
            int own = level(stat.commits(), known.maxFolder().getOrDefault(depth(rel), stat.commits()));
            return Math.min(own, activityParentLevel(known.folderLevels(), rel));
        }
        return level(stat.commits(), known.maxFile());
    }

    /** {@code commits} against {@code max} as a step from 1 to {@link #LEVELS}; 0 for none. Pure. */
    static int level(int commits, int max) {
        if (commits <= 0 || max <= 0) {
            return 0;
        }
        double ratio = Math.log1p(commits) / Math.log1p(Math.max(commits, max));
        return Math.max(1, Math.min(LEVELS, (int) Math.ceil(ratio * LEVELS - 1e-9)));
    }

    /** The recent history of the row at {@code path} while "Show recent changes" is on; null otherwise or none. */
    GitRecentChanges.@Nullable Stat stat(Path path, boolean directory) {
        Derived known = derived;
        String rel = relative(root, path);
        if (!heatOn || known == null || rel == null || rel.isEmpty()) {
            return null;
        }
        return known.changes().stat(rel, directory);
    }

    /** Who owns the row at {@code path}, as far as known yet ({@link #classify} works it out). */
    ProjectMapOwners.@Nullable Owners owners(Path path) {
        Path key = ProjectMapModel.normalize(path);
        return key == null ? null : owners.get(key);
    }

    /** Works out, on the worker, who owns each of these rows not looked at yet. */
    void classify(Collection<ProjectMapModel.Entry> entries) {
        Path base = root;
        if (base == null) {
            return;
        }
        Map<Path, Boolean> asked = new HashMap<>();
        for (ProjectMapModel.Entry entry : entries) {
            if (entry.isPlaceholder()
                    || entry.isSymbol()
                    || ownersAsked.putIfAbsent(entry.path(), entry.directory()) != null) {
                continue;
            }
            asked.put(entry.path(), entry.directory());
        }
        if (asked.isEmpty()) {
            return;
        }
        long expected = generation;
        work(() -> {
            ProjectMapOwners loaded = rulesFor(base);
            Map<Path, ProjectMapOwners.Owners> found = new HashMap<>();
            asked.forEach((path, directory) -> {
                String rel = relative(base, path);
                ProjectMapOwners.Owners owner = rel == null ? null : loaded.owners(rel, directory);
                if (owner != null) {
                    found.put(path, owner);
                }
            });
            if (!found.isEmpty()) {
                ui.execute(() -> {
                    if (generation == expected) {
                        owners.putAll(found);
                    }
                });
            }
        });
    }

    /**
     * The Overview card's owners: the project's, then each of {@code folders}' (top-level folder names) that someone
     * else owns, worked out on the worker; {@code onChanged} runs when they are in.
     */
    void loadOverviewOwners(List<String> folders) {
        Path base = root;
        if (base == null || folders.equals(overviewOwnerFolders)) {
            return;
        }
        overviewOwnerFolders = List.copyOf(folders);
        List<String> asked = new ArrayList<>();
        asked.add("");
        asked.addAll(folders);
        long expected = generation;
        work(() -> {
            List<Owned> found = activityOwned(rulesFor(base), asked);
            ui.execute(() -> {
                if (generation == expected) {
                    overviewOwners = List.copyOf(found);
                    onChanged.run();
                }
            });
        });
    }

    /** The Overview's owner rows for {@code asked} ({@code ""} first). Worker only. */
    private static List<Owned> activityOwned(ProjectMapOwners loaded, List<String> asked) {
        List<Owned> found = new ArrayList<>();
        ProjectMapOwners.Owners project = loaded.owners("", true);
        for (String folder : asked) {
            ProjectMapOwners.Owners owner = loaded.owners(folder, true);
            // A folder owned as the project is (CODEOWNERS' "*" line) adds nothing to the project's own line.
            if (owner != null && (folder.isEmpty() || !owner.equals(project))) {
                found.add(new Owned(folder, owner));
            }
        }
        return found;
    }

    /**
     * Files in {@code directories} were written or came and went (the Project watcher, a save in Editora): when an
     * owner file there now reads differently, the owner rules are read again and every row's owners worked out anew,
     * replacing the old ones in one go (Map review 4, B9). The check runs on the worker.
     */
    void ownerFilesMayHaveChanged(Collection<Path> directories) {
        Path base = root;
        if (base == null || (ownersAsked.isEmpty() && overviewOwnerFolders.isEmpty())) {
            return; // no owners were worked out yet: the next ask reads the files as they are
        }
        Set<String> folders = new HashSet<>();
        for (Path directory : directories) {
            String rel = relative(base, directory);
            if (rel != null) {
                folders.add(rel);
            }
        }
        if (folders.isEmpty()) {
            return;
        }
        Map<Path, Boolean> asked = Map.copyOf(ownersAsked);
        List<String> overview = overviewOwnerFolders;
        long expected = generation;
        work(() -> {
            ProjectMapOwners known = rules;
            if (known == null || !base.equals(rulesRoot) || !known.changedIn(folders)) {
                return;
            }
            rules = null;
            ProjectMapOwners loaded = rulesFor(base);
            Map<Path, ProjectMapOwners.Owners> found = new HashMap<>();
            asked.forEach((path, directory) -> {
                String rel = relative(base, path);
                ProjectMapOwners.Owners owner = rel == null ? null : loaded.owners(rel, directory);
                if (owner != null) {
                    found.put(path, owner);
                }
            });
            List<String> overviewAsked = new ArrayList<>();
            overviewAsked.add("");
            overviewAsked.addAll(overview);
            List<Owned> owned = overview.isEmpty() ? List.of() : activityOwned(loaded, overviewAsked);
            ui.execute(() -> {
                if (generation != expected) {
                    return;
                }
                owners.clear();
                owners.putAll(found);
                if (!overview.isEmpty()) {
                    overviewOwners = List.copyOf(owned);
                }
                onChanged.run();
            });
        });
    }

    /** What the Overview card shows now. */
    Summary summary() {
        Derived known = derived;
        return new Summary(
                reading && known == null,
                known == null ? GitRecentChanges.Result.NONE : known.changes(),
                known == null ? List.of() : known.hot(),
                overviewOwners);
    }

    void dispose() {
        generation++;
        ExecutorService running = worker;
        if (running != null) {
            running.shutdownNow();
        }
    }

    private ProjectMapOwners rulesFor(Path base) {
        ProjectMapOwners loaded = rules;
        if (loaded == null || rulesRoot == null || !rulesRoot.equals(base)) {
            loaded = ownerSource.apply(base);
            rules = loaded;
            rulesRoot = base;
        }
        return loaded;
    }

    /** The root {@link #rules} were read for; worker only. */
    private @Nullable Path rulesRoot;

    private void work(Runnable task) {
        Executor override = workerOverride;
        if (override != null) {
            override.execute(task);
            return;
        }
        ExecutorService running = worker;
        if (running == null) {
            running = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "project-map-activity");
                thread.setDaemon(true);
                return thread;
            });
            worker = running;
        }
        try {
            running.execute(task);
        } catch (RejectedExecutionException e) {
            // disposed
        }
    }

    /**
     * A row's fill at heat step {@code level}: {@link #HEAT_TINT} of the heat mixed into {@code row}, less where a
     * theme needs it so that the row's {@code text} keeps 4.5:1 and its {@code muted} ink 3:1 (or what they had on the
     * plain row, when that was less). Pure.
     */
    static javafx.scene.paint.Color tinted(
            javafx.scene.paint.Color row,
            javafx.scene.paint.Color heat,
            int level,
            javafx.scene.paint.Color text,
            javafx.scene.paint.Color muted) {
        double textFloor = Math.min(4.5, ProjectMapView.contrastRatio(text, row));
        double mutedFloor = Math.min(3.0, ProjectMapView.contrastRatio(muted, row));
        for (double amount = HEAT_TINT[Math.max(0, Math.min(LEVELS, level))]; amount > 0.005; amount *= 0.8) {
            javafx.scene.paint.Color fill = row.interpolate(heat, amount);
            if (ProjectMapView.contrastRatio(text, fill) >= textFloor
                    && ProjectMapView.contrastRatio(muted, fill) >= mutedFloor) {
                return fill;
            }
        }
        return row;
    }

    /** The heat meter's least contrast with its row (a graphic that carries meaning: WCAG 1.4.11). */
    static final double METER_CONTRAST = 3.0;

    /**
     * The heat meter's ink on a row filled with {@code ground}: the heat, moved toward the row's {@code text} ink only
     * as far as it takes to stand {@link #METER_CONTRAST} off the fill (a pale theme amber on a light row). Pure.
     */
    static javafx.scene.paint.Color meterInk(
            javafx.scene.paint.Color heat, javafx.scene.paint.Color ground, javafx.scene.paint.Color text) {
        for (int step = 0; step <= 10; step++) {
            javafx.scene.paint.Color ink = heat.interpolate(text, step / 10.0);
            if (ProjectMapView.contrastRatio(ink, ground) >= METER_CONTRAST) {
                return ink;
            }
        }
        return text;
    }

    /** Names shown before "+N". */
    static final int NAMES_SHOWN = 4;

    /**
     * A row's owner lines: "Owners: …" with the file that says so, and the reviewers when an {@code OWNERS} file names
     * others than its approvers.
     */
    static List<String> ownerLines(ProjectMapOwners.Owners owners) {
        List<String> lines = new ArrayList<>(2);
        if (owners.approvers().isEmpty()) {
            // Reviewers are not owners: an OWNERS file naming only reviewers says so (Map review 4, B3).
            lines.add(tr("project.map.activity.reviewersFrom", names(owners.reviewers()), owners.reviewersSource()));
            return lines;
        }
        lines.add(tr("project.map.activity.owners", names(owners.approvers()), owners.source()));
        if (!owners.reviewers().isEmpty() && !owners.reviewers().equals(owners.approvers())) {
            lines.add(
                    owners.reviewersSource().equals(owners.source())
                            ? tr("project.map.activity.reviewers", names(owners.reviewers()))
                            : tr(
                                    "project.map.activity.reviewersFrom",
                                    names(owners.reviewers()),
                                    owners.reviewersSource()));
        }
        return lines;
    }

    /** The first {@link #NAMES_SHOWN} names, then how many more. */
    static String names(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(NAMES_SHOWN, names.size())));
        return names.size() <= NAMES_SHOWN
                ? shown
                : tr("project.map.activity.moreNames", shown, names.size() - NAMES_SHOWN);
    }

    /** {@code path} relative to {@code base}, {@code /}-separated; null when it is not under it. */
    static @Nullable String relative(@Nullable Path base, Path path) {
        Path at = ProjectMapModel.normalize(path); // the root is normalized the same way (setRoot)
        Path from = ProjectMapModel.normalize(base);
        if (from == null || at == null || !at.startsWith(from)) {
            return null;
        }
        StringBuilder rel = new StringBuilder();
        for (Path part : from.relativize(at)) {
            if (!rel.isEmpty()) {
                rel.append('/');
            }
            rel.append(part);
        }
        return rel.toString();
    }

    /** Whether {@code rel} is in a vendored ({@code vendor/}, {@code third_party/}) or dependency folder. */
    private static boolean vendored(String rel) {
        for (String part : rel.split("/")) {
            if (com.editora.search.DependencyDirs.NAMES.contains(part)
                    || ProjectMapIgnored.VENDOR_NAMES.contains(part.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int depth(String rel) {
        int depth = 1;
        for (int i = 0; i < rel.length(); i++) {
            if (rel.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}
