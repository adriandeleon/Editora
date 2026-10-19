package com.editora.doctor;

import com.editora.run.NodeRuntimes;

import static com.editora.i18n.Messages.tr;

/**
 * Doctor's one "Run → Node.js" row (review E6): which Node.js Run, Debug, npm scripts, the TypeScript server and
 * the HTTP Client use — the one {@link NodeRuntimes} picks — its version and why it was picked. Before, Node.js
 * showed only under HTTP Client and the installer prerequisites, and a Node project's Run section had no Node row.
 * Pure: the caller resolves and runs {@code node --version}.
 */
public final class NodeRuntimeRow {

    /** What {@code node --version} prints: {@code v24.18.1}. */
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("v\\d{1,4}(\\.\\d{1,6}){1,2}");

    private NodeRuntimeRow() {}

    /** The row's verdict for a {@code node --version} answer ({@code ""} when it did not run). */
    public static DoctorStatus status(String version, boolean needed) {
        // Shape first: an error text ("…/junit8552894125453687280/node: not found") is no version to parse.
        if (version != null && VERSION.matcher(version.strip()).matches()) {
            return DoctorStatus.OK;
        }
        return needed ? DoctorStatus.MISSING : DoctorStatus.OPTIONAL;
    }

    /** Why this Node.js was chosen, in the user's words. */
    public static String reason(NodeRuntimes.NodeRuntime runtime) {
        return switch (runtime.source()) {
            case CONFIGURATION -> tr("doctor.node.source.configuration");
            case SETTING -> tr("doctor.node.source.setting");
            case VERSION_FILE -> tr("doctor.node.source.versionFile", runtime.versionFile());
            case PATH -> tr("doctor.node.source.path");
        };
    }

    /** {@code v24.18.1 — /usr/bin/node (node on PATH)}. */
    public static String detail(String version, NodeRuntimes.NodeRuntime runtime) {
        return tr("doctor.detail.node", version.strip(), String.join(" ", runtime.command()), reason(runtime));
    }
}
