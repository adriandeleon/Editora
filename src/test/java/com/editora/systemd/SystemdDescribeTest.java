package com.editora.systemd;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests for the unit-kind inference and the directive glosses shown in the systemd unit preview. */
class SystemdDescribeTest {

    @ParameterizedTest
    @CsvSource({
        "Timer, timer",
        "Service, service",
        "Socket, socket",
        "Mount, mount",
        "Automount, automount",
        "Path, path",
        "Swap, swap",
    })
    void kindComesFromTheTypeSpecificSection(String section, String kind) {
        SystemdUnit u = SystemdUnit.parse("[Unit]\nDescription=x\n\n[" + section + "]\nFoo=bar\n\n[Install]\n");
        assertEquals(kind, SystemdDescribe.kind(u));
    }

    @Test
    void kindIsUnitWithoutATypeSection() {
        assertEquals("unit", SystemdDescribe.kind(SystemdUnit.parse("[Unit]\nDescription=a target\n[Install]\n")));
        assertEquals("unit", SystemdDescribe.kind(SystemdUnit.parse("")));
    }

    @Test
    void kindPrefersTheTimerOverTheServiceItCarries() {
        SystemdUnit u = SystemdUnit.parse("[Service]\nExecStart=/bin/true\n[Timer]\nOnCalendar=daily\n");
        assertEquals("timer", SystemdDescribe.kind(u));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "OnBootSec|15min|15 minutes after boot",
                "OnStartupSec|10s|10 seconds after systemd started",
                "OnActiveSec|2d|2 days after the timer is activated",
                "OnUnitActiveSec|1h 30min|1 hour 30 minutes after the unit was last activated",
                "OnUnitInactiveSec|300|5 minutes after the unit was last deactivated",
                "RandomizedDelaySec|1h|randomized delay of up to 1 hour",
                "AccuracySec|1min|accuracy window of 1 minute",
                "RestartSec|5s|wait 5 seconds between restarts",
                "TimeoutStartSec|90s|start timeout 1 minute 30 seconds",
                "TimeoutStopSec|10s|stop timeout 10 seconds",
                "TimeoutSec|2min|start/stop timeout 2 minutes",
            })
    void timeSpanDirectivesAreDecoded(String key, String value, String gloss) {
        assertEquals(gloss, SystemdDescribe.gloss(key, value));
    }

    @Test
    void onCalendarIsDescribedOrReportedInvalid() {
        assertEquals("Daily at 02:00", SystemdDescribe.gloss("OnCalendar", "*-*-* 02:00:00"));
        assertEquals(
                "invalid schedule: unknown weekday \"Funday\"", SystemdDescribe.gloss("OnCalendar", "Funday 02:00"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Description|Nightly backup|Nightly backup",
                "Documentation|man:foo(1)|documentation: man:foo(1)",
                "After|network.target|starts after: network.target",
                "Before|shutdown.target|starts before: shutdown.target",
                "Requires|a.service|requires (hard dependency): a.service",
                "Wants|b.service|wants (soft dependency): b.service",
                "Requisite|c.service|requires already-started: c.service",
                "BindsTo|d.device|bound to: d.device",
                "PartOf|e.target|part of: e.target",
                "Conflicts|f.service|conflicts with: f.service",
                "Condition|x|condition: x",
                "ConditionPathExists|/etc/foo|condition: /etc/foo",
                "Unit|backup.service|triggers: backup.service",
                "ExecStart|/opt/run.sh|runs: /opt/run.sh",
                "ExecStartPre|/opt/pre.sh|before start, runs: /opt/pre.sh",
                "ExecStartPost|/opt/post.sh|after start, runs: /opt/post.sh",
                "ExecStop|/opt/stop.sh|on stop, runs: /opt/stop.sh",
                "ExecStopPost|/opt/gone.sh|after stop, runs: /opt/gone.sh",
                "ExecReload|/opt/reload.sh|on reload, runs: /opt/reload.sh",
                "Type|oneshot|service type: oneshot",
                "User|backup|as user backup",
                "Group|staff|as group staff",
                "WorkingDirectory|/srv|working directory: /srv",
                "Restart|on-failure|restart policy: on-failure",
                "Environment|A=1|environment: A=1",
                "EnvironmentFile|/etc/default/x|environment from file: /etc/default/x",
                "StandardOutput|journal|stdout → journal",
                "StandardError|null|stderr → null",
                "PIDFile|/run/x.pid|PID file: /run/x.pid",
                "WantedBy|multi-user.target|enabled for: multi-user.target",
                "RequiredBy|g.target|required by: g.target",
                "Alias|h.service|alias: h.service",
                "Also|i.socket|also enable: i.socket",
                "ListenStream|8080|listens (TCP/stream) on 8080",
                "ListenDatagram|514|listens (UDP/datagram) on 514",
                "ListenFIFO|/run/x.fifo|listens on FIFO /run/x.fifo",
                "SocketMode|0660|socket permissions 0660",
                "What|/dev/sdb1|source: /dev/sdb1",
                "Where|/mnt/data|mount point: /mnt/data",
                "Options|noatime|options: noatime",
                "PathExists|/tmp/go|activates when path exists: /tmp/go",
                "PathChanged|/etc/x|activates when path changes: /etc/x",
                "PathModified|/etc/y|activates when path is modified: /etc/y",
                "DirectoryNotEmpty|/var/spool/x|activates when directory is non-empty: /var/spool/x",
            })
    void plainDirectivesAreGlossed(String key, String value, String gloss) {
        assertEquals(gloss, SystemdDescribe.gloss(key, value));
        // The key's case does not matter.
        assertEquals(gloss, SystemdDescribe.gloss(key.toUpperCase(Locale.ROOT), value));
    }

    @ParameterizedTest
    @CsvSource({"true", "yes", "on", "1", "' YES '", "On"})
    void truthyValuesTurnBooleanDirectivesOn(String value) {
        assertEquals("runs immediately if the last trigger was missed", SystemdDescribe.gloss("Persistent", value));
        assertEquals("wakes the system from suspend", SystemdDescribe.gloss("WakeSystem", value));
        assertEquals("stays active after the process exits", SystemdDescribe.gloss("RemainAfterExit", value));
        assertEquals("spawns a service instance per connection", SystemdDescribe.gloss("Accept", value));
    }

    @ParameterizedTest
    @CsvSource({"false", "no", "off", "0", "maybe"})
    void anythingElseTurnsThemOff(String value) {
        assertEquals("does not catch up missed triggers", SystemdDescribe.gloss("Persistent", value));
        assertEquals("one service handles all connections", SystemdDescribe.gloss("Accept", value));
        // An "off" that is also the default says nothing rather than stating the obvious.
        assertNull(SystemdDescribe.gloss("WakeSystem", value));
        assertNull(SystemdDescribe.gloss("RemainAfterExit", value));
    }

    @Test
    void anUnknownDirectiveHasNoGloss() {
        assertNull(SystemdDescribe.gloss("X-Custom", "whatever"));
        assertNull(SystemdDescribe.gloss("", ""));
    }
}
