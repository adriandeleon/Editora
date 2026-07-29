package com.editora.run;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a newly added run configuration starts out as.
 *
 * <p>Settings → Run Configurations → <b>Add</b> used to create a wholly blank Java configuration, which is
 * unrunnable until you fill the main class in — and running it before you did reported a language-server
 * stack trace (#795). It now starts from whatever the window can tell about the active file, which is usually
 * the configuration you wanted.
 *
 * <p>Pure, so the naming rules are unit-testable; the caller supplies the suggestion.
 */
public final class RunConfigDefaults {

    private RunConfigDefaults() {}

    /**
     * The configuration Settings → Run Configurations → <b>Add</b> creates.
     *
     * @param suggestedMainClass the active Java file's main class, or null when there is nothing to suggest
     * @param existingNames the names already in the list, so the new one does not collide with them
     * @param fallbackName the localized "New Configuration", used when there is nothing to name it after
     */
    public static com.editora.config.RunConfiguration newConfiguration(
            String suggestedMainClass, Collection<String> existingNames, String fallbackName) {
        String name = uniqueName(nameFor(suggestedMainClass, fallbackName), existingNames);
        return new com.editora.config.RunConfiguration(
                name, "run", suggestedMainClass == null ? "" : suggestedMainClass.strip(), "", "", "", "");
    }

    /**
     * A name for a configuration launching {@code mainClass} — its simple name ({@code com.example.App} →
     * {@code App}), or {@code fallback} when there is nothing to name it after.
     *
     * <p>Named after the class rather than left as "New configuration" for the same reason IDEs do it: the
     * list is read at a glance, and three entries all reading "New configuration" tell you nothing.
     */
    public static String nameFor(String mainClass, String fallback) {
        if (mainClass == null || mainClass.isBlank()) {
            return fallback;
        }
        String fqn = mainClass.strip();
        int dot = fqn.lastIndexOf('.');
        // A trailing dot leaves an empty simple name, which the isBlank check below turns into the fallback
        // — returning the whole string would name the configuration "com.example.".
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        return simple.isBlank() ? fallback : simple;
    }

    /**
     * {@code base}, or {@code base (2)}, {@code base (3)}… until it collides with nothing in {@code existing}.
     *
     * <p><b>Not cosmetic.</b> Each configuration is registered as a synthetic {@code run.config.<slug>}
     * command so it can be found in the palette and given a keybinding, and the slug comes from the name — so
     * two configurations sharing a name share an id, and the second silently overwrites the first's command.
     * Adding twice from the same file would otherwise produce that collision every time, since both would be
     * named after the same class.
     *
     * <p>Compared case-insensitively, because the slug is: {@code App} and {@code app} both slug to
     * {@code app} and would collide just the same.
     */
    public static String uniqueName(String base, Collection<String> existing) {
        Set<String> taken = new HashSet<>();
        if (existing != null) {
            for (String e : existing) {
                if (e != null) {
                    taken.add(e.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        String stem = base == null ? "" : base.strip();
        if (!taken.contains(stem.toLowerCase(Locale.ROOT))) {
            return stem;
        }
        // Bounded so a pathological list cannot spin; past the cap the caller gets a colliding name, which is
        // the behaviour it had before this existed rather than a hang.
        for (int n = 2; n < 1000; n++) {
            String candidate = stem + " (" + n + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return stem;
    }
}
