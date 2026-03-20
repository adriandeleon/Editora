package org.adriandeleon.editora.settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ReadOnlyOpenRules {
    private ReadOnlyOpenRules() {
    }

    public static List<String> effectivePatterns(EditorSettings settings) {
        Objects.requireNonNull(settings);
        LinkedHashSet<String> patterns = new LinkedHashSet<>(EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS);
        patterns.addAll(settings.readOnlyOpenPatterns());
        return List.copyOf(patterns);
    }

    public static boolean shouldOpenReadOnly(Path file, EditorSettings settings) {
        if (file == null || settings == null || !settings.readOnlyOpenEnabled()) {
            return false;
        }

        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        for (String pattern : effectivePatterns(settings)) {
            if (matchesPattern(lowerFileName, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPattern(String lowerFileName, String configuredPattern) {
        String pattern = configuredPattern == null ? "" : configuredPattern.strip().toLowerCase(Locale.ROOT);
        if (pattern.isBlank()) {
            return false;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return lowerFileName.equals(pattern);
        }
        return Pattern.compile(globToRegex(pattern)).matcher(lowerFileName).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> regex.append('\\').append(c);
                case '\\' -> regex.append("\\\\");
                case '[', ']', '{', '}' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    public static String normalizePatternText(String patternText) {
        if (patternText == null || patternText.isBlank()) {
            return "";
        }
        String[] tokens = patternText.split("[\\r\\n,;]");
        List<String> normalized = new ArrayList<>();
        for (String token : tokens) {
            String candidate = token.strip();
            if (!candidate.isBlank()) {
                normalized.add(candidate);
            }
        }
        return String.join(", ", new LinkedHashSet<>(normalized));
    }
}

