package com.editora.cron;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for splitting crontab text into assignments + jobs. */
class CrontabTest {

    @Test
    void parsesTheExampleFile() {
        String text = """
                # deploy crontab
                MAILTO=ops@acme.com

                */15 * * * *    /opt/poll.sh
                30 2 * * 1-5    /opt/backup.sh
                0 0 1 * *       /opt/report.sh --monthly
                @reboot         /opt/warmcache.sh

                # bad line below
                99 * * * *      /opt/oops.sh
                """;
        Crontab c = Crontab.parse(text);

        assertEquals(1, c.assignments().size());
        Crontab.Assignment a = c.assignments().get(0);
        assertEquals("MAILTO", a.name());
        assertEquals("ops@acme.com", a.value());

        List<Crontab.Job> jobs = c.jobs();
        assertEquals(5, jobs.size());

        assertEquals("/opt/poll.sh", jobs.get(0).command());
        assertTrue(jobs.get(0).ok());
        assertEquals("Every 15 minutes", jobs.get(0).expr().describe());

        assertEquals("/opt/report.sh --monthly", jobs.get(2).command());

        assertEquals("@reboot", jobs.get(3).rawSchedule());
        assertEquals("/opt/warmcache.sh", jobs.get(3).command());
        assertTrue(jobs.get(3).expr().isReboot());

        Crontab.Job bad = jobs.get(4);
        assertFalse(bad.ok());
        assertTrue(bad.error().contains("99"));
        assertEquals(10, bad.line());
    }

    @Test
    void quotedAssignmentValueIsUnwrapped() {
        Crontab c = Crontab.parse("MAILTO=\"\"\nPATH='/usr/bin:/bin'\n");
        assertEquals(2, c.assignments().size());
        assertEquals("", c.assignments().get(0).value());
        assertEquals("/usr/bin:/bin", c.assignments().get(1).value());
        assertTrue(c.jobs().isEmpty());
    }

    @Test
    void handlesExtraWhitespaceAndTabs() {
        Crontab c = Crontab.parse("  30\t2 * * 1-5\t/opt/backup.sh  \n");
        assertEquals(1, c.jobs().size());
        assertEquals("/opt/backup.sh", c.jobs().get(0).command());
        assertTrue(c.jobs().get(0).ok());
    }

    @Test
    void tooFewFieldsIsAnError() {
        Crontab c = Crontab.parse("* * * /opt/x.sh\n");
        assertEquals(1, c.jobs().size());
        assertFalse(c.jobs().get(0).ok());
    }
}
