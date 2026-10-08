package com.editora.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateNamesTest {

    @Test
    void identifiersBecomeReadableLabels() {
        assertEquals("Package name", TemplateNames.humanize("packageName"));
        assertEquals("Base name", TemplateNames.humanize("base_name"));
        assertEquals("Description", TemplateNames.humanize("description"));
        assertEquals("Issue title2", TemplateNames.humanize("issueTitle2"));
        assertEquals("HTML Title", TemplateNames.humanize("HTMLTitle"));
        assertEquals("", TemplateNames.humanize(" "));
    }

    @Test
    void aProjectNameBecomesAPackageName() {
        assertEquals("my_app", TemplateNames.packageNameFor("My App"));
        assertEquals("my_app_2", TemplateNames.packageNameFor("  my-app.2 "));
        assertEquals("_3d_viewer", TemplateNames.packageNameFor("3D Viewer"));
        assertEquals("shop", TemplateNames.packageNameFor("shop"));
        assertEquals("", TemplateNames.packageNameFor("???"));
        assertEquals("", TemplateNames.packageNameFor(null));
    }
}
