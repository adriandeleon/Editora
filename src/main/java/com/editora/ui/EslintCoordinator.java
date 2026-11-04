package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import com.editora.config.ConfigManager;
import com.editora.config.TrustStore;
import com.editora.editor.EditorBuffer;
import com.editora.jstools.EslintReport;
import com.editora.jstools.JsToolRun;
import com.editora.jstools.JsTools;
import com.editora.lsp.LspManager;
import com.editora.process.ProcessRunner;
import com.editora.run.NodeRuntimes;
import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * ESLint on JavaScript and TypeScript files, without a second language server: the project's own ESLint is run on the
 * buffer's text ({@code --stdin}) when a tab is first shown, after a pause in typing and on save, and its problems
 * join the language server's (source {@code eslint}) in the squiggles, the counter and the Problems window. Its fixes
 * and suggestions are offered in Code Actions; {@code eslint.fixAll} applies every automatic fix.
 *
 * <p>It runs only where the project has an ESLint configuration and ESLint installed in {@code node_modules}, and only
 * from a trusted folder — ESLint loads the project's configuration and plugins, which are code. An untrusted folder
 * says so once, with Trust Folder. A missing ESLint is quiet (Doctor tells). Off with Settings → LSP → TypeScript →
 * "Check with ESLint", and with LSP support off (the problems are shown through it). One per window.
 */
final class EslintCoordinator {

    /** How long typing must pause before the file is checked again. */
    static final Duration EDIT_PAUSE = Duration.millis(800);

    /** Two runs at a time across all windows: a restored session's JS tabs do not start a Node.js each at once. */
    private static final ExecutorService RUNS = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "eslint");
        t.setDaemon(true);
        return t;
    });

    private final LspCoordinator lsp;
    private final CoordinatorHost host;
    private final LspCoordinator.Ops ops;

    /** One buffer's ESLint state. Holds nothing that points back at the buffer (the map's keys are weak). */
    private static final class State {
        /** The run in flight, stopped when a newer one starts. */
        ProcessRunner.@Nullable Cancellation running;
        /** Whether a check was ever asked for (a tab is checked once when first shown). */
        boolean requested;
        /** The language server's last diagnostics, which ESLint's are joined to. */
        List<LspDiagnostic> server = List.of();
        /** ESLint's last problems, and the text they were found in (fixes apply to that text only). */
        List<EslintReport.Problem> problems = List.of();

        @Nullable
        String checkedText;
    }

    private final Map<EditorBuffer, State> states = new WeakHashMap<>();

    /** Trust folders already told why ESLint does not run there (once per window). */
    private final Set<Path> toldUntrusted = new HashSet<>();

    /** Configuration folders whose ESLint failure was already said (once, until it works again). */
    private final Set<Path> toldFailed = new HashSet<>();

    /** What {@link #settingsChanged} last saw: the switch, LSP support, and the trust list. */
    private @Nullable String appliedSignature;

    /** The kind of each outcome a run can have. */
    enum Kind {
        OK,
        /** No ESLint configuration for the file: nothing to show. */
        NO_CONFIG,
        /** A configuration but no ESLint in node_modules: quiet (Doctor says it). */
        NOT_INSTALLED,
        UNTRUSTED,
        FAILED,
        CANCELLED
    }

    /** One run's outcome. {@code dir}: the configuration's folder (the trust target for UNTRUSTED). */
    record Run(
            Kind kind,
            EslintReport.@Nullable Result report,
            @Nullable String error,
            @Nullable Path dir) {
        static Run of(Kind kind, @Nullable Path dir) {
            return new Run(kind, null, null, dir);
        }
    }

    EslintCoordinator(LspCoordinator lsp, CoordinatorHost host, LspCoordinator.Ops ops) {
        this.lsp = lsp;
        this.host = host;
        this.ops = ops;
    }

    // --- when a file is checked ---------------------------------------------------------------------

    /** A new buffer: checked when first shown (focused), after a pause in typing, and on save ({@link #saved}). */
    void wire(EditorBuffer buffer) {
        PauseTransition pause = new PauseTransition(EDIT_PAUSE);
        pause.setOnFinished(e -> check(buffer));
        buffer.getArea().plainTextChanges().subscribe(change -> {
            State st = states.get(buffer);
            if (st != null && st.requested) {
                pause.playFromStart(); // only a file already checked: its problems follow the text
            }
        });
        buffer.getArea().focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                shown(buffer);
            }
        });
        Platform.runLater(() -> {
            if (buffer == host.activeBuffer()) {
                shown(buffer);
            }
        });
    }

    /** The tab is shown: checked once. */
    private void shown(EditorBuffer buffer) {
        State st = states.get(buffer);
        if (st == null || !st.requested) {
            check(buffer);
        }
    }

    /** {@code buffer} was saved (explicitly or by autosave): checked now. */
    void saved(@Nullable EditorBuffer buffer) {
        if (buffer != null && JsTools.applies(buffer.getPath())) {
            check(buffer);
        }
    }

    /** Whether ESLint may check {@code buffer} at all (not whether the project has it). FX thread. */
    boolean eligible(@Nullable EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        return buffer != null
                && host.settings().isEslintEnabled()
                && !buffer.isDisposed()
                && !buffer.isLargeFile()
                && JsTools.applies(path)
                && local(path);
    }

    /** A file on this machine: a remote, WSL or container file's tools are not run from here. */
    static boolean local(@Nullable Path path) {
        return path != null
                && !com.editora.vfs.Vfs.isRemote(path)
                && path.getFileSystem() == java.nio.file.FileSystems.getDefault()
                && com.editora.process.ExecTargets.runsHere(path);
    }

    /** Checks {@code buffer}'s text now, stopping a run still going for it. */
    private void check(EditorBuffer buffer) {
        if (appliedSignature == null) {
            appliedSignature = signature(); // what a later settings or trust change is compared with
        }
        if (!eligible(buffer) || !ops.lspFeatureEnabled()) {
            clear(buffer);
            return;
        }
        State st = states.computeIfAbsent(buffer, b -> new State());
        st.requested = true;
        if (st.running != null) {
            st.running.cancel();
        }
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        st.running = cancel;
        long version = buffer.docVersion();
        String text = buffer.getContent();
        Path file = java.util.Objects.requireNonNull(buffer.getPath());
        Inputs in = inputs();
        CompletableFuture.supplyAsync(() -> lint(file, text, in, false, cancel), RUNS)
                .exceptionally(t -> new Run(Kind.FAILED, null, "ESLint: " + t.getMessage(), null))
                .thenAccept(run -> Platform.runLater(() -> landed(buffer, st, cancel, version, text, run)));
    }

    /** What a run needs from the FX thread: the project, the Node.js setting, a copy of the trust list. */
    record Inputs(@Nullable Path project, String nodeSetting, TrustStore trust) {}

    private Inputs inputs() {
        TrustStore copy = new TrustStore();
        ConfigManager config = ops.config();
        if (config != null) {
            copy.roots.putAll(config.getTrustStore().roots);
        }
        return new Inputs(ops.lspProjectRoot(), host.settings().getNodePath(), copy);
    }

    /** Finds and runs ESLint for {@code file}. Off the FX thread. */
    static Run lint(Path file, String text, Inputs in, boolean fix, ProcessRunner.@Nullable Cancellation cancel) {
        JsTools.EslintSetup setup = JsTools.eslint(file, JsTools.Lookup.local(in.project()));
        Path dir = setup.dir().orElse(null);
        if (setup.config().isEmpty() || dir == null) {
            return Run.of(Kind.NO_CONFIG, null);
        }
        if (setup.tool().isEmpty()) {
            return Run.of(Kind.NOT_INSTALLED, dir);
        }
        JsTools.Tool tool = setup.tool().get();
        if (!in.trust().isTrusted(dir) || !in.trust().isTrusted(tool.root())) {
            return Run.of(Kind.UNTRUSTED, dir);
        }
        NodeRuntimes.NodeRuntime node = NodeRuntimes.resolveLocal(file, in.project(), "", in.nodeSetting());
        JsToolRun.Invocation inv = JsToolRun.eslint(
                tool, setup.config().get(), file, node.command(), node.binDir().orElse(null), fix);
        JsToolRun.Output out = JsToolRun.run(inv, text, cancel);
        if (out.cancelled()) {
            return Run.of(Kind.CANCELLED, dir);
        }
        JsToolRun.EslintOutcome outcome = JsToolRun.eslintOutcome(out, text);
        return outcome.report() != null
                ? new Run(Kind.OK, outcome.report(), null, dir)
                : new Run(Kind.FAILED, null, outcome.error(), dir);
    }

    private void landed(
            EditorBuffer buffer, State st, ProcessRunner.Cancellation cancel, long version, String text, Run run) {
        if (buffer.isDisposed() || st.running != cancel || run.kind() == Kind.CANCELLED) {
            return; // a newer run owns the buffer now
        }
        st.running = null;
        if (buffer.docVersion() != version) {
            return; // typed since: the pause's run follows
        }
        switch (run.kind()) {
            case OK -> {
                if (run.dir() != null) {
                    toldFailed.remove(run.dir());
                }
                show(buffer, st, run.report() == null ? List.of() : run.report().problems(), text);
            }
            case UNTRUSTED -> {
                show(buffer, st, List.of(), null);
                offerTrust(buffer, run.dir());
            }
            case FAILED -> {
                show(buffer, st, List.of(), null);
                if (run.dir() != null && toldFailed.add(run.dir()) && run.error() != null) {
                    host.setError(run.error()); // a broken configuration, said once until it works
                }
            }
            default -> show(buffer, st, List.of(), null);
        }
    }

    /** Shows {@code problems} on {@code buffer} with the server's, through the LSP diagnostics path. */
    private void show(EditorBuffer buffer, State st, List<EslintReport.Problem> problems, @Nullable String text) {
        boolean had = !st.problems.isEmpty();
        st.problems = List.copyOf(problems);
        st.checkedText = text;
        Path path = lsp.lspPath(buffer);
        if (path != null && (had || !problems.isEmpty())) {
            lsp.onDiagnostics(path, st.server); // joined again with ESLint's by {@link #joined}
        }
    }

    /** Drops {@code buffer}'s ESLint problems. */
    private void clear(EditorBuffer buffer) {
        State st = states.get(buffer);
        if (st != null) {
            if (st.running != null) {
                st.running.cancel();
                st.running = null;
            }
            show(buffer, st, List.of(), null);
        }
    }

    /**
     * The language server's {@code diagnostics} for {@code buffer} with ESLint's added (the coordinator's
     * diagnostics callback calls this); remembered, so a later ESLint answer is joined to them.
     */
    List<LspDiagnostic> joined(@Nullable EditorBuffer buffer, List<LspDiagnostic> diagnostics) {
        State st = buffer == null ? null : states.get(buffer);
        if (st == null) {
            return diagnostics;
        }
        st.server = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (st.problems.isEmpty()) {
            return st.server;
        }
        List<LspDiagnostic> all = new ArrayList<>(st.server);
        st.problems.forEach(p -> all.add(p.diagnostic()));
        return all;
    }

    /**
     * A settings apply or a trust change: when ESLint's switch, LSP support or the trust list changed, every checked
     * file is checked again (or cleared); a notice that no longer applies goes.
     */
    void settingsChanged() {
        String signature = signature();
        if (signature.equals(appliedSignature)) {
            return;
        }
        boolean first = appliedSignature == null;
        appliedSignature = signature;
        if (first) {
            return; // nothing checked yet: each tab is checked when shown
        }
        toldUntrusted.clear();
        toldFailed.clear();
        host.forEachBuffer(b -> {
            State st = states.get(b);
            hideOwnNotice(b);
            if ((st != null && st.requested) || b == host.activeBuffer()) {
                check(b);
            }
        });
    }

    /** What decides whether files are checked: ESLint's switch, LSP support and the trust list. */
    private String signature() {
        ConfigManager config = ops.config();
        return host.settings().isEslintEnabled() + ":" + ops.lspFeatureEnabled() + ":"
                + (config == null ? 0 : config.getTrustStore().roots.hashCode());
    }

    // --- trust -----------------------------------------------------------------------------------------

    /**
     * A file whose ESLint did not run because its folder is not trusted: says so once per folder, on the file, with
     * Trust Folder (the same command XML's trust uses) — when the file is in the project, which is what is trusted.
     */
    private void offerTrust(EditorBuffer buffer, @Nullable Path dir) {
        Path target = lsp.xmlTrust().target(buffer);
        Path project = ops.lspProjectRoot();
        if (dir == null || target == null || project == null || !target.equals(project) || !toldUntrusted.add(target)) {
            return;
        }
        buffer.showFileNotice(tr("eslint.trust.notice"), tr("xml.trust.button"), tr("xml.trust.notNow"), () -> {
            if (buffer == host.activeBuffer()) {
                lsp.xmlTrust().trustFolder();
            }
        });
    }

    private static void hideOwnNotice(EditorBuffer b) {
        if (tr("eslint.trust.notice").equals(b.fileNoticeText())) {
            b.hideFileNotice();
        }
    }

    // --- fixes -----------------------------------------------------------------------------------------

    /** A Code Actions row of ESLint's: one fix or suggestion, or every automatic fix ({@code fix} null). */
    record Action(String checkedText, EslintReport.@Nullable Fix fix) {}

    /**
     * {@code items} with ESLint's fixes and suggestions for the problems on lines {@code first}..{@code last} of
     * {@code buffer} added (kind {@code quickfix}: Code Actions lists them, Generate and Refactor do not), and "Fix
     * all auto-fixable problems" when the file has any.
     */
    List<LspManager.CodeActionItem> withFixes(
            @Nullable EditorBuffer buffer, int first, int last, List<LspManager.CodeActionItem> items) {
        State st = buffer == null ? null : states.get(buffer);
        if (st == null || st.problems.isEmpty() || st.checkedText == null) {
            return items;
        }
        List<LspManager.CodeActionItem> out = new ArrayList<>(items);
        out.addAll(fixRows(st.problems, st.checkedText, first, last));
        return out;
    }

    /** Pure: the rows for {@code problems} on lines {@code first}..{@code last}, then "Fix all" when any can be. */
    static List<LspManager.CodeActionItem> fixRows(
            List<EslintReport.Problem> problems, String checkedText, int first, int last) {
        List<LspManager.CodeActionItem> out = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (EslintReport.Problem p : problems) {
            LspDiagnostic d = p.diagnostic();
            if (d.endLine() < first || d.startLine() > last) {
                continue;
            }
            String rule = p.rule() == null ? "eslint" : p.rule();
            if (p.fix() != null && titles.add(tr("eslint.action.fix", rule))) {
                out.add(row(tr("eslint.action.fix", rule), new Action(checkedText, p.fix())));
            }
            for (EslintReport.Suggestion s : p.suggestions()) {
                if (titles.add(s.description())) {
                    out.add(row(s.description(), new Action(checkedText, s.fix())));
                }
            }
        }
        if (problems.stream().anyMatch(p -> p.fix() != null)) {
            out.add(row(tr("eslint.action.fixAll"), new Action(checkedText, null)));
        }
        return out;
    }

    private static LspManager.CodeActionItem row(String title, Action action) {
        return new LspManager.CodeActionItem(title, "quickfix", false, action);
    }

    /** Applies {@code item} when it is one of ESLint's rows; false for anyone else's. */
    boolean runsCodeAction(LspManager.CodeActionItem item) {
        if (!(item.raw() instanceof Action action)) {
            return false;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null || !ops.activeEditable() || b.isNarrowed()) {
            host.setStatus(tr("status.lsp.noCodeActions"));
            return true;
        }
        if (action.fix() == null) {
            fixAll(b, null);
            return true;
        }
        String now = b.getContent();
        String fixed = now.equals(action.checkedText()) ? action.fix().applyTo(now) : null;
        if (fixed == null) {
            host.setStatus(tr("status.eslint.stale")); // typed since it was checked: the next check offers it again
            return true;
        }
        b.getArea()
                .replaceText(
                        action.fix().start(), action.fix().end(), action.fix().text());
        host.setStatus(tr("status.lsp.codeActionApplied", LspCoordinator.bareTitle(item.title())));
        return true;
    }

    /** {@code eslint.fixAll}: every automatic fix ESLint has for the active file. */
    void fixAllActive() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !JsTools.applies(b.getPath())) {
            host.setStatus(tr("status.eslint.notJs"));
            return;
        }
        if (!ops.activeEditable() || b.isNarrowed()) {
            host.setStatus(tr("status.lsp.readOnly"));
            return;
        }
        fixAll(b, null);
    }

    /**
     * Applies every automatic fix ESLint has for {@code buffer} ({@code --fix-dry-run}: ESLint writes nothing), as one
     * undoable edit, and shows what is left. {@code done} (may be null) learns whether the text changed; then (on
     * save) it says nothing when there was nothing to fix and nothing went wrong.
     */
    void fixAll(EditorBuffer buffer, @Nullable Consumer<Boolean> done) {
        boolean quiet = done != null;
        if (!host.settings().isEslintEnabled() || !eligible(buffer)) {
            if (!quiet) {
                host.setStatus(tr(host.settings().isEslintEnabled() ? "status.eslint.notJs" : "status.eslint.off"));
            }
            finish(done, false);
            return;
        }
        long version = buffer.docVersion();
        String text = buffer.getContent();
        Path file = java.util.Objects.requireNonNull(buffer.getPath());
        Inputs in = inputs();
        if (!quiet) {
            host.setStatus(tr("status.eslint.fixing"));
        }
        CompletableFuture.supplyAsync(() -> lint(file, text, in, true, null), RUNS)
                .exceptionally(t -> new Run(Kind.FAILED, null, "ESLint: " + t.getMessage(), null))
                .thenAccept(run -> Platform.runLater(() -> fixLanded(buffer, version, text, run, quiet, done)));
    }

    private void fixLanded(
            EditorBuffer buffer, long version, String text, Run run, boolean quiet, @Nullable Consumer<Boolean> done) {
        if (buffer.isDisposed() || buffer.docVersion() != version) {
            if (!quiet && !buffer.isDisposed()) {
                host.setStatus(tr("status.lsp.formatStale"));
            }
            finish(done, false);
            return;
        }
        String said = switch (run.kind()) {
            case NO_CONFIG -> tr("status.eslint.noConfig");
            case NOT_INSTALLED -> tr("status.eslint.notInstalled");
            case UNTRUSTED -> tr("status.eslint.untrusted");
            case FAILED -> run.error();
            default -> null;
        };
        EslintReport.Result report = run.report();
        if (run.kind() != Kind.OK || report == null) {
            if (said != null && (!quiet || run.kind() == Kind.FAILED)) {
                host.setError(said);
            }
            finish(done, false);
            return;
        }
        String fixed = report.output();
        int[] span = fixed == null ? null : com.editora.format.PythonFormatter.changedSpan(text, fixed);
        if (fixed == null || span == null) {
            if (!quiet) {
                host.setStatus(
                        tr("status.eslint.nothingToFix", report.problems().size()));
            }
            finish(done, false);
            return;
        }
        if (!NoUndoGuard.allow(buffer, tr("command.eslint.fixAll"))) {
            finish(done, false);
            return;
        }
        buffer.getArea().replaceText(span[0], span[1], fixed.substring(span[0], span[2]));
        State st = states.computeIfAbsent(buffer, b -> new State());
        show(buffer, st, report.problems(), fixed); // what is left, at once
        host.setStatus(tr("status.eslint.fixed", report.problems().size()));
        finish(done, true);
    }

    private static void finish(@Nullable Consumer<Boolean> done, boolean changed) {
        if (done != null) {
            done.accept(changed);
        }
    }

    /** For Doctor: what ESLint has in {@code project} (a lookup from its root). Off the FX thread. */
    static Optional<JsTools.EslintSetup> setupFor(@Nullable Path project) {
        if (project == null || !local(project)) {
            return Optional.empty();
        }
        return Optional.of(JsTools.eslint(project.resolve("index.js"), JsTools.Lookup.local(project)));
    }
}
