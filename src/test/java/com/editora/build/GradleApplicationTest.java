package com.editora.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GradleApplicationTest {

    @Test
    void groovyAssignmentSingleQuotes() {
        String g = "plugins { id 'application' }\napplication {\n    mainClass = 'com.app.Main'\n}\n";
        assertEquals("com.app.Main", GradleApplication.mainClass(g));
    }

    @Test
    void kotlinDslAssignmentDoubleQuotes() {
        String g = "application {\n    mainClass = \"demo.App\"\n}\n";
        assertEquals("demo.App", GradleApplication.mainClass(g));
    }

    @Test
    void kotlinDslPropertySetForm() {
        String g = "application {\n    mainClass.set(\"com.example.Boot\")\n}\n";
        assertEquals("com.example.Boot", GradleApplication.mainClass(g));
    }

    @Test
    void legacyMainClassName() {
        assertEquals("legacy.Main", GradleApplication.mainClass("mainClassName = 'legacy.Main'"));
    }

    @Test
    void nestedClassNameWithDollar() {
        assertEquals("a.Outer$Inner", GradleApplication.mainClass("mainClass = 'a.Outer$Inner'"));
    }

    @Test
    void computedValueYieldsNullSoCallerFallsBack() {
        // Built from a variable — we don't evaluate Gradle, so the caller must fall back to a scan.
        assertNull(GradleApplication.mainClass("mainClass = mainClassProperty"));
        assertNull(GradleApplication.mainClass("mainClass = \"$group.Main\"".replace("$group", "\" + g + \"")));
    }

    @Test
    void noApplicationBlock() {
        assertNull(GradleApplication.mainClass("plugins { id 'java' }"));
        assertNull(GradleApplication.mainClass(""));
        assertNull(GradleApplication.mainClass(null));
    }
}
