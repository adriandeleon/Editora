package com.editora.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Decides how blame uses an <em>ignore-revs file</em> — the list of commits (mass reformatting, a licence
 * header sweep) that blame should look through. Pure; the process and file-system facts are passed in.
 *
 * <p>What git does, verified against 2.47: a file named by {@code blame.ignoreRevsFile} is applied by
 * {@code git blame} itself, and a configured file that cannot be opened is <b>fatal</b> ("could not open
 * object name list"). Nothing on the command line takes a configured entry back out — the empty name
 * documented for {@code --ignore-revs-file ""} only clears the commits read so far, the missing file is still
 * opened first. That bites in the most common setup of all: {@code blame.ignoreRevsFile =
 * .git-blame-ignore-revs} in the user's <em>global</em> config, which breaks blame in every repository that
 * has no such file. A file with a line that is not an object name is fatal too ("invalid object name"); a
 * well-formed name of a commit the repository does not have is ignored.
 *
 * <p>So:
 * <ul>
 *   <li>nothing configured and {@code .git-blame-ignore-revs} at the repository root → pass it with
 *       {@code --ignore-revs-file} (the convention GitHub and GitLab follow);
 *   <li>every configured file readable → nothing to do, git applies them;
 *   <li>an unreadable file configured in the <em>global</em> config → run blame with the global config file
 *       replaced by a copy without that key ({@link Plan#withoutGlobalConfig}), passing the readable global
 *       entries explicitly;
 *   <li>an unreadable file configured in the repository (or system) config cannot be skipped: blame fails
 *       exactly as it does in a terminal, and {@link Plan#unreadable} names the file so the user is told why.
 * </ul>
 */
public final class BlameIgnoreRevs {

    /** The conventional file name at the repository root. */
    public static final String ROOT_FILE = ".git-blame-ignore-revs";

    /** One {@code blame.ignoreRevsFile} value and the config scope ({@code global}, {@code local}, …) it is set in. */
    public record Configured(String scope, String path) {}

    /**
     * @param files               files to pass with {@code --ignore-revs-file}, in order
     * @param withoutGlobalConfig run blame without the user's global {@code blame.ignoreRevsFile}
     * @param unreadable          a configured file that is missing and cannot be skipped, or {@code null}
     */
    public record Plan(List<String> files, boolean withoutGlobalConfig, String unreadable) {

        public static final Plan NONE = new Plan(List.of(), false, null);

        public Plan {
            files = List.copyOf(files);
        }

        /** The {@code git blame} arguments for {@link #files}. */
        public List<String> args() {
            List<String> args = new ArrayList<>(files.size() * 2);
            for (String file : files) {
                args.add("--ignore-revs-file");
                args.add(file);
            }
            return args;
        }

        /** Whether this plan changes anything about a plain {@code git blame}. */
        public boolean plain() {
            return files.isEmpty() && !withoutGlobalConfig;
        }
    }

    private BlameIgnoreRevs() {}

    /** Parses {@code git config --show-scope --get-all --path -z blame.ignoreRevsFile}: scope NUL value NUL … */
    public static List<Configured> parseScoped(String out) {
        List<Configured> list = new ArrayList<>();
        if (out == null || out.isEmpty()) {
            return list;
        }
        String[] tokens = out.split("\u0000", -1);
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            // An empty value is git's "forget the commits read so far" marker, not a file.
            if (!tokens[i + 1].isEmpty()) {
                list.add(new Configured(tokens[i].strip(), tokens[i + 1]));
            }
        }
        return list;
    }

    /**
     * @param configured the {@code blame.ignoreRevsFile} values, in the order git reads them
     * @param root       the repository root: git resolves a relative value against it
     * @param readable   whether a path is a readable regular file
     */
    public static Plan plan(List<Configured> configured, Path root, Predicate<Path> readable) {
        if (root == null) {
            return Plan.NONE;
        }
        if (configured == null || configured.isEmpty()) {
            Path conventional = root.resolve(ROOT_FILE);
            return readable.test(conventional) ? new Plan(List.of(conventional.toString()), false, null) : Plan.NONE;
        }
        boolean globalBroken = false;
        List<String> globalReadable = new ArrayList<>();
        for (Configured entry : configured) {
            Path file;
            try {
                file = root.resolve(entry.path());
            } catch (java.nio.file.InvalidPathException invalid) {
                file = null;
            }
            boolean ok = file != null && readable.test(file);
            boolean global = "global".equals(entry.scope());
            if (!ok && !global) {
                return new Plan(List.of(), false, entry.path());
            }
            if (!ok) {
                globalBroken = true;
            } else if (global) {
                globalReadable.add(file.toString());
            }
        }
        return globalBroken ? new Plan(globalReadable, true, null) : Plan.NONE;
    }

    /**
     * The text of a git config file equal to the user's global configuration without
     * {@code blame.ignoreRevsFile}: what {@code GIT_CONFIG_GLOBAL} points at for a blame that must not open
     * a missing globally configured file. Written as a <em>file</em>, not handed over with {@code -c} or
     * {@code GIT_CONFIG_COUNT}: those outrank the repository's own config, so a global
     * {@code core.autocrlf} would start overriding the repository's and change what blame calls an
     * uncommitted line. {@code safeDirectory} is added as {@code safe.directory} (honoured only from a
     * config file): git has already answered for that repository under the user's real configuration.
     *
     * @param listZ {@code git config --global --includes -z --list}: {@code key LF value NUL …}, the
     *     includes already expanded (so {@code include.*} entries are dropped)
     */
    public static String globalConfigWithoutIgnoreRevs(String listZ, String safeDirectory) {
        StringBuilder sb = new StringBuilder();
        for (String entry : listZ == null ? new String[0] : listZ.split("\u0000")) {
            int newline = entry.indexOf('\n');
            String key = newline < 0 ? entry : entry.substring(0, newline);
            int first = key.indexOf('.');
            int last = key.lastIndexOf('.');
            if (first <= 0 || last == key.length() - 1) {
                continue;
            }
            String section = key.substring(0, first);
            String name = key.substring(last + 1);
            if ((section.equalsIgnoreCase("blame") && name.equalsIgnoreCase("ignoreRevsFile"))
                    || section.equalsIgnoreCase("include")
                    || section.equalsIgnoreCase("includeIf")) {
                continue;
            }
            sb.append('[').append(section);
            if (last > first) {
                String subsection = key.substring(first + 1, last);
                sb.append(" \"")
                        .append(subsection.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
            sb.append("]\n\t").append(name);
            if (newline >= 0) { // a key listed without a value is a bare boolean
                sb.append(" = ").append(quoted(entry.substring(newline + 1)));
            }
            sb.append('\n');
        }
        if (safeDirectory != null && !safeDirectory.isBlank()) {
            sb.append("[safe]\n\tdirectory = ").append(quoted(safeDirectory)).append('\n');
        }
        return sb.toString();
    }

    /** A config value in double quotes, with the escapes git's config parser reads. */
    private static String quoted(String value) {
        return '"'
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + '"';
    }

    /** Whether a failed blame's message is about an ignore-revs file (so retrying without one may succeed). */
    public static boolean isIgnoreRevsFailure(String err) {
        return err != null && (err.contains("object name list") || err.contains("invalid object name"));
    }
}
