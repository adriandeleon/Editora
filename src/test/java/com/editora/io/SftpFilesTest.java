package com.editora.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpFilesTest {

    @Test
    void theLinkCountIsReadFromAnLsStyleLongName() {
        assertEquals(2, SftpFiles.linkCount("-rw-r--r--    2 adl      adl             5 Oct  5 10:00 h1.txt"));
        assertEquals(1, SftpFiles.linkCount("-rw-r--r-- 1 1000 1000 5 Oct  5 10:00 name with spaces"));
        assertEquals(13, SftpFiles.linkCount("drwxr-xr-x+  13 root root 4096 Jan  1  2025 etc"));
    }

    @Test
    void aLongNameThatIsNotAListingLineCountsAsOneLink() {
        assertEquals(1, SftpFiles.linkCount(null));
        assertEquals(1, SftpFiles.linkCount(""));
        assertEquals(1, SftpFiles.linkCount("h1.txt"));
        assertEquals(1, SftpFiles.linkCount("report 2 final.txt"), "a file name is not a mode string");
        assertEquals(1, SftpFiles.linkCount("-rw-r--r-- many adl adl 5 h1.txt"));
    }

    @Test
    void onlyARefusalForPermissionsCountsAsPermissionDenied() {
        assertTrue(SftpFiles.permissionDenied(new AccessDeniedException("/locked/.conf.tmp")));
        assertTrue(SftpFiles.permissionDenied(
                new IOException("wrapped", new SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied"))));
        assertFalse(SftpFiles.permissionDenied(new SftpException(SftpConstants.SSH_FX_FAILURE, "disk full")));
        assertFalse(SftpFiles.permissionDenied(new IOException("connection reset")));
    }
}
