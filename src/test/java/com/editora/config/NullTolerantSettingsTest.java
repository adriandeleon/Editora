package com.editora.config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A settings or session file is edited by hand, merged by sync and written by older builds: a text, list or
 * map entry can come back as {@code null}. Nothing that reads {@link Settings} or {@link WorkspaceState}
 * checks for that — the two classes promise text (possibly empty) and collections (possibly empty) instead.
 * This holds them to it for every such property.
 */
class NullTolerantSettingsTest {

    private static boolean nullable(Class<?> type) {
        return type == String.class || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    private static Method getterFor(Class<?> bean, Method setter) {
        String suffix = setter.getName().substring(3);
        try {
            Method getter = bean.getMethod("get" + suffix);
            return nullable(getter.getReturnType()) ? getter : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** The properties whose setter, handed {@code null}, leaves the getter returning {@code null}. */
    private static List<String> nullAfterSetNull(Object bean, int atLeast) throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Method setter : bean.getClass().getMethods()) {
            if (!setter.getName().startsWith("set")
                    || setter.getParameterCount() != 1
                    || !nullable(setter.getParameterTypes()[0])) {
                continue;
            }
            Method getter = getterFor(bean.getClass(), setter);
            if (getter == null) {
                continue;
            }
            setter.invoke(bean, (Object) null);
            checked++;
            if (getter.invoke(bean) == null) {
                problems.add(setter.getName());
            }
        }
        assertTrue(checked >= atLeast, "the properties were found: " + checked);
        return problems;
    }

    /**
     * The settings whose value is handed on as it is. Their readers take {@code null} for "not set" themselves
     * (the theme and keymap names are normalised where they are applied, the key-binding maps where they are
     * read), so they are not held to the promise here. A new text setting belongs on this list only for the
     * same reason.
     */
    private static final List<String> PASSED_ON_AS_IS = List.of(
            "setAutoSave",
            "setEditorTheme",
            "setFontFamily",
            "setKeybindings",
            "setKeybindingsMac",
            "setKeymap",
            "setTheme",
            "setTodoPriorityCriticalColor",
            "setTodoPriorityHighColor",
            "setTodoPriorityLowColor",
            "setTodoPriorityMediumColor",
            "setTodoTagColor");

    @Test
    void noSettingsSetterLetsANullThrough() throws Exception {
        List<String> through = new ArrayList<>(nullAfterSetNull(new Settings(), 80));
        through.removeAll(PASSED_ON_AS_IS);
        assertEquals(List.of(), through);
    }

    @Test
    void noSessionSetterLetsANullThrough() throws Exception {
        assertEquals(List.of(), nullAfterSetNull(new WorkspaceState(), 20));
        assertEquals(List.of(), nullAfterSetNull(new WorkspaceState.OpenFile(), 1));
    }

    @Test
    void aMissingChoiceFallsBackToItsDefaultRatherThanToNothing() {
        Settings s = new Settings();
        s.setSpellLanguage(null);
        assertEquals("en_US", s.getSpellLanguage());
        s.setSpellLanguage("   ");
        assertEquals("en_US", s.getSpellLanguage());
        s.setSyncBranch(null);
        assertEquals("main", s.getSyncBranch());
        s.setSyncBranch("  ");
        assertEquals("main", s.getSyncBranch());
        s.setSyncBranch(" trunk ");
        assertEquals("trunk", s.getSyncBranch());
        s.setSyncRepoUrl("  git@example.org:me/sync.git ");
        assertEquals("git@example.org:me/sync.git", s.getSyncRepoUrl());
        s.setIndentStyle(null);
        assertEquals("detect", s.getIndentStyle());
        s.setInlayHintMode(null);
        assertEquals("literals", s.getInlayHintMode());
        s.setTodoGroupBy(null);
        assertEquals("FILE", s.getTodoGroupBy());
        s.setFillColumn(0);
        assertEquals(com.editora.editops.Filler.DEFAULT_FILL_COLUMN, s.getFillColumn());
        s.setFillColumn(Settings.MAX_FILL_COLUMN + 500);
        assertEquals(Settings.MAX_FILL_COLUMN, s.getFillColumn());
        s.setPdfCodeFontSize(-3);
        assertTrue(Settings.PDF_CODE_FONT_SIZES.contains(s.getPdfCodeFontSize()), "a size the export offers");

        WorkspaceState w = new WorkspaceState();
        String flow = w.getProjectMapFlow();
        w.setProjectMapFlow("something else");
        w.setProjectMapFlow(null);
        assertEquals(flow, w.getProjectMapFlow(), "the flow a new session starts with");
    }

    @Test
    void anUnsetAuthorIsTheLoginNameAndTheBundledUrlsStandInForBlankOnes() {
        Settings s = new Settings();
        s.setAuthorName(null);
        assertEquals("", s.getAuthorNameRaw(), "nothing is stored");
        assertEquals(System.getProperty("user.name", ""), s.getAuthorName(), "templates still get a name");
        s.setAuthorName("  ");
        assertEquals(System.getProperty("user.name", ""), s.getAuthorName());
        s.setAuthorName("Ada");
        assertEquals("Ada", s.getAuthorName());

        s.setPluginRegistryUrl(null);
        assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, s.getPluginRegistryUrl());
        assertEquals("", s.getPluginRegistryUrlRaw());
        s.setPluginRegistryUrl("  " + Settings.DEFAULT_PLUGIN_REGISTRY + " ");
        assertEquals("", s.getPluginRegistryUrlRaw(), "the bundled URL is not stored: a later default applies");
        s.setPluginRegistryUrl("https://plugins.example.org/index.json");
        assertEquals("https://plugins.example.org/index.json", s.getPluginRegistryUrl());

        s.setMavenArchetypeCatalogUrl(null);
        assertEquals(Settings.DEFAULT_MAVEN_ARCHETYPE_CATALOG, s.getMavenArchetypeCatalogUrl());
        assertEquals("", s.getMavenArchetypeCatalogUrlRaw());
        s.setMavenArchetypeCatalogUrl("   ");
        assertEquals(Settings.DEFAULT_MAVEN_ARCHETYPE_CATALOG, s.getMavenArchetypeCatalogUrl());
    }

    @Test
    void aToolbarLayoutHandedInIsCopiedSoLaterEditsToTheCallersListDoNotReachTheSettings() {
        Settings s = new Settings();
        List<String> mine = new ArrayList<>(List.of("file.save", "|"));
        s.setToolbarLayout(mine);
        mine.add("edit.undo");
        assertEquals(List.of("file.save", "|"), s.getToolbarLayout());
        s.setToolbarLayout(null);
        assertEquals(List.of(), s.getToolbarLayout());
    }
}
