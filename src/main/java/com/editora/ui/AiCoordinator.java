package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

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
 * {@code Settings.aiApiKey}, falling back to the {@code ANTHROPIC_API_KEY} environment variable.
 * {@code MainController} keeps the {@code ai.*} command registrations and delegates here.
 */
final class AiCoordinator {

    /** The default model for all AI actions (user-overridable via Settings / {@code ai.setModel}). */
    static final String DEFAULT_MODEL = "claude-opus-4-8";

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
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final AiService service = new AiService();
    private boolean busy;

    AiCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Whether the AI actions are enabled (the setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isAiSupport() && !host.simpleModeActive();
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
            start(tr("status.ai.explaining"));
            service.generate(
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
                            if (checkStop(stopReason)) {
                                host.setStatus(tr("status.ai.done"));
                            }
                        }

                        @Override
                        public void onError(String message) {
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
        if (apiKey().isEmpty()) {
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
        String configured = host.settings().getAiApiKey();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? "" : env.trim();
    }

    private String model() {
        String configured = host.settings().getAiModel();
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    /** Window close: cancel any stream + stop the worker thread. */
    void shutdown() {
        service.shutdown();
    }
}
