package org.adriandeleon.editora.languages;

import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

final class TextMateScopeStyleMapper {
    private static final Map<String, Collection<String>> CACHE = new ConcurrentHashMap<>();

    private TextMateScopeStyleMapper() {
    }

    static Collection<String> map(String scopeName) {
        if (scopeName == null || scopeName.isBlank()) {
            return List.of();
        }

        return CACHE.computeIfAbsent(scopeName, TextMateScopeStyleMapper::computeStyles);
    }

    private static Collection<String> computeStyles(String scopeName) {
        String scope = scopeName.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> styles = new LinkedHashSet<>();

        if (scope.contains("comment")) {
            styles.add("comment");
        }
        if (scope.contains("string") || scope.contains("markup.raw")) {
            styles.add("string");
        }
        if (scope.contains("keyword") || scope.contains("storage")) {
            styles.add("keyword");
        }
        if (scope.contains("constant.numeric") || scope.contains("number")) {
            styles.add("number");
        }
        if (scope.contains("constant") && !styles.contains("number")) {
            styles.add("constant");
        }
        if (scope.contains("entity.name.function") || scope.contains("support.function")) {
            styles.add("function");
        }
        if (scope.contains("entity.name.type") || scope.contains("support.type")) {
            styles.add("type");
        }
        if (scope.contains("variable") || scope.contains("parameter")) {
            styles.add("variable");
        }
        if (scope.contains("entity.other.attribute-name")) {
            styles.add("attribute");
        }
        if (scope.contains("entity.name.tag") || scope.contains("meta.tag")) {
            styles.add("tag");
        }
        if (scope.contains("punctuation") || scope.contains("operator")) {
            styles.add("punctuation");
        }
        if (scope.contains("markup.heading")) {
            styles.add("heading");
        }
        if (scope.contains("markup.bold")) {
            styles.add("bold");
        }
        if (scope.contains("markup.italic")) {
            styles.add("italic");
        }
        if (scope.contains("invalid")) {
            styles.add("invalid");
        }

        return List.copyOf(styles);
    }
}

