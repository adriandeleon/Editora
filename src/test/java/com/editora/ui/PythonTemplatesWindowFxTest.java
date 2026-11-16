package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Template breakpoints through the window: Settings' Template breakpoints reaches the window's sessions, and a Python
 * launch in a Django project with Jinja in its requirements goes to debugpy (a {@link FakeDebugAdapter}) with both
 * switches on when Automatic, and with neither when Off. {@code PythonTemplatesProbeFxTest} stops in real templates.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonTemplatesWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private Path shop;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        shop = Files.createDirectories(fx.configDir.resolve("shop"));
        Files.writeString(shop.resolve("manage.py"), "import django\n");
        Files.writeString(shop.resolve("requirements.txt"), "Django>=5\nJinja2\n");
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        FxTestSupport.drainFx();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The launch debugpy receives for a program in {@code dir}, with Settings' Template breakpoints {@code mode}. */
    private Map<String, Object> launchWith(String mode) throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugPythonTemplates(mode);
            debug.applySupport();
        });
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            long epoch =
                    (Long) FxTestSupport.callOnFx(() -> FxTestSupport.call(manager, "beginSession", new Class<?>[] {}));
            DapClient client = new DapClient(
                    (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, epoch));
            client.connect(adapter.port(), "python").get(10, TimeUnit.SECONDS);
            assertTrue((Boolean) FxTestSupport.call(
                    manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
            FakeDebugAdapter.Session session = adapter.awaitSession();
            client.launch(Map.of(
                            "type",
                            "python",
                            "request",
                            "launch",
                            "program",
                            shop.resolve("shop.py").toString(),
                            "cwd",
                            shop.toString()))
                    .get(10, TimeUnit.SECONDS);
            Map<String, Object> args = session.launchArgs;
            client.dispose();
            return args;
        }
    }

    @Test
    void automaticTurnsOnTheEnginesTheProjectUsesAndOffNeither() throws Exception {
        assertEquals(
                "auto", FxTestSupport.callOnFx(() -> fx.shared.getSettings().getDebugPythonTemplates()));
        Map<String, Object> auto = launchWith("auto");
        assertEquals(true, auto.get("django"), "a manage.py and Django in the requirements");
        assertEquals(true, auto.get("jinja"), "Jinja2 in the requirements");

        Map<String, Object> off = launchWith("off");
        assertFalse(off.containsKey("django"));
        assertFalse(off.containsKey("jinja"));
    }
}
