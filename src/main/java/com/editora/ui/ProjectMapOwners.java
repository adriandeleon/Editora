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
 * Who owns a file or folder of the project, from the project's own files (Map explore position 4): a GitHub- or
 * GitLab-style {@code CODEOWNERS} (the first of {@code .github/}, the root, {@code docs/} and {@code .gitlab/}; the
 * last matching line wins, within each GitLab {@code [Section]}, whose default owners go to its owner-less lines,
 * and the sections' owners together; Map review 4, B6) and Kubernetes-style {@code OWNERS} files (their
 * {@code approvers} and {@code reviewers}, top-level or under the {@code ".*"} filter; the nearest folder's file that
 * names someone wins, and when it names only reviewers the approvers come from the nearest file above that names
 * some, as approval bubbles up there; B3). A path that a {@code CODEOWNERS} line matches takes that line; otherwise
 * its {@code OWNERS}. No network, no GitHub API: alias and team names are shown as written.
 *
 * <p>{@link #owners} reads {@code OWNERS} files as it walks up (each folder's at most once): call it off the FX
 * thread, from one thread at a time. The parsing and matching are pure.
 */
final class ProjectMapOwners {

    /** The places GitHub looks for {@code CODEOWNERS}, in its order. */
    static final List<String> CODEOWNERS_PLACES =
            List.of(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS", ".gitlab/CODEOWNERS");
    /** A name no file has, standing for the project's root when CODEOWNERS lines are matched. */
    private static final String PROJECT_PROBE = "\u0000";
    /** The largest owner file read. */
    private static final long MAX_READ = 512 * 1024;

    /**
     * Who owns a path: approvers (or CODEOWNERS' owners) and reviewers, the file that names the approvers and the one
     * that names the reviewers (root-relative; the same file unless the nearest {@code OWNERS} names only reviewers).
     * No approvers: only reviewers are known, and {@code source} is empty.
     */
    record Owners(List<String> approvers, List<String> reviewers, String source, String reviewersSource) {
        Owners {
            approvers = List.copyOf(approvers);
            reviewers = List.copyOf(reviewers);
        }

        Owners(List<String> approvers, List<String> reviewers, String source) {
            this(approvers, reviewers, source, source);
        }
    }

    /**
     * One {@code CODEOWNERS} line: its pattern as a regular expression, and its owners (none: explicitly unowned);
     * {@code section} numbers its GitLab section (0: before any).
     */
    record Rule(
            String pattern, Pattern regex, boolean directoryOnly, boolean nested, List<String> owners, int section) {}

    /** A GitLab section header: {@code [Name]}, {@code ^[Optional]}, {@code [Name][2]}, then its default owners. */
    private static final Pattern SECTION = Pattern.compile("^\\^?\\[([^\\]]+)\\](?:\\[\\d+\\])?(.*)$");

    private final @Nullable List<Rule> codeowners;
    private final String codeownersSource;
    private final Function<String, @Nullable String> read;
    /** Each folder's own {@code OWNERS} verdict by root-relative path; {@link #NONE} for no file or nobody named. */
    private final Map<String, Object> folderOwners = new HashMap<>();
    /** Every owner file looked for, by root-relative path, with what it held then (null: no such file). */
    private final Map<String, @Nullable String> seen;

    private static final Object NONE = new Object();

    private ProjectMapOwners(
            @Nullable String codeownersText,
            String codeownersSource,
            Function<String, @Nullable String> read,
            Map<String, @Nullable String> seen) {
        this.codeowners = codeownersText == null ? null : parseCodeowners(codeownersText);
        this.codeownersSource = codeownersSource;
        this.read = read;
        this.seen = seen;
    }

    /** The owner files of {@code root}: its {@code CODEOWNERS} now, its {@code OWNERS} files as they are asked for. */
    static ProjectMapOwners load(Path root) {
        return load(rel -> read(root, rel));
    }

    /** {@link #load(Path)} over files given by root-relative path. */
    static ProjectMapOwners load(Function<String, @Nullable String> read) {
        Map<String, @Nullable String> seen = new HashMap<>();
        for (String place : CODEOWNERS_PLACES) {
            String text = read.apply(place);
            seen.put(place, text);
            if (text != null) {
                return new ProjectMapOwners(text, place, read, seen);
            }
        }
        return new ProjectMapOwners(null, "", read, seen);
    }

    /** For tests: {@code CODEOWNERS} text (or null) and the owner files by root-relative path. */
    static ProjectMapOwners of(@Nullable String codeowners, Function<String, @Nullable String> files) {
        return new ProjectMapOwners(codeowners, CODEOWNERS_PLACES.getFirst(), files, new HashMap<>());
    }

    /**
     * Whether an owner file these rules read (or looked for and did not find) in one of {@code folders}
     * (root-relative) now reads differently: the rules are stale and should be loaded again (Map review 4, B9).
     * Reads those files again.
     */
    boolean changedIn(java.util.Collection<String> folders) {
        for (Map.Entry<String, @Nullable String> file : new ArrayList<>(seen.entrySet())) {
            if (folders.contains(parent(file.getKey()))
                    && !java.util.Objects.equals(read.apply(file.getKey()), file.getValue())) {
                return true;
            }
        }
        return false;
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
            java.util.Set<Integer> decided = new java.util.HashSet<>();
            java.util.LinkedHashSet<String> named = new java.util.LinkedHashSet<>();
            List<Integer> order = new ArrayList<>();
            Map<Integer, List<String>> bySection = new HashMap<>();
            for (int i = rules.size() - 1; i >= 0; i--) {
                Rule rule = rules.get(i);
                // The last match wins within a section; each GitLab section adds its own (GitHub has one section).
                if (!decided.contains(rule.section()) && matches(rule, asked, directory && !rel.isEmpty())) {
                    decided.add(rule.section());
                    order.add(rule.section());
                    bySection.put(rule.section(), rule.owners());
                }
            }
            if (!decided.isEmpty()) {
                order.sort(null); // file order
                for (int section : order) {
                    named.addAll(bySection.getOrDefault(section, List.of()));
                }
                return named.isEmpty() ? null : new Owners(List.copyOf(named), List.of(), codeownersSource);
            }
        }
        String folder = directory ? rel : parent(rel);
        Owners reviewersOnly = null;
        while (true) {
            Owners found = folderOwners(folder);
            if (found != null && reviewersOnly == null && !found.approvers().isEmpty()) {
                return found;
            }
            if (found != null && reviewersOnly == null) {
                reviewersOnly = found; // reviewers here; approval bubbles up to the nearest file with approvers
            } else if (found != null
                    && reviewersOnly != null
                    && !found.approvers().isEmpty()) {
                return new Owners(found.approvers(), reviewersOnly.reviewers(), found.source(), reviewersOnly.source());
            }
            if (folder.isEmpty()) {
                return reviewersOnly == null
                        ? null
                        : new Owners(List.of(), reviewersOnly.reviewers(), "", reviewersOnly.source());
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
        seen.put(source, text);
        Owners owners = text == null ? null : parseOwnersFile(text, source);
        folderOwners.put(folder, owners == null ? NONE : owners);
        return owners;
    }

    // --- CODEOWNERS ------------------------------------------------------------------------------------------

    /**
     * The rules of a {@code CODEOWNERS} file, in file order. Comments and blank lines are skipped. A GitLab section
     * header ({@code [Name] @default-owners}) starts a section: its lines without owners take its default owners, and
     * sections of the same name (any case) are one.
     */
    static List<Rule> parseCodeowners(String text) {
        List<Rule> rules = new ArrayList<>();
        Map<String, Integer> sections = new HashMap<>();
        Map<Integer, List<String>> sectionDefaults = new HashMap<>();
        int section = 0;
        List<String> defaults = List.of();
        for (String raw : text.split("\\R")) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                String name = header.group(1).strip().toLowerCase(java.util.Locale.ROOT);
                Integer known = sections.get(name);
                section = known != null ? known : sections.size() + 1;
                sections.putIfAbsent(name, section);
                List<String> named = ownersIn(header.group(2).strip().split("\\s+"), 0);
                if (!named.isEmpty()) {
                    sectionDefaults.put(section, named);
                }
                defaults = sectionDefaults.getOrDefault(section, List.of()); // a repeated header keeps its defaults
                continue;
            }
            String[] parts = line.split("\\s+");
            List<String> owners = ownersIn(parts, 1);
            Rule rule = rule(parts[0], owners.isEmpty() ? defaults : owners, section);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static List<String> ownersIn(String[] parts, int from) {
        List<String> owners = new ArrayList<>();
        for (int i = from; i < parts.length; i++) {
            if (parts[i].contains("@")) {
                owners.add(parts[i]);
            }
        }
        return owners;
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
        return rule(pattern, owners, 0);
    }

    private static @Nullable Rule rule(String pattern, List<String> owners, int section) {
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
        return new Rule(pattern, Pattern.compile(regex.toString()), directoryOnly, nested, owners, section);
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
