package com.editora.dap;

import java.util.List;

/**
 * The exception filters each adapter is known to offer, for the picker before a session has asked the adapter
 * itself (a live session's {@code exceptionBreakpointFilters} replace these). Defaults as the adapters declare
 * them: debugpy and Editora's JVM adapter stop on uncaught exceptions; java-debug and vscode-js-debug on none.
 */
public final class ExceptionFilterCatalog {

    private ExceptionFilterCatalog() {}

    public static List<DapModels.ExceptionFilterOption> known(String language) {
        if (language == null) {
            return List.of();
        }
        return switch (language) {
            case "python" ->
                List.of(
                        new DapModels.ExceptionFilterOption("raised", "Raised Exceptions", false),
                        new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", true),
                        new DapModels.ExceptionFilterOption("userUnhandled", "User Uncaught Exceptions", false));
            case "java" ->
                List.of(
                        new DapModels.ExceptionFilterOption("caught", "Caught Exceptions", false),
                        new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", false));
            case "javascript" ->
                List.of(
                        new DapModels.ExceptionFilterOption("all", "Caught Exceptions", false),
                        new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", false));
            // Editora's JVM adapter (com.editora.dap.jvm): a script's uncaught exception stops, as debugpy's does.
            // Metals' adapter (scala-debug-adapter, on java-debug) offers the same two ids.
            case "groovy", "kotlin", "clojure", "scala" ->
                List.of(
                        new DapModels.ExceptionFilterOption("caught", "Caught Exceptions", false),
                        new DapModels.ExceptionFilterOption("uncaught", "Uncaught Exceptions", true));
            default -> List.of();
        };
    }

    /**
     * The id of {@code language}'s "stop on caught exceptions too" filter (java-debug: {@code caught}, debugpy:
     * {@code raised}, vscode-js-debug: {@code all}), or null when its adapter has none. What the Caught toggle of
     * the Run menu and the Debug window turns on and off.
     */
    public static String caughtFilter(String language) {
        if (language == null) {
            return null;
        }
        return switch (language) {
            case "java", "groovy", "kotlin", "clojure", "scala" -> "caught";
            case "python" -> "raised";
            case "javascript" -> "all";
            default -> null;
        };
    }

    /** The id of {@code language}'s "stop on uncaught exceptions" filter, or null when its adapter has none. */
    public static String uncaughtFilter(String language) {
        return caughtFilter(language) == null ? null : "uncaught";
    }

    /**
     * The label {@code option} is shown with, in the UI's language: the labels of the filters above — which the
     * adapters themselves also use (java-debug reports "Caught Exceptions" and "Uncaught Exceptions") — are
     * translated; any other label an adapter declares is shown as it sent it.
     */
    public static String displayLabel(DapModels.ExceptionFilterOption option) {
        String label = option == null ? null : option.label();
        if (label == null) {
            return "";
        }
        String key = switch (label) {
            case "Caught Exceptions" -> "debug.exceptionFilter.caught";
            case "Uncaught Exceptions" -> "debug.exceptionFilter.uncaught";
            case "Raised Exceptions" -> "debug.exceptionFilter.raised";
            case "User Uncaught Exceptions" -> "debug.exceptionFilter.userUncaught";
            default -> null;
        };
        return key == null ? label : com.editora.i18n.Messages.tr(key);
    }
}
