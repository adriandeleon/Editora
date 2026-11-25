package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;

import com.editora.dap.GoLaunch;
import com.editora.dap.RemoteNativeAttach;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug: Attach to Remote Delve… — a Go program under Delve's headless server on another machine, in a container, or
 * here ({@code dlv debug --headless --listen=:2345 --accept-multiclient}), and launch.json's {@code go} configuration
 * with {@code mode: remote}: the session talks DAP to that server ({@link GoLaunch#remoteAttach}), so no Delve need be
 * installed here. It asks for the server's address, then for the folder the program was built from over there when
 * that is not this project's (Delve maps the paths both ways), both remembered for the next time in this window. Part
 * of {@link DebugCoordinator}.
 */
final class RemoteDelveLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    /** The address and the remote folder asked for last (this window). */
    private String lastAddress = "localhost:" + GoLaunch.REMOTE_PORT;

    private String lastRemoteRoot = "";

    RemoteDelveLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** Whether a remote Delve can be attached to: debugging on, and Go's switch (no Delve need be found here). */
    private boolean ready() {
        DebugAdapterSettings.Binding go = DebugAdapterSettings.of("go");
        return d.debugSupportEnabled() && (go == null || go.isEnabled(d.host.settings()));
    }

    void attach() {
        if (!ready()) {
            d.host.setStatus(d.unavailableMessage("go", null));
            return;
        }
        d.host.promptText(tr("debug.remoteGo.title"), tr("debug.remoteGo.addressPrompt"), lastAddress, input -> {
            if (input == null) {
                return; // cancelled
            }
            String address = RemoteNativeAttach.address(input);
            if (address == null) {
                d.host.setStatus(tr("status.debug.badAddress", input.strip()));
                return;
            }
            lastAddress = address;
            Path project = d.ops.projectRoot();
            if (project == null) {
                start(address, null, null, tr("debug.remoteGo.session", address));
                return;
            }
            d.host.promptText(
                    tr("debug.remoteGo.title"),
                    tr("debug.remoteGo.folderPrompt", project.toString()),
                    lastRemoteRoot,
                    typed -> {
                        if (typed == null) {
                            return; // cancelled
                        }
                        lastRemoteRoot = typed.strip();
                        start(address, project, lastRemoteRoot, tr("debug.remoteGo.session", address));
                    });
        });
    }

    /** launch.json's {@code go} configuration with {@code mode: remote}, named {@code name}. */
    void startConfigured(String name, Map<String, Object> request) {
        if (!ready()) {
            d.host.setStatus(d.unavailableMessage("go", null));
            return;
        }
        String host = GoLaunch.remoteHost(request);
        int port = GoLaunch.remotePort(request);
        Path anchor = anchor();
        d.ops.openToolWindow();
        d.nameSession(name);
        Runnable start = () -> d.withClosedBreakpoints(() -> d.dap().startRemoteDelve(anchor, host, port, request));
        d.s().relaunch = start;
        start.run();
    }

    /** Attaches to the server at {@code address}, {@code localRoot} built from {@code remoteRoot} over there. */
    private void start(String address, @Nullable Path localRoot, @Nullable String remoteRoot, String label) {
        int colon = address.lastIndexOf(':');
        String host = address.substring(0, colon);
        int port = Integer.parseInt(address.substring(colon + 1));
        Map<String, Object> request = GoLaunch.remoteAttach(host, port, localRoot, remoteRoot);
        d.inSession(d.sessionForLaunch(), () -> startConfigured(label, request));
    }

    /** What the session is anchored to: the active file, else the project, else the home folder. */
    private Path anchor() {
        EditorBuffer b = d.host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null) {
            return file;
        }
        Path project = d.ops.projectRoot();
        return project != null ? project : Path.of(System.getProperty("user.home", "."));
    }
}
