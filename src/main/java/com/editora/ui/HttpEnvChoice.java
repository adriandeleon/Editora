package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.http.CapturedResponses;
import com.editora.http.HttpEnv;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The decisions behind a {@code .http} file's environment choice as the editor shows it — the pill beside the
 * Editor/Split/Preview toggle, the status-bar segment, the "Use environment dev" quick fix on an unresolved {@code
 * {{variable}}}, and the run warning that names the environments defining a variable that was sent empty. Pure.
 */
final class HttpEnvChoice {

    private HttpEnvChoice() {}

    /** The pill's and the status bar's text: "Env: dev", or "No environment". */
    static String label(String selected) {
        return selected == null || selected.isEmpty() ? tr("httpenv.none") : tr("httpenv.pill", selected);
    }

    /**
     * Which environments of the env files define each variable name, in the order the files list them ({@link
     * HttpEnvironments.EnvFiles#names()}). Parses the files: the worker's, or the FX thread's for a one-off.
     */
    static Map<String, List<String>> definedIn(HttpEnvironments.EnvFiles files) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : files.names()) {
            for (String key : files.variables(name).keySet()) {
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    /**
     * The variable a {@code {{…}}} reference names ({@code {{ token }}} → {@code token}), or null when it is not one
     * an environment can define: a built-in ({@code {{$uuid}}}) or a response reference ({@code
     * {{login.response.body.$.token}}}).
     */
    static @Nullable String variableOf(String reference) {
        if (reference == null || !reference.startsWith("{{") || !reference.endsWith("}}") || reference.length() < 4) {
            return null;
        }
        String name = reference.substring(2, reference.length() - 2).strip();
        if (name.isEmpty() || name.startsWith("$") || CapturedResponses.isResponseRef(name)) {
            return null;
        }
        return name;
    }

    /**
     * The environments to offer "Use environment X" for on an unresolved {@code reference}: those that define its
     * variable, other than the one {@code selected} ({@code ""}: none), in file order. Empty when none would help.
     */
    static List<String> fixesFor(String reference, String selected, Map<String, List<String>> definedIn) {
        String name = variableOf(reference);
        List<String> envs = name == null || definedIn == null ? null : definedIn.get(name);
        if (envs == null || envs.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String env : envs) {
            if (!env.equals(selected) && !out.contains(env)) {
                out.add(env);
            }
        }
        return out;
    }

    /**
     * A squiggle's reason, made specific when another environment is selected and the plain "not defined" reason
     * hides that others define the variable: "not defined in the environment staging (defined in dev)". With none
     * selected the analysis already says which define it; any other reason is kept.
     */
    static String reason(String reference, String reason, String selected, Map<String, List<String>> definedIn) {
        if (selected == null
                || selected.isEmpty()
                || !tr("http.unresolved.reason.undefined").equals(reason)) {
            return reason;
        }
        List<String> others = fixesFor(reference, selected, definedIn);
        return others.isEmpty()
                ? reason
                : tr("http.unresolved.reason.otherEnvironment", selected, String.join(", ", others));
    }

    /**
     * A run's warnings with each "{{x}} was sent as empty text: not defined …" naming the environments that define
     * {@code x} — none was selected, or the selected one lacks it — so the response says what to choose. Other
     * warnings are kept as they are.
     */
    static List<String> explainWarnings(List<String> warnings, String selected, Map<String, List<String>> definedIn) {
        if (warnings == null || warnings.isEmpty() || definedIn == null || definedIn.isEmpty()) {
            return warnings;
        }
        String undefined = tr("http.unresolved.reason.undefined");
        boolean none = selected == null || selected.isEmpty();
        List<String> out = null;
        for (int i = 0; i < warnings.size(); i++) {
            String w = warnings.get(i);
            for (String name : definedIn.keySet()) {
                String ref = "{{" + name + "}}";
                if (w == null || !w.equals(tr("http.warn.unresolved", ref, undefined))) {
                    continue;
                }
                List<String> envs = fixesFor(ref, none ? "" : selected, definedIn);
                if (envs.isEmpty()) {
                    break;
                }
                String why = none
                        ? tr("http.unresolved.reason.noEnvironment", String.join(", ", envs))
                        : tr("http.unresolved.reason.otherEnvironment", selected, String.join(", ", envs));
                if (out == null) {
                    out = new ArrayList<>(warnings);
                }
                out.set(i, tr("http.warn.unresolved", ref, why));
                break;
            }
        }
        return out == null ? warnings : List.copyOf(out);
    }

    /**
     * The disabled line the menu shows when the file has no environment to choose: no env file was found for it
     * ("No http-client.env.json beside this file"), or the env files it has define none.
     */
    static String emptyHint(HttpEnvironments.EnvFiles files) {
        boolean pub = files != null && files.publicJson() != null;
        boolean priv = files != null && files.privateJson() != null;
        if (!pub && !priv) {
            return tr("httpenv.noFile", HttpEnv.PUBLIC_FILE);
        }
        return tr("httpenv.noEnvironments", pub ? HttpEnv.PUBLIC_FILE : HttpEnv.PRIVATE_FILE);
    }

    /** The env files a file's environments come from, as named in the pill's tooltip ("" when it has none). */
    static String sources(HttpEnvironments.EnvFiles files) {
        List<String> names = new ArrayList<>(2);
        if (files != null && files.publicJson() != null) {
            names.add(HttpEnv.PUBLIC_FILE);
        }
        if (files != null && files.privateJson() != null) {
            names.add(HttpEnv.PRIVATE_FILE);
        }
        return String.join(", ", names);
    }
}
