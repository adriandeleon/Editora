package org.adriandeleon.editora.languages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class TextMateGrammar {
    private static final int REGEX_FLAGS = Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private final String name;
    private final String scopeName;
    private final Set<String> fileTypes;
    private final List<Rule> patterns;
    private final Map<String, List<Rule>> repository;

    private TextMateGrammar(String name,
                            String scopeName,
                            Collection<String> fileTypes,
                            List<Rule> patterns,
                            Map<String, List<Rule>> repository) {
        this.name = name == null || name.isBlank() ? "TextMate" : name.strip();
        this.scopeName = scopeName == null ? "" : scopeName.strip();
        this.fileTypes = normalizeFileTypes(fileTypes);
        this.patterns = List.copyOf(patterns);
        this.repository = Map.copyOf(repository);
    }

    static TextMateGrammar fromPlist(Map<String, Object> plist) {
        String name = asString(plist.get("name"));
        String scopeName = asString(plist.get("scopeName"));
        List<Rule> patterns = parseRuleList(plist.get("patterns"));
        Map<String, List<Rule>> repository = parseRepository(plist.get("repository"));
        return new TextMateGrammar(name, scopeName, asStringList(plist.get("fileTypes")), patterns, repository);
    }

    String name() {
        return name;
    }

    String scopeName() {
        return scopeName;
    }

    Set<String> fileTypes() {
        return fileTypes;
    }

    List<Rule> patterns() {
        return patterns;
    }

    List<Rule> repository(String name) {
        return repository.getOrDefault(name, List.of());
    }

    private static Set<String> normalizeFileTypes(Collection<String> fileTypes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (fileTypes != null) {
            for (String fileType : fileTypes) {
                if (fileType == null || fileType.isBlank()) {
                    continue;
                }
                normalized.add(fileType.strip().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, List<Rule>> parseRepository(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, List<Rule>> repository = new LinkedHashMap<>();
        map.forEach((key, entry) -> repository.put(String.valueOf(key), parseRepositoryEntry(entry)));
        return repository;
    }

    private static List<Rule> parseRepositoryEntry(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ruleMap = castMap(map);
            if (ruleMap.containsKey("patterns") && !containsRuleDefinition(ruleMap)) {
                return parseRuleList(ruleMap.get("patterns"));
            }
            return List.of(parseRule(ruleMap));
        }
        if (value instanceof List<?> list) {
            List<Rule> rules = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> entryMap) {
                    rules.add(parseRule(castMap(entryMap)));
                }
            }
            return rules;
        }
        return List.of();
    }

    private static boolean containsRuleDefinition(Map<String, Object> ruleMap) {
        return ruleMap.containsKey("match") || ruleMap.containsKey("begin") || ruleMap.containsKey("include") || ruleMap.containsKey("name");
    }

    private static List<Rule> parseRuleList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                rules.add(parseRule(castMap(map)));
            }
        }
        return List.copyOf(rules);
    }

    private static Rule parseRule(Map<String, Object> ruleMap) {
        String include = asString(ruleMap.get("include"));
        if (!include.isBlank()) {
            return new IncludeRule(include);
        }

        String name = asString(ruleMap.get("name"));
        String contentName = asString(ruleMap.get("contentName"));
        String match = asString(ruleMap.get("match"));
        if (!match.isBlank()) {
            return new MatchRule(name, Pattern.compile(match, REGEX_FLAGS));
        }

        String begin = asString(ruleMap.get("begin"));
        String end = asString(ruleMap.get("end"));
        if (!begin.isBlank() && !end.isBlank()) {
            return new BeginEndRule(
                    name,
                    contentName,
                    Pattern.compile(begin, REGEX_FLAGS),
                    Pattern.compile(end, REGEX_FLAGS),
                    parseRuleList(ruleMap.get("patterns"))
            );
        }

        return new NoOpRule();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null) {
                values.add(String.valueOf(entry));
            }
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    sealed interface Rule permits MatchRule, BeginEndRule, IncludeRule, NoOpRule {
        String name();
    }

    record MatchRule(String name, Pattern pattern) implements Rule {
        MatchRule {
            name = name == null ? "" : name;
        }
    }

    record BeginEndRule(String name, String contentName, Pattern beginPattern, Pattern endPattern, List<Rule> patterns) implements Rule {
        BeginEndRule {
            name = name == null ? "" : name;
            contentName = contentName == null ? "" : contentName;
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
        }
    }

    record IncludeRule(String include) implements Rule {
        @Override
        public String name() {
            return include;
        }
    }

    record NoOpRule() implements Rule {
        @Override
        public String name() {
            return "";
        }
    }
}

