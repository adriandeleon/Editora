package com.editora.ui;

import java.util.List;

import javafx.scene.image.Image;

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
    Rendered render(double pageWidth, double pageHeight);

    /**
     * @param pages one image per output page, in reading order
     * @param pointsPerPixel the paper size of one image pixel, the same for every page
     * @param plan how the map was scaled and split
     */
    record Rendered(List<Image> pages, double pointsPerPixel, ProjectMapOutputPlan.Plan plan) {}
}
