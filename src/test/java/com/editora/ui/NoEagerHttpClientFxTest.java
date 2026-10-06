package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Building a window must not build HTTP clients: each one starts a selector thread that lives as long as the
 * client, and the AI, plugin and language-server-install services used to create seven per window for
 * features an ordinary session never touches.
 */
@Tag("fx")
class NoEagerHttpClientFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Pattern SELECTOR = Pattern.compile("HttpClient-\\d+-SelectorManager");

    @Test
    void anEmptyWindowStartsNoHttpSelectorThread(@TempDir Path dir) throws Exception {
        Set<Thread> before = selectorThreads(); // other tests in this JVM may legitimately have left some
        try (AsyncTestScope async = new AsyncTestScope()) {
            // The daily update check is a real first use of its (lazy) client; it is not due in this window.
            FxWindowFixture fx = async.own(FxWindowFixture.create(
                    Files.createTempDirectory(dir, "cfg"),
                    shared -> shared.getSettings().setLastUpdateCheckEpoch(System.currentTimeMillis())));
            FxTestSupport.drainFx();
            Set<Thread> started = selectorThreads();
            started.removeAll(before);
            assertEquals(
                    Set.of(),
                    started.stream().map(Thread::getName).collect(Collectors.toSet()),
                    "HTTP clients built while the window was (controller: " + fx.controller + ")");
        }
    }

    private static Set<Thread> selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> SELECTOR.matcher(t.getName()).matches())
                .collect(Collectors.toSet());
    }
}
