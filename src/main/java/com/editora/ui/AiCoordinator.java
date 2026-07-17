package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import com.editora.ai.AiProvider;
import com.editora.ai.AiRequests;
import com.editora.ai.AiService;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the direct-API AI actions (the {@code CoordinatorHost} feature-coordinator pattern): commit-message
 * generation from the staged diff, explain-selection into a Markdown buffer, and rewrite-selection as an
 * undoable edit — each one streamed call to the Anthropic Messages API via {@link AiService} (no SDK, no
 * agent loop; the embedded ACP agent is the separate {@code AgentCoordinator}). The API key comes from
 * {@code Settings.aiApiKey}, falling back — for the Anthropic provider only — to the
 * {@code ANTHROPIC_API_KEY} environment variable (see {@link #effectiveKey}).
 * {@code MainController} keeps the {@code ai.*} command registrations and delegates here.
 */
final class AiCoordinator {

    /** The default model for all AI actions (user-overridable via Settings / {@code ai.setModel}). */
    static final String DEFAULT_MODEL = "claude-opus-4-8";

    /** The default model for inline completion — the fastest tier, since latency is the whole game. */
    static final String DEFAULT_COMPLETION_MODEL = "claude-haiku-4-5";

    /** The AI-specific window services beyond {@link CoordinatorHost}. */
    interface Ops {
        /** The active Git repo root, or null (Git off / not a repo). */
        Path repoRoot();

        /** Fetches {@code git diff --cached} off-thread; delivers the diff (or null on failure) on FX. */
        void stagedDiff(Path root, Consumer<String> onResult);

        /** Puts {@code message} into the Commit tool window's message box. */
        void setCommitMessage(String message);

        /** Opens the Commit tool window and focuses the message box. */
        void openCommitWindow();

        /** Opens {@code buffer} in a new, selected editor tab. */
        void openTab(EditorBuffer buffer);

        /** Shows/hides the Commit tool window's "Generate Commit Message" (AI) button. */
        void setCommitAiAvailable(boolean available);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final AiService service = new AiService();
    /** Inline completion streams on its own service so it can never cancel (or be cancelled by) an
     *  explicit action like commit-message generation; each keystroke's request supersedes the last. */
    private final AiService completionService = new AiService();

    private boolean busy;
    /** Cached result of the last connectivity probe (see {@link #applySupport}) — never re-checked per
     *  selection/keystroke, only at init and (debounced) on a Settings edit. Backs the floating selection
     *  Explain/Rewrite bar's availability. */
    private boolean connected;
    /** The provider/endpoint/key/model combination the last probe (scheduled or completed) was for — lets
     *  {@link #applySupport} tell "the AI config actually changed" from "some unrelated Settings field was
     *  edited", so typing in, say, the tab-size field doesn't re-ping the network. */
    private String probeSignature;
    /** Debounces the network probe after a config edit (matches the Settings page's own AI-status
     *  debounce): a keystroke in the endpoint/key/model field must not ping the server per character. */
    private final PauseTransition probeDebounce = new PauseTransition(Duration.millis(600));

    AiCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        probeDebounce.setOnFinished(e -> probeNow());
    }

    /** Whether the AI actions are enabled (the master AI kill switch + the feature's own setting,
     *  suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isAiEnabled() && host.settings().isAiSupport() && !host.simpleModeActive();
    }

    /** The effective gate for the floating selection Explain/Rewrite bar: enabled + last-known reachable. */
    boolean isActionsAvailable() {
        return isEnabled() && connected;
    }

    /**
     * Pushes the cached selection-actions-bar availability to every open buffer, and — only when the
     * provider/endpoint/key/model actually changed since the last probe — (re)schedules a debounced
     * connectivity re-probe. Called at init and on every settings apply, mirroring
     * {@code MermaidCoordinator}/{@code HtmlPreviewCoordinator}'s detect-then-gate idiom, but this one is
     * itself debounced: {@code apply()} fires on every keystroke in <em>any</em> Settings field (not just
     * AI's), so a naive re-probe here would ping the network while the user types in an unrelated field —
     * or, worse, once per character while editing the AI endpoint/key/model fields themselves.
     */
    void applySupport() {
        if (!isEnabled()) {
            probeDebounce.stop();
            connected = false;
            probeSignature = null; // force a fresh probe the next time AI is (re)enabled
            pushAvailability();
            return;
        }
        pushAvailability(); // reflect the cached result now
        String signature = provider() + "|" + endpoint() + "|" + apiKey() + "|" + model();
        if (!signature.equals(probeSignature)) {
            probeSignature = signature;
            probeDebounce.playFromStart();
        }
    }

    /** Pushes {@link #isActionsAvailable()} to every open buffer's selection bar + the Commit window's
     *  "Generate Commit Message" button. */
    private void pushAvailability() {
        boolean available = isActionsAvailable();
        host.forEachBuffer(b -> b.setAiActionsEnabled(available));
        ops.setCommitAiAvailable(available);
    }

    private void probeNow() {
        checkConnection((ok, message) -> {
            connected = ok;
            pushAvailability();
        });
    }

    /** The effective inline-completion gate: master + sub-toggle + a key when the provider needs one. */
    boolean isInlineCompletionEnabled() {
        return isEnabled()
                && host.settings().isAiInlineCompletion()
                && (!provider().requiresApiKey() || !apiKey().isEmpty());
    }

    /** {@code EditorBuffer.AiCompletionProvider}: one short, stop-at-newline completion per idle pause.
     *  Errors are silent by design — an inline suggester must never nag; the buffer's generation guard
     *  drops a stale result. */
    void inlineComplete(String language, String prefix, String suffix, Consumer<String> onResult) {
        if (!isInlineCompletionEnabled()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        completionService.generate(
                provider(),
                endpoint(),
                apiKey(),
                completionModel(),
                AiRequests.completionSystem(),
                AiRequests.completionUser(language, prefix, suffix),
                AiRequests.COMPLETION_MAX_TOKENS,
                java.util.List.of("\n"),
                new AiService.Callbacks() {
                    @Override
                    public void onText(String delta) {
                        out.append(delta);
                    }

                    @Override
                    public void onDone(String stopReason) {
                        onResult.accept(out.toString());
                    }

                    @Override
                    public void onError(String message) {
                        // silent — see the method note
                    }
                });
    }

    private String completionModel() {
        String configured = host.settings().getAiCompletionModel();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        // OpenAI-compatible: a blank model is omitted from the request (LM Studio serves the loaded model).
        return provider() == AiProvider.ANTHROPIC ? DEFAULT_COMPLETION_MODEL : "";
    }

    /** The configured wire dialect (Anthropic vs an OpenAI-compatible local server). */
    private AiProvider provider() {
        return AiProvider.from(host.settings().getAiProvider());
    }

    /** The configured endpoint, or the provider's default (Anthropic's API / LM Studio's local port). */
    private String endpoint() {
        String configured = host.settings().getAiEndpoint();
        return configured == null || configured.isBlank() ? provider().defaultEndpoint() : configured.trim();
    }

    /** {@code ai.cancel}: drop the in-flight generation. */
    void cancel() {
        service.cancel();
        busy = false;
        host.setStatus(tr("status.ai.cancelled"));
    }

    // --- ai.generateCommitMessage ---------------------------------------------------------------------

    void generateCommitMessage() {
        ifReady(() -> {
            Path root = ops.repoRoot();
            if (root == null) {
                host.setStatus(tr("status.ai.noRepo"));
                return;
            }
            ops.stagedDiff(root, diff -> {
                if (diff == null || diff.isBlank()) {
                    host.setStatus(tr("status.ai.nothingStaged"));
                    return;
                }
                StringBuilder out = new StringBuilder();
                start(tr("status.ai.generatingCommit"));
                service.generate(
                        provider(),
                        endpoint(),
                        apiKey(),
                        model(),
                        AiRequests.commitMessageSystem(),
                        AiRequests.commitMessageUser(diff),
                        new AiService.Callbacks() {
                            @Override
                            public void onText(String delta) {
                                out.append(delta);
                            }

                            @Override
                            public void onDone(String stopReason) {
                                busy = false;
                                if (!checkStop(stopReason)) {
                                    return;
                                }
                                ops.setCommitMessage(out.toString().strip());
                                ops.openCommitWindow();
                                host.setStatus(tr("status.ai.commitReady"));
                            }

                            @Override
                            public void onError(String message) {
                                fail(message);
                            }
                        });
            });
        });
    }

    // --- ai.explainSelection --------------------------------------------------------------------------

    void explainSelection() {
        ifReady(() -> {
            EditorBuffer b = host.activeBuffer();
            String selection = b == null ? "" : b.getFocusedArea().getSelectedText();
            if (selection.isEmpty()) {
                host.setStatus(tr("status.ai.needSelection"));
                return;
            }
            EditorBuffer target = new EditorBuffer();
            target.setDisplayName("explanation.md");
            ops.openTab(target);
            target.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
            // A fast continuous stream may never hit the preview's 250ms debounce quiet period, so the
            // preview can stay blank for the whole generation with no feedback — show a spinner over it
            // until generation ends (success or failure), whichever comes first.
            target.setPreviewLoading(true, tr("markdown.preview.generating"));
            start(tr("status.ai.explaining"));
            service.generate(
                    provider(),
                    endpoint(),
                    apiKey(),
                    model(),
                    AiRequests.explainSystem(),
                    AiRequests.explainUser(b.getLanguage(), selection),
                    new AiService.Callbacks() {
                        @Override
                        public void onText(String delta) {
                            target.getArea().appendText(delta);
                        }

                        @Override
                        public void onDone(String stopReason) {
                            busy = false;
                            target.setPreviewLoading(false, null);
                            if (checkStop(stopReason)) {
                                host.setStatus(tr("status.ai.done"));
                            }
                        }

                        @Override
                        public void onError(String message) {
                            target.setPreviewLoading(false, null);
                            fail(message);
                        }
                    });
        });
    }

    // --- ai.rewriteSelection --------------------------------------------------------------------------

    void rewriteSelection() {
        ifReady(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || !b.isEditable()) {
                host.setStatus(tr("status.ai.needSelection"));
                return;
            }
            CodeArea area = b.getFocusedArea();
            var sel = area.getSelection();
            String selection = area.getSelectedText();
            if (selection.isEmpty()) {
                host.setStatus(tr("status.ai.needSelection"));
                return;
            }
            host.promptText(tr("command.ai.rewriteSelection"), tr("ai.rewritePrompt"), "", instruction -> {
                if (instruction == null || instruction.isBlank()) {
                    return;
                }
                int start = sel.getStart();
                int end = sel.getEnd();
                StringBuilder out = new StringBuilder();
                start(tr("status.ai.rewriting"));
                service.generate(
                        provider(),
                        endpoint(),
                        apiKey(),
                        model(),
                        AiRequests.rewriteSystem(),
                        AiRequests.rewriteUser(b.getLanguage(), instruction.strip(), selection),
                        new AiService.Callbacks() {
                            @Override
                            public void onText(String delta) {
                                out.append(delta);
                            }

                            @Override
                            public void onDone(String stopReason) {
                                busy = false;
                                if (!checkStop(stopReason)) {
                                    return;
                                }
                                // Abort if the buffer changed under us — the range no longer means the same text.
                                if (area.getLength() < end
                                        || !area.getText(start, end).equals(selection)) {
                                    host.setStatus(tr("status.ai.bufferChanged"));
                                    return;
                                }
                                String replacement = AiRequests.stripCodeFence(out.toString());
                                area.replaceText(start, end, replacement);
                                area.selectRange(start, start + replacement.length());
                                host.setStatus(tr("status.ai.rewritten"));
                            }

                            @Override
                            public void onError(String message) {
                                fail(message);
                            }
                        });
            });
        });
    }

    // --- helpers --------------------------------------------------------------------------------------

    private void ifReady(Runnable action) {
        if (!isEnabled()) {
            host.setStatus(tr("status.ai.disabled"));
            return;
        }
        if (provider().requiresApiKey() && apiKey().isEmpty()) {
            host.setStatus(tr("status.ai.noApiKey"));
            return;
        }
        if (busy) {
            host.setStatus(tr("status.ai.busy"));
            return;
        }
        action.run();
    }

    private void start(String statusMessage) {
        busy = true;
        host.setStatus(statusMessage);
    }

    private void fail(String message) {
        busy = false;
        host.setStatus(tr("status.ai.failed", message));
    }

    /** True when the turn ended normally; a refusal/max-tokens stop gets its own status. */
    private boolean checkStop(String stopReason) {
        if ("refusal".equals(stopReason)) {
            host.setStatus(tr("status.ai.refused"));
            return false;
        }
        return true;
    }

    /** The API key: the Settings override, else the {@code ANTHROPIC_API_KEY} environment variable. */
    private String apiKey() {
        return effectiveKey(host.settings().getAiApiKey(), provider(), System.getenv("ANTHROPIC_API_KEY"));
    }

    /**
     * The key to send: {@code configured} if set, else {@code anthropicEnvKey} — but <b>only</b> for
     * {@link AiProvider#ANTHROPIC}. Pure, so it is unit-tested without touching the real environment.
     *
     * <p>The environment fallback must stay tied to the provider the variable belongs to. It is Anthropic's
     * credential by name and it is exported on most developer machines, while the OpenAI-compatible provider
     * points at a <em>user-supplied</em> endpoint (LM Studio, a LAN proxy, any host at all) and its
     * {@link AiProvider#requiresApiKey()} is false precisely because a local server needs none. Handing that
     * provider the environment's Anthropic key sent it there as a bearer token with the Settings key field
     * empty — nothing on screen suggested a credential was in play — and inline completion ships it on every
     * idle pause, unprompted. It also let a blank-field user past the "no key configured" gate.
     */
    static String effectiveKey(String configured, AiProvider provider, String anthropicEnvKey) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (provider != AiProvider.ANTHROPIC || anthropicEnvKey == null) {
            return "";
        }
        return anthropicEnvKey.trim();
    }

    private String model() {
        String configured = host.settings().getAiModel();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return provider() == AiProvider.ANTHROPIC ? DEFAULT_MODEL : "";
    }

    /**
     * Runs a live connection check for the configured provider/endpoint/key/model and delivers
     * {@code (ok, message)} on the FX thread — green when the endpoint accepts a minimal request, red
     * with the error otherwise. Reports the gate state (disabled / no key) without a network call.
     */
    void checkConnection(java.util.function.BiConsumer<Boolean, String> onResult) {
        if (!isEnabled()) {
            onResult.accept(false, tr("status.ai.disabled"));
            return;
        }
        if (provider().requiresApiKey() && apiKey().isEmpty()) {
            onResult.accept(false, tr("status.ai.noApiKey"));
            return;
        }
        service.ping(
                provider(),
                endpoint(),
                apiKey(),
                model(),
                ping -> onResult.accept(ping.ok(), ping.ok() ? tr("settings.ai.connected") : ping.message()));
    }

    /** {@code ai.testConnection}: run the check and echo the result to the status bar. */
    void testConnection() {
        host.setStatus(tr("status.ai.testing"));
        checkConnection((ok, message) ->
                host.setStatus(ok ? tr("status.ai.connected") : tr("status.ai.connectFailed", message)));
    }

    /** Window close: cancel any stream + stop the worker threads. */
    void shutdown() {
        probeDebounce.stop();
        service.shutdown();
        completionService.shutdown();
    }
}
