package com.editora.agent.runtime;

import java.util.regex.Pattern;

/** Conservative bridge from a fresh JUnit XML testcase to a Java source method. */
public record AgentTestIdentity(
        String framework, String className, String sourceMethod, String invocation, String report, String module) {
    private static final Pattern METHOD =
            Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)(?:\\([^)]*\\))?(?:\\[[^]\\r\\n]{1,120}\\])?$");

    public static AgentTestIdentity from(AgentValidationReports.TestCase test) {
        return from(test, ".");
    }

    public static AgentTestIdentity from(AgentValidationReports.TestCase test, String module) {
        // Surefire commonly emits method(Type)[index] or method[index]. A display name that
        // cannot be mapped unambiguously to source is execution evidence, not coverage proof.
        String name = test.name();
        var matcher = METHOD.matcher(name);
        String method = matcher.matches() ? matcher.group(1) : "";
        return new AgentTestIdentity("JUNIT_XML", test.className(), method, name, test.report(), module);
    }

    public String evidenceSubject() {
        return className + "#" + invocation;
    }

    public String sourceSuffix() {
        return "/" + className.split("\\$")[0].replace('.', '/') + ".java";
    }

    public boolean matchesSource(String path, String method) {
        if (sourceMethod.isEmpty() || !sourceMethod.equals(method) || !path.endsWith(sourceSuffix())) return false;
        String normalized = report.replace('\\', '/');
        int marker = normalized.indexOf("/target/");
        if (marker < 0) marker = normalized.indexOf("/build/");
        String reportModule = marker < 0 ? "" : normalized.substring(0, marker);
        String prefix = (module.equals(".") || module.isBlank() ? "" : module + "/")
                + (reportModule.isBlank() ? "" : reportModule + "/");
        return path.startsWith(prefix);
    }
}
