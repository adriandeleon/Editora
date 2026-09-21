package com.editora.agent.runtime;

/** Session trust is chosen by the user. Model arguments/tool annotations cannot grant permission. */
public final class AgentPolicy {
    public enum Trust {
        ASK,
        WORKSPACE,
        AGENT
    }

    private volatile Trust trust = Trust.ASK;

    public Trust trust() {
        return trust;
    }

    public void setTrust(Trust trust) {
        this.trust = java.util.Objects.requireNonNull(trust);
    }

    public boolean requiresApproval(AgentTool.Spec tool) {
        // There is no OS sandbox: arbitrary execution always needs a concrete approval, even in Agent mode.
        return switch (tool.effect()) {
            case READ -> false;
            case WORKSPACE_WRITE -> trust == Trust.ASK;
            case DESTRUCTIVE -> true;
            case EXTERNAL -> true;
        };
    }
}
