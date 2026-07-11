package com.editora.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.dockerfile.Dockerfile;
import com.editora.editor.DockerfilePreview;
import com.editora.editor.SshConfigPreview;
import com.editora.editor.SystemdPreview;
import com.editora.sshconfig.SshConfig;
import com.editora.systemd.SystemdUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders the systemd / ssh-config / Dockerfile preview nodes on the FX thread and checks decoded content. */
@Tag("fx")
class ConfigPreviewsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void systemdTimerDecodesScheduleAndDirectives() throws Exception {
        String text = """
                [Unit]
                Description=Nightly backup timer
                [Timer]
                OnCalendar=Mon..Fri *-*-* 02:30:00
                Persistent=true
                Unit=backup.service
                [Install]
                WantedBy=timers.target
                """;
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox n = SystemdPreview.content(SystemdUnit.parse(text), LocalDateTime.of(2026, 7, 10, 9, 41), 700);
            n.applyCss();
            return collect(n);
        });
        String all = String.join("\n", labels);
        assertTrue(all.contains("Timer — Nightly backup timer"), all);
        assertTrue(all.contains("At 02:30, Monday through Friday"), all);
        assertTrue(all.contains("triggers: backup.service"), all);
        assertTrue(labels.stream().anyMatch(s -> s.startsWith("→ ")), all); // next-run line
    }

    @Test
    void sshConfigSummarizesHost() throws Exception {
        String text = "Host web\n  HostName example.com\n  Port 2222\n  User deploy\n  ForwardAgent yes\n";
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox n = SshConfigPreview.content(SshConfig.parse(text), 700);
            n.applyCss();
            return collect(n);
        });
        String all = String.join("\n", labels);
        assertTrue(all.contains("Connects to example.com on port 2222 as deploy"), all);
        assertTrue(all.contains("SSH agent forwarding on"), all);
    }

    @Test
    void dockerfileDigestsStages() throws Exception {
        String text = "FROM node:20 AS builder\nRUN npm ci\nFROM nginx:1.25\nEXPOSE 80\nCMD [\"nginx\"]\n";
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox n = DockerfilePreview.content(Dockerfile.parse(text), 700);
            n.applyCss();
            return collect(n);
        });
        String all = String.join("\n", labels);
        assertTrue(all.contains("Stage 1 — builder (from node:20)"), all);
        assertTrue(all.contains("Final stage (from nginx:1.25)"), all);
        assertTrue(all.contains("Exposes port 80"), all);
    }

    private static List<String> collect(Node node) {
        List<String> out = new ArrayList<>();
        walk(node, out);
        return out;
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, out);
            }
        }
    }
}
