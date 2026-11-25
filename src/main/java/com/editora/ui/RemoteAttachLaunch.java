package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.editora.dap.DebugLanguage;
import com.editora.dap.DebugLanguages;
import com.editora.dap.RemoteNativeAttach;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * Debug: Attach to Remote Program… — a native program (C, C++, Rust) run under a debug server on another machine, in
 * a container or on a board ({@code gdbserver host:2345 program}, {@code lldb-server gdbserver …}), debugged with a
 * copy of it here for its symbols ({@link RemoteNativeAttach}): on GDB when C/C++ is set to GDB in Settings →
 * Debugging, else on CodeLLDB (Rust always). It asks for the server's address, then for the program, then for the
 * folder it was built in over there when that is not where its sources are here (the debugger maps one to the other),
 * all remembered for the next time in this window. Part of {@link DebugCoordinator}.
 */
final class RemoteAttachLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    /** The address and the program asked for last (this window). */
    private String lastAddress = "localhost:" + RemoteNativeAttach.DEFAULT_PORT;

    private String lastProgram = "";

    private String lastRemoteRoot = "";

    RemoteAttachLaunch(DebugCoordinator d) {
        this.d = d;
    }

    void attach() {
        if (!d.debugSupportEnabled()) {
            d.host.setStatus(d.unavailableMessage("cpp", null));
            return;
        }
        d.host.promptText(tr("debug.remote.title"), tr("debug.remote.addressPrompt"), lastAddress, input -> {
            if (input == null) {
                return; // cancelled
            }
            String address = RemoteNativeAttach.address(input);
            if (address == null) {
                d.host.setStatus(tr("status.debug.badAddress", input.strip()));
                return;
            }
            lastAddress = address;
            d.host.promptText(tr("debug.remote.title"), tr("debug.remote.programPrompt"), defaultProgram(), typed -> {
                if (typed != null && !typed.isBlank()) {
                    program(address, typed.strip());
                }
            });
        });
    }

    /** The program to suggest: the one asked for last, else the active C, C++ or Rust file's name without its suffix. */
    private String defaultProgram() {
        if (!lastProgram.isBlank()) {
            return lastProgram;
        }
        EditorBuffer b = d.host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || file.getFileName() == null) {
            return "";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return file.resolveSibling(dot > 0 ? name.substring(0, dot) : name).toString();
    }

    /** Looks at {@code typed} (relative to the project) off the FX thread, then attaches with it. */
    private void program(String address, String typed) {
        Path project = d.ops.projectRoot();
        Path given = Path.of(typed);
        Path program = given.isAbsolute() || project == null ? given : project.resolve(given);
        d.planOffFx("debug-remote-program", () -> Files.isRegularFile(program), a -> {
            if (!Boolean.TRUE.equals(a.value())) {
                d.host.setStatus(tr("status.debug.remoteNoProgram", typed));
                return;
            }
            lastProgram = program.toString();
            Path local = sourcesHere(program);
            d.host.promptText(
                    tr("debug.remote.title"),
                    tr("debug.remote.folderPrompt", local.toString()),
                    lastRemoteRoot,
                    typed2 -> {
                        if (typed2 == null) {
                            return; // cancelled
                        }
                        lastRemoteRoot = typed2.strip();
                        start(address, program, RemoteNativeAttach.folderMap(local, lastRemoteRoot));
                    });
        });
    }

    /** Where the program's sources are here: the project, else the program's folder. */
    private Path sourcesHere(Path program) {
        Path project = d.ops.projectRoot();
        if (project != null) {
            return project;
        }
        Path parent = program.toAbsolutePath().getParent();
        return parent != null ? parent : program.toAbsolutePath();
    }

    /**
     * Attaches to the server at {@code address} with {@code program}'s symbols, on the debugger C/C++ is set to;
     * {@code sourceMap} maps the folder it was built in over there to the one here.
     */
    private void start(String address, Path program, Map<String, String> sourceMap) {
        EditorBuffer b = d.host.activeBuffer();
        boolean rust = b != null && "rust".equals(b.getLanguage());
        boolean gdb = !rust && "gdb".equals(d.host.settings().getCppDebugger().toLowerCase(Locale.ROOT));
        DebugLanguage profile = gdb ? DebugLanguages.GDB : rust ? DebugLanguages.RUST : DebugLanguages.CPP;
        boolean ready =
                gdb ? d.dap().isExternalLocated(DebugLanguages.GDB) : d.debugEffectiveFor(rust ? "rust" : "cpp");
        if (!ready) {
            d.host.setStatus(gdb ? tr("status.debug.gdbMissing") : d.unavailableMessage(rust ? "rust" : "cpp", null));
            return;
        }
        Map<String, Object> request = gdb
                ? RemoteNativeAttach.gdb(program.toString(), address, sourceMap)
                : RemoteNativeAttach.codelldb(program.toString(), address, rust, sourceMap);
        String label = tr("debug.remote.session", String.valueOf(program.getFileName()), address);
        d.inSession(d.sessionForLaunch(), () -> d.startExternalSession(program, profile, request, label));
    }
}
