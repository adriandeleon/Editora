package com.editora.doctor;

import java.util.List;

/**
 * One row of the Doctor screen: an external tool (or feature) with its probed health, an optional tip and
 * the corrective actions the UI can offer. Pure and immutable; the {@code tipKey} is an i18n <b>key</b>
 * (resolved by the pane, like {@code Chrome.DisabledReason}) so this package stays catalog-free — tool
 * names and command strings are technical tokens and stay literal.
 *
 * <p>The builder-style {@code ok}/{@code warn}/{@code missing}/{@code disabled} copies keep the
 * coordinator's check catalog readable: a probe closes over the {@code CHECKING} placeholder and returns
 * the resolved copy.
 */
public record DoctorCheck(
        String id,
        String sectionKey,
        String label,
        String command,
        DoctorStatus status,
        String detail,
        String tipKey,
        List<String> tipArgs,
        Install install,
        String installArg,
        String settingsKey) {

    /** Which in-app installer (if any) can fix a missing tool; {@code installArg} carries its argument. */
    public enum Install {
        NONE,
        /** {@code InstallCoordinator.installServer(installArg)} — an LSP-only server id. */
        SERVER,
        /** {@code InstallCoordinator.installSupport(Lang.valueOf(installArg))} — a language bundle. */
        LANG,
        /** {@code InstallCoordinator.installTypstCli()} — the typst render CLI. */
        TYPST_CLI
    }

    public DoctorCheck {
        command = command == null ? "" : command;
        detail = detail == null ? "" : detail;
        tipKey = tipKey == null ? "" : tipKey;
        tipArgs = tipArgs == null ? List.of() : List.copyOf(tipArgs);
        install = install == null ? Install.NONE : install;
        installArg = installArg == null ? "" : installArg;
        settingsKey = settingsKey == null ? "" : settingsKey;
    }

    /** A fresh row in the transient {@link DoctorStatus#CHECKING} state (shown while its probe runs). */
    public static DoctorCheck checking(String id, String sectionKey, String label, String command) {
        return new DoctorCheck(
                id, sectionKey, label, command, DoctorStatus.CHECKING, "", "", List.of(), Install.NONE, "", "");
    }

    /** Resolved healthy, with an optional version/path {@code detail}. */
    public DoctorCheck ok(String detail) {
        return withOutcome(DoctorStatus.OK, detail, "", List.of());
    }

    /** Resolved degraded-but-working (optional tool absent, unauthenticated, old version). */
    public DoctorCheck warn(String detail, String tipKey, String... tipArgs) {
        return withOutcome(DoctorStatus.WARN, detail, tipKey, List.of(tipArgs));
    }

    /** Resolved absent while its feature is enabled — the red, fix-me state. */
    public DoctorCheck missing(String tipKey, String... tipArgs) {
        return withOutcome(DoctorStatus.MISSING, "", tipKey, List.of(tipArgs));
    }

    /** The gray informational state for a feature that is switched off (never probed). */
    public DoctorCheck disabled() {
        return withOutcome(DoctorStatus.DISABLED, "", "doctor.tip.disabled", List.of());
    }

    /** Copy carrying an in-app install action the pane renders as an Install… button. */
    public DoctorCheck withInstall(Install kind, String arg) {
        return new DoctorCheck(id, sectionKey, label, command, status, detail, tipKey, tipArgs, kind, arg, settingsKey);
    }

    /** Copy carrying the Settings page this row's "Settings…" action opens. */
    public DoctorCheck withSettings(String key) {
        return new DoctorCheck(
                id, sectionKey, label, command, status, detail, tipKey, tipArgs, install, installArg, key);
    }

    private DoctorCheck withOutcome(DoctorStatus status, String detail, String tipKey, List<String> tipArgs) {
        return new DoctorCheck(
                id, sectionKey, label, command, status, detail, tipKey, tipArgs, install, installArg, settingsKey);
    }
}
