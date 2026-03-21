package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaLanguageService implements LanguageService {
    public static final JavaLanguageService INSTANCE = new JavaLanguageService();

    private static final String[] JAVA_KEYWORDS = new String[]{
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
            "yield", "record", "permits", "non-sealed"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", JAVA_KEYWORDS) + ")\\b";
    private static final String PAREN_PATTERN = "[()]";
    private static final String BRACE_PATTERN = "[{}]";
    private static final String BRACKET_PATTERN = "[\\[\\]]";
    private static final String SEMICOLON_PATTERN = ";";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String COMMENT_PATTERN = "//[^\\n]*|/\\*(.|\\R)*?\\*/";
    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile(
            "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<PAREN>" + PAREN_PATTERN + ")"
                    + "|(?<BRACE>" + BRACE_PATTERN + ")"
                    + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
                    + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
    );

    private JavaLanguageService() {
    }

    @Override
    public String id() {
        return "java";
    }

    @Override
    public String displayName() {
        return "Java";
    }

    @Override
    public boolean supports(Path path) {
        return path != null && path.getFileName().toString().endsWith(".java");
    }

    @Override
    public LanguageAnalysis analyze(String text) {
        return new LanguageAnalysis(computeHighlighting(text), computeDiagnostics(text), FoldingSupport.computeBraceFolds(text));
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = HIGHLIGHT_PATTERN.matcher(text);
        int lastKeywordEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass = matcher.group("KEYWORD") != null ? "keyword"
                    : matcher.group("PAREN") != null ? "paren"
                    : matcher.group("BRACE") != null ? "brace"
                    : matcher.group("BRACKET") != null ? "bracket"
                    : matcher.group("SEMICOLON") != null ? "semicolon"
                    : matcher.group("STRING") != null ? "string"
                    : matcher.group("COMMENT") != null ? "comment"
                    : null;

            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKeywordEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKeywordEnd = matcher.end();
        }

        spansBuilder.add(Collections.emptyList(), text.length() - lastKeywordEnd);
        return spansBuilder.create();
    }

    private List<Diagnostic> computeDiagnostics(String text) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        int braceDepth = 0;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.contains("TODO")) {
                diagnostics.add(new Diagnostic(index, DiagnosticSeverity.INFO, "TODO marker"));
            }
            if (line.length() > 140) {
                diagnostics.add(new Diagnostic(index, DiagnosticSeverity.WARNING, "Line exceeds 140 characters"));
            }

            for (int charIndex = 0; charIndex < line.length(); charIndex++) {
                char current = line.charAt(charIndex);
                if (current == '{') {
                    braceDepth++;
                } else if (current == '}') {
                    if (braceDepth == 0) {
                        diagnostics.add(new Diagnostic(index, DiagnosticSeverity.ERROR, "Unmatched closing brace"));
                    } else {
                        braceDepth--;
                    }
                }
            }
        }

        if (braceDepth > 0) {
            diagnostics.add(new Diagnostic(Math.max(0, lines.length - 1), DiagnosticSeverity.WARNING,
                    "Missing " + braceDepth + " closing brace(s)"));
        }

        return diagnostics;
    }
}

