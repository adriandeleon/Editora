package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.lsp.TypeScriptServer;

import static com.editora.i18n.Messages.tr;

/**
 * The words of "Trust This Project's Settings". When the folder brings nothing but its own TypeScript, the prompt
 * said its {@code .editora/settings.json} chooses programs (there was none) and listed raw paths, an Astro one
 * among them in a folder without Astro (JS review B110). Such a request is now said as what it is — "TypeScript
 * 5.4.5 in node_modules/typescript, run by the TypeScript / JavaScript server" — with a body about that alone.
 */
final class TrustPromptText {

    private TrustPromptText() {}

    /** The request prefixes of a folder's TypeScript SDK ({@code "typescript: <tsserver.js>"}, {@code "astro: <lib>"}). */
    static final String TYPESCRIPT = TypeScriptServer.SERVER_ID + ": ";

    static final String ASTRO = "astro: ";

    /** Whether {@code request} is a folder's TypeScript SDK, not a setting from its settings file. Pure. */
    static boolean sdkRequest(String request) {
        return request.startsWith(TYPESCRIPT) || request.startsWith(ASTRO);
    }

    /** The prompt's body for {@code requests} on {@code root}. Pure apart from the package.json read. */
    static String body(Path root, List<String> requests) {
        List<String> lines = new ArrayList<>();
        boolean settings = false;
        for (String r : requests) {
            if (sdkRequest(r)) {
                lines.add(sdkLine(root, r));
            } else {
                settings = true;
                lines.add(r);
            }
        }
        String list = String.join("\n", lines);
        return settings
                ? tr("dialog.lsp.trust.body", root.toString(), list)
                : tr("dialog.lsp.trust.sdkBody", root.toString(), list);
    }

    /** The prompt's header: about the folder's settings, or about its own TypeScript when that is all it brings. */
    static String header(String folder, List<String> requests) {
        return requests.stream().allMatch(TrustPromptText::sdkRequest) && !requests.isEmpty()
                ? tr("dialog.lsp.trust.sdkHeader", folder)
                : tr("dialog.lsp.trust.header", folder);
    }

    /** One SDK request as the user reads it. */
    static String sdkLine(Path root, String request) {
        boolean astro = request.startsWith(ASTRO);
        Path at = Path.of(request.substring(astro ? ASTRO.length() : TYPESCRIPT.length()));
        // …/node_modules/typescript/lib/tsserver.js (TypeScript server) or …/node_modules/typescript/lib (Astro)
        Path lib = astro ? at : at.getParent();
        Path pkg = lib == null ? null : lib.getParent();
        String version = pkg == null
                ? ""
                : TypeScriptServer.sdkVersion(pkg.resolve("lib").resolve("tsserver.js"));
        String where = pkg == null ? at.toString() : relative(root, pkg);
        return tr(
                astro ? "dialog.lsp.trust.sdkAstro" : "dialog.lsp.trust.sdkTypescript",
                version.isEmpty() ? "TypeScript" : "TypeScript " + version,
                where);
    }

    private static String relative(Path root, Path p) {
        Path r = root.toAbsolutePath().normalize();
        Path a = p.toAbsolutePath().normalize();
        return a.startsWith(r) && !a.equals(r) ? r.relativize(a).toString().replace('\\', '/') : a.toString();
    }
}
