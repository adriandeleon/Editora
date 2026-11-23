package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.dap.DebugLanguages;
import com.editora.dap.ScriptLaunch;
import com.editora.editor.EditorBuffer;
import com.editora.run.PhpApps;
import com.editora.run.PhpUnitTests;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * PHP launches of {@link DebugCoordinator}, on Xdebug through vscode-php-debug: Debug File on a PHP file as
 * {@link PhpApps} plans it — a script, a web app on PHP's built-in server (a Laravel app's as {@code artisan serve}
 * serves it), a PHPUnit test file — Debug Test at Caret in a PHPUnit test, and Debug: Listen for Xdebug, VS Code's
 * configuration of that name without a launch.json. What the plan reads from the disk is read off the FX thread
 * (PERF-01, Pauta 6.2.0). Part of {@link DebugCoordinator}.
 */
final class PhpDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    PhpDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** What {@link PhpApps#plan} reads, from this machine's disk, PATH and ports. */
    static PhpApps.Lookups lookups() {
        return new PhpApps.Lookups(
                Files::isRegularFile,
                PhpDebugLaunch::firstLine,
                tool -> {
                    Path p = Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of(tool))
                            .getFirst());
                    return p.isAbsolute() && Files.isRegularFile(p) ? p : null;
                },
                PhpDebugLaunch::portFree);
    }

    /** {@code file}'s first line, or null when it cannot be read. */
    static @Nullable String firstLine(Path file) {
        try (var lines = Files.lines(file)) {
            return lines.findFirst().orElse("");
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return null;
        }
    }

    /** Whether nothing listens on {@code port} of this machine's loopback address. */
    static boolean portFree(int port) {
        try (var socket = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Debug File on PHP file {@code file} (text {@code text}) with program arguments {@code args}. */
    void debug(Path file, String text, List<String> args) {
        plan(file, text, args, null, file.getFileName().toString());
    }

    /**
     * Debug Test at Caret on a PHPUnit test file: the test at the caret ({@link PhpUnitTests}), else the file's
     * tests. False, nothing done, for any other buffer.
     */
    boolean debugTestAtCaret(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null
                || file == null
                || !"php".equals(b.getLanguage())
                || !PhpApps.isTestFile(String.valueOf(file.getFileName()), b.getContent())) {
            return false;
        }
        if (!d.debugEffectiveFor("php", file)) {
            d.host.setStatus(d.unavailableMessage("php", file));
            return true;
        }
        String method = PhpUnitTests.at(b.getContent(), DebugCoordinator.caretLine(b));
        d.ops.openToolWindow();
        plan(
                file,
                b.getContent(),
                List.of(),
                method == null ? null : PhpUnitTests.filter(method),
                method != null ? method : file.getFileName().toString());
        return true;
    }

    /**
     * Debug: Listen for Xdebug — vscode-php-debug waits on Xdebug's port ({@link ScriptLaunch#XDEBUG_PORT}) for the
     * PHP the user runs (a request to their web server, a script with {@code XDEBUG_SESSION} set), beside a session
     * already running, until it is stopped.
     */
    void listen() {
        EditorBuffer b = d.host.activeBuffer();
        Path active = b == null ? null : b.getPath();
        Path project = d.ops.projectRoot();
        Path anchor = active != null ? active : project != null ? project : Path.of(System.getProperty("user.home"));
        if (!d.debugEffectiveFor("php", active)) {
            d.host.setStatus(d.unavailableMessage("php", active));
            return;
        }
        d.inSession(d.sessionForLaunch(), () -> {
            d.startExternalSession(
                    anchor,
                    DebugLanguages.PHP,
                    ScriptLaunch.phpListen(ScriptLaunch.XDEBUG_PORT),
                    tr("debug.php.listenName"));
            d.host.setStatus(tr("status.debug.phpListening", ScriptLaunch.XDEBUG_PORT));
        });
    }

    /** Plans {@code file}'s launch off the FX thread, then starts it as session {@code label}. */
    private void plan(Path file, String text, List<String> args, @Nullable String filter, String label) {
        Path top = d.ops.projectRoot();
        d.planOffFx(
                "debug-php-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("PhpApps.plan");
                    return PhpApps.plan(file, text, args, filter, top, "php", lookups());
                },
                a -> {
                    PhpApps.Plan plan = a.value();
                    if (plan == null) {
                        d.host.setStatus(tr(
                                "status.debug.planFailed",
                                file.getFileName(),
                                String.valueOf(
                                        a.failure() == null ? "" : a.failure().getMessage())));
                        return;
                    }
                    launch(file, plan, label);
                });
    }

    private void launch(Path file, PhpApps.Plan plan, String label) {
        String missing = missingMessage(plan);
        if (missing != null) {
            d.host.setStatus(missing);
            return;
        }
        Map<String, Object> launch = ScriptLaunch.phpCommand(plan.command(), plan.php(), plan.cwd());
        Runnable start = () -> d.withClosedBreakpoints(() -> d.dap().startExternal(file, DebugLanguages.PHP, launch));
        Runnable go = () -> {
            d.s().relaunch = start;
            start.run();
            if (plan.url() != null) {
                d.host.setStatus(tr("status.debug.phpServing", plan.url()));
            }
        };
        // The project's other edited PHP files are saved first (off the FX thread): the server and PHPUnit read them
        // from the disk.
        Path project = d.ops.projectRoot();
        Path under = project != null && file.toAbsolutePath().startsWith(project) ? project : plan.cwd();
        d.nameSession(label);
        if (d.savedOrThen(d.buffersUnder(under, "php"), go)) {
            go.run();
        }
    }

    /** What {@code plan} says is missing, in words, or null. */
    static @Nullable String missingMessage(PhpApps.Plan plan) {
        String missing = plan.missing();
        if (missing == null) {
            return null;
        }
        return plan.kind() == PhpApps.Kind.TESTS
                ? tr("status.run.phpunitMissing")
                : tr("status.run.laravelVendorMissing", missing);
    }
}
