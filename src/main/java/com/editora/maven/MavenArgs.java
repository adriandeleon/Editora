package com.editora.maven;

import java.util.ArrayList;
import java.util.List;

/** Builds the goal/phase argv tail: the given goal(s)-or-phase(s) tokens plus a trailing
 *  {@code -P<a>,<b>} flag when one or more profiles are active. */
public final class MavenArgs {

    private MavenArgs() {}

    public static List<String> build(List<String> goalsOrPhases, List<String> activeProfiles) {
        List<String> out = new ArrayList<>(goalsOrPhases);
        if (activeProfiles != null && !activeProfiles.isEmpty()) {
            out.add("-P" + String.join(",", activeProfiles));
        }
        return out;
    }
}
