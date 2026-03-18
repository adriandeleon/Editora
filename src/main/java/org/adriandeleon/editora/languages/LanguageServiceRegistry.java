package org.adriandeleon.editora.languages;

import java.nio.file.Path;
import java.util.List;

public final class LanguageServiceRegistry {
    private static final LanguageServiceRegistry INSTANCE = new LanguageServiceRegistry();

    private final LanguageService defaultService = JavaLanguageService.INSTANCE;
    private final List<LanguageService> services = List.of(JavaLanguageService.INSTANCE, PlainTextLanguageService.INSTANCE);

    private LanguageServiceRegistry() {
    }

    public static LanguageServiceRegistry getInstance() {
        return INSTANCE;
    }

    public LanguageService resolve(Path path) {
        if (path == null) {
            return defaultService;
        }

        return services.stream()
                .filter(service -> service.supports(path))
                .findFirst()
                .orElse(PlainTextLanguageService.INSTANCE);
    }

    public String availableLanguagesSummary() {
        return services.stream().map(LanguageService::displayName).distinct().sorted().reduce((left, right) -> left + ", " + right).orElse("None");
    }
}

