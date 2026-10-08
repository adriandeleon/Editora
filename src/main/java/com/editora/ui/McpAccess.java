package com.editora.ui;

import java.nio.file.Path;

import com.editora.agent.AcpFsGuard;
import com.editora.io.PathContainment;

/**
 * Which files an MCP client may bring into the editor and which it may write — the MCP counterpart of
 * {@link AcpFsGuard}, which confines the ACP agent's file requests.
 *
 * <p>An MCP client works on what the user has open. {@code open_file} was the one tool that named a file by
 * path and had the editor fetch it: with nothing confining that path, a client could open
 * {@code ~/.ssh/id_rsa} and read it with {@code read_buffer}, or open {@code ~/.bashrc}, edit it and
 * {@code save_buffer} it — none of which the enable-MCP notice ("read and edit your open files") describes,
 * and all of which the agent channel already refuses. The rules:
 *
 * <ul>
 *   <li>{@code open_file} opens a file that is canonically inside the window's project folder
 *       ({@link PathContainment}: a link in the project that leads out of it is outside), or selects one that
 *       is already open in the window. A window with no project opens nothing new.
 *   <li>No write tool ({@code edit_buffer}, {@code save_buffer}) touches a buffer whose file is in the
 *       editor's configuration directory or in version-control metadata, wherever that file is and whoever
 *       opened it: settings, keymaps, plugins and Git hooks can make the editor or Git run commands.
 * </ul>
 *
 * <p>Both answers are a message the client can read, or {@code null} when the request is allowed. Touches the
 * filesystem only to canonicalize; unit-tested against a temp directory.
 */
final class McpAccess {

    private McpAccess() {}

    /** Why {@code file} may not be opened for a client of a window whose project folder is {@code root}. */
    static String openRefusal(Path root, Path file, boolean alreadyOpen) {
        if (alreadyOpen) {
            return null;
        }
        if (root == null) {
            return "Refused: this window has no project open, so open_file can only select a file that is"
                    + " already open in it. Ask the user to open " + file + ".";
        }
        if (!PathContainment.isWithin(root, file)) {
            return "Refused: " + file + " is outside the project folder " + root + ". open_file opens the"
                    + " project's files; a file elsewhere has to be opened by the user.";
        }
        return null;
    }

    /** Why a client may not change or save the buffer of {@code file} ({@code null} for an untitled one). */
    static String writeRefusal(Path configDir, Path file) {
        if (file == null) {
            return null;
        }
        if (configDir != null && com.editora.vfs.Vfs.isLocal(file) && PathContainment.isWithin(configDir, file)) {
            return "Refused: " + file + " is inside the editor's configuration directory, which is not written"
                    + " on an MCP client's behalf.";
        }
        // A remote file is judged by its name alone: canonicalizing it is a network round trip, and this is
        // asked on the FX thread.
        boolean metadata = com.editora.vfs.Vfs.isLocal(file)
                ? AcpFsGuard.isVcsMetadata(file)
                : AcpFsGuard.hasVcsMetadataName(file.normalize());
        if (metadata) {
            return "Refused: " + file + " is version-control metadata, which is not written on an MCP client's"
                    + " behalf.";
        }
        return null;
    }
}
