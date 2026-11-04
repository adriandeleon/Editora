package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.editora.config.TrustStore;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorService;
import com.editora.jstools.JsTools;
import org.jspecify.annotations.Nullable;

/**
 * Doctor's ESLint, Prettier and Biome rows (Formatting &amp; Linting) for a project with a {@code package.json}: what
 * the project configures, what it has installed in {@code node_modules} (the version its {@code package.json} names —
 * nothing is run), and whether its folder is trusted, which they need to run.
 */
final class JsToolDoctorRows {

    private JsToolDoctorRows() {}

    /**
     * The rows for {@code project} (none without a local project with a {@code package.json}).
     *
     * @param trust a copy of the trust list, read off the FX thread
     * @param eslintOn Settings' ESLint switch
     * @param formatter Settings' JS/TS formatter choice
     */
    static List<DoctorService.CheckSpec> rows(
            @Nullable Path project, TrustStore trust, boolean eslintOn, String formatter) {
        if (project == null
                || !EslintCoordinator.local(project)
                || !Files.isRegularFile(project.resolve("package.json"))) {
            return List.of();
        }
        Path probeFile = project.resolve("index.js"); // the root's own configuration and node_modules
        List<DoctorService.CheckSpec> out = new ArrayList<>();
        DoctorCheck eslint = row(JsTools.Kind.ESLINT);
        if (!eslintOn) {
            out.add(new DoctorService.CheckSpec(eslint.disabled(), null));
        } else {
            out.add(new DoctorService.CheckSpec(eslint, () -> {
                JsTools.Lookup in = JsTools.Lookup.local(project);
                JsTools.EslintSetup setup = JsTools.eslint(probeFile, in);
                if (setup.config().isEmpty()) {
                    return eslint.optional("doctor.tip.eslintNoConfig");
                }
                return found(eslint, setup.tool(), in, trust, "doctor.tip.eslintNotInstalled");
            }));
        }
        for (JsTools.Kind kind : List.of(JsTools.Kind.PRETTIER, JsTools.Kind.BIOME)) {
            DoctorCheck base = row(kind);
            boolean chosen = kind.binName().equals(formatter);
            out.add(new DoctorService.CheckSpec(base, () -> {
                JsTools.Lookup in = JsTools.Lookup.local(project);
                boolean configured = kind == JsTools.Kind.PRETTIER
                        ? JsTools.prettierConfigured(probeFile, in).isPresent()
                        : JsTools.firstConfig(probeFile, in, JsTools.BIOME_CONFIGS)
                                .isPresent();
                Optional<JsTools.Tool> tool = JsTools.tool(kind, probeFile, in);
                if (!configured && !chosen && tool.isEmpty()) {
                    return base.optional("doctor.tip.jsFormatterNotUsed", kind.displayName());
                }
                return found(base, tool, in, trust, "doctor.tip.jsFormatterNotInstalled");
            }));
        }
        return out;
    }

    private static DoctorCheck row(JsTools.Kind kind) {
        return DoctorCheck.checking("js." + kind.binName(), "format", kind.displayName(), kind.binName())
                .withSettings("lsp");
    }

    /** A configured tool's row: its version and path, or why it cannot run. */
    private static DoctorCheck found(
            DoctorCheck base, Optional<JsTools.Tool> tool, JsTools.Lookup in, TrustStore trust, String missingTip) {
        if (tool.isEmpty()) {
            return base.warn("", missingTip, base.label(), base.command());
        }
        JsTools.Tool t = tool.get();
        String detail = JsTools.installedVersion(t, in).map(v -> v + " — ").orElse("") + t.script();
        return trust.isTrusted(t.root())
                ? base.ok(detail)
                : base.warn(detail, "doctor.tip.jsToolUntrusted", base.label());
    }
}
