package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import com.editora.dap.DebugLanguages;
import com.editora.dap.PowerShellLaunch;

import static com.editora.i18n.Messages.tr;

/**
 * PowerShell's debugging, for {@link DebugCoordinator}: PowerShell Editor Services located (Settings → Debugging), and
 * F5 on a {@code .ps1}, which runs the script on it in the script's folder ({@link PowerShellLaunch}). FX thread.
 */
final class PowerShellDebugLaunch {

    private final DebugCoordinator d;

    PowerShellDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /** Looks for PSES when PowerShell debugging is on ({@code on}: debugging at all), then runs {@code found}. */
    void configure(boolean on, Settings s, ProjectScope scope, Runnable found) {
        d.dap()
                .configureExternal(
                        DebugLanguages.POWERSHELL,
                        on && s.isPowershellDebugEnabled(),
                        scope.debugTool("powershell", s.getPowershellDebugPath()),
                        f -> found.run());
    }

    /** F5 on {@code path} with {@code args}: a script, in its folder; a module or data file says it is not one. */
    void debug(Path path, List<String> args) {
        if (!PowerShellLaunch.isScript(path)) {
            d.host.setStatus(tr("status.debug.powershellNotScript", path.getFileName()));
            return;
        }
        Path dir = java.util.Objects.requireNonNullElse(path.toAbsolutePath().getParent(), path);
        d.startExternalSession(
                path,
                DebugLanguages.POWERSHELL,
                PowerShellLaunch.launch(path, args, dir),
                String.valueOf(path.getFileName()));
    }
}
