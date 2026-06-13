package com.editora.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure sftp:// URI parser/formatter. */
class SftpUriTest {

    @Test
    void parsesUserHostPortPath() {
        SftpUri u = SftpUri.parse("sftp://ada@example.com:2222/srv/app/main.js");
        assertEquals("ada", u.user());
        assertEquals("example.com", u.host());
        assertEquals(2222, u.port());
        assertEquals("/srv/app/main.js", u.path());
    }

    @Test
    void defaultsPortAndNoUser() {
        SftpUri u = SftpUri.parse("sftp://host/etc/hosts");
        assertEquals("", u.user());
        assertEquals(22, u.port());
        assertEquals("/etc/hosts", u.path());
        assertEquals("host:22", u.authority());
    }

    @Test
    void noPathBecomesRoot() {
        assertEquals("/", SftpUri.parse("sftp://h").path());
        assertEquals("/", SftpUri.parse("sftp://h/").path());
    }

    @Test
    void formatRoundTripsAndOmitsDefaultPort() {
        assertEquals("sftp://ada@host/x", SftpUri.parse("sftp://ada@host:22/x").format());
        assertEquals(
                "sftp://ada@host:2222/x",
                SftpUri.parse("sftp://ada@host:2222/x").format());
        String s = "sftp://ada@host:2222/srv/app.js";
        assertEquals(s, SftpUri.parse(s).format());
    }

    @Test
    void authorityIsTheConnectionKey() {
        assertEquals("ada@host:22", SftpUri.parse("sftp://ada@host/a").authority());
        assertEquals("ada@host:22", SftpUri.parse("sftp://ada@host/b/c").authority());
    }

    @Test
    void rejectsNonSftpOrBadInput() {
        assertNull(SftpUri.parse(null));
        assertNull(SftpUri.parse("file:/x"));
        assertNull(SftpUri.parse("https://h/x"));
        assertNull(SftpUri.parse("sftp://")); // no host
        assertNull(SftpUri.parse("sftp://h:notaport/x")); // bad port
    }
}
