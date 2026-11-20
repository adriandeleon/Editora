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
    }

    // --- the project's own tools ----------------------------------------------------------------------------

    /** Installs, with jkit, every tool the project names ({@code .sdkmanrc}, project settings) that is missing. */
    void installProjectTools() {
        List<ProjectToolchain.Choice> missing = scope.get().toolchain().missing();
        if (scope.get().root() == null) {
            host.setStatus(tr("toolchain.noProject"));
            return;
        }
        if (missing.isEmpty()) {
            host.setStatus(tr("toolchain.nothingMissing"));
            return;
        }
        withJkit(jkit -> {
            List<List<String>> installs = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (ProjectToolchain.Choice c : missing) {
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
            runInstalls(jkit, installs);
        });
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
                    ToolInstall.JDK.equals(tool)
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
            // Clear it from both places Editora reads it from.
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
                Platform.runLater(() -> {
                    reload.run();
                    host.setStatus(tr("toolchain.setProject.cleared", toolName(tool)));
                });
            });
            return;
        }
        String sdkmanId = sdkmanValue(install);
        Where where = sdkmanId == null ? Where.PROJECT_SETTINGS : askWhere(install, sdkmanId);
        if (where == null) {
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

    /** Where a project's choice is kept. */
    enum Where {
        SDKMANRC,
        PROJECT_SETTINGS
    }

    /** The words of "where should this choice go?". Answers: .sdkmanrc (0), Editora's project settings (1). */
    static Confirm.Choice whereQuestion(ToolInstall install, String sdkmanId) {
        return Confirm.choice(
                tr("toolchain.where.title"),
                tr("toolchain.where.header", install.label()),
                tr("toolchain.where.message", SdkmanInstalls.candidate(install.tool()) + "=" + sdkmanId),
                tr("toolchain.where.sdkmanrc"),
                tr("toolchain.where.settings"));
    }

    private @Nullable Where askWhere(ToolInstall install, String sdkmanId) {
        Confirm.Choice words = whereQuestion(install, sdkmanId);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.window());
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType sdkmanrc = new ButtonType(words.answer(0));
        ButtonType settings = new ButtonType(words.answer(1));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(sdkmanrc, settings, cancel);
        Dialogs.defaultTo(alert, sdkmanrc);
        ButtonType answer = Dialogs.showAndWait(alert).orElse(cancel);
        return answer == sdkmanrc ? Where.SDKMANRC : answer == settings ? Where.PROJECT_SETTINGS : null;
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
        return switch (tool) {
            case ToolInstall.JDK -> "JDK";
            case ToolInstall.MAVEN -> "Maven";
            case ToolInstall.GRADLE -> "Gradle";
            default -> tool;
        };
    }
}
