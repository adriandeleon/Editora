package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Who owns a file or folder of the project, from the project's own files (Map explore position 4): a GitHub-style
 * {@code CODEOWNERS} (the first of {@code .github/}, the root and {@code docs/}, as GitHub reads them; the last
 * matching line wins) and Kubernetes-style {@code OWNERS} files (their {@code approvers} and {@code reviewers},
 * top-level or under the {@code ".*"} filter; the nearest folder's file that names someone wins). A path that a
 * {@code CODEOWNERS} line matches takes that line; otherwise its {@code OWNERS}. No network, no GitHub API: alias
 * and team names are shown as written.
 *
 * <p>{@link #owners} reads {@code OWNERS} files as it walks up (each folder's at most once): call it off the FX
 * thread, from one thread at a time. The parsing and matching are pure.
 */
final class ProjectMapOwners {

    /** The places GitHub looks for {@code CODEOWNERS}, in its order. */
    static final List<String> CODEOWNERS_PLACES = List.of(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS");
    /** A name no file has, standing for the project's root when CODEOWNERS lines are matched. */
    private static final String PROJECT_PROBE = "\u0000";
    /** The largest owner file read. */
    private static final long MAX_READ = 512 * 1024;

    /** Who owns a path: approvers (or CODEOWNERS' owners) and reviewers, and the file that says so (root-relative). */
    record Owners(List<String> approvers, List<String> reviewers, String source) {
        Owners {
            approvers = List.copyOf(approvers);
            reviewers = List.copyOf(reviewers);
        }
    }

    /** One {@code CODEOWNERS} line: its pattern as a regular expression, and its owners (none: explicitly unowned). */
    record Rule(String pattern, Pattern regex, boolean directoryOnly, boolean nested, List<String> owners) {}

    private final @Nullable List<Rule> codeowners;
    private final String codeownersSource;
    private final Function<String, @Nullable String> read;
    /** Each folder's own {@code OWNERS} verdict by root-relative path; {@link #NONE} for no file or nobody named. */
    private final Map<String, Object> folderOwners = new HashMap<>();

    private static final Object NONE = new Object();

    private ProjectMapOwners(
            @Nullable String codeownersText, String codeownersSource, Function<String, @Nullable String> read) {
        this.codeowners = codeownersText == null ? null : parseCodeowners(codeownersText);
        this.codeownersSource = codeownersSource;
        this.read = read;
    }

    /** The owner files of {@code root}: its {@code CODEOWNERS} now, its {@code OWNERS} files as they are asked for. */
    static ProjectMapOwners load(Path root) {
        Function<String, @Nullable String> read = rel -> read(root, rel);
        for (String place : CODEOWNERS_PLACES) {
            String text = read.apply(place);
            if (text != null) {
                return new ProjectMapOwners(text, place, read);
            }
        }
        return new ProjectMapOwners(null, "", read);
    }

    /** For tests: {@code CODEOWNERS} text (or null) and the owner files by root-relative path. */
    static ProjectMapOwners of(@Nullable String codeowners, Function<String, @Nullable String> files) {
        return new ProjectMapOwners(codeowners, CODEOWNERS_PLACES.getFirst(), files);
    }

    /**
     * Who owns the file or folder at root-relative {@code rel} ({@code ""}: the root), or null when no owner file
     * names anyone for it. Reads {@code OWNERS} files on the way up.
     */
    @Nullable
    Owners owners(String rel, boolean directory) {
        List<Rule> rules = codeowners;
        if (rules != null) {
            // The project itself is owned as a file at its top would be: by the "*" default, not by "/pkg/".
            String asked = rel.isEmpty() ? PROJECT_PROBE : rel;
            for (int i = rules.size() - 1; i >= 0; i--) {
                Rule rule = rules.get(i);
                if (matches(rule, asked, directory && !rel.isEmpty())) {
                    return rule.owners().isEmpty() ? null : new Owners(rule.owners(), List.of(), codeownersSource);
                }
            }
        }
        String folder = directory ? rel : parent(rel);
        while (true) {
            Owners found = folderOwners(folder);
            if (found != null) {
                return found;
            }
            if (folder.isEmpty()) {
                return null;
            }
            folder = parent(folder);
        }
    }

    private @Nullable Owners folderOwners(String folder) {
        Object known = folderOwners.get(folder);
        if (known != null) {
            return known == NONE ? null : (Owners) known;
        }
        String source = folder.isEmpty() ? "OWNERS" : folder + "/OWNERS";
        String text = read.apply(source);
        Owners owners = text == null ? null : parseOwnersFile(text, source);
        folderOwners.put(folder, owners == null ? NONE : owners);
        return owners;
    }

    // --- CODEOWNERS ------------------------------------------------------------------------------------------

    /** The rules of a {@code CODEOWNERS} file, in file order. Comments, blank lines and GitLab sections are skipped. */
    static List<Rule> parseCodeowners(String text) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = stripComment(raw).strip();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("^[")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            List<String> owners = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].contains("@")) {
                    owners.add(parts[i]);
                }
            }
            Rule rule = rule(parts[0], owners);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) {
            return "";
        }
        int hash = line.indexOf(" #");
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * A {@code CODEOWNERS} pattern as a rule. Gitignore-like: a leading or inner {@code /} anchors it at the root,
     * otherwise it matches at any depth; a trailing {@code /} matches a folder (and what is in it); {@code *} stays
     * within one name, {@code **} crosses folders. A pattern ending in {@code /*} owns that folder's own files only,
     * not those of its subfolders, as GitHub documents.
     */
    static @Nullable Rule rule(String pattern, List<String> owners) {
        String body = pattern;
        boolean nested = !body.endsWith("/*");
        boolean directoryOnly = body.endsWith("/");
        while (body.endsWith("/") && body.length() > 1) {
            body = body.substring(0, body.length() - 1);
        }
        boolean anchored = body.startsWith("/") || body.contains("/");
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        if (body.isEmpty() || body.equals("/")) {
            return null;
        }
        StringBuilder regex = new StringBuilder(anchored ? "" : "(?:.*/)?");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '*' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                boolean slashAfter = i + 2 < body.length() && body.charAt(i + 2) == '/';
                regex.append(slashAfter ? "(?:.*/)?" : ".*");
                i += slashAfter ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '\\' && i + 1 < body.length()) {
                regex.append(Pattern.quote(String.valueOf(body.charAt(++i))));
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Rule(pattern, Pattern.compile(regex.toString()), directoryOnly, nested, owners);
    }

    /** Whether {@code rule} applies to the path at root-relative {@code rel}: to it, or to a folder above it. */
    static boolean matches(Rule rule, String rel, boolean directory) {
        if (rel.isEmpty()) {
            return false;
        }
        if ((directory || !rule.directoryOnly()) && rule.regex().matcher(rel).matches()) {
            return true;
        }
        if (!rule.nested()) {
            return false; // "docs/*" owns docs/a.md, and not docs/b/c.md
        }
        for (String folder = parent(rel); !folder.isEmpty(); folder = parent(folder)) {
            if (rule.regex().matcher(folder).matches()) {
                return true;
            }
        }
        return false;
    }

    // --- OWNERS ----------------------------------------------------------------------------------------------

    /**
     * The approvers and reviewers a Kubernetes-style {@code OWNERS} file names at its top level or under the
     * {@code ".*"} filter (filters for other file patterns, emeritus lists and labels are left out); null when it names
     * none. A small YAML reader: block lists ({@code - name}) and flow lists ({@code [a, b]}).
     */
    static @Nullable Owners parseOwnersFile(String text, String source) {
        List<String> approvers = new ArrayList<>();
        List<String> reviewers = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = stripComment(raw);
            String content = line.strip();
            if (content.isEmpty() || content.equals("---")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (content.startsWith("- ") || content.equals("-")) {
                List<String> into = listFor(keys, approvers, reviewers);
                String item = unquote(content.substring(1).strip());
                if (into != null && !item.isEmpty()) {
                    into.add(item);
                }
                continue;
            }
            int colon = keyColon(content);
            if (colon < 0) {
                continue;
            }
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                keys.removeLast();
            }
            keys.add(unquote(content.substring(0, colon).strip()));
            indents.add(indent);
            String value = content.substring(colon + 1).strip();
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> into = listFor(keys, approvers, reviewers);
                if (into != null) {
                    for (String item : value.substring(1, value.length() - 1).split(",")) {
                        String name = unquote(item.strip());
                        if (!name.isEmpty()) {
                            into.add(name);
                        }
                    }
                }
            }
        }
        if (approvers.isEmpty() && reviewers.isEmpty()) {
            return null;
        }
        return new Owners(distinct(approvers), distinct(reviewers), source);
    }

    /** Where an item under {@code keys} goes: approvers, reviewers, or nowhere. */
    private static @Nullable List<String> listFor(List<String> keys, List<String> approvers, List<String> reviewers) {
        String last = keys.isEmpty() ? "" : keys.getLast();
        boolean topLevel = keys.size() == 1;
        boolean anyFile =
                keys.size() == 3 && keys.get(0).equals("filters") && keys.get(1).equals(".*");
        if (!topLevel && !anyFile) {
            return null;
        }
        return switch (last) {
            case "approvers" -> approvers;
            case "reviewers" -> reviewers;
            default -> null;
        };
    }

    /** The colon that ends a YAML key (outside quotes), or -1. */
    private static int keyColon(String content) {
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == content.length() || content.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).replace("\\\\", "\\");
        }
        return value;
    }

    private static List<String> distinct(List<String> names) {
        return names.stream().distinct().toList();
    }

    private static String parent(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    private static @Nullable String read(Path root, String rel) {
        try {
            Path file = root.resolve(rel);
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_READ) {
                return null;
            }
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
