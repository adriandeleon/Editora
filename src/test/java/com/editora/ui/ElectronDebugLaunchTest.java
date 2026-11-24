package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.dap.BrowserLaunch;
import com.editora.dap.LaunchRoute;
import com.editora.run.ElectronApps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElectronDebugLaunchTest {

    private static final Path ROOT = Path.of("/work/shelf").toAbsolutePath();

    private static final ElectronApps.App APP =
            new ElectronApps.App(ROOT, ROOT.resolve("node_modules/electron/dist/electron"));

    @Test
    void theMainProcessRunsOnElectronUnderJsDebugWithItsRendererPortOpen() {
        Map<String, Object> main = ElectronDebugLaunch.main(APP, 9333, List.of("--no-sandbox"));
        assertEquals("pwa-node", main.get("type"));
        assertEquals("launch", main.get("request"));
        assertEquals(APP.electron().toString(), main.get("runtimeExecutable"));
        assertEquals(List.of("--remote-debugging-port=9333", ".", "--no-sandbox"), main.get("runtimeArgs"));
        assertEquals(ROOT.toString(), main.get("cwd"));
        assertEquals("std", main.get("outputCapture"));
        assertEquals(LaunchRoute.Kind.JS_DEBUG, LaunchRoute.of(main, Map.of()).kind());
    }

    @Test
    void theRendererIsAttachedToOnThatPortThroughTheAppsFolder() {
        Map<String, Object> renderer = ElectronDebugLaunch.renderer(APP, 9333);
        assertEquals(BrowserLaunch.CHROME, renderer.get("type"));
        assertEquals("attach", renderer.get("request"));
        assertEquals(9333, renderer.get("port"));
        assertEquals(ROOT.toString(), renderer.get("webRoot"));
        assertEquals(ElectronDebugLaunch.RENDERER, renderer.get("name"));
        assertEquals(
                LaunchRoute.Kind.BROWSER, LaunchRoute.of(renderer, Map.of()).kind());
    }
}
