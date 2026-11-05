package com.editora.ui;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Doctor's Python formatter row when neither Ruff nor Black is found (Python review 2, C111). */
class DoctorPythonFormatterRowTest {

    private static DoctorCheck base() {
        return DoctorCheck.checking("python.formatter", "format", "Python formatter", "ruff / black");
    }

    /** Formatting is optional: a missing formatter is a note, not a warning under Needs Attention. */
    @Test
    void aMissingFormatterIsOptional() {
        DoctorCheck row = DoctorCoordinator.formatterAbsent(base(), false);
        assertEquals(DoctorStatus.OPTIONAL, row.status());
        assertFalse(row.status().needsAttention());
    }

    /** With Format on save switched on, a save asks for one: then it is worth a warning. */
    @Test
    void formatOnSaveMakesItAWarning() {
        assertEquals(
                DoctorStatus.WARN,
                DoctorCoordinator.formatterAbsent(base(), true).status());
    }
}
