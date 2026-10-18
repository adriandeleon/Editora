package com.editora.run;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a newly added run configuration starts out as.
 *
 * <p>Run Configurations → <b>Add</b> used to create a wholly blank Java configuration, which is
 * unrunnable until you fill the main class in — and running it before you did reported a language-server
 * stack trace (#795). It now starts from whatever the window can tell about the active file, which is usually
 * the configuration you wanted.
 *
 * <p>Pure, so the naming rules are unit-testable; the caller supplies the suggestion.
 */
public final class RunConfigDefaults {

    private RunConfigDefaults() {}

    /**
     * The configuration Run Configurations → <b>Add</b> creates.
     *
     * @param suggestedMainClass the active Java file's main class, or null when there is nothing to suggest
     * @param existingNames the names already in the list, so the new one does not collide with them
     * @param fallbackName the localized "New Configuration", used when there is nothing to name it after
     */
    public static com.editora.config.RunConfiguration newConfiguration(
            String suggestedMainClass, Collection<String> existingNames, String fallbackName) {
        String name = uniqueName(nameFor(suggestedMainClass, fallbackName), existingNames);
        return new com.editora.config.RunConfiguration(
                name, suggestedMainClass == null ? "" : suggestedMainClass.strip(), "", "", "", "");
    }

    /**
     * A configuration for a Python or shell script — what Add and Save as Run Configuration propose when such
     * a script is the active file, instead of an empty Java configuration. Named after the file; the target is
     * its path relative to {@code projectRoot} when the script is inside it (a configuration's run resolves it
     * against the project), else absolute. Null when {@code type} is not a script type or there is no script.
     *
     * <p>The working directory is written out too — the script's own folder, the one its gutter ▶ runs it in —
     * so the saved configuration behaves exactly like ▶, and the field shows where it runs instead of a blank
     * that means something else. Blank when that folder is the project root (blank means the root).
     */
    public static com.editora.config.RunConfiguration forScript(
            String type, java.nio.file.Path script, java.nio.file.Path projectRoot) {
        if (script == null
                || !(ScriptRunCommand.PYTHON.equals(type)
                        || ScriptRunCommand.SHELL.equals(type)
                        || ScriptRunCommand.isJvmScript(type))
                || script.getFileName() == null) {
            return null;
        }
        String target = script.toString();
        java.nio.file.Path folder = script.normalize().getParent();
        String workingDir = folder == null ? "" : folder.toString();
        if (projectRoot != null
                && script.getFileSystem() == projectRoot.getFileSystem()
                && script.normalize().startsWith(projectRoot.normalize())) {
            java.nio.file.Path root = projectRoot.normalize();
            target = root.relativize(script.normalize()).toString();
            workingDir = folder == null || folder.equals(root)
                    ? ""
                    : root.relativize(folder).toString();
        }
        return new com.editora.config.RunConfiguration(
                script.getFileName().toString(), type, target, "", "", "", "", workingDir, "", "", "");
    }

    /**
     * The main class a {@code .java} file picked with Run Configurations → Main class → <b>Browse…</b> launches,
     * so nobody has to work a fully qualified name out by hand. A {@code static void main} the file declares
     * wins (the one in the class named after the file first); otherwise the file's package plus its name — a
     * Java 25 instance {@code void main()} or a multi-line signature the scanner does not see still runs as
     * that class. Null when {@code fileName} is not a {@code .java} file.
     */
    public static String mainClassForSource(String source, String fileName) {
        if (fileName == null || !fileName.endsWith(".java") || fileName.length() == ".java".length()) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - ".java".length());
        List<MainMethodScanner.MainMethod> mains = MainMethodScanner.scan(source);
        for (MainMethodScanner.MainMethod main : mains) {
            if (nameFor(main.fqn(), "").equals(stem)) {
                return main.fqn();
            }
        }
        if (!mains.isEmpty()) {
            return mains.get(0).fqn();
        }
        String pkg = MainMethodScanner.packageOf(source);
        return pkg.isEmpty() ? stem : pkg + "." + stem;
    }

    /**
     * The name a configuration gets when its main class changes from {@code oldMainClass} to
     * {@code newMainClass}: still named after nothing in particular — blank, "New Configuration" or the old
     * class ({@code (2)}-suffixed or not) — it is renamed after the new class, as Add would have named it;
     * a name the user typed is kept.
     */
    public static String renamedForMainClass(
            String currentName,
            String oldMainClass,
            String newMainClass,
            String fallbackName,
            Collection<String> otherNames) {
        String stem = currentName == null ? "" : currentName.strip().replaceFirst(" \\(\\d+\\)$", "");
        boolean placeholder = stem.isEmpty()
                || stem.equalsIgnoreCase(fallbackName)
                || stem.equalsIgnoreCase(nameFor(oldMainClass, fallbackName));
        return placeholder ? uniqueName(nameFor(newMainClass, fallbackName), otherNames) : currentName;
    }

    /** {@code cfg} renamed with a {@link #uniqueName} among {@code existingNames}. */
    public static com.editora.config.RunConfiguration uniquelyNamed(
            com.editora.config.RunConfiguration cfg, Collection<String> existingNames) {
        // A copy of every field but the name: rebuilding it field by field dropped the ones added later
        // (Run in Terminal, the deployment).
        return cfg.withName(uniqueName(cfg.name(), existingNames));
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
     * <p><b>Not cosmetic.</b> Each configuration is registered as synthetic {@code run.config.<slug>} and
     * {@code debug.config.<slug>} commands so it can be found in the palette and given a keybinding, and the
     * slug comes from the name — so two configurations sharing a name share both ids, and the second
     * silently overwrites the first's commands.
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
