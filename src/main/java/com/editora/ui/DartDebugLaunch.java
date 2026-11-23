package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import com.editora.dap.DartLaunch;
import com.editora.dap.DartTests;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.run.DartRun;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Dart launches of {@link DebugCoordinator}: a Dart file (Debug File, or a Dart run configuration) on {@code dart
 * debug_adapter}, or — its package a Flutter app — on {@code flutter debug_adapter}, on the device Settings names
 * ({@link DartLaunch}). The package's {@code pubspec.yaml} is read off the FX thread (PERF-01, Pauta 6.2.0), and its
 * folder is trusted first (pub fetches its packages and runs their build hooks), as Run asks it. Also where both
 * adapters are looked for. Part of {@link DebugCoordinator}.
 */
final class DartDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    DartDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** Looks for {@code dart} and {@code flutter} (as configured, else on the PATH); {@code found} hears each answer. */
    void configure(boolean on, Settings s, ProjectScope scope, Runnable found) {
        boolean dart = on && s.isDartDebugEnabled();
        d.dap()
                .configureExternal(
                        DebugLanguages.DART, dart, scope.debugTool("dart", s.getDartDebugPath()), f -> found.run());
        d.dap()
                .configureExternal(
                        DebugLanguages.FLUTTER,
                        dart,
                        scope.debugTool("flutter", s.getFlutterDebugPath()),
                        f -> found.run());
    }

    /** What the launch reads from the disk: the package's folder and its {@code pubspec.yaml}. */
    private record Read(@Nullable Path root, @Nullable String pubspec) {}

    /**
     * Debugs Dart file {@code file} with {@code args}; {@code env} goes to the program, which runs in {@code cwd} when
     * given (a configuration's folder), else in its package's. A configuration names its session and Restart itself
     * ({@code config}); a file's are set here.
     */
    void debug(Path file, List<String> args, Map<String, String> env, @Nullable Path cwd, boolean config) {
        d.planOffFx(
                "debug-dart-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("DartLaunch.plan");
                    Path root = DartRun.packageRoot(file, Files::isRegularFile);
                    String pubspec = root == null ? null : Files.readString(root.resolve(DartRun.PUBSPEC));
                    return new Read(root, pubspec);
                },
                a -> {
                    Read read = a.value();
                    if (read == null) {
                        d.host.setStatus(tr(
                                "status.debug.planFailed",
                                file.getFileName(),
                                String.valueOf(
                                        a.failure() == null ? "" : a.failure().getMessage())));
                        return;
                    }
                    planned(file, args, env, cwd, config, read);
                });
    }

    /**
     * Debug Test at Caret on a Dart test file ({@link DartTests#isTestFile}): the test or group at the caret (else the
     * whole file) on the adapter's test mode. False, nothing done, for any other buffer.
     */
    boolean debugTestAtCaret(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || !"dart".equals(b.getLanguage()) || !DartTests.isTestFile(file)) {
            return false;
        }
        int caret = DebugCoordinator.caretLine(b);
        String text = b.getContent();
        d.planOffFx(
                "debug-dart-test-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("DartLaunch.testPlan");
                    Path root = DartRun.packageRoot(file, Files::isRegularFile);
                    String pubspec = root == null ? null : Files.readString(root.resolve(DartRun.PUBSPEC));
                    return new TestRead(new Read(root, pubspec), DartTests.at(text, caret));
                },
                a -> {
                    TestRead read = a.value();
                    if (read == null) {
                        d.host.setStatus(tr(
                                "status.debug.planFailed",
                                file.getFileName(),
                                String.valueOf(
                                        a.failure() == null ? "" : a.failure().getMessage())));
                        return;
                    }
                    Read pkg = read.read();
                    DartLaunch.Plan plan = DartLaunch.testPlan(file, pkg.root(), pkg.pubspec(), read.target());
                    launch(file, Map.of(), null, false, pkg.root(), plan, DartLaunch.TEST_MODE);
                });
        return true;
    }

    /** What a test launch reads: the package, and the test at the caret (null: none, the whole file). */
    private record TestRead(Read read, DartTests.@Nullable Target target) {}

    private void planned(
            Path file, List<String> args, Map<String, String> env, @Nullable Path cwd, boolean config, Read read) {
        Path root = read.root();
        DartLaunch.Plan plan = DartLaunch.plan(
                file,
                root,
                read.pubspec(),
                args,
                DartRun.device(d.host.settings().getFlutterDevice()));
        launch(file, env, cwd, config, root, plan, List.of());
    }

    /**
     * Starts {@code plan} for {@code file} (in package {@code root}, trusted), with {@code tail} after the adapter's
     * command ({@link DartLaunch#TEST_MODE} for a test run).
     */
    private void launch(
            Path file,
            Map<String, String> env,
            @Nullable Path cwd,
            boolean config,
            @Nullable Path root,
            DartLaunch.Plan plan,
            List<String> tail) {
        if (root != null && !d.ops.trustBuild(root, List.of(tr("dialog.trust.item.pubspec", DartRun.PUBSPEC)))) {
            return; // not trusted: nothing runs
        }
        if (plan.language() == DebugLanguages.FLUTTER && !d.dap().isExternalLocated(DebugLanguages.FLUTTER)) {
            d.host.setStatus(tr("status.debug.flutterMissing"));
            return;
        }
        Map<String, Object> launch = new LinkedHashMap<>(plan.launch());
        if (cwd != null && plan.language() == DebugLanguages.DART) { // flutter runs in its app's folder
            launch.put("cwd", cwd.toString());
        }
        if (!env.isEmpty()) {
            launch.put("env", Map.copyOf(env));
        }
        Runnable start =
                () -> d.withClosedBreakpoints(() -> d.dap().startExternal(file, plan.language(), launch, tail));
        // The package's other edited Dart files are saved first (off the FX thread), as a build would read them.
        Runnable go = () -> {
            if (!config) {
                d.s().relaunch = start;
            }
            start.run();
        };
        if (root == null || d.savedOrThen(d.buffersUnder(root, "dart"), go)) {
            go.run();
        }
    }
}
