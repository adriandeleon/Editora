package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuChords;
import com.editora.editor.markdown.MarkdownLintText;
import com.editora.markdown.MarkdownLint;
import com.editora.markdown.MarkdownLint.Diagnostic;
import com.editora.markdown.MarkdownLintConfig;
import com.editora.markdown.MarkdownLintFix;
import com.editora.markdown.MarkdownLintOptions;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What can be done about a Markdown-lint diagnostic, and which rules a project turns off — the part of
 * {@link PreviewCoordinator}'s lint support that acts on issues rather than showing them:
 *
 * <ul>
 *   <li>project config discovery ({@link MarkdownLintConfig#FILE_NAMES}, nearest first, {@code extends}
 *       followed, never above the project / repository root);
 *   <li>Fix Issues (whole file, or one issue) keeping the caret and the scroll position, with counts;
 *   <li>next / previous issue, disable a rule for a line or altogether, open a rule's documentation;
 *   <li>the menu items the editor's right-click menu and the Lint tool window share.
 * </ul>
 */
final class MarkdownLintActions implements MarkdownLintPanel.IssueActions {

    /** Where every markdownlint rule is documented, by lower-case code. */
    static final String RULE_DOC_URL = "https://github.com/DavidAnson/markdownlint/blob/main/doc/%s.md";

    private static final int MAX_EXTENDS_DEPTH = 5;

    private final PreviewCoordinator.Host host;
    private final PreviewCoordinator previews;
    /** Opens a URL in the browser; a test replaces it so none is ever launched. */
    Consumer<String> openUrl;

    /**
     * A project config in effect: the file, the rules it turns off, the rules it switches on by name (those win
     * over the user's global "off"), and its rule options.
     */
    record ConfigHit(Path file, Set<String> disabled, Set<String> enabled, MarkdownLintOptions options) {}

    /**
     * A config as last read. {@code settled} says the file had been left alone for a while when it was read:
     * only then is an unchanged modification time proof of unchanged content. A file written twice within the
     * clock's (or the file system's) resolution keeps its time stamp, and so does one deleted and written again.
     */
    private record CacheEntry(
            long mtime,
            long size,
            boolean settled,
            Set<String> disabled,
            Set<String> enabled,
            MarkdownLintOptions options,
            boolean valid) {}

    /** How long a file must have been untouched for its modification time to be trusted. */
    private static final long SETTLED_MILLIS = 2_000;

    private final Map<Path, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Unreadable configs already reported ({@code path@mtime}), so each is announced once. */
    private final Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MarkdownLintActions(PreviewCoordinator.Host host, PreviewCoordinator previews) {
        this.host = host;
        this.previews = previews;
        this.openUrl = host::openExternalUrl;
    }

    // --- project config -------------------------------------------------------------------------------

    /** Which config a file's folder resolves to under a project root (null outside a project). */
    private record HitKey(Path folder, @Nullable Path projectRoot) {}

    /** What a look off the FX thread found for a folder, and when (System.nanoTime). */
    private record Looked(Optional<ConfigHit> hit, long at) {}

    /**
     * The config each folder resolved to at the last look (PERF-01, Pauta 6.0.1): the FX thread answers from
     * here and never stats or reads a config itself. A folder never looked at is looked at on
     * {@link #configExec}, and the lint it held back runs when the answer lands.
     */
    private final Map<HitKey, Looked> hits = new java.util.concurrent.ConcurrentHashMap<>();

    private final Set<HitKey> looking = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How old an answer the FX thread takes before it looks again behind it. TEST SEAM: 0 looks every time. */
    static volatile long hitFreshNanos = 2_000_000_000L;

    private static final java.util.concurrent.Executor CONFIG_LOOK =
            r -> Thread.ofVirtual().name("markdown-lint-config").start(r);

    /** Where the configs are stat-ed, read and parsed. TEST SEAM: a test may run the look in place. */
    static volatile java.util.concurrent.Executor configExec = CONFIG_LOOK;

    /** TEST SEAM — restores the look's own thread and freshness. */
    static void resetLookForTest() {
        configExec = CONFIG_LOOK;
        hitFreshNanos = 2_000_000_000L;
    }

    /** The nearest markdownlint config governing {@code file}, or null when there is none (or none known yet). */
    @Nullable
    ConfigHit configFor(Path file) {
        return knownConfigFor(file, host.activeProjectRoot(), null);
    }

    /**
     * Whether the config governing {@code file} is known (looked at off the FX thread at least once); when it is
     * not, the look is started and {@code whenKnown} runs on the FX thread once it lands.
     */
    boolean configKnown(Path file, Runnable whenKnown) {
        HitKey key = keyOf(file, host.activeProjectRoot());
        if (key == null || hits.containsKey(key)) {
            return true;
        }
        lookBehind(file, key, whenKnown);
        return hits.containsKey(key); // a look run in place (a test) has answered already
    }

    private @Nullable HitKey keyOf(Path file, @Nullable Path root) {
        Path folder = file.getParent();
        return folder == null ? null : new HitKey(folder, root != null && file.startsWith(root) ? root : null);
    }

    /** The answer kept for {@code file}'s folder; looked at again behind it when it is old. */
    private @Nullable ConfigHit knownConfigFor(Path file, @Nullable Path root, @Nullable Runnable whenKnown) {
        HitKey key = keyOf(file, root);
        if (key == null) {
            return null;
        }
        Looked known = hits.get(key);
        if (known == null || System.nanoTime() - known.at() > hitFreshNanos) {
            lookBehind(file, key, whenKnown);
            known = hits.get(key); // a look run in place (a test) has answered already
        }
        return known == null ? null : known.hit().orElse(null);
    }

    /** Looks at {@code key} on {@link #configExec}; a changed answer re-lints the open buffers. */
    private void lookBehind(Path file, HitKey key, @Nullable Runnable whenKnown) {
        if (!looking.add(key)) {
            if (whenKnown != null) {
                waiting.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(whenKnown);
            }
            return;
        }
        if (whenKnown != null) {
            waiting.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(whenKnown);
        }
        configExec.execute(() -> {
            ConfigHit found = null;
            try {
                found = configFor(file, key.projectRoot());
            } catch (RuntimeException e) {
                // unreadable: as with no config
            }
            ConfigHit hit = found;
            com.editora.perf.OffFx.onFx(() -> {
                looking.remove(key);
                Looked before = hits.put(key, new Looked(Optional.ofNullable(hit), System.nanoTime()));
                List<Runnable> held = waiting.remove(key);
                if (held != null) {
                    held.forEach(Runnable::run);
                } else if (before != null && !before.hit().equals(Optional.ofNullable(hit))) {
                    previews.applyMarkdownLint(); // the config changed on disk: lint again by the new one
                }
            });
        });
    }

    private final Map<HitKey, List<Runnable>> waiting = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * As {@link #configFor(Path)}, with the project root already in hand: the look itself, which stats, reads and
     * parses the configs. Never on the FX thread (a project lint and {@link #configExec} ask).
     */
    @Nullable
    ConfigHit configFor(Path file, @Nullable Path projectRoot) {
        com.editora.perf.BlockingSites.enter("MarkdownLintActions.configFor");
        Path stop = boundary(file, projectRoot);
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (stop == null && isHomeOrRoot(dir)) {
                break; // outside any project: a stray config in the home directory governs nothing
            }
            for (String name : MarkdownLintConfig.FILE_NAMES) {
                Path cfg = dir.resolve(name);
                if (!Files.isRegularFile(cfg)) {
                    cache.remove(cfg); // gone: whatever is written there next is read afresh
                } else {
                    CacheEntry loaded = load(cfg, 0);
                    return new ConfigHit(cfg, loaded.disabled(), loaded.enabled(), loaded.options());
                }
            }
            if (dir.equals(stop)) {
                break;
            }
        }
        return null;
    }

    /** The directory the walk-up stops at: the project root, else the Git repository root, else none. */
    private @Nullable Path boundary(Path file, @Nullable Path project) {
        if (project != null && file.startsWith(project)) {
            return project;
        }
        for (Path dir = file.getParent(); dir != null && !isHomeOrRoot(dir); dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        return null;
    }

    private static boolean isHomeOrRoot(Path dir) {
        String home = System.getProperty("user.home");
        return dir.getParent() == null || (home != null && dir.equals(Path.of(home)));
    }

    private static final CacheEntry NOTHING =
            new CacheEntry(0, 0, true, Set.of(), Set.of(), MarkdownLintOptions.DEFAULT, true);

    private CacheEntry load(Path cfg, int depth) {
        try {
            long mtime = Files.getLastModifiedTime(cfg).toMillis();
            long size = Files.size(cfg);
            CacheEntry cached = cache.get(cfg);
            if (cached == null || !cached.settled() || cached.mtime() != mtime || cached.size() != size) {
                boolean settled = System.currentTimeMillis() - mtime >= SETTLED_MILLIS;
                MarkdownLintConfig.Parsed parsed =
                        MarkdownLintConfig.parseFile(cfg.getFileName().toString(), Files.readString(cfg));
                Set<String> disabled = new HashSet<>();
                Set<String> enabled = new HashSet<>();
                MarkdownLintOptions options = MarkdownLintOptions.DEFAULT;
                if (parsed.valid() && parsed.extendsPath() != null && depth < MAX_EXTENDS_DEPTH) {
                    Path base = cfg.resolveSibling(parsed.extendsPath()).normalize();
                    if (Files.isRegularFile(base)) {
                        CacheEntry inherited = load(base, depth + 1);
                        disabled.addAll(inherited.disabled());
                        enabled.addAll(inherited.enabled());
                        options = inherited.options();
                    }
                }
                MarkdownLintConfig.apply(parsed, disabled, enabled);
                options = MarkdownLintConfig.options(parsed, options);
                cached = new CacheEntry(
                        mtime, size, settled, Set.copyOf(disabled), Set.copyOf(enabled), options, parsed.valid());
                cache.put(cfg, cached);
            }
            if (!cached.valid() && reported.add(cfg + "@" + mtime)) {
                String message = tr("status.markdownLint.configError", cfg.getFileName());
                if (javafx.application.Platform.isFxApplicationThread()) {
                    host.setStatus(message);
                } else {
                    javafx.application.Platform.runLater(() -> host.setStatus(message));
                }
            }
            return cached;
        } catch (IOException e) {
            return NOTHING;
        }
    }

    /**
     * The rule options for {@code buffer}: the project config's (markdownlint's defaults without one), with the
     * user's per-rule severities where the project has not set its own.
     */
    MarkdownLintOptions options(EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        ConfigHit hit = path != null && com.editora.vfs.Vfs.isLocal(path) ? configFor(path) : null;
        return MarkdownLintRules.withSeverities(
                host.config().getSettings(), hit == null ? MarkdownLintOptions.DEFAULT : hit.options());
    }

    /** Files larger than this are not read for their heading anchors. */
    private static final long MAX_ANCHOR_FILE_BYTES = 1_000_000;

    /**
     * What {@code buffer}'s relative links point at, resolved against the saved file's folder; null for an
     * unsaved or remote buffer (the link-target rules then say nothing). Asked on the lint thread: it reads the
     * disk, and remembers what it read for the one lint run it serves.
     */
    MarkdownLint.@Nullable LinkTargets linkTargets(EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path) || path.getParent() == null) {
            return null;
        }
        Path root = host.activeProjectRoot();
        boolean inProject = root != null && com.editora.vfs.Vfs.isLocal(root) && path.startsWith(root);
        boolean gitignore = host.config().getSettings().isSearchRespectGitignore();
        return linkTargets(path.getParent(), inProject ? () -> wikiNames(root, gitignore) : null);
    }

    /** The project's file names for wiki links; rebuilt when stale, and after its files change. */
    private volatile @Nullable WikiNoteNames wikiNames;

    private WikiNoteNames wikiNames(@Nullable Path root, boolean respectGitignore) {
        WikiNoteNames names = wikiNames;
        if (names == null || names.stale(root)) {
            names = WikiNoteNames.of(root, respectGitignore);
            wikiNames = names;
        }
        return names;
    }

    /** As {@link #linkTargets(EditorBuffer)}, for links written in a file of {@code folder}. */
    static MarkdownLint.LinkTargets linkTargets(Path folder) {
        return linkTargets(folder, null);
    }

    /**
     * As {@link #linkTargets(Path)}, resolving wiki links through {@code names} (asked on the lint thread, at
     * most once per run); without it only a note in the document's own folder can be vouched for, and a wiki
     * link that is not there is left unjudged.
     */
    static MarkdownLint.LinkTargets linkTargets(
            Path folder, java.util.function.@Nullable Supplier<WikiNoteNames> names) {
        Map<String, Boolean> exists = new HashMap<>();
        WikiNoteNames[] project = new WikiNoteNames[1];
        Map<String, @Nullable Set<String>> anchors = new HashMap<>();
        return new MarkdownLint.LinkTargets() {
            @Override
            public boolean exists(String relative) {
                return exists.computeIfAbsent(relative, r -> {
                    try {
                        return Files.exists(folder.resolve(r));
                    } catch (RuntimeException e) {
                        return true; // not a path this file system can express: not ours to judge
                    }
                });
            }

            @Override
            public @Nullable Set<String> anchors(String relative) {
                if (!anchors.containsKey(relative)) {
                    Set<String> found = null;
                    try {
                        Path file = folder.resolve(relative);
                        if (Files.isRegularFile(file) && Files.size(file) <= MAX_ANCHOR_FILE_BYTES) {
                            found = MarkdownLint.anchors(Files.readString(file));
                        }
                    } catch (IOException | RuntimeException e) {
                        found = null; // unreadable (binary, wrong charset): unknown, not "missing"
                    }
                    anchors.put(relative, found);
                }
                return anchors.get(relative);
            }

            @Override
            public @Nullable String wikiFile(String target) {
                try {
                    if (names == null) {
                        for (String candidate : new String[] {target, target + ".md", target + ".markdown"}) {
                            if (Files.isRegularFile(folder.resolve(candidate))) {
                                return candidate;
                            }
                        }
                        return ""; // no project to search: elsewhere is unknown, not missing
                    }
                    if (project[0] == null) {
                        project[0] = names.get();
                    }
                    Path file = project[0].resolve(folder, target);
                    return file == null ? null : folder.relativize(file).toString();
                } catch (RuntimeException e) {
                    return ""; // not a path this file system can express
                }
            }
        };
    }

    /**
     * What {@code buffer}'s project config does to the rule list: "2 rules disabled by .markdownlint.json", and
     * the rules it switches on although Settings has them off; "" when it changes nothing.
     */
    String configNote(EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            return "";
        }
        ConfigHit hit = configFor(path);
        if (hit == null) {
            return "";
        }
        long off = hit.disabled().stream().filter(MarkdownLint.CODES::contains).count();
        Set<String> userOff = previews.settingsDisabledRules();
        long on = hit.enabled().stream().filter(userOff::contains).count();
        Object file = hit.file().getFileName();
        if (on == 0) {
            return off == 0 ? "" : tr("markdownLint.configNote", off, file);
        }
        return off == 0
                ? tr("markdownLint.configNoteEnabled", on, file)
                : tr("markdownLint.configNoteBoth", off, on, file);
    }

    // --- fixing ---------------------------------------------------------------------------------------

    /** Fix Issues in Selection: the fixable issues on the selected lines (the caret's line without one). */
    void fixSelection() {
        EditorBuffer b = lintable();
        if (b == null) {
            return;
        }
        CodeArea area = b.getArea();
        org.fxmisc.richtext.model.TwoDimensional.Bias forward = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
        int from =
                area.offsetToPosition(area.getSelection().getStart(), forward).getMajor();
        int to = area.offsetToPosition(area.getSelection().getEnd(), forward).getMajor();
        int first = Math.min(from, to);
        int last = Math.max(from, to);
        fix((line, code) -> line >= first && line <= last);
    }

    /**
     * Whether "fix on save" applies to {@code buffer}: the setting is on and it is an editable Markdown source the
     * user is editing (not a read-only file, not a preview-only view, not a narrowed one). Memory only.
     */
    boolean fixesOnSave(EditorBuffer buffer) {
        return buffer != null
                && host.config().getSettings().isMarkdownLintFixOnSave()
                && previews.markdownLintEnabled()
                && buffer.isMarkdown()
                && buffer.isEditable()
                && !buffer.isPreviewOnly()
                && !buffer.isNarrowed();
    }

    /**
     * The save hook of "fix on save": works out the safe fixes of {@code buffer}'s text off the FX thread (PERF-01,
     * Pauta 6.0.1), applies them as one undo step when the text has not changed meanwhile, then runs {@code save}
     * on the FX thread (always, also for a buffer closed meanwhile: the caller checks). Text typed while the fixes were worked out is fixed again (a few times at most; then the
     * save goes on with the text as it is). Runs {@code save} at once when "fix on save" does not apply.
     */
    void fixThenSave(EditorBuffer buffer, Runnable save) {
        if (!fixesOnSave(buffer)) {
            save.run();
            return;
        }
        fixThen(buffer, MarkdownLintFix.Scope.ALL, FIX_TRIES, (b, worked) -> {
            if (!b.isDisposed() && worked != null && !worked.fixed().text().equals(worked.text())) {
                replaceKeepingPlace(b, worked.fixed());
            }
            save.run(); // always: the caller checks the buffer is still open
        });
    }

    /** How often a fix is worked out again when the text changed under it. */
    private static final int FIX_TRIES = 3;

    /**
     * Works out the fixes in {@code scope} of {@code b}'s text off the FX thread, with the config governing it known
     * first, and hands them to {@code then} on the FX thread while {@code b}'s text is still the one fixed; else
     * again, {@code tries} times at most, then {@code then} with null.
     */
    private void fixThen(EditorBuffer b, MarkdownLintFix.Scope scope, int tries, FixLanded then) {
        Path path = b.getPath();
        boolean[] askedAgain = {false};
        if (path != null
                && com.editora.vfs.Vfs.isLocal(path)
                && !configKnown(path, () -> {
                    askedAgain[0] = true;
                    fixThen(b, scope, tries, then);
                })) {
            return; // the rules the fixes follow are looked at first
        }
        if (askedAgain[0]) {
            return; // a look run in place (a test) has asked again already
        }
        String text = b.getContent();
        long version = b.docVersion();
        Set<String> off = previews.effectiveMarkdownLintDisabled(b);
        MarkdownLintOptions options = options(b);
        int tabSize = b.getTabSize(); // the buffer's own: .editorconfig, else the project's, else the user's
        com.editora.perf.OffFx.then(
                "MarkdownLintActions.fix",
                () -> {
                    MarkdownLintFix.Result fixed = MarkdownLintFix.fix(text, off, tabSize, scope, options);
                    List<Diagnostic> before = MarkdownLint.lint(text, off, options);
                    List<Diagnostic> after =
                            fixed.text().equals(text) ? before : MarkdownLint.lint(fixed.text(), off, options);
                    return new FixWorked(text, fixed, before, after);
                },
                answer -> {
                    if (b.isDisposed()) {
                        then.accept(b, null); // closed meanwhile: nothing to fix, nothing to save
                        return;
                    }
                    FixWorked worked = answer.value();
                    if (worked != null && b.docVersion() == version) {
                        then.accept(b, worked);
                    } else if (worked != null && tries > 1) {
                        fixThen(b, scope, tries - 1, then); // typed into meanwhile: worked out on the new text
                    } else {
                        then.accept(b, null);
                    }
                });
    }

    /** A fix worked out off the FX thread: the text it started from, the fixes, and the issues before and after. */
    private record FixWorked(
            String text, MarkdownLintFix.Result fixed, List<Diagnostic> before, List<Diagnostic> after) {}

    /** {@link #fixThen}'s answer on the FX thread: null when the text kept changing (or the work failed). */
    @FunctionalInterface
    private interface FixLanded {
        void accept(EditorBuffer buffer, @Nullable FixWorked worked);
    }

    /** Applies the safe-to-automate fixes in {@code scope} to the active buffer (one undo step). */
    void fix(MarkdownLintFix.Scope scope) {
        EditorBuffer b = lintable();
        if (b == null || refusedInPreview(b)) {
            return;
        }
        if (fixing) {
            return; // the fixes asked for are still being worked out: a second press starts nothing
        }
        fixing = true;
        fixThen(b, scope, FIX_TRIES, (buffer, worked) -> {
            fixing = false;
            if (worked == null) {
                return; // typed into every time the fixes were worked out: nothing is replaced under the typing
            }
            MarkdownLintFix.Result fixed = worked.fixed();
            if (fixed.text().equals(worked.text())) {
                int manual = worked.before().size();
                host.setStatus(
                        manual == 0
                                ? tr("status.markdownLint.fixNone")
                                : tr("status.markdownLint.fixNoneManual", manual));
                return;
            }
            if (!NoUndoGuard.allow(buffer, tr("command.markdownLint.fix"))) {
                return;
            }
            replaceKeepingPlace(buffer, fixed);
            long done = Math.max(1, fixable(worked.before()) - fixable(worked.after()));
            host.setStatus(
                    worked.after().isEmpty()
                            ? tr("status.markdownLint.fixed", done)
                            : tr(
                                    "status.markdownLint.fixedManual",
                                    done,
                                    worked.after().size()));
        });
    }

    /** A Fix command's work is out: a second press starts nothing until it lands. */
    private boolean fixing;

    private static long fixable(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> MarkdownLint.fixable(d.code())).count();
    }

    /**
     * Replaces the document with {@code result} and puts the caret and the top line back where they were
     * (through the fix's line map). A whole-document replace leaves the caret at the end of the file otherwise.
     */
    private static void replaceKeepingPlace(EditorBuffer b, MarkdownLintFix.Result result) {
        CodeArea area = b.getArea();
        boolean narrowed = b.isNarrowed(); // widened by the replace: the old positions mean nothing after it
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        int top = Math.max(0, area.firstVisibleParToAllParIndex());
        // Computed from getContent(): widens a narrowed buffer first. The span that differs is worked out off the
        // FX thread (PERF-01, Pauta 6.0.1), then the place is put back.
        b.replaceWholeDocumentLater(result.text(), applied -> {
            if (!applied || narrowed) {
                return;
            }
            int last = area.getParagraphs().size() - 1;
            int newLine = Math.max(0, Math.min(result.mapLine(line), last));
            area.moveTo(newLine, Math.min(column, area.getParagraphLength(newLine)));
            area.showParagraphAtTop(Math.max(0, Math.min(result.mapLine(top), last)));
        });
    }

    // --- at the caret ---------------------------------------------------------------------------------

    /** The diagnostic under the caret of {@code b}, else the first on the caret's line, else null. */
    static @Nullable Diagnostic atCaret(EditorBuffer b) {
        int line = b.getArea().getCurrentParagraph() + 1;
        int column = b.getArea().getCaretColumn() + 1;
        Diagnostic onLine = null;
        for (Diagnostic d : b.markdownLintDiagnostics()) {
            if (d.line() != line) {
                continue;
            }
            if (column >= d.column() && column <= d.column() + d.length()) {
                return d;
            }
            if (onLine == null) {
                onLine = d;
            }
        }
        return onLine;
    }

    /** Moves the caret to the next ({@code step} +1) or previous (-1) issue, wrapping around. */
    void go(int step) {
        EditorBuffer b = lintable();
        if (b == null) {
            return;
        }
        List<Diagnostic> all = b.markdownLintDiagnostics();
        if (all.isEmpty()) {
            host.setStatus(tr("status.markdownLint.noIssues"));
            return;
        }
        CodeArea area = b.getArea();
        int line = area.getCurrentParagraph() + 1;
        int column = area.getCaretColumn() + 1;
        int index = -1;
        if (step > 0) {
            for (int i = 0; i < all.size() && index < 0; i++) {
                Diagnostic d = all.get(i);
                if (d.line() > line || (d.line() == line && d.column() > column)) {
                    index = i;
                }
            }
            index = index < 0 ? 0 : index;
        } else {
            for (int i = all.size() - 1; i >= 0 && index < 0; i--) {
                Diagnostic d = all.get(i);
                if (d.line() < line || (d.line() == line && d.column() < column)) {
                    index = i;
                }
            }
            index = index < 0 ? all.size() - 1 : index;
        }
        Diagnostic target = all.get(index);
        int paragraph =
                Math.max(0, Math.min(target.line() - 1, area.getParagraphs().size() - 1));
        area.moveTo(paragraph, Math.max(0, Math.min(target.column() - 1, area.getParagraphLength(paragraph))));
        area.requestFollowCaret();
        host.setStatus(tr("status.markdownLint.issue", MarkdownLintText.labelled(target), index + 1, all.size()));
    }

    void fixAtCaret() {
        withIssueAtCaret(this::fix);
    }

    void disableLineAtCaret() {
        withIssueAtCaret(this::disableLine);
    }

    void disableRuleAtCaret() {
        withIssueAtCaret(this::disableRule);
    }

    void ruleHelpAtCaret() {
        withIssueAtCaret(this::help);
    }

    void disableFileAtCaret() {
        withIssueAtCaret(this::disableFile);
    }

    void disableProjectAtCaret() {
        withIssueAtCaret(this::disableProject);
    }

    private void withIssueAtCaret(Consumer<Diagnostic> action) {
        EditorBuffer b = lintable();
        if (b == null) {
            return;
        }
        Diagnostic d = atCaret(b);
        if (d == null) {
            host.setStatus(tr("status.markdownLint.noIssueHere"));
            return;
        }
        action.accept(d);
    }

    /** With only the preview showing, the source is not edited behind it (as for every editing command). */
    private boolean refusedInPreview(EditorBuffer b) {
        if (b.isPreviewOnly()) {
            host.setStatus(tr("status.previewCannotEdit"));
            return true;
        }
        return false;
    }

    /** The active buffer when it is Markdown and linting is on; otherwise says why not and returns null. */
    private @Nullable EditorBuffer lintable() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.markdownLint.notMarkdown"));
            return null;
        }
        if (!previews.markdownLintEnabled()) {
            host.setStatus(tr("status.markdownLint.off"));
            return null;
        }
        return b;
    }

    // --- one issue (the tool window's rows and the editor's right-click menu) ----------------------------

    @Override
    public void fixAll() {
        fix(MarkdownLintFix.Scope.ALL);
    }

    @Override
    public void fix(Diagnostic d) {
        if (!MarkdownLint.fixable(d.code())) {
            host.setStatus(tr("status.markdownLint.notFixable", d.code()));
            return;
        }
        fix(MarkdownLintFix.Scope.only(d));
    }

    @Override
    public void disableLine(Diagnostic d) {
        EditorBuffer b = lintable();
        if (b == null || refusedInPreview(b) || !NoUndoGuard.allow(b, tr("command.markdownLint.disableLine"))) {
            return;
        }
        // Worked out off the FX thread on a copy of the text (PERF-01, Pauta 6.0.1); applied while it is unchanged.
        String text = b.getContent();
        long version = b.docVersion();
        com.editora.perf.OffFx.then(
                "MarkdownLintActions.disableLine",
                () -> MarkdownLintFix.disableForLine(text, d.line() - 1, d.code()),
                answer -> {
                    MarkdownLintFix.Result r = answer.value();
                    if (r == null || b.isDisposed() || b.docVersion() != version) {
                        return; // typed into meanwhile: the line the issue was on may have moved
                    }
                    if (!r.text().equals(text)) {
                        replaceKeepingPlace(b, r);
                    }
                    host.setStatus(tr("status.markdownLint.lineDisabled", d.code()));
                });
    }

    @Override
    public void disableFile(Diagnostic d) {
        EditorBuffer b = lintable();
        if (b == null || refusedInPreview(b) || !NoUndoGuard.allow(b, tr("command.markdownLint.disableFile"))) {
            return;
        }
        // The document is scanned off the FX thread on a copy of its text (PERF-01, Pauta 6.0.1); applied while it
        // is unchanged.
        String text = b.getContent();
        long version = b.docVersion();
        com.editora.perf.OffFx.then(
                "MarkdownLintActions.disableFile", () -> MarkdownLintFix.disableForFile(text, d.code()), answer -> {
                    MarkdownLintFix.Result r = answer.value();
                    if (r == null || b.isDisposed() || b.docVersion() != version) {
                        return; // typed into meanwhile
                    }
                    if (!r.text().equals(text)) {
                        replaceKeepingPlace(b, r);
                    }
                    host.setStatus(tr("status.markdownLint.fileDisabled", d.code()));
                });
    }

    /**
     * Switches the rule of {@code d} off in the project's markdownlint config: an existing JSON/JSONC config is
     * edited in place (comments kept); with none, one is offered at the project root — the path is shown in a
     * prompt and nothing is created until it is accepted. A YAML or cli2 config is the user's to edit.
     */
    @Override
    public void disableProject(Diagnostic d) {
        EditorBuffer b = lintable();
        Path path = b == null ? null : b.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            if (b != null) {
                host.setStatus(tr("status.markdownLint.noProjectConfig"));
            }
            return;
        }
        Path projectRoot = host.activeProjectRoot();
        // Which config governs the file, and where a new one would go, are looked at off the FX thread.
        com.editora.perf.OffFx.then(
                "markdown-lint-disable-look",
                () -> {
                    ConfigHit hit = configFor(path, projectRoot);
                    if (hit != null) {
                        return hit.file();
                    }
                    Path root = boundary(path, projectRoot);
                    Path dir = root == null ? path.getParent() : root;
                    return dir == null ? null : new ProposedConfig(dir.resolve(".markdownlint.json"));
                },
                looked -> {
                    Object answer = looked.value();
                    if (answer instanceof Path existing) {
                        writeRuleOff(existing, d.code());
                    } else if (answer instanceof ProposedConfig(Path proposed)) {
                        offerConfig(proposed, d);
                    }
                });
    }

    /** Where a new project config would be created (the answer of the disable look when there is none). */
    private record ProposedConfig(Path path) {}

    private void offerConfig(Path proposed, Diagnostic d) {
        host.promptText(
                tr("prompt.markdownLint.createConfig.title"),
                tr("prompt.markdownLint.createConfig.label", d.code()),
                proposed.toString(),
                answer -> {
                    if (answer != null && !answer.isBlank()) {
                        writeRuleOff(Path.of(answer.strip()), d.code());
                    }
                });
    }

    private void writeRuleOff(Path config, String code) {
        String name = String.valueOf(config.getFileName());
        // Read, parsed, edited and written off the FX thread (PERF-01, Pauta 6.0.1); the lint runs again after.
        com.editora.perf.OffFx.then(
                "markdown-lint-config-write",
                () -> {
                    String before = Files.isRegularFile(config) ? Files.readString(config) : "";
                    String after = MarkdownLintConfig.withRuleOff(name, before, code);
                    if (after == null) {
                        return Boolean.FALSE;
                    }
                    if (config.getParent() != null) {
                        Files.createDirectories(config.getParent());
                    }
                    Files.writeString(config, after);
                    return Boolean.TRUE;
                },
                done -> {
                    if (!done.ok()) {
                        host.setStatus(tr("status.markdownLint.configError", config.getFileName()));
                    } else if (!done.or(false)) {
                        // No safe edit (options written as a nested mapping, a file that does not parse): say what
                        // to add.
                        host.setStatus(tr(
                                "status.markdownLint.projectConfigManual",
                                MarkdownLintConfig.manualLine(name, code),
                                name));
                    } else {
                        cache.remove(config);
                        hits.clear(); // looked at again before the next lint
                        previews.applyMarkdownLint();
                        host.setStatus(tr("status.markdownLint.projectDisabled", code, config.getFileName()));
                    }
                });
    }

    // --- the project, the status bar, the config watcher ----------------------------------------------------

    /** Bounds of a project lint: enough for a large documentation tree, never an afternoon. */
    static final int PROJECT_MAX_FILES = 2_000;

    static final int PROJECT_MAX_ISSUES = 5_000;
    private static final long PROJECT_MAX_FILE_BYTES = 1_000_000;

    private final java.util.concurrent.ExecutorService projectExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "markdown-lint-project");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicLong projectRun = new java.util.concurrent.atomic.AtomicLong();

    /** Stops a running project lint (a newer one, or going back to the active file, supersedes it). */
    void cancelProjectLint() {
        projectRun.incrementAndGet();
    }

    void shutdown() {
        projectExec.shutdownNow();
    }

    /**
     * Lint Project: every Markdown file under the project root (ignore rules honoured as Find in Files does),
     * off the FX thread, each with the config that governs it; open buffers are linted as they are in memory.
     * The result fills the tool window grouped by file.
     */
    void lintProject() {
        Path root = host.activeProjectRoot();
        if (!previews.markdownLintEnabled()) {
            host.setStatus(tr("status.markdownLint.off"));
            return;
        }
        if (root == null || !com.editora.vfs.Vfs.isLocal(root)) {
            host.setStatus(tr("status.markdownLint.noProject"));
            return;
        }
        // The panel is put in project mode before its window opens: opening it refreshes the active file's
        // list otherwise, and that refresh cancels the project run it was opened for.
        MarkdownLintPanel panel = host.markdownLintPanel();
        panel.showProjectScanning(String.valueOf(root.getFileName()));
        if (!host.toolWindows().isOpen(host.markdownLintToolWindow())) {
            host.toolWindows().setAvailable(host.markdownLintToolWindow(), true);
            host.toolWindows().open(host.markdownLintToolWindow());
        }
        Map<Path, String> open = new HashMap<>();
        for (javafx.scene.control.Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            if (b != null && b.isMarkdown() && b.getPath() != null) {
                open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
            }
        }
        Set<String> userOff = previews.settingsDisabledRules();
        com.editora.config.Settings settings = host.config().getSettings();
        if (!settings.isMarkdownWikiLinks()) {
            userOff.add(MarkdownLint.LINK_WIKI);
        }
        boolean gitignore = settings.isSearchRespectGitignore();
        long mine = projectRun.incrementAndGet();
        projectExec.submit(() -> {
            ProjectResult result = lintTree(root, open, userOff, settings, gitignore, () -> projectRun.get() != mine);
            javafx.application.Platform.runLater(() -> {
                if (projectRun.get() == mine) {
                    panel.setProjectResults(String.valueOf(root.getFileName()), result.files(), result.truncated());
                }
            });
        });
    }

    /** What a project lint found. */
    record ProjectResult(List<MarkdownLintPanel.FileIssues> files, boolean truncated) {}

    /** The worker of {@link #lintProject} (no toolkit: callable from a test). */
    ProjectResult lintTree(
            Path root,
            Map<Path, String> open,
            Set<String> userOff,
            com.editora.config.Settings settings,
            boolean respectGitignore,
            java.util.function.BooleanSupplier cancelled) {
        List<Path> candidates = new java.util.ArrayList<>();
        com.editora.search.GitignoreFilter filter = respectGitignore
                ? com.editora.search.GitignoreFilter.load(root)
                : com.editora.search.GitignoreFilter.NONE;
        com.editora.search.ProjectWalk.Outcome walked = com.editora.search.ProjectWalk.walk(
                root,
                new com.editora.search.ProjectWalk.Options(25, PROJECT_MAX_FILES, filter, cancelled),
                (file, rel, attrs) -> {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!attrs.isRegularFile()
                            || attrs.size() > PROJECT_MAX_FILE_BYTES
                            || !(name.endsWith(".md") || name.endsWith(".markdown"))) {
                        return com.editora.search.ProjectWalk.Verdict.SKIP;
                    }
                    candidates.add(file);
                    return com.editora.search.ProjectWalk.Verdict.ACCEPT;
                });
        candidates.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));
        WikiNoteNames[] names = {WikiNoteNames.of(root, respectGitignore)};
        List<MarkdownLintPanel.FileIssues> files = new java.util.ArrayList<>();
        boolean truncated = walked.truncated();
        int total = 0;
        for (Path file : candidates) {
            if (cancelled.getAsBoolean()) {
                return new ProjectResult(List.of(), false);
            }
            String text = open.get(file.toAbsolutePath().normalize());
            try {
                text = text == null ? Files.readString(file) : text;
            } catch (IOException | RuntimeException e) {
                continue; // unreadable or not UTF-8: not a document we can judge
            }
            ConfigHit hit = configFor(file, root);
            Set<String> off = new HashSet<>(userOff);
            if (hit != null) {
                off.removeAll(hit.enabled());
                off.addAll(hit.disabled());
            }
            MarkdownLintOptions options = MarkdownLintRules.withSeverities(
                    settings, hit == null ? MarkdownLintOptions.DEFAULT : hit.options());
            List<Diagnostic> found = MarkdownLint.lint(
                    text,
                    off,
                    options,
                    file.getParent() == null ? null : linkTargets(file.getParent(), () -> names[0]));
            if (found.isEmpty()) {
                continue;
            }
            if (total + found.size() > PROJECT_MAX_ISSUES) {
                found = found.subList(0, PROJECT_MAX_ISSUES - total);
                truncated = true;
            }
            total += found.size();
            files.add(
                    new MarkdownLintPanel.FileIssues(file, root.relativize(file).toString(), List.copyOf(found)));
            if (total >= PROJECT_MAX_ISSUES) {
                break;
            }
        }
        return new ProjectResult(files, truncated);
    }

    /** Shows {@code buffer}'s issue count in the status bar (hidden for anything that is not linted). */
    void updateStatusBar(@Nullable EditorBuffer buffer) {
        StatusBar bar = host.statusBar();
        if (bar == null) {
            return;
        }
        if (buffer == null || !buffer.isMarkdown() || !previews.markdownLintEnabled()) {
            bar.setMarkdownLint(-1, 0, 0);
            return;
        }
        int errors = 0;
        int warnings = 0;
        int hints = 0;
        for (Diagnostic d : buffer.markdownLintDiagnostics()) {
            if (d.isError()) {
                errors++;
            } else if (d.isHint()) {
                hints++;
            } else {
                warnings++;
            }
        }
        bar.setMarkdownLint(errors, warnings, hints);
    }

    /** How often, at most, {@link #checkConfigs} looks at the disk. */
    long configCheckIntervalNanos = 2_000_000_000L;

    private long lastConfigCheck;
    /** The configs the open buffers resolved to, with their modification times, at the last look. */
    private @Nullable String configSignature;

    private static final int MAX_BUFFERS_CHECKED = 50;

    /**
     * Re-lints the open buffers when a markdownlint config one of them resolved to has changed, appeared or
     * gone — wherever it lives, inside an open project or not. Called when the app looks for files changed on
     * disk (the window regaining focus, a tab switch): outside a project nothing watches the disk, and that is
     * the moment an edit made in another program is noticed. Bounded: at most every two seconds, the first
     * fifty Markdown buffers, one look per folder.
     */
    void checkConfigs() {
        long now = System.nanoTime();
        if (!previews.markdownLintEnabled()
                || (lastConfigCheck != 0 && now - lastConfigCheck < configCheckIntervalNanos)) {
            return;
        }
        lastConfigCheck = now;
        Path root = host.activeProjectRoot();
        List<Path> paths = new java.util.ArrayList<>();
        Set<Path> folders = new HashSet<>();
        for (javafx.scene.control.Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            Path path = b == null || !b.isMarkdown() ? null : b.getPath();
            if (path == null || path.getParent() == null || !com.editora.vfs.Vfs.isLocal(path)) {
                continue;
            }
            if (paths.size() >= MAX_BUFFERS_CHECKED) {
                break;
            }
            if (folders.add(path.getParent())) {
                paths.add(path);
            }
        }
        // The stats, reads and parses happen on configExec (PERF-01, Pauta 6.0.1); the answer is compared on FX.
        configExec.execute(() -> {
            Map<HitKey, Optional<ConfigHit>> found = new HashMap<>();
            StringBuilder signature = new StringBuilder();
            for (Path path : paths) {
                ConfigHit hit;
                try {
                    hit = configFor(path, root);
                } catch (RuntimeException e) {
                    hit = null;
                }
                HitKey key = keyOf(path, root);
                if (key != null) {
                    found.put(key, Optional.ofNullable(hit));
                }
                CacheEntry entry = hit == null ? null : cache.get(hit.file());
                signature.append(
                        hit == null
                                ? "-;"
                                : hit.file() + "@" + (entry == null ? 0 : entry.mtime()) + "/" + hit.disabled()
                                        + hit.enabled() + ";");
            }
            String seen = signature.toString();
            com.editora.perf.OffFx.onFx(() -> {
                long at = System.nanoTime();
                found.forEach((key, hit) -> hits.put(key, new Looked(hit, at)));
                boolean changed = configSignature != null && !configSignature.equals(seen);
                configSignature = seen;
                if (changed) {
                    previews.applyMarkdownLint();
                }
            });
        });
    }

    /** The project's file watcher saw changes: a touched markdownlint config re-lints the open buffers. */
    void onExternalChanges(List<ProjectPanel.FsChange> changes) {
        for (ProjectPanel.FsChange change : changes) {
            if (change.kind() != ProjectPanel.FsKind.CHANGED) {
                wikiNames = null; // a file came or went: the names wiki links resolve against are out of date
            }
            Path name = change.path() == null ? null : change.path().getFileName();
            if (name != null && name.toString().startsWith(".markdownlint")) {
                cache.remove(change.path());
                hits.clear(); // looked at again before the next lint
                previews.applyMarkdownLint();
                return;
            }
        }
    }

    @Override
    public void disableRule(Diagnostic d) {
        if (!previews.markdownLintRuleDisabled(d.code())) {
            previews.toggleMarkdownLintRule(d.code());
        }
    }

    @Override
    public void help(Diagnostic d) {
        openUrl.accept(String.format(RULE_DOC_URL, d.code().toLowerCase(Locale.ROOT)));
    }

    /** The actions on {@code d} as menu items (shared by the editor's menu and the tool window's rows). */
    List<MenuItem> menuItems(Diagnostic d) {
        return menuItems(d, false);
    }

    /**
     * {@link #menuItems(Diagnostic)}; {@code atCaret}: {@code d} is the issue at the editor's caret, so each row is
     * that issue's "at Caret" command and shows its chord.
     */
    private List<MenuItem> menuItems(Diagnostic d, boolean atCaret) {
        MenuItem fix = new MenuItem(tr("markdownLint.menu.fix"));
        com.editora.editor.RowReasons.when(fix, MarkdownLint.fixable(d.code()), "row.unavailable.notFixable");
        fix.setOnAction(e -> fix(d));
        MenuItem line = new MenuItem(tr("markdownLint.menu.disableLine"));
        line.setOnAction(e -> disableLine(d));
        MenuItem file = new MenuItem(tr("markdownLint.menu.disableFile", d.code()));
        file.setOnAction(e -> disableFile(d));
        MenuItem project = new MenuItem(tr("markdownLint.menu.disableProject", d.code()));
        project.setOnAction(e -> disableProject(d));
        MenuItem rule = new MenuItem(tr("markdownLint.menu.disableRule", d.code()));
        rule.setOnAction(e -> disableRule(d));
        MenuItem help = new MenuItem(tr("markdownLint.menu.help"));
        help.setOnAction(e -> help(d));
        if (atCaret) {
            MenuChords.tag(fix, "markdownLint.fixAtCaret");
            MenuChords.tag(line, "markdownLint.disableLine");
            MenuChords.tag(file, "markdownLint.disableFile");
            MenuChords.tag(project, "markdownLint.disableProject");
            MenuChords.tag(rule, "markdownLint.disableRule");
            MenuChords.tag(help, "markdownLint.ruleHelp");
        }
        return List.of(fix, line, file, project, rule, help);
    }

    /** The "Markdown Lint: MD034" submenu for the issue at {@code buffer}'s caret; empty when there is none. */
    List<MenuItem> editorMenu(EditorBuffer buffer) {
        if (buffer == null || !buffer.isMarkdown()) {
            return List.of();
        }
        Diagnostic d = atCaret(buffer);
        if (d == null) {
            return List.of();
        }
        Menu menu = new Menu(tr("markdownLint.menu.title", MarkdownLintText.labelled(d)));
        menu.setGraphic(Icons.warning());
        menu.getItems().setAll(menuItems(d, true)); // d is the issue at the caret
        return List.of(menu);
    }
}
