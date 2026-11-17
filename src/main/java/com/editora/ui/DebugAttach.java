package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.editora.config.PathKeys;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** Debug's attach helpers, pure: which open file routes an attach, the remembered addresses, a process row's text. */
final class DebugAttach {

    private DebugAttach() {}

    /**
     * Pure: the file whose jdtls session starts the debug adapter for an attach. The anchor itself when it is
     * open in a tab; otherwise an open Java file of the anchor's project (one already on the server first),
     * else any open Java file already on the server, else the anchor — which then fails with the precise
     * "no language server for file" rather than attaching through an unrelated project.
     */
    static @Nullable Path attachRouting(
            Path anchor, List<Path> openJavaFiles, @Nullable Path projectRoot, Predicate<Path> managed) {
        if (anchor == null) {
            return openJavaFiles.stream().filter(managed).findFirst().orElse(null);
        }
        Path sameProject = null;
        Path anyManaged = null;
        for (Path open : openJavaFiles) {
            if (PathKeys.sameNormalized(open, anchor)) {
                return anchor;
            }
            boolean onServer = managed.test(open);
            if (projectRoot != null && open.toAbsolutePath().normalize().startsWith(projectRoot)) {
                if (sameProject == null || (onServer && !managed.test(sameProject))) {
                    sameProject = open;
                }
            } else if (onServer && anyManaged == null) {
                anyManaged = open;
            }
        }
        return sameProject != null ? sameProject : anyManaged != null ? anyManaged : anchor;
    }

    /** The most addresses Attach remembers per project. */
    static final int MAX_ATTACH_TARGETS = 8;

    /** Pure: {@code address} first, then the earlier ones without it, at most {@link #MAX_ATTACH_TARGETS}. */
    static List<String> rememberAttachTarget(List<String> earlier, String address) {
        List<String> out = new ArrayList<>();
        out.add(address);
        for (String a : earlier) {
            if (out.size() >= MAX_ATTACH_TARGETS) {
                break;
            }
            if (a != null && !a.isBlank() && !a.equalsIgnoreCase(address)) {
                out.add(a);
            }
        }
        return out;
    }

    /** A row's second line: which debugger, and where it listens — or what to start the program with. Pure. */
    static String processDetail(com.editora.dap.LocalProcesses.Target t) {
        String kind = tr("debug.attachProcess.kind." + t.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (t.kind().byPid()) {
            return tr(
                    t.kind() == com.editora.dap.LocalProcesses.Kind.GO
                            ? "debug.attachProcess.byPid"
                            : "debug.attachProcess.byPidLldb",
                    kind);
        }
        return t.attachable()
                ? tr("debug.attachProcess.listening", kind, t.address())
                : tr("debug.attachProcess.notListening", kind, startFlag(t.kind()));
    }

    /** What a program of {@code kind} is started with so a debugger can attach. Pure. */
    static String startFlag(com.editora.dap.LocalProcesses.Kind kind) {
        return switch (kind) {
            case JAVA -> "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005";
            case NODE -> "--inspect";
            case PYTHON -> "-m debugpy --listen 5678";
            case GO, RUST, NATIVE -> "";
        };
    }
}
