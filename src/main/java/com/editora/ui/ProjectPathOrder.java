package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared ordering contract for project navigation: folders first, then names without case bias. */
final class ProjectPathOrder {

    static final Comparator<Path> DIRECTORIES_FIRST =
            directoriesFirst(Files::isDirectory, path -> path.getFileName().toString());

    private ProjectPathOrder() {}

    static <T> Comparator<T> directoriesFirst(Predicate<T> isDirectory, Function<T, String> name) {
        return Comparator.<T, Boolean>comparing(item -> !isDirectory.test(item))
                .thenComparing(name, String.CASE_INSENSITIVE_ORDER);
    }
}
