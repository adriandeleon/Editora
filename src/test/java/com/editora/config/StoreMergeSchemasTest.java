package com.editora.config;

import java.util.List;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What two Editora processes adding to the same store at the same time end up with, store by store: each
 * store's entries are told apart by the fields that identify one, so both additions survive. Where the entries
 * have no identity, or cannot be told apart, the list is one value and this process's wins.
 */
class StoreMergeSchemasTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text.replace('\'', '"'));
    }

    private static JsonNode merge(ConfigSchema schema, String base, String mine, String theirs) throws Exception {
        return StoreMerge.merge(json(base), json(mine), json(theirs), StoreMerge.keysFor(schema));
    }

    @Test
    void twoWindowsOpeningDifferentProjectsBothStayOpenAndBothProjectsExist() throws Exception {
        assertEquals(
                json("{'projects':[{'id':'a','name':'A'},{'id':'mine','name':'Mine'},{'id':'theirs','name':'Theirs'}],"
                        + "'openProjectIds':['a','mine','theirs']}"),
                merge(
                        ConfigSchema.PROJECTS,
                        "{'projects':[{'id':'a','name':'A'}],'openProjectIds':['a']}",
                        "{'projects':[{'id':'a','name':'A'},{'id':'mine','name':'Mine'}],'openProjectIds':['a','mine']}",
                        "{'projects':[{'id':'a','name':'A'},{'id':'theirs','name':'Theirs'}],"
                                + "'openProjectIds':['a','theirs']}"));
    }

    @Test
    void aProjectsListThatIsNotOneOfTheTwoKnownListsIsOneValue() throws Exception {
        assertEquals(
                json("{'somethingElse':[1,2]}"),
                merge(
                        ConfigSchema.PROJECTS,
                        "{'somethingElse':[1]}",
                        "{'somethingElse':[1,2]}",
                        "{'somethingElse':[1,3]}"));
    }

    @Test
    void bookmarksAndBreakpointsOfOneFileAreToldApartByLine() throws Exception {
        for (ConfigSchema schema : List.of(ConfigSchema.BOOKMARKS, ConfigSchema.BREAKPOINTS)) {
            assertEquals(
                    json("{'byProject':{'':{'/a.txt':[{'line':1},{'line':5},{'line':9}]}}}"),
                    merge(
                            schema,
                            "{'byProject':{'':{'/a.txt':[{'line':1}]}}}",
                            "{'byProject':{'':{'/a.txt':[{'line':1},{'line':5}]}}}",
                            "{'byProject':{'':{'/a.txt':[{'line':1},{'line':9}]}}}"),
                    schema.name());
            // A list anywhere else in the file has no identity to merge by.
            assertEquals(
                    json("{'other':[{'line':5}]}"),
                    merge(schema, "{'other':[{'line':1}]}", "{'other':[{'line':5}]}", "{'other':[{'line':9}]}"),
                    schema.name());
        }
    }

    @Test
    void notesAreToldApartByTheirId() throws Exception {
        assertEquals(
                json("{'byProject':{'p':{'/a.txt':[{'id':'n1','body':'edited here'},{'id':'n3','body':'theirs'}]}}}"),
                merge(
                        ConfigSchema.NOTES,
                        "{'byProject':{'p':{'/a.txt':[{'id':'n1','body':'old'}]}}}",
                        "{'byProject':{'p':{'/a.txt':[{'id':'n1','body':'edited here'}]}}}",
                        "{'byProject':{'p':{'/a.txt':[{'id':'n1','body':'old'},{'id':'n3','body':'theirs'}]}}}"));
    }

    @Test
    void localHistoryRevisionsAreToldApartByTimeContentAndReason() throws Exception {
        String shared = "{'timestamp':1,'sha256':'aa','reason':'save'}";
        String mine = "{'timestamp':2,'sha256':'bb','reason':'save'}";
        String theirs = "{'timestamp':2,'sha256':'bb','reason':'checkpoint'}";
        assertEquals(
                json("{'byProject':{'':{'/a.txt':[" + shared + "," + mine + "," + theirs + "]}}}"),
                merge(
                        ConfigSchema.HISTORY,
                        "{'byProject':{'':{'/a.txt':[" + shared + "]}}}",
                        "{'byProject':{'':{'/a.txt':[" + shared + "," + mine + "]}}}",
                        "{'byProject':{'':{'/a.txt':[" + shared + "," + theirs + "]}}}"),
                "the same content saved at the same moment for two reasons is two revisions");
    }

    @Test
    void remoteConnectionsAreToldApartByHostPortAndUser() throws Exception {
        String a = "{'host':'h','port':22,'user':'ada','name':'A'}";
        String sameHostOtherUser = "{'host':'h','port':22,'user':'bob','name':'B'}";
        String otherPort = "{'host':'h','port':2222,'user':'ada','name':'C'}";
        assertEquals(
                json("{'connections':[" + a + "," + sameHostOtherUser + "," + otherPort + "]}"),
                merge(
                        ConfigSchema.CONNECTIONS,
                        "{'connections':[" + a + "]}",
                        "{'connections':[" + a + "," + sameHostOtherUser + "]}",
                        "{'connections':[" + a + "," + otherPort + "]}"));
        assertEquals(
                json("{'somewhereElse':[2]}"),
                merge(
                        ConfigSchema.CONNECTIONS,
                        "{'somewhereElse':[1]}",
                        "{'somewhereElse':[2]}",
                        "{'somewhereElse':[3]}"));
    }

    @Test
    void macrosAbbreviationsAndAgentSessionsAreToldApartByTheirOwnKey() throws Exception {
        assertEquals(
                json("{'macros':[{'id':'m1'},{'id':'m2'},{'id':'m3'}]}"),
                merge(
                        ConfigSchema.MACROS,
                        "{'macros':[{'id':'m1'}]}",
                        "{'macros':[{'id':'m1'},{'id':'m2'}]}",
                        "{'macros':[{'id':'m1'},{'id':'m3'}]}"));
        assertEquals(
                json("{'abbreviations':[{'abbreviation':'a','expansion':'A'},{'abbreviation':'b','expansion':'B'},"
                        + "{'abbreviation':'c','expansion':'C'}]}"),
                merge(
                        ConfigSchema.ABBREVIATIONS,
                        "{'abbreviations':[{'abbreviation':'a','expansion':'A'}]}",
                        "{'abbreviations':[{'abbreviation':'a','expansion':'A'},{'abbreviation':'b','expansion':'B'}]}",
                        "{'abbreviations':[{'abbreviation':'a','expansion':'A'},{'abbreviation':'c','expansion':'C'}]}"));
        assertEquals(
                json("{'sessions':[{'sessionId':'s1'},{'sessionId':'s2'},{'sessionId':'s3'}]}"),
                merge(
                        ConfigSchema.AGENT_SESSIONS,
                        "{'sessions':[{'sessionId':'s1'}]}",
                        "{'sessions':[{'sessionId':'s1'},{'sessionId':'s2'}]}",
                        "{'sessions':[{'sessionId':'s1'},{'sessionId':'s3'}]}"));
        for (ConfigSchema schema :
                List.of(ConfigSchema.MACROS, ConfigSchema.ABBREVIATIONS, ConfigSchema.AGENT_SESSIONS)) {
            assertEquals(
                    json("{'unrelated':['mine']}"),
                    merge(schema, "{'unrelated':[]}", "{'unrelated':['mine']}", "{'unrelated':['theirs']}"),
                    schema.name());
        }
    }

    @Test
    void recentFilesAndSearchHistoryAreSetsOfStrings() throws Exception {
        assertEquals(
                json("{'files':['/mine','/a','/theirs']}"),
                merge(
                        ConfigSchema.RECENT,
                        "{'files':['/a']}",
                        "{'files':['/mine','/a']}",
                        "{'files':['/theirs','/a']}"));
        assertEquals(
                json("{'queries':['mine','old','theirs']}"),
                merge(
                        ConfigSchema.SEARCH_HISTORY,
                        "{'queries':['old']}",
                        "{'queries':['mine','old']}",
                        "{'queries':['theirs','old']}"));
        assertEquals(
                json("{'notQueries':['mine']}"),
                merge(
                        ConfigSchema.SEARCH_HISTORY,
                        "{'notQueries':[]}",
                        "{'notQueries':['mine']}",
                        "{'notQueries':['theirs']}"));
        assertEquals(
                json("{'notFiles':['mine']}"),
                merge(ConfigSchema.RECENT, "{'notFiles':[]}", "{'notFiles':['mine']}", "{'notFiles':['theirs']}"));
    }

    @Test
    void aStoreWithoutEntryIdentitiesTreatsEveryListAsOneValue() throws Exception {
        assertSame(StoreMerge.Keys.NONE, StoreMerge.keysFor(null));
        assertNull(StoreMerge.keysFor(ConfigSchema.SETTINGS).entryKey(List.of("todoPatterns")));
        assertEquals(
                json("{'todoPatterns':['mine']}"),
                merge(
                        ConfigSchema.SETTINGS,
                        "{'todoPatterns':[]}",
                        "{'todoPatterns':['mine']}",
                        "{'todoPatterns':['theirs']}"));
        // No keys given at all is the same as "no identities".
        assertEquals(
                json("{'l':['mine']}"),
                StoreMerge.merge(json("{'l':[]}"), json("{'l':['mine']}"), json("{'l':['theirs']}"), null));
    }

    @Test
    void entriesMissingTheirIdentityOrSharingOneMakeTheListOneValue() throws Exception {
        // An entry with no id, a null id, an id that is itself a list, an entry that is not an object, and
        // two entries with one id: none of these lists can be merged entry by entry.
        for (String theirs : List.of(
                "{'macros':[{'name':'no id'}]}",
                "{'macros':[{'id':null}]}",
                "{'macros':[{'id':['a','b']}]}",
                "{'macros':['just text']}",
                "{'macros':[{'id':'dup'},{'id':'dup'}]}")) {
            assertEquals(
                    json("{'macros':[{'id':'mine'}]}"),
                    merge(ConfigSchema.MACROS, "{'macros':[]}", "{'macros':[{'id':'mine'}]}", theirs),
                    theirs);
        }
        // The same for a set of strings that holds something other than a string.
        for (String theirs : List.of("{'files':[null]}", "{'files':[{'path':'/x'}]}", "{'files':['/x','/x']}")) {
            assertEquals(
                    json("{'files':['/mine']}"),
                    merge(ConfigSchema.RECENT, "{'files':[]}", "{'files':['/mine']}", theirs),
                    theirs);
        }
    }

    @Test
    void aValueThatChangedShapeOnOneSideIsAConflictThisProcessWins() throws Exception {
        assertEquals(
                json("{'macros':{'now':'an object'}}"),
                merge(
                        ConfigSchema.MACROS,
                        "{'macros':[]}",
                        "{'macros':{'now':'an object'}}",
                        "{'macros':[{'id':'t'}]}"));
        // Both sides replaced a text value by an object: there is no common object to start from, so the
        // result is the two objects together.
        assertEquals(
                json("{'k':{'mine':1,'theirs':2}}"),
                merge(ConfigSchema.SETTINGS, "{'k':'text'}", "{'k':{'mine':1}}", "{'k':{'theirs':2}}"));
    }
}
