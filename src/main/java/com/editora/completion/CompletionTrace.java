package com.editora.completion;

/** Opt-in timing without document contents: -Deditora.completion.trace=true. Inert in normal typing. */
public final class CompletionTrace {
    private static final boolean ENABLED = Boolean.getBoolean("editora.completion.trace");
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(CompletionTrace.class.getName());

    private CompletionTrace() {}

    public static long now() {
        return ENABLED ? System.nanoTime() : 0;
    }

    public static void elapsed(String stage, long start) {
        if (ENABLED && start != 0) {
            LOG.info(() -> String.format(
                    java.util.Locale.ROOT, "completion %s %.3f ms", stage, (System.nanoTime() - start) / 1e6));
        }
    }
}
