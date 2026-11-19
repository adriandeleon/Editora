package com.editora.ui;

import javafx.application.Application;

import com.editora.config.Settings;
import com.editora.editor.UiZoom;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Puts the UI zoom back to 100% after a test class that changed it. The zoom is one value for the whole app,
 * so it outlives the window that set it: a Settings test that steps every number field leaves the interface at
 * 110%, and every size measured by a later class in the same fork is a tenth too large (a completion popup's
 * 1100 px cap read 1210 on the macOS shard that happened to run the two in that order).
 *
 * <p>The number is not the whole zoom: the theme's user-agent sheet is a scaled copy while zoomed, and a class
 * that sets the number back with {@link UiZoom#init} leaves that copy in force. {@code UiZoomSheetsOffFxTest} did:
 * the theme's 170% copy it had asked for landed before its teardown, and every later class in the fork laid text
 * out 1.7 times larger at a zoom that read 100% (a 299-character line wrapped into 15 rows instead of 9).
 */
public final class DefaultUiZoom implements AfterAllCallback {

    /** Public, with a public constructor: the service loader makes it. */
    public DefaultUiZoom() {}

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (restore()) {
            System.err.println("DefaultUiZoom: " + context.getDisplayName()
                    + " left the UI zoom on; it is back at 100% for the next class");
        }
    }

    /**
     * Puts the zoom, and the theme's user-agent sheet, back to 100%: what a test that zoomed calls in its own
     * teardown. Off the FX thread.
     *
     * @return whether anything was left zoomed
     */
    public static boolean restore() throws Exception {
        boolean changed = false;
        if (UiZoom.percent() != Settings.DEFAULT_UI_ZOOM) {
            // A zoom other than the default was set on the FX thread, so the toolkit is running.
            FxTestSupport.runOnFx(() -> Themes.applyUiZoom(Settings.DEFAULT_UI_ZOOM));
            changed = true;
        }
        if (zoomedTheme()) {
            // Only a running toolkit has a zoomed copy as its user-agent sheet.
            FxTestSupport.runOnFx(() -> {
                String sheet = Application.getUserAgentStylesheet();
                if (sheet != null && !sheet.equals(UiZoom.base(sheet))) {
                    Application.setUserAgentStylesheet(UiZoom.base(sheet));
                    UiZoom.themeChanged();
                }
            });
            changed = true;
        }
        return changed;
    }

    /** Whether the user-agent sheet in force is a zoomed copy rather than a theme's 100% sheet. */
    private static boolean zoomedTheme() {
        String sheet = Application.getUserAgentStylesheet();
        return sheet != null && !sheet.equals(UiZoom.base(sheet));
    }
}
