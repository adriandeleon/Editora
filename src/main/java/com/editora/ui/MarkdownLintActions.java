package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MarkdownLintText;
import com.editora.markdown.MarkdownLint;
import com.editora.markdown.MarkdownLint.Diagnostic;
import com.editora.markdown.MarkdownLintConfig;
import com.editora.markdown.MarkdownLintFix;
import com.editora.markdown.MarkdownLintOptions;
import org.fxmisc.richtext.CodeArea;

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

    private record CacheEntry(
            long mtime, Set<String> disabled, Set<String> enabled, MarkdownLintOptions options, boolean valid) {}

    private final Map<Path, CacheEntry> cache = new HashMap<>();
    /** Unreadable configs already reported ({@code path@mtime}), so each is announced once. */
    private final Set<String> reported = new HashSet<>();

    MarkdownLintActions(PreviewCoordinator.Host host, PreviewCoordinator previews) {
        this.host = host;
        this.previews = previews;
        this.openUrl = host::openExternalUrl;
    }

    // --- project config -------------------------------------------------------------------------------

    /** The nearest markdownlint config governing {@code file}, or null when there is none. */
    ConfigHit configFor(Path file) {
        Path stop = boundary(file);
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (stop == null && isHomeOrRoot(dir)) {
                break; // outside any project: a stray config in the home directory governs nothing
            }
            for (String name : MarkdownLintConfig.FILE_NAMES) {
                Path cfg = dir.resolve(name);
                if (Files.isRegularFile(cfg)) {
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
    private Path boundary(Path file) {
        Path project = host.activeProjectRoot();
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

    private static final CacheEntry NOTHING = new CacheEntry(0, Set.of(), Set.of(), MarkdownLintOptions.DEFAULT, true);

    private CacheEntry load(Path cfg, int depth) {
        try {
            long mtime = Files.getLastModifiedTime(cfg).toMillis();
            CacheEntry cached = cache.get(cfg);
            if (cached == null || cached.mtime() != mtime) {
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
                cached = new CacheEntry(mtime, Set.copyOf(disabled), Set.copyOf(enabled), options, parsed.valid());
                cache.put(cfg, cached);
            }
            if (!cached.valid() && reported.add(cfg + "@" + mtime)) {
                host.setStatus(tr("status.markdownLint.configError", cfg.getFileName()));
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
    MarkdownLint.LinkTargets linkTargets(EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path) || path.getParent() == null) {
            return null;
        }
        return linkTargets(path.getParent());
    }

    /** As {@link #linkTargets(EditorBuffer)}, for links written in a file of {@code folder}. */
    static MarkdownLint.LinkTargets linkTargets(Path folder) {
        Map<String, Boolean> exists = new HashMap<>();
        Map<String, Set<String>> anchors = new HashMap<>();
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
            public Set<String> anchors(String relative) {
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

    /** Applies the safe-to-automate fixes in {@code scope} to the active buffer (one undo step). */
    void fix(MarkdownLintFix.Scope scope) {
        EditorBuffer b = lintable();
        if (b == null || refusedInPreview(b)) {
            return;
        }
        String text = b.getContent();
        Set<String> off = previews.effectiveMarkdownLintDisabled(b);
        MarkdownLintOptions options = options(b);
        MarkdownLintFix.Result fixed =
                MarkdownLintFix.fix(text, off, host.config().getSettings().getTabSize(), scope, options);
        if (fixed.text().equals(text)) {
            int manual = MarkdownLint.lint(text, off, options).size();
            host.setStatus(
                    manual == 0 ? tr("status.markdownLint.fixNone") : tr("status.markdownLint.fixNoneManual", manual));
            return;
        }
        if (!NoUndoGuard.allow(b, tr("command.markdownLint.fix"))) {
            return;
        }
        replaceKeepingPlace(b, fixed);
        List<Diagnostic> before = MarkdownLint.lint(text, off, options);
        List<Diagnostic> after = MarkdownLint.lint(fixed.text(), off, options);
        long done = Math.max(1, fixable(before) - fixable(after));
        host.setStatus(
                after.isEmpty()
                        ? tr("status.markdownLint.fixed", done)
                        : tr("status.markdownLint.fixedManual", done, after.size()));
    }

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
        b.replaceWholeDocument(result.text()); // computed from getContent(): widens a narrowed buffer first
        if (narrowed) {
            return;
        }
        int last = area.getParagraphs().size() - 1;
        int newLine = Math.max(0, Math.min(result.mapLine(line), last));
        area.moveTo(newLine, Math.min(column, area.getParagraphLength(newLine)));
        area.showParagraphAtTop(Math.max(0, Math.min(result.mapLine(top), last)));
    }

    // --- at the caret ---------------------------------------------------------------------------------

    /** The diagnostic under the caret of {@code b}, else the first on the caret's line, else null. */
    static Diagnostic atCaret(EditorBuffer b) {
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
    private EditorBuffer lintable() {
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
        MarkdownLintFix.Result r = MarkdownLintFix.disableForLine(b.getContent(), d.line() - 1, d.code());
        if (!r.text().equals(b.getContent())) {
            replaceKeepingPlace(b, r);
        }
        host.setStatus(tr("status.markdownLint.lineDisabled", d.code()));
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
        MenuItem fix = new MenuItem(tr("markdownLint.menu.fix"));
        fix.setDisable(!MarkdownLint.fixable(d.code()));
        fix.setOnAction(e -> fix(d));
        MenuItem line = new MenuItem(tr("markdownLint.menu.disableLine"));
        line.setOnAction(e -> disableLine(d));
        MenuItem rule = new MenuItem(tr("markdownLint.menu.disableRule", d.code()));
        rule.setOnAction(e -> disableRule(d));
        MenuItem help = new MenuItem(tr("markdownLint.menu.help"));
        help.setOnAction(e -> help(d));
        return List.of(fix, line, rule, help);
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
        menu.getItems().setAll(menuItems(d));
        return List.of(menu);
    }
}
