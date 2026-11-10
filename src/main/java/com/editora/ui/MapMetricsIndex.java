package com.editora.ui;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/** The project's code metrics (Map code browser gap 15): each source file measured ({@link ProjectMapMetrics}). */
final class MapMetricsIndex extends MapFileReader<ProjectMapMetrics.Measure, ProjectMapMetrics> {

    MapMetricsIndex() {
        super("project-map-metrics");
    }

    @Override
    protected boolean wanted(String rel) {
        return ProjectMapMetrics.languageOf(rel) != null;
    }

    @Override
    protected ProjectMapMetrics.@Nullable Measure read(String rel, String text) {
        return ProjectMapMetrics.measure(rel, text);
    }

    @Override
    protected ProjectMapMetrics finish(
            Map<String, ProjectMapMetrics.Measure> read, Map<String, String> sides, boolean truncated) {
        return new ProjectMapMetrics(read, truncated);
    }
}
