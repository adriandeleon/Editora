package com.editora.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void theLinkCountOfProtocol6AttributesIsFoundBehindEveryFieldThatMayPrecedeIt() {
        ByteArrayBuffer minimal = new ByteArrayBuffer();
        minimal.putInt(SftpConstants.SSH_FILEXFER_ATTR_LINK_COUNT);
        minimal.putByte((byte) SftpConstants.SSH_FILEXFER_TYPE_REGULAR);
        minimal.putInt(2);
        assertEquals(2, SftpFiles.linkCountOfV6Attributes(minimal));

        ByteArrayBuffer full = new ByteArrayBuffer();
        full.putInt(SftpConstants.SSH_FILEXFER_ATTR_ALL); // every protocol-6 field, sub-second times included
        full.putByte((byte) SftpConstants.SSH_FILEXFER_TYPE_REGULAR);
        full.putLong(5); // size
        full.putLong(4096); // allocation size
        full.putString("adl");
        full.putString("staff");
        full.putInt(0644);
        for (int time = 0; time < 4; time++) { // access, create, modify, change
            full.putLong(1_700_000_000L);
            full.putInt(123);
        }
        full.putBytes(new byte[] {0, 0, 0, 0, 0, 0, 0, 0}); // ACL: flags + zero entries
        full.putInt(0); // attribute bits
        full.putInt(0); // … and which of them are valid
        full.putByte((byte) 0); // text hint
        full.putString("text/plain");
        full.putInt(3);
        full.putString("untranslated");
        assertEquals(3, SftpFiles.linkCountOfV6Attributes(full));
    }

    @Test
    void protocol6AttributesWithoutALinkCountSayNothing() {
        ByteArrayBuffer attributes = new ByteArrayBuffer();
        attributes.putInt(SftpConstants.SSH_FILEXFER_ATTR_SIZE | SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS);
        attributes.putByte((byte) SftpConstants.SSH_FILEXFER_TYPE_REGULAR);
        attributes.putLong(5);
        attributes.putInt(0644);
        assertNull(SftpFiles.linkCountOfV6Attributes(attributes));
    }

    @Test
    void aLeftoverListMayOnlyNameStagingFiles() {
        assertTrue(SftpFiles.isStagingName("/srv/www/.index.html.1234567890.editora-tmp"));
        assertTrue(SftpFiles.isStagingName(".conf.9.editora-tmp"));
        assertFalse(SftpFiles.isStagingName("/srv/www/index.html"));
        assertFalse(SftpFiles.isStagingName("/srv/www/notes.editora-tmp"), "staging files are hidden");
        assertFalse(SftpFiles.isStagingName("/srv/.editora-tmp/passwd"));
        assertFalse(SftpFiles.isStagingName("/srv/.editora-tmp"));
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
