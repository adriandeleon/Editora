package com.editora.doctor;

import java.util.Collection;

/** Status counts over a set of Doctor rows — the header's "N OK · 1 warning · 2 problems" line. Pure. */
public record DoctorSummary(int ok, int warn, int missing, int optional, int disabled, int checking) {

    public static DoctorSummary of(Collection<DoctorCheck> checks) {
        int ok = 0;
        int warn = 0;
        int missing = 0;
        int optional = 0;
        int disabled = 0;
        int checking = 0;
        for (DoctorCheck c : checks) {
            switch (c.status()) {
                case OK -> ok++;
                case WARN -> warn++;
                case MISSING -> missing++;
                case OPTIONAL -> optional++;
                case DISABLED -> disabled++;
                case CHECKING -> checking++;
            }
        }
        return new DoctorSummary(ok, warn, missing, optional, disabled, checking);
    }

    /** Rows needing attention (amber + red); an optional tool that is absent is not one. */
    public int issues() {
        return warn + missing;
    }

    /** Whether any probe is still in flight. */
    public boolean pending() {
        return checking > 0;
    }
}
