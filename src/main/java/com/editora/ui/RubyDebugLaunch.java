package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.dap.DebugLanguages;
import com.editora.dap.ScriptLaunch;
import com.editora.editor.EditorBuffer;
import com.editora.run.RubyApps;
import com.editora.run.RubyTests;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Ruby launches of {@link DebugCoordinator}, under rdbg: Debug File on a Ruby file as {@link RubyApps} plans it — a
 * script, an RSpec or Minitest file's tests, a Rails app's server — and Debug Test at Caret in a spec or test file (the
 * example or group at the caret's line for RSpec and Rails, the named test for Minitest). rdbg runs the planned command
 * after its {@code --}. What the plan reads from the disk is read off the FX thread (PERF-01, Pauta 6.2.0). Part of
 * {@link DebugCoordinator}.
 */
final class RubyDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    RubyDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** What {@link RubyApps#plan} reads, from this machine's disk and ports. */
    static RubyApps.Lookups lookups() {
        return new RubyApps.Lookups(Files::isRegularFile, PhpDebugLaunch::portFree);
    }

    /** Debug File on Ruby file {@code file} (text {@code text}) with program arguments {@code args}. */
    void debug(Path file, String text, List<String> args) {
        plan(file, text, args, -1, null, file.getFileName().toString());
    }

    /**
     * Debug Test at Caret on an RSpec or Minitest file: the test at the caret, else the file's tests. False, nothing
     * done, for any other buffer.
     */
    boolean debugTestAtCaret(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || !"ruby".equals(b.getLanguage())) {
            return false;
        }
        String name = String.valueOf(file.getFileName());
        String text = b.getContent();
        if (!RubyApps.isSpec(name) && !RubyApps.isMinitest(name, text)) {
            return false;
        }
        if (!d.debugEffectiveFor("ruby", file)) {
            d.host.setStatus(d.unavailableMessage("ruby", file));
            return true;
        }
        int caret = DebugCoordinator.caretLine(b);
        String test = RubyApps.isSpec(name) ? null : RubyTests.at(text, caret);
        d.ops.openToolWindow();
        plan(file, text, List.of(), caret, test, test != null ? test : name + ":" + (caret + 1));
        return true;
    }

    /** Plans {@code file}'s launch off the FX thread, then starts it as session {@code label}. */
    private void plan(Path file, String text, List<String> args, int line, @Nullable String test, String label) {
        Path top = d.ops.projectRoot();
        d.planOffFx(
                "debug-ruby-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("RubyApps.plan");
                    return RubyApps.plan(file, text, args, line, test, top, lookups());
                },
                a -> {
                    RubyApps.Plan plan = a.value();
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

    private void launch(Path file, RubyApps.Plan plan, String label) {
        Map<String, Object> launch = ScriptLaunch.rubyLaunch(file, List.of(), plan.cwd());
        List<String> tail = new ArrayList<>(List.of("--"));
        tail.addAll(plan.command());
        Runnable start =
                () -> d.withClosedBreakpoints(() -> d.dap().startExternal(file, DebugLanguages.RUBY, launch, tail));
        Runnable go = () -> {
            d.s().relaunch = start;
            start.run();
            if (plan.url() != null) {
                d.host.setStatus(tr("status.debug.rubyServing", plan.url()));
            }
        };
        // The project's other edited Ruby files are saved first (off the FX thread): what runs reads them from disk.
        Path project = d.ops.projectRoot();
        Path under = project != null && file.toAbsolutePath().startsWith(project) ? project : plan.cwd();
        d.nameSession(label);
        if (d.savedOrThen(d.buffersUnder(under, "ruby"), go)) {
            go.run();
        }
    }
}
