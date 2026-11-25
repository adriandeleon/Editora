package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.config.ProjectSettingsFile;
import com.editora.process.ProcessRunner;
import com.editora.toolchain.JkitCli;
import com.editora.toolchain.JkitInstalls;
import com.editora.toolchain.ProjectToolchain;
import com.editora.toolchain.SdkmanIds;
import com.editora.toolchain.SdkmanInstalls;
import com.editora.toolchain.SdkmanRc;
import com.editora.toolchain.ToolInstall;
import com.editora.toolchain.ToolRequest;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands that choose and install a project's JDK, Maven and Gradle, and drive jkit (jkit-project.dev):
 * install what the project asks for, install another version, upgrade, make one jkit's default, remove one, and
 * set the project's own JDK, Maven or Gradle in its {@code .sdkmanrc} or {@code .editora/settings.json}. jkit runs
 * off the FX thread; its progress shows in the status line and the background-task indicator.
 */
final class ToolchainCommands {

    private final CoordinatorHost host;
    private final Supplier<ProjectScope> scope;
    /** Re-reads the project's toolchain after an install, a removal or a written file. */
    private final Runnable reload;

    /** Where jkit and the disk scans run (a seam: a test runs them in place). */
    Executor work = com.editora.process.BlockingTasks.named("toolchain");

    ToolchainCommands(CoordinatorHost host, Supplier<ProjectScope> scope, Runnable reload) {
        this.host = host;
        this.scope = scope;
        this.reload = reload;
        JkitCli.useProgram(host.settings().getJkitPath()); // Settings ▸ Build Tools ▸ jkit program
    }

    // --- the project's own tools ----------------------------------------------------------------------------

    /**
     * Installs, with jkit, every tool the project names that is missing: what its project files pin through
     * {@code jkit sync} (as a shell or CI job would), what {@code .editora/settings.json} names one by one.
     */
    void installProjectTools() {
        List<ProjectToolchain.Choice> missing = scope.get().toolchain().missing();
        Path root = scope.get().root();
        if (root == null) {
            host.setStatus(tr("toolchain.noProject"));
            return;
        }
        if (missing.isEmpty()) {
            host.setStatus(tr("toolchain.nothingMissing"));
            return;
        }
        boolean viaSync = missing.stream().anyMatch(c -> c.origin() == ProjectToolchain.Origin.JKIT);
        withJkit(jkit -> {
            List<List<String>> installs = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (ProjectToolchain.Choice c : missing) {
                if (c.origin() == ProjectToolchain.Origin.JKIT) {
                    continue; // jkit sync installs these
                }
                List<String> args = jkitArgs(c.request());
                if (args == null) {
                    unknown.add(c.request().raw());
                } else {
                    installs.add(args);
                }
            }
            if (!unknown.isEmpty()) {
                Platform.runLater(() -> host.setError(tr("toolchain.cannotInstall", String.join(", ", unknown))));
            }
            if (viaSync) {
                syncWith(jkit, root, () -> work.execute(() -> runInstalls(jkit, installs)));
            } else {
                runInstalls(jkit, installs);
            }
        });
    }

    /** Installs what the project's files pin with {@code jkit sync}, after a dry run says what it would do. */
    void syncProject() {
        Path root = scope.get().root();
        if (root == null || !com.editora.process.ExecTargets.runsHere(root)) {
            host.setStatus(tr("toolchain.noProject"));
            return;
        }
        withJkit(jkit -> syncWith(jkit, root, () -> {}));
    }

    /**
     * {@code jkit sync} in {@code root} (off the FX thread): a dry run first, then the sync; a pin whose download
     * jkit cannot verify is installed only when the user says so. {@code then} runs after, whatever happened.
     */
    private void syncWith(String jkit, Path root, Runnable then) {
        JkitCli.Outcome dry = JkitCli.sync(jkit, root, true, false, null, null);
        Platform.runLater(() -> {
            if (!dry.ok()) {
                host.setError(tr("toolchain.jkit.failed", dry.error()));
                then.run();
                return;
            }
            List<JkitCli.SyncLine> lines = JkitCli.parseSync(dry.doc());
            boolean refuses = lines.stream().anyMatch(l -> "would-refuse".equals(l.status()));
            boolean installs = refuses || lines.stream().anyMatch(l -> "would-install".equals(l.status()));
            if (!installs) {
                host.setStatus(lines.isEmpty() ? tr("toolchain.sync.nothingPinned") : tr("toolchain.nothingMissing"));
                then.run();
                return;
            }
            boolean allowUnverified = refuses && Confirm.ask(host.window(), unverifiedQuestion(lines));
            work.execute(() -> {
                runJkit(
                        jkit,
                        tr("toolchain.sync.running"),
                        cancel -> JkitCli.sync(jkit, root, false, allowUnverified, liveStatus(), cancel),
                        tr("toolchain.sync.done"));
                then.run();
            });
        });
    }

    /** The words of "Install builds jkit cannot verify?", naming them. */
    static Confirm.Question unverifiedQuestion(List<JkitCli.SyncLine> lines) {
        List<String> rows = new ArrayList<>();
        for (JkitCli.SyncLine l : lines) {
            if ("would-refuse".equals(l.status())) {
                rows.add("• " + toolName(l.tool()) + " " + l.label());
            }
        }
        return new Confirm.Question(
                tr("toolchain.sync.unverified.title"),
                tr("toolchain.sync.unverified.header"),
                String.join("\n", rows) + "\n\n" + tr("toolchain.sync.unverified.message"),
                tr("toolchain.sync.unverified.action"));
    }

    /**
     * The jkit arguments that install {@code request}, or null when jkit cannot name it: a folder, or a JDK whose
     * SDKMAN vendor suffix Editora does not know (installing the default vendor instead would install another JDK).
     */
    static @Nullable List<String> jkitArgs(ToolRequest request) {
        if (ToolInstall.JDK.equals(request.tool())
                && request.vendor() == null
                && request.raw().contains("-")) {
            return null;
        }
        return request.jkitInstallArgs();
    }

    /**
     * Picks the project's JDK, Maven or Gradle among the installs (or back to the user's own), then asks where to
     * keep the choice: the project's {@code .sdkmanrc}, which SDKMAN and teammates read too, or Editora's
     * {@code .editora/settings.json}.
     */
    void setProjectTool(String tool) {
        Path root = scope.get().root();
        if (root == null || !com.editora.process.ExecTargets.runsHere(root)) {
            host.setStatus(tr("toolchain.noProject"));
            return;
        }
        AutoCloseable task = host.startBackgroundTask(tr("toolchain.scanning"));
        work.execute(() -> {
            ProjectToolchain.Installs found = ProjectToolchain.scan(
                    JkitInstalls.home(),
                    SdkmanInstalls.dir(),
                    ToolInstall.JDK.equals(tool) || ToolInstall.GRAALVM.equals(tool)
                            ? com.editora.lsp.JavaRuntimes.discover().stream()
                                    .map(com.editora.lsp.JavaRuntimes.Jdk::path)
                                    .toList()
                            : List.of());
            Platform.runLater(() -> {
                close(task);
                List<Pick> items = new ArrayList<>();
                items.add(new Pick(null)); // the user's own (Settings), clearing the project's choice
                found.of(tool).forEach(i -> items.add(new Pick(i)));
                QuickOpen<Pick> picker = new QuickOpen<>(
                        tr("toolchain.setProject.title", toolName(tool)),
                        tr("toolchain.setProject.prompt"),
                        () -> items,
                        p -> p.install() == null
                                ? tr("toolchain.setProject.usersOwn")
                                : p.install().label(),
                        p -> p.install() == null
                                ? ""
                                : p.install().source() + " · " + p.install().home(),
                        p -> {
                            if (p != null) {
                                choose(root, tool, p.install());
                            }
                        });
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            });
        });
    }

    /** A row of the project-tool picker: an install, or none (the user's own). */
    record Pick(@Nullable ToolInstall install) {}

    private void choose(Path root, String tool, @Nullable ToolInstall install) {
        if (install == null) {
            // Clear it from every place Editora reads it from.
            ProjectSettingsFile.setLater(root, "toolchain", tool, null, failure -> {
                if (failure != null) {
                    host.setError(tr("toolchain.writeFailed", failure.getMessage()));
                }
            });
            work.execute(() -> {
                try {
                    if (!SdkmanRc.read(root).isEmpty()) {
                        SdkmanRc.remove(root, SdkmanInstalls.candidate(tool));
                    }
                } catch (java.io.IOException e) {
                    Platform.runLater(() -> host.setError(tr("toolchain.writeFailed", e.getMessage())));
                }
                String jkit = JkitCli.locate();
                if (jkit != null && hasJkitToml(root)) {
                    JkitCli.Outcome unpinned = JkitCli.run(
                            jkit, root, List.of("unpin", tool, "--here"), java.time.Duration.ofSeconds(30), null, null);
                    if (!unpinned.ok()) {
                        Platform.runLater(() -> host.setError(tr("toolchain.jkit.failed", unpinned.error())));
                    }
                }
                Platform.runLater(() -> {
                    reload.run();
                    host.setStatus(tr("toolchain.setProject.cleared", toolName(tool)));
                });
            });
            return;
        }
        String sdkmanId = ToolInstall.GRAALVM.equals(tool) ? null : sdkmanValue(install);
        work.execute(() -> {
            boolean jkitHere = JkitCli.locate() != null;
            Where preferred = preferredWhere(
                    hasJkitToml(root), java.nio.file.Files.isRegularFile(SdkmanRc.fileFor(root)), jkitHere);
            Platform.runLater(() -> {
                Where where = askWhere(install, sdkmanId, jkitHere, preferred);
                if (where != null) {
                    keep(root, tool, install, sdkmanId, where);
                }
            });
        });
    }

    private void keep(Path root, String tool, ToolInstall install, @Nullable String sdkmanId, Where where) {
        if (where == Where.JKIT_TOML) {
            withJkit(jkit -> pinInJkitToml(jkit, root, tool, install));
            return;
        }
        if (where == Where.SDKMANRC && sdkmanId != null) {
            work.execute(() -> {
                try {
                    SdkmanRc.write(root, SdkmanInstalls.candidate(tool), sdkmanId);
                    Platform.runLater(() -> {
                        reload.run();
                        host.setStatus(tr("toolchain.setProject.done", install.label(), SdkmanRc.FILE));
                    });
                } catch (java.io.IOException e) {
                    Platform.runLater(() -> host.setError(tr("toolchain.writeFailed", e.getMessage())));
                }
            });
            return;
        }
        ProjectSettingsFile.setLater(root, "toolchain", tool, settingsValue(install), failure -> {
            if (failure != null) {
                host.setError(tr("toolchain.writeFailed", failure.getMessage()));
                return;
            }
            reload.run();
            host.setStatus(tr("toolchain.setProject.done", install.label(), ".editora/settings.json"));
        });
    }

    private static boolean hasJkitToml(Path root) {
        return java.nio.file.Files.isRegularFile(root.resolve(com.editora.toolchain.ProjectFiles.JKIT_TOML))
                || java.nio.file.Files.isRegularFile(root.resolve(".jkit.toml"));
    }

    /**
     * Pins {@code install} in the project's {@code jkit.toml} with {@code jkit pin --here} (off the FX thread). A
     * new {@code jkit.toml} replaces the folder's {@code .sdkmanrc}, {@code .tool-versions} and
     * {@code .java-version} for jkit, so the pins they made are carried into it first.
     */
    private void pinInJkitToml(String jkit, Path root, String tool, ToolInstall install) {
        List<String> carried = new ArrayList<>();
        if (!hasJkitToml(root)) {
            JkitCli.Outcome status = JkitCli.status(jkit, root);
            if (status.ok()) {
                for (JkitCli.StatusTool t : JkitCli.parseStatus(status.doc()).tools()) {
                    JkitCli.PinSpec pin = t.pin();
                    if (pin != null
                            && !t.tool().equals(tool)
                            && JkitCli.pin(jkit, root, pin, true).ok()) {
                        carried.add(toolName(t.tool()) + " " + pin.label());
                    }
                }
            }
        }
        JkitCli.PinSpec pin = pinFor(tool, install);
        boolean ok = runJkit(
                jkit,
                tr("toolchain.pin.running", install.label()),
                cancel -> JkitCli.pin(jkit, root, pin, true),
                tr("toolchain.setProject.done", install.label(), com.editora.toolchain.ProjectFiles.JKIT_TOML));
        if (ok && !carried.isEmpty()) {
            Platform.runLater(() -> host.setStatus(tr("toolchain.pin.carried", String.join(", ", carried))));
        }
    }

    /** The pin that names exactly {@code install} for {@code tool} (a GraalVM install pins the graalvm slot). Pure. */
    static JkitCli.PinSpec pinFor(String tool, ToolInstall install) {
        boolean vendors = ToolInstall.JDK.equals(tool) || ToolInstall.GRAALVM.equals(tool);
        return new JkitCli.PinSpec(tool, install.version(), vendors ? install.vendor() : null, vendors && install.fx());
    }

    /** Where a project's choice is kept. */
    enum Where {
        JKIT_TOML,
        SDKMANRC,
        PROJECT_SETTINGS
    }

    /**
     * Which answer "where should this go?" defaults to: the {@code jkit.toml} the project has, else its
     * {@code .sdkmanrc}, else a new {@code jkit.toml} when jkit is installed. Pure.
     */
    static Where preferredWhere(boolean hasJkitToml, boolean hasSdkmanrc, boolean jkitInstalled) {
        if (hasJkitToml && jkitInstalled) {
            return Where.JKIT_TOML;
        }
        if (hasSdkmanrc) {
            return Where.SDKMANRC;
        }
        return jkitInstalled ? Where.JKIT_TOML : Where.SDKMANRC;
    }

    /** The words of "where should this choice go?". Answers: jkit.toml (0), .sdkmanrc (1), project settings (2). */
    static Confirm.Choice whereQuestion(ToolInstall install, @Nullable String sdkmanId) {
        return Confirm.choice(
                tr("toolchain.where.title"),
                tr("toolchain.where.header", install.label()),
                tr(
                        "toolchain.where.message",
                        sdkmanId == null ? "—" : SdkmanInstalls.candidate(install.tool()) + "=" + sdkmanId),
                tr("toolchain.where.jkitToml"),
                tr("toolchain.where.sdkmanrc"),
                tr("toolchain.where.settings"));
    }

    private @Nullable Where askWhere(ToolInstall install, @Nullable String sdkmanId, boolean jkit, Where preferred) {
        Confirm.Choice words = whereQuestion(install, sdkmanId);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.window());
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType toml = new ButtonType(words.answer(0));
        ButtonType sdkmanrc = new ButtonType(words.answer(1));
        ButtonType settings = new ButtonType(words.answer(2));
        ButtonType cancel = Dialogs.cancel();
        List<ButtonType> buttons = new ArrayList<>();
        if (jkit) {
            buttons.add(toml);
        }
        if (sdkmanId != null) {
            buttons.add(sdkmanrc);
        }
        buttons.add(settings);
        buttons.add(cancel);
        alert.getButtonTypes().setAll(buttons);
        ButtonType first = preferred == Where.JKIT_TOML && jkit
                ? toml
                : preferred == Where.SDKMANRC && sdkmanId != null ? sdkmanrc : buttons.getFirst();
        Dialogs.defaultTo(alert, first);
        ButtonType answer = Dialogs.showAndWait(alert).orElse(cancel);
        return answer == toml
                ? Where.JKIT_TOML
                : answer == sdkmanrc ? Where.SDKMANRC : answer == settings ? Where.PROJECT_SETTINGS : null;
    }

    /**
     * How {@code .sdkmanrc} names {@code install}: SDKMAN's own identifier, a JDK's identifier made from its version
     * and vendor, Maven's and Gradle's version; null when SDKMAN has no name for its vendor (or it has none).
     */
    static @Nullable String sdkmanValue(ToolInstall install) {
        if (ToolInstall.SDKMAN.equals(install.source())) {
            return install.id();
        }
        if (!ToolInstall.JDK.equals(install.tool())) {
            return ToolInstall.OTHER.equals(install.source()) ? null : versionWithoutBuild(install.version());
        }
        return SdkmanIds.format(install.version(), install.vendor(), install.fx());
    }

    /**
     * How {@code .editora/settings.json} names {@code install}: jkit's or SDKMAN's id, which every machine with
     * that install answers; a JDK found elsewhere by its version and vendor; else its folder (this machine only).
     */
    static String settingsValue(ToolInstall install) {
        if (!ToolInstall.OTHER.equals(install.source())) {
            return install.id();
        }
        if (ToolInstall.JDK.equals(install.tool()) && install.vendor() != null) {
            return install.version() + "-" + install.vendor() + (install.fx() ? "-fx" : "");
        }
        return install.home().toString();
    }

    private static String versionWithoutBuild(String version) {
        int plus = version.indexOf('+');
        return plus > 0 ? version.substring(0, plus) : version;
    }

    // --- jkit -----------------------------------------------------------------------------------------------

    /** Picks a JDK, Maven or Gradle that jkit can install (newest per vendor and major for a JDK), installs it. */
    void installWithJkit(String tool) {
        withJkit(jkit -> {
            JkitCli.Outcome found = JkitCli.search(jkit, tool, null, null);
            Platform.runLater(() -> {
                if (!found.ok()) {
                    host.setError(tr("toolchain.jkit.failed", found.error()));
                    return;
                }
                List<JkitCli.Available> builds = JkitCli.parseSearch(found.doc()).stream()
                        .filter(a -> !a.preRelease())
                        .toList();
                QuickOpen<JkitCli.Available> picker = new QuickOpen<>(
                        tr("toolchain.install.title", toolName(tool)),
                        tr("toolchain.install.prompt"),
                        () -> builds,
                        ToolchainCommands::availableLabel,
                        a -> a.installed() ? tr("toolchain.install.installed") : "",
                        a -> {
                            if (a != null && !a.installed()) {
                                work.execute(() -> runInstalls(jkit, List.of(a.installArgs())));
                            } else if (a != null) {
                                host.setStatus(tr("toolchain.install.already", availableLabel(a)));
                            }
                        });
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            });
        });
    }

    /** {@code JDK 25 · temurin · 25.0.1+8 · LTS}. */
    static String availableLabel(JkitCli.Available a) {
        StringBuilder sb = new StringBuilder(toolName(a.tool()));
        if (ToolInstall.JDK.equals(a.tool())) {
            sb.append(' ').append(a.major());
        }
        if (a.vendor() != null) {
            sb.append(" · ").append(a.vendor());
        }
        sb.append(" · ").append(a.version());
        if (a.lts()) {
            sb.append(" · LTS");
        }
        if (!a.maintained()) {
            sb.append(" · ").append(tr("toolchain.install.endOfLife"));
        }
        return sb.toString();
    }

    /** Upgrades every jkit install to the newest build of its release line, after saying what would change. */
    void upgradeJkit() {
        withJkit(jkit -> {
            JkitCli.Outcome dry = JkitCli.upgrade(jkit, true, null, null);
            Platform.runLater(() -> {
                if (!dry.ok()) {
                    host.setError(tr("toolchain.jkit.failed", dry.error()));
                    return;
                }
                List<JkitCli.UpgradeLine> available = JkitCli.parseUpgrade(dry.doc()).stream()
                        .filter(JkitCli.UpgradeLine::available)
                        .toList();
                if (available.isEmpty()) {
                    host.setStatus(tr("toolchain.upgrade.upToDate"));
                    return;
                }
                if (!Confirm.ask(host.window(), upgradeQuestion(available))) {
                    return;
                }
                work.execute(() -> runJkit(
                        jkit,
                        tr("toolchain.upgrade.running"),
                        cancel -> JkitCli.upgrade(jkit, false, liveStatus(), cancel),
                        tr("toolchain.upgrade.done")));
            });
        });
    }

    /** The words of "Upgrade these?", one line per release line ({@code JDK 21 temurin: 21.0.5 → 21.0.9}). */
    static Confirm.Question upgradeQuestion(List<JkitCli.UpgradeLine> lines) {
        List<String> rows = new ArrayList<>();
        for (JkitCli.UpgradeLine l : lines) {
            rows.add("• " + toolName(l.tool()) + " " + l.major() + (l.vendor() == null ? "" : " " + l.vendor()) + ": "
                    + l.from() + " → " + l.to());
        }
        return new Confirm.Question(
                tr("toolchain.upgrade.title"),
                tr("toolchain.upgrade.header", lines.size()),
                String.join("\n", rows) + "\n\n" + tr("toolchain.upgrade.keeps"),
                tr("toolchain.upgrade.action"));
    }

    /** Makes one jkit install the default jkit gives new shells and apps ({@code jkit use --global}). */
    void setJkitDefault() {
        pickJkitInstall(
                tr("toolchain.default.title"),
                install -> withJkit(jkit -> runJkit(
                        jkit,
                        tr("toolchain.default.running", install.label()),
                        cancel -> JkitCli.useGlobal(jkit, install),
                        tr("toolchain.default.done", install.label()))));
    }

    /** Removes one jkit install, after asking. jkit refuses one that is its default. */
    void removeJkitInstall() {
        pickJkitInstall(tr("toolchain.remove.title"), install -> {
            if (!Confirm.ask(host.window(), removeQuestion(install))) {
                return;
            }
            withJkit(jkit -> runJkit(
                    jkit,
                    tr("toolchain.remove.running", install.label()),
                    cancel -> JkitCli.remove(jkit, install),
                    tr("toolchain.remove.done", install.label())));
        });
    }

    /** The words of "Remove this install?". */
    static Confirm.Question removeQuestion(ToolInstall install) {
        return new Confirm.Question(
                tr("toolchain.remove.title"),
                tr("toolchain.remove.header", install.label()),
                tr("toolchain.remove.message", install.home().toString()),
                tr("toolchain.remove.action"));
    }

    // --- jkit's other commands --------------------------------------------------------------------------------

    /** Shows what jkit says about this project: its files, each tool's pin, what that picks, and jkit's hints. */
    void showStatus() {
        Path root = scope.get().root();
        withJkit(jkit -> {
            String version = JkitCli.version(jkit);
            Path dir = root != null && com.editora.process.ExecTargets.runsHere(root) ? root : null;
            JkitCli.Outcome status = dir == null
                    ? JkitCli.run(jkit, List.of("status"), java.time.Duration.ofSeconds(30))
                    : JkitCli.status(jkit, dir);
            Platform.runLater(() -> {
                if (!status.ok()) {
                    host.setError(tr("toolchain.jkit.failed", status.error()));
                    return;
                }
                report(
                        tr("toolchain.status.title"),
                        tr("toolchain.status.header", version == null ? "?" : version, jkit),
                        statusText(JkitCli.parseStatus(status.doc())));
            });
        });
    }

    /** The status popup's text: the project's files and warnings, then a line or two per tool. Pure. */
    static String statusText(JkitCli.Status status) {
        StringBuilder sb = new StringBuilder();
        if (status.projectDir() == null) {
            sb.append(tr("toolchain.status.noProject"));
        } else {
            sb.append(tr("toolchain.status.project", status.projectDir().toString(), status.fileNames()));
        }
        sb.append('\n');
        if (status.projectSkipped() != null) {
            sb.append("! ").append(status.projectSkipped()).append('\n');
        }
        if (status.projectError() != null) {
            sb.append("✗ ").append(status.projectError()).append('\n');
        }
        for (String w : status.warnings()) {
            sb.append("! ").append(w).append('\n');
        }
        for (JkitCli.StatusTool t : status.tools()) {
            sb.append('\n').append(toolName(t.tool())).append(": ");
            if (t.pin() != null) {
                sb.append(tr("toolchain.status.pinned", t.pin().label()));
                sb.append(" → ")
                        .append(
                                t.pinInstalled() == null
                                        ? tr("toolchain.status.notInstalled")
                                        : t.pinInstalled().home().toString());
            } else if (t.global() != null) {
                sb.append(tr("toolchain.status.global", t.global()));
            } else {
                sb.append(tr("toolchain.status.none"));
            }
            if (t.hint() != null) {
                sb.append("\n    ").append(t.hint());
            }
        }
        return sb.toString();
    }

    /** Runs {@code jkit doctor} (with its network check when asked) and shows each check and its fix. */
    void doctor(boolean network) {
        Path root = scope.get().root();
        withJkit(jkit -> {
            Path dir = root != null && com.editora.process.ExecTargets.runsHere(root) ? root : null;
            JkitCli.Outcome out = JkitCli.doctor(jkit, dir, network);
            Platform.runLater(() -> {
                List<JkitCli.DoctorCheck> checks = JkitCli.parseDoctor(out.doc());
                if (checks.isEmpty()) {
                    host.setError(tr("toolchain.jkit.failed", out.error() == null ? "no checks" : out.error()));
                    return;
                }
                report(tr("toolchain.doctor.title"), doctorHeader(checks), doctorText(checks));
            });
        });
    }

    /** {@code 9 fine, 1 to look at, 1 failed}. Pure. */
    static String doctorHeader(List<JkitCli.DoctorCheck> checks) {
        long fail = checks.stream().filter(c -> "fail".equals(c.level())).count();
        long warn = checks.stream().filter(c -> "warn".equals(c.level())).count();
        return fail > 0
                ? tr("toolchain.doctor.failed", fail)
                : warn > 0 ? tr("toolchain.doctor.warnings", warn) : tr("toolchain.doctor.ok");
    }

    /** One line per check, marked ✓ ! ✗ or ·, with jkit's fix under it. Pure. */
    static String doctorText(List<JkitCli.DoctorCheck> checks) {
        StringBuilder sb = new StringBuilder();
        for (JkitCli.DoctorCheck c : checks) {
            String mark = switch (c.level()) {
                case "ok" -> "✓";
                case "warn" -> "!";
                case "fail" -> "✗";
                default -> "·";
            };
            sb.append(mark).append(' ').append(c.message()).append('\n');
            if (c.fix() != null) {
                sb.append("    ").append(tr("toolchain.doctor.fix", c.fix())).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Removes builds a newer one of the same release line replaced, after saying which and how much space. */
    void prune() {
        withJkit(jkit -> {
            JkitCli.Outcome dry = JkitCli.prune(jkit, true);
            Platform.runLater(() -> {
                if (!dry.ok()) {
                    host.setError(tr("toolchain.jkit.failed", dry.error()));
                    return;
                }
                JkitCli.Prune would = JkitCli.parsePrune(dry.doc());
                if (would.isEmpty()) {
                    host.setStatus(tr("toolchain.prune.nothing"));
                    return;
                }
                if (!Confirm.ask(host.window(), pruneQuestion(would))) {
                    return;
                }
                work.execute(() -> runJkit(
                        jkit,
                        tr("toolchain.prune.running"),
                        cancel -> JkitCli.prune(jkit, false),
                        tr("toolchain.prune.done", megabytes(would.bytes()))));
            });
        });
    }

    /** The words of "Remove these older builds?". Pure. */
    static Confirm.Question pruneQuestion(JkitCli.Prune would) {
        List<String> rows = new ArrayList<>();
        for (ToolInstall i : would.builds()) {
            rows.add("• " + i.label());
        }
        int other = would.leftovers().size() + would.cache().size();
        if (other > 0) {
            rows.add("• " + tr("toolchain.prune.leftovers", other));
        }
        return new Confirm.Question(
                tr("toolchain.prune.title"),
                tr("toolchain.prune.header", megabytes(would.bytes())),
                String.join("\n", rows) + "\n\n" + tr("toolchain.prune.keeps"),
                tr("toolchain.prune.action"));
    }

    static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    /** Checks for a newer jkit and updates it: in place for an archive install, else says the package command. */
    void selfUpdate() {
        withJkit(jkit -> {
            JkitCli.Outcome check = JkitCli.selfUpdate(jkit, true);
            Platform.runLater(() -> {
                JkitCli.SelfUpdate u = JkitCli.parseSelfUpdate(check.doc());
                if (!check.ok() || u == null) {
                    host.setError(tr("toolchain.jkit.failed", check.error()));
                    return;
                }
                switch (u.status()) {
                    case "up-to-date" -> host.setStatus(tr("toolchain.selfUpdate.upToDate", u.current()));
                    case "managed" -> {
                        if (u.latest() != null && !u.latest().equals(u.current())) {
                            report(
                                    tr("toolchain.selfUpdate.title"),
                                    tr("toolchain.selfUpdate.available", u.current(), u.latest()),
                                    tr(
                                            "toolchain.selfUpdate.managed",
                                            u.installedWith() == null ? "?" : u.installedWith(),
                                            u.command() == null ? "" : u.command()));
                        } else {
                            host.setStatus(tr("toolchain.selfUpdate.upToDate", u.current()));
                        }
                    }
                    default -> {
                        if (!Confirm.ask(
                                host.window(),
                                tr("toolchain.selfUpdate.title"),
                                tr(
                                        "toolchain.selfUpdate.available",
                                        u.current(),
                                        u.latest() == null ? "?" : u.latest()),
                                tr("toolchain.selfUpdate.action"))) {
                            return;
                        }
                        work.execute(() -> runJkit(
                                jkit,
                                tr("toolchain.selfUpdate.running"),
                                cancel -> JkitCli.selfUpdate(jkit, false),
                                tr("toolchain.selfUpdate.done", u.latest() == null ? "" : u.latest())));
                    }
                }
            });
        });
    }

    /** The vendors offered for jkit's {@code jdk.vendor} ({@code graalvm} false) or {@code graalvm.vendor}. Pure. */
    static List<String> vendorChoices(boolean graalvm) {
        List<String> all = List.of(
                "temurin",
                "zulu",
                "corretto",
                "microsoft",
                "liberica",
                "oracle",
                "openjdk",
                "sapmachine",
                "semeru",
                "dragonwell",
                "kona",
                "jetbrains",
                "redhat",
                "graalvm-ce",
                "graalvm",
                "liberica-nik",
                "mandrel");
        return graalvm ? all.stream().filter(ToolInstall::graalvmVendor).toList() : all;
    }

    /** Picks the vendor jkit installs when none is named ({@code jdk.vendor}, or {@code graalvm.vendor}). */
    void setDefaultVendor(boolean graalvm) {
        String key = graalvm ? "graalvm.vendor" : "jdk.vendor";
        List<String> items = new ArrayList<>();
        items.add(""); // jkit's own default
        items.addAll(vendorChoices(graalvm));
        QuickOpen<String> picker = new QuickOpen<>(
                tr(graalvm ? "toolchain.vendor.graalvmTitle" : "toolchain.vendor.jdkTitle"),
                tr("toolchain.vendor.prompt"),
                () -> items,
                v -> v.isEmpty() ? tr("toolchain.vendor.jkitDefault") : v,
                v -> "",
                v -> {
                    if (v != null) {
                        withJkit(jkit -> runJkit(
                                jkit,
                                tr("toolchain.config.running"),
                                cancel -> JkitCli.configSet(jkit, key, v.isEmpty() ? null : v),
                                tr(
                                        "toolchain.config.done",
                                        key,
                                        v.isEmpty() ? tr("toolchain.vendor.jkitDefault") : v)));
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Turns jkit's switching on {@code cd} ({@code shell.auto_switch}) on or off. */
    void toggleAutoSwitch() {
        withJkit(jkit -> {
            JkitCli.Outcome config = JkitCli.config(jkit);
            if (!config.ok()) {
                Platform.runLater(() -> host.setError(tr("toolchain.jkit.failed", config.error())));
                return;
            }
            boolean on = JkitCli.parseConfig(config.doc()).stream()
                    .anyMatch(c -> "shell.auto_switch".equals(c.key()) && "true".equals(c.value()));
            runJkit(
                    jkit,
                    tr("toolchain.config.running"),
                    cancel -> JkitCli.configSet(jkit, "shell.auto_switch", on ? "false" : "true"),
                    tr(on ? "toolchain.autoSwitch.off" : "toolchain.autoSwitch.on"));
        });
    }

    /** Adds jkit's hook to the user's shell startup file ({@code jkit hook --install}), after asking. */
    void installShellHook() {
        Confirm.Choice words = Confirm.choice(
                tr("toolchain.hook.title"),
                tr("toolchain.hook.header"),
                tr("toolchain.hook.message"),
                tr("toolchain.hook.withAutoSwitch"),
                tr("toolchain.hook.only"));
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.window());
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType auto = new ButtonType(words.answer(0));
        ButtonType only = new ButtonType(words.answer(1));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(auto, only, cancel);
        Dialogs.defaultTo(alert, only);
        ButtonType answer = Dialogs.showAndWait(alert).orElse(cancel);
        if (answer == cancel) {
            return;
        }
        boolean autoSwitch = answer == auto;
        withJkit(jkit -> {
            ProcessRunner.Result r = JkitCli.installHook(jkit, autoSwitch);
            String text = (r.out() + "\n" + r.err()).strip();
            Platform.runLater(() -> {
                if (r.ok()) {
                    report(tr("toolchain.hook.title"), tr("toolchain.hook.done"), text);
                } else {
                    host.setError(tr("toolchain.jkit.failed", text.isEmpty() ? "exit " + r.exit() : text));
                }
            });
        });
    }

    /** Lists every jkit install; choosing one offers to make it the default, copy its folder, or remove it. */
    void showInstalls() {
        pickJkitInstall(tr("toolchain.installs.title"), install -> {
            List<String> actions = List.of("default", "copy", "remove");
            QuickOpen<String> picker = new QuickOpen<>(
                    install.label(),
                    tr("toolchain.installs.prompt"),
                    () -> actions,
                    a -> tr("toolchain.installs." + a),
                    a -> "",
                    a -> {
                        if (a == null) {
                            return;
                        }
                        switch (a) {
                            case "default" ->
                                withJkit(jkit -> runJkit(
                                        jkit,
                                        tr("toolchain.default.running", install.label()),
                                        cancel -> JkitCli.useGlobal(jkit, install),
                                        tr("toolchain.default.done", install.label())));
                            case "copy" -> {
                                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                                content.putString(install.home().toString());
                                javafx.scene.input.Clipboard.getSystemClipboard()
                                        .setContent(content);
                                host.setStatus(tr(
                                        "toolchain.installs.copied",
                                        install.home().toString()));
                            }
                            default -> {
                                if (Confirm.ask(host.window(), removeQuestion(install))) {
                                    withJkit(jkit -> runJkit(
                                            jkit,
                                            tr("toolchain.remove.running", install.label()),
                                            cancel -> JkitCli.remove(jkit, install),
                                            tr("toolchain.remove.done", install.label())));
                                }
                            }
                        }
                    });
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Opens jkit's site, where its documentation and downloads are. */
    void openWebsite() {
        host.openExternalUrl(JKIT_SITE);
    }

    static final String JKIT_SITE = "https://jkit-project.dev/";

    /** Opens jkit's settings ({@code jkit config}) in a dialog: default vendors and switching on {@code cd}. */
    void openSettings() {
        withJkit(jkit -> {
            JkitCli.Outcome config = JkitCli.config(jkit);
            String version = JkitCli.version(jkit);
            Platform.runLater(() -> {
                if (!config.ok()) {
                    host.setError(tr("toolchain.jkit.failed", config.error()));
                    return;
                }
                JkitSettingsDialog.show(
                        host.window(),
                        jkit,
                        version,
                        JkitCli.parseConfig(config.doc()),
                        changes -> work.execute(() -> applyConfig(jkit, changes)));
            });
        });
    }

    /** Writes each changed jkit setting (key → value, "" to go back to jkit's default), off the FX thread. */
    private void applyConfig(String jkit, java.util.Map<String, String> changes) {
        for (java.util.Map.Entry<String, String> e : changes.entrySet()) {
            String value = e.getValue().isEmpty() ? null : e.getValue();
            if (!runJkit(
                    jkit,
                    tr("toolchain.config.running"),
                    cancel -> JkitCli.configSet(jkit, e.getKey(), value),
                    tr(
                            "toolchain.config.done",
                            e.getKey(),
                            value == null ? tr("toolchain.vendor.jkitDefault") : value))) {
                return;
            }
        }
    }

    /** A read-only popup of {@code text} (a status, a doctor's checks) that can be selected and copied. */
    private void report(String title, String header, String text) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.INFORMATION));
        alert.initOwner(host.window());
        alert.setTitle(title);
        alert.setHeaderText(header);
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(Math.min(20, Math.max(6, (int) text.lines().count() + 1)));
        area.setPrefColumnCount(70);
        alert.getDialogPane().setContent(area);
        alert.setResizable(true);
        Dialogs.show(alert);
    }

    private void pickJkitInstall(String title, Consumer<ToolInstall> onChoose) {
        work.execute(() -> {
            Path home = JkitInstalls.home();
            List<ToolInstall> installs = new ArrayList<>();
            for (String tool : ProjectToolchain.TOOLS) {
                installs.addAll(JkitInstalls.installs(home, tool));
            }
            Platform.runLater(() -> {
                if (installs.isEmpty()) {
                    host.setStatus(tr("toolchain.jkit.nothingInstalled"));
                    return;
                }
                QuickOpen<ToolInstall> picker = new QuickOpen<>(
                        title,
                        tr("toolchain.pick.prompt"),
                        () -> installs,
                        ToolInstall::label,
                        i -> i.home().toString(),
                        i -> {
                            if (i != null) {
                                onChoose.accept(i);
                            }
                        });
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            });
        });
    }

    /** Runs {@code then} off the FX thread with jkit's path, or says how to get jkit when there is none. */
    private void withJkit(Consumer<String> then) {
        JkitCli.useProgram(host.settings().getJkitPath()); // as Settings say now (a palette change, another window)
        work.execute(() -> {
            String jkit = JkitCli.locate();
            if (jkit == null) {
                Platform.runLater(() -> host.setError(tr("toolchain.jkit.missing")));
                return;
            }
            then.accept(jkit);
        });
    }

    /** Installs each of {@code installs} in turn (off the FX thread), then re-reads the project's toolchain. */
    private void runInstalls(String jkit, List<List<String>> installs) {
        for (List<String> args : installs) {
            String what = String.join(" ", args.subList(1, args.size()));
            boolean ok = runJkit(
                    jkit,
                    tr("toolchain.install.running", what),
                    cancel -> JkitCli.install(jkit, args, liveStatus(), cancel),
                    tr("toolchain.install.done", what));
            if (!ok) {
                return;
            }
        }
    }

    /**
     * Runs one jkit command off the FX thread under a background task (Stop cancels it), reports its outcome and
     * re-reads the project's toolchain. True when it succeeded.
     */
    private boolean runJkit(
            String jkit,
            String running,
            java.util.function.Function<ProcessRunner.Cancellation, JkitCli.Outcome> command,
            String done) {
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        java.util.concurrent.CompletableFuture<AutoCloseable> task = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            host.setStatus(running);
            task.complete(host.startBackgroundTask(running));
        });
        JkitCli.Outcome outcome = command.apply(cancel);
        Platform.runLater(() -> {
            task.thenAccept(ToolchainCommands::close);
            if (outcome.ok()) {
                host.setStatus(done);
            } else if (!ProcessRunner.CANCELLED.equals(outcome.error())) {
                host.setError(tr("toolchain.jkit.failed", outcome.error()));
            }
            reload.run();
        });
        return outcome.ok();
    }

    /** jkit's progress lines ("downloading … from …") into the status line. */
    private ProcessRunner.LiveOutput liveStatus() {
        return (text, transientLine) -> {
            String line = text.strip();
            if (!line.isEmpty() && !line.startsWith("{") && !line.startsWith("}") && !line.startsWith("\"")) {
                Platform.runLater(() -> host.setStatus("jkit: " + line));
            }
        };
    }

    private static void close(@Nullable AutoCloseable task) {
        if (task == null) {
            return;
        }
        try {
            task.close();
        } catch (Exception ignored) {
            // the indicator only
        }
    }

    /** {@code JDK}, {@code Maven} or {@code Gradle}. */
    static String toolName(String tool) {
        return ToolInstall.toolName(tool);
    }
}
