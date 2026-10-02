package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The environment a real language-server process is started with.
 *
 * <p>Servers used to inherit the parse-stable {@code LC_ALL=C} that exists for git output. A JVM in the C
 * locale decodes file names as ASCII, so jdtls could not index a project under a path with ñ/é; every other
 * server reported paths and messages through an ASCII-only stdout. The server must see the locale exactly as
 * Editora inherited it.
 *
 * <p>A POSIX shell stands in for the server, so Windows sits this out.
 */
@DisabledOnOs(OS.WINDOWS)
class LanguageServerEnvProcessTest {

    /**
     * A stand-in server: reports the {@code LC_ALL} it was started with as one {@code window/showMessage}
     * notification — a real LSP frame, so it comes back through the session's own reader — then keeps reading
     * stdin like a server would until the session is disposed.
     */
    private static final String STAND_IN = """
            m='{"jsonrpc":"2.0","method":"window/showMessage","params":{"type":3,"message":"LC_ALL='"${LC_ALL-<unset>}"'"}}'
            printf 'Content-Length: %d\\r\\n\\r\\n%s' "${#m}" "$m"
            cat > /dev/null
            """;

    @Test
    void aLanguageServerInheritsTheUsersLocale(@TempDir Path dir) throws Exception {
        CompletableFuture<String> reported = new CompletableFuture<>();
        var spec = new LspServerRegistry.ServerSpec("xml", List.of("sh", "-c", STAND_IN), List.of());
        var session = new LanguageServerSession(
                spec,
                dir,
                diagnostics -> {},
                (type, message) -> {
                    if (message != null && message.startsWith("LC_ALL=")) {
                        reported.complete(message.substring("LC_ALL=".length()));
                    }
                },
                null);
        try {
            session.start();

            String inherited = System.getenv("LC_ALL") == null ? "<unset>" : System.getenv("LC_ALL");
            assertEquals(
                    inherited,
                    reported.get(20, TimeUnit.SECONDS),
                    "a language server must not be forced into LC_ALL=C");
        } finally {
            session.dispose();
        }
    }
}
