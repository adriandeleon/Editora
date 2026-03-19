package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

final class TextMateTokenizer {
    private TextMateTokenizer() {
    }

    static StyleSpans<Collection<String>> computeHighlighting(TextMateGrammar grammar, String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            builder.add(List.of(), 0);
            return builder.create();
        }

        ArrayDeque<Context> stack = new ArrayDeque<>();
        Map<ExpansionKey, List<TextMateGrammar.Rule>> expansionCache = new HashMap<>();
        int position = 0;
        while (position < text.length()) {
            Context context = stack.peek();
            MatchCandidate endCandidate = context == null ? null : findEndCandidate(context.rule(), text, position);
            MatchCandidate nextCandidate = findNextCandidate(grammar,
                    context == null ? grammar.patterns() : context.rule().patterns(),
                    text,
                    position,
                    context == null ? Set.of() : Set.of(context.rule().name()),
                    expansionCache);

            if (endCandidate != null && (nextCandidate == null || endCandidate.start() <= nextCandidate.start())) {
                appendSpan(builder, context.contentStyles(), endCandidate.start() - position);
                if (endCandidate.end() <= endCandidate.start()) {
                    appendSpan(builder, context.contentStyles(), 1);
                    position = Math.min(text.length(), position + 1);
                    continue;
                }
                appendSpan(builder, TextMateScopeStyleMapper.map(context.rule().name()), endCandidate.end() - endCandidate.start());
                position = endCandidate.end();
                stack.pop();
                continue;
            }

            if (nextCandidate == null) {
                appendSpan(builder, context == null ? List.of() : context.contentStyles(), text.length() - position);
                break;
            }

            appendSpan(builder, context == null ? List.of() : context.contentStyles(), nextCandidate.start() - position);
            if (nextCandidate.end() <= nextCandidate.start()) {
                appendSpan(builder, context == null ? List.of() : context.contentStyles(), 1);
                position = Math.min(text.length(), position + 1);
                continue;
            }

            if (nextCandidate.rule() instanceof TextMateGrammar.MatchRule matchRule) {
                appendSpan(builder, TextMateScopeStyleMapper.map(matchRule.name()), nextCandidate.end() - nextCandidate.start());
            } else if (nextCandidate.rule() instanceof TextMateGrammar.BeginEndRule beginEndRule) {
                appendSpan(builder, TextMateScopeStyleMapper.map(beginEndRule.name()), nextCandidate.end() - nextCandidate.start());
                Collection<String> contentStyles = TextMateScopeStyleMapper.map(beginEndRule.contentName().isBlank()
                        ? beginEndRule.name()
                        : beginEndRule.contentName());
                stack.push(new Context(beginEndRule, contentStyles));
            }
            position = nextCandidate.end();
        }

        return builder.create();
    }

    private static MatchCandidate findEndCandidate(TextMateGrammar.BeginEndRule rule, String text, int position) {
        Matcher matcher = rule.endPattern().matcher(text);
        return matcher.find(position) ? new MatchCandidate(rule, matcher.start(), matcher.end()) : null;
    }

    private static MatchCandidate findNextCandidate(TextMateGrammar grammar,
                                                    List<TextMateGrammar.Rule> patterns,
                                                    String text,
                                                    int position,
                                                    Set<String> includeStack,
                                                    Map<ExpansionKey, List<TextMateGrammar.Rule>> expansionCache) {
        MatchCandidate best = null;
        for (TextMateGrammar.Rule rule : expandRules(grammar, patterns, includeStack, expansionCache)) {
            MatchCandidate candidate = switch (rule) {
                case TextMateGrammar.MatchRule matchRule -> findCandidate(matchRule, matchRule.pattern().matcher(text), position);
                case TextMateGrammar.BeginEndRule beginEndRule -> findCandidate(beginEndRule, beginEndRule.beginPattern().matcher(text), position);
                default -> null;
            };
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.start() < best.start() || (candidate.start() == best.start() && candidate.end() > best.end())) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<TextMateGrammar.Rule> expandRules(TextMateGrammar grammar,
                                                          List<TextMateGrammar.Rule> patterns,
                                                          Set<String> includeStack,
                                                          Map<ExpansionKey, List<TextMateGrammar.Rule>> expansionCache) {
        ExpansionKey key = new ExpansionKey(patterns, includeStack);
        List<TextMateGrammar.Rule> cached = expansionCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<TextMateGrammar.Rule> expanded = new ArrayList<>();
        for (TextMateGrammar.Rule rule : patterns) {
            if (rule instanceof TextMateGrammar.IncludeRule includeRule) {
                String include = includeRule.include();
                if ("$self".equals(include) || "$base".equals(include)) {
                    expanded.addAll(expandRules(grammar, grammar.patterns(), includeStack, expansionCache));
                    continue;
                }
                if (include.startsWith("#")) {
                    String repositoryKey = include.substring(1);
                    if (includeStack.contains(repositoryKey)) {
                        continue;
                    }
                    expanded.addAll(expandRules(
                            grammar,
                            grammar.repository(repositoryKey),
                            mergeIncludeStack(includeStack, repositoryKey),
                            expansionCache
                    ));
                }
                continue;
            }
            if (!(rule instanceof TextMateGrammar.NoOpRule)) {
                expanded.add(rule);
            }
        }

        List<TextMateGrammar.Rule> resolved = List.copyOf(expanded);
        expansionCache.put(key, resolved);
        return resolved;
    }

    private static Set<String> mergeIncludeStack(Set<String> includeStack, String repositoryKey) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(includeStack);
        merged.add(repositoryKey);
        return Set.copyOf(merged);
    }

    private static MatchCandidate findCandidate(TextMateGrammar.Rule rule, Matcher matcher, int position) {
        return matcher.find(position) ? new MatchCandidate(rule, matcher.start(), matcher.end()) : null;
    }

    private static void appendSpan(StyleSpansBuilder<Collection<String>> builder, Collection<String> styles, int length) {
        if (length <= 0) {
            return;
        }
        builder.add(styles == null || styles.isEmpty() ? List.of() : styles, length);
    }

    private record Context(TextMateGrammar.BeginEndRule rule, Collection<String> contentStyles) {
    }

    private record ExpansionKey(List<TextMateGrammar.Rule> patterns, Set<String> includeStack) {
    }

    private record MatchCandidate(TextMateGrammar.Rule rule, int start, int end) {
    }
}

