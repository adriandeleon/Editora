package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMateLanguageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void bundledJavaScriptGrammarProducesKeywordStringAndCommentStyles() {
        LanguageService service = TextMateBundleLoader.loadBundledServices().stream()
                .filter(candidate -> candidate.displayName().equals("JavaScript"))
                .findFirst()
                .orElseThrow();

        String source = "const render = () => {\n  return \"Editora\"; // todo\n};\n";
        LanguageAnalysis analysis = service.analyze(source);

        assertEquals(source.length(), analysis.highlighting().length());
        assertTrue(hasStyle(analysis.highlighting(), "keyword"));
        assertTrue(hasStyle(analysis.highlighting(), "string"));
        assertTrue(hasStyle(analysis.highlighting(), "comment"));
        assertTrue(analysis.diagnostics().isEmpty());
    }

    @Test
    void registryResolvesMarkdownFilesToTextMateService() {
        LanguageService service = LanguageServiceRegistry.getInstance().resolve(Path.of("notes.md"));

        assertEquals("Markdown", service.displayName());
        assertInstanceOf(TextMateLanguageService.class, service);
    }

    @Test
    void registryResolvesJavaFilesToTextMateService() {
        LanguageService service = LanguageServiceRegistry.getInstance().resolve(Path.of("Demo.java"));

        assertEquals("Java", service.displayName());
        assertInstanceOf(TextMateLanguageService.class, service);
    }

    @Test
    void loadsExternalTextMateJsonGrammarFiles() throws IOException {
        Path bundlesDirectory = Files.createDirectories(tempDir.resolve("bundles"));
        Files.writeString(bundlesDirectory.resolve("demo.tmLanguage.json"), """
                {
                  "name": "Demo Grammar",
                  "scopeName": "source.demo",
                  "fileTypes": ["demo"],
                  "patterns": [
                    {
                      "name": "keyword.control.demo",
                      "match": "\\\\b(?:demo|bundle)\\\\b"
                    }
                  ]
                }
                """);

        List<LanguageService> services = TextMateBundleLoader.loadExternalServices(bundlesDirectory);
        assertEquals(1, services.size());
        LanguageService service = services.getFirst();
        assertTrue(service.supports(Path.of("sample.demo")));

        LanguageAnalysis analysis = service.analyze("demo bundle");
        assertTrue(hasStyle(analysis.highlighting(), "keyword"));
    }

    @Test
    void flagsLargeFilesForProgressiveHighlighting() {
        LanguageService service = new TextMateLanguageService(
                "demo",
                "Demo",
                TextMateGrammar.fromPlist(Map.of(
                        "name", "Demo",
                        "scopeName", "source.demo",
                        "fileTypes", List.of("demo"),
                        "patterns", List.of(Map.of(
                                "name", "keyword.control.demo",
                                "match", "\\b(?:demo|bundle)\\b"
                        ))
                )),
                Set.of("demo")
        );

        String source = "demo bundle sample\n".repeat(TextMateLanguageService.LARGE_FILE_LINE_THRESHOLD + 10);

        assertTrue(service.supportsProgressiveHighlighting(source));
    }

    @Test
    void highlightsRequestedRangeForLargeFiles() {
        LanguageService service = new TextMateLanguageService(
                "demo",
                "Demo",
                TextMateGrammar.fromPlist(Map.of(
                        "name", "Demo",
                        "scopeName", "source.demo",
                        "fileTypes", List.of("demo"),
                        "patterns", List.of(Map.of(
                                "name", "keyword.control.demo",
                                "match", "\\b(?:demo|bundle)\\b"
                        ))
                )),
                Set.of("demo")
        );

        String source = "prefix\n" + "demo bundle sample\n".repeat(TextMateLanguageService.LARGE_FILE_LINE_THRESHOLD + 10);
        int start = source.indexOf("demo bundle");
        int end = source.indexOf('\n', start);
        StyleSpans<Collection<String>> highlighting = service.highlightRange(source, start, end);

        assertEquals(end - start, highlighting.length());
        assertTrue(hasStyle(highlighting, "keyword"));
    }

    private boolean hasStyle(StyleSpans<Collection<String>> spans, String styleClass) {
        for (StyleSpan<Collection<String>> span : spans) {
            Collection<String> styles = span.getStyle();
            if (styles != null && styles.contains(styleClass)) {
                return true;
            }
        }
        return false;
    }
}



