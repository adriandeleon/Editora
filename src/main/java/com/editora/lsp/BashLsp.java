package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the Bash server's replies need before Editora shows them: ShellCheck diagnostics that are noise for a
 * file the server cannot help (Bash review D2, A15), and a hover that waits on explainshell (B10).
 */
public final class BashLsp {

    /** The Bash server's id. */
    public static final String SERVER_ID = "bash";

    /**
     * How long a Bash hover may wait when an explainshell endpoint is set: the server asks it before answering,
     * with no timeout of its own, and a slow or dead endpoint kept the hover pending for as long as it took.
     */
    static final long HOVER_TIMEOUT_MS = 5_000;

    /**
     * ShellCheck's "only supports sh/bash/dash/ksh/'busybox sh' scripts": on a zsh script. bash-language-server
     * 5.7+ runs ShellCheck on every script it is given and lets it say so as an error on line 1, which the user
     * cannot act on; completion, hover and symbols, which do work on zsh, stay.
     */
    private static final String NO_ZSH = "SC1071";

    /**
     * A {@code PKGBUILD}'s variables are read by makepkg, which sources it: ShellCheck reports {@code pkgname},
     * {@code pkgver}, … as unused (SC2034) and makepkg's own {@code srcdir}/{@code pkgdir} as never assigned
     * (SC2154). makepkg requires that exact file name.
     */
    private static final Set<String> PKGBUILD_NOISE = Set.of("SC2034", "SC2154");

    private BashLsp() {}

    /**
     * {@code diagnostics} as Editora shows them for {@code file}: for the Bash server, without SC1071 on any file
     * and without SC2034/SC2154 on a {@code PKGBUILD}; another server's are returned as they are.
     */
    public static List<Diagnostic> diagnostics(String serverId, Path file, @Nullable List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty() || !SERVER_ID.equals(serverId)) {
            return diagnostics == null ? List.of() : diagnostics;
        }
        boolean pkgbuild = file != null
                && file.getFileName() != null
                && "PKGBUILD".equals(file.getFileName().toString());
        List<Diagnostic> kept = new ArrayList<>(diagnostics.size());
        for (Diagnostic d : diagnostics) {
            String code = shellcheckCode(d);
            if (code == null || !(NO_ZSH.equals(code) || pkgbuild && PKGBUILD_NOISE.contains(code))) {
                kept.add(d);
            }
        }
        return kept.size() == diagnostics.size() ? diagnostics : kept;
    }

    /** The diagnostic's ShellCheck code ({@code SC2086}), or null when it is not ShellCheck's. */
    private static @Nullable String shellcheckCode(@Nullable Diagnostic d) {
        if (d == null || d.getCode() == null || !"shellcheck".equalsIgnoreCase(String.valueOf(d.getSource()))) {
            return null;
        }
        var code = d.getCode();
        String s = code.isLeft() ? code.getLeft() : String.valueOf(code.getRight());
        if (s == null) {
            return null;
        }
        s = s.strip();
        return s.startsWith("SC") ? s : "SC" + s;
    }

    /** {@code hover} with a deadline when {@code session} is a Bash server that asks explainshell first. */
    static CompletableFuture<Hover> hoverDeadline(LanguageServerSession session, CompletableFuture<Hover> hover) {
        return hoverDeadline(session, hover, HOVER_TIMEOUT_MS);
    }

    /** As {@link #hoverDeadline(LanguageServerSession, CompletableFuture)} after {@code timeoutMs} (tests). */
    static CompletableFuture<Hover> hoverDeadline(
            LanguageServerSession session, CompletableFuture<Hover> hover, long timeoutMs) {
        if (!asksExplainshell(session)) {
            return hover;
        }
        // A copy: the deadline fails what the caller waits on, not the request (whose late answer is dropped).
        CompletableFuture<Hover> deadline = new CompletableFuture<>();
        hover.whenComplete((h, e) -> {
            if (e != null) {
                deadline.completeExceptionally(e);
            } else {
                deadline.complete(h);
            }
        });
        return deadline.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * What to say when {@code error} is the deadline of {@link #hoverDeadline} running out ("explainshell did
     * not answer …"), else null.
     */
    static @Nullable String hoverTimedOut(LanguageServerSession session, @Nullable Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (!(cause instanceof TimeoutException) || !asksExplainshell(session)) {
            return null;
        }
        return tr("status.lsp.explainshellNoAnswer", HOVER_TIMEOUT_MS / 1000, session.explainshellEndpoint());
    }

    /**
     * Whether a file of {@code serverId} with no root marker of its own, outside the project, is served from the
     * window's one shared root of loose files: TypeScript (review B7) and the Bash server (Bash review A9), whose
     * scripts in {@code ~/bin}, {@code /etc} and every other folder each started a ~125 MB node process before.
     * Only a script whose tools run on this machine: a container's or WSL's runs in its own folder there.
     */
    public static boolean sharesLooseRoot(String serverId, @Nullable Path projectRoot, Path file) {
        boolean shares = TypeScriptServer.SERVER_ID.equals(serverId)
                || SERVER_ID.equals(serverId) && com.editora.process.ExecTargets.runsHere(file);
        return shares && RootResolver.isLoose(projectRoot, file, LspServerRegistry.rootMarkersForServer(serverId));
    }

    private static boolean asksExplainshell(LanguageServerSession session) {
        return SERVER_ID.equals(session.serverId())
                && !session.explainshellEndpoint().isEmpty();
    }
}
