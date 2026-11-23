package com.editora.config;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** E3 of the second Local History review: what a removal here does to what the other writer added. */
class StoreMergeRemovalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text.replace('\'', '"'));
    }

    private static JsonNode merge(ConfigSchema schema, String base, String mine, String theirs) throws Exception {
        return StoreMerge.merge(json(base), json(mine), json(theirs), StoreMerge.keysFor(schema));
    }

    private static final String A = "{'timestamp':1,'sha256':'aa','reason':'SAVE','label':''}";
    private static final String B = "{'timestamp':2,'sha256':'bb','reason':'SAVE','label':''}";

    @Test
    void aFileRemovedHereKeepsOnlyTheRevisionsTheOtherWriterAdded() throws Exception {
        assertEquals(
                json("{'byProject':{'':{'/g':[" + A + "],'/f':[" + B + "]}}}"),
                merge(
                        ConfigSchema.HISTORY,
                        "{'byProject':{'':{'/f':[" + A + "]}}}",
                        "{'byProject':{'':{'/g':[" + A + "]}}}",
                        "{'byProject':{'':{'/f':[" + A + "," + B + "]}}}"),
                "renamed here: the other writer's new revision of the old name survives, the moved one does not");
    }

    @Test
    void aFileRemovedHereThatTheOtherWriterOnlyEditedStaysRemoved() throws Exception {
        String relabelled = "{'timestamp':1,'sha256':'aa','reason':'SAVE','label':'theirs'}";
        assertEquals(
                json("{'byProject':{'':{}}}"),
                merge(
                        ConfigSchema.HISTORY,
                        "{'byProject':{'':{'/f':[" + A + "]}}}",
                        "{'byProject':{'':{}}}",
                        "{'byProject':{'':{'/f':[" + relabelled + "]}}}"));
    }

    @Test
    void aProjectRemovedHereKeepsWhatTheOtherWriterAddedToIt() throws Exception {
        assertEquals(
                json("{'byProject':{'p':{'/f':[" + B + "]}}}"),
                merge(
                        ConfigSchema.HISTORY,
                        "{'byProject':{'p':{'/f':[" + A + "]}}}",
                        "{'byProject':{}}",
                        "{'byProject':{'p':{'/f':[" + A + "," + B + "]}}}"));
    }

    @Test
    void revisionsThatShareTimeContentAndReasonAreStillMergedOneByOne() throws Exception {
        String labelled = "{'timestamp':1,'sha256':'aa','reason':'SAVE','label':'x'}";
        String theirs = "{'timestamp':3,'sha256':'cc','reason':'SAVE','label':''}";
        assertEquals(
                json("{'byProject':{'':{'/f':[" + A + "," + labelled + "," + B + "," + theirs + "]}}}"),
                merge(
                        ConfigSchema.HISTORY,
                        "{'byProject':{'':{'/f':[" + A + "," + labelled + "]}}}",
                        "{'byProject':{'':{'/f':[" + A + "," + labelled + "," + B + "]}}}",
                        "{'byProject':{'':{'/f':[" + A + "," + labelled + "," + theirs + "]}}}"),
                "both additions survive instead of the list becoming one value");
    }

    @Test
    void otherStoresStillTreatARepeatedIdentityAsOneValue() throws Exception {
        String n1 = "{'id':'n','body':'1'}";
        String n2 = "{'id':'n','body':'2'}";
        String n3 = "{'id':'m','body':'3'}";
        assertEquals(
                json("{'byProject':{'p':{'/a':[" + n1 + "," + n2 + "]}}}"),
                merge(
                        ConfigSchema.NOTES,
                        "{'byProject':{'p':{'/a':[]}}}",
                        "{'byProject':{'p':{'/a':[" + n1 + "," + n2 + "]}}}",
                        "{'byProject':{'p':{'/a':[" + n3 + "]}}}"));
    }
}
