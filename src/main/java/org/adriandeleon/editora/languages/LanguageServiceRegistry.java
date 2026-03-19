package org.adriandeleon.editora.languages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LanguageServiceRegistry {
    private static final LanguageServiceRegistry INSTANCE = new LanguageServiceRegistry();
    private static final Path TEXTMATE_BUNDLES_DIRECTORY = Path.of("textmate-bundles");

    private final LanguageService defaultService = PlainTextLanguageService.INSTANCE;
    private volatile List<LanguageService> services = List.of(PlainTextLanguageService.INSTANCE);

    private LanguageServiceRegistry() {
        reloadTextMateBundles();
    }

    public static LanguageServiceRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void reloadTextMateBundles() {
        List<LanguageService> languageServices = new ArrayList<>();
        languageServices.addAll(TextMateBundleLoader.loadExternalServices(TEXTMATE_BUNDLES_DIRECTORY));
        languageServices.addAll(TextMateBundleLoader.loadBundledServices());
        languageServices.add(PlainTextLanguageService.INSTANCE);
        services = List.copyOf(languageServices);
    }

    public List<LanguageService> availableServices() {
        return services.stream()
                .filter(service -> service != PlainTextLanguageService.INSTANCE)
                .toList();
    }

    public LanguageService resolve(Path path) {
        if (path == null) {
            return defaultService;
        }

        return services.stream()
                .filter(service -> service.supports(path))
                .findFirst()
                .orElse(defaultService);
    }

    public String availableLanguagesSummary() {
        return availableServices().stream()
                .map(LanguageService::displayName)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("None");
    }

    public List<LanguagePreviewSpec> previewSpecs() {
        return availableServices().stream()
                .map(this::buildPreviewSpec)
                .filter(Objects::nonNull)
                .toList();
    }

    private LanguagePreviewSpec buildPreviewSpec(LanguageService service) {
        List<String> extensions = service.fileExtensions();
        if (extensions.isEmpty()) {
            return null;
        }

        String primaryExtension = extensions.getFirst();
        String description = extensions.stream()
                .map(extension -> "." + extension)
                .collect(Collectors.joining(", "));
        return new LanguagePreviewSpec(
                service.displayName(),
                description,
                Path.of("preview." + primaryExtension),
                sampleTextFor(service.displayName(), primaryExtension)
        );
    }

    private String sampleTextFor(String displayName, String extension) {
        String language = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        return switch (language) {
            case "java" -> String.join("\n",
                    "public record Greeting(String value) {",
                    "    public static void main(String[] args) {",
                    "        System.out.println(new Greeting(\"Editora\").value());",
                    "    }",
                    "}");
            case "javascript" -> String.join("\n",
                    "const render = (name) => {",
                    "  return `Hello ${name}`;",
                    "};",
                    "console.log(render('Editora')); // preview");
            case "json" -> String.join("\n",
                    "{",
                    "  \"name\": \"Editora\",",
                    "  \"themes\": 7,",
                    "  \"plugins\": true",
                    "}");
            case "markdown" -> String.join("\n",
                    "# Editora Preview",
                    "",
                    "**Bold** text, *italic* text, and `inline code`.",
                    "",
                    "```java",
                    "System.out.println(\"Hello\");",
                    "```");
            case "xml / html" -> String.join("\n",
                    "<editor theme=\"primer-dark\">",
                    "  <name>Editora</name>",
                    "  <!-- syntax preview -->",
                    "</editor>");
            case "css" -> String.join("\n",
                    ".editor-shell {",
                    "  color: #e5e7eb;",
                    "  padding: 12px;",
                    "}");
            case "python" -> String.join("\n",
                    "def render(name: str) -> str:",
                    "    return f\"Hello {name}\"",
                    "",
                    "print(render(\"Editora\"))");
            case "yaml" -> String.join("\n",
                    "app:",
                    "  name: Editora",
                    "  syntax: textmate",
                    "  themes: [primer-dark, nord-light]");
            case "shell script" -> String.join("\n",
                    "#!/usr/bin/env bash",
                    "theme=\"primer-dark\"",
                    "echo \"Launching Editora with $theme\"");
            case "sql" -> String.join("\n",
                    "SELECT name, theme",
                    "FROM editor_settings",
                    "WHERE app = 'Editora';");
            default -> String.join("\n",
                    "# " + displayName,
                    "# Preview file: preview." + extension,
                    "sample = true");
        };
    }
}

