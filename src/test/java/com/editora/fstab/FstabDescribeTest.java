package com.editora.fstab;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full decoding tables of the fstab preview, and the malformed lines the parser has to survive. */
class FstabDescribeTest {

    private static FstabEntry one(String line) {
        List<FstabEntry> e = Fstab.parse(line);
        assertEquals(1, e.size());
        return e.get(0);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "UUID=abc|the filesystem with UUID abc",
                "uuid=abc|the filesystem with UUID abc",
                "PARTUUID=1-2|the partition with PARTUUID 1-2",
                "LABEL=root|the filesystem labeled \"root\"",
                "PARTLABEL=EFI|the partition labeled \"EFI\"",
                "//nas/share|the SMB/CIFS share //nas/share",
                "nas:/export/home|the NFS export nas:/export/home",
                "proc|the proc virtual filesystem",
                "TMPFS|the TMPFS virtual filesystem",
                "none|no backing device",
                "None|no backing device",
                "/dev/nvme0n1p2|device /dev/nvme0n1p2",
                // Neither a device node nor anything else we know: shown as written.
                "/swapfile|/swapfile",
                "overlay|overlay",
                // A colon that is not followed by a path is not an NFS export.
                "host:|host:",
                ":/x|:/x",
            })
    void describesTheDeviceColumn(String spec, String text) {
        assertEquals(text, FstabDescribe.device(spec));
    }

    @Test
    void aMissingDeviceIsSaidToBeUnspecified() {
        assertEquals("an unspecified device", FstabDescribe.device(null));
        assertEquals("an unspecified device", FstabDescribe.device(""));
    }

    @Test
    void theSummaryNamesTheTypeUnlessItIsAutoDetected() {
        assertEquals(
                "Mount device /dev/sdb1 at /mnt/usb (auto-detected type)",
                FstabDescribe.summary(one("/dev/sdb1 /mnt/usb auto noauto,user 0 0")));
        assertEquals(
                "Mount device /dev/sdb1 at /mnt/usb (auto-detected type)",
                FstabDescribe.summary(one("/dev/sdb1 /mnt/usb AUTO noauto 0 0")));
        assertEquals(
                "Mount the NFS export nas:/srv at /srv as nfs4",
                FstabDescribe.summary(one("nas:/srv /srv nfs4 rw 0 0")));
        assertEquals("Swap space on /swapfile", FstabDescribe.summary(one("/swapfile none SWAP sw 0 0")));
        // An entry built without a type at all reads the same as "auto".
        FstabEntry untyped = new FstabEntry("/dev/sdz", "/z", null, List.of(), 0, 0, null, 1);
        assertEquals("Mount device /dev/sdz at /z (auto-detected type)", FstabDescribe.summary(untyped));
        FstabEntry blank = new FstabEntry("/dev/sdz", "/z", "", List.of(), 0, 0, null, 1);
        assertEquals("Mount device /dev/sdz at /z (auto-detected type)", FstabDescribe.summary(blank));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "defaults|default options (read-write, auto-mount, executables allowed, setuid honored)",
                "rw|read-write",
                "ro|read-only",
                "auto|mounted automatically at boot",
                "noauto|not mounted at boot (mounted on demand)",
                "exec|executables allowed",
                "noexec|executables blocked",
                "suid|setuid/setgid bits honored",
                "nosuid|setuid/setgid bits ignored",
                "dev|device files interpreted",
                "nodev|device files not interpreted",
                "user|any user may mount",
                "users|any user may mount and unmount",
                "nouser|only root may mount",
                "owner|only the device owner may mount",
                "group|a user in the device's group may mount",
                "atime|access times updated",
                "noatime|access times not updated",
                "nodiratime|directory access times not updated",
                "relatime|access times updated only relative to modify time",
                "strictatime|access times always updated",
                "sync|I/O done synchronously",
                "async|I/O done asynchronously",
                "dirsync|directory changes done synchronously",
                "nofail|boot continues even if the device is missing",
                "_netdev|waits for the network before mounting",
                "discard|TRIM/discard enabled (for SSDs)",
                "nodiscard|TRIM/discard disabled",
                "bind|bind mount (mirrors another directory)",
                "rbind|recursive bind mount",
                "remount|remount an already-mounted filesystem",
                "x-systemd.automount|mounted on first access (systemd automount)",
                "NoExec|executables blocked",
                "uid=1000|owned by user id 1000",
                "gid=100|owned by group id 100",
                "umask=022|permission mask 022",
                "fmask=133|file permission mask 133",
                "dmask=022|directory permission mask 022",
                "mode=1777|mount permissions 1777",
                "size=2G|size limit 2G",
                "commit=60|data flushed to disk every 60 seconds",
                "iocharset=utf8|character set utf8",
                "codepage=437|character set 437",
                "compress=zstd|compression: zstd",
                "compress-force=lzo|compression: lzo",
                "subvol=@home|btrfs subvolume @home",
                "data=ordered|ext journaling mode: ordered",
                "errors=remount-ro|on errors: remount ro",
                "x-systemd.device-timeout=10s|device timeout 10s",
                "UID=7|owned by user id 7",
                // Unknown options, with or without a value, pass through as written.
                "credentials=/etc/smb.cred|credentials=/etc/smb.cred",
                "lazytime|lazytime",
                // "=x" has no key, so it is not a key=value option.
                "=x|=x",
            })
    void decodesEachMountOption(String option, String text) {
        FstabEntry e = new FstabEntry("/dev/sda1", "/", "ext4", List.of(option), 0, 0, null, 1);
        assertEquals(List.of(text), FstabDescribe.options(e));
    }

    @Test
    void optionsKeepTheirOrder() {
        assertEquals(
                List.of("read-only", "bogus", "owned by user id 5"),
                FstabDescribe.options(one("/dev/sda1 / ext4 ro,bogus,uid=5 0 0")));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0|0|never fsck-checked · not backed up by dump",
                "1|1|fsck-checked first (root) · included in dump backups",
                "0|2|fsck-checked after root · not backed up by dump",
                "2|3|fsck pass 3 · not backed up by dump",
            })
    void describesDumpAndFsckPass(int dump, int pass, String text) {
        assertEquals(text, FstabDescribe.checkLine(one("/dev/sda1 / ext4 defaults " + dump + " " + pass)));
    }

    // ---- the parser's edges ---------------------------------------------------------------------------

    @Test
    void noTextMeansNoEntries() {
        assertEquals(List.of(), Fstab.parse(null));
        assertEquals(List.of(), Fstab.parse("\n   \n# only a comment\n"));
    }

    @Test
    void aShortLineKeepsTheColumnsItHas() {
        FstabEntry lone = one("/dev/sda1");
        assertFalse(lone.ok());
        assertEquals("expected at least 4 columns, found 1", lone.error());
        assertEquals("/dev/sda1", lone.spec());
        assertEquals("", lone.mountPoint());
        assertEquals("", lone.fsType());

        FstabEntry three = one("/dev/sda1 /boot vfat");
        assertEquals("expected at least 4 columns, found 3", three.error());
        assertEquals("/boot", three.mountPoint());
        assertEquals("vfat", three.fsType());
        assertEquals(List.of(), three.options());
    }

    @Test
    void aLineWithTooManyColumnsIsFlaggedButStillDecoded() {
        FstabEntry e = one("/dev/sda1 / ext4 ro 0 1 extra");
        assertFalse(e.ok());
        assertEquals("too many columns (7)", e.error());
        assertEquals(List.of("ro"), e.options());
        assertEquals(1, e.line());
    }

    @Test
    void aNonNumericDumpIsNamedAsSuch() {
        FstabEntry dump = one("/dev/sda1 / ext4 ro x 1");
        assertEquals("non-numeric dump value \"x\"", dump.error());
        FstabEntry pass = one("/dev/sda1 / ext4 ro 0 y");
        assertEquals("non-numeric pass value \"y\"", pass.error());
        // Both wrong: the first one is reported.
        assertEquals(
                "non-numeric dump value \"x\"", one("/dev/sda1 / ext4 ro x y").error());
    }

    @Test
    void anEmptyOptionsColumnMeansDefaults() {
        FstabEntry e = one("/dev/sda1 / ext4 , 0 1");
        assertTrue(e.ok());
        assertEquals(List.of("defaults"), e.options());
        assertEquals(
                List.of("rw", "noatime"),
                one("/dev/sda1 / ext4 rw,,noatime, 0 1").options());
    }

    @Test
    void lineNumbersCountCommentsAndBlanks() {
        List<FstabEntry> e = Fstab.parse("# header\n\n/dev/sda1 / ext4 ro 0 1\n\t\n/dev/sda2 /home ext4 rw 0 2\n");
        assertEquals(3, e.get(0).line());
        assertEquals(5, e.get(1).line());
        assertEquals(List.of("a", "b", "c"), Fstab.columns("  a \t b   c "));
    }
}
