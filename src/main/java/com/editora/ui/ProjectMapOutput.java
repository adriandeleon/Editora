package com.editora.ui;

import java.util.List;

import javafx.scene.image.Image;

import org.jspecify.annotations.Nullable;

/**
 * A deferred Project Map print or PDF job. Nothing is rendered until the page size is known, so the PDF
 * destination dialog can be cancelled for free and print can re-render for the layout the user picks.
 */
interface ProjectMapOutput {

    /** True when the laid-out map is wider than it is tall, so a landscape page wastes less paper. */
    boolean landscape();

    /**
     * Renders the complete map for pages whose printable area is {@code pageWidth} × {@code pageHeight}
     * points. Must run on the FX thread. Returns {@code null} when the map has nothing to show.
     */
    @Nullable
    Rendered render(double pageWidth, double pageHeight);

    /** The window the job's dialogs (Save, Print Preview) belong over — the Map's own; null: the editor's. */
    default javafx.stage.@Nullable Window owner() {
        return null;
    }

    /** The PDF file name suggested, without its extension ({@code <folder>-map}); null: the default. */
    default @Nullable String baseName() {
        return null;
    }

    /**
     * @param pages one image per output page, in reading order
     * @param pointsPerPixel the paper size of one image pixel, the same for every page
     * @param plan how the map was scaled and split
     */
    record Rendered(List<Image> pages, double pointsPerPixel, ProjectMapOutputPlan.Plan plan) {}
}
