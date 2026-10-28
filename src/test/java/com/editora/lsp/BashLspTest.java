package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.editora.config.Settings;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Editora does to the Bash server's replies before showing them (Bash review D2, A15, B10, A9). */
class BashLspTest {

    private static Diagnostic shellcheck(String code) {
        Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), code + " message");
        d.setSource("shellcheck");
        d.setCode(Either.forLeft(code));
        return d;
    }

    private static List<String> codes(List<Diagnostic> ds) {
        return ds.stream().map(d -> d.getCode().getLeft()).toList();
    }

    /** bash-language-server 5.7+ lints zsh too, and ShellCheck answers SC1071 "only supports sh/bash…" (D2). */
    @Test
    void sc1071IsDroppedForEveryBashServedFile() {
        List<Diagnostic> in = List.of(shellcheck("SC1071"), shellcheck("SC2086"));
        assertEquals(List.of("SC2086"), codes(BashLsp.diagnostics("bash", Path.of("/p/script.zsh"), in)));
        assertEquals(List.of("SC2086"), codes(BashLsp.diagnostics("bash", Path.of("/p/tool"), in)));
        assertSame(in, BashLsp.diagnostics("python", Path.of("/p/script.zsh"), in), "another server's stay");
        Diagnostic parse = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "SC1071-like text");
        parse.setSource("bash-language-server");
        assertEquals(List.of(parse), BashLsp.diagnostics("bash", Path.of("/p/a.sh"), List.of(parse)));
    }

    /** makepkg reads a PKGBUILD's variables and sets srcdir/pkgdir itself: no "unused" or "never assigned" (A15). */
    @Test
    void aPkgbuildLosesUnusedAndUnassignedButKeepsTheRest() {
        List<Diagnostic> in = List.of(shellcheck("SC2034"), shellcheck("SC2154"), shellcheck("SC2086"));
        assertEquals(List.of("SC2086"), codes(BashLsp.diagnostics("bash", Path.of("/aur/foo/PKGBUILD"), in)));
        assertEquals(
                List.of("SC2034", "SC2154", "SC2086"),
                codes(BashLsp.diagnostics("bash", Path.of("/aur/foo/build.sh"), in)),
                "only a file named PKGBUILD");
        Diagnostic numeric = shellcheck("SC2034");
        numeric.setCode(Either.forRight(2034));
        assertTrue(BashLsp.diagnostics("bash", Path.of("/aur/foo/PKGBUILD"), List.of(numeric))
                .isEmpty());
        assertEquals(List.of(), BashLsp.diagnostics("bash", Path.of("/a.sh"), null));
    }

    private static LanguageServerSession bashSession(Path root, String endpoint) {
        var spec = new LspServerRegistry.ServerSpec("bash", List.of("bash-language-server"), List.of());
        LanguageServerSession session = new LanguageServerSession(spec, root, d -> {}, (t, m) -> {});
        Settings s = new Settings();
        s.setBashExplainshellEndpoint(endpoint);
        session.setBashIde(BashIdeConfig.of(s));
        return session;
    }

    /** A Bash hover that waits on explainshell gives up after a while and says why (B10). */
    @Test
    void aHoverWaitingOnExplainshellTimesOutWithAReason(@TempDir Path root) throws Exception {
        com.editora.i18n.Messages.init("en");
        LanguageServerSession asking = bashSession(root, "http://localhost:5000");
        CompletableFuture<Hover> never = new CompletableFuture<>();
        CompletableFuture<Hover> late = BashLsp.hoverDeadline(asking, never, 50);
        ExecutionException e = assertThrows(ExecutionException.class, () -> late.get(5, TimeUnit.SECONDS));
        String why = BashLsp.hoverTimedOut(asking, e.getCause());
        assertNotNull(why);
        assertTrue(why.startsWith("explainshell did not answer") && why.contains("http://localhost:5000"), why);
        assertFalse(never.isDone(), "the request itself is left alone; its late answer is dropped");

        CompletableFuture<Hover> quick = new CompletableFuture<>();
        CompletableFuture<Hover> answered = BashLsp.hoverDeadline(asking, quick, 5_000);
        quick.complete(new Hover());
        assertNotNull(answered.get(5, TimeUnit.SECONDS));

        LanguageServerSession plain = bashSession(root, "");
        CompletableFuture<Hover> request = new CompletableFuture<>();
        assertSame(request, BashLsp.hoverDeadline(plain, request, 50), "no endpoint: no deadline");
        assertNull(BashLsp.hoverTimedOut(plain, new java.util.concurrent.TimeoutException()));
        LanguageServerSession refused = bashSession(root, "http://explain.example.com");
        assertSame(request, BashLsp.hoverDeadline(refused, request, 50), "an endpoint that is not sent asks no one");
    }

    /** Loose Bash scripts share one server root, as loose TypeScript does (A9); a project's scripts do not. */
    @Test
    void looseScriptsShareTheLooseRoot(@TempDir Path dir) throws Exception {
        Path loose = java.nio.file.Files.createDirectories(dir.resolve("bin")).resolve("x.sh");
        Path repo =
                java.nio.file.Files.createDirectories(dir.resolve("repo/.git")).getParent();
        assertTrue(BashLsp.sharesLooseRoot("bash", null, loose));
        assertFalse(BashLsp.sharesLooseRoot("bash", null, repo.resolve("main.sh")), "a git repository is its own");
        assertFalse(BashLsp.sharesLooseRoot("bash", dir, loose), "inside the window's project");
        assertTrue(BashLsp.sharesLooseRoot(TypeScriptServer.SERVER_ID, null, dir.resolve("bin/x.ts")));
        assertFalse(BashLsp.sharesLooseRoot("python", null, dir.resolve("bin/x.py")));
    }
}
