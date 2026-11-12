package com.editora.ui;

import com.editora.config.Settings;
import com.editora.editor.UiZoom;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Puts the UI zoom back to 100% after a test class that changed it. The zoom is one value for the whole app,
 * so it outlives the window that set it: a Settings test that steps every number field leaves the interface at
 * 110%, and every size measured by a later class in the same fork is a tenth too large (a completion popup's
 * 1100 px cap read 1210 on the macOS shard that happened to run the two in that order).
 */
public final class DefaultUiZoom implements AfterAllCallback {

    /** Public, with a public constructor: the service loader makes it. */
    public DefaultUiZoom() {}

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (UiZoom.percent() != Settings.DEFAULT_UI_ZOOM) {
            // A zoom other than the default was set on the FX thread, so the toolkit is running.
            FxTestSupport.runOnFx(() -> Themes.applyUiZoom(Settings.DEFAULT_UI_ZOOM));
        }
    }
}
