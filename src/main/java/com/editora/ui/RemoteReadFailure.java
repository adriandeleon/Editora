package com.editora.ui;

import java.nio.file.Path;

import com.editora.vfs.RemoteFileSystems;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Why a file or folder could not be read, in words the user can act on. A request on a closed SFTP
 * connection fails with whatever the first call happens to raise ({@code SftpFileSystem is closed
 * SftpFileSystem[ClientSessionImpl[…]]}) — or, for a directory listing, with nothing at all: an empty folder.
 * Every read path (open, reload, the Project tree and map, search) asks here, as a save already does.
 */
final class RemoteReadFailure {

    private RemoteReadFailure() {}

    /** True when {@code path} is on an SFTP connection that has been closed or has dropped. */
    static boolean connectionClosed(Path path) {
        return RemoteFileSystems.isDisconnected(path);
    }

    /** The reason to put behind "Failed to open: " and its like: the closed connection, else the error's own text. */
    static String reason(Path path, Throwable error) {
        if (connectionClosed(path)) {
            return tr("status.remote.connectionClosed", Vfs.authorityOf(path), tr("command.remote.connect"));
        }
        String message = error == null ? null : error.getMessage();
        return message != null ? message : String.valueOf(error);
    }

    /** A whole status line for a folder or search root that could not be read because its connection is closed. */
    static String unreadable(Path path) {
        return tr("status.remote.unreadable", Vfs.displayLabel(path), reason(path, null));
    }
}
