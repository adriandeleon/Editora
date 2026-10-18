package com.editora.doctor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoctorReportTest {

    @Test
    void rendersAHeaderThenOneLinePerRowUnderItsSection() {
        String report = DoctorReport.render(
                "Doctor",
                List.of("Editora 1.0", "Linux 6.12 (amd64) · Java 25", ""),
                List.of(
                        new DoctorReport.Line("Version Control", "OK", "Git", "git", "git version 2.47.3", ""),
                        new DoctorReport.Line(
                                "Version Control", "Warning", "GitHub CLI", "gh", "gh 2.6", "Not signed in."),
                        new DoctorReport.Line("Language Servers", "Missing", "XML", "lemminx", "", "Install it.")));
        assertEquals("""
                # Doctor
                Editora 1.0
                Linux 6.12 (amd64) · Java 25

                ## Version Control
                - [OK] Git (git): git version 2.47.3
                - [Warning] GitHub CLI (gh): gh 2.6
                  Not signed in.

                ## Language Servers
                - [Missing] XML (lemminx)
                  Install it.
                """, report);
    }
}
