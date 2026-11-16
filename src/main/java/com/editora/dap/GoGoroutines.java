package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of a Go program's goroutines the Threads view lists, and what it names them by (GoLand's and VS Code's
 * goroutine options): Delve's own {@code hideSystemGoroutines} (the runtime's goroutines — the garbage collector's
 * workers, the finalizer, the scavenger — left out), {@code goroutineFilters} (its {@code goroutines} command's filter:
 * {@code -with label job=*}, {@code -without go runtime.*}, {@code -with user}) and {@code showPprofLabels} (the pprof
 * labels a goroutine carries, shown in its name: {@code [Go 7 job:resize] main.worker}). Told to Delve in its launch
 * or attach request ({@link #decorate}), and to a running session through its console's {@code dlv config}
 * ({@link #liveCommands}). Pure.
 */
public final class GoGoroutines {

    /**
     * The options as Settings keeps them.
     *
     * @param hideRuntime the runtime's own goroutines are not listed
     * @param labels the pprof label keys a goroutine's name shows ({@code *}: every label it has)
     * @param filter Delve's goroutine filter ("" for none)
     */
    public record Options(boolean hideRuntime, List<String> labels, String filter) {
        public Options {
            labels = List.copyOf(labels == null ? List.of() : labels);
            filter = filter == null ? "" : filter.strip();
        }

        /** The options Settings starts with: runtime goroutines hidden, every pprof label shown. */
        public static final Options DEFAULT = new Options(true, List.of("*"), "");
    }

    private GoGoroutines() {}

    /** The label keys a Settings value names: separated by commas or spaces, each once. */
    public static List<String> labels(String setting) {
        List<String> out = new ArrayList<>();
        if (setting == null) {
            return out;
        }
        for (String key : setting.split("[,\\s]+")) {
            if (!key.isBlank() && !out.contains(key.strip())) {
                out.add(key.strip());
            }
        }
        return out;
    }

    /**
     * {@code args}, a launch or attach request, with the options added when it goes to Delve ({@code type} go). What a
     * launch.json already says is kept.
     */
    public static Map<String, Object> decorate(Map<String, Object> args, Options options) {
        if (args == null || !"go".equals(args.get("type")) || options == null) {
            return args;
        }
        Map<String, Object> out = new LinkedHashMap<>(args);
        if (options.hideRuntime()) {
            out.putIfAbsent("hideSystemGoroutines", true);
        }
        if (!options.labels().isEmpty()) {
            out.putIfAbsent("showPprofLabels", options.labels());
        }
        if (!options.filter().isEmpty()) {
            out.putIfAbsent("goroutineFilters", options.filter());
        }
        return out;
    }

    /**
     * What a running session's Delve is told, in its console, to take {@code options} on: each a {@code dlv config}
     * command, the labels cleared first and then added one by one.
     */
    public static List<String> liveCommands(Options options) {
        List<String> out = new ArrayList<>();
        out.add("dlv config hideSystemGoroutines " + options.hideRuntime());
        out.add("dlv config goroutineFilters " + options.filter());
        out.add("dlv config showPprofLabels -clear");
        for (String key : options.labels()) {
            out.add("dlv config showPprofLabels " + key);
        }
        return out;
    }
}
