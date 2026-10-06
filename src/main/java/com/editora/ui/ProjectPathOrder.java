package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared ordering contract for project navigation: folders first, then names without case bias. */
final class ProjectPathOrder {

    private ProjectPathOrder() {}

    static <T> Comparator<T> directoriesFirst(Predicate<T> isDirectory, Function<T, String> name) {
        return Comparator.<T, Boolean>comparing(item -> !isDirectory.test(item))
                .thenComparing(name, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * {@code paths} in the shared order, asking {@code isDirectory} exactly once per path. The question is a
     * {@code stat}; asked from inside a comparator it is put twice per comparison, some {@code 2·n·log2 n}
     * times for one listing, which is what a large folder on a slow mount was paying to be sorted.
     */
    static List<Path> sorted(Collection<Path> paths, Predicate<Path> isDirectory) {
        record Keyed(Path path, boolean directory, String name) {}
        List<Keyed> keyed = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path name = path.getFileName();
            keyed.add(new Keyed(path, isDirectory.test(path), name == null ? path.toString() : name.toString()));
        }
        keyed.sort(directoriesFirst(Keyed::directory, Keyed::name));
        List<Path> out = new ArrayList<>(keyed.size());
        for (Keyed k : keyed) {
            out.add(k.path());
        }
        return out;
    }
}
