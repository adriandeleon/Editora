package com.editora.agent;

import java.util.List;
import java.util.Map;

import com.editora.run.ProgramArgs;

/**
 * The known ACP agent clients Editora can drive over stdio — id, human display name, and default launch
 * command — plus the pure resolution helpers the coordinator and Settings use. The agent-side analog of
 * {@code lsp.LspServerRegistry} (which is language-server-centric): here there are no root markers or
 * language ids, because exactly ONE ACP agent is active at a time (unlike LSP, where every configured
 * server can run concurrently for its own languages). Every agent launches the same way — a tokenized
 * argv spawned via {@code ProcessRunner}, speaking ACP over stdio — so adding one is a single enum row.
 *
 * <p>All methods are static + pure (no process launch, no I/O), so they're directly unit-tested. The
 * command overrides map ({@code id -> user command}) comes from {@code Settings} (per-agent fields).
 */
public final class AcpAgentRegistry {

    /** A known ACP agent: its stable id, display name, and default stdio launch command. */
    public enum AgentDef {
        CLAUDE("claude", "Claude Code", "claude-code-acp"),
        GEMINI("gemini", "Gemini CLI", "gemini --acp"),
        COPILOT("copilot", "GitHub Copilot CLI", "copilot --acp"),
        CODEX("codex", "Codex CLI", "codex-acp"),
        QWEN("qwen", "Qwen Code", "qwen --acp"),
        OPENCODE("opencode", "OpenCode", "opencode acp");

        private final String id;
        private final String displayName;
        private final String defaultCommand;

        AgentDef(String id, String displayName, String defaultCommand) {
            this.id = id;
            this.displayName = displayName;
            this.defaultCommand = defaultCommand;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public String defaultCommand() {
            return defaultCommand;
        }
    }

    private AcpAgentRegistry() {}

    /** The known agents in display order (Settings combo + per-client override rows). Pure. */
    public static List<AgentDef> all() {
        return List.of(AgentDef.values());
    }

    /** The agent for {@code id} — blank/unknown falls back to {@link AgentDef#CLAUDE} (the pre-feature
     *  default, mirroring {@code AiProvider.from}). Never null. */
    public static AgentDef from(String id) {
        if (id != null && !id.isBlank()) {
            String key = id.strip();
            for (AgentDef d : AgentDef.values()) {
                if (d.id.equalsIgnoreCase(key)) {
                    return d;
                }
            }
        }
        return AgentDef.CLAUDE;
    }

    /** The default command for an agent id (blank if unknown). Mirrors {@code LspServerRegistry.defaultCommandFor}. */
    public static String defaultCommandFor(String id) {
        for (AgentDef d : AgentDef.values()) {
            if (d.id.equals(id)) {
                return d.defaultCommand;
            }
        }
        return "";
    }

    /** The display name for an agent id (falls back to the bare id if unknown, mirroring
     *  {@code AgentCoordinator.modelDisplayName}). Pure. */
    public static String displayNameFor(String id) {
        for (AgentDef d : AgentDef.values()) {
            if (d.id.equals(id)) {
                return d.displayName;
            }
        }
        return id == null ? "" : id;
    }

    /**
     * The tokenized argv for {@code id}: its override from {@code overrides} (id -> command) when set,
     * else its registry default. Empty list only for a fully-unknown id with no override. Mirrors
     * {@code LspServerRegistry.commandFor}; tokenized with {@code ProgramArgs.tokenize} (quote-aware).
     */
    public static List<String> commandFor(String id, Map<String, String> overrides) {
        String configured = overrides == null ? null : overrides.get(id);
        String def = defaultCommandFor(id);
        String cmd = configured == null || configured.isBlank() ? def : configured;
        return cmd.isBlank() ? List.of() : ProgramArgs.tokenize(cmd);
    }
}
