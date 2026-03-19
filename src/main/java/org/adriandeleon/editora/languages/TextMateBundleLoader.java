package org.adriandeleon.editora.languages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TextMateBundleLoader {
    private static final List<String> BUILT_IN_GRAMMARS = List.of(
            "/org/adriandeleon/editora/textmate/java.tmLanguage.json",
            "/org/adriandeleon/editora/textmate/javascript.tmLanguage",
            "/org/adriandeleon/editora/textmate/json.tmLanguage",
            "/org/adriandeleon/editora/textmate/markdown.tmLanguage",
            "/org/adriandeleon/editora/textmate/xml.tmLanguage",
            "/org/adriandeleon/editora/textmate/css.tmLanguage",
            "/org/adriandeleon/editora/textmate/python.tmLanguage.json",
            "/org/adriandeleon/editora/textmate/yaml.tmLanguage.json",
            "/org/adriandeleon/editora/textmate/sql.tmLanguage.json",
            "/org/adriandeleon/editora/textmate/shell.tmLanguage.json"
    );

    private TextMateBundleLoader() {
    }

    static List<LanguageService> loadBundledServices() {
        List<LanguageService> services = new ArrayList<>();
        for (String resourcePath : BUILT_IN_GRAMMARS) {
            try (InputStream inputStream = TextMateBundleLoader.class.getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    continue;
                }
                services.add(createService(parseGrammar(inputStream, resourcePath), resourcePath));
            } catch (IOException | IllegalArgumentException exception) {
                // Fail-soft: ignore malformed bundled grammars rather than breaking the editor shell.
            }
        }
        return List.copyOf(services);
    }

    static List<LanguageService> loadExternalServices(Path bundlesDirectory) {
        if (bundlesDirectory == null || !Files.isDirectory(bundlesDirectory)) {
            return List.of();
        }

        List<LanguageService> services = new ArrayList<>();
        try (var stream = Files.walk(bundlesDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".tmlanguage")
                                || fileName.endsWith(".tmlanguage.json")
                                || fileName.endsWith(".plist");
                    })
                    .forEach(path -> {
                        try (InputStream inputStream = Files.newInputStream(path)) {
                            services.add(createService(parseGrammar(inputStream, path.getFileName().toString()), path.toString()));
                        } catch (IOException | IllegalArgumentException exception) {
                            // Ignore malformed user bundles so one broken grammar does not disable the others.
                        }
                    });
        } catch (IOException exception) {
            return List.of();
        }
        return List.copyOf(services);
    }

    private static LanguageService createService(Map<String, Object> plist, String sourceId) {
        TextMateGrammar grammar = TextMateGrammar.fromPlist(plist);
        String identifier = grammar.scopeName().isBlank()
                ? sourceId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                : grammar.scopeName().toLowerCase(Locale.ROOT).replace('.', '-');
        return new TextMateLanguageService(identifier, grammar.name(), grammar, grammar.fileTypes());
    }

    private static Map<String, Object> parseGrammar(InputStream inputStream, String sourceName) {
        String normalizedName = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".json") || normalizedName.endsWith(".tmlanguage.json")) {
            return TextMateJsonParser.parse(inputStream);
        }
        return TextMatePlistParser.parse(inputStream);
    }
}

