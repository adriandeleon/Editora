package com.editora.ui;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The Map's dependencies between the project's files (Map code browser gap 14): each source file's imports ({@link
 * ProjectMapImports}), the {@code go.mod} files read every time for their module paths, resolved together into {@link
 * ProjectMapDependencies}.
 */
final class MapDependencyIndex extends MapFileReader<ProjectMapImports.Parsed, ProjectMapDependencies> {

    MapDependencyIndex() {
        super("project-map-dependencies");
    }

    @Override
    protected boolean wanted(String rel) {
        return ProjectMapImports.languageOf(rel) != null;
    }

    @Override
    protected boolean side(String rel) {
        return rel.equals("go.mod") || rel.endsWith("/go.mod");
    }

    @Override
    protected ProjectMapImports.@Nullable Parsed read(String rel, String text) {
        return ProjectMapImports.parse(rel, text);
    }

    @Override
    protected ProjectMapDependencies finish(
            Map<String, ProjectMapImports.Parsed> read, Map<String, String> sides, boolean truncated) {
        Map<String, String> goModules = new HashMap<>();
        sides.forEach((rel, text) -> {
            String module = ProjectMapImports.goModule(text);
            if (module != null) {
                goModules.put(ProjectMapImports.folderOf(rel), module);
            }
        });
        return new ProjectMapDependencies(read.keySet(), ProjectMapImports.resolve(read, goModules), truncated);
    }
}
