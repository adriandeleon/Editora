package com.editora.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.config.ConfigManager;
import com.editora.config.TrustStore;
import com.editora.editor.EditorBuffer;
import com.editora.jstools.JsToolRun;
import com.editora.jstools.JsTools;
import com.editora.lsp.LspManager;
import com.editora.run.NodeRuntimes;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document and actions on save for JavaScript and TypeScript.
 *
 * <p><b>Formatting</b> (Settings → LSP → TypeScript → Formatter): {@code auto} uses the project's own Prettier when the
 * project uses Prettier, else its Biome when it has a {@code biome.json}, else the TypeScript server, as before;
 * {@code prettier} / {@code biome} / {@code server} pick one. Prettier and Biome run only from a trusted folder
 * (the project's code: plugins, a JS configuration). A selection is formatted by Prettier's range, else by the
 * server. A failure shows the tool's first error line and leaves the text as it was. Works with LSP support off
 * (the server choice then has nothing to format with), as Ruff and Black do for Python.
 *
 * <p><b>On save</b>, after an explicit save only (never autosave), in this order: organize imports (the server),
 * ESLint's automatic fixes, format; the file is saved again when they changed it, and their changes are one undo
 * step — as Java's actions on save do. A step whose file was typed in meanwhile is skipped.
 */
final class JsFormatCoordinator {

    private final LspCoordinator lsp;
    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final EslintCoordinator eslint;

    /** Buffers being saved again by {@link #afterUserSave}: that save does not start another round. */
    private final Set<EditorBuffer> savingAgain = Collections.newSetFromMap(new WeakHashMap<>());

    JsFormatCoordinator(
            LspCoordinator lsp,
            CoordinatorHost host,
            LspManager lspManager,
            LspCoordinator.Ops ops,
            EslintCoordinator eslint) {
        this.lsp = lsp;
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.eslint = eslint;
    }

    /** How a format ended. */
    enum Outcome {
        FORMATTED,
        UNCHANGED,
        /** The server is to format it: no Prettier/Biome applies. */
        SERVER,
        FAILED
    }

    /** Whether {@code buffer} is a local JS/TS file this may format with a project's tool. */
    private boolean mayUseTool(@Nullable EditorBuffer buffer) {
        Path path = buffer == null ? null : buffer.getPath();
        return buffer != null
                && JsTools.applies(path)
                && EslintCoordinator.local(path)
                && !"server".equals(host.settings().getJsFormatter());
    }

    /**
     * Format Document on the active JS/TS file: true when this took the command (whatever came of it), false to let
     * it go to the language server as before.
     */
    boolean formatActive() {
        EditorBuffer buffer = host.activeBuffer();
        if (!mayUseTool(buffer) || buffer == null) {
            return false;
        }
        if (!ops.activeEditable() || buffer.isNarrowed()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return true;
        }
        var sel = buffer.getFocusedArea().getSelection();
        int[] range = sel.getLength() > 0 ? new int[] {sel.getStart(), sel.getEnd()} : null;
        format(buffer, range, false, outcome -> {
            if (outcome == Outcome.SERVER) {
                lsp.formatDocument(); // today's path: the TypeScript server (its own "unavailable" when off)
            }
        });
        return true;
    }

    /**
     * Formats {@code buffer} (the {@code range} of it: [start, end) offsets, or null for all) with the project's
     * Prettier or Biome, off the FX thread; {@code done} gets the outcome on the FX thread. {@code quiet}: a save's
     * format, which says nothing when nothing changed.
     */
    void format(EditorBuffer buffer, int @Nullable [] range, boolean quiet, Consumer<Outcome> done) {
        Path file = java.util.Objects.requireNonNull(buffer.getPath());
        String snapshot = buffer.getContent();
        long version = buffer.docVersion();
        String setting = host.settings().getJsFormatter();
        Path project = ops.lspProjectRoot();
        String nodeSetting = host.settings().getNodePath();
        TrustStore trust = new TrustStore();
        ConfigManager config = ops.config();
        if (config != null) {
            trust.roots.putAll(config.getTrustStore().roots);
        }
        CompletableFuture.supplyAsync(
                        () -> {
                            JsTools.FormatPlan plan =
                                    JsTools.formatPlan(setting, file, JsTools.Lookup.local(project), trust::isTrusted);
                            // Biome formats whole files only: a selection goes to the server's range formatting
                            if (plan.tool() == null || (range != null && plan.formatter() == JsTools.Formatter.BIOME)) {
                                return new Ran(plan, null);
                            }
                            NodeRuntimes.NodeRuntime node = NodeRuntimes.resolveLocal(file, project, "", nodeSetting);
                            JsToolRun.Invocation inv = JsToolRun.format(
                                    plan, file, node.command(), node.binDir().orElse(null), range);
                            JsToolRun.Output out = JsToolRun.run(inv, snapshot, null);
                            return new Ran(
                                    plan, JsToolRun.formatOutcome(plan.tool().kind(), out, snapshot));
                        },
                        com.editora.process.BlockingTasks.named("js-format"))
                .exceptionally(t -> new Ran(
                        new JsTools.FormatPlan(JsTools.Formatter.SERVER, null, null, null),
                        new JsToolRun.FormatOutcome(null, String.valueOf(t.getMessage()))))
                .thenAccept(ran ->
                        Platform.runLater(() -> done.accept(landed(buffer, snapshot, version, setting, ran, quiet))));
    }

    /** A format run: the plan, and what the tool produced (null when no tool ran). */
    private record Ran(JsTools.FormatPlan plan, JsToolRun.@Nullable FormatOutcome outcome) {}

    private Outcome landed(EditorBuffer buffer, String snapshot, long version, String setting, Ran ran, boolean quiet) {
        JsTools.FormatPlan plan = ran.plan();
        JsToolRun.FormatOutcome outcome = ran.outcome();
        if (outcome == null) {
            if (plan.problem() != null) {
                host.setError(tr(plan.problem(), toolName(setting)));
                return Outcome.FAILED;
            }
            return Outcome.SERVER;
        }
        String name = plan.tool() == null ? "" : plan.tool().kind().displayName();
        String text = outcome.text();
        if (!outcome.ok() || text == null) {
            host.setError(outcome.error()); // the tool's first error line; the text stays as it was
            return Outcome.FAILED;
        }
        if (buffer.isDisposed()
                || buffer.docVersion() != version
                || !buffer.getContent().equals(snapshot)) {
            if (!quiet) {
                host.setStatus(tr("status.lsp.formatStale")); // edited mid-format: a re-run formats cleanly
            }
            return Outcome.FAILED;
        }
        int[] span = com.editora.format.PythonFormatter.changedSpan(snapshot, text);
        if (span == null) {
            if (!quiet) {
                host.setStatus(tr("status.jsFormat.noChange", name));
            }
            return Outcome.UNCHANGED;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.format"))) {
            return Outcome.FAILED;
        }
        buffer.getArea().replaceText(span[0], span[1], text.substring(span[0], span[2]));
        host.setStatus(tr("status.jsFormat.formatted", name));
        return Outcome.FORMATTED;
    }

    /** The tool a Formatter choice names, for its messages. */
    private static String toolName(String setting) {
        return "biome".equals(setting) ? JsTools.Kind.BIOME.displayName() : JsTools.Kind.PRETTIER.displayName();
    }

    // --- actions on save ---------------------------------------------------------------------------------

    /**
     * After an explicit save of a JS/TS file: organize imports, ESLint's fixes, format — each as Settings asks —
     * then saves again when they changed the file. Never on autosave (the caller is the explicit-save path).
     */
    void afterUserSave(@Nullable EditorBuffer buffer) {
        if (buffer == null || savingAgain.remove(buffer)) {
            return;
        }
        Path path = buffer.getPath();
        Path served = lsp.lspPath(buffer);
        var s = host.settings();
        boolean organize = s.isJsOrganizeImportsOnSave() && served != null && serverOn(served);
        boolean fix = s.isEslintFixOnSave() && s.isEslintEnabled();
        boolean format = s.isJsFormatOnSave();
        if (!JsTools.applies(path)
                || !(organize || fix || format)
                || !buffer.isEditable()
                || buffer.isDisposed()
                || buffer.isLargeFile()
                || buffer.isNarrowed()) {
            return;
        }
        String saved = buffer.getContent();
        Runnable formatThenSave = () -> {
            if (!format || buffer.isDisposed()) {
                saveIfChanged(buffer, saved);
                return;
            }
            if (!mayUseTool(buffer)) {
                serverFormat(buffer, () -> saveIfChanged(buffer, saved));
                return;
            }
            format(buffer, null, true, outcome -> {
                if (outcome == Outcome.SERVER) {
                    serverFormat(buffer, () -> saveIfChanged(buffer, saved));
                } else {
                    saveIfChanged(buffer, saved);
                }
            });
        };
        Runnable fixThenRest = () -> {
            if (!fix || buffer.isDisposed()) {
                formatThenSave.run();
                return;
            }
            eslint.fixAll(buffer, changed -> formatThenSave.run());
        };
        if (!organize || served == null) {
            fixThenRest.run();
            return;
        }
        var area = buffer.getArea();
        int last = Math.max(0, area.getParagraphs().size() - 1);
        lspManager.changeDocument(served, buffer.text());
        lspManager.organizeImportsAny(served, last, area.getParagraph(last).length(), outcome -> fixThenRest.run());
    }

    private boolean serverOn(Path served) {
        return ops.lspFeatureEnabled() && lspManager.isManaged(served);
    }

    /** The TypeScript server's whole-file format, then {@code then}; skipped when no server formats the file. */
    private void serverFormat(EditorBuffer buffer, Runnable then) {
        Path served = lsp.lspPath(buffer);
        if (served == null || !serverOn(served) || !lspManager.supportsFormatting(served)) {
            then.run();
            return;
        }
        String before = buffer.getContent();
        lspManager.changeDocument(served, buffer.text()); // the formatter sees what the fixes left
        lspManager.formatDocument(served, lsp.formatRequestIndent(buffer), edits -> {
            if (!edits.isEmpty() && !buffer.isDisposed() && buffer.getContent().equals(before)) {
                buffer.applyLspEdits(edits);
            }
            then.run();
        });
    }

    /** Saves {@code buffer} again when the actions changed what was saved, and nothing else did. */
    private void saveIfChanged(EditorBuffer buffer, String saved) {
        if (buffer.isDisposed() || buffer.getContent().equals(saved) || !buffer.isDirty()) {
            return;
        }
        SaveActionUndo.foldIntoOneUndo(buffer, saved);
        savingAgain.add(buffer);
        if (!ops.saveBuffer(buffer)) {
            savingAgain.remove(buffer);
        }
    }
}
