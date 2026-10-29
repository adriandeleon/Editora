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
        String settingsKey,
        String enableKey,
        String helpUrl,
        boolean update) {

    /** Which in-app installer (if any) can fix a missing tool; {@code installArg} carries its argument. */
    public enum Install {
        NONE,
        /** {@code InstallCoordinator.installServer(installArg)} — an LSP-only server id. */
        SERVER,
        /** {@code InstallCoordinator.installSupport(Lang.valueOf(installArg))} — a language bundle. */
        LANG,
        /** {@code InstallCoordinator.installTypstCli()} — the typst render CLI. */
        TYPST_CLI,
        /** {@code InstallCoordinator.installTool(installArg)} — a pinned command-line tool (shellcheck, shfmt). */
        TOOL
    }

    /**
     * The row's main fix, in the order the pane offers it: an in-app {@link Install}, turning a switched-off
     * feature on ({@code enableKey}, e.g. {@code lsp}), or a page saying how to install a tool Editora cannot
     * fetch ({@code helpUrl}). {@code settingsKey} is always offered beside it. {@code update} marks a row whose
     * install is older than the version Editora pins: its install action reads Update ({@link #outdated}).
     */
    public DoctorCheck {
        command = command == null ? "" : command;
        detail = detail == null ? "" : detail;
        tipKey = tipKey == null ? "" : tipKey;
        tipArgs = tipArgs == null ? List.of() : List.copyOf(tipArgs);
        install = install == null ? Install.NONE : install;
        installArg = installArg == null ? "" : installArg;
        settingsKey = settingsKey == null ? "" : settingsKey;
        enableKey = enableKey == null ? "" : enableKey;
        helpUrl = helpUrl == null ? "" : helpUrl;
    }

    /** The id-to-label core every row starts from; outcome, actions and keys are added by the copies below. */
    public DoctorCheck(String id, String sectionKey, String label, String command, DoctorStatus status) {
        this(id, sectionKey, label, command, status, "", "", List.of(), Install.NONE, "", "", "", "", false);
    }

    /** The id prefix of a row that reports a cache Editora can empty (the pane offers Clear on it). */
    public static final String CACHE_PREFIX = "cache.";

    /** Whether this row reports a cache the pane offers to clear ({@link #CACHE_PREFIX}). */
    public boolean clearable() {
        return id.startsWith(CACHE_PREFIX);
    }

    /** The tips of a server that is installed but crashes or fails, which tell the user to click Install…. */
    private static final java.util.Set<String> REINSTALL_TIPS = java.util.Set.of(
            "doctor.tip.serverCrashing",
            "doctor.tip.serverCrashingXml",
            "doctor.tip.serverFailing",
            "doctor.tip.serverCrashLoop");

    /** The tips of a server this window has given up on, which a "Restart Language Servers" may bring back. */
    private static final java.util.Set<String> RESTART_TIPS = java.util.Set.of(
            "doctor.tip.serverCrashing",
            "doctor.tip.serverCrashingXml",
            "doctor.tip.serverFailing",
            "doctor.tip.serverTroubleOwn",
            "doctor.tip.serverCrashLoop",
            "doctor.tip.serverNeedsNode");

    /**
     * Whether the pane offers Restart Language Servers on this warning: a language server that crashed, failed its
     * requests or could not start, which this window stopped retrying (Bash review A7). The tip names the command;
     * the row now runs it.
     */
    public boolean restartable() {
        return status == DoctorStatus.WARN && id.startsWith("lsp.") && RESTART_TIPS.contains(tipKey);
    }

    /**
     * Whether the pane offers Install on this warning: an installable server whose tip says to reinstall it because it
     * crashes or fails its requests (XML/pom review 2, A2) — the row used to say "Click Install…" with no such button.
     */
    public boolean reinstallable() {
        return status == DoctorStatus.WARN && install != Install.NONE && REINSTALL_TIPS.contains(tipKey);
    }

    /** A fresh row in the transient {@link DoctorStatus#CHECKING} state (shown while its probe runs). */
    public static DoctorCheck checking(String id, String sectionKey, String label, String command) {
        return new DoctorCheck(id, sectionKey, label, command, DoctorStatus.CHECKING);
    }

    /** Resolved healthy, with an optional version/path {@code detail}. */
    public DoctorCheck ok(String detail) {
        return withOutcome(DoctorStatus.OK, detail, "", List.of());
    }

    /** Resolved degraded-but-working (optional tool absent, unauthenticated, old version). */
    public DoctorCheck warn(String detail, String tipKey, String... tipArgs) {
        return withOutcome(DoctorStatus.WARN, detail, tipKey, List.of(tipArgs));
    }

    /**
     * Resolved but needing its install again — older than the version Editora installs and is tested with
     * ({@code PinnedVersions}), or installed without what it needs (a TypeScript server with no tsserver): a warning
     * whose {@link Install} action the pane offers as Update.
     */
    public DoctorCheck outdated(String detail, String tipKey, String... tipArgs) {
        return withUpdate(warn(detail, tipKey, tipArgs));
    }

    /**
     * Resolved healthy, with a newer release of it published than the {@code installed} one ({@code latest}, as
     * Check for Newer Versions found): OK, but its {@link Install} action is offered as Update, which installs the
     * latest release.
     */
    public DoctorCheck newer(String detail, String installed, String latest) {
        return withUpdate(
                withOutcome(DoctorStatus.OK, detail, "doctor.tip.newerAvailable", List.of(installed, latest)));
    }

    private DoctorCheck withUpdate(DoctorCheck resolved) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                resolved.status(),
                resolved.detail(),
                resolved.tipKey(),
                resolved.tipArgs(),
                install,
                installArg,
                settingsKey,
                enableKey,
                helpUrl,
                true);
    }

    /**
     * Resolved absent, but nothing in this window needs it (a server for a language not open, an optional
     * accelerator) — the neutral state; any Install action is kept.
     */
    public DoctorCheck optional(String tipKey, String... tipArgs) {
        return withOutcome(DoctorStatus.OPTIONAL, "", tipKey, List.of(tipArgs));
    }

    /** Resolved absent while its feature is enabled and needed here — the red, fix-me state. */
    public DoctorCheck missing(String tipKey, String... tipArgs) {
        return withOutcome(DoctorStatus.MISSING, "", tipKey, List.of(tipArgs));
    }

    /** The gray informational state for a feature that is switched off (never probed). */
    public DoctorCheck disabled() {
        return disabled("doctor.tip.disabled");
    }

    /** As {@link #disabled()} with another tip ({@code ""}: none — a "Turned off" row that says it all). */
    public DoctorCheck disabled(String tipKey) {
        return withOutcome(DoctorStatus.DISABLED, "", tipKey, List.of());
    }

    /** Copy carrying an in-app install action the pane renders as an Install… button. */
    public DoctorCheck withInstall(Install kind, String arg) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                kind,
                arg,
                settingsKey,
                enableKey,
                helpUrl,
                update);
    }

    /** This row with {@code detail} in place of its own, its outcome unchanged. */
    public DoctorCheck withDetail(String detail) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                settingsKey,
                enableKey,
                helpUrl,
                update);
    }

    /** Copy without an install action (its fix is something else, such as turning on another feature). */
    public DoctorCheck withoutInstall() {
        return withInstall(Install.NONE, "");
    }

    /** Copy carrying the Settings page this row's "Settings…" action opens. */
    public DoctorCheck withSettings(String key) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                key,
                enableKey,
                helpUrl,
                update);
    }

    /** Copy carrying the feature a "Turn on" action switches on ({@code lsp}, {@code debug}, …). */
    public DoctorCheck withEnable(String key) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                settingsKey,
                key,
                helpUrl,
                update);
    }

    /** Copy carrying a "How to install" page, for a tool Editora has no installer for. */
    public DoctorCheck withHelp(String url) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                settingsKey,
                enableKey,
                url,
                update);
    }

    /** Copy with a different command column (e.g. none, for a server with no command configured). */
    public DoctorCheck withCommand(String newCommand) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                newCommand,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                settingsKey,
                enableKey,
                helpUrl,
                update);
    }

    private DoctorCheck withOutcome(DoctorStatus status, String detail, String tipKey, List<String> tipArgs) {
        return new DoctorCheck(
                id,
                sectionKey,
                label,
                command,
                status,
                detail,
                tipKey,
                tipArgs,
                install,
                installArg,
                settingsKey,
                enableKey,
                helpUrl,
                false);
    }
}
