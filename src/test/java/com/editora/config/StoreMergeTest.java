package com.editora.config;

import java.util.List;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The three-way merge behind every store write: this process's changes on top of what is on disk. */
class StoreMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text.replace('\'', '"'));
    }

    private static JsonNode merge(String base, String mine, String theirs, StoreMerge.Keys keys) throws Exception {
        return StoreMerge.merge(json(base), json(mine), json(theirs), keys);
    }

    @Test
    void aValueThisProcessDidNotTouchKeepsTheOtherWritersVersion() throws Exception {
        assertEquals(
                json("{'fontSize':19,'theme':'dark'}"),
                merge(
                        "{'fontSize':14,'theme':'light'}",
                        "{'fontSize':14,'theme':'dark'}",
                        "{'fontSize':19,'theme':'light'}",
                        StoreMerge.Keys.NONE));
    }

    @Test
    void aKeyTheOtherWriterAddedOrRemovedIsKeptThatWay() throws Exception {
        assertEquals(
                json("{'a':1,'added':true}"),
                merge("{'a':1,'gone':2}", "{'a':1,'gone':2}", "{'a':1,'added':true}", StoreMerge.Keys.NONE));
    }

    @Test
    void aKeyRemovedHereIsRemovedEvenThoughItIsStillOnDisk() throws Exception {
        assertEquals(json("{'a':1}"), merge("{'a':1,'b':2}", "{'a':1}", "{'a':1,'b':2}", StoreMerge.Keys.NONE));
    }

    @Test
    void whenBothChangedTheSameScalarTheValueSetHereWins() throws Exception {
        assertEquals(
                json("{'k':'mine'}"), merge("{'k':'old'}", "{'k':'mine'}", "{'k':'theirs'}", StoreMerge.Keys.NONE));
    }

    @Test
    void nestedObjectsAreMergedKeyByKey() throws Exception {
        assertEquals(
                json("{'keybindings':{'file.save':'Ctrl+Alt+S','edit.copy':'Ctrl+Y'}}"),
                merge(
                        "{'keybindings':{}}",
                        "{'keybindings':{'file.save':'Ctrl+Alt+S'}}",
                        "{'keybindings':{'edit.copy':'Ctrl+Y'}}",
                        StoreMerge.Keys.NONE));
    }

    @Test
    void anArrayWithNoEntryIdentityIsOneValue() throws Exception {
        // A macro's steps: interleaving two edits of a sequence would produce a macro nobody recorded.
        assertEquals(
                json("{'steps':['a','x']}"),
                merge("{'steps':['a','b']}", "{'steps':['a','x']}", "{'steps':['a','b','c']}", StoreMerge.Keys.NONE));
    }

    @Test
    void entriesOfABucketedStoreAreMergedById() throws Exception {
        StoreMerge.Keys notes = StoreMerge.keysFor(ConfigSchema.NOTES);
        JsonNode merged = merge(
                "{'byProject':{'':{'/f':[{'id':'1','body':'one'},{'id':'2','body':'two'}]}}}",
                // here: edited 1, removed 2, added 3
                "{'byProject':{'':{'/f':[{'id':'1','body':'ONE'},{'id':'3','body':'three'}]}}}",
                // there: added 4, left the rest
                "{'byProject':{'':{'/f':[{'id':'1','body':'one'},{'id':'2','body':'two'},{'id':'4','body':'four'}]}}}",
                notes);
        assertEquals(
                json(
                        "{'byProject':{'':{'/f':[{'id':'1','body':'ONE'},{'id':'3','body':'three'},{'id':'4','body':'four'}]}}}"),
                merged);
    }

    @Test
    void anEntryTheOtherWriterRemovedStaysRemovedUnlessItWasEditedHere() throws Exception {
        StoreMerge.Keys notes = StoreMerge.keysFor(ConfigSchema.NOTES);
        assertEquals(
                json("{'byProject':{'':{'/f':[{'id':'2','body':'edited here'}]}}}"),
                merge(
                        "{'byProject':{'':{'/f':[{'id':'1','body':'a'},{'id':'2','body':'b'}]}}}",
                        "{'byProject':{'':{'/f':[{'id':'1','body':'a'},{'id':'2','body':'edited here'}]}}}",
                        "{'byProject':{'':{'/f':[]}}}",
                        notes));
    }

    @Test
    void stringListsAreMergedAsSetsInThisProcesssOrder() throws Exception {
        StoreMerge.Keys recent = StoreMerge.keysFor(ConfigSchema.RECENT);
        assertEquals(
                json("{'files':['/mine','/a','/theirs']}"),
                merge("{'files':['/a','/b']}", "{'files':['/mine','/a']}", "{'files':['/theirs','/a','/b']}", recent));
    }

    @Test
    void entriesThatCannotBeToldApartFallBackToOneValue() throws Exception {
        StoreMerge.Keys recent = StoreMerge.keysFor(ConfigSchema.RECENT);
        assertEquals(
                json("{'files':['/a','/a','/mine']}"),
                merge("{'files':['/a']}", "{'files':['/a','/a','/mine']}", "{'files':['/a','/theirs']}", recent));
    }

    @Test
    void aStoreWithNoBaseIsTheUnionWithThisProcesssValuesWinning() throws Exception {
        StoreMerge.Keys projects = StoreMerge.keysFor(ConfigSchema.PROJECTS);
        JsonNode merged = StoreMerge.merge(
                JSON.createObjectNode(),
                json("{'projects':[{'id':'b','name':'B'}],'activeProjectId':'b'}"),
                json("{'projects':[{'id':'a','name':'A'}],'activeProjectId':'a'}"),
                projects);
        assertEquals(json("{'projects':[{'id':'b','name':'B'},{'id':'a','name':'A'}],'activeProjectId':'b'}"), merged);
        assertEquals(List.of("id"), projects.entryKey(List.of("projects")));
    }
}
