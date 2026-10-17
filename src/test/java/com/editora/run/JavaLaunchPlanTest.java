package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** One launch plan for Run and Debug (review D9). */
class JavaLaunchPlanTest {

    @TempDir
    Path root;

    private static RunConfiguration cfg(String args, String vm, String dir, String env, String jdk) {
        return new RunConfiguration("App", "java", "", "demo.App", "", args, vm, dir, env, "", jdk);
    }

    private JavaLaunchPlan.Inputs local(boolean maven) {
        return new JavaLaunchPlan.Inputs(
                root, false, maven, "/jdks/maven", "", n -> "PATH".equals(n) ? "/bin" : null, "/bin");
    }

    @Test
    void aLocalMavenConfigurationRunsOnItsOwnJdkWithItsEnvironmentAndSplitArguments() {
        JavaLaunchPlan plan = JavaLaunchPlan.forConfiguration(
                        cfg("one 'two three'", "-Dx=\"a b\" -Xmx64m", "sub", "GREETING=hi P=$PATH:/x", "/jdks/own"),
                        local(true))
                .plan();
        assertEquals(root.resolve("sub"), plan.cwd());
        assertEquals("/jdks/own", plan.jdkHome());
        assertEquals(JdkToolchain.javaExecutable("/jdks/own"), plan.javaExecutable());
        assertEquals(List.of("-Dx=a b", "-Xmx64m"), plan.vmArgs(), "split as Run splits them, not by java-debug");
        assertEquals(List.of("one", "two three"), plan.args());
        assertEquals("/jdks/own", plan.environment().get("JAVA_HOME"));
        assertEquals("hi", plan.environment().get("GREETING"));
        assertEquals("/bin:/x", plan.environment().get("P"));
        assertFalse(plan.jdkEnvironment().containsKey("GREETING"), "a before-launch build gets the JDK only");
    }

    @Test
    void theMavenJdkSettingAppliesToMavenProjectsOnly() {
        assertEquals(
                "/jdks/maven",
                JavaLaunchPlan.forConfiguration(cfg("", "", "", "", ""), local(true))
                        .plan()
                        .jdkHome());
        assertEquals(
                "",
                JavaLaunchPlan.forConfiguration(cfg("", "", "", "", "/jdks/own"), local(false))
                        .plan()
                        .jdkHome());
    }

    /** On a remote host Run used the host's configured JDK and Debug whatever jdtls found: now both the host's. */
    @Test
    void aRemoteConfigurationRunsOnTheHostsConfiguredJdk() {
        var remote = new JavaLaunchPlan.Inputs(root, true, true, "/local/jdk", "/opt/jdk-25/", n -> "$" + n, "/bin");
        JavaLaunchPlan plan = JavaLaunchPlan.forConfiguration(cfg("", "", "", "A=$HOME", "/local/other"), remote)
                .plan();
        assertEquals("/opt/jdk-25/", plan.jdkHome());
        assertEquals("/opt/jdk-25/bin/java", plan.javaExecutable());
        assertEquals(Map.of("JAVA_HOME", "/opt/jdk-25/"), plan.jdkEnvironment(), "none of this machine's PATH");
        assertEquals("$HOME", plan.environment().get("A"));
        var own = new JavaLaunchPlan.Inputs(root, true, true, "/local/jdk", "", n -> "$" + n, "/bin");
        assertEquals(
                "",
                JavaLaunchPlan.forConfiguration(cfg("", "", "", "", ""), own)
                        .plan()
                        .javaExecutable());
    }

    @Test
    void anUnclosedQuoteSaysWhichField() {
        assertEquals(
                new JavaLaunchPlan.Problem(JavaLaunchPlan.Field.VM_ARGS, '"'),
                JavaLaunchPlan.forConfiguration(cfg("", "-Dx=\"open", "", "", ""), local(false))
                        .problem());
        assertEquals(
                JavaLaunchPlan.Field.ARGS,
                JavaLaunchPlan.forConfiguration(cfg("'open", "", "", "", ""), local(false))
                        .problem()
                        .field());
        assertEquals(
                JavaLaunchPlan.Field.ENV,
                JavaLaunchPlan.forConfiguration(cfg("", "", "", "A='x", ""), local(false))
                        .problem()
                        .field());
    }

    @Test
    void onlyAConfigurationNamingTheClassIsUsedByDebug() {
        RunConfiguration app = cfg("", "", "", "", "");
        RunConfiguration script = new RunConfiguration("S", "python", "a.py", "demo.App", "", "", "", "", "", "", "");
        assertSame(app, JavaLaunchPlan.configurationFor("demo.App", List.of(script, app)));
        assertNull(JavaLaunchPlan.configurationFor("demo.Other", List.of(app)), "never another class's");
        assertNull(JavaLaunchPlan.configurationFor(null, List.of(app)));
    }
}
