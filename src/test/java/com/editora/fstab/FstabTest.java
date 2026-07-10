package com.editora.fstab;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for fstab parsing + human-readable decoding. */
class FstabTest {

    @Test
    void parsesAndDecodesTheExampleFile() {
        String text = """
                # <device>       <mount>  <type>  <options>              <dump> <pass>
                UUID=abc-123     /        ext4    defaults,noatime       0      1
                /dev/sda2        /home    ext4    rw,nodev,nosuid        0      2
                /dev/sdb1        none     swap    sw                     0      0
                //nas/media      /mnt/tv  cifs    ro,_netdev,nofail      0      0
                """;
        List<FstabEntry> e = Fstab.parse(text);
        assertEquals(4, e.size());

        FstabEntry root = e.get(0);
        assertTrue(root.ok());
        assertEquals("Mount the filesystem with UUID abc-123 at / as ext4", FstabDescribe.summary(root));
        assertTrue(FstabDescribe.options(root).contains("access times not updated"));
        assertEquals("fsck-checked first (root) · not backed up by dump", FstabDescribe.checkLine(root));

        FstabEntry home = e.get(1);
        assertEquals("Mount device /dev/sda2 at /home as ext4", FstabDescribe.summary(home));
        assertTrue(FstabDescribe.options(home).contains("device files not interpreted"));
        assertTrue(FstabDescribe.checkLine(home).startsWith("fsck-checked after root"));

        FstabEntry swap = e.get(2);
        assertTrue(swap.isSwap());
        assertEquals("Swap space on device /dev/sdb1", FstabDescribe.summary(swap));

        FstabEntry cifs = e.get(3);
        assertEquals("Mount the SMB/CIFS share //nas/media at /mnt/tv as cifs", FstabDescribe.summary(cifs));
        assertTrue(FstabDescribe.options(cifs).contains("waits for the network before mounting"));
        assertTrue(FstabDescribe.options(cifs).contains("boot continues even if the device is missing"));
    }

    @Test
    void defaultsWhenDumpPassOmitted() {
        List<FstabEntry> e = Fstab.parse("tmpfs /tmp tmpfs defaults\n");
        assertEquals(1, e.size());
        assertTrue(e.get(0).ok());
        assertEquals(0, e.get(0).dump());
        assertEquals(0, e.get(0).pass());
        assertEquals("the tmpfs virtual filesystem", FstabDescribe.device("tmpfs"));
    }

    @Test
    void decodesKeyValueOptions() {
        List<FstabEntry> e = Fstab.parse("/dev/sdc1 /data vfat uid=1000,gid=1000,umask=022 0 0\n");
        List<String> opts = FstabDescribe.options(e.get(0));
        assertTrue(opts.contains("owned by user id 1000"));
        assertTrue(opts.contains("owned by group id 1000"));
        assertTrue(opts.contains("permission mask 022"));
    }

    @Test
    void unknownOptionPassesThrough() {
        List<FstabEntry> e = Fstab.parse("/dev/sda1 / ext4 rw,someexoticopt 0 1\n");
        assertTrue(FstabDescribe.options(e.get(0)).contains("someexoticopt"));
    }

    @Test
    void flagsTooFewColumns() {
        FstabEntry e = Fstab.parse("/dev/sda1 /\n").get(0);
        assertFalse(e.ok());
        assertTrue(e.error().contains("at least 4"));
    }

    @Test
    void flagsNonNumericPass() {
        FstabEntry e = Fstab.parse("/dev/sda1 / ext4 defaults 0 x\n").get(0);
        assertFalse(e.ok());
        assertTrue(e.error().contains("pass"));
    }

    @Test
    void decodesDeviceSpecs() {
        assertEquals("the filesystem labeled \"data\"", FstabDescribe.device("LABEL=data"));
        assertEquals("the partition with PARTUUID xy-9", FstabDescribe.device("PARTUUID=xy-9"));
        assertEquals("the NFS export server:/export", FstabDescribe.device("server:/export"));
        assertEquals("no backing device", FstabDescribe.device("none"));
    }
}
