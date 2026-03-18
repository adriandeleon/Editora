package org.adriandeleon.editora.editor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FindFileSupport {

    private FindFileSupport() {
    }

    public record Match(Path path, String displayPath, String parentPath, boolean directory, boolean recent, boolean open) {
        public Match {
            path = normalizeRoot(path);
            displayPath = displayPath == null ? "" : displayPath;
            parentPath = parentPath == null ? "" : parentPath;
        }
    }

    public record Preview(String title, String content, boolean directory, boolean truncated) {
        public Preview {
            title = title == null ? "Preview" : title;
            content = content == null ? "" : content;
        }
    }

    public static List<Match> rankMatches(Path workspaceRoot,
                                          Collection<Path> workspaceEntries,
                                          Collection<Path> recentFiles,
                                          Collection<Path> openFiles,
                                          String rawQuery,
                                          int maxResults) {
        Path normalizedWorkspaceRoot = normalizeRoot(workspaceRoot);
        Path normalizedHome = normalizeHomeDirectory();
        Map<Path, Integer> recentRanks = indexPaths(recentFiles);
        Set<Path> openPathSet = normalizePaths(openFiles);

        Map<Path, MatchDetails> matches = new LinkedHashMap<>();
        Stream.of(workspaceEntries, recentFiles, openFiles)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .forEach(path -> matches.computeIfAbsent(path,
                        candidate -> buildMatchDetails(normalizedWorkspaceRoot, normalizedHome, candidate,
                                recentRanks.containsKey(candidate), openPathSet.contains(candidate))));

        String query = normalizeInput(rawQuery).strip().toLowerCase(Locale.ROOT);
        int limit = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;

        return matches.values().stream()
                .map(details -> score(details, query, recentRanks.getOrDefault(details.match().path(), Integer.MAX_VALUE)))
                .filter(scored -> scored.score() < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt(ScoredMatch::score)
                        .thenComparingInt(ScoredMatch::recentRank)
                        .thenComparingInt(ScoredMatch::directoryPriority)
                        .thenComparingInt(ScoredMatch::pathDepth)
                        .thenComparingInt(ScoredMatch::pathLength)
                        .thenComparing(scored -> scored.match().displayPath(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .map(ScoredMatch::match)
                .toList();
    }

    public static Optional<String> completeInput(Path workspaceRoot, String rawInput, List<Match> matches) {
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }

        String input = normalizeInput(rawInput);
        boolean absoluteInput = input.startsWith("/");
        boolean homeRelativeInput = input.startsWith("~");
        Path normalizedWorkspaceRoot = normalizeRoot(workspaceRoot);
        Path normalizedHome = normalizeHomeDirectory();

        List<String> completions = matches.stream()
                .map(match -> completionText(match, normalizedWorkspaceRoot, normalizedHome, absoluteInput, homeRelativeInput))
                .filter(candidate -> input.isBlank() || startsWithIgnoreCase(candidate, input))
                .toList();
        if (completions.isEmpty()) {
            return Optional.empty();
        }
        if (completions.size() == 1) {
            return Optional.of(completions.getFirst());
        }

        String commonPrefix = commonPrefix(completions);
        return commonPrefix.length() > input.length() ? Optional.of(commonPrefix) : Optional.empty();
    }

    public static String deleteTrailingPathSegment(String rawInput) {
        String input = normalizeInput(rawInput).strip();
        if (input.isBlank()) {
            return "";
        }
        if (input.equals("/") || input.equals("~/")) {
            return input;
        }
        if (input.equals("~")) {
            return "~/";
        }

        String working = input.endsWith("/") && input.length() > 1
                ? input.substring(0, input.length() - 1)
                : input;
        if (working.equals("~")) {
            return "~/";
        }

        int lastSlash = working.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        if (lastSlash == 0) {
            return "/";
        }
        if (working.startsWith("~/") && lastSlash <= 1) {
            return "~/";
        }
        return working.substring(0, lastSlash + 1);
    }

    public static Optional<Path> resolvePath(Path workspaceRoot, String rawInput, Path homeDirectory) {
        String input = normalizeInput(rawInput).strip();
        if (input.isBlank()) {
            return Optional.empty();
        }

        try {
            Path normalizedHome = normalizeRoot(homeDirectory == null ? Path.of(System.getProperty("user.home", ".")) : homeDirectory);
            Path resolved;
            if (input.equals("~") || input.equals("~/")) {
                resolved = normalizedHome;
            } else if (input.startsWith("~/")) {
                resolved = normalizedHome.resolve(input.substring(2));
            } else {
                Path candidate = Path.of(input);
                resolved = candidate.isAbsolute() ? candidate : normalizeRoot(workspaceRoot).resolve(candidate);
            }
            return Optional.of(resolved.toAbsolutePath().normalize());
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }

    public static Optional<Match> resolveExistingMatch(Path workspaceRoot,
                                                       String rawInput,
                                                       Collection<Match> matches,
                                                       Path homeDirectory) {
        Optional<Path> resolvedPath = resolvePath(workspaceRoot, rawInput, homeDirectory);
        if (resolvedPath.isEmpty()) {
            return Optional.empty();
        }

        Path path = resolvedPath.get();
        if (matches != null) {
            for (Match match : matches) {
                if (match != null && match.path().equals(path)) {
                    return Optional.of(match);
                }
            }
        }

        boolean directory = Files.isDirectory(path);
        if (!directory && !Files.isRegularFile(path)) {
            return Optional.empty();
        }

        return Optional.of(new Match(
                path,
                presentPath(workspaceRoot, path, directory),
                buildParentPath(workspaceRoot, path),
                directory,
                false,
                false
        ));
    }

    public static String presentPath(Path workspaceRoot, Path path, boolean directory) {
        Path normalizedPath = normalizeRoot(path);
        Path normalizedWorkspaceRoot = normalizeRoot(workspaceRoot);
        Path normalizedHome = normalizeHomeDirectory();

        String display;
        if (normalizedPath.startsWith(normalizedWorkspaceRoot)) {
            Path relative = normalizedWorkspaceRoot.relativize(normalizedPath);
            display = normalizeInput(relative.toString());
        } else if (normalizedPath.startsWith(normalizedHome)) {
            Path relative = normalizedHome.relativize(normalizedPath);
            display = relative.getNameCount() == 0 ? "~/" : "~/" + normalizeInput(relative.toString());
        } else {
            display = normalizeInput(normalizedPath.toString());
        }

        if (directory && !display.endsWith("/")) {
            display += "/";
        }
        return display;
    }

    public static Preview buildPreview(Path workspaceRoot, Match match, int maxLines, int maxCharacters) {
        if (match == null) {
            return new Preview(
                    "Preview",
                    "Highlight a file candidate to preview it here.",
                    false,
                    false
            );
        }

        int previewLines = Math.max(1, maxLines);
        int previewCharacters = Math.max(64, maxCharacters);
        String title = presentPath(workspaceRoot, match.path(), match.directory());

        if (match.directory()) {
            return new Preview(
                    title,
                    String.join("\n",
                            "Directory preview",
                            "",
                            "Press Enter to descend into this directory.",
                            "Tab completes the current path segment.",
                            "Parent: " + match.parentPath()),
                    true,
                    false
            );
        }

        if (!Files.exists(match.path())) {
            return new Preview(title, "Preview unavailable.\n\nFile not found.", false, false);
        }
        if (!Files.isRegularFile(match.path())) {
            return new Preview(title, "Preview unavailable.\n\nOnly regular files can be previewed.", false, false);
        }

        try (BufferedReader reader = Files.newBufferedReader(match.path(), StandardCharsets.UTF_8)) {
            StringBuilder content = new StringBuilder();
            boolean truncated = false;
            int linesRead = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (linesRead > 0) {
                    if (content.length() + 1 > previewCharacters) {
                        truncated = true;
                        break;
                    }
                    content.append('\n');
                }

                int remaining = previewCharacters - content.length();
                if (line.length() > remaining) {
                    content.append(line, 0, Math.max(0, remaining));
                    truncated = true;
                    break;
                }

                content.append(line);
                linesRead++;
                if (linesRead >= previewLines) {
                    if (reader.readLine() != null) {
                        truncated = true;
                    }
                    break;
                }
            }

            if (content.isEmpty() && Files.size(match.path()) == 0) {
                content.append("(Empty file)");
            }
            if (truncated) {
                content.append("\n\n… Preview truncated.");
            }

            return new Preview(title, content.toString(), false, truncated);
        } catch (IOException exception) {
            return new Preview(
                    title,
                    "Preview unavailable.\n\nEditora could not read this file as UTF-8 text.",
                    false,
                    false
            );
        }
    }

    private static MatchDetails buildMatchDetails(Path workspaceRoot, Path homeDirectory, Path path, boolean recent, boolean open) {
        boolean directory = Files.isDirectory(path);
        String displayPath = presentPath(workspaceRoot, path, directory);
        String parentPath = buildParentPath(workspaceRoot, path);
        Match match = new Match(path, displayPath, parentPath, directory, recent, open);
        return new MatchDetails(
                match,
                trimTrailingSlash(match.displayPath()).toLowerCase(Locale.ROOT),
                normalizeInput(path.toString()).toLowerCase(Locale.ROOT),
                homeRelativePresentation(homeDirectory, path).toLowerCase(Locale.ROOT)
        );
    }

    private static ScoredMatch score(MatchDetails details, String query, int recentRank) {
        Match match = details.match();
        if (query.isBlank()) {
            int base = match.recent() ? 0 : match.open() ? 25 : 100;
            return new ScoredMatch(match, base, recentRank, match.directory() ? 0 : 1,
                    pathDepth(match.displayPath()), match.displayPath().length());
        }

        String lastSegment = lastSegment(query);
        int score = Math.min(
                scoreAgainst(details.displayLower(), query, lastSegment, match.directory()),
                Math.min(
                        scoreAgainst(details.absoluteLower(), query, lastSegment, match.directory()),
                        details.homeRelativeLower().isBlank()
                                ? Integer.MAX_VALUE
                                : scoreAgainst(details.homeRelativeLower(), query, lastSegment, match.directory())
                )
        );
        if (score == Integer.MAX_VALUE) {
            return new ScoredMatch(match, Integer.MAX_VALUE, recentRank, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }
        int effectiveRecentRank = match.recent() ? recentRank : Integer.MAX_VALUE;
        return new ScoredMatch(match, score, effectiveRecentRank, match.directory() ? 0 : 1,
                pathDepth(match.displayPath()), match.displayPath().length());
    }

    private static int scoreAgainst(String candidate, String query, String lastSegment, boolean directory) {
        if (candidate.isBlank()) {
            return Integer.MAX_VALUE;
        }
        if (candidate.equals(query)) {
            return directory ? 0 : 1;
        }
        if (candidate.startsWith(query)) {
            return directory ? 1 : 2;
        }

        String trimmedCandidate = trimTrailingSlash(candidate);
        String fileName = lastSegment(trimmedCandidate);
        if (!lastSegment.isBlank()) {
            if (fileName.equals(lastSegment)) {
                return 3;
            }
            if (fileName.startsWith(lastSegment)) {
                return 4;
            }
            if (segmentStartsWith(trimmedCandidate, lastSegment)) {
                return 5;
            }
        }
        if (trimmedCandidate.contains(query)) {
            return 6;
        }
        return isSubsequenceMatch(trimmedCandidate, query) ? 7 : Integer.MAX_VALUE;
    }

    private static String buildParentPath(Path workspaceRoot, Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return "Workspace";
        }
        String display = presentPath(workspaceRoot, parent, true);
        if (display.endsWith("/")) {
            display = display.substring(0, display.length() - 1);
        }
        return display.isBlank() ? "Workspace" : display;
    }

    private static String completionText(Match match,
                                         Path workspaceRoot,
                                         Path homeDirectory,
                                         boolean absoluteInput,
                                         boolean homeRelativeInput) {
        if (absoluteInput) {
            return normalizeInput(match.path().toString()) + (match.directory() ? "/" : "");
        }
        if (homeRelativeInput) {
            String homeRelative = homeRelativePresentation(homeDirectory, match.path());
            if (!homeRelative.isBlank()) {
                return homeRelative + (match.directory() && !homeRelative.endsWith("/") ? "/" : "");
            }
        }
        String display = presentPath(workspaceRoot, match.path(), match.directory());
        return match.directory() && !display.endsWith("/") ? display + "/" : display;
    }

    private static String homeRelativePresentation(Path homeDirectory, Path path) {
        Path normalizedPath = normalizeRoot(path);
        if (!normalizedPath.startsWith(homeDirectory)) {
            return "";
        }
        Path relative = homeDirectory.relativize(normalizedPath);
        return relative.getNameCount() == 0 ? "~/" : "~/" + normalizeInput(relative.toString());
    }

    private static Map<Path, Integer> indexPaths(Collection<Path> paths) {
        Map<Path, Integer> result = new LinkedHashMap<>();
        if (paths == null) {
            return result;
        }
        int index = 0;
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            result.putIfAbsent(path.toAbsolutePath().normalize(), index++);
        }
        return result;
    }

    private static Set<Path> normalizePaths(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        return paths.stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toSet());
    }

    private static String commonPrefix(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        List<String> normalized = new ArrayList<>(values);
        String prefix = normalized.getFirst();
        for (int index = 1; index < normalized.size(); index++) {
            prefix = commonPrefix(prefix, normalized.get(index));
            if (prefix.isEmpty()) {
                break;
            }
        }
        return prefix;
    }

    private static String commonPrefix(String left, String right) {
        int maxLength = Math.min(left.length(), right.length());
        int index = 0;
        while (index < maxLength && Character.toLowerCase(left.charAt(index)) == Character.toLowerCase(right.charAt(index))) {
            index++;
        }
        return left.substring(0, index);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean segmentStartsWith(String candidate, String querySegment) {
        if (querySegment.isBlank()) {
            return false;
        }
        for (String segment : trimTrailingSlash(candidate).split("/")) {
            if (segment.startsWith(querySegment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSubsequenceMatch(String candidate, String query) {
        if (query.isBlank()) {
            return true;
        }
        int queryIndex = 0;
        for (int candidateIndex = 0; candidateIndex < candidate.length() && queryIndex < query.length(); candidateIndex++) {
            if (candidate.charAt(candidateIndex) == query.charAt(queryIndex)) {
                queryIndex++;
            }
        }
        return queryIndex == query.length();
    }

    private static String lastSegment(String value) {
        String normalized = trimTrailingSlash(value);
        int separatorIndex = normalized.lastIndexOf('/');
        return separatorIndex < 0 ? normalized : normalized.substring(separatorIndex + 1);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank() || value.equals("/") || value.equals("~/")) {
            return value == null ? "" : value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int pathDepth(String displayPath) {
        String normalized = trimTrailingSlash(displayPath);
        if (normalized.isBlank()) {
            return 0;
        }
        return normalized.split("/").length;
    }

    private static String normalizeInput(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static Path normalizeRoot(Path path) {
        return (path == null ? Path.of("") : path).toAbsolutePath().normalize();
    }

    private static Path normalizeHomeDirectory() {
        return normalizeRoot(Path.of(System.getProperty("user.home", ".")));
    }

    private record MatchDetails(Match match, String displayLower, String absoluteLower, String homeRelativeLower) {
    }

    private record ScoredMatch(Match match, int score, int recentRank, int directoryPriority, int pathDepth, int pathLength) {
    }
}
