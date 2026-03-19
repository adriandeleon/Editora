package org.adriandeleon.editora.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StatusBarSupport {
    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;
    private static final long GIB = 1024 * MIB;

    private StatusBarSupport() {
    }

    public static String formatDocumentStatus(String displayName, int lines, int characters, long utf8Bytes) {
        String resolvedDisplayName = displayName == null || displayName.isBlank() ? "Untitled" : displayName;
        return resolvedDisplayName
                + " · " + lines + " lines"
                + " · " + characters + " chars"
                + " · " + formatFileSize(utf8Bytes);
    }

    public static long utf8Size(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8).length;
    }

    public static List<BreadcrumbEntry> buildBreadcrumbEntries(Path workspaceRoot, Path filePath, String fallbackName) {
        String resolvedFallback = fallbackName == null || fallbackName.isBlank() ? "No document" : fallbackName;
        if (filePath == null) {
            return List.of(new BreadcrumbEntry(resolvedFallback, null));
        }

        Path normalizedFile = filePath.toAbsolutePath().normalize();
        Path normalizedWorkspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
        if (normalizedWorkspaceRoot != null && normalizedFile.startsWith(normalizedWorkspaceRoot)) {
            List<BreadcrumbEntry> segments = new ArrayList<>();
            Path currentPath = normalizedWorkspaceRoot;
            segments.add(new BreadcrumbEntry(displayPath(normalizedWorkspaceRoot), currentPath));
            Path relativePath = normalizedWorkspaceRoot.relativize(normalizedFile);
            for (Path segment : relativePath) {
                currentPath = currentPath.resolve(segment).normalize();
                segments.add(new BreadcrumbEntry(segment.toString(), currentPath));
            }
            return List.copyOf(segments);
        }

        List<BreadcrumbEntry> segments = new ArrayList<>();
        Path root = normalizedFile.getRoot();
        if (root != null && !root.toString().isBlank()) {
            segments.add(new BreadcrumbEntry(root.toString(), root));
        }
        Path currentPath = root;
        for (Path segment : normalizedFile) {
            currentPath = currentPath == null ? segment : currentPath.resolve(segment).normalize();
            segments.add(new BreadcrumbEntry(segment.toString(), currentPath));
        }
        return segments.isEmpty() ? List.of(new BreadcrumbEntry(resolvedFallback, null)) : List.copyOf(segments);
    }

    public static String formatFileSize(long bytes) {
        long normalizedBytes = Math.max(0, bytes);
        if (normalizedBytes < KIB) {
            return normalizedBytes + " B";
        }
        if (normalizedBytes < MIB) {
            return formatScaledSize(normalizedBytes, KIB, "KB");
        }
        if (normalizedBytes < GIB) {
            return formatScaledSize(normalizedBytes, MIB, "MB");
        }
        return formatScaledSize(normalizedBytes, GIB, "GB");
    }

    private static String formatScaledSize(long bytes, long unitSize, String suffix) {
        double value = bytes / (double) unitSize;
        if (value >= 100) {
            return String.format(Locale.ROOT, "%.0f %s", value, suffix);
        }
        return String.format(Locale.ROOT, "%.1f %s", value, suffix);
    }

    private static String displayPath(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    public record BreadcrumbEntry(String label, Path path) {
        public BreadcrumbEntry {
            label = label == null || label.isBlank() ? "Unknown" : label;
            path = path == null ? null : path.toAbsolutePath().normalize();
        }

        @Override
        public String toString() {
            return label;
        }
    }
}

