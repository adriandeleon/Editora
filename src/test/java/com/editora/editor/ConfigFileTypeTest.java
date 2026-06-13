package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ConfigFileTypeTest {

    @Test
    void dockerfileByNameAndTag() {
        assertEquals("dockerfile", ConfigFileType.resolve("Dockerfile"));
        assertEquals("dockerfile", ConfigFileType.resolve("Containerfile"));
        assertEquals("dockerfile", ConfigFileType.resolve("Dockerfile.dev"));
        assertEquals("dockerfile", ConfigFileType.resolve("/srv/app/Dockerfile"));
    }

    @Test
    void dotenvVariants() {
        assertEquals("dotenv", ConfigFileType.resolve(".env"));
        assertEquals("dotenv", ConfigFileType.resolve(".env.local"));
        assertEquals("dotenv", ConfigFileType.resolve(".env.production"));
        assertEquals("dotenv", ConfigFileType.resolve("config.env"));
        assertEquals("dotenv", ConfigFileType.resolve("/app/.env.test"));
    }

    @Test
    void sshConfigByNameAndLocation() {
        assertEquals("ssh-config", ConfigFileType.resolve("ssh_config"));
        assertEquals("ssh-config", ConfigFileType.resolve("sshd_config"));
        assertEquals("ssh-config", ConfigFileType.resolve("/home/u/.ssh/config"));
        assertEquals("ssh-config", ConfigFileType.resolve("/home/u/.ssh/config.d/work"));
        // Windows-style separators are normalized.
        assertEquals("ssh-config", ConfigFileType.resolve("C:\\Users\\u\\.ssh\\config"));
        // A bare "config" with no .ssh ancestor is NOT ssh-config.
        assertNull(ConfigFileType.resolve("config"));
        assertNull(ConfigFileType.resolve("/etc/myapp/config"));
    }

    @Test
    void gitConfigByNameAndLocation() {
        assertEquals("git-config", ConfigFileType.resolve(".gitconfig"));
        assertEquals("git-config", ConfigFileType.resolve("/etc/gitconfig"));
        assertEquals("git-config", ConfigFileType.resolve("/repo/.git/config"));
        // "config" under .git, not .ssh.
        assertEquals("git-config", ConfigFileType.resolve("/repo/.git/config"));
    }

    @Test
    void crontabAndCaddyfile() {
        assertEquals("crontab", ConfigFileType.resolve("crontab"));
        assertEquals("crontab", ConfigFileType.resolve("backup.cron"));
        assertEquals("crontab", ConfigFileType.resolve("/etc/cron.d/backup"));
        assertEquals("caddyfile", ConfigFileType.resolve("Caddyfile"));
        assertEquals("caddyfile", ConfigFileType.resolve("site.caddyfile"));
    }

    @Test
    void fstabAndHostsCollisionGuard() {
        assertEquals("fstab", ConfigFileType.resolve("fstab"));
        assertEquals("fstab", ConfigFileType.resolve("/etc/fstab"));
        assertEquals("hosts", ConfigFileType.resolve("/etc/hosts"));
        // A bare "hosts" (e.g. an Ansible inventory) must NOT be treated as /etc/hosts.
        assertNull(ConfigFileType.resolve("hosts"));
        assertNull(ConfigFileType.resolve("/project/inventory/hosts"));
    }

    @Test
    void debianFormats() {
        // deb822 — APT .sources, .dsc, debian/control, debian/copyright, apt preferences.
        assertEquals("deb822", ConfigFileType.resolve("/etc/apt/sources.list.d/debian.sources"));
        assertEquals("deb822", ConfigFileType.resolve("hello_2.10-3.dsc"));
        assertEquals("deb822", ConfigFileType.resolve("/work/pkg/debian/control"));
        assertEquals("deb822", ConfigFileType.resolve("/work/pkg/debian/copyright"));
        assertEquals("deb822", ConfigFileType.resolve("/etc/apt/preferences.d/pin"));
        // A bare "control"/"copyright" with no debian ancestor is not deb822.
        assertNull(ConfigFileType.resolve("control"));
        assertNull(ConfigFileType.resolve("/src/copyright"));

        // Classic one-line APT sources (a .list under sources.list.d, or the file itself).
        assertEquals("apt-sources", ConfigFileType.resolve("/etc/apt/sources.list"));
        assertEquals("apt-sources", ConfigFileType.resolve("/etc/apt/sources.list.d/ppa.list"));

        // ifupdown network config.
        assertEquals("interfaces", ConfigFileType.resolve("/etc/network/interfaces"));
        assertEquals("interfaces", ConfigFileType.resolve("/etc/network/interfaces.d/eth0"));
        // A bare "interfaces" elsewhere is not the network config.
        assertNull(ConfigFileType.resolve("interfaces"));

        // Debian packaging changelog — only debian/changelog, not a generic CHANGELOG.
        assertEquals("debian-changelog", ConfigFileType.resolve("/work/pkg/debian/changelog"));
        assertNull(ConfigFileType.resolve("CHANGELOG.md"));
        assertNull(ConfigFileType.resolve("/project/changelog"));
    }

    @Test
    void nullAndOrdinaryFilesAreNotSpecial() {
        assertNull(ConfigFileType.resolve(null));
        assertNull(ConfigFileType.resolve(""));
        assertNull(ConfigFileType.resolve("Main.java"));
        assertNull(ConfigFileType.resolve("/src/app.py"));
    }
}
