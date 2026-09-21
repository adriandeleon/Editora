package com.editora.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** One operation's cancellation scope. Hooks close blocking resources as well as interrupting workers. */
public final class AgentCancellation {
    private boolean cancelled;
    private final List<Runnable> hooks = new ArrayList<>();

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public void check() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Agent operation cancelled");
        }
    }

    public AutoCloseable onCancel(Runnable hook) {
        synchronized (this) {
            if (!cancelled) {
                hooks.add(hook);
                return () -> {
                    synchronized (this) {
                        hooks.remove(hook);
                    }
                };
            }
        }
        hook.run();
        return () -> {};
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pending = List.copyOf(hooks);
            hooks.clear();
        }
        for (Runnable hook : pending) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // Every resource must get its cancellation, even if a sibling close failed.
            }
        }
    }
}
